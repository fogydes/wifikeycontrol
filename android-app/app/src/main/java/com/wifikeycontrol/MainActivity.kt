package com.wifikeycontrol

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.wifikeycontrol.services.ConnectionService
import com.wifikeycontrol.services.InputSimulatorService
import com.wifikeycontrol.services.KeyboardService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Permission request codes
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_ACCESSIBILITY_PERMISSION = 1002
        private const val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1003
    }

    // UI Components
    private lateinit var statusTextView: TextView
    private lateinit var connectionStatusTextView: TextView
    private lateinit var serverIpEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    private lateinit var settingsButton: Button
    private lateinit var logsTextView: TextView

    // Service state
    private var isServiceStarted = false
    private var isConnected = false

    // BroadcastReceiver for connection status updates
    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectionService.ACTION_CONNECTION_STATUS_CHANGED) {
                val connected = intent.getBooleanExtra(ConnectionService.EXTRA_IS_CONNECTED, false)
                val deviceName = intent.getStringExtra(ConnectionService.EXTRA_DEVICE_NAME) ?: ""
                val errorMessage = intent.getStringExtra(ConnectionService.EXTRA_ERROR_MESSAGE) ?: ""

                updateConnectionStatus(connected, deviceName, errorMessage)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity created")

        // Initialize UI
        initializeUI()

        // Set up event listeners
        setupEventListeners()

        // Register for connection status updates
        registerReceiver(connectionStatusReceiver, IntentFilter(ConnectionService.ACTION_CONNECTION_STATUS_CHANGED))

        // Check and request permissions
        checkPermissions()

        // Load saved settings
        loadSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")

        // Unregister receiver
        unregisterReceiver(connectionStatusReceiver)

        // Save settings
        saveSettings()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        addLog("Overlay permission granted")
                    } else {
                        addLog("Overlay permission denied")
                    }
                }
            }
            REQUEST_ACCESSIBILITY_PERMISSION -> {
                checkAccessibilityPermission()
            }
        }
    }

    private fun initializeUI() {
        // Find UI components
        statusTextView = findViewById(R.id.status_text)
        connectionStatusTextView = findViewById(R.id.connection_status_text)
        serverIpEditText = findViewById(R.id.server_ip_edit)
        serverPortEditText = findViewById(R.id.server_port_edit)
        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        startServiceButton = findViewById(R.id.start_service_button)
        stopServiceButton = findViewById(R.id.stop_service_button)
        settingsButton = findViewById(R.id.settings_button)
        logsTextView = findViewById(R.id.logs_text)

        // Set up logs text view
        logsTextView.movementMethod = LinkMovementMethod.getInstance()

        // Initialize UI state
        updateUIState()
        addLog("WiFi Key Control started")
    }

    private fun setupEventListeners() {
        // Connect button
        connectButton.setOnClickListener {
            val ip = serverIpEditText.text.toString().trim()
            val portText = serverPortEditText.text.toString().trim()

            if (ip.isEmpty()) {
                showError("Please enter a server IP address")
                return@setOnClickListener
            }

            val port = try {
                portText.toInt()
            } catch (e: NumberFormatException) {
                showError("Please enter a valid port number")
                return@setOnClickListener
            }

            connectToServer(ip, port)
        }

        // Disconnect button
        disconnectButton.setOnClickListener {
            disconnectFromServer()
        }

        // Start service button
        startServiceButton.setOnClickListener {
            startConnectionService()
        }

        // Stop service button
        stopServiceButton.setOnClickListener {
            stopConnectionService()
        }

        // Settings button
        settingsButton.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun connectToServer(ip: String, port: Int) {
        Log.d(TAG, "Connecting to server $ip:$port")
        addLog("Connecting to $ip:$port...")

        // Start connection service if not already started
        if (!isServiceStarted) {
            startConnectionService()
        }

        // Send connect command to service
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_CONNECT
            putExtra(ConnectionService.EXTRA_SERVER_IP, ip)
            putExtra(ConnectionService.EXTRA_SERVER_PORT, port)
        }
        startService(intent)
    }

    private fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")
        addLog("Disconnecting...")

        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun startConnectionService() {
        Log.d(TAG, "Starting connection service")
        addLog("Starting service...")

        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START_SERVICE
        }
        startService(intent)

        isServiceStarted = true
        updateUIState()
    }

    private fun stopConnectionService() {
        Log.d(TAG, "Stopping connection service")
        addLog("Stopping service...")

        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_STOP_SERVICE
        }
        startService(intent)

        isServiceStarted = false
        isConnected = false
        updateUIState()
    }

    private fun updateConnectionStatus(connected: Boolean, deviceName: String, errorMessage: String) {
        Log.d(TAG, "Connection status updated: connected=$connected, device=$deviceName")
        isConnected = connected

        if (connected) {
            addLog("Connected to $deviceName")
            connectionStatusTextView.text = "Connected to $deviceName"
            connectionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            if (errorMessage.isNotEmpty()) {
                addLog("Connection failed: $errorMessage")
                connectionStatusTextView.text = "Connection failed: $errorMessage"
                connectionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            } else {
                addLog("Disconnected")
                connectionStatusTextView.text = "Disconnected"
                connectionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
        }

        updateUIState()
    }

    private fun updateUIState() {
        // Update buttons based on service and connection state
        startServiceButton.isEnabled = !isServiceStarted
        stopServiceButton.isEnabled = isServiceStarted
        connectButton.isEnabled = isServiceStarted && !isConnected
        disconnectButton.isEnabled = isServiceStarted && isConnected

        // Update status text
        if (isServiceStarted) {
            statusTextView.text = "Service Running"
            statusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        } else {
            statusTextView.text = "Service Stopped"
            statusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check overlay permission
            if (!Settings.canDrawOverlays(this)) {
                showPermissionDialog(
                    "Overlay Permission Required",
                    "WiFi Key Control needs overlay permission to display control status. Please grant this permission.",
                    REQUEST_OVERLAY_PERMISSION
                )
            }

            // Check accessibility permission
            checkAccessibilityPermission()

            // Check battery optimization
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun checkAccessibilityPermission() {
        try {
            val accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            ) == 1

            if (!accessibilityEnabled) {
                showAccessibilityPermissionDialog()
            } else {
                addLog("Accessibility permission granted")
            }
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Error checking accessibility permission: ${e.message}")
        }
    }

    private fun showAccessibilityPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_permission_title)
            .setMessage(R.string.accessibility_permission_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, REQUEST_ACCESSIBILITY_PERMISSION)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting battery optimization permission: ${e.message}")
            }
        }
    }

    private fun showPermissionDialog(title: String, message: String, requestCode: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Grant") { _, _ ->
                if (requestCode == REQUEST_OVERLAY_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Settings dialog will be implemented in Phase 3")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        addLog("Error: $message")
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message\n"

        logsTextView.append(logEntry)

        // Auto-scroll to bottom
        val layout = logsTextView.layout
        if (layout != null) {
            val scrollAmount = layout.getLineTop(logsTextView.lineCount) - logsTextView.height
            if (scrollAmount > 0) {
                logsTextView.scrollTo(0, scrollAmount)
            }
        }

        Log.d(TAG, "Log: $message")
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("wifikeycontrol_prefs", MODE_PRIVATE)
        serverIpEditText.setText(prefs.getString("server_ip", getString(R.string.default_server_ip)))
        serverPortEditText.setText(prefs.getString("server_port", getString(R.string.default_server_port)))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("wifikeycontrol_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_ip", serverIpEditText.text.toString().trim())
            putString("server_port", serverPortEditText.text.toString().trim())
            apply()
        }
    }
}