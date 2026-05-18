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
import androidx.core.app.ActivityCompat
import com.TapLinkX3.app.DebugLog
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

    fun start(): Boolean {
        if (isRunning.get()) return true

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true || !hasPermission()) {
            DebugLog.w(TAG, "Bluetooth controller unavailable or missing permission")
            return false
        }

        isRunning.set(true)
        workerThread =
                Thread({ connectLoop() }, "TapLinkControllerBluetooth").apply {
                    isDaemon = true
                    start()
                }
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

    @SuppressLint("MissingPermission")
    private fun connectLoop() {
        while (isRunning.get()) {
            val phone = findPairedPhone()
            if (phone == null) {
                sleepBeforeRetry()
                continue
            }

            try {
                bluetoothAdapter?.cancelDiscovery()
                val btSocket = phone.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = btSocket
                btSocket.connect()
                isConnected.set(true)
                outputStream = btSocket.outputStream
                listener.onControllerConnected(phone.name ?: "TapLink controller", phone.address)
                readLoop(btSocket)
            } catch (e: Exception) {
                DebugLog.w(TAG, "Bluetooth controller connection failed: ${e.message}")
            } finally {
                isConnected.set(false)
                try {
                    socket?.close()
                } catch (_: IOException) {}
                socket = null
                outputStream = null
                listener.onControllerDisconnected()
            }

            sleepBeforeRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findPairedPhone(): BluetoothDevice? {
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: return null
        return pairedDevices.firstOrNull { device ->
            val name = device.name?.lowercase().orEmpty()
            !name.contains("rayneo") &&
                    !name.contains("glasses") &&
                    !name.contains("watch") &&
                    !name.contains("buds") &&
                    !name.contains("headphone")
        } ?: pairedDevices.firstOrNull()
    }

    private fun readLoop(btSocket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(btSocket.inputStream))
        while (isRunning.get() && isConnected.get()) {
            val line = reader.readLine() ?: break
            handlePacket(line)
        }
    }

    private fun handlePacket(payload: String) {
        val json =
                try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    DebugLog.w(TAG, "Ignoring malformed controller packet: ${e.message}")
                    return
                }

        when (json.optString("type")) {
            "hello" -> listener.onControllerConnected(json.optString("name", "TapLink controller"), "bluetooth")
            "mode" -> parseMode(json.optString("mode"))?.let(listener::onControllerModeChanged)
            "key" -> listener.onControllerKey(json.optString("key"))
            "groqApiKey" -> listener.onControllerGroqApiKey(json.optString("key"))
            "airMouse" -> {
                listener.onControllerAirMouseRay(
                        json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                        json.optBoolean("select", false)
                )
            }
            "trackpad" -> {
                listener.onControllerTrackpadDelta(
                        json.optDouble("dx", 0.0).toFloat(),
                        json.optDouble("dy", 0.0).toFloat()
                )
            }
            "tap" -> listener.onControllerTap()
            "touch" -> {
                val action = parseTouchAction(json.optString("action")) ?: return
                listener.onControllerTouch(
                        action,
                        json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                )
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
        } catch (e: IOException) {
            DebugLog.w(TAG, "Failed to send controller message: ${e.message}")
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

    private fun hasPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
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
        val SPP_UUID: UUID = UUID.fromString("a7160f20-63cb-4f61-b6bb-c9e8a35f0f5a")
    }
}
