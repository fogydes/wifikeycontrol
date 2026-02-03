package com.wifikeycontrol.protocol

import android.util.Log
import wifikeycontrol.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FlatBuffers-based protocol handler.
 * 
 * Protocol:
 * - Messages are length-prefixed (4 bytes, little-endian)
 * - FlatBuffer payload follows immediately
 * - Handshake messages remain JSON over TCP (newline-delimited)
 */
class FlatBuffersHandler {

    companion object {
        private const val TAG = "FlatBuffersHandler"
    }

    /**
     * Parse a FlatBuffer InputEvent from raw bytes.
     * Returns null if parsing fails.
     */
    fun parseEvent(data: ByteArray): ParsedEvent? {
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val event = InputEvent.getRootAsInputEvent(buffer)
            
            when (event.type) {
                EventType.MouseMoveRel -> {
                    val move = event.mouseMove
                    if (move != null) {
                        ParsedEvent.MouseMove(move.dx, move.dy)
                    } else null
                }
                
                EventType.MouseClick -> {
                    val click = event.mouseClick
                    if (click != null) {
                        ParsedEvent.MouseClick(
                            button = click.button,
                            pressed = click.pressed
                        )
                    } else null
                }
                
                EventType.MouseScroll -> {
                    val scroll = event.mouseScroll
                    if (scroll != null) {
                        ParsedEvent.MouseScroll(scroll.dx, scroll.dy)
                    } else null
                }
                
                EventType.KeyPress, EventType.KeyRelease -> {
                    val key = event.key
                    if (key != null) {
                        ParsedEvent.Key(
                            keycode = key.keycode,
                            keyName = key.key ?: "",
                            modifiers = key.modifiers.toInt(),
                            pressed = event.type == EventType.KeyPress
                        )
                    } else null
                }
                
                EventType.ControlSwitch -> {
                    val ctrl = event.controlSwitch
                    if (ctrl != null) {
                        ParsedEvent.ControlSwitch(ctrl.edge)
                    } else null
                }
                
                EventType.Heartbeat -> {
                    ParsedEvent.Heartbeat
                }
                
                EventType.HeartbeatAck -> {
                    ParsedEvent.HeartbeatAck
                }
                
                else -> {
                    Log.w(TAG, "Unknown event type: ${event.type}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse FlatBuffer event: ${e.message}")
            null
        }
    }

    /**
     * Read a length-prefixed message from input stream data.
     * Returns pair of (parsed event, bytes consumed) or null if incomplete.
     */
    fun tryReadMessage(buffer: ByteArray, offset: Int = 0): Pair<ParsedEvent?, Int>? {
        val remaining = buffer.size - offset
        
        // Need at least 4 bytes for length prefix
        if (remaining < 4) {
            return null
        }
        
        // Read length (little-endian uint32)
        val len = ((buffer[offset].toInt() and 0xFF)) or
                  ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                  ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                  ((buffer[offset + 3].toInt() and 0xFF) shl 24)
        
        // Sanity check length
        if (len <= 0 || len > 65536) {
            Log.w(TAG, "Invalid message length: $len")
            return Pair(null, 4) // Skip the bad length
        }
        
        // Check if we have the full message
        if (remaining < 4 + len) {
            return null
        }
        
        // Extract and parse the FlatBuffer payload
        val payload = buffer.copyOfRange(offset + 4, offset + 4 + len)
        val event = parseEvent(payload)
        
        return Pair(event, 4 + len)
    }
}

/**
 * Sealed class representing parsed input events.
 */
sealed class ParsedEvent {
    data class MouseMove(val dx: Short, val dy: Short) : ParsedEvent()
    data class MouseClick(val button: Byte, val pressed: Boolean) : ParsedEvent()
    data class MouseScroll(val dx: Short, val dy: Short) : ParsedEvent()
    data class Key(val keycode: Int, val keyName: String, val modifiers: Int, val pressed: Boolean) : ParsedEvent()
    data class ControlSwitch(val edge: Byte) : ParsedEvent()
    data object Heartbeat : ParsedEvent()
    data object HeartbeatAck : ParsedEvent()
}
