package com.wifikeycontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "wifi_key_control_prefs"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupUI()
        loadPreferences()
    }

    private fun setupUI() {
        // Server IP input
        val ipEditText = findViewById<EditText>(R.id.serverIpInput)
        val portEditText = findViewById<EditText>(R.id.serverPortInput)
        val autoConnectSwitch = findViewById<Switch>(R.id.autoConnectSwitch)
        val keepScreenOnSwitch = findViewById<Switch>(R.id.keepScreenOnSwitch)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val accessibilityButton = findViewById<Button>(R.id.accessibilitySettingsBtn)
        val keyboardButton = findViewById<Button>(R.id.keyboardSettingsBtn)

        // Save button
        saveButton?.setOnClickListener {
            savePreferences(
                ipEditText?.text?.toString() ?: "",
                portEditText?.text?.toString()?.toIntOrNull() ?: 12346,
                autoConnectSwitch?.isChecked ?: false,
                keepScreenOnSwitch?.isChecked ?: false
            )
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        // Accessibility settings shortcut
        accessibilityButton?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Keyboard settings shortcut
        keyboardButton?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Keyboard settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        findViewById<EditText>(R.id.serverIpInput)?.setText(
            prefs.getString(KEY_SERVER_IP, "")
        )
        findViewById<EditText>(R.id.serverPortInput)?.setText(
            prefs.getInt(KEY_SERVER_PORT, 12346).toString()
        )
        findViewById<Switch>(R.id.autoConnectSwitch)?.isChecked =
            prefs.getBoolean(KEY_AUTO_CONNECT, false)
        findViewById<Switch>(R.id.keepScreenOnSwitch)?.isChecked =
            prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
    }

    private fun savePreferences(ip: String, port: Int, autoConnect: Boolean, keepScreenOn: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SERVER_IP, ip)
            putInt(KEY_SERVER_PORT, port)
            putBoolean(KEY_AUTO_CONNECT, autoConnect)
            putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
            apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}