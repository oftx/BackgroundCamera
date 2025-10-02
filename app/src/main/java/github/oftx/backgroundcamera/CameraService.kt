package github.oftx.backgroundcamera

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat
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

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "CameraServiceChannel"
        const val ALARM_REQUEST_CODE = 102
        const val ACTION_SETTINGS_UPDATED = "ACTION_SETTINGS_UPDATED"

        @Volatile
        var isRunning = false

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
            webSocketManager = WebSocketManager(deviceId) { command: CommandPayload ->
                handleRemoteCommand(command.command)
            }
            webSocketManager?.connect()
        } else {
            Log.w("CameraService", "Device is not bound, WebSocket will not connect.")
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
        Log.d("CameraService", "Service Started with action: ${intent?.action}")

        if (intent?.action == ACTION_SETTINGS_UPDATED) {
            if (sessionManager.isDeviceBound() && webSocketManager == null) {
                setupWebSocket()
            }
            sendStatusUpdate()
            return START_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_camera_notification)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        scheduleNextCapture()
        sendStatusUpdate()

        return START_STICKY
    }

    private fun sendStatusUpdate() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val status = DeviceStatus(
            isServiceRunning = isRunning,
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
        isRunning = false
        sendStatusUpdate()
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