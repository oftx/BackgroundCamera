package github.oftx.backgroundcamera.network

import github.oftx.backgroundcamera.network.dto.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("/api/auth/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    @POST("/api/v1/devices/{deviceId}/bind")
    suspend fun bindDevice(
        @Header("Authorization") jwt: String,
        @Path("deviceId") deviceId: String
    ): Response<DeviceBindingResponse>

    @Multipart
    @POST("/api/v1/photos/upload")
    suspend fun uploadPhoto(
        @Header("X-Device-Token") deviceToken: String,
        @Part file: MultipartBody.Part,
        @Query("deviceId") deviceId: String,
        @Query("timestamp") timestamp: String // ISO 8601 format (e.g., 2025-10-02T10:30:00Z)
    ): Response<PhotoDto>
}
