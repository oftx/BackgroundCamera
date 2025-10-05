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

fun interface ConnectionStatusListener {
    fun onStatusChanged(status: WsConnectionStatus)
}

class WebSocketManager(
    private val deviceId: String,
    private val deviceToken: String?, // 【新增】接收设备Token
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
    private val NULL_CHAR = "\u0000"

    private var reconnectScheduler: ScheduledExecutorService? = null
    private var reconnectAttempts = 0

    fun connect() {
        // 【新增】安全检查：如果token为空，则无法连接
        if (deviceToken.isNullOrEmpty()) {
            LogManager.addLog("[WS] Connection aborted: Device token is null or empty.")
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED)
            return
        }

        if (isConnected.get() || webSocket != null) {
            LogManager.addLog("[WS] Connect called but already connected or connecting.")
            return
        }
        statusListener.onStatusChanged(WsConnectionStatus.CONNECTING)
        val request = Request.Builder().url(webSocketUrl).build()
        LogManager.addLog("[WS] Attempting to connect to $webSocketUrl")
        webSocket = client.newWebSocket(request, WebSocketListenerImpl())
    }

    fun disconnect() {
        stopReconnecting()
        if (isConnected.get()) {
            val disconnectFrame = "DISCONNECT\nreceipt:disconnect-123\n\n$NULL_CHAR"
            webSocket?.send(disconnectFrame)
            LogManager.addLog("[WS] Sent DISCONNECT frame.")
        }
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected.set(false)
        statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED)
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
        reconnectScheduler?.scheduleAtFixedRate({
            if (!isConnected.get()) {
                reconnectAttempts++
                val delay = (5.coerceAtMost(reconnectAttempts) * 1000).toLong()
                LogManager.addLog("[WS] Scheduling reconnect attempt #${reconnectAttempts}...")
                webSocket = null
                connect()
                Thread.sleep(delay)
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS)
    }

    private fun stopReconnecting() {
        reconnectScheduler?.shutdownNow()
        reconnectScheduler = null
    }

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            LogManager.addLog("[WS] Connection opened. Sending CONNECT frame with device credentials.")

            // 【核心修改】在CONNECT帧中添加设备ID和Token作为Header
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
                    reconnectAttempts = 0
                    LogManager.addLog("[WS] STOMP CONNECTED successfully.")
                    statusListener.onStatusChanged(WsConnectionStatus.CONNECTED)

                    val subId = "sub-0"
                    val destination = "/queue/device/command/$deviceId"
                    val subscribeFrame = "SUBSCRIBE\nid:$subId\ndestination:$destination\n\n$NULL_CHAR"
                    webSocket.send(subscribeFrame)
                    LogManager.addLog("[WS] Subscribed to command topic.")

                    // Device registration is now handled by the connect frame, but we can keep this for compatibility if needed.
                    // val regPayload = gson.toJson(DeviceRegistration(deviceId))
                    // val registerFrame = "SEND\ndestination:/app/device/register\ncontent-type:application/json\n\n$regPayload$NULL_CHAR"
                    // webSocket.send(registerFrame)
                    // LogManager.addLog("[WS] Sent device registration.")
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
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            LogManager.addLog("[WS] Connection closed: $code - $reason")
            isConnected.set(false)
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED)
            startReconnecting()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            LogManager.addLog("[WS] Connection failure: ${t.message}")
            isConnected.set(false)
            statusListener.onStatusChanged(WsConnectionStatus.DISCONNECTED)
            startReconnecting()
        }
    }
}