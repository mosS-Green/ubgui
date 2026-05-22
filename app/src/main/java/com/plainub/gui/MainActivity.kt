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
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.plainub.gui.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isDualMode = false

    // Atomic counter to generate unique request codes per invocation
    private val requestCounter = AtomicInteger(0)

    // Store last command output for AI diagnosis
    private var lastStdout: String = ""
    private var lastStderr: String = ""

    // Extra env vars from paste (keys not in the known set)
    private val extraEnvVars = mutableMapOf<String, String>()

    // OkHttp client for Gemini API calls
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
        private const val CMD_CUSTOM = "custom"

        // Known env keys that map to specific UI fields
        private val KNOWN_ENV_KEYS = setOf(
            "API_ID", "API_HASH", "BOT_TOKEN", "SESSION_STRING",
            "EXTRA_MODULES_REPO", "DATABASE_URL", "USE_DUAL_BRANCH"
        )
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
        // Mode radio group
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            isDualMode = checkedId == binding.rbDual.id
            binding.tvModeDisplay.text = getString(
                if (isDualMode) R.string.mode_dual else R.string.mode_single
            )
        }

        // Config buttons
        binding.btnSaveConfig.setOnClickListener { saveConfig() }

        // Paste env toggle
        binding.btnPasteEnv.setOnClickListener { toggleEnvPaste() }
        binding.btnParseFill.setOnClickListener { parseAndFillEnv() }

        // Setup & bot controls
        binding.btnRunSetup.setOnClickListener { runSetup() }
        binding.btnStart.setOnClickListener { startBot() }
        binding.btnStop.setOnClickListener { stopBot() }
        binding.btnCheck.setOnClickListener { checkBotStatus() }

        // Log controls
        binding.btnRefreshLogs.setOnClickListener { fetchLogs() }
        binding.btnClearLogs.setOnClickListener {
            binding.tvTerminalOutput.text = getString(R.string.terminal_placeholder)
        }

        // AI diagnosis
        binding.btnDiagnose.setOnClickListener { diagnoseWithGemini() }

        // Collapsible custom command
        binding.headerCustomCmd.setOnClickListener { toggleCustomCmd() }
        binding.btnSendCmd.setOnClickListener { runCustomCommand() }
    }

    // ──────────────────────────────────────────────
    // Paste .env toggle & parse
    // ──────────────────────────────────────────────
    private fun toggleEnvPaste() {
        val layout = binding.layoutEnvPaste
        TransitionManager.beginDelayedTransition(
            binding.root.findViewById(android.R.id.content) ?: binding.root,
            AutoTransition().apply { duration = 200 }
        )
        layout.visibility = if (layout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun parseAndFillEnv() {
        val content = binding.etEnvPaste.text.toString()
        if (content.isBlank()) {
            Toast.makeText(this, "Nothing to parse", Toast.LENGTH_SHORT).show()
            return
        }

        val parsed = parseEnvContent(content)
        if (parsed.isEmpty()) {
            Toast.makeText(this, "No valid KEY=VALUE pairs found", Toast.LENGTH_SHORT).show()
            return
        }

        // Fill known fields
        parsed["API_ID"]?.let { binding.etApiId.setText(it) }
        parsed["API_HASH"]?.let { binding.etApiHash.setText(it) }
        parsed["BOT_TOKEN"]?.let { binding.etBotToken.setText(it) }
        parsed["SESSION_STRING"]?.let { binding.etSessionString.setText(it) }
        parsed["EXTRA_MODULES_REPO"]?.let { binding.etExtraRepo.setText(it) }
        parsed["DATABASE_URL"]?.let { binding.etDatabaseUrl.setText(it) }

        // Handle dual mode
        parsed["USE_DUAL_BRANCH"]?.let {
            if (it == "1" || it.equals("true", ignoreCase = true)) {
                binding.rbDual.isChecked = true
            }
        }

        // Store extra env vars not in the known set
        extraEnvVars.clear()
        parsed.forEach { (key, value) ->
            if (key !in KNOWN_ENV_KEYS) {
                extraEnvVars[key] = value
            }
        }

        Toast.makeText(this, getString(R.string.env_parsed, parsed.size), Toast.LENGTH_SHORT).show()
        appendLog("Parsed ${parsed.size} variables from pasted .env content")

        // Collapse the paste area
        binding.layoutEnvPaste.visibility = View.GONE
        binding.etEnvPaste.text?.clear()
    }

    /**
     * Parses .env file content into a map.
     * Supports: KEY=VALUE, export KEY=VALUE, "quoted values", 'single quoted', # comments
     */
    private fun parseEnvContent(content: String): Map<String, String> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val cleaned = line.removePrefix("export ").trim()
                val eqIndex = cleaned.indexOf('=')
                if (eqIndex > 0) {
                    val key = cleaned.substring(0, eqIndex).trim()
                    val value = cleaned.substring(eqIndex + 1).trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    key to value
                } else null
            }
            .toMap()
    }

    // ──────────────────────────────────────────────
    // Collapsible custom command
    // ──────────────────────────────────────────────
    private fun toggleCustomCmd() {
        val layout = binding.layoutCustomCmd
        val chevron = binding.ivChevronCmd
        TransitionManager.beginDelayedTransition(
            binding.root.findViewById(android.R.id.content) ?: binding.root,
            AutoTransition().apply { duration = 200 }
        )
        if (layout.visibility == View.VISIBLE) {
            layout.visibility = View.GONE
            chevron.rotation = 0f
        } else {
            layout.visibility = View.VISIBLE
            chevron.rotation = 180f
        }
    }

    private fun runCustomCommand() {
        val cmd = binding.etCustomCmd.text.toString().trim()
        if (cmd.isEmpty()) {
            Toast.makeText(this, "Enter a command first", Toast.LENGTH_SHORT).show()
            return
        }
        appendLog("» $cmd", isMuted = true)
        execInTermux(CMD_CUSTOM, cmd)
        binding.etCustomCmd.text?.clear()
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
        binding.etDatabaseUrl.setText(prefs.getString("database_url", ""))
        binding.etGeminiKey.setText(prefs.getString("gemini_key", ""))

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
            .putString("database_url", binding.etDatabaseUrl.text.toString().trim())
            .putString("gemini_key", binding.etGeminiKey.text.toString().trim())
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
    // Command builders (revised per creator's guide)
    // ──────────────────────────────────────────────
    private fun runSetup() {
        val apiId = binding.etApiId.text.toString().trim()
        val apiHash = binding.etApiHash.text.toString().trim()
        val botToken = binding.etBotToken.text.toString().trim()
        val session = binding.etSessionString.text.toString().trim()
        val extraRepo = binding.etExtraRepo.text.toString().trim()
        val databaseUrl = binding.etDatabaseUrl.text.toString().trim()

        if (apiId.isEmpty() || apiHash.isEmpty()) {
            Toast.makeText(this, "Save a valid config first!", Toast.LENGTH_SHORT).show()
            return
        }

        appendLog("═══ Starting Plain-UB Setup ═══", isSuccess = true)

        val script = buildString {
            appendLine("set -e")
            appendLine("echo '[1/6] Updating packages...'")
            appendLine("apt update -y && apt install -y python3 git curl python-pip ffmpeg python-numpy")
            appendLine()
            appendLine("echo '[2/6] Cloning / updating repository...'")
            appendLine("if [ ! -d ~/ubot/.git ]; then")
            appendLine("  git clone -q https://github.com/thedragonsinn/plain-ub ~/ubot")
            appendLine("else")
            appendLine("  cd ~/ubot && git fetch --all && git reset --hard origin/main")
            appendLine("fi")
            appendLine()
            appendLine("cd ~/ubot")
            appendLine("echo '[3/6] Writing config.env...'")
            appendLine("cp sample-config.env config.env 2>/dev/null || true")
            // Write config values via printf
            append("printf '%s\\n'")
            append(" 'API_ID=${apiId}'")
            append(" 'API_HASH=${apiHash}'")
            if (botToken.isNotEmpty()) append(" 'BOT_TOKEN=${botToken}'")
            if (session.isNotEmpty()) append(" 'SESSION_STRING=${session}'")
            if (extraRepo.isNotEmpty()) append(" 'EXTRA_MODULES_REPO=${extraRepo}'")
            if (databaseUrl.isNotEmpty()) append(" 'DATABASE_URL=${databaseUrl}'")
            if (isDualMode) append(" 'USE_DUAL_BRANCH=1'")
            append(" 'ENV_VARS=1'")
            // Include any extra env vars from paste
            extraEnvVars.forEach { (key, value) ->
                append(" '${key}=${value}'")
            }
            appendLine(" > config.env")
            appendLine()
            appendLine("echo '[4/6] Upgrading pip tools...'")
            appendLine("pip install -U setuptools wheel")
            appendLine()
            appendLine("echo '[5/6] Installing Termux requirements...'")
            appendLine("bash scripts/install_termux_reqs.sh 2>&1")
            appendLine()
            appendLine("echo '[6/6] Installing ub-core...'")
            appendLine("./scripts/install_ub_core.sh 2>&1")
            appendLine()
            appendLine("echo 'SETUP_COMPLETE'")
        }

        execInTermux(CMD_SETUP, script)
    }

    private fun startBot() {
        appendLog("═══ Starting Bot ═══")
        val script = """
            cd ~/ubot || { echo "NOT_INSTALLED"; exit 1; }
            if pgrep -f "python3 -m app" > /dev/null 2>&1; then
                echo "ALREADY_RUNNING"
            else
                nohup ./run >> bot.log 2>&1 &
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
            if [ ! -d ~/ubot ]; then
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
            if [ -f ~/ubot/bot.log ]; then
                tail -n 150 ~/ubot/bot.log
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
        // Always capture last output for AI diagnosis
        lastStdout = stdout
        lastStderr = stderr

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

            CMD_CUSTOM -> {
                if (stdout.isNotEmpty()) appendLog(stdout)
                if (stderr.isNotEmpty()) appendLog(stderr, isError = true)
                if (exitCode != 0 && stdout.isEmpty() && stderr.isEmpty()) {
                    appendLog("Command exited with code $exitCode", isMuted = true)
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Gemini AI Error Diagnosis
    // ──────────────────────────────────────────────
    private fun diagnoseWithGemini() {
        val apiKey = prefs.getString("gemini_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, getString(R.string.ai_no_key), Toast.LENGTH_SHORT).show()
            return
        }

        val errorContext = buildString {
            if (lastStderr.isNotBlank()) appendLine("STDERR:\n$lastStderr")
            if (lastStdout.isNotBlank()) {
                appendLine("STDOUT (last 100 lines):")
                appendLine(lastStdout.lines().takeLast(100).joinToString("\n"))
            }
        }
        if (errorContext.isBlank()) {
            Toast.makeText(this, getString(R.string.ai_no_error), Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        binding.layoutAiResponse.visibility = View.VISIBLE
        binding.tvAiResponse.text = getString(R.string.ai_analyzing)
        binding.progressAi.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = buildString {
                    appendLine("You are a Termux/Android package installation expert.")
                    appendLine("The user is running plain-ub setup on Termux (ARM Android).")
                    appendLine("Diagnose this error and provide the EXACT commands to fix it.")
                    appendLine("Be concise. Give the fix commands first, then a brief explanation.")
                    appendLine()
                    appendLine("--- ERROR LOG ---")
                    appendLine(errorContext.take(4000))
                }

                val jsonBody = JSONObject().apply {
                    put("model", "gemini-2.5-flash")
                    put("input", prompt)
                    put("tools", JSONArray().put(JSONObject().put("type", "google_search")))
                    put("generation_config", JSONObject().put("thinking_level", "high"))
                    put("system_instruction",
                        "You are a Termux expert. Be concise. Commands first, explanation second. " +
                        "Focus on practical fixes for pip install failures on ARM Android.")
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/interactions")
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Api-Revision", "2026-05-20")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: ${body.take(200)}"
                    }
                    withContext(Dispatchers.Main) {
                        binding.tvAiResponse.text = "API Error: $errorMsg"
                        binding.progressAi.visibility = View.GONE
                    }
                    return@launch
                }

                val json = JSONObject(body)

                // Extract text from the last model_output step
                val steps = json.optJSONArray("steps")
                var resultText = ""
                if (steps != null) {
                    for (i in steps.length() - 1 downTo 0) {
                        val step = steps.getJSONObject(i)
                        if (step.optString("type") == "model_output") {
                            val content = step.optJSONArray("content")
                            if (content != null) {
                                for (j in 0 until content.length()) {
                                    val block = content.getJSONObject(j)
                                    if (block.optString("type") == "text") {
                                        resultText = block.optString("text", "")
                                        break
                                    }
                                }
                            }
                            break
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.tvAiResponse.text = resultText.ifBlank { "No diagnosis available. Try again." }
                    binding.progressAi.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API error", e)
                withContext(Dispatchers.Main) {
                    binding.tvAiResponse.text = "Error: ${e.message}"
                    binding.progressAi.visibility = View.GONE
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
