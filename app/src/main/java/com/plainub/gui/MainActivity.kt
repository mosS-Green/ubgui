package com.plainub.gui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.plainub.gui.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private var isDualMode = false

    companion object {
        private const val TAG = "PlainUBGui"
        private const val PREFS_NAME = "PlainUBPrefs"
        private const val ACTION_TERMUX_RESULT = "com.plainub.gui.TERMUX_RESULT"
        
        // Termux RUN_COMMAND Constants
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        // Request codes for different Termux actions
        private const val REQ_SETUP = 1001
        private const val REQ_START = 1002
        private const val REQ_STOP = 1003
        private const val REQ_STATUS = 1004
        private const val REQ_LOGS = 1005
    }

    // Dynamic BroadcastReceiver to receive Termux execution results
    private val termuxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != ACTION_TERMUX_RESULT) return

            val resultBundle = intent.getBundleExtra("result")
            val requestCode = intent.getIntExtra("request_code", 0)
            
            if (resultBundle != null) {
                val stdout = resultBundle.getString("stdout") ?: ""
                val stderr = resultBundle.getString("stderr") ?: ""
                val exitCode = resultBundle.getInt("exit_code", -999)
                val errCode = resultBundle.getString("err") ?: ""

                runOnUiThread {
                    handleTermuxResult(requestCode, stdout, stderr, exitCode, errCode)
                }
            } else {
                appendLog("System Error: Received null result bundle from Termux.", isError = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        loadSavedConfig()
        registerTermuxReceiver()
        
        // Auto-check status on startup
        checkBotStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(termuxReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    private fun registerTermuxReceiver() {
        val filter = IntentFilter(ACTION_TERMUX_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(termuxReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(termuxReceiver, filter)
        }
        appendLog("System: Result receiver initialized successfully.")
    }

    private fun setupUI() {
        // Enable vertical scrolling for terminal output view
        binding.tvTerminalOutput.movementMethod = ScrollingMovementMethod()

        // Toggle inputs based on Mode
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == binding.rbDual.id) {
                isDualMode = true
                binding.tvModeDisplay.text = getString(R.string.mode_dual)
                binding.tvModeDisplay.setTextColor(ContextCompat.getColor(this, R.color.primary))
            } else {
                isDualMode = false
                binding.tvModeDisplay.text = getString(R.string.mode_single)
                binding.tvModeDisplay.setTextColor(ContextCompat.getColor(this, R.color.primary))
            }
        }

        // Action Buttons Listeners
        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
        }

        binding.btnRunSetup.setOnClickListener {
            runSetupCommand()
        }

        binding.btnStart.setOnClickListener {
            startBotCommand()
        }

        binding.btnStop.setOnClickListener {
            stopBotCommand()
        }

        binding.btnCheck.setOnClickListener {
            checkBotStatus()
        }

        binding.btnRefreshLogs.setOnClickListener {
            fetchBotLogs()
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvTerminalOutput.text = "Console cleared.\n"
        }
    }

    private fun loadSavedConfig() {
        binding.etApiId.setText(sharedPreferences.getString("api_id", ""))
        binding.etApiHash.setText(sharedPreferences.getString("api_hash", ""))
        binding.etBotToken.setText(sharedPreferences.getString("bot_token", ""))
        binding.etSessionString.setText(sharedPreferences.getString("session_string", ""))
        binding.etExtraRepo.setText(sharedPreferences.getString("extra_repo", ""))

        isDualMode = sharedPreferences.getBoolean("is_dual_mode", false)
        if (isDualMode) {
            binding.rbDual.isChecked = true
            binding.tvModeDisplay.text = getString(R.string.mode_dual)
        } else {
            binding.rbSingle.isChecked = true
            binding.tvModeDisplay.text = getString(R.string.mode_single)
        }
    }

    private fun saveConfig() {
        val apiId = binding.etApiId.text.toString().trim()
        val apiHash = binding.etApiHash.text.toString().trim()
        val botToken = binding.etBotToken.text.toString().trim()
        val sessionString = binding.etSessionString.text.toString().trim()
        val extraRepo = binding.etExtraRepo.text.toString().trim()

        if (apiId.isEmpty() || apiHash.isEmpty()) {
            Toast.makeText(this, "API ID and API HASH are required!", Toast.LENGTH_SHORT).show()
            return
        }

        sharedPreferences.edit().apply {
            putString("api_id", apiId)
            putString("api_hash", apiHash)
            putString("bot_token", botToken)
            putString("session_string", sessionString)
            putString("extra_repo", extraRepo)
            putBoolean("is_dual_mode", isDualMode)
            apply()
        }

        Toast.makeText(this, "Configuration saved successfully!", Toast.LENGTH_SHORT).show()
        appendLog("System: Configuration saved to app storage.")
    }

    private fun executeTermuxCommand(requestCode: Int, scriptContent: String, isBackground: Boolean = true) {
        // Create callback pending intent
        val callbackIntent = Intent(ACTION_TERMUX_RESULT).apply {
            setPackage(packageName)
            putExtra("request_code", requestCode)
        }

        // FLAG_MUTABLE is absolutely critical so that Termux can append the bundle results!
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            callbackIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Termux RUN_COMMAND intent construction
        val termuxIntent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_COMMAND_ARGUMENTS, arrayOf("-c", scriptContent))
            putExtra(EXTRA_COMMAND_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_COMMAND_BACKGROUND, isBackground)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            startService(termuxIntent)
            appendLog("System: Sending execution request to Termux...", isMuted = true)
        } catch (e: Exception) {
            appendLog("System Error: Failed to contact Termux. Ensure Termux is installed and running.\nException: ${e.message}", isError = true)
            Log.e(TAG, "Error executing termux command", e)
        }
    }

    private fun runSetupCommand() {
        val apiId = binding.etApiId.text.toString().trim()
        val apiHash = binding.etApiHash.text.toString().trim()
        val botToken = binding.etBotToken.text.toString().trim()
        val sessionString = binding.etSessionString.text.toString().trim()
        val extraRepo = binding.etExtraRepo.text.toString().trim()

        if (apiId.isEmpty() || apiHash.isEmpty()) {
            Toast.makeText(this, "Save valid configuration before running setup!", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("\n=== Starting Plain-UB Installation in Termux ===")
        appendLog("Task: Setting up libraries, dependencies, and git repo...")

        // Escape variables for shell writing
        val escapedApiId = apiId.replace("'", "'\\''")
        val escapedApiHash = apiHash.replace("'", "'\\''")
        val escapedBotToken = botToken.replace("'", "'\\''")
        val escapedSessionString = sessionString.replace("'", "'\\''")
        val escapedExtraRepo = extraRepo.replace("'", "'\\''")
        val mode = if (isDualMode) "dual" else "single"

        // Build temporary shell script logic inside Termux to avoid command size restrictions or string escaping issues
        val setupScript = """
            echo 'Initializing setup...'
            pkg update -y && pkg install python git openssl clang python-dev libffi-dev make -y
            
            mkdir -p ~/plain-ub
            if [ ! -d ~/plain-ub/.git ]; then
                echo 'Cloning plain-ub repository...'
                git clone https://github.com/thedragonsinn/plain-ub ~/plain-ub
            else
                echo 'Updating plain-ub repository...'
                cd ~/plain-ub && git fetch --all && git reset --hard origin/main
            fi
            
            cd ~/plain-ub
            echo 'Writing configuration file...'
            cat << 'EOF' > config.env
API_ID=${escapedApiId}
API_HASH=${escapedApiHash}
BOT_TOKEN=${escapedBotToken}
SESSION_STRING=${escapedSessionString}
EXTRA_MODULES_REPO=${escapedExtraRepo}
ENV_VARS=1
EOF

            echo 'Installing requirements.txt...'
            pip install --upgrade pip
            pip install -r requirements.txt
            
            if [ "${mode}" = "dual" ]; then
                echo 'Installing ub-core DUAL MODE branch...'
                pip install --force-reinstall git+https://github.com/thedragonsinn/ub-core@dual_mode
            else
                echo 'Installing ub-core SINGLE CLIENT branch...'
                pip install --force-reinstall git+https://github.com/thedragonsinn/ub-core@main
            fi
            
            echo 'SETUP COMPLETE: All configurations and dependencies configured successfully!'
        """.trimIndent()

        executeTermuxCommand(REQ_SETUP, setupScript)
    }

    private fun startBotCommand() {
        appendLog("\n=== Starting Plain-UB User-Bot ===")
        
        // Execute background startup command that redirects outputs to bot.log
        val startScript = """
            cd ~/plain-ub
            # Check if running first
            if pgrep -f "python3 -m app" > /dev/null; then
                echo "ALREADY_RUNNING"
            else
                nohup python3 -m app > bot.log 2>&1 &
                sleep 2
                if pgrep -f "python3 -m app" > /dev/null; then
                    echo "START_SUCCESS"
                else
                    echo "START_FAILED"
                fi
            fi
        """.trimIndent()

        executeTermuxCommand(REQ_START, startScript)
    }

    private fun stopBotCommand() {
        appendLog("\n=== Stopping Plain-UB User-Bot ===")

        val stopScript = """
            if pgrep -f "python3 -m app" > /dev/null; then
                pkill -9 -f "python3 -m app"
                pkill -9 -f "app"
                sleep 1
                if pgrep -f "python3 -m app" > /dev/null; then
                    echo "STOP_FAILED"
                else
                    echo "STOP_SUCCESS"
                fi
            else
                echo "NOT_RUNNING"
            fi
        """.trimIndent()

        executeTermuxCommand(REQ_STOP, stopScript)
    }

    private fun checkBotStatus() {
        val statusScript = """
            if [ ! -d ~/plain-ub ]; then
                echo "UNINSTALLED"
            elif pgrep -f "python3 -m app" > /dev/null; then
                echo "RUNNING"
            else
                echo "STOPPED"
            fi
        """.trimIndent()

        executeTermuxCommand(REQ_STATUS, statusScript)
    }

    private fun fetchBotLogs() {
        appendLog("System: Fetching bot log output...", isMuted = true)
        val logScript = """
            if [ -f ~/plain-ub/bot.log ]; then
                tail -n 100 ~/plain-ub/bot.log
            else
                echo "LOG_FILE_MISSING"
            fi
        """.trimIndent()

        executeTermuxCommand(REQ_LOGS, logScript)
    }

    private fun handleTermuxResult(requestCode: Int, stdout: String, stderr: String, exitCode: Int, errCode: String) {
        val cleanStdout = stdout.trim()
        val cleanStderr = stderr.trim()

        if (errCode.isNotEmpty()) {
            appendLog("Termux System Error (Code: $errCode): Ensure Termux allows external apps and permission is granted.", isError = true)
            return
        }

        when (requestCode) {
            REQ_SETUP -> {
                appendLog(cleanStdout)
                if (cleanStderr.isNotEmpty()) {
                    appendLog("Logs:\n$cleanStderr", isMuted = true)
                }
                if (exitCode == 0) {
                    appendLog("Success: Setup completed perfectly!", isSuccess = true)
                } else {
                    appendLog("Error: Setup script exited with code $exitCode.", isError = true)
                }
                checkBotStatus()
            }
            REQ_START -> {
                when {
                    cleanStdout.contains("ALREADY_RUNNING") -> {
                        appendLog("Status: Bot is already active and running.")
                        updateStatusUI("RUNNING")
                    }
                    cleanStdout.contains("START_SUCCESS") -> {
                        appendLog("Success: Plain-UB User-bot successfully initialized in background!", isSuccess = true)
                        updateStatusUI("RUNNING")
                        // Wait a brief second then fetch logs automatically to show startup outputs
                        binding.root.postDelayed({ fetchBotLogs() }, 1500)
                    }
                    cleanStdout.contains("START_FAILED") -> {
                        appendLog("Error: Bot command failed to maintain process. Check logs for details.", isError = true)
                        updateStatusUI("STOPPED")
                        fetchBotLogs()
                    }
                    else -> {
                        appendLog(cleanStdout)
                        if (cleanStderr.isNotEmpty()) appendLog(cleanStderr, isError = true)
                        checkBotStatus()
                    }
                }
            }
            REQ_STOP -> {
                when {
                    cleanStdout.contains("STOP_SUCCESS") || cleanStdout.contains("NOT_RUNNING") -> {
                        appendLog("Success: User-bot terminated.", isSuccess = true)
                        updateStatusUI("STOPPED")
                    }
                    cleanStdout.contains("STOP_FAILED") -> {
                        appendLog("Warning: Force kill command failed to stop the process.", isError = true)
                        checkBotStatus()
                    }
                    else -> {
                        appendLog(cleanStdout)
                        checkBotStatus()
                    }
                }
            }
            REQ_STATUS -> {
                val status = cleanStdout.split("\n").lastOrNull()?.trim() ?: "STOPPED"
                updateStatusUI(status)
            }
            REQ_LOGS -> {
                if (cleanStdout == "LOG_FILE_MISSING") {
                    binding.tvTerminalOutput.text = "Console log is empty or bot.log hasn't been created yet.\n"
                } else {
                    binding.tvTerminalOutput.text = cleanStdout
                    if (cleanStderr.isNotEmpty()) {
                        binding.tvTerminalOutput.append("\nErrors:\n$cleanStderr")
                    }
                    // Auto-scroll to bottom of log scroll view
                    binding.svTerminal.post {
                        binding.svTerminal.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun updateStatusUI(status: String) {
        when (status) {
            "RUNNING" -> {
                binding.tvStatusBadge.text = getString(R.string.status_running)
                binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_running))
                binding.tvStatusBadge.setTextColor(Color.WHITE)
            }
            "STOPPED" -> {
                binding.tvStatusBadge.text = getString(R.string.status_stopped)
                binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_stopped))
                binding.tvStatusBadge.setTextColor(Color.WHITE)
            }
            "UNINSTALLED" -> {
                binding.tvStatusBadge.text = getString(R.string.status_uninstalled)
                binding.tvStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_uninstalled))
                binding.tvStatusBadge.setTextColor(Color.WHITE)
            }
        }
    }

    private fun appendLog(message: String, isError: Boolean = false, isSuccess: Boolean = false, isMuted: Boolean = false) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val prefix = "[$timestamp] "
        
        val coloredMessage = when {
            isError -> {
                val errorColor = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.terminal_error))
                "<font color='$errorColor'>$prefix$message</font><br/>"
            }
            isSuccess -> {
                val successColor = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.terminal_success))
                "<font color='$successColor'>$prefix$message</font><br/>"
            }
            isMuted -> {
                val mutedColor = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.text_muted))
                "<font color='$mutedColor'>$prefix$message</font><br/>"
            }
            else -> {
                val normalColor = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.terminal_text))
                "<font color='$normalColor'>$prefix$message</font><br/>"
            }
        }
        
        binding.tvTerminalOutput.append(android.text.Html.fromHtml(coloredMessage, android.text.Html.FROM_HTML_MODE_LEGACY))
        
        // Auto scroll
        binding.svTerminal.post {
            binding.svTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }
}
