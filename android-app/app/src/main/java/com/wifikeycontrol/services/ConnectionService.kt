package com.wifikeycontrol.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import com.wifikeycontrol.R
import com.wifikeycontrol.protocol.ProtocolHandler
import android.os.Bundle

class ConnectionService : Service() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WiFiKeyControlChannel"

        // Network constants from planning.md
        private const val DEFAULT_SERVER_PORT = 12346
        private const val DISCOVERY_PORT = 12345
        private const val DISCOVERY_MESSAGE = "WIFIKEY_DISCOVERY"
        private const val DISCOVERY_RESPONSE = "WIFIKEY_RESPONSE"
        private const val DISCOVERY_INVITE = "WIFIKEY_INVITE"

        // Connection settings
        private const val CONNECTION_TIMEOUT = 10000 // 10 seconds
        private const val HEARTBEAT_INTERVAL = 5000L // 5 seconds
        private const val RECONNECT_DELAY = 3000L // 3 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 5

        // Intent actions
        const val ACTION_START_SERVICE = "com.wifikeycontrol.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.wifikeycontrol.STOP_SERVICE"
        const val ACTION_CONNECT = "com.wifikeycontrol.CONNECT"
        const val ACTION_DISCONNECT = "com.wifikeycontrol.DISCONNECT"
        const val ACTION_INVITATION_RECEIVED = "com.wifikeycontrol.INVITATION_RECEIVED"

        // Intent extras
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_PC_NAME = "pc_name"

        // Broadcast actions
        const val ACTION_CONNECTION_STATUS_CHANGED = "com.wifikeycontrol.CONNECTION_STATUS_CHANGED"
        const val EXTRA_IS_CONNECTED = "is_connected"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    // Service state
    private var serviceStarted = false
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Network components
    private var socket: Socket? = null
    private var inputReader: BufferedReader? = null
    private var outputWriter: BufferedWriter? = null
    private var discoverySocket: DatagramSocket? = null

    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null

    // Connection state
    private var serverIp: String = "192.168.1.100"
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var deviceName: String = ""
    private var connectedDeviceName: String = ""
    private var reconnectAttempts = 0

    // Protocol handler
    private lateinit var protocolHandler: ProtocolHandler

    // System services
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConnectionService onCreate")

        // Initialize system services
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Get device name
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Initialize protocol handler
        protocolHandler = ProtocolHandler()

        // Create notification channel
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startServiceInternal()
            }
            ACTION_STOP_SERVICE -> {
                stopServiceInternal()
            }
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) ?: serverIp
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, serverPort)
                connectToServer(ip, port)
            }
            ACTION_DISCONNECT -> {
                disconnectFromServer()
            }
        }

        return START_STICKY // Service will be restarted if killed
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    override fun onDestroy() {
        Log.d(TAG, "ConnectionService onDestroy")
        super.onDestroy()

        // Cleanup resources
        disconnectFromServer()
        serviceScope.cancel()
        isRunning.set(false)
    }

    private fun startServiceInternal() {
        if (serviceStarted) {
            Log.d(TAG, "Service already started")
            return
        }

        Log.d(TAG, "Starting ConnectionService")
        serviceStarted = true
        isRunning.set(true)

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification("Service started", false))

        // Start discovery listener
        startDiscoveryListener()

        Log.d(TAG, "ConnectionService started successfully")
    }

    private fun stopServiceInternal() {
        if (!serviceStarted) {
            Log.d(TAG, "Service not started")
            return
        }

        Log.d(TAG, "Stopping ConnectionService")
        serviceStarted = false
        isRunning.set(false)

        // Disconnect if connected
        disconnectFromServer()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "ConnectionService stopped")
    }

    private fun connectToServer(ip: String, port: Int) {
        if (isConnected.get()) {
            Log.d(TAG, "Already connected to server")
            return
        }

        serverIp = ip
        serverPort = port
        reconnectAttempts = 0

        Log.d(TAG, "Connecting to server $ip:$port")

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
                // Update notification
                updateNotification("Connecting to $ip:$port...", false)

                // Create socket with timeout
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)

                // Setup I/O streams
                inputReader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                outputWriter = BufferedWriter(OutputStreamWriter(socket?.getOutputStream()))

                // Perform handshake
                if (performHandshake()) {
                    isConnected.set(true)
                    reconnectAttempts = 0

                    Log.d(TAG, "Connected to server successfully")
                    updateNotification("Connected to PC", true)
                    broadcastConnectionStatus(true, connectedDeviceName) // Using connectedDeviceName here

                    // Start heartbeat
                    startHeartbeat()

                    // Start message listener
                    startMessageListener()

                } else {
                    Log.e(TAG, "Handshake failed")
                    throw Exception("Handshake failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                isConnected.set(false)
                updateNotification("Connection failed: ${e.message}", false)
                broadcastConnectionStatus(false, e.message ?: "Unknown error")

                // Attempt reconnection if enabled
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    Log.d(TAG, "Reconnection attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
                    delay(RECONNECT_DELAY)
                    if (isRunning.get()) {
                        connectToServer(ip, port)
                    }
                }
            }
        }
    }

    private fun disconnectFromServer() {
        Log.d(TAG, "Disconnecting from server")

        isConnected.set(false)

        // Cancel coroutines
        connectionJob?.cancel()
        heartbeatJob?.cancel()

        // Close socket and streams
        try {
            outputWriter?.close()
            inputReader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        } finally {
            outputWriter = null
            inputReader = null
            socket = null
        }

        // Update UI
        updateNotification("Disconnected", false)
        broadcastConnectionStatus(false, "") // This should use connectedDeviceName if it was ever set

        Log.d(TAG, "Disconnected from server")
    }

    private suspend fun performHandshake(): Boolean {
        return try {
            // Receive handshake request
            val handshakeJson = inputReader?.readLine()
            if (handshakeJson == null) {
                Log.e(TAG, "No handshake received")
                return false
            }

            val handshake = JSONObject(handshakeJson)
            if (handshake.getString("type") != "handshake") {
                Log.e(TAG, "Invalid handshake type")
                return false
            }
            
            // Get PC name from handshake
            if (handshake.has("device_name")) {
                connectedDeviceName = handshake.getString("device_name")
            } else {
                connectedDeviceName = "Unknown PC"
            }
            
            // Get Screen Dimensions
            val screenWidth = if (handshake.has("screen_width")) handshake.getInt("screen_width") else 1920
            val screenHeight = if (handshake.has("screen_height")) handshake.getInt("screen_height") else 1080
            
            // Save to prefs for InputSimulator
            getSharedPreferences("wifikeycontrol_prefs", Context.MODE_PRIVATE).edit().apply {
                putInt("pc_screen_width", screenWidth)
                putInt("pc_screen_height", screenHeight)
                apply()
            }

            // Send handshake response
            val response = JSONObject().apply {
                put("type", "handshake_response")
                put("device_name", deviceName)
                put("version", "1.0")
                put("timestamp", System.currentTimeMillis())
            }

            outputWriter?.write("$response\n")
            outputWriter?.flush()

            Log.d(TAG, "Handshake completed successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Handshake error: ${e.message}")
            false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isConnected.get() && isRunning.get()) {
                try {
                    // Send heartbeat
                    val heartbeat = JSONObject().apply {
                        put("type", "heartbeat")
                        put("timestamp", System.currentTimeMillis())
                    }

                    outputWriter?.write("$heartbeat\n")
                    outputWriter?.flush()

                    delay(HEARTBEAT_INTERVAL)

                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun startMessageListener() {
        serviceScope.launch {
            try {
                val inputStream = socket?.getInputStream()
                if (inputStream == null) {
                    Log.e(TAG, "Input stream is null")
                    return@launch
                }

                val recvBuf = ByteArray(4096)
                val bufferStream = java.io.ByteArrayOutputStream()

                while (isConnected.get() && isRunning.get()) {
                    val read = try {
                        inputStream.read(recvBuf)
                    } catch (e: Exception) {
                        Log.e(TAG, "Socket read error: ${e.message}")
                        -1
                    }

                    if (read <= 0) {
                        Log.d(TAG, "Server disconnected or read 0 bytes")
                        break
                    }

                    bufferStream.write(recvBuf, 0, read)
                    var buffer = bufferStream.toByteArray()

                    // Process as many complete messages/packets as possible
                    var offset = 0
                    while (offset < buffer.size) {
                        // Need at least header(2) + type(1) + checksum(2) to attempt parse
                        if (buffer.size - offset < 5) break

                        // Check for header magic (little-endian 0xAABB -> bytes 0xBB,0xAA)
                        val b0 = buffer[offset].toInt() and 0xFF
                        val b1 = buffer[offset + 1].toInt() and 0xFF
                        val header = (b1 shl 8) or b0

                        if (header == 0xAABB) {
                            val typeByte = buffer[offset + 2].toInt() and 0xFF
                            val compressed = (typeByte and 0x80) != 0
                            val packetType = typeByte and 0x7F

                            // Determine expected full packet length
                            val fullLen = when (packetType) {
                                0x01 -> 3 + 18 + 2 // mouse movement
                                0x02 -> 3 + 20 + 2 // mouse click
                                0x03 -> 3 + 32 + 2 // keyboard
                                0x04 -> 3 + 22 + 2 // scroll
                                0x07 -> 3 + 14 + 2 // control switch
                                0x08 -> 3 + 10 + 2 // heartbeat
                                0xFE, 0xFF -> {
                                    // Need at least 4 bytes of payload to read json_len (seq(2)+len(2))
                                    if (buffer.size - offset < 3 + 4) break
                                    val lenLo = buffer[offset + 5].toInt() and 0xFF
                                    val lenHi = buffer[offset + 6].toInt() and 0xFF
                                    val jsonLen = (lenHi shl 8) or lenLo
                                    3 + 4 + jsonLen + 2
                                }
                                else -> {
                                    // Unknown type: try to find next header to resync
                                    -1
                                }
                            }

                            if (fullLen == -1) {
                                // Can't determine size; attempt to resync by searching for next header
                                val nextHeader = indexOfHeader(buffer, offset + 1)
                                if (nextHeader >= 0) {
                                    offset = nextHeader
                                    continue
                                } else {
                                    // Wait for more data
                                    break
                                }
                            }

                            if (buffer.size - offset < fullLen) break // wait for more data

                            val packetBytes = buffer.copyOfRange(offset, offset + fullLen)
                            val eventData = protocolHandler.parsePacket(packetBytes)
                            if (eventData != null) {
                                // Dispatch event to input simulator
                                processInputEventFromPacket(eventData)
                            } else {
                                Log.w(TAG, "Failed to parse packet or checksum mismatch")
                            }

                            offset += fullLen
                        } else {
                            // Not a binary packet at current offset — try to find newline (JSON) or next header
                            val newlineIndex = indexOfByte(buffer, '\n'.code.toByte(), offset)
                            if (newlineIndex >= 0) {
                                val lineBytes = buffer.copyOfRange(offset, newlineIndex)
                                val line = try {
                                    String(lineBytes)
                                } catch (e: Exception) {
                                    null
                                }

                                if (line != null && line.trim().isNotEmpty()) {
                                    try {
                                        processMessage(line)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error processing JSON message: ${e.message}")
                                    }
                                }

                                offset = newlineIndex + 1
                            } else {
                                // No newline and no header at current position; attempt to find next header
                                val nextHeader = indexOfHeader(buffer, offset + 1)
                                if (nextHeader >= 0) {
                                    offset = nextHeader
                                    continue
                                } else {
                                    // Need more data
                                    break
                                }
                            }
                        }
                    }

                    // Remove processed bytes from bufferStream
                    if (offset >= buffer.size) {
                        bufferStream.reset()
                    } else if (offset > 0) {
                        val remaining = buffer.copyOfRange(offset, buffer.size)
                        bufferStream.reset()
                        bufferStream.write(remaining)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error: ${e.message}")
            } finally {
                if (isConnected.get()) {
                    disconnectFromServer()
                }
            }
        }
    }

    /**
     * Find little-endian header 0xAABB (bytes 0xBB,0xAA) in buffer starting at fromIndex
     */
    private fun indexOfHeader(buffer: ByteArray, fromIndex: Int): Int {
        var i = fromIndex
        while (i + 1 < buffer.size) {
            if ((buffer[i].toInt() and 0xFF) == 0xBB && (buffer[i + 1].toInt() and 0xFF) == 0xAA) return i
            i++
        }
        return -1
    }

    /**
     * Find a single byte value in the buffer starting at fromIndex
     */
    private fun indexOfByte(buffer: ByteArray, value: Byte, fromIndex: Int): Int {
        var i = fromIndex
        while (i < buffer.size) {
            if (buffer[i] == value) return i
            i++
        }
        return -1
    }

    /**
     * Helper to route parsed packet event data into existing processing path
     */
    private fun processInputEventFromPacket(eventData: Map<String, Any>) {
        try {
            // Convert parsed event map into Broadcast for InputSimulatorService
            val intent = Intent(InputSimulatorService.ACTION_PROCESS_INPUT_EVENT).apply {
                setPackage(packageName)
                putExtra(InputSimulatorService.EXTRA_EVENT_DATA, Bundle().apply {
                    eventData.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            else -> putString(key, value.toString())
                        }
                    }
                })
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching parsed packet: ${e.message}")
        }
    }

    private fun processMessage(message: String) {
        try {
            // Try to parse as JSON first
            val json = JSONObject(message)
            when (json.getString("type")) {
                "heartbeat" -> {
                    // Respond to server heartbeat
                    val response = JSONObject().apply {
                        put("type", "heartbeat_response")
                        put("timestamp", System.currentTimeMillis())
                    }
                    outputWriter?.write("$response\n")
                    outputWriter?.flush()
                }
                "status" -> {
                    Log.d(TAG, "Server status: ${json.getString("message")}")
                }
                else -> {
                    Log.d(TAG, "Unknown message type: ${json.getString("type")}")
                }
            }
        } catch (e: Exception) {
            // Try to parse as binary packet
            try {
                val packet = message.toByteArray()
                val eventData = protocolHandler.parsePacket(packet)
                if (eventData != null) {
                    // Process input event
                    processInputEvent(eventData)
                } else {
                    Log.w(TAG, "Unable to parse message: $message")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error processing message: ${ex.message}")
            }
        }
    }

    private fun processInputEvent(eventData: Map<String, Any>) {
        // Send event to input simulator service
        val intent = Intent(InputSimulatorService.ACTION_PROCESS_INPUT_EVENT).apply {
            setPackage(packageName)
            putExtra(InputSimulatorService.EXTRA_EVENT_DATA, Bundle().apply {
                eventData.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        else -> putString(key, value.toString())
                    }
                }
            })
        }
        sendBroadcast(intent)
    }

    private fun startDiscoveryListener() {
        discoveryJob?.cancel()
        discoveryJob = serviceScope.launch {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT)
                discoverySocket?.soTimeout = 1000

                val buffer = ByteArray(1024)

                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        discoverySocket?.receive(packet)

                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith(DISCOVERY_MESSAGE)) {
                            handleDiscoveryPacket(packet)
                        } else if (message.startsWith(DISCOVERY_INVITE)) {
                            handleInvitationPacket(packet, message)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Continue listening
                    } catch (e: Exception) {
                        Log.e(TAG, "Discovery listener error: ${e.message}")
                        delay(1000) // Brief pause before continuing
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery listener: ${e.message}")
            }
        }
    }

    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        try {
            val response = JSONObject().apply {
                put("type", "discovery_response")
                put("device_name", deviceName)
                put("version", "1.0")
                put("timestamp", System.currentTimeMillis())
            }

            val responseData = (DISCOVERY_RESPONSE + response.toString()).toByteArray()
            val responsePacket = DatagramPacket(
                responseData,
                responseData.size,
                packet.address,
                packet.port
            )

            discoverySocket?.send(responsePacket)
            Log.d(TAG, "Sent discovery response to ${packet.address}")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling discovery packet: ${e.message}")
        }
    }

    private fun handleInvitationPacket(packet: DatagramPacket, message: String) {
        try {
            val rawJson = message.substring(DISCOVERY_INVITE.length)
            val invite = JSONObject(rawJson)
            
            val pcName = invite.getString("pc_name")
            // Use the IP address from the packet header, as it's the one that is actually reachable
            val pcIp = packet.address.hostAddress
            val pcPort = invite.getInt("pc_port")

            Log.d(TAG, "Received invitation from $pcName at $pcIp")

            // Check if trusted
            val prefs = getSharedPreferences("wifikeycontrol_prefs", Context.MODE_PRIVATE)
            val trustedDevices = prefs.getStringSet("trusted_devices", emptySet()) ?: emptySet()
            
            // We use IP as the key for now, but ideally we should use a unique ID if available
            // Or we can trust the Name + IP combination
            val deviceKey = "$pcName@$pcIp"
            
            if (trustedDevices.contains(deviceKey)) {
                Log.d(TAG, "Device is trusted, connecting automatically...")
                connectToServer(pcIp, pcPort)
            } else {
                Log.d(TAG, "Device not trusted, requesting user permission...")
                val intent = Intent(ACTION_INVITATION_RECEIVED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PC_NAME, pcName)
                    putExtra(EXTRA_SERVER_IP, pcIp)
                    putExtra(EXTRA_SERVER_PORT, pcPort)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(intent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling invitation packet: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_description)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(if (connected) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, connected: Boolean) {
        val notification = createNotification(text, connected)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastConnectionStatus(connected: Boolean, errorMessage: String) {
        val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_DEVICE_NAME, if (connected) connectedDeviceName else "")
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        // Persist connection state so UI can read it if it missed the broadcast
        try {
            val prefs = getSharedPreferences("wifikeycontrol_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_connected", connected)
                putString("device_name", if (connected) connectedDeviceName else "")
                apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist connection state: ${e.message}")
        }

        sendBroadcast(intent)
    }
}
