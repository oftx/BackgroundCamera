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
import androidx.core.widget.doOnTextChanged
import github.oftx.backgroundcamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupListeners()
        setupCameraSpinner()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
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

        binding.storageLocationGroup.setOnCheckedChangeListener { _, checkedId ->
            val isPublic = checkedId == R.id.radio_public_storage
            prefs.edit().putBoolean(KEY_STORAGE_IS_PUBLIC, isPublic).apply()

            if (isPublic && !hasPermissions()) {
                Toast.makeText(this, "公共目录需要存储权限", Toast.LENGTH_SHORT).show()
                requestPermissionsLauncher.launch(this.permissions.toTypedArray())
            }
        }

        binding.intervalEditText.doOnTextChanged { text, _, _, _ ->
            val interval = text.toString().toIntOrNull() ?: DEFAULT_INTERVAL_SECONDS
            prefs.edit().putInt(KEY_CAPTURE_INTERVAL, interval).apply()

            // 如果服务正在运行，提示用户更改将在下一次生效
            if (CameraService.isRunning) {
                Toast.makeText(this, "间隔设置已更新，将在下一次拍照后生效", Toast.LENGTH_SHORT).show()
            }
        }

        binding.toastSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_TOAST, isChecked).apply()
        }
    }

    private fun setupCameraSpinner() {
        cameraList = CameraHandler.getAvailableCameras(this)
        val cameraNames = cameraList.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cameraSpinner.adapter = adapter

        binding.cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (cameraList.isNotEmpty()) {
                    val selectedCameraId = cameraList[position].cameraId
                    prefs.edit().putString(KEY_SELECTED_CAMERA_ID, selectedCameraId).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadPreferences() {
        val isPublic = prefs.getBoolean(KEY_STORAGE_IS_PUBLIC, false)
        binding.storageLocationGroup.check(
            if (isPublic) R.id.radio_public_storage else R.id.radio_private_storage
        )

        val interval = prefs.getInt(KEY_CAPTURE_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        binding.intervalEditText.setText(interval.toString())

        val showToast = prefs.getBoolean(KEY_SHOW_TOAST, true)
        binding.toastSwitch.isChecked = showToast

        val savedCameraId = prefs.getString(KEY_SELECTED_CAMERA_ID, null)
        if (savedCameraId != null) {
            val savedIndex = cameraList.indexOfFirst { it.cameraId == savedCameraId }
            if (savedIndex != -1) {
                binding.cameraSpinner.setSelection(savedIndex)
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (!isBatteryOptimizationIgnored()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
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
        // 稍作延迟以等待服务状态更新
        binding.root.postDelayed({ updateUI() }, 200)
    }

    private fun viewPhotos() {
        val isPublic = prefs.getBoolean(KEY_STORAGE_IS_PUBLIC, false)
        if (isPublic) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开相册应用", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "照片保存在应用专属目录，请使用文件管理器访问 Android/data/$packageName", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 核心改动：移除了在服务运行时禁用UI控件的逻辑。
     */
    private fun updateUI() {
        val isServiceRunning = CameraService.isRunning
        if (isServiceRunning) {
            binding.statusText.text = "服务正在运行"
            binding.toggleServiceButton.text = "停止监控服务"
        } else {
            binding.statusText.text = "服务已停止"
            binding.toggleServiceButton.text = "启动监控服务"
        }
        // 以下代码已被移除，以允许在服务运行时修改设置
        // binding.radioPublicStorage.isEnabled = !isServiceRunning
        // binding.radioPrivateStorage.isEnabled = !isServiceRunning
        // binding.intervalEditText.isEnabled = !isServiceRunning
        // binding.toastSwitch.isEnabled = !isServiceRunning
        // binding.cameraSpinner.isEnabled = !isServiceRunning
    }

    companion object {
        const val PREFS_NAME = "CameraPrefs"
        const val KEY_STORAGE_IS_PUBLIC = "storage_is_public"
        const val KEY_CAPTURE_INTERVAL = "capture_interval"
        const val KEY_SHOW_TOAST = "show_toast"
        const val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
        const val DEFAULT_INTERVAL_SECONDS = 30
    }
}