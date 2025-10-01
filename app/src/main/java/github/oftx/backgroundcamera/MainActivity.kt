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

            if (CameraService.isRunning) {
                Toast.makeText(this, "间隔设置已更新，将在下一次拍照后生效", Toast.LENGTH_SHORT).show()
            }
        }

        binding.toastSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_TOAST, isChecked).apply()
        }

        binding.autoRotateSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_ROTATE, isChecked).apply()
            updateForcedOrientationGroupState(isChecked)
        }

        // 修改：为新的4选项RadioGroup添加监听器
        binding.forcedOrientationGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientationValue = when (checkedId) {
                R.id.radio_force_portrait -> VALUE_ORIENTATION_PORTRAIT
                R.id.radio_force_portrait_reversed -> VALUE_ORIENTATION_PORTRAIT_REVERSED
                R.id.radio_force_landscape -> VALUE_ORIENTATION_LANDSCAPE
                R.id.radio_force_landscape_reversed -> VALUE_ORIENTATION_LANDSCAPE_REVERSED
                else -> VALUE_ORIENTATION_PORTRAIT
            }
            prefs.edit().putString(KEY_FORCED_ORIENTATION, orientationValue).apply()
        }
    }

    private fun updateForcedOrientationGroupState(isAutoRotateEnabled: Boolean) {
        binding.forcedOrientationGroup.children.forEach { view ->
            view.isEnabled = !isAutoRotateEnabled
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

        val autoRotate = prefs.getBoolean(KEY_AUTO_ROTATE, true)
        binding.autoRotateSwitch.isChecked = autoRotate
        updateForcedOrientationGroupState(autoRotate)

        // 修改：加载并选中正确的固定方向
        val forcedOrientation = prefs.getString(KEY_FORCED_ORIENTATION, VALUE_ORIENTATION_PORTRAIT)
        val checkedId = when (forcedOrientation) {
            VALUE_ORIENTATION_PORTRAIT -> R.id.radio_force_portrait
            VALUE_ORIENTATION_PORTRAIT_REVERSED -> R.id.radio_force_portrait_reversed
            VALUE_ORIENTATION_LANDSCAPE -> R.id.radio_force_landscape
            VALUE_ORIENTATION_LANDSCAPE_REVERSED -> R.id.radio_force_landscape_reversed
            else -> R.id.radio_force_portrait
        }
        binding.forcedOrientationGroup.check(checkedId)

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

    private fun updateUI() {
        val isServiceRunning = CameraService.isRunning
        if (isServiceRunning) {
            binding.statusText.text = "服务正在运行"
            binding.toggleServiceButton.text = "停止监控服务"
        } else {
            binding.statusText.text = "服务已停止"
            binding.toggleServiceButton.text = "启动监控服务"
        }
    }

    companion object {
        const val PREFS_NAME = "CameraPrefs"
        const val KEY_STORAGE_IS_PUBLIC = "storage_is_public"
        const val KEY_CAPTURE_INTERVAL = "capture_interval"
        const val KEY_SHOW_TOAST = "show_toast"
        const val KEY_SELECTED_CAMERA_ID = "selected_camera_id"
        const val KEY_AUTO_ROTATE = "auto_rotate"

        // 修改：为4个方向定义常量
        const val KEY_FORCED_ORIENTATION = "forced_orientation"
        const val VALUE_ORIENTATION_PORTRAIT = "portrait"
        const val VALUE_ORIENTATION_PORTRAIT_REVERSED = "portrait_reversed"
        const val VALUE_ORIENTATION_LANDSCAPE = "landscape"
        const val VALUE_ORIENTATION_LANDSCAPE_REVERSED = "landscape_reversed"

        const val DEFAULT_INTERVAL_SECONDS = 30
    }
}