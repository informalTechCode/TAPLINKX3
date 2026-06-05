package com.TapLinkX3.app.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class ControllerNetworkInputServer(
        @Suppress("unused") private val context: Context,
        private val listener: ControllerInputListener
) {
    private val isRunning = AtomicBoolean(false)
    private val loggedActiveReceive = AtomicBoolean(false)
    private val lastReceiveAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var discoveryThread: Thread? = null

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        try {
            val udpSocket =
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(GLASSES_INPUT_PORT))
                    }
            socket = udpSocket
            receiveThread =
                    Thread({ receiveLoop(udpSocket) }, "TapLinkControllerUdpRx").apply {
                        isDaemon = true
                        start()
                    }
            discoveryThread =
                    Thread({ discoveryLoop(udpSocket) }, "TapLinkControllerUdpDiscovery").apply {
                        isDaemon = true
                        start()
                    }
        } catch (e: SocketException) {
            isRunning.set(false)
            Log.w(TAG, "Failed to start network input server: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
        discoveryThread?.interrupt()
        discoveryThread = null
    }

    fun localIpv4Addresses(): List<String> =
            NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .asSequence()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .distinct()
                    .toList()

    private fun receiveLoop(udpSocket: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (isRunning.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(packet)
                lastReceiveAtMs.set(System.currentTimeMillis())
                if (loggedActiveReceive.compareAndSet(false, true)) {
                    Log.d(TAG, "Network controller UDP receive active from ${packet.address.hostAddress}")
                }
                val line = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                sendAck(udpSocket, packet, line)
                if (isDuplicateReliableMessage(line)) continue
                handleMessage(line)
            } catch (_: SocketException) {
                if (isRunning.get()) Log.d(TAG, "Network input socket closed")
                break
            } catch (e: Exception) {
                Log.d(TAG, "Network input receive failed: ${e.message}")
            }
        }
    }

    private fun discoveryLoop(udpSocket: DatagramSocket) {
        while (isRunning.get()) {
            sendDiscovery(udpSocket)
            // Broadcast more aggressively when not yet receiving data, slower once active
            val interval = if (System.currentTimeMillis() - lastReceiveAtMs.get() < ACTIVE_THRESHOLD_MS)
                    DISCOVERY_IDLE_INTERVAL_MS else DISCOVERY_INTERVAL_MS
            try {
                Thread.sleep(interval)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sendDiscovery(udpSocket: DatagramSocket) {
        val data =
                JSONObject()
                        .put("type", TYPE_ENDPOINT)
                        .put("port", GLASSES_INPUT_PORT)
                        .toString()
                        .toByteArray(Charsets.UTF_8)

        val broadcasts =
                sequenceOf(java.net.InetAddress.getByName("255.255.255.255")) +
                        NetworkInterface.getNetworkInterfaces()
                                ?.toList()
                                .orEmpty()
                                .asSequence()
                                .filter {
                                    runCatching { it.isUp && !it.isLoopback }.getOrDefault(false)
                                }
                                .flatMap { it.interfaceAddresses.toList().asSequence() }
                                .mapNotNull { it.broadcast }

        broadcasts.distinctBy { it.hostAddress }.forEach { address ->
            try {
                udpSocket.send(DatagramPacket(data, data.size, address, PHONE_DISCOVERY_PORT))
            } catch (e: Exception) {
                Log.d(TAG, "Network discovery send failed: ${e.message}")
            }
        }
    }

    private fun handleMessage(line: String) {
        val typeStart = line.indexOf("\"type\":\"")
        if (typeStart < 0) return

        val typeValueStart = typeStart + 8
        val typeEnd = line.indexOf('"', typeValueStart)
        if (typeEnd < 0) return

        when (line.substring(typeValueStart, typeEnd)) {
            TYPE_PING -> Unit
            "trackpad" -> {
                val dx = extractFloat(line, PREFIX_DX) ?: 0f
                val dy = extractFloat(line, PREFIX_DY) ?: 0f
                val action =
                        parseTrackpadAction(extractString(line, PREFIX_ACTION) ?: "move")
                                ?: ControllerTrackpadAction.MOVE
                val pointerCount = (extractInt(line, PREFIX_POINTER_COUNT) ?: 1).coerceAtLeast(1)
                handler.post {
                    listener.onControllerTrackpadGesture(action, dx, dy, pointerCount)
                }
            }
            "airMouse" -> {
                val x = extractFloat(line, PREFIX_X) ?: return
                val y = extractFloat(line, PREFIX_Y) ?: return
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
                val dy = extractFloat(line, PREFIX_DY) ?: return
                handler.post { listener.onControllerScroll(dy) }
            }
            "key" -> {
                val key =
                        try {
                            JSONObject(line).optString("key")
                        } catch (_: Exception) {
                            return
                        }
                handler.post { listener.onControllerKey(key) }
            }
        }
    }

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
            } catch (_: NumberFormatException) {
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
            } catch (_: NumberFormatException) {
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

    private fun isDuplicateReliableMessage(line: String): Boolean =
            !ControllerReliableMessageDeduper.shouldDispatch(extractString(line, PREFIX_MESSAGE_ID))

    private fun parseTrackpadAction(value: String): ControllerTrackpadAction? =
            when (value) {
                "down" -> ControllerTrackpadAction.DOWN
                "move" -> ControllerTrackpadAction.MOVE
                "pointer" -> ControllerTrackpadAction.POINTER
                "up" -> ControllerTrackpadAction.UP
                "cancel" -> ControllerTrackpadAction.CANCEL
                else -> null
            }

    private fun sendAck(udpSocket: DatagramSocket, packet: DatagramPacket, line: String) {
        val ack = JSONObject().put("type", TYPE_ACK)
        extractString(line, PREFIX_MESSAGE_ID)?.let { ack.put(FIELD_MESSAGE_ID, it) }
        val data = ack.toString().toByteArray(Charsets.UTF_8)
        try {
            udpSocket.send(DatagramPacket(data, data.size, packet.address, PHONE_DISCOVERY_PORT))
        } catch (e: Exception) {
            Log.d(TAG, "Network ack send failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ControllerNetworkInput"
        private const val PHONE_DISCOVERY_PORT = 37692
        const val GLASSES_INPUT_PORT = 37693
        const val TYPE_ENDPOINT = "controllerNetworkEndpoint"
        private const val TYPE_ACK = "controllerNetworkAck"
        private const val TYPE_PING = "controllerNetworkPing"
        private const val FIELD_MESSAGE_ID = "messageId"
        private const val DISCOVERY_INTERVAL_MS = 1000L
        private const val DISCOVERY_IDLE_INTERVAL_MS = 5000L
        private const val ACTIVE_THRESHOLD_MS = 10000L

        private const val PREFIX_ACTION = "\"action\":\""
        private const val PREFIX_DX = "\"dx\":"
        private const val PREFIX_DY = "\"dy\":"
        private const val PREFIX_POINTER_COUNT = "\"pointerCount\":"
        private const val PREFIX_X = "\"x\":"
        private const val PREFIX_Y = "\"y\":"
        private const val PREFIX_MESSAGE_ID = "\"messageId\":\""
    }
}
