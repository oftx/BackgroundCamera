package github.oftx.backgroundcamera.network.dto

import java.time.Instant

data class PhotoDto(
    val photoId: String,
    val deviceId: String,
    val url: String,
    val timestamp: Instant
)
