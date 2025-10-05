package github.oftx.backgroundcamera

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import github.oftx.backgroundcamera.databinding.ActivityMainBinding
import github.oftx.backgroundcamera.network.ApiService
import github.oftx.backgroundcamera.network.AppConfig
import github.oftx.backgroundcamera.network.RetrofitClient
import github.oftx.backgroundcamera.network.dto.ErrorResponseDto
import github.oftx.backgroundcamera.network.dto.UpdateDeviceNameRequestDto
import github.oftx.backgroundcamera.util.LogManager
import github.oftx.backgroundcamera.util.SessionManager
import kotlinx.coroutines.launch
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var sessionManager: SessionManager
    private val gson = Gson()

    private val apiService: ApiService
        get() = RetrofitClient.getApiService(this)

    private var cameraList: List<CameraHandler.CameraInfo> = emptyList()

    private val nameUpdateHandler = Handler(Looper.getMainLooper())
    private var nameUpdateRunnable: Runnable? = null
    private var currentDeviceName: String? = null

    // Cache the last known WS status to help update UI correctly
    private var lastWsStatus: WsConnectionStatus? = null

    private val wsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraService.ACTION_WS_STATUS_UPDATE) {
                val statusString = intent.getStringExtra(CameraService.EXTRA_WS_STATUS)
                val status = WsConnectionStatus.valueOf(statusString ?: WsConnectionStatus.DISCONNECTED.name)
                lastWsStatus = status
                updateCombinedStatusUI()
            }
        }
    }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            LogManager.addLog("[UI] Returned from successful login. Refreshing UI.")
            updateUI()
        }
    }

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 【新增】处理边到边显示的边衬距问题
        applyWindowInsets()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        LogManager.addLog("[UI] MainActivity created.")
        setupListeners()
        setupCameraSpinner()
        loadPreferences()
    }

    // 【新增】处理窗口边衬距的方法
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 将系统栏的高度应用为根视图的内边距
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            wsStatusReceiver, IntentFilter(CameraService.ACTION_WS_STATUS_UPDATE)
        )
        // If service is expected to be running, request its current status
        if (sessionManager.isDeviceBound()) {
            val requestStatusIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_REQUEST_WS_STATUS
            }
            startService(requestStatusIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(wsStatusReceiver)
    }

    private fun setupListeners() {
        binding.toggleServiceButton.setOnClickListener {
            if (hasPermissions()) {
                toggleService()
            } else {
                requestPermissionsLauncher.launch(permissions.toTypedArray())
            }
        }
        binding.checkBatteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.viewPhotosButton.setOnClickListener { viewPhotos() }
        binding.viewLogsButton.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }

        binding.accountActionButton.setOnClickListener {
            if (sessionManager.isLoggedIn()) {
                if (!sessionManager.isDeviceBound()) {
                    bindDevice()
                }
            } else {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        binding.logoutButton.setOnClickListener {
            LogManager.addLog("[Auth] User logged out.")
            // Send shutdown command to the service
            val shutdownIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_SHUTDOWN
            }
            startService(shutdownIntent)
            sessionManager.logout()
            updateUI()
        }

        binding.unbindButton.setOnClickListener {
            showUnbindConfirmationDialog()
        }

        binding.reconnectButton.setOnClickListener {
            val reconnectIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_RECONNECT_WS
            }
            startService(reconnectIntent)
            lastWsStatus = WsConnectionStatus.CONNECTING
            updateCombinedStatusUI()
        }

        // Add listener for the new disconnect button
        binding.disconnectWsButton.setOnClickListener {
            val disconnectIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_DISCONNECT_WS
            }
            startService(disconnectIntent)
        }


        binding.deviceNameEditText.doOnTextChanged { text, _, _, _ ->
            nameUpdateRunnable?.let { nameUpdateHandler.removeCallbacks(it) }
            nameUpdateRunnable = Runnable {
                val newName = text.toString().trim()
                if (newName.isNotEmpty() && newName != currentDeviceName) {
                    updateDeviceName(newName)
                }
            }
            nameUpdateHandler.postDelayed(nameUpdateRunnable!!, 1000)
        }

        binding.serverAddressEditText.doOnTextChanged { text, _, _, _ ->
            val url = text.toString().trim().removeSuffix("/")
            prefs.edit().putString(KEY_SERVER_URL, url).apply()
            notifyServiceOfSettingsChange()
        }

        binding.storageLocationGroup.setOnCheckedChangeListener { _, checkedId ->
            val isPublic = checkedId == R.id.radio_public_storage
            prefs.edit().putBoolean(KEY_STORAGE_IS_PUBLIC, isPublic).apply()
            if (isPublic && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasPermissions()) {
                Toast.makeText(this, "公共目录需要存储权限", Toast.LENGTH_SHORT).show()
                requestPermissionsLauncher.launch(permissions.toTypedArray())
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
        binding.forcedOrientationGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientationValue = when (checkedId) {
                R.id.radio_force_portrait -> VALUE_ORIENTATION_PORTRAIT
                R.id.radio_force_portrait_reversed -> VALUE_ORIENTATION_PORTRAIT_REVERSED
                R.id.radio_force_landscape -> VALUE_ORIENTATION_LANDSCAPE
                R.id.radio_force_landscape_reversed -> VALUE_ORIENTATION_LANDSCAPE_REVERSED
                else -> VALUE_ORIENTATION_PORTRAIT
            }
            prefs.edit().putString(KEY_FORCED_ORIENTATION, orientationValue).apply()
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

    private fun showUnbindConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("解绑设备")
            .setMessage("您确定要将此设备从您的账户中移除吗？解绑后，您需要重新登录并绑定才能再次使用。")
            .setPositiveButton("确认解绑") { _, _ ->
                performUnbind()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performUnbind() {
        if (!sessionManager.isLoggedIn()) return
        val deviceId = sessionManager.getDeviceId()
        val jwt = sessionManager.getUserJwt()!!
        lifecycleScope.launch {
            try {
                // Send shutdown command to the service before unbinding
                val shutdownIntent = Intent(this@MainActivity, CameraService::class.java).apply {
                    action = CameraService.ACTION_SHUTDOWN
                }
                startService(shutdownIntent)

                val response = apiService.unbindDevice("Bearer $jwt", deviceId)
                if (response.isSuccessful) {
                    LogManager.addLog("[Binding] Device unbind successful.")
                    Toast.makeText(this@MainActivity, "设备已解绑", Toast.LENGTH_SHORT).show()
                    sessionManager.logout()
                    updateUI()
                } else {
                    val errorMsg = parseError(response)
                    LogManager.addLog("[Binding] Unbind failed: $errorMsg")
                    Toast.makeText(this@MainActivity, "解绑失败: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Unbind exception", e)
                val errorMsg = "网络错误: ${e.localizedMessage}"
                LogManager.addLog("[Binding] Unbind failed: $errorMsg")
                Toast.makeText(this@MainActivity, "发生错误: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchAndDisplayDeviceName() {
        if (!sessionManager.isDeviceBound()) return
        val deviceId = sessionManager.getDeviceId()
        val jwt = sessionManager.getUserJwt()!!
        lifecycleScope.launch {
            try {
                val response = apiService.getDeviceDetails("Bearer $jwt", deviceId)
                if (response.isSuccessful && response.body() != null) {
                    val deviceName = response.body()!!.name
                    this@MainActivity.currentDeviceName = deviceName
                    binding.deviceNameEditText.setText(deviceName)
                } else {
                    LogManager.addLog("[Device] Failed to fetch device name: ${parseError(response)}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Fetch device name exception", e)
                LogManager.addLog("[Device] Failed to fetch device name: ${e.localizedMessage}")
            }
        }
    }

    private fun updateDeviceName(newName: String) {
        if (!sessionManager.isDeviceBound()) return
        val deviceId = sessionManager.getDeviceId()
        val jwt = sessionManager.getUserJwt()!!
        lifecycleScope.launch {
            try {
                val request = UpdateDeviceNameRequestDto(newName)
                val response = apiService.updateDeviceName("Bearer $jwt", deviceId, request)
                if (response.isSuccessful) {
                    this@MainActivity.currentDeviceName = newName
                    LogManager.addLog("[Device] Device name updated to '$newName'")
                    Toast.makeText(this@MainActivity, "名称已更新", Toast.LENGTH_SHORT).show()
                } else {
                    LogManager.addLog("[Device] Failed to update device name: ${parseError(response)}")
                    Toast.makeText(this@MainActivity, "名称更新失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Update device name exception", e)
                LogManager.addLog("[Device] Failed to update device name: ${e.localizedMessage}")
            }
        }
    }

    private fun bindDevice() {
        val deviceId = sessionManager.getDeviceId()
        val jwt = sessionManager.getUserJwt()!!
        lifecycleScope.launch {
            try {
                val response = apiService.bindDevice("Bearer $jwt", deviceId)
                if (response.isSuccessful && response.body() != null) {
                    val authToken = response.body()!!.authToken
                    sessionManager.saveDeviceBinding(authToken)
                    LogManager.addLog("[Binding] Device bound successfully!")
                    Toast.makeText(this@MainActivity, "设备绑定成功!", Toast.LENGTH_SHORT).show()

                    // Initialize and start the service in foreground after binding
                    val initIntent = Intent(this@MainActivity, CameraService::class.java).apply {
                        action = CameraService.ACTION_INITIALIZE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(initIntent)
                    } else {
                        startService(initIntent)
                    }

                    updateUI()
                } else {
                    val errorMsg = parseError(response)
                    LogManager.addLog("[Binding] Binding failed: $errorMsg")
                    Toast.makeText(this@MainActivity, "绑定失败: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Binding exception", e)
                val errorMsg = "网络错误: ${e.localizedMessage}"
                LogManager.addLog("[Binding] Binding failed: $errorMsg")
                Toast.makeText(this@MainActivity, "发生错误: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
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
        val isMonitoring = CameraService.isMonitoringActive
        binding.statusText.text = if (isMonitoring) "服务正在运行" else "服务已停止"
        binding.toggleServiceButton.text = if (isMonitoring) "停止监控服务" else "启动监控服务"

        if (sessionManager.isLoggedIn()) {
            if (sessionManager.isDeviceBound()) {
                binding.bindingStatusText.text = "已同步到: ${sessionManager.getUsername()}"
                binding.accountActionButton.visibility = View.GONE
                binding.boundActionsLayout.visibility = View.VISIBLE
                binding.deviceNameLayout.visibility = View.VISIBLE
                binding.connectionStatusLayout.visibility = View.VISIBLE
                fetchAndDisplayDeviceName()
                updateCombinedStatusUI() // Update WS buttons based on current state
            } else {
                binding.bindingStatusText.text = "已登录: ${sessionManager.getUsername()}"
                binding.accountActionButton.text = "绑定此设备"
                binding.accountActionButton.isEnabled = true
                binding.accountActionButton.visibility = View.VISIBLE
                binding.boundActionsLayout.visibility = View.GONE
                binding.deviceNameLayout.visibility = View.GONE
                binding.connectionStatusLayout.visibility = View.GONE
            }
        } else {
            binding.bindingStatusText.text = "登录以同步和远程控制"
            binding.accountActionButton.text = "登录 / 注册"
            binding.accountActionButton.isEnabled = true
            binding.accountActionButton.visibility = View.VISIBLE
            binding.boundActionsLayout.visibility = View.GONE
            binding.deviceNameLayout.visibility = View.GONE
            binding.connectionStatusLayout.visibility = View.GONE
        }
    }

    private fun updateCombinedStatusUI() {
        val status = lastWsStatus ?: return // If we don't know the status yet, do nothing
        val isMonitoring = CameraService.isMonitoringActive

        when (status) {
            WsConnectionStatus.CONNECTED -> {
                binding.connectionStatusText.text = "已连接"
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.green_700))
                binding.reconnectButton.visibility = View.GONE
                // Only show disconnect button if monitoring is NOT active
                binding.disconnectWsButton.visibility = if (!isMonitoring) View.VISIBLE else View.GONE
            }
            WsConnectionStatus.CONNECTING -> {
                binding.connectionStatusText.text = "连接中..."
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                binding.reconnectButton.visibility = View.GONE
                binding.disconnectWsButton.visibility = View.GONE
            }
            WsConnectionStatus.DISCONNECTED -> {
                binding.connectionStatusText.text = "已断开"
                binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_error))
                // Only show reconnect button if monitoring is NOT active
                binding.reconnectButton.visibility = if (!isMonitoring) View.VISIBLE else View.GONE
                binding.disconnectWsButton.visibility = View.GONE
            }
        }
    }

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
        val serverUrl = prefs.getString(KEY_SERVER_URL, AppConfig.BASE_URL)
        binding.serverAddressEditText.setText(serverUrl)
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
        if (CameraService.isMonitoringActive) {
            serviceIntent.action = CameraService.ACTION_STOP_MONITORING
            startService(serviceIntent)
        } else {
            serviceIntent.action = CameraService.ACTION_START_MONITORING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        // Use a short delay to allow service state to update, then refresh UI
        binding.root.postDelayed({
            updateUI()
            updateCombinedStatusUI()
        }, 200)
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
        const val KEY_SERVER_URL = "server_url"
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