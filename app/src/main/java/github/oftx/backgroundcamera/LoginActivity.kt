package github.oftx.backgroundcamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import github.oftx.backgroundcamera.databinding.ActivityLoginBinding
import github.oftx.backgroundcamera.network.ApiService
import github.oftx.backgroundcamera.network.RetrofitClient
import github.oftx.backgroundcamera.network.dto.AuthRequest
import github.oftx.backgroundcamera.network.dto.RegisterRequest
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private val apiService: ApiService by lazy { RetrofitClient.apiService }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        // If already logged in, go to MainActivity
        if (sessionManager.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding.loginButton.setOnClickListener {
            performLogin()
        }
        binding.registerButton.setOnClickListener {
            performRegister()
        }
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
                    val token = response.body()!!.token
                    sessionManager.saveUserSession(token, username)
                    Toast.makeText(this@LoginActivity, "Login Successful", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "Login failed: \${response.message()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "An error occurred: \${e.message}", Toast.LENGTH_LONG).show()
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
                    val token = response.body()!!.token
                    sessionManager.saveUserSession(token, username)
                    Toast.makeText(this@LoginActivity, "Registration Successful", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "Registration failed: \${response.message()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "An error occurred: \${e.message}", Toast.LENGTH_LONG).show()
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
}
