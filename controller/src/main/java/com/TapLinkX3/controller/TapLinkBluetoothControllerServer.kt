package com.TapLinkX3.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class TapLinkBluetoothControllerServer(private val context: Context) {
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onKeyboardVisibilityRequested: ((Boolean) -> Unit)? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var acceptThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            onStatusChanged?.invoke("Bluetooth is not available on this device.")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true) {
            onStatusChanged?.invoke("Turn on Bluetooth, then start the controller.")
            return false
        }
        if (!hasPermission()) {
            onStatusChanged?.invoke("Bluetooth permission is required.")
            return false
        }

        return startServer()
    }

    fun stop() {
        isRunning.set(false)
        isConnected.set(false)
        try {
            clientSocket?.close()
        } catch (_: IOException) {}
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        clientSocket = null
        outputStream = null
        acceptThread?.interrupt()
        acceptThread = null
        onConnectionChanged?.invoke(false)
    }

    fun isConnected(): Boolean = isConnected.get()

    fun setMode(mode: ControllerMode) {
        send(JSONObject().put("type", "mode").put("mode", mode.wireName))
    }

    fun sendAirMouseRay(x: Float, y: Float, select: Boolean) {
        send(
                JSONObject()
                        .put("type", "airMouse")
                        .put("x", x.coerceIn(0f, 1f).toDouble())
                        .put("y", y.coerceIn(0f, 1f).toDouble())
                        .put("select", select)
        )
    }

    fun sendTrackpadDelta(dx: Float, dy: Float) {
        send(JSONObject().put("type", "trackpad").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
    }

    fun sendTap() {
        send(JSONObject().put("type", "tap"))
    }

    fun sendKey(key: String) {
        send(JSONObject().put("type", "key").put("key", key))
    }

    fun sendGroqApiKey(key: String) {
        send(JSONObject().put("type", "groqApiKey").put("key", key))
    }

    fun sendTouch(action: TouchAction, x: Float, y: Float) {
        send(
                JSONObject()
                        .put("type", "touch")
                        .put("action", action.wireName)
                        .put("x", x.coerceIn(0f, 1f).toDouble())
                        .put("y", y.coerceIn(0f, 1f).toDouble())
        )
    }

    @SuppressLint("MissingPermission")
    private fun startServer(): Boolean {
        return try {
            serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            isRunning.set(true)
            acceptThread =
                    Thread({ acceptConnections() }, "TapLinkControllerAccept").apply {
                        isDaemon = true
                        start()
                    }
            onStatusChanged?.invoke("Waiting for TapLink glasses...")
            true
        } catch (e: Exception) {
            onStatusChanged?.invoke("Failed to start Bluetooth server: ${e.message}")
            false
        }
    }

    private fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val socket = serverSocket?.accept() ?: continue
                clientSocket?.close()
                clientSocket = socket
                outputStream = socket.outputStream
                isConnected.set(true)
                onConnectionChanged?.invoke(true)
                onStatusChanged?.invoke("Connected to TapLink glasses")
                send(JSONObject().put("type", "hello").put("name", "TapLink Controller"))
                readMessages(socket)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    onStatusChanged?.invoke("Bluetooth connection closed.")
                }
            } finally {
                isConnected.set(false)
                outputStream = null
                try {
                    clientSocket?.close()
                } catch (_: IOException) {}
                clientSocket = null
                onConnectionChanged?.invoke(false)
            }
        }
    }

    private fun readMessages(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        while (isRunning.get() && socket.isConnected) {
            val line = reader.readLine() ?: break
            val json =
                    try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
            if (json.optString("type") == "keyboard") {
                onKeyboardVisibilityRequested?.invoke(json.optBoolean("visible", false))
            }
        }
    }

    private fun send(json: JSONObject) {
        val output = outputStream ?: return
        if (!isConnected.get()) return
        try {
            synchronized(output) {
                output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: IOException) {
            isConnected.set(false)
        }
    }

    private fun hasPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                        PackageManager.PERMISSION_GRANTED
            }

    enum class ControllerMode(val wireName: String) {
        AIR_MOUSE("airMouse"),
        TRACKPAD("trackpad")
    }

    enum class TouchAction(val wireName: String) {
        DOWN("down"),
        MOVE("move"),
        UP("up"),
        CANCEL("cancel")
    }

    companion object {
        private const val SERVICE_NAME = "TapLinkController"
        val SPP_UUID: UUID = UUID.fromString("a7160f20-63cb-4f61-b6bb-c9e8a35f0f5a")
    }
}
