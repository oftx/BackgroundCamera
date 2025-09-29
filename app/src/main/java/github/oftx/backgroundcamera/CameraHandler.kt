package github.oftx.backgroundcamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

object CameraHandler {

    private const val TAG = "CameraHandler"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var onCaptureComplete: ((Boolean) -> Unit)? = null
    private lateinit var appContext: Context

    data class CameraInfo(val name: String, val cameraId: String)

    /**
     * [最终版] 检索所有厂商通过标准API开放的摄像头。
     *
     * 该函数遵循Android最佳实践，能够：
     * 1. 发现所有独立的、非逻辑摄像头。
     * 2. 在支持的设备上（如Google Pixel），发现逻辑摄像头下的所有物理子摄像头。
     *
     * **重要提示**: 在许多设备上（如小米、一加等），制造商可能不会通过此标准API
     * 暴露其所有物理摄像头（如广角、微距）。这是设备本身的限制，而非代码问题。
     * 因此，本函数返回的列表是“该设备愿意向第三方应用展示的所有摄像头”。
     */
    fun getAvailableCameras(context: Context): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = mutableListOf<CameraInfo>()
        try {
            Log.d(TAG, "Querying available cameras using standard Camera2 API...")
            val allCameraIds = cameraManager.cameraIdList
            if (allCameraIds.isEmpty()) {
                Log.w(TAG, "No cameras found on this device.")
                return emptyList()
            }

            for (cameraId in allCameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外置"
                    else -> "未知"
                }

                // 检查是否为逻辑摄像头 (需要 API 28+)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {

                    val logicalCamInfo = CameraInfo("$facingStr (逻辑相机 ID $cameraId)", cameraId)
                    cameraList.add(logicalCamInfo)
                    Log.d(TAG, "Found Logical Camera: ${logicalCamInfo.name}. Checking for physical sub-cameras...")

                    // 尝试获取其下的所有物理摄像头
                    val physicalCameraIds = characteristics.physicalCameraIds
                    if (physicalCameraIds.isEmpty()) {
                        Log.w(TAG, "Device reports it as a Logical Camera, but physical camera ID list is empty. Manufacturer restriction is likely.")
                    }
                    for (physicalId in physicalCameraIds) {
                        val physicalCamInfo = CameraInfo("$facingStr 物理 (ID $physicalId)", physicalId)
                        cameraList.add(physicalCamInfo)
                        Log.d(TAG, "Found Physical Sub-camera: ${physicalCamInfo.name}")
                    }
                } else {
                    val regularCamInfo = CameraInfo("$facingStr (ID $cameraId)", cameraId)
                    cameraList.add(regularCamInfo)
                    Log.d(TAG, "Found Regular Camera: ${regularCamInfo.name}")
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera list", e)
        }

        val distinctList = cameraList.distinctBy { it.cameraId }
        Log.i(TAG, "Total unique cameras found and exposed by OEM: ${distinctList.size}")
        distinctList.forEach { Log.i(TAG, " -> ${it.name}") }
        return distinctList
    }


    @SuppressLint("MissingPermission")
    fun takePicture(context: Context, onComplete: (Boolean) -> Unit) {
        this.onCaptureComplete = onComplete
        this.appContext = context.applicationContext
        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            var cameraId = prefs.getString(MainActivity.KEY_SELECTED_CAMERA_ID, null)

            val availableCams = getAvailableCameras(context)
            if (cameraId == null || availableCams.none { it.cameraId == cameraId }) {
                Log.w(TAG, "Selected camera ID '$cameraId' is invalid or null. Finding a fallback.")
                cameraId = availableCams.firstOrNull { it.name.startsWith("后置") }?.cameraId
                    ?: availableCams.firstOrNull()?.cameraId
            }

            if (cameraId == null) {
                Log.e(TAG, "No camera available to open.")
                handleResult(false)
                return
            }
            Log.d(TAG, "Attempting to open camera ID: $cameraId")

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)

        } catch (e: Exception) { // Catch broader exceptions during setup
            Log.e(TAG, "Exception during takePicture setup", e)
            handleResult(false)
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) {
            Log.w(TAG, "ImageReader acquired null image.")
            handleResult(false)
            return@OnImageAvailableListener
        }

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        val success = saveImage(appContext, bytes)
        handleResult(success)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected.")
            closeCameraResources()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error callback. Error code: $error")
            closeCameraResources()
            handleResult(false)
        }

        override fun onClosed(camera: CameraDevice) {
            Log.d(TAG, "Camera device closed, stopping background thread.")
            stopBackgroundThread()
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: run {
                Log.e(TAG, "ImageReader surface is null before creating session.")
                handleResult(false)
                return
            }
            cameraDevice?.createCaptureSession(listOf(surface), sessionStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            handleResult(false)
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            triggerCapture()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Failed to configure session")
            handleResult(false)
        }
    }

    private fun triggerCapture() {
        try {
            val surface = imageReader?.surface ?: run { handleResult(false); return }
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(surface)
            }
            if (captureBuilder != null) {
                captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
            } else {
                handleResult(false)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to trigger capture", e)
            handleResult(false)
        }
    }

    private fun saveImage(context: Context, bytes: ByteArray): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isPublic = prefs.getBoolean(MainActivity.KEY_STORAGE_IS_PUBLIC, false)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        return if (isPublic) {
            saveToPublicDirectory(context, bytes, fileName)
        } else {
            saveToPrivateDirectory(context, bytes, fileName)
        }
    }

    private fun saveToPrivateDirectory(context: Context, bytes: ByteArray, fileName: String): Boolean {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (storageDir == null) {
            Log.e(TAG, "Private storage directory not available.")
            return false
        }
        val imageFile = File(storageDir, fileName)
        return try {
            FileOutputStream(imageFile).use { output ->
                output.write(bytes)
                Log.d(TAG, "Image saved to private dir: ${imageFile.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private image", e)
            false
        }
    }

    private fun saveToPublicDirectory(context: Context, bytes: ByteArray, fileName: String): Boolean {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "BackgroundCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert new image record into MediaStore.", e)
            null
        }

        uri?.let {
            return try {
                resolver.openOutputStream(it)?.use { stream -> stream.write(bytes) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
                Log.d(TAG, "Image saved to public gallery: $it")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save public image", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.delete(it, null, null) // Clean up pending entry on failure
                }
                false
            }
        }
        return false
    }

    private fun handleResult(success: Boolean) {
        onCaptureComplete?.invoke(success)
        onCaptureComplete = null
        closeCameraResources()
    }

    private fun closeCameraResources() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera resources", e)
        } finally {
            if (cameraDevice == null) {
                stopBackgroundThread()
            }
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}