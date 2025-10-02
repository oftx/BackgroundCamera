package github.oftx.backgroundcamera.util

import android.content.Context
import android.content.SharedPreferences
import java.util.*

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "AppSessionPrefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_JWT = "user_jwt"
        private const val KEY_DEVICE_AUTH_TOKEN = "device_auth_token"
        private const val KEY_IS_BOUND = "is_bound"
        private const val KEY_USERNAME = "username"
    }

    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            saveDeviceId(deviceId)
        }
        return deviceId
    }

    private fun saveDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun saveUserSession(jwt: String, username: String) {
        prefs.edit()
            .putString(KEY_USER_JWT, jwt)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun saveDeviceBinding(deviceAuthToken: String) {
        prefs.edit()
            .putString(KEY_DEVICE_AUTH_TOKEN, deviceAuthToken)
            .putBoolean(KEY_IS_BOUND, true)
            .apply()
    }

    fun getUserJwt(): String? = prefs.getString(KEY_USER_JWT, null)
    fun getDeviceAuthToken(): String? = prefs.getString(KEY_DEVICE_AUTH_TOKEN, null)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)
    fun isDeviceBound(): Boolean = prefs.getBoolean(KEY_IS_BOUND, false)

    fun isLoggedIn(): Boolean = !getUserJwt().isNullOrEmpty()

    fun logout() {
        prefs.edit()
            .remove(KEY_USER_JWT)
            .remove(KEY_USERNAME)
            .remove(KEY_DEVICE_AUTH_TOKEN)
            .remove(KEY_IS_BOUND)
            .apply()
    }
}
