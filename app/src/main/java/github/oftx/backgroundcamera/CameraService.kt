package github.oftx.backgroundcamera

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat

class CameraService : Service() {

    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmIntent: PendingIntent

    private lateinit var orientationEventListener: OrientationEventListener

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "CameraServiceChannel"
        const val ALARM_REQUEST_CODE = 102

        @Volatile
        var isRunning = false

        /**
         * 修改：不再存储EXIF常量，而是存储设备当前的物理旋转角度。
         * 这个值将被CameraHandler用来计算最终的照片方向。
         */
        @Volatile
        var currentDeviceRotation: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d("CameraService", "Service Created")

        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                // 修改：根据设备物理角度，计算出标准的0, 90, 180, 270度旋转值
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation >= 45 && orientation < 135 -> 90
                    orientation >= 135 && orientation < 225 -> 180
                    orientation >= 225 && orientation < 315 -> 270
                    else -> 0
                }
                currentDeviceRotation = rotation
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
            Log.d("CameraService", "OrientationEventListener enabled.")
        } else {
            Log.w("CameraService", "Cannot detect orientation.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CameraService", "Service Started")
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_camera_notification)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        scheduleNextCapture()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAlarm()
        orientationEventListener.disable()
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
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}