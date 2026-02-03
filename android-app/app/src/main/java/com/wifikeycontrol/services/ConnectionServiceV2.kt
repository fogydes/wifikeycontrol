package com.wifikeycontrol.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import com.wifikeycontrol.R
import com.wifikeycontrol.protocol.FlatBuffersHandler
import com.wifikeycontrol.protocol.ParsedEvent
import wifikeycontrol.Edge
import wifikeycontrol.MouseButton

/**
 * Refactored ConnectionService using FlatBuffers protocol.
 */
class ConnectionServiceV2 : Service() {

    companion object {
        private const val TAG = "ConnectionServiceV2"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WiFiKeyControlChannel"

        // Network constants
        private const val DEFAULT_SERVER_PORT = 12346
        private const val DISCOVERY_PORT = 12345
        private const val DISCOVERY_MESSAGE = "WIFIKEYCONTROL_DISCOVER"
        
        // Connection settings
        private const val CONNECTION_TIMEOUT = 10000
        private const val HEARTBEAT_INTERVAL = 5000L

        // Intent actions
        const val ACTION_START_SERVICE = "com.wifikeycontrol.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.wifikeycontrol.STOP_SERVICE"
        const val ACTION_CONNECT = "com.wifikeycontrol.CONNECT"
        const val ACTION_DISCONNECT = "com.wifikeycontrol.DISCONNECT"

        // Intent extras
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_SERVER_PORT = "server_port"

        // Broadcast actions
        const val ACTION_CONNECTION_STATUS_CHANGED = "com.wifikeycontrol.CONNECTION_STATUS_CHANGED"
        const val EXTRA_IS_CONNECTED = "is_connected"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    // State
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Network
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var discoverySocket: DatagramSocket? = null

    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var messageListenerJob: Job? = null
    private var discoveryJob: Job? = null

    // Connection state
    private var serverIp: String = ""
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var deviceName: String = ""
    private var connectedDeviceName: String = ""

    // Protocol handler
    private val protocolHandler = FlatBuffersHandler()

    // System services
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConnectionServiceV2 onCreate")
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Ready", false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> startServiceInternal()
            ACTION_STOP_SERVICE -> stopServiceInternal()
            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) ?: return START_STICKY
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, DEFAULT_SERVER_PORT)
                connectToServer(ip, port)
            }
            ACTION_DISCONNECT -> disconnectFromServer()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServiceInternal()
        serviceScope.cancel()
    }

    private fun startServiceInternal() {
        if (isRunning.getAndSet(true)) return
        
        Log.d(TAG, "Starting service")
        updateNotification("Waiting for connection...", false)
        startDiscoveryListener()
    }

    private fun stopServiceInternal() {
        isRunning.set(false)
        disconnectFromServer()
        discoveryJob?.cancel()
        discoverySocket?.close()
    }

    private fun connectToServer(ip: String, port: Int) {
        if (isConnected.get()) {
            Log.d(TAG, "Already connected")
            return
        }

        serverIp = ip
        serverPort = port

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
                Log.d(TAG, "Connecting to $ip:$port")

                socket = Socket().apply {
                    soTimeout = CONNECTION_TIMEOUT
                    connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                }

                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()

                if (!performHandshake()) {
                    throw Exception("Handshake failed")
                }

                isConnected.set(true)
                updateNotification("Connected to $connectedDeviceName", true)
                broadcastConnectionStatus(true, "")

                // Start message listener
                startMessageListener()

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                broadcastConnectionStatus(false, e.message ?: "Connection failed")
                disconnectFromServer()
            }
        }
    }

    private fun disconnectFromServer() {
        messageListenerJob?.cancel()
        connectionJob?.cancel()
        
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        
        socket = null
        inputStream = null
        outputStream = null
        
        if (isConnected.getAndSet(false)) {
            updateNotification("Disconnected", false)
            broadcastConnectionStatus(false, "Disconnected")
        }
    }

    /**
     * Perform JSON handshake with PC server.
     */
    private suspend fun performHandshake(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Read hello from server
                val reader = BufferedReader(InputStreamReader(inputStream!!))
                val helloLine = reader.readLine() ?: return@withContext false
                
                val hello = JSONObject(helloLine)
                if (hello.getString("type") != "hello") {
                    Log.e(TAG, "Expected hello, got: ${hello.getString("type")}")
                    return@withContext false
                }
                
                connectedDeviceName = hello.optString("name", "PC")
                Log.d(TAG, "Server: $connectedDeviceName")

                // Send hello_ack
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                
                val response = JSONObject().apply {
                    put("type", "hello_ack")
                    put("version", 1)
                    put("name", deviceName)
                    put("screen", listOf(screenWidth, screenHeight))
                }

                outputStream?.write("$response\n".toByteArray())
                outputStream?.flush()

                Log.d(TAG, "Handshake complete")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Handshake error: ${e.message}")
                false
            }
        }
    }

    /**
     * Start listening for FlatBuffer messages.
     */
    private fun startMessageListener() {
        messageListenerJob = serviceScope.launch {
            try {
                val buffer = ByteArrayOutputStream()
                val readBuffer = ByteArray(4096)

                while (isConnected.get() && isRunning.get()) {
                    val bytesRead = try {
                        inputStream?.read(readBuffer) ?: -1
                    } catch (e: Exception) {
                        Log.e(TAG, "Read error: ${e.message}")
                        -1
                    }

                    if (bytesRead <= 0) {
                        Log.d(TAG, "Connection closed by server")
                        break
                    }

                    buffer.write(readBuffer, 0, bytesRead)
                    
                    // Process complete messages
                    var data = buffer.toByteArray()
                    var offset = 0
                    
                    while (true) {
                        val result = protocolHandler.tryReadMessage(data, offset)
                        if (result == null) break
                        
                        val (event, consumed) = result
                        offset += consumed
                        
                        if (event != null) {
                            processEvent(event)
                        }
                    }
                    
                    // Keep remaining bytes in buffer
                    buffer.reset()
                    if (offset < data.size) {
                        buffer.write(data, offset, data.size - offset)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error: ${e.message}")
            } finally {
                disconnectFromServer()
            }
        }
    }

    /**
     * Process a parsed FlatBuffer event.
     */
    private fun processEvent(event: ParsedEvent) {
        val bundle = Bundle()
        
        when (event) {
            is ParsedEvent.MouseMove -> {
                bundle.putString("type", "mouse_move")
                bundle.putInt("dx", event.dx.toInt())
                bundle.putInt("dy", event.dy.toInt())
            }
            is ParsedEvent.MouseClick -> {
                bundle.putString("type", "mouse_click")
                bundle.putString("button", buttonToString(event.button))
                bundle.putBoolean("pressed", event.pressed)
            }
            is ParsedEvent.MouseScroll -> {
                bundle.putString("type", "scroll")
                bundle.putInt("dx", event.dx.toInt())
                bundle.putInt("dy", event.dy.toInt())
            }
            is ParsedEvent.Key -> {
                bundle.putString("type", if (event.pressed) "key_press" else "key_release")
                bundle.putInt("keycode", event.keycode)
                bundle.putString("key", event.keyName)
                bundle.putInt("modifiers", event.modifiers)
            }
            is ParsedEvent.ControlSwitch -> {
                bundle.putString("type", "control_switch")
                bundle.putString("edge", edgeToString(event.edge))
            }
            is ParsedEvent.Heartbeat -> {
                // Send heartbeat ack back
                sendHeartbeatAck()
                return
            }
            is ParsedEvent.HeartbeatAck -> {
                // No action needed
                return
            }
        }

        // Send to InputSimulatorService
        val intent = Intent(InputSimulatorService.ACTION_PROCESS_INPUT_EVENT).apply {
            setPackage(packageName)
            putExtra(InputSimulatorService.EXTRA_EVENT_DATA, bundle)
        }
        sendBroadcast(intent)
    }

    private fun sendHeartbeatAck() {
        // For now, use JSON heartbeat ack for simplicity
        serviceScope.launch {
            try {
                val response = JSONObject().apply {
                    put("type", "heartbeat_ack")
                    put("timestamp", System.currentTimeMillis())
                }
                outputStream?.write("$response\n".toByteArray())
                outputStream?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send heartbeat ack: ${e.message}")
            }
        }
    }

    private fun buttonToString(button: Byte): String {
        return when (button) {
            MouseButton.Left -> "left"
            MouseButton.Right -> "right"
            MouseButton.Middle -> "middle"
            else -> "unknown"
        }
    }

    private fun edgeToString(edge: Byte): String {
        return when (edge) {
            Edge.Left -> "left"
            Edge.Right -> "right"
            Edge.Top -> "top"
            Edge.Bottom -> "bottom"
            Edge.ReturnToPC -> "return_to_pc"
            else -> "unknown"
        }
    }

    private fun startDiscoveryListener() {
        discoveryJob = serviceScope.launch {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT)
                discoverySocket?.broadcast = true
                
                val buffer = ByteArray(1024)
                
                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        discoverySocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        
                        if (message == DISCOVERY_MESSAGE) {
                            // Respond to discovery
                            val response = "WIFIKEYCONTROL_SERVER:{\"name\":\"$deviceName\"}".toByteArray()
                            val responsePacket = DatagramPacket(
                                response, response.size,
                                packet.address, packet.port
                            )
                            discoverySocket?.send(responsePacket)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Ignore timeouts
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Key Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Connection status notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val icon = if (connected) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Key Control")
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, connected: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, connected))
    }

    private fun broadcastConnectionStatus(connected: Boolean, errorMessage: String) {
        val intent = Intent(ACTION_CONNECTION_STATUS_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_CONNECTED, connected)
            putExtra(EXTRA_DEVICE_NAME, connectedDeviceName)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        sendBroadcast(intent)
    }
}
