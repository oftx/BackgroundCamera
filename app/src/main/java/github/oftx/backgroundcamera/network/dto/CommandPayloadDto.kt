package github.oftx.backgroundcamera.network.dto

import com.google.gson.annotations.SerializedName

data class CommandPayload(
    @SerializedName("command") val command: String,
    @SerializedName("details") val details: Map<String, Any>?
)
