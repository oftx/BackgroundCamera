package github.oftx.backgroundcamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object CameraHandler {

    private const val TAG = "CameraHandler"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var onCaptureComplete: ((Boolean) -> Unit)? = null
    private lateinit var appContext: Context

    // 新增：用于存储相机传感器的物理方向
    private var sensorOrientation: Int = 0

    private const val STATE_PREVIEW = 0
    private const val STATE_WAITING_LOCK = 1
    private const val STATE_WAITING_PRECAPTURE = 2
    private const val STATE_WAITING_NON_PRECAPTURE = 3
    private const val STATE_PICTURE_TAKEN = 4
    private var state = STATE_PREVIEW

    data class CameraInfo(val name: String, val cameraId: String)

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

                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {

                    val logicalCamInfo = CameraInfo("$facingStr (逻辑相机 ID $cameraId)", cameraId)
                    cameraList.add(logicalCamInfo)
                    Log.d(TAG, "Found Logical Camera: ${logicalCamInfo.name}. Checking for physical sub-cameras...")

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
        state = STATE_PREVIEW

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

            // 新增：获取并存储传感器的方向
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            this.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Exception during takePicture setup", e)
            handleResult(false)
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) {
            Log.w(TAG, "ImageReader acquired null image.")
            return@OnImageAvailableListener
        }

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        saveImage(appContext, bytes)
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfiguration = OutputConfiguration(surface)
                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfiguration),
                    Executors.newSingleThreadExecutor(),
                    sessionStateCallback
                )
                cameraDevice?.createCaptureSession(sessionConfiguration)
            } else {
                @Suppress("DEPRECATION")
                cameraDevice?.createCaptureSession(listOf(surface), sessionStateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            handleResult(false)
        }
    }


    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            lockFocus()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Failed to configure session")
            handleResult(false)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                STATE_WAITING_LOCK -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    } else {
                        runPrecaptureSequence()
                    }
                }
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
                else -> {}
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }
    }

    private fun lockFocus() {
        try {
            val surface = imageReader?.surface ?: run { handleResult(false); return }
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            if (captureBuilder != null) {
                state = STATE_WAITING_LOCK
                captureSession?.capture(captureBuilder.build(), captureCallback, backgroundHandler)
            } else {
                handleResult(false)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to lock focus", e)
            handleResult(false)
        }
    }

    private fun runPrecaptureSequence() {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(captureBuilder!!.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to run precapture sequence", e)
            handleResult(false)
        }
    }

    private fun captureStillPicture() {
        try {
            val device = cameraDevice ?: run { handleResult(false); return }
            val surface = imageReader?.surface ?: run { handleResult(false); return }

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                // 核心修改：根据传感器方向和设备方向，计算并设置JPEG的最终方向
                val deviceRotation = CameraService.currentDeviceRotation
                val jpegOrientation = (sensorOrientation + deviceRotation + 270) % 360
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                Log.d(TAG, "Sensor: $sensorOrientation, Device: $deviceRotation, Final JPEG orientation: $jpegOrientation")
            }

            val finalCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.d(TAG, "Capture completed successfully.")
                    handleResult(true)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Capture failed. Reason: ${failure.reason}")
                    handleResult(false)
                }
            }

            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.capture(captureBuilder.build(), finalCaptureCallback, null)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to trigger final capture", e)
            handleResult(false)
        }
    }

    /**
     * 删除：此方法不再需要，因为方向已在拍照时直接设置。
     */
    // private fun setExifOrientation(...) { ... }

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
            // 删除：不再需要手动设置EXIF
            // setExifOrientation(context, imagePath = imageFile.absolutePath)
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
                // 删除：不再需要手动设置EXIF
                // setExifOrientation(context, imageUri = it)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save public image", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.delete(it, null, null)
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