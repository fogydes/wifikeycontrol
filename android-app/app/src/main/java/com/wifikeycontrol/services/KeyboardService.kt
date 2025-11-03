package com.wifikeycontrol.services

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.KeyboardView
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class KeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "KeyboardService"

        // Action for receiving key events from InputSimulatorService
        const val ACTION_PROCESS_KEY_INPUT = "com.wifikeycontrol.PROCESS_KEY_INPUT"

        // Intent extras
        const val EXTRA_KEY = "key"
        const val EXTRA_KEY_CODE = "key_code"
        const val EXTRA_ACTION = "action" // "key_press" or "key_release"

        // Special key mappings
        private val KEY_CODE_MAP = mapOf(
            "enter" to KeyEvent.KEYCODE_ENTER,
            "backspace" to KeyEvent.KEYCODE_DEL,
            "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "tab" to KeyEvent.KEYCODE_TAB,
            "space" to KeyEvent.KEYCODE_SPACE,
            "escape" to KeyEvent.KEYCODE_ESCAPE,
            "shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
            "alt" to KeyEvent.KEYCODE_ALT_LEFT,
            "home" to KeyEvent.KEYCODE_HOME,
            "end" to KeyEvent.KEYCODE_MOVE_END,
            "page_up" to KeyEvent.KEYCODE_PAGE_UP,
            "page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "f1" to KeyEvent.KEYCODE_F1,
            "f2" to KeyEvent.KEYCODE_F2,
            "f3" to KeyEvent.KEYCODE_F3,
            "f4" to KeyEvent.KEYCODE_F4,
            "f5" to KeyEvent.KEYCODE_F5,
            "f6" to KeyEvent.KEYCODE_F6,
            "f7" to KeyEvent.KEYCODE_F7,
            "f8" to KeyEvent.KEYCODE_F8,
            "f9" to KeyEvent.KEYCODE_F9,
            "f10" to KeyEvent.KEYCODE_F10,
            "f11" to KeyEvent.KEYCODE_F11,
            "f12" to KeyEvent.KEYCODE_F12
        )
    }

    private var inputConnection: InputConnection? = null
    private var keyboardView: KeyboardView? = null

    // Key event receiver
    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == ACTION_PROCESS_KEY_INPUT) {
                val key = intent.getStringExtra(EXTRA_KEY) ?: ""
                val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, 0)
                val action = intent.getStringExtra(EXTRA_ACTION) ?: ""

                processKeyEvent(key, keyCode, action)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeyboardService created")

        // Register for key events
        val filter = IntentFilter(ACTION_PROCESS_KEY_INPUT)
        ContextCompat.registerReceiver(this, keyEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // These methods are deprecated in newer Android versions
    // We'll handle input session events through standard lifecycle methods

    override fun onBindInput() {
        super.onBindInput()
        inputConnection = currentInputConnection
        Log.d(TAG, "Input method bound")
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        inputConnection = null
        Log.d(TAG, "Input method unbound")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "Input view started")

        // Create a simple keyboard view (could be replaced with custom layout)
        // For now, we'll use a minimal keyboard view for demonstration
        createKeyboardView()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "Input view finished")

        // Clean up keyboard view
        keyboardView?.visibility = View.GONE
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "Input finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "KeyboardService destroyed")

        // Unregister receiver
        unregisterReceiver(keyEventReceiver)

        // Clean up keyboard view
        keyboardView = null
        inputConnection = null
    }

    private fun createKeyboardView() {
        // Create a minimal keyboard view for demonstration
        // In a real implementation, this would be a full keyboard layout
        // For our use case, we don't need to show a keyboard since we're receiving
        // key events from the PC and simulating them directly

        try {
            // Hide any visible keyboard view since we're operating in "invisible" mode
            val view = View(this)
            view.visibility = View.GONE
            setInputView(view)
            keyboardView = null

        } catch (e: Exception) {
            Log.e(TAG, "Error creating keyboard view: ${e.message}")
        }
    }

    private fun processKeyEvent(key: String, keyCode: Int, action: String) {
        try {
            Log.d(TAG, "Processing key: $key ($keyCode) $action")

            when (action) {
                "key_press" -> handleKeyPress(key, keyCode)
                "key_release" -> handleKeyRelease(key, keyCode)
                else -> Log.w(TAG, "Unknown key action: $action")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing key event: ${e.message}")
        }
    }

    private fun handleKeyPress(key: String, keyCode: Int) {
        val connection = inputConnection ?: return

        try {
            // Check if it's a special key or regular character
            val androidKeyCode = KEY_CODE_MAP[key.lowercase()] ?: keyCode

            if (androidKeyCode != 0) {
                // Special key - send key event
                val keyEvent = KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    androidKeyCode
                )
                connection.sendKeyEvent(keyEvent)
                Log.d(TAG, "Sent special key: $key -> $androidKeyCode")

            } else if (key.isNotEmpty() && key.length == 1) {
                // Regular character - commit text
                connection.commitText(key, 1)
                Log.d(TAG, "Sent character: $key")

            } else {
                // Try to handle as Unicode character or special text
                connection.commitText(key, 1)
                Log.d(TAG, "Sent text: $key")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling key press: ${e.message}")
        }
    }

    private fun handleKeyRelease(key: String, keyCode: Int) {
        val connection = inputConnection ?: return

        try {
            val androidKeyCode = KEY_CODE_MAP[key.lowercase()] ?: keyCode

            if (androidKeyCode != 0) {
                // Special key - send key release event
                val keyEvent = KeyEvent(
                    KeyEvent.ACTION_UP,
                    androidKeyCode
                )
                connection.sendKeyEvent(keyEvent)
                Log.d(TAG, "Sent special key release: $key -> $androidKeyCode")
            }

            // Regular characters don't need explicit release events
            // since we use commitText() for them

        } catch (e: Exception) {
            Log.e(TAG, "Error handling key release: ${e.message}")
        }
    }

    /**
     * Send text directly to the input field
     */
    fun sendText(text: String) {
        val connection = inputConnection ?: return

        try {
            connection.commitText(text, text.length)
            Log.d(TAG, "Sent text: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text: ${e.message}")
        }
    }

    /**
     * Send a delete operation (backspace)
     */
    fun sendDelete() {
        val connection = inputConnection ?: return

        try {
            connection.deleteSurroundingText(1, 0)
            Log.d(TAG, "Sent delete")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending delete: ${e.method}")
        }
    }

    /**
     * Send Enter key
     */
    fun sendEnter() {
        handleKeyPress("enter", KeyEvent.KEYCODE_ENTER)
        handleKeyRelease("enter", KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Clear the current input field
     */
    fun clearInput() {
        val connection = inputConnection ?: return

        try {
            // Select all and delete
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.performContextMenuAction(android.R.id.cut)
            Log.d(TAG, "Cleared input field")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing input: ${e.message}")
        }
    }

    /**
     * Check if the keyboard service is ready
     */
    fun isServiceReady(): Boolean {
        return inputConnection != null
    }

    inner class MyInputMethodSession : AbstractInputMethodService.AbstractInputMethodSessionImpl() {
        override fun onFinishInput() {
            super.onFinishInput()
            Log.d(TAG, "Input session finished")
        }

        override fun updateCursor(newCursor: android.view.inputmethod.ExtractedText?) {
            super.updateCursor(newCursor)
            Log.d(TAG, "Cursor updated")
        }

        override fun updateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
            super.updateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
            Log.d(TAG, "Selection updated: $newSelStart-$newSelEnd")
        }
    }
}