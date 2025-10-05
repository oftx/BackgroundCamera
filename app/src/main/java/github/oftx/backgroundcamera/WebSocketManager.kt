package github.oftx.backgroundcamera

import com.google.gson.Gson
import github.oftx.backgroundcamera.network.dto.CommandPayload
import github.oftx.backgroundcamera.network.dto.DeviceRegistration
import github.oftx.backgroundcamera.network.dto.DeviceStatusUpdate
import github.oftx.backgroundcamera.util.LogManager
import okhttp3.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class WsConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

// 【修改】移除函数式接口抽象方法参数的默认值
fun interface ConnectionStatusListener {
    fun onStatusChanged(status: WsConnectionStatus, reconnectDelaySeconds: Int)
}

class WebSocketManager(
    private val deviceId: String,
    private val deviceToken: String?,
    private val webSocketUrl: String,
    private val statusListener: ConnectionStatusListener,
    private val onCommandReceived: (CommandPayload) -> Unit
) {
    private val TAG = "WebSocketManager"
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isManuallyDisconnected = AtomicBoolean(false)
    private val NULL_CHAR = "\u0000"

    private var reconnectScheduler: ScheduledExecutorService? = null
    private var reconnectAttempts = 0

    fun connect() {
        isManuallyDisconnected.set(false)

        if (deviceToken.isNullOrEmpty()) {
            LogManager.addLog("[WS] Connection aborted: Device token is null or empty.")
            // 【修改】显式传递第二个参数
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, 0)
            return
        }

        if (isConnected.get() || webSocket != null) {
            LogManager.addLog("[WS] Connect called but already connected or connecting.")
            return
        }
        // 【修改】显式传递第二个参数
        statusListener.onStatusChanged(WsConnectionStatus.CONNECTING, 0)
        val request = Request.Builder().url(webSocketUrl).build()
        LogManager.addLog("[WS] Attempting to connect to $webSocketUrl")
        webSocket = client.newWebSocket(request, WebSocketListenerImpl())
    }

    fun disconnect() {
        isManuallyDisconnected.set(true)
        stopReconnecting()
        if (isConnected.get()) {
            val disconnectFrame = "DISCONNECT\nreceipt:disconnect-123\n\n$NULL_CHAR"
            webSocket?.send(disconnectFrame)
            LogManager.addLog("[WS] Sent DISCONNECT frame.")
        }
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected.set(false)
        // 【修改】显式传递第二个参数
        statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, 0)
    }

    fun sendStatusUpdate(statusUpdate: DeviceStatusUpdate) {
        if (!isConnected.get()) {
            LogManager.addLog("[WS] Failed to send status: WebSocket not connected.")
            return
        }
        val payload = gson.toJson(statusUpdate)
        val frame = "SEND\ndestination:/app/device/status\ncontent-type:application/json\n\n$payload$NULL_CHAR"
        webSocket?.send(frame)
        LogManager.addLog("[WS] Sent status update: isRunning=${statusUpdate.status.isServiceRunning}")
    }

    private fun startReconnecting() {
        if (reconnectScheduler?.isShutdown == false) return

        reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
        reconnectAttempts = 0

        scheduleNextAttempt()
    }

    private fun scheduleNextAttempt() {
        if (reconnectScheduler?.isShutdown == true) return

        reconnectAttempts++
        val delaySeconds = 5L.coerceAtMost(reconnectAttempts.toLong())

        LogManager.addLog("[WS] Scheduling reconnect attempt #${reconnectAttempts} in ${delaySeconds}s...")

        // 这里已经正确传递了第二个参数，无需修改
        statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, delaySeconds.toInt())

        reconnectScheduler?.schedule({
            if (!isConnected.get() && !isManuallyDisconnected.get()) {
                LogManager.addLog("[WS] Executing reconnect attempt #${reconnectAttempts}.")
                webSocket = null
                connect()
                scheduleNextAttempt()
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }


    private fun stopReconnecting() {
        reconnectScheduler?.shutdownNow()
        reconnectScheduler = null
        reconnectAttempts = 0
    }

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            LogManager.addLog("[WS] Connection opened. Sending CONNECT frame with device credentials.")
            val connectFrame = """
            CONNECT
            accept-version:1.2,1.1,1.0
            heart-beat:10000,10000
            X-Device-Id:$deviceId
            X-Device-Token:$deviceToken

            $NULL_CHAR
            """.trimIndent()
            webSocket.send(connectFrame)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                text.startsWith("CONNECTED") -> {
                    isConnected.set(true)
                    stopReconnecting()
                    LogManager.addLog("[WS] STOMP CONNECTED successfully.")
                    // 【修改】显式传递第二个参数
                    statusListener.onStatusChanged(WsConnectionStatus.CONNECTED, 0)

                    val subId = "sub-0"
                    val destination = "/queue/device/command/$deviceId"
                    val subscribeFrame = "SUBSCRIBE\nid:$subId\ndestination:$destination\n\n$NULL_CHAR"
                    webSocket.send(subscribeFrame)
                    LogManager.addLog("[WS] Subscribed to command topic.")
                }
                text.startsWith("MESSAGE") -> {
                    val bodyIndex = text.indexOf("\n\n")
                    if (bodyIndex != -1) {
                        val body = text.substring(bodyIndex + 2).trimEnd(NULL_CHAR[0])
                        LogManager.addLog("[WS] Received command message body: $body")
                        try {
                            val command = gson.fromJson(body, CommandPayload::class.java)
                            onCommandReceived(command)
                        } catch (e: Exception) {
                            LogManager.addLog("[WS] ERROR: Failed to parse command JSON.")
                        }
                    }
                }
                else -> {
                    LogManager.addLog("[WS] Received unknown frame: ${text.take(20)}...")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            LogManager.addLog("[WS] Connection closing: $code - $reason")
            isConnected.set(false)
            // 【修改】显式传递第二个参数
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, 0)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            LogManager.addLog("[WS] Connection closed: $code - $reason")
            isConnected.set(false)
            if (!isManuallyDisconnected.get()) {
                startReconnecting()
            } else {
                // 【修改】显式传递第二个参数
                statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, 0)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            LogManager.addLog("[WS] Connection failure: ${t.message}")
            isConnected.set(false)
            if (!isManuallyDisconnected.get()) {
                startReconnecting()
            } else {
                // 【修改】显式传递第二个参数
                statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED, 0)
            }
        }
    }
}