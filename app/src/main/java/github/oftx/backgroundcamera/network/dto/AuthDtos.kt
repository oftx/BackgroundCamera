package github.oftx.backgroundcamera.network.dto

data class AuthRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val password: String)
data class AuthResponse(val token: String)
