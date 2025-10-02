package github.oftx.backgroundcamera

import android.util.Log
import com.google.gson.Gson
import github.oftx.backgroundcamera.network.AppConfig
import github.oftx.backgroundcamera.network.dto.CommandPayload
import github.oftx.backgroundcamera.network.dto.DeviceRegistration
import github.oftx.backgroundcamera.network.dto.DeviceStatusUpdate
import okhttp3.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketManager(
    private val deviceId: String,
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
        if (isConnected.get() || webSocket != null) {
            Log.w(TAG, "Already connected or connecting.")
            return
        }
        val request = Request.Builder().url(AppConfig.WEBSOCKET_URL).build()
        webSocket = client.newWebSocket(request, WebSocketListenerImpl())
    }

    fun disconnect() {
        stopReconnecting()
        if (isConnected.get()) {
            val disconnectFrame = "DISCONNECT\nreceipt:disconnect-123\n\n$NULL_CHAR"
            webSocket?.send(disconnectFrame)
        }
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected.set(false)
    }

    fun sendStatusUpdate(statusUpdate: DeviceStatusUpdate) {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot send status update, WebSocket not connected.")
            return
        }
        val payload = gson.toJson(statusUpdate)
        val frame = "SEND\ndestination:/app/device/status\ncontent-type:application/json\n\n$payload$NULL_CHAR"
        webSocket?.send(frame)
        Log.d(TAG, "Sent status update: $payload")
    }

    private fun startReconnecting() {
        if (reconnectScheduler?.isShutdown == false) return

        reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
        reconnectAttempts = 0
        reconnectScheduler?.scheduleAtFixedRate({
            if (!isConnected.get()) {
                reconnectAttempts++
                val delay = (5.coerceAtMost(reconnectAttempts) * 1000).toLong() // Max 5 sec delay
                Log.i(TAG, "Attempting to reconnect... (Attempt #$reconnectAttempts)")
                webSocket = null // Ensure old instance is cleared
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
            Log.i(TAG, "WebSocket connection opened.")
            val connectFrame = "CONNECT\naccept-version:1.2,1.1,1.0\nheart-beat:10000,10000\n\n$NULL_CHAR"
            webSocket.send(connectFrame)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            when {
                text.startsWith("CONNECTED") -> {
                    isConnected.set(true)
                    stopReconnecting() // Successfully connected, stop retry attempts
                    reconnectAttempts = 0

                    // 1. Subscribe to command topic
                    val subId = "sub-0"
                    val destination = "/queue/device/command/$deviceId"
                    val subscribeFrame = "SUBSCRIBE\nid:$subId\ndestination:$destination\n\n$NULL_CHAR"
                    webSocket.send(subscribeFrame)
                    Log.i(TAG, "Subscribed to $destination")

                    // 2. Register device
                    val regPayload = gson.toJson(DeviceRegistration(deviceId))
                    val registerFrame = "SEND\ndestination:/app/device/register\ncontent-type:application/json\n\n$regPayload$NULL_CHAR"
                    webSocket.send(registerFrame)
                    Log.i(TAG, "Sent device registration")
                }
                text.startsWith("MESSAGE") -> {
                    // Extract JSON body from MESSAGE frame
                    val bodyIndex = text.indexOf("\n\n")
                    if (bodyIndex != -1) {
                        // The body starts after the double newline and ends before the null character
                        val body = text.substring(bodyIndex + 2).trimEnd(NULL_CHAR[0])
                        try {
                            val command = gson.fromJson(body, CommandPayload::class.java)
                            onCommandReceived(command)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse command JSON: $body", e)
                        }
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closing: $code / $reason")
            isConnected.set(false)
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closed: $code / $reason")
            isConnected.set(false)
            startReconnecting()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            isConnected.set(false)
            startReconnecting()
        }
    }
}