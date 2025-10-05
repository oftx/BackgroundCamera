package github.oftx.backgroundcamera.network.dto

/**
 * DTO for sending a request to update the device's name.
 * @param name The new name for the device.
 */
data class UpdateDeviceNameRequestDto(val name: String)