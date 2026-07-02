package com.xtunnel.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.xtunnel.android.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogsText: String? = null
    private val ipStrategyOptions: List<IpStrategyOption> by lazy {
        listOf(
            IpStrategyOption(getString(R.string.ip_strategy_auto), ""),
            IpStrategyOption(getString(R.string.ip_strategy_v4), "4"),
            IpStrategyOption(getString(R.string.ip_strategy_v6), "6"),
            IpStrategyOption(getString(R.string.ip_strategy_v4_v6), "4,6"),
            IpStrategyOption(getString(R.string.ip_strategy_v6_v4), "6,4"),
        )
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupIpStrategyDropdown()
        bindFormWatchers()
        bindActions()
        renderConfig(ConfigStore.loadConfig(this))
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        saveCurrentConfig()
    }

    private fun setupIpStrategyDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            ipStrategyOptions.map { it.label },
        )
        binding.ipStrategyInput.setAdapter(adapter)
    }

    private fun bindFormWatchers() {
        listOf(
            binding.forwardUrlInput,
            binding.tokenInput,
            binding.socksPortInput,
            binding.connectionNumInput,
            binding.dnsServerInput,
            binding.echDomainInput,
            binding.targetIpsInput,
            binding.udpBlockPortsInput,
        ).forEach { input ->
            input.doAfterTextChanged {
                updateGeneratedConfig()
                clearFieldErrors()
            }
        }

        binding.ipStrategyInput.doAfterTextChanged {
            updateGeneratedConfig()
        }
        binding.fallbackSwitch.setOnCheckedChangeListener { _, _ -> updateGeneratedConfig() }
        binding.insecureSwitch.setOnCheckedChangeListener { _, _ -> updateGeneratedConfig() }
    }

    private fun bindActions() {
        binding.startButton.setOnClickListener {
            val config = currentConfig()
            if (!validate(config)) {
                return@setOnClickListener
            }
            ensureNotificationPermission()
            ConfigStore.saveConfig(this, config)
            val intent = TunnelService.startIntent(this, config.toJson())
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, getString(R.string.starting_tunnel), Toast.LENGTH_SHORT).show()
        }

        binding.stopButton.setOnClickListener {
            startService(TunnelService.stopIntent(this))
            Toast.makeText(this, getString(R.string.stopping_tunnel), Toast.LENGTH_SHORT).show()
        }

        binding.restartButton.setOnClickListener {
            val config = currentConfig()
            if (!validate(config)) {
                return@setOnClickListener
            }
            ensureNotificationPermission()
            ConfigStore.saveConfig(this, config)
            val intent = TunnelService.startIntent(this, config.toJson())
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, getString(R.string.restarting_tunnel), Toast.LENGTH_SHORT).show()
        }

        binding.copyConfigButton.setOnClickListener {
            copyToClipboard("xtunnel-config", binding.generatedConfigView.text?.toString().orEmpty())
            Toast.makeText(this, getString(R.string.config_copied), Toast.LENGTH_SHORT).show()
        }

        binding.clearLogsButton.setOnClickListener {
            bridge.Bridge.clearLogs()
            lastLogsText = null
            updateLogsWindow(getString(R.string.no_logs))
            Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
        }

        binding.copyLogsButton.setOnClickListener {
            copyToClipboard("xtunnel-logs", binding.logsView.text?.toString().orEmpty())
            Toast.makeText(this, getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderConfig(config: TunnelConfig) {
        binding.forwardUrlInput.setText(config.forwardUrl)
        binding.tokenInput.setText(config.token)
        binding.socksPortInput.setText(config.socksPort)
        binding.connectionNumInput.setText(config.connectionNum)
        binding.dnsServerInput.setText(config.dnsServer)
        binding.echDomainInput.setText(config.echDomain)
        binding.targetIpsInput.setText(config.targetIps)
        binding.udpBlockPortsInput.setText(config.udpBlockPorts)
        binding.ipStrategyInput.setText(
            ipStrategyOptions.firstOrNull { it.value == config.ipStrategy }?.label
                ?: ipStrategyOptions.first().label,
            false,
        )
        binding.fallbackSwitch.isChecked = config.fallback
        binding.insecureSwitch.isChecked = config.insecure
        updateGeneratedConfig()
    }

    private fun currentConfig(): TunnelConfig {
        val ipStrategyLabel = binding.ipStrategyInput.text?.toString().orEmpty()
        val ipStrategyValue = ipStrategyOptions.firstOrNull { it.label == ipStrategyLabel }?.value.orEmpty()
        return TunnelConfig(
            forwardUrl = binding.forwardUrlInput.text?.toString().orEmpty(),
            token = binding.tokenInput.text?.toString().orEmpty(),
            socksPort = binding.socksPortInput.text?.toString().orEmpty(),
            connectionNum = binding.connectionNumInput.text?.toString().orEmpty(),
            dnsServer = binding.dnsServerInput.text?.toString().orEmpty(),
            echDomain = binding.echDomainInput.text?.toString().orEmpty(),
            targetIps = binding.targetIpsInput.text?.toString().orEmpty(),
            udpBlockPorts = binding.udpBlockPortsInput.text?.toString().orEmpty(),
            ipStrategy = ipStrategyValue,
            insecure = binding.insecureSwitch.isChecked,
            fallback = binding.fallbackSwitch.isChecked,
        )
    }

    private fun validate(config: TunnelConfig): Boolean {
        clearFieldErrors()
        var valid = true
        val forwardUrl = config.forwardUrl.trim()
        if (!(forwardUrl.startsWith("ws://") || forwardUrl.startsWith("wss://"))) {
            binding.forwardUrlLayout.error = getString(R.string.validation_forward_url)
            valid = false
        }
        if (!isValidPort(config.socksPort)) {
            binding.socksPortLayout.error = getString(R.string.validation_port)
            valid = false
        }
        if (config.socksPort.isBlank()) {
            binding.socksPortLayout.error = getString(R.string.validation_socks_required)
            valid = false
        }
        if ((config.connectionNum.trim().toIntOrNull() ?: 0) <= 0) {
            binding.connectionNumLayout.error = getString(R.string.validation_connection_num)
            valid = false
        }
        if (config.dnsServer.trim().isEmpty()) {
            binding.dnsServerLayout.error = getString(R.string.validation_dns_required)
            valid = false
        }
        if (config.echDomain.trim().isEmpty()) {
            binding.echDomainLayout.error = getString(R.string.validation_ech_required)
            valid = false
        }
        return valid
    }

    private fun isValidPort(raw: String): Boolean {
        if (raw.isBlank()) {
            return true
        }
        val port = raw.trim().toIntOrNull() ?: return false
        return port in 1..65535
    }

    private fun clearFieldErrors() {
        binding.forwardUrlLayout.error = null
        binding.socksPortLayout.error = null
        binding.connectionNumLayout.error = null
        binding.dnsServerLayout.error = null
        binding.echDomainLayout.error = null
    }

    private fun updateGeneratedConfig() {
        binding.generatedConfigView.text = currentConfig().toJson()
    }

    private fun saveCurrentConfig() {
        ConfigStore.saveConfig(this, currentConfig())
    }

    private fun refreshUi() {
        val statusJson = bridge.Bridge.statusJSON()
        val config = currentConfig()
        val root = try {
            JSONObject(statusJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val running = root.optBoolean("running", false)
        val activeChannels = root.optInt("active_channels", 0)
        val forwardAddr = root.optString("forward_addr").ifBlank { config.forwardUrl.trim() }
        val endpoints = formatEndpoints(root.optJSONArray("listen_addrs"), config)

        binding.statusChip.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        val chipBg = ContextCompat.getColor(
            this,
            if (running) R.color.chip_running_bg else R.color.chip_stopped_bg,
        )
        val chipText = ContextCompat.getColor(
            this,
            if (running) R.color.chip_running_text else R.color.chip_stopped_text,
        )
        binding.statusChip.chipBackgroundColor = ColorStateList.valueOf(chipBg)
        binding.statusChip.setTextColor(chipText)
        binding.statusSummaryView.text =
            if (running) {
                getString(R.string.status_running_summary, forwardAddr, activeChannels)
            } else {
                getString(R.string.status_idle_summary)
            }

        binding.endpointsView.text = buildString {
            appendLine(getString(R.string.local_endpoints))
            appendLine(endpoints)
            if (forwardAddr.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.forward_url_label))
                appendLine(forwardAddr)
            }
            root.optString("client_id").takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(getString(R.string.client_id_label))
                appendLine(it)
            }
        }.trim()

        val logs = bridge.Bridge.logs().ifBlank { getString(R.string.no_logs) }
        updateLogsWindow(logs)
        binding.startButton.isEnabled = !running
        binding.stopButton.isEnabled = running
        binding.restartButton.isEnabled = true
    }

    private fun updateLogsWindow(logs: String) {
        if (logs == lastLogsText) {
            return
        }
        val scrollView = binding.logsScrollView
        val distanceToBottom =
            binding.logsView.bottom - (scrollView.scrollY + scrollView.height)
        val shouldStickToBottom = distanceToBottom <= 48
        val previousScrollY = scrollView.scrollY
        binding.logsView.text = logs
        lastLogsText = logs
        scrollView.post {
            if (shouldStickToBottom) {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            } else {
                scrollView.scrollTo(0, previousScrollY)
            }
        }
    }

    private fun formatEndpoints(listenAddrs: JSONArray?, fallbackConfig: TunnelConfig): String {
        val lines = mutableListOf<String>()
        if (listenAddrs != null && listenAddrs.length() > 0) {
            for (i in 0 until listenAddrs.length()) {
                lines += listenAddrs.optString(i)
            }
        }
        if (lines.isEmpty()) {
            lines += fallbackConfig.buildListenAddrs()
        }
        return if (lines.isEmpty()) {
            getString(R.string.no_listeners)
        } else {
            lines.joinToString("\n")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private data class IpStrategyOption(val label: String, val value: String)
}
