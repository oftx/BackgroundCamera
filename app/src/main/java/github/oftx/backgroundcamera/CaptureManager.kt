package github.oftx.backgroundcamera

import android.content.Context
import android.widget.Toast
import github.oftx.backgroundcamera.network.RetrofitClient
import github.oftx.backgroundcamera.util.LogManager // <-- Import LogManager
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

object CaptureManager {

    suspend fun performCapture(context: Context) {
        LogManager.addLog("[Capture] Starting capture process...")
        val (success, bytes) = takePictureAsync(context)

        LogManager.addLog("[Capture] Camera capture finished. Success: $success")

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val showToast = prefs.getBoolean(MainActivity.KEY_SHOW_TOAST, false)

        if (showToast && success) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Photo captured", Toast.LENGTH_SHORT).show()
            }
        }

        if (success && bytes != null) {
            uploadPhoto(context, bytes)
        }
    }

    private suspend fun takePictureAsync(context: Context): Pair<Boolean, ByteArray?> =
        suspendCancellableCoroutine { continuation ->
            CameraHandler.takePicture(context) { success, bytes ->
                if (continuation.isActive) {
                    continuation.resume(Pair(success, bytes))
                }
            }
        }

    private suspend fun uploadPhoto(context: Context, bytes: ByteArray) {
        val sessionManager = SessionManager(context)
        if (!sessionManager.isDeviceBound()) {
            LogManager.addLog("[Upload] Skipped: Device not bound.")
            return
        }
        val deviceId = sessionManager.getDeviceId()
        val deviceToken = sessionManager.getDeviceAuthToken() ?: run {
            LogManager.addLog("[Upload] ERROR: Device token is null.")
            return
        }

        LogManager.addLog("[Upload] Starting photo upload...")
        withContext(Dispatchers.IO) {
            try {
                val requestFile = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", "photo.jpg", requestFile)
                val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

                val response = RetrofitClient.apiService.uploadPhoto(deviceToken, body, deviceId, timestamp)
                if (response.isSuccessful) {
                    LogManager.addLog("[Upload] Success! URL: ${response.body()?.url}")
                } else {
                    LogManager.addLog("[Upload] FAILED: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                LogManager.addLog("[Upload] FAILED with exception: ${e.message}")
            }
        }
    }
}