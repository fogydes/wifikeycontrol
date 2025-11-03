package com.wifikeycontrol.protocol

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream
import kotlin.experimental.and

class ProtocolHandler {

    companion object {
        private const val TAG = "ProtocolHandler"

        // Protocol constants matching PC implementation
        private const val HEADER_MAGIC = 0xAABB
        private const val COMPRESSION_FLAG = 0x80

        // Packet types
        private const val TYPE_MOUSE_MOVEMENT = 0x01
        private const val TYPE_MOUSE_CLICK = 0x02
        private const val TYPE_KEYBOARD_KEY = 0x03
        private const val TYPE_SCROLL_EVENT = 0x04
        private const val TYPE_GESTURE_START = 0x05
        private const val TYPE_GESTURE_END = 0x06
        private const val TYPE_CONTROL_SWITCH = 0x07
        private const val TYPE_HEARTBEAT = 0x08
        private const val TYPE_JSON = 0xFF
        private const val TYPE_BATCH = 0xFE

        // Mouse buttons
        private val MOUSE_BUTTONS = mapOf(
            0x01 to "left",
            0x02 to "right",
            0x03 to "middle",
            0x04 to "x1",
            0x05 to "x2"
        )

        // Control switch edges
        private val CONTROL_EDGES = mapOf(
            0x01 to "left",
            0x02 to "right",
            0x03 to "top",
            0x04 to "bottom",
            0x05 to "hotkey",
            0x06 to "return_to_pc"
        )
    }

    private var packetSequence = 0

    /**
     * Parse incoming packet and return event data
     */
    fun parsePacket(packet: ByteArray): Map<String, Any>? {
        if (packet.size < 5) { // Minimum: HEADER(2) + TYPE(1) + CHECKSUM(2)
            Log.w(TAG, "Packet too short: ${packet.size} bytes")
            return null
        }

        try {
            // Read header
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val header = buffer.short.toInt() and 0xFFFF

            // Verify magic bytes
            if (header != HEADER_MAGIC) {
                Log.w(TAG, "Invalid header magic: 0x${header.toString(16)}")
                return null
            }

            // Read packet type
            val typeByte = buffer.get().toInt() and 0xFF
            val compressed = (typeByte and COMPRESSION_FLAG) != 0
            val packetType = typeByte and COMPRESSION_FLAG.inv()

            // Extract payload (everything after header+type, before checksum)
            val payloadSize = packet.size - 5 // Remove header(2) + type(1) + checksum(2)
            val payload = ByteArray(payloadSize)
            buffer.get(payload)

            // Decompress if needed
            val finalPayload = if (compressed) {
                decompressPayload(payload)
            } else {
                payload
            } ?: return null

            // Verify checksum
            if (!verifyChecksum(packet)) {
                Log.w(TAG, "Checksum verification failed")
                return null
            }

            // Parse based on packet type
            return when (packetType) {
                TYPE_MOUSE_MOVEMENT -> parseMouseMovementPacket(finalPayload)
                TYPE_MOUSE_CLICK -> parseMouseClickPacket(finalPayload)
                TYPE_KEYBOARD_KEY -> parseKeyboardPacket(finalPayload)
                TYPE_SCROLL_EVENT -> parseScrollPacket(finalPayload)
                TYPE_CONTROL_SWITCH -> parseControlSwitchPacket(finalPayload)
                TYPE_HEARTBEAT -> parseHeartbeatPacket(finalPayload)
                TYPE_JSON -> parseJsonPacket(finalPayload)
                TYPE_BATCH -> parseBatchPacket(finalPayload)
                else -> {
                    Log.w(TAG, "Unknown packet type: 0x${packetType.toString(16)}")
                    null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet: ${e.message}")
            return null
        }
    }

    /**
     * Create a packet for sending to PC
     */
    fun createPacket(eventData: Map<String, Any>): ByteArray? {
        return try {
            val eventType = eventData["type"] as? String ?: return null
            val timestamp = (eventData["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

            when (eventType) {
                "status_update" -> createStatusUpdatePacket(eventData, timestamp)
                "heartbeat_response" -> createHeartbeatResponsePacket(timestamp)
                "control_return" -> createControlReturnPacket(eventData, timestamp)
                else -> {
                    // Fallback to JSON
                    createJsonPacket(eventData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating packet: ${e.message}")
            null
        }
    }

    private fun parseMouseMovementPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val x = buffer.int
        val y = buffer.int
        val timestamp = buffer.long

        return mapOf(
            "type" to "mouse_move",
            "sequence" to sequence,
            "x" to x,
            "y" to y,
            "timestamp" to timestamp
        )
    }

    private fun parseMouseClickPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val x = buffer.int
        val y = buffer.int
        val button = buffer.get().toInt() and 0xFF
        val state = buffer.get().toInt() and 0xFF
        val timestamp = buffer.long

        val buttonName = MOUSE_BUTTONS[button] ?: "unknown"

        return mapOf(
            "type" to "mouse_click",
            "sequence" to sequence,
            "x" to x,
            "y" to y,
            "button" to buttonName,
            "pressed" to (state == 1),
            "timestamp" to timestamp
        )
    }

    private fun parseKeyboardPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val keyCode = buffer.int
        val state = buffer.get().toInt() and 0xFF
        val modifiers = buffer.get().toInt() and 0xFF
        val keyBytes = ByteArray(16)
        buffer.get(keyBytes)
        val timestamp = buffer.long

        val key = String(keyBytes).trimEnd('\u0000')
        val eventType = if (state == 1) "key_press" else "key_release"

        return mapOf(
            "type" to eventType,
            "sequence" to sequence,
            "key_code" to keyCode,
            "key" to key,
            "pressed" to (state == 1),
            "modifiers" to modifiers,
            "timestamp" to timestamp
        )
    }

    private fun parseScrollPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val x = buffer.int
        val y = buffer.int
        val dx = buffer.short.toInt() and 0xFFFF
        val dy = buffer.short.toInt() and 0xFFFF
        val timestamp = buffer.long

        return mapOf(
            "type" to "mouse_scroll",
            "sequence" to sequence,
            "x" to x,
            "y" to y,
            "dx" to dx,
            "dy" to dy,
            "timestamp" to timestamp
        )
    }

    private fun parseControlSwitchPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val edge = buffer.get().toInt() and 0xFF
        val reserved1 = buffer.get().toInt() and 0xFF
        val reserved2 = buffer.get().toInt() and 0xFF
        val reserved3 = buffer.get().toInt() and 0xFF
        val timestamp = buffer.long

        val edgeName = CONTROL_EDGES[edge] ?: "unknown"

        return mapOf(
            "type" to "control_switch",
            "sequence" to sequence,
            "edge" to edgeName,
            "timestamp" to timestamp
        )
    }

    private fun parseHeartbeatPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val timestamp = buffer.long

        return mapOf(
            "type" to "heartbeat",
            "sequence" to sequence,
            "timestamp" to timestamp
        )
    }

    private fun parseJsonPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val jsonLen = buffer.short.toInt() and 0xFFFF

        if (payload.size < 4 + jsonLen) {
            Log.w(TAG, "JSON packet too short")
            return emptyMap()
        }

        val jsonBytes = ByteArray(jsonLen)
        buffer.get(jsonBytes)
        val jsonString = String(jsonBytes)

        return try {
            val json = JSONObject(jsonString)
            val result = mutableMapOf<String, Any>()
            result["sequence"] = sequence

            json.keys().forEach { key ->
                result[key] = json.get(key).toString()
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON: ${e.message}")
            emptyMap()
        }
    }

    private fun parseBatchPacket(payload: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val jsonLen = buffer.short.toInt() and 0xFFFF

        if (payload.size < 4 + jsonLen) {
            Log.w(TAG, "Batch packet too short")
            return emptyMap()
        }

        val jsonBytes = ByteArray(jsonLen)
        buffer.get(jsonBytes)
        val jsonString = String(jsonBytes)

        return try {
            mapOf(
                "type" to "batch",
                "sequence" to sequence,
                "events" to jsonString
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing batch JSON: ${e.message}")
            emptyMap()
        }
    }

    private fun createStatusUpdatePacket(eventData: Map<String, Any>, timestamp: Long): ByteArray {
        val json = JSONObject(eventData).apply {
            put("type", "status")
            put("timestamp", timestamp)
        }

        return createJsonPacket(json.toString().toByteArray())
    }

    private fun createHeartbeatResponsePacket(timestamp: Long): ByteArray {
        val json = JSONObject().apply {
            put("type", "heartbeat_response")
            put("timestamp", timestamp)
        }

        return createJsonPacket(json.toString().toByteArray())
    }

    private fun createControlReturnPacket(eventData: Map<String, Any>, timestamp: Long): ByteArray {
        val json = JSONObject(eventData).apply {
            put("type", "control_return")
            put("timestamp", timestamp)
        }

        return createJsonPacket(json.toString().toByteArray())
    }

    private fun createJsonPacket(data: ByteArray): ByteArray {
        // Simple JSON packet with header and checksum
        val payload = ByteBuffer.allocate(4 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(packetSequence.toShort())
            .putShort(data.size.toShort())
            .put(data)
            .array()

        val packet = ByteBuffer.allocate(3 + payload.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(HEADER_MAGIC.toShort())
            .put(TYPE_JSON.toByte())
            .put(payload)
            .array()

        // Add checksum
        val checksum = calculateChecksum(packet)
        val finalPacket = ByteBuffer.allocate(packet.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(packet)
            .putShort(checksum.toShort())
            .array()

        packetSequence = (packetSequence + 1) % 65536
        return finalPacket
    }

    private fun decompressPayload(compressed: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(compressed)
            val buffer = ByteArray(4096)
            val result = ByteArrayOutputStream()

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                result.write(buffer, 0, count)
            }

            inflater.end()
            result.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Decompression error: ${e.message}")
            null
        }
    }

    private fun verifyChecksum(packet: ByteArray): Boolean {
        if (packet.size < 2) return false

        val receivedChecksum = ByteBuffer.wrap(packet)
            .order(ByteOrder.LITTLE_ENDIAN)
            .position(packet.size - 2)
            .short.toInt() and 0xFFFF

        val calculatedChecksum = calculateChecksum(packet.copyOfRange(0, packet.size - 2))
        return receivedChecksum == calculatedChecksum
    }

    private fun calculateChecksum(data: ByteArray): Int {
        var crc = 0xFFFF

        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                if ((crc and 1) != 0) {
                    crc = (crc shr 1) xor 0xA001
                } else {
                    crc = crc shr 1
                }
            }
        }

        return crc
    }

    /**
     * Get screen dimensions for coordinate mapping
     */
    fun getScreenDimensions(): Pair<Int, Int> {
        // This would typically use the WindowManager to get real screen dimensions
        // For now, return common Android resolutions
        return Pair(1080, 1920) // Default to 1080x1920 (portrait)
    }

    /**
     * Map PC coordinates to Android screen coordinates
     */
    fun mapCoordinates(pcX: Int, pcY: Int, pcWidth: Int, pcHeight: Int): Pair<Int, Int> {
        val (androidWidth, androidHeight) = getScreenDimensions()

        // Simple linear mapping
        val androidX = (pcX * androidWidth) / pcWidth
        val androidY = (pcY * androidHeight) / pcHeight

        return Pair(androidX, androidY)
    }
}