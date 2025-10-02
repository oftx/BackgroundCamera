package github.oftx.backgroundcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.Executors

object CameraHandler {

    private const val TAG = "CameraHandler"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var onCaptureComplete: ((Boolean, ByteArray?) -> Unit)? = null
    private lateinit var appContext: Context

    private var sensorOrientation: Int = 0
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    private const val STATE_PREVIEW = 0
    private const val STATE_WAITING_LOCK = 1
    private var state = STATE_PREVIEW

    data class CameraInfo(val name: String, val cameraId: String)

    fun getAvailableCameras(context: Context): List<CameraInfo> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraList = mutableListOf<CameraInfo>()
        try {
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
                cameraList.add(CameraInfo("$facingStr (ID $cameraId)", cameraId))
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera list", e)
        }
        return cameraList.distinctBy { it.cameraId }
    }

    @SuppressLint("MissingPermission")
    fun takePicture(context: Context, onComplete: (Boolean, ByteArray?) -> Unit) {
        if (this.onCaptureComplete != null) {
            Log.w(TAG, "Capture already in progress. Ignoring new request.")
            onComplete(false, null)
            return
        }

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
                handleResult(false, null)
                return
            }
            Log.d(TAG, "Attempting to open camera ID: $cameraId")

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            this.sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            this.lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Exception during takePicture setup", e)
            handleResult(false, null)
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) {
            Log.w(TAG, "ImageReader acquired null image.")
            handleResult(false, null)
            return@OnImageAvailableListener
        }

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        handleResult(true, bytes)
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
            handleResult(false, null)
        }
        override fun onClosed(camera: CameraDevice) {
            Log.d(TAG, "Camera device closed, stopping background thread.")
            stopBackgroundThread()
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: run { handleResult(false, null); return }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfiguration = OutputConfiguration(surface)
                val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(outputConfiguration), Executors.newSingleThreadExecutor(), sessionStateCallback)
                cameraDevice?.createCaptureSession(sessionConfiguration)
            } else {
                @Suppress("DEPRECATION")
                cameraDevice?.createCaptureSession(listOf(surface), sessionStateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            handleResult(false, null)
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            lockFocus()
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Failed to configure session")
            handleResult(false, null)
        }
    }

    private fun lockFocus() {
        try {
            val surface = imageReader?.surface ?: run { handleResult(false, null); return }
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
                handleResult(false, null)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to lock focus", e)
            handleResult(false, null)
        }
    }

    private fun captureStillPicture() {
        try {
            val device = cameraDevice ?: run { handleResult(false, null); return }
            val surface = imageReader?.surface ?: run { handleResult(false, null); return }

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                val prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val isAutoRotateEnabled = prefs.getBoolean(MainActivity.KEY_AUTO_ROTATE, true)

                val finalJpegOrientation = if (isAutoRotateEnabled) {
                    val deviceRotation = CameraService.currentDeviceRotation
                    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        (sensorOrientation - deviceRotation + 360) % 360
                    } else {
                        (sensorOrientation + deviceRotation) % 360
                    }
                } else {
                    when (prefs.getString(MainActivity.KEY_FORCED_ORIENTATION, MainActivity.VALUE_ORIENTATION_PORTRAIT)) {
                        MainActivity.VALUE_ORIENTATION_PORTRAIT -> 90
                        MainActivity.VALUE_ORIENTATION_PORTRAIT_REVERSED -> 270
                        MainActivity.VALUE_ORIENTATION_LANDSCAPE -> 0
                        MainActivity.VALUE_ORIENTATION_LANDSCAPE_REVERSED -> 180
                        else -> 90
                    }
                }
                set(CaptureRequest.JPEG_ORIENTATION, finalJpegOrientation)
            }

            val finalCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.d(TAG, "Final capture request completed.")
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Capture failed. Reason: ${failure.reason}")
                    handleResult(false, null)
                }
            }
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            captureSession?.capture(captureBuilder.build(), finalCaptureCallback, null)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to trigger final capture", e)
            handleResult(false, null)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            if (state == STATE_WAITING_LOCK) {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                if (afState == null || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    captureStillPicture()
                    state = STATE_PREVIEW
                }
            }
        }
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
            process(partialResult)
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }
    }

    private fun handleResult(success: Boolean, bytes: ByteArray?) {
        onCaptureComplete?.let {
            it(success, bytes)
            onCaptureComplete = null
        }
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