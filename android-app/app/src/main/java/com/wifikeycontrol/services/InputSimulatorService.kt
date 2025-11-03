package com.wifikeycontrol.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wifikeycontrol.R
import com.wifikeycontrol.protocol.ProtocolHandler
import kotlinx.coroutines.*

class InputSimulatorService : AccessibilityService() {

    companion object {
        private const val TAG = "InputSimulatorService"

        // Actions for receiving input events
        const val ACTION_PROCESS_INPUT_EVENT = "com.wifikeycontrol.PROCESS_INPUT_EVENT"
        const val EXTRA_EVENT_DATA = "event_data"

        // Control state
        private var isControlActive = false
        private var controlOverlay: View? = null
        private var overlayManager: WindowManager? = null
    }

    private lateinit var protocolHandler: ProtocolHandler
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Input event receiver
    private val inputEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PROCESS_INPUT_EVENT) {
                val eventData = intent.getBundleExtra(EXTRA_EVENT_DATA)
                if (eventData != null) {
                    processInputEvent(eventData)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "InputSimulatorService connected")

        protocolHandler = ProtocolHandler()

        // Register for input events
        val filter = IntentFilter(ACTION_PROCESS_INPUT_EVENT)
        ContextCompat.registerReceiver(this, inputEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "InputSimulatorService ready for input simulation")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events if needed
    }

    override fun onInterrupt() {
        Log.d(TAG, "InputSimulatorService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "InputSimulatorService destroyed")

        // Unregister receiver
        unregisterReceiver(inputEventReceiver)

        // Hide control overlay
        hideControlOverlay()

        // Cancel coroutines
        serviceScope.cancel()
    }

    private fun processInputEvent(eventData: Bundle) {
        try {
            val type = eventData.getString("type") ?: return

            when (type) {
                "mouse_move" -> processMouseMove(eventData)
                "mouse_click" -> processMouseClick(eventData)
                "mouse_scroll" -> processMouseScroll(eventData)
                "key_press", "key_release" -> processKeyboardEvent(eventData)
                "control_switch" -> processControlSwitch(eventData)
                else -> Log.w(TAG, "Unknown event type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing input event: ${e.message}")
        }
    }

    private fun processMouseMove(eventData: Bundle) {
        if (!isControlActive) return

        val x = eventData.getInt("x")
        val y = eventData.getInt("y")

        // Map PC coordinates to Android screen coordinates
        val (androidX, androidY) = protocolHandler.mapCoordinates(x, y, 1920, 1080)

        Log.d(TAG, "Mouse move: ($x, $y) -> ($androidX, $androidY)")

        // For smooth movement, we could implement drag gestures here
        // For now, just log the movement
    }

    private fun processMouseClick(eventData: Bundle) {
        if (!isControlActive) return

        val x = eventData.getInt("x")
        val y = eventData.getInt("y")
        val button = eventData.getString("button") ?: "left"
        val pressed = eventData.getBoolean("pressed")

        // Map PC coordinates to Android screen coordinates
        val (androidX, androidY) = protocolHandler.mapCoordinates(x, y, 1920, 1080)

        Log.d(TAG, "Mouse click: $button $pressed at ($androidX, $androidY)")

        if (pressed && button == "left") {
            // Perform click at the mapped coordinates
            performClick(androidX, androidY)
        }
    }

    private fun processMouseScroll(eventData: Bundle) {
        if (!isControlActive) return

        val x = eventData.getInt("x")
        val y = eventData.getInt("y")
        val dx = eventData.getInt("dx")
        val dy = eventData.getInt("dy")

        // Map PC coordinates to Android screen coordinates
        val (androidX, androidY) = protocolHandler.mapCoordinates(x, y, 1920, 1080)

        Log.d(TAG, "Mouse scroll: dx=$dx, dy=$dy at ($androidX, $androidY)")

        // Perform scroll gesture
        if (dy != 0) {
            performScroll(androidX, androidY, dy)
        }
    }

    private fun processKeyboardEvent(eventData: Bundle) {
        if (!isControlActive) return

        val key = eventData.getString("key") ?: ""
        val keyCode = eventData.getInt("key_code")
        val pressed = eventData.getBoolean("pressed")

        Log.d(TAG, "Keyboard: $key ($keyCode) $pressed")

        if (pressed) {
            // Send key to keyboard service
            sendKeyToKeyboardService(key, keyCode)
        }
    }

    private fun processControlSwitch(eventData: Bundle) {
        val edge = eventData.getString("edge") ?: ""

        Log.d(TAG, "Control switch: $edge")

        when (edge) {
            "left", "right", "top", "bottom", "hotkey" -> {
                // Switch control to Android
                isControlActive = true
                showControlOverlay()
            }
            "return_to_pc" -> {
                // Return control to PC
                isControlActive = false
                hideControlOverlay()
            }
        }
    }

    private fun performClick(x: Int, y: Int) {
        serviceScope.launch {
            try {
                // Create a click gesture
                val path = Path()
                path.moveTo(x.toFloat(), y.toFloat())

                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0, // Start immediately
                    100 // Duration in milliseconds
                )

                val gestureBuilder = GestureDescription.Builder()
                gestureBuilder.addStroke(stroke)

                val gesture = gestureBuilder.build()
                val result = dispatchGesture(gesture, null, null)

                Log.d(TAG, "Click gesture dispatched: $result")

            } catch (e: Exception) {
                Log.e(TAG, "Error performing click: ${e.message}")
            }
        }
    }

    private fun performScroll(x: Int, y: Int, deltaY: Int) {
        serviceScope.launch {
            try {
                // Create a scroll gesture
                val path = Path()
                val startY = y.toFloat()
                val endY = (y + deltaY).toFloat()

                path.moveTo(x.toFloat(), startY)
                path.lineTo(x.toFloat(), endY)

                val stroke = GestureDescription.StrokeDescription(
                    path,
                    0, // Start immediately
                    200 // Duration in milliseconds
                )

                val gestureBuilder = GestureDescription.Builder()
                gestureBuilder.addStroke(stroke)

                val gesture = gestureBuilder.build()
                val result = dispatchGesture(gesture, null, null)

                Log.d(TAG, "Scroll gesture dispatched: $result")

            } catch (e: Exception) {
                Log.e(TAG, "Error performing scroll: ${e.message}")
            }
        }
    }

    private fun sendKeyToKeyboardService(key: String, keyCode: Int) {
        try {
            val intent = Intent("com.wifikeycontrol.PROCESS_KEY_INPUT").apply {
                putExtra("key", key)
                putExtra("key_code", keyCode)
                putExtra("action", "key_press")
            }
            sendBroadcast(intent)

            // Send key release
            intent.putExtra("action", "key_release")
            sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending key to keyboard service: ${e.message}")
        }
    }

    private fun showControlOverlay() {
        if (controlOverlay != null) return

        try {
            overlayManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Create overlay view
            val inflater = LayoutInflater.from(this)
            controlOverlay = inflater.inflate(R.layout.control_overlay, null)

            // Set up overlay parameters
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            // Add overlay to window
            overlayManager?.addView(controlOverlay, params)

            // Update status text
            val statusText = controlOverlay?.findViewById<TextView>(R.id.control_status)
            statusText?.text = "PC Control Active"
            statusText?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))

            Log.d(TAG, "Control overlay shown")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing control overlay: ${e.message}")
        }
    }

    private fun hideControlOverlay() {
        if (controlOverlay == null) return

        try {
            overlayManager?.removeView(controlOverlay)
            controlOverlay = null
            Log.d(TAG, "Control overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding control overlay: ${e.message}")
        }
    }

    /**
     * Check if the service is enabled and ready
     */
    fun isServiceEnabled(): Boolean {
        return try {
            // This would typically check accessibility settings
            // For now, assume service is enabled if it's running
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get current control state
     */
    fun isUnderPCControl(): Boolean {
        return isControlActive
    }

    /**
     * Force return control to PC (called by user action)
     */
    fun returnControlToPC() {
        isControlActive = false
        hideControlOverlay()

        // Send control return message to PC
        val intent = Intent("com.wifikeycontrol.SEND_CONTROL_RETURN")
        sendBroadcast(intent)
    }
}