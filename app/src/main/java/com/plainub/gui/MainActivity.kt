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
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.plainub.gui.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isDualMode = false

    // Atomic counter to generate unique request codes per invocation
    private val requestCounter = AtomicInteger(0)

    companion object {
        private const val TAG = "PlainUBGui"
        private const val PREFS_NAME = "PlainUBPrefs"
        private const val ACTION_TERMUX_RESULT = "com.plainub.gui.TERMUX_RESULT"

        // Termux RUN_COMMAND intent constants
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        // Logical tags sent via the intent extra to identify what command was run
        private const val CMD_SETUP = "setup"
        private const val CMD_START = "start"
        private const val CMD_STOP = "stop"
        private const val CMD_STATUS = "status"
        private const val CMD_LOGS = "logs"
    }

    // ──────────────────────────────────────────────
    // BroadcastReceiver for Termux results
    // ──────────────────────────────────────────────
    private val termuxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TERMUX_RESULT) return

            val cmdTag = intent.getStringExtra("cmd_tag") ?: return
            val resultBundle = intent.getBundleExtra("result")

            if (resultBundle == null) {
                runOnUiThread {
                    appendLog("Error: No result bundle from Termux. Check permissions.", isError = true)
                }
                return
            }

            val stdout = resultBundle.getString("stdout", "") ?: ""
            val stderr = resultBundle.getString("stderr", "") ?: ""
            val exitCode = resultBundle.getInt("exit_code", -1)
            val internalErr = resultBundle.getString("err", "") ?: ""

            runOnUiThread {
                handleResult(cmdTag, stdout.trim(), stderr.trim(), exitCode, internalErr.trim())
            }
        }
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        wireUI()
        loadSavedConfig()
        registerResultReceiver()
        checkBotStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(termuxReceiver) } catch (_: Exception) {}
    }

    private fun registerResultReceiver() {
        val filter = IntentFilter(ACTION_TERMUX_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(termuxReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(termuxReceiver, filter)
        }
    }

    // ──────────────────────────────────────────────
    // UI wiring
    // ──────────────────────────────────────────────
    private fun wireUI() {
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            isDualMode = checkedId == binding.rbDual.id
            binding.tvModeDisplay.text = getString(
                if (isDualMode) R.string.mode_dual else R.string.mode_single
            )
        }

        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.btnRunSetup.setOnClickListener { runSetup() }
        binding.btnStart.setOnClickListener { startBot() }
        binding.btnStop.setOnClickListener { stopBot() }
        binding.btnCheck.setOnClickListener { checkBotStatus() }
        binding.btnRefreshLogs.setOnClickListener { fetchLogs() }
        binding.btnClearLogs.setOnClickListener {
            binding.tvTerminalOutput.text = getString(R.string.terminal_placeholder)
        }
    }

    // ──────────────────────────────────────────────
    // Config persistence
    // ──────────────────────────────────────────────
    private fun loadSavedConfig() {
        binding.etApiId.setText(prefs.getString("api_id", ""))
        binding.etApiHash.setText(prefs.getString("api_hash", ""))
        binding.etBotToken.setText(prefs.getString("bot_token", ""))
        binding.etSessionString.setText(prefs.getString("session_string", ""))
        binding.etExtraRepo.setText(prefs.getString("extra_repo", ""))

        isDualMode = prefs.getBoolean("is_dual_mode", false)
        if (isDualMode) {
            binding.rbDual.isChecked = true
        } else {
            binding.rbSingle.isChecked = true
        }
        binding.tvModeDisplay.text = getString(
            if (isDualMode) R.string.mode_dual else R.string.mode_single
        )
    }

    private fun saveConfig() {
        val apiId = binding.etApiId.text.toString().trim()
        val apiHash = binding.etApiHash.text.toString().trim()

        if (apiId.isEmpty() || apiHash.isEmpty()) {
            Toast.makeText(this, "API ID and API HASH are required!", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putString("api_id", apiId)
            .putString("api_hash", apiHash)
            .putString("bot_token", binding.etBotToken.text.toString().trim())
            .putString("session_string", binding.etSessionString.text.toString().trim())
            .putString("extra_repo", binding.etExtraRepo.text.toString().trim())
            .putBoolean("is_dual_mode", isDualMode)
            .apply()

        Toast.makeText(this, "Configuration saved.", Toast.LENGTH_SHORT).show()
        appendLog("Config saved to app storage.")
    }

    // ──────────────────────────────────────────────
    // Termux command execution
    // ──────────────────────────────────────────────
    /**
     * Sends a bash script to Termux RunCommandService for background execution.
     * Results are delivered back via [termuxReceiver].
     *
     * @param cmdTag   Identifies which logical command this is (setup/start/stop/status/logs)
     * @param script   The bash script body to execute
     */
    private fun execInTermux(cmdTag: String, script: String) {
        val reqCode = requestCounter.incrementAndGet()

        val callbackIntent = Intent(ACTION_TERMUX_RESULT).apply {
            setPackage(packageName)
            putExtra("cmd_tag", cmdTag)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reqCode,
            callbackIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_COMMAND_ARGUMENTS, arrayOf("-c", script))
            putExtra(EXTRA_COMMAND_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: SecurityException) {
            appendLog("Permission denied. Grant \"Run commands in Termux\" in app settings.", isError = true)
            Log.e(TAG, "SecurityException starting Termux service", e)
        } catch (e: Exception) {
            appendLog("Failed to contact Termux. Is it installed?", isError = true)
            Log.e(TAG, "Error starting Termux service", e)
        }
    }

    // ──────────────────────────────────────────────
    // Command builders
    // ──────────────────────────────────────────────
    private fun runSetup() {
        val apiId = binding.etApiId.text.toString().trim()
        val apiHash = binding.etApiHash.text.toString().trim()
        val botToken = binding.etBotToken.text.toString().trim()
        val session = binding.etSessionString.text.toString().trim()
        val extraRepo = binding.etExtraRepo.text.toString().trim()

        if (apiId.isEmpty() || apiHash.isEmpty()) {
            Toast.makeText(this, "Save a valid config first!", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("═══ Starting Plain-UB Setup ═══", isSuccess = true)

        // Use a heredoc with a non-quoted delimiter so that we can safely write
        // the config values without worrying about shell metacharacters.
        // Kotlin string interpolation fills in the values before Termux ever sees them.
        val ubCoreBranch = if (isDualMode) "dual_mode" else "main"

        val script = buildString {
            appendLine("set -e")
            appendLine("echo '[1/5] Updating packages...'")
            appendLine("pkg update -y && pkg install -y python git openssl libffi rust binutils")
            appendLine()
            appendLine("echo '[2/5] Cloning / updating repository...'")
            appendLine("if [ ! -d ~/plain-ub/.git ]; then")
            appendLine("  git clone https://github.com/thedragonsinn/plain-ub ~/plain-ub")
            appendLine("else")
            appendLine("  cd ~/plain-ub && git fetch --all && git reset --hard origin/main")
            appendLine("fi")
            appendLine()
            appendLine("cd ~/plain-ub")
            appendLine("echo '[3/5] Writing config.env...'")
            // Write config.env using printf to avoid heredoc escaping issues
            appendLine("printf '%s\\n' \\")
            appendLine("  'API_ID=${apiId}' \\")
            appendLine("  'API_HASH=${apiHash}' \\")
            appendLine("  'BOT_TOKEN=${botToken}' \\")
            appendLine("  'SESSION_STRING=${session}' \\")
            appendLine("  'EXTRA_MODULES_REPO=${extraRepo}' \\")
            appendLine("  'ENV_VARS=1' \\")
            appendLine("  > config.env")
            appendLine()
            appendLine("echo '[4/5] Installing Python dependencies...'")
            appendLine("pip install --upgrade pip")
            appendLine("pip install -r requirements.txt 2>&1 || true")
            appendLine()
            appendLine("echo '[5/5] Installing ub-core (${ubCoreBranch})...'")
            appendLine("pip install --force-reinstall 'git+https://github.com/thedragonsinn/ub-core@${ubCoreBranch}'")
            appendLine()
            appendLine("echo 'SETUP_COMPLETE'")
        }

        execInTermux(CMD_SETUP, script)
    }

    private fun startBot() {
        appendLog("═══ Starting Bot ═══")
        val script = """
            cd ~/plain-ub || { echo "NOT_INSTALLED"; exit 1; }
            if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                echo "ALREADY_RUNNING"
            else
                nohup python3 -m app >> bot.log 2>&1 &
                sleep 3
                if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                    echo "START_OK"
                else
                    echo "START_FAIL"
                    tail -20 bot.log 2>/dev/null
                fi
            fi
        """.trimIndent()
        execInTermux(CMD_START, script)
    }

    private fun stopBot() {
        appendLog("═══ Stopping Bot ═══")
        // Only kill processes whose full command line matches "python3 -m app"
        // This avoids killing unrelated processes
        val script = """
            if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                pkill -f "python3 -m app"
                sleep 1
                if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                    pkill -9 -f "python3 -m app"
                    sleep 1
                fi
                if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                    echo "STOP_FAIL"
                else
                    echo "STOP_OK"
                fi
            else
                echo "NOT_RUNNING"
            fi
        """.trimIndent()
        execInTermux(CMD_STOP, script)
    }

    private fun checkBotStatus() {
        val script = """
            if [ ! -d ~/plain-ub ]; then
                echo "UNINSTALLED"
            elif pgrep -f "python3 -m app" > /dev/null 2>&1; then
                echo "RUNNING"
            else
                echo "STOPPED"
            fi
        """.trimIndent()
        execInTermux(CMD_STATUS, script)
    }

    private fun fetchLogs() {
        appendLog("Fetching logs…", isMuted = true)
        val script = """
            if [ -f ~/plain-ub/bot.log ]; then
                tail -n 150 ~/plain-ub/bot.log
            else
                echo "LOG_NOT_FOUND"
            fi
        """.trimIndent()
        execInTermux(CMD_LOGS, script)
    }

    // ──────────────────────────────────────────────
    // Result handling
    // ──────────────────────────────────────────────
    private fun handleResult(cmd: String, stdout: String, stderr: String, exitCode: Int, err: String) {
        // If Termux itself errored (e.g. permission denied), show that first
        if (err.isNotEmpty()) {
            appendLog("Termux error: $err", isError = true)
            return
        }

        when (cmd) {
            CMD_SETUP -> {
                if (stdout.isNotEmpty()) appendLog(stdout)
                if (stderr.isNotEmpty()) appendLog(stderr, isMuted = true)
                if (stdout.contains("SETUP_COMPLETE")) {
                    appendLog("✓ Setup completed successfully!", isSuccess = true)
                } else if (exitCode != 0) {
                    appendLog("✗ Setup failed (exit $exitCode).", isError = true)
                }
                checkBotStatus()
            }

            CMD_START -> when {
                stdout.contains("ALREADY_RUNNING") -> {
                    appendLog("Bot is already running.")
                    setStatus("RUNNING")
                }
                stdout.contains("START_OK") -> {
                    appendLog("✓ Bot started!", isSuccess = true)
                    setStatus("RUNNING")
                    binding.root.postDelayed({ fetchLogs() }, 2000)
                }
                stdout.contains("NOT_INSTALLED") -> {
                    appendLog("plain-ub not found. Run setup first.", isError = true)
                    setStatus("UNINSTALLED")
                }
                else -> {
                    appendLog("✗ Bot failed to start.", isError = true)
                    if (stdout.isNotEmpty()) appendLog(stdout)
                    if (stderr.isNotEmpty()) appendLog(stderr, isError = true)
                    setStatus("STOPPED")
                }
            }

            CMD_STOP -> when {
                stdout.contains("STOP_OK") || stdout.contains("NOT_RUNNING") -> {
                    appendLog("✓ Bot stopped.", isSuccess = true)
                    setStatus("STOPPED")
                }
                stdout.contains("STOP_FAIL") -> {
                    appendLog("✗ Could not kill bot process.", isError = true)
                    checkBotStatus()
                }
                else -> {
                    if (stdout.isNotEmpty()) appendLog(stdout)
                    checkBotStatus()
                }
            }

            CMD_STATUS -> {
                val status = stdout.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "STOPPED"
                setStatus(status)
            }

            CMD_LOGS -> {
                if (stdout == "LOG_NOT_FOUND") {
                    appendLog("No bot.log found yet. Start the bot first.", isMuted = true)
                } else {
                    // Replace terminal content entirely with fresh log output
                    binding.tvTerminalOutput.text = stdout
                    if (stderr.isNotEmpty()) {
                        binding.tvTerminalOutput.append("\n--- stderr ---\n$stderr")
                    }
                    scrollTerminalToBottom()
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────
    private fun setStatus(status: String) {
        val (textRes, colorRes) = when (status) {
            "RUNNING" -> R.string.status_running to R.color.status_running
            "UNINSTALLED" -> R.string.status_uninstalled to R.color.status_uninstalled
            else -> R.string.status_stopped to R.color.status_stopped
        }
        binding.tvStatusBadge.text = getString(textRes)
        binding.tvStatusBadge.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.tvStatusBadge.setTextColor(Color.WHITE)
    }

    private fun appendLog(
        message: String,
        isError: Boolean = false,
        isSuccess: Boolean = false,
        isMuted: Boolean = false
    ) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val colorRes = when {
            isError -> R.color.terminal_error
            isSuccess -> R.color.terminal_success
            isMuted -> R.color.text_muted
            else -> R.color.terminal_text
        }
        val hex = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, colorRes))
        val html = "<font color='$hex'>[$ts] $message</font><br/>"

        binding.tvTerminalOutput.append(
            android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
        )
        scrollTerminalToBottom()
    }

    private fun scrollTerminalToBottom() {
        binding.svTerminal.post {
            binding.svTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }
}
