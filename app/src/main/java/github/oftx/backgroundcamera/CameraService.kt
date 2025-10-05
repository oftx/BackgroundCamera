package github.oftx.backgroundcamera

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import github.oftx.backgroundcamera.network.AppConfig
import github.oftx.backgroundcamera.network.dto.CommandPayload
import github.oftx.backgroundcamera.network.dto.DeviceStatus
import github.oftx.backgroundcamera.network.dto.DeviceStatusUpdate
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraService : Service() {

    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmIntent: PendingIntent
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var sessionManager: SessionManager
    private var webSocketManager: WebSocketManager? = null
    private var currentWsStatus: WsConnectionStatus = WsConnectionStatus.DISCONNECTED

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "CameraServiceChannel"
        const val ALARM_REQUEST_CODE = 102

        // --- Action Constants ---
        const val ACTION_START_MONITORING = "github.oftx.backgroundcamera.ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "github.oftx.backgroundcamera.ACTION_STOP_MONITORING"
        const val ACTION_SETTINGS_UPDATED = "github.oftx.backgroundcamera.ACTION_SETTINGS_UPDATED"
        const val ACTION_WS_STATUS_UPDATE = "github.oftx.backgroundcamera.WS_STATUS_UPDATE"
        const val EXTRA_WS_STATUS = "EXTRA_WS_STATUS"
        const val ACTION_REQUEST_WS_STATUS = "github.oftx.backgroundcamera.REQUEST_WS_STATUS"
        const val ACTION_RECONNECT_WS = "github.oftx.backgroundcamera.ACTION_RECONNECT_WS"

        // --- State Variables ---
        @Volatile
        var isRunning = false // Indicates if the service object exists
        @Volatile
        var isMonitoringActive = false // Indicates if the capture alarm is scheduled

        // 【修正】将 currentDeviceRotation 定义移回此处
        @Volatile
        var currentDeviceRotation: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sessionManager = SessionManager(this)
        Log.d("CameraService", "Service Created")
        setupWebSocket()
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentDeviceRotation = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation >= 45 && orientation < 135 -> 90
                    orientation >= 135 && orientation < 225 -> 180
                    orientation >= 225 && orientation < 315 -> 270
                    else -> 0
                }
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    private fun setupWebSocket() {
        val deviceId = sessionManager.getDeviceId()
        if (sessionManager.isDeviceBound()) {
            webSocketManager?.disconnect()
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(MainActivity.KEY_SERVER_URL, AppConfig.BASE_URL) ?: AppConfig.BASE_URL
            val webSocketUrl = baseUrl.replace("http://", "ws://")
                .replace("https://", "wss://")
                .removeSuffix("/") + "/ws/websocket"

            val statusListener = ConnectionStatusListener { status ->
                currentWsStatus = status
                val intent = Intent(ACTION_WS_STATUS_UPDATE).apply {
                    putExtra(EXTRA_WS_STATUS, status.name)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }

            webSocketManager = WebSocketManager(deviceId, webSocketUrl, statusListener) { command: CommandPayload ->
                handleRemoteCommand(command.command)
            }
            webSocketManager?.connect()
        } else {
            Log.w("CameraService", "Device is not bound, WebSocket will not connect.")
            webSocketManager?.disconnect()
            webSocketManager = null
            currentWsStatus = WsConnectionStatus.DISCONNECTED
        }
    }

    private fun handleRemoteCommand(command: String) {
        when (command) {
            "take_picture" -> {
                Log.i("CameraService", "Received remote command to take picture.")
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BackgroundCamera::RemoteCommandWakeLock"
                )
                wakeLock.acquire(20 * 1000L)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        CaptureManager.performCapture(this@CameraService)
                    } finally {
                        wakeLock.release()
                    }
                }
            }
            else -> Log.w("CameraService", "Unknown remote command: $command")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CameraService", "Service command received with action: ${intent?.action}")

        // 【核心修改】所有操作都由显式Action驱动
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                if (!isMonitoringActive) {
                    isMonitoringActive = true
                    Log.i("CameraService", "Monitoring started.")
                    createNotificationChannel()
                    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.notification_title))
                        .setContentText(getString(R.string.notification_text))
                        .setSmallIcon(R.drawable.ic_camera_notification)
                        .build()
                    startForeground(NOTIFICATION_ID, notification)
                    scheduleNextCapture()
                    sendStatusUpdate()
                }
            }
            ACTION_STOP_MONITORING -> {
                if (isMonitoringActive) {
                    isMonitoringActive = false
                    Log.i("CameraService", "Monitoring stopped.")
                    cancelAlarm()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    sendStatusUpdate() // Send a final status update
                    stopSelf() // Stop the service itself
                }
            }
            ACTION_SETTINGS_UPDATED -> {
                setupWebSocket()
                sendStatusUpdate()
            }
            ACTION_RECONNECT_WS -> {
                Log.d("CameraService", "Received reconnect command.")
                webSocketManager?.connect()
            }
            ACTION_REQUEST_WS_STATUS -> {
                Log.d("CameraService", "Received status request, broadcasting current status.")
                val statusIntent = Intent(ACTION_WS_STATUS_UPDATE).apply {
                    putExtra(EXTRA_WS_STATUS, currentWsStatus.name)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(statusIntent)
            }
            else -> {
                // If service is restarted by system with a null intent, do nothing.
                Log.w("CameraService", "Service started with null or unknown intent action. No action taken.")
            }
        }

        return START_STICKY
    }

    private fun sendStatusUpdate() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val status = DeviceStatus(
            isServiceRunning = isMonitoringActive, // Report monitoring status
            captureInterval = prefs.getInt(
                MainActivity.KEY_CAPTURE_INTERVAL,
                MainActivity.DEFAULT_INTERVAL_SECONDS
            ),
            selectedCameraId = prefs.getString(MainActivity.KEY_SELECTED_CAMERA_ID, null)
        )
        val statusUpdate = DeviceStatusUpdate(sessionManager.getDeviceId(), status)
        webSocketManager?.sendStatusUpdate(statusUpdate)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAlarm()
        webSocketManager?.disconnect()
        orientationEventListener.disable()
        isMonitoringActive = false // Reset states
        isRunning = false
        Log.d("CameraService", "Service Destroyed")
    }

    private fun scheduleNextCapture() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val intervalSeconds =
            prefs.getInt(MainActivity.KEY_CAPTURE_INTERVAL, MainActivity.DEFAULT_INTERVAL_SECONDS)
        val intervalMillis = intervalSeconds * 1000L
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, CameraBroadcastReceiver::class.java)
        alarmIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                alarmIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                alarmIntent
            )
        }
        Log.d("CameraService", "Next capture scheduled in $intervalSeconds seconds")
    }

    private fun cancelAlarm() {
        if (::alarmManager.isInitialized) {
            alarmManager.cancel(alarmIntent)
            Log.d("CameraService", "Alarm canceled.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                serviceChannel
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}