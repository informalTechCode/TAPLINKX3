package com.TapLinkX3.app.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class ControllerBluetoothClient(
        private val context: Context,
        private val listener: ControllerInputListener
) {
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var workerThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter is null — not available on this device")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is disabled")
            return false
        }
        if (!hasPermission()) {
            Log.w(TAG, "Missing Bluetooth permission (BLUETOOTH_CONNECT)")
            return false
        }

        isRunning.set(true)
        Log.d(TAG, "Starting controller client connection thread")
        connectToPhone()
        return true
    }

    fun stop() {
        isRunning.set(false)
        isConnected.set(false)
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
        outputStream = null
        workerThread?.interrupt()
        workerThread = null
        listener.onControllerDisconnected()
    }

    fun sendKeyboardVisibility(visible: Boolean) {
        send(JSONObject().put("type", "keyboard").put("visible", visible))
    }

    fun sendGroqApiKey(key: String) {
        send(JSONObject().put("type", "groqApiKey").put("key", key))
    }

    fun sendNetworkEndpoint(port: Int, addresses: List<String>) {
        send(
                JSONObject()
                        .put("type", ControllerNetworkInputServer.TYPE_ENDPOINT)
                        .put("port", port)
                        .put("addresses", org.json.JSONArray(addresses))
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectToPhone() {
        workerThread =
                Thread(
                                {
                                    while (isRunning.get() && !isConnected.get()) {
                                        val phone = findPairedPhone()

                                        if (phone == null) {
                                            Log.d(
                                                    TAG,
                                                    "No paired phone found — retrying in ${RETRY_DELAY_MS}ms"
                                            )
                                            sleepBeforeRetry()
                                            continue
                                        }

                                        val deviceName = phone.name ?: phone.address
                                        Log.d(
                                                TAG,
                                                "Attempting connection to $deviceName [${phone.address}]"
                                        )

                                        try {
                                            Log.d(TAG, "Creating RFCOMM socket...")
                                            val btSocket =
                                                    phone.createRfcommSocketToServiceRecord(
                                                            SPP_UUID
                                                    )
                                            socket = btSocket
                                            try {
                                                bluetoothAdapter?.cancelDiscovery()
                                            } catch (_: SecurityException) {}

                                            Log.d(TAG, "Calling connect()...")
                                            btSocket.connect()
                                            Log.d(
                                                    TAG,
                                                    "connect() returned, isConnected=${btSocket.isConnected}"
                                            )

                                            if (btSocket.isConnected) {
                                                isConnected.set(true)
                                                outputStream = btSocket.outputStream
                                                Log.d(
                                                        TAG,
                                                        "Successfully connected to controller phone!"
                                                )

                                                listener.onControllerConnected(
                                                        deviceName,
                                                        phone.address
                                                )

                                                // Read messages until disconnected
                                                readLoop(btSocket)
                                            } else {
                                                Log.w(
                                                        TAG,
                                                        "connect() returned but socket.isConnected is false"
                                                )
                                            }
                                        } catch (e: IOException) {
                                            Log.e(TAG, "Connection failed: ${e.message}")
                                            try {
                                                socket?.close()
                                            } catch (_: IOException) {}
                                            socket = null
                                            outputStream = null
                                            sleepBeforeRetry()
                                        } catch (e: SecurityException) {
                                            Log.e(
                                                    TAG,
                                                    "Permission error during connection: ${e.message}"
                                            )
                                            break
                                        }
                                    }

                                    isConnected.set(false)
                                    outputStream = null
                                    try {
                                        socket?.close()
                                    } catch (_: IOException) {}
                                    socket = null

                                    listener.onControllerDisconnected()
                                    Log.d(TAG, "Disconnected from controller")

                                    if (isRunning.get()) {
                                        handler.postDelayed(
                                                {
                                                    if (isRunning.get() && !isConnected.get()) {
                                                        Log.d(TAG, "Auto-reconnecting...")
                                                        connectToPhone()
                                                    }
                                                },
                                                RECONNECT_DELAY_MS
                                        )
                                    }
                                },
                                "TapLinkControllerBluetooth"
                        )
                        .apply {
                            isDaemon = true
                            start()
                        }
    }

    @SuppressLint("MissingPermission")
    private fun findPairedPhone(): BluetoothDevice? {
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: return null

        for (device in pairedDevices) {
            Log.d(TAG, "Found paired device: ${device.name} [${device.address}]")
        }

        val likelyPhone =
                pairedDevices.firstOrNull { device ->
                    val name = device.name?.lowercase().orEmpty()
                    !name.contains("rayneo") &&
                            !name.contains("glasses") &&
                            !name.contains("watch") &&
                            !name.contains("buds") &&
                            !name.contains("headphone")
                }

        Log.d(TAG, "Selected device: ${likelyPhone?.name ?: "none found (will use first paired)"}")
        return likelyPhone ?: pairedDevices.firstOrNull()
    }

    private fun readLoop(btSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(btSocket.inputStream), 8192)

            while (isRunning.get() && isConnected.get()) {
                val line = reader.readLine() ?: break

                val typeStart = line.indexOf("\"type\":\"")
                if (typeStart < 0) continue

                val typeValueStart = typeStart + 8
                val typeEnd = line.indexOf('"', typeValueStart)
                if (typeEnd < 0) continue

                val type = line.substring(typeValueStart, typeEnd)

                when (type) {
                    "trackpad" -> {
                        val dx = extractFloat(line, PREFIX_DX) ?: 0f
                        val dy = extractFloat(line, PREFIX_DY) ?: 0f
                        val action =
                                parseTrackpadAction(extractString(line, PREFIX_ACTION) ?: "move")
                                        ?: ControllerTrackpadAction.MOVE
                        val pointerCount =
                                (extractInt(line, PREFIX_POINTER_COUNT) ?: 1).coerceAtLeast(1)

                        handler.post {
                            listener.onControllerTrackpadGesture(action, dx, dy, pointerCount)
                        }
                    }
                    "airMouse" -> {
                        val x = extractFloat(line, PREFIX_X) ?: continue
                        val y = extractFloat(line, PREFIX_Y) ?: continue
                        val select = line.contains("\"select\":true")

                        handler.post {
                            listener.onControllerAirMouseRay(
                                    x.coerceIn(0f, 1f),
                                    y.coerceIn(0f, 1f),
                                    select
                            )
                        }
                    }
                    "scroll" -> {
                        val dy = extractFloat(line, PREFIX_DY) ?: continue

                        handler.post { listener.onControllerScroll(dy) }
                    }
                    "tap" -> {
                        handler.post { listener.onControllerTap() }
                    }
                    else -> {
                        val json =
                                try {
                                    JSONObject(line)
                                } catch (_: Exception) {
                                    continue
                                }

                        when (type) {
                            "hello" -> {
                                handler.post {
                                    listener.onControllerConnected(
                                            json.optString("name", "TapLink controller"),
                                            btSocket.remoteDevice?.address ?: "bluetooth"
                                    )
                                }
                            }
                            "mode" -> {
                                val parsedMode = parseMode(json.optString("mode")) ?: continue

                                handler.post { listener.onControllerModeChanged(parsedMode) }
                            }
                            "key" -> {
                                val key = json.optString("key")

                                handler.post { listener.onControllerKey(key) }
                            }
                            "groqApiKey" -> {
                                val key = json.optString("key")

                                handler.post { listener.onControllerGroqApiKey(key) }
                            }
                            "aiPrompt" -> {
                                val prompt = json.optString("prompt")

                                handler.post { listener.onControllerAiPrompt(prompt) }
                            }
                            "touch" -> {
                                val action = parseTouchAction(json.optString("action")) ?: continue
                                val x = json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f)
                                val y = json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)

                                handler.post { listener.onControllerTouch(action, x, y) }
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Controller read loop ended: ${e.message}")
        }
    }

    /** Fast float extraction using pre-allocated prefixes to avoid GC churn. */
    private fun extractFloat(json: String, prefix: String): Float? {
        val idx = json.indexOf(prefix)
        if (idx < 0) return null
        val start = idx + prefix.length
        var end = start
        while (end < json.length &&
                (json[end].isDigit() ||
                        json[end] == '.' ||
                        json[end] == '-' ||
                        json[end] == 'E' ||
                        json[end] == 'e')) {
            end++
        }
        return if (end > start) {
            try {
                json.substring(start, end).toFloat()
            } catch (e: NumberFormatException) {
                null
            }
        } else null
    }

    private fun extractInt(json: String, prefix: String): Int? {
        val idx = json.indexOf(prefix)
        if (idx < 0) return null
        val start = idx + prefix.length
        var end = start
        while (end < json.length && json[end].isDigit()) {
            end++
        }
        return if (end > start) {
            try {
                json.substring(start, end).toInt()
            } catch (e: NumberFormatException) {
                null
            }
        } else null
    }

    private fun extractString(json: String, prefix: String): String? {
        val idx = json.indexOf(prefix)
        if (idx < 0) return null
        val start = idx + prefix.length
        val end = json.indexOf('"', start)
        return if (end > start) json.substring(start, end) else null
    }

    private fun send(json: JSONObject) {
        val output = outputStream ?: return
        if (!isConnected.get()) return
        try {
            synchronized(output) {
                output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send controller message: ${e.message}")
            isConnected.set(false)
        }
    }

    private fun parseMode(value: String): ControllerMode? =
            when (value) {
                "airMouse" -> ControllerMode.AIR_MOUSE
                "trackpad" -> ControllerMode.TRACKPAD
                else -> null
            }

    private fun parseTouchAction(value: String): ControllerTouchAction? =
            when (value) {
                "down" -> ControllerTouchAction.DOWN
                "move" -> ControllerTouchAction.MOVE
                "up" -> ControllerTouchAction.UP
                "cancel" -> ControllerTouchAction.CANCEL
                else -> null
            }

    private fun parseTrackpadAction(value: String): ControllerTrackpadAction? =
            when (value) {
                "down" -> ControllerTrackpadAction.DOWN
                "move" -> ControllerTrackpadAction.MOVE
                "pointer" -> ControllerTrackpadAction.POINTER
                "up" -> ControllerTrackpadAction.UP
                "cancel" -> ControllerTrackpadAction.CANCEL
                else -> null
            }

    private fun hasPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                        PackageManager.PERMISSION_GRANTED
            }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (_: InterruptedException) {}
    }

    companion object {
        private const val TAG = "ControllerBluetooth"
        private const val RETRY_DELAY_MS = 2500L
        private const val RECONNECT_DELAY_MS = 2000L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Pre-allocate strings for the high-speed parser to avoid Garbage Collection frame drops
        private const val PREFIX_ACTION = "\"action\":\""
        private const val PREFIX_DX = "\"dx\":"
        private const val PREFIX_DY = "\"dy\":"
        private const val PREFIX_POINTER_COUNT = "\"pointerCount\":"
        private const val PREFIX_X = "\"x\":"
        private const val PREFIX_Y = "\"y\":"
    }
}
