package github.oftx.backgroundcamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import github.oftx.backgroundcamera.databinding.ActivityMainBinding
import github.oftx.backgroundcamera.network.ApiService
import github.oftx.backgroundcamera.network.RetrofitClient
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var sessionManager: SessionManager
    private val apiService: ApiService by lazy { RetrofitClient.apiService }

    private var cameraList: List<CameraHandler.CameraInfo> = emptyList()

    private val permissions = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                toggleService()
            } else {
                Toast.makeText(this, "必要的权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(this)
        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupListeners()
        setupCameraSpinner()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) { // Check if UI is initialized
            updateUI()
        }
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setupListeners() {
        binding.toggleServiceButton.setOnClickListener {
            if (hasPermissions()) {
                toggleService()
            } else {
                requestPermissionsLauncher.launch(this.permissions.toTypedArray())
            }
        }

        binding.checkBatteryButton.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.viewPhotosButton.setOnClickListener {
            viewPhotos()
        }

        binding.bindDeviceButton.setOnClickListener {
            bindDevice()
        }

        binding.logoutButton.setOnClickListener {
            sessionManager.logout()
            // Stop service if it's running
            if (CameraService.isRunning) {
                stopService(Intent(this, CameraService::class.java))
            }
            redirectToLogin()
        }

        binding.storageLocationGroup.setOnCheckedChangeListener { _, checkedId ->
            val isPublic = checkedId == R.id.radio_public_storage
            prefs.edit().putBoolean(KEY_STORAGE_IS_PUBLIC, isPublic).apply()

            if (isPublic && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasPermissions()) {
                Toast.makeText(this, "公共目录需要存储权限", Toast.LENGTH_SHORT).show()
                requestPermissionsLauncher.launch(this.permissions.toTypedArray())
            }
            notifyServiceOfSettingsChange()
        }

        binding.intervalEditText.doOnTextChanged { text, _, _, _ ->
            val interval = text.toString().toIntOrNull() ?: DEFAULT_INTERVAL_SECONDS
            prefs.edit().putInt(KEY_CAPTURE_INTERVAL, interval).apply()
            notifyServiceOfSettingsChange()
        }

        binding.toastSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_TOAST, isChecked).apply()
            notifyServiceOfSettingsChange()
        }

        binding.autoRotateSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_ROTATE, isChecked).apply()
            updateForcedOrientationGroupState(isChecked)
            notifyServiceOfSettingsChange()
        }

        binding.forcedOrientationGroup.setOnCheckedChangeListener { _, _ ->
            // Save is handled inside the listener
            notifyServiceOfSettingsChange()
        }

        binding.cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (cameraList.isNotEmpty()) {
                    val selectedCameraId = cameraList[position].cameraId
                    prefs.edit().putString(KEY_SELECTED_CAMERA_ID, selectedCameraId).apply()
                    notifyServiceOfSettingsChange()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindDevice() {
        val deviceId = sessionManager.getDeviceId()
        val jwt = sessionManager.getUserJwt()

        if (jwt == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_SHORT).show()
            redirectToLogin()
            return
        }

        lifecycleScope.launch {
            try {
                val response = apiService.bindDevice("Bearer $jwt", deviceId)
                if (response.isSuccessful && response.body() != null) {
                    val authToken = response.body()!!.authToken
                    sessionManager.saveDeviceBinding(authToken)
                    Toast.makeText(this@MainActivity, "Device bound successfully!", Toast.LENGTH_SHORT).show()
                    updateUI()
                    // If service is running, restart it to connect websocket
                    if (CameraService.isRunning) {
                        restartService()
                    }
                } else {
                     Toast.makeText(this@MainActivity, "Binding failed: \${response.code()} - \${response.message()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "An error occurred: \${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notifyServiceOfSettingsChange() {
        if (CameraService.isRunning) {
            val intent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_SETTINGS_UPDATED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun updateUI() {
        val isServiceRunning = CameraService.isRunning
        if (isServiceRunning) {
            binding.statusText.text = "服务正在运行"
            binding.toggleServiceButton.text = "停止监控服务"
        } else {
            binding.statusText.text = "服务已停止"
            binding.toggleServiceButton.text = "启动监控服务"
        }

        if (sessionManager.isDeviceBound()) {
            binding.bindingStatusText.text = "已绑定到: \${sessionManager.getUsername()}"
            binding.bindDeviceButton.isEnabled = false
            binding.bindDeviceButton.text = "已绑定"
        } else {
            binding.bindingStatusText.text = "设备未绑定"
            binding.bindDeviceButton.isEnabled = true
            binding.bindDeviceButton.text = "绑定到账户"
        }
    }

    private fun restartService() {
        stopService(Intent(this, CameraService::class.java))
        // Give it a moment to fully stop before restarting
        binding.root.postDelayed({
            val serviceIntent = Intent(this, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }, 500)
    }
    
    // Unchanged Methods below
    private fun setupCameraSpinner() {
        cameraList = CameraHandler.getAvailableCameras(this)
        val cameraNames = cameraList.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cameraSpinner.adapter = adapter

        val savedCameraId = prefs.getString(KEY_SELECTED_CAMERA_ID, null)
        if (savedCameraId != null) {
            val savedIndex = cameraList.indexOfFirst { it.cameraId == savedCameraId }
            if (savedIndex != -1) {
                binding.cameraSpinner.setSelection(savedIndex)
            }
        }
    }

    private fun loadPreferences() {
        val isPublic = prefs.getBoolean(KEY_STORAGE_IS_PUBLIC, false)
        binding.storageLocationGroup.check(if (isPublic) R.id.radio_public_storage else R.id.radio_private_storage)

        val interval = prefs.getInt(KEY_CAPTURE_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        binding.intervalEditText.setText(interval.toString())

        binding.toastSwitch.isChecked = prefs.getBoolean(KEY_SHOW_TOAST, true)

        val autoRotate = prefs.getBoolean(KEY_AUTO_ROTATE, true)
        binding.autoRotateSwitch.isChecked = autoRotate
        updateForcedOrientationGroupState(autoRotate)

        val forcedOrientation = prefs.getString(KEY_FORCED_ORIENTATION, VALUE_ORIENTATION_PORTRAIT)
        binding.forcedOrientationGroup.check(when (forcedOrientation) {
            VALUE_ORIENTATION_PORTRAIT -> R.id.radio_force_portrait
            VALUE_ORIENTATION_PORTRAIT_REVERSED -> R.id.radio_force_portrait_reversed
            VALUE_ORIENTATION_LANDSCAPE -> R.id.radio_force_landscape
            VALUE_ORIENTATION_LANDSCAPE_REVERSED -> R.id.radio_force_landscape_reversed
            else -> R.id.radio_force_portrait
        })
    }
    
    private fun updateForcedOrientationGroupState(isAutoRotateEnabled: Boolean) {
        binding.forcedOrientationGroup.children.forEach { view ->
            view.isEnabled = !isAutoRotateEnabled
        }
    }

    private fun hasPermissions(): Boolean {
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (!isBatteryOptimizationIgnored()) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } else {
            Toast.makeText(this, "电池优化已禁用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleService() {
        if (!isBatteryOptimizationIgnored()) {
            Toast.makeText(this, "请先禁用电池优化", Toast.LENGTH_LONG).show()
            requestIgnoreBatteryOptimizations()
            return
        }

        val interval = binding.intervalEditText.text.toString().toIntOrNull() ?: DEFAULT_INTERVAL_SECONDS
        if (interval < 5) {
            Toast.makeText(this, "拍摄间隔不能小于5秒", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putInt(KEY_CAPTURE_INTERVAL, interval).apply()

        val serviceIntent = Intent(this, CameraService::class.java)
        if (CameraService.isRunning) {
            stopService(serviceIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        binding.root.postDelayed({ updateUI() }, 200)
    }

    private fun viewPhotos() {
        val isPublic = prefs.getBoolean(KEY_STORAGE_IS_PUBLIC, false)
        if (isPublic) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开相册应用", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "照片保存在应用专属目录，请使用文件管理器访问 Android/data/$packageName", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val PREFS_NAME = "CameraPrefs"
        const val KEY_STORAGE_IS_PUBLIC = "storage_is_public"
        const val KEY_CAPTURE_INTERVAL = "capture_interval"
        const val KEY_SHOW_TOAST = "show_toast"
        const val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
        const val KEY_AUTO_ROTATE = "auto_rotate"
        const val KEY_FORCED_ORIENTATION = "forced_orientation"
        const val VALUE_ORIENTATION_PORTRAIT = "portrait"
        const val VALUE_ORIENTATION_PORTRAIT_REVERSED = "portrait_reversed"
        const val VALUE_ORIENTATION_LANDSCAPE = "landscape"
        const val VALUE_ORIENTATION_LANDSCAPE_REVERSED = "landscape_reversed"
        const val DEFAULT_INTERVAL_SECONDS = 30
    }
}
