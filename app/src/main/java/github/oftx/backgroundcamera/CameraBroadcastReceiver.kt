package github.oftx.backgroundcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

class CameraBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("CameraReceiver", "Alarm received. Starting capture process.")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackgroundCamera::WakeLock")
        
        wakeLock.acquire(15 * 1000L /* 15 seconds timeout */)

        CameraHandler.takePicture(context) { success ->
            Log.d("CameraReceiver", "Capture finished. Success: $success")

            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val showToast = prefs.getBoolean(MainActivity.KEY_SHOW_TOAST, false)
            
            if (showToast && success) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Photo captured", Toast.LENGTH_SHORT).show()
                }
            }
            
            if (CameraService.isRunning) {
                val serviceIntent = Intent(context, CameraService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            wakeLock.release()
        }
    }
}
