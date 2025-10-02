package github.oftx.backgroundcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("CameraReceiver", "Alarm received. Starting capture process.")
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackgroundCamera::WakeLock")
        wakeLock.acquire(20 * 1000L)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                CaptureManager.performCapture(context)
            } finally {
                if (CameraService.isRunning) {
                    val serviceIntent = Intent(context, CameraService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
                wakeLock.release()
                pendingResult.finish()
            }
        }
    }
}