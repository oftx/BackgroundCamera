package github.oftx.backgroundcamera.network.dto

/**
 * DTO for receiving device details from the server.
 * @param id The unique ID of the device.
 * @param name The custom name set by the user for the device.
 */
data class DeviceDetailsDto(
    val id: String,
    val name: String
)