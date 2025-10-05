package github.oftx.backgroundcamera

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import github.oftx.backgroundcamera.databinding.ActivityLoginBinding
import github.oftx.backgroundcamera.network.ApiService
import github.oftx.backgroundcamera.network.RetrofitClient
import github.oftx.backgroundcamera.network.dto.AuthRequest
import github.oftx.backgroundcamera.network.dto.ErrorResponseDto
import github.oftx.backgroundcamera.network.dto.RegisterRequest
import github.oftx.backgroundcamera.util.LogManager
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    // 【修正】使用新的方式来延迟初始化 apiService
    private val apiService: ApiService by lazy { RetrofitClient.getApiService(this) }
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        LogManager.addLog("[UI] LoginActivity created.")

        binding.loginButton.setOnClickListener {
            performLogin()
        }
        binding.registerButton.setOnClickListener {
            performRegister()
        }
    }

    private fun handleSuccessfulAuth(token: String, username: String) {
        sessionManager.saveUserSession(token, username)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun performLogin() {
        val username = binding.usernameEditText.text.toString().trim()
        val password = binding.passwordEditText.text.toString().trim()
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = apiService.login(AuthRequest(username, password))
                if (response.isSuccessful && response.body() != null) {
                    LogManager.addLog("[Auth] Login successful for user: $username")
                    Toast.makeText(this@LoginActivity, "Login Successful", Toast.LENGTH_SHORT).show()
                    handleSuccessfulAuth(response.body()!!.token, username)
                } else {
                    val errorMsg = parseError(response)
                    LogManager.addLog("[Auth] Login failed: $errorMsg")
                    Toast.makeText(this@LoginActivity, "Login failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Login exception", e)
                val errorMsg = "Network error: ${e.localizedMessage}"
                LogManager.addLog("[Auth] Login failed: $errorMsg")
                Toast.makeText(this@LoginActivity, "An error occurred: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun performRegister() {
        val username = binding.usernameEditText.text.toString().trim()
        val password = binding.passwordEditText.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = apiService.register(RegisterRequest(username, password))
                if (response.isSuccessful && response.body() != null) {
                    LogManager.addLog("[Auth] Registration successful for user: $username")
                    Toast.makeText(this@LoginActivity, "Registration Successful", Toast.LENGTH_SHORT).show()
                    handleSuccessfulAuth(response.body()!!.token, username)
                } else {
                    val errorMsg = parseError(response)
                    LogManager.addLog("[Auth] Registration failed: $errorMsg")
                    Toast.makeText(this@LoginActivity, "Registration failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Registration exception", e)
                val errorMsg = "Network error: ${e.localizedMessage}"
                LogManager.addLog("[Auth] Registration failed: $errorMsg")
                Toast.makeText(this@LoginActivity, "An error occurred: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !isLoading
        binding.registerButton.isEnabled = !isLoading
    }

    private fun parseError(response: Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            val errorResponse = gson.fromJson(errorBody, ErrorResponseDto::class.java)
            errorResponse.message ?: "An unknown error occurred."
        } catch (e: Exception) {
            "${response.code()} - ${response.message()}"
        }
    }
}