package github.oftx.backgroundcamera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import github.oftx.backgroundcamera.network.RetrofitClient
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
        val (success, bytes) = takePictureAsync(context)

        Log.d("CaptureManager", "Capture finished. Success: $success")

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
            Log.w("CaptureManager", "Device not bound. Skipping upload.")
            return
        }
        val deviceId = sessionManager.getDeviceId()
        val deviceToken = sessionManager.getDeviceAuthToken() ?: run {
            Log.e("CaptureManager", "Device token is null. Cannot upload.")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val requestFile = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", "photo.jpg", requestFile)
                val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

                val response = RetrofitClient.apiService.uploadPhoto(deviceToken, body, deviceId, timestamp)
                if (response.isSuccessful) {
                    Log.i("CaptureManager", "Photo uploaded successfully: ${response.body()?.url}")
                } else {
                    Log.e("CaptureManager", "Photo upload failed: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e("CaptureManager", "Exception during photo upload", e)
            }
        }
    }
}