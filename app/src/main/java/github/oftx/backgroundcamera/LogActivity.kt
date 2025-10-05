package github.oftx.backgroundcamera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import github.oftx.backgroundcamera.databinding.ActivityLogBinding
import github.oftx.backgroundcamera.util.LogManager

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the action bar with a title and back button
        supportActionBar?.title = "Application Logs"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Load and display logs from the LogManager
        binding.logTextView.text = LogManager.getLogs()

        // Set up the copy button
        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }

        // 【新增】为清除按钮设置点击监听器
        binding.clearLogsButton.setOnClickListener {
            // 1. 调用LogManager清空日志
            LogManager.clearLogs()
            // 2. 重新加载日志内容到TextView (此时会显示"Logs cleared."这一条)
            binding.logTextView.text = LogManager.getLogs()
            // 3. 给用户反馈
            Toast.makeText(this, "All logs have been cleared!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLogsToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val allLogs = binding.logTextView.text.toString()
        val clip = ClipData.newPlainText("App Logs", allLogs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    // Handle the action bar's back button press
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}