package github.oftx.backgroundcamera.network.dto

data class DeviceBindingResponse(val deviceId: String, val authToken: String)
data class DeviceRegistration(val deviceId: String)
data class DeviceStatus(
    val isServiceRunning: Boolean,
    val captureInterval: Int,
    val selectedCameraId: String?
)
data class DeviceStatusUpdate(val deviceId: String, val status: DeviceStatus)
