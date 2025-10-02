package github.oftx.backgroundcamera.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Represents the error response object sent from the server.
 * Example: {"status":400, "error":"Bad Request", "message":"Device is already bound to a user."}
 */
data class ErrorResponseDto(
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
)