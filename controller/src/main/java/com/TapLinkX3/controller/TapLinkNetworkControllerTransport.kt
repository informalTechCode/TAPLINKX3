package com.TapLinkX3.controller

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class TapLinkNetworkControllerTransport {
    private val isRunning = AtomicBoolean(false)
    private val loggedQueuedSend = AtomicBoolean(false)
    private val loggedActiveSend = AtomicBoolean(false)
    private val loggedLatestQueuedSend = AtomicBoolean(false)
    private val activeEndpoint = AtomicReference<InetSocketAddress?>(null)
    private val lastAckAtMs = AtomicLong(0L)
    private val lastProbeAtMs = AtomicLong(0L)
    private val latestDatagram = AtomicReference<PendingDatagram?>(null)
    private val latestSendScheduled = AtomicBoolean(false)
    private val pendingReliableAcks = ConcurrentHashMap<String, CountDownLatch>()
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private val sendExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "TapLinkNetworkControllerSender").apply { isDaemon = true }
            }
    private val reliableSendExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "TapLinkNetworkControllerReliableSender").apply { isDaemon = true }
            }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        try {
            val udpSocket =
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(PHONE_DISCOVERY_PORT))
                    }
            socket = udpSocket
            Log.d(TAG, "Network controller listening for UDP discovery on port $PHONE_DISCOVERY_PORT")
            receiveThread =
                    Thread({ receiveLoop(udpSocket) }, "TapLinkNetworkController").apply {
                        isDaemon = true
                        start()
                    }
        } catch (e: SocketException) {
            isRunning.set(false)
            Log.w(TAG, "Failed to start network controller transport: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        activeEndpoint.set(null)
        pendingReliableAcks.clear()
        socket?.close()
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
    }

    fun isReachable(): Boolean = activeEndpoint.get() != null && socket != null && isUdpReachable()

    fun updateEndpoint(addresses: List<String>, port: Int) {
        val address =
                addresses.asSequence()
                        .mapNotNull { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                        ?: return
        updateEndpoint(InetSocketAddress(address, port))
    }

    fun sendReliable(data: String, fallback: (String) -> Unit): Boolean {
        val endpoint = activeEndpoint.get() ?: return false
        val udpSocket = socket ?: return false
        if (!isUdpReachable()) {
            sendProbe(endpoint)
            return false
        }

        val messageId = UUID.randomUUID().toString()
        val reliableData =
                try {
                    JSONObject(data).put(FIELD_MESSAGE_ID, messageId).toString()
                } catch (e: Exception) {
                    Log.d(TAG, "Reliable UDP payload was not JSON: ${e.message}")
                    return false
                }
        val bytes = reliableData.toByteArray(Charsets.UTF_8)
        reliableSendExecutor.execute {
            sendReliableDatagram(udpSocket, endpoint, messageId, reliableData, bytes, fallback)
        }
        return true
    }

    fun send(data: String): Boolean {
        val endpoint = activeEndpoint.get() ?: return false
        val udpSocket = socket ?: return false
        if (!isUdpReachable()) {
            sendProbe(endpoint)
            return false
        }
        val bytes = data.toByteArray(Charsets.UTF_8)
        if (loggedQueuedSend.compareAndSet(false, true)) {
            Log.d(TAG, "Network controller UDP send queued to ${endpoint.address.hostAddress}:${endpoint.port}")
        }
        sendExecutor.execute {
            try {
                udpSocket.send(DatagramPacket(bytes, bytes.size, endpoint.address, endpoint.port))
                if (loggedActiveSend.compareAndSet(false, true)) {
                    Log.d(TAG, "Network controller UDP send active")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Network controller send failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return true
    }

    fun sendLatest(data: String): Boolean {
        val endpoint = activeEndpoint.get() ?: return false
        if (socket == null) return false
        if (!isUdpReachable()) {
            sendProbe(endpoint)
            return false
        }

        val bytes = data.toByteArray(Charsets.UTF_8)
        latestDatagram.set(PendingDatagram(bytes, endpoint))
        if (loggedLatestQueuedSend.compareAndSet(false, true)) {
            Log.d(
                    TAG,
                    "Network controller UDP latest-send queued to ${endpoint.address.hostAddress}:${endpoint.port}"
            )
        }

        if (latestSendScheduled.compareAndSet(false, true)) {
            sendExecutor.execute { drainLatestDatagrams() }
        }
        return true
    }

    private fun drainLatestDatagrams() {
        while (true) {
            val pending = latestDatagram.getAndSet(null) ?: break
            val udpSocket = socket ?: break
            try {
                udpSocket.send(
                        DatagramPacket(
                                pending.bytes,
                                pending.bytes.size,
                                pending.endpoint.address,
                                pending.endpoint.port
                        )
                )
                if (loggedActiveSend.compareAndSet(false, true)) {
                    Log.d(TAG, "Network controller UDP send active")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Network controller send failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        latestSendScheduled.set(false)
        if (latestDatagram.get() != null && latestSendScheduled.compareAndSet(false, true)) {
            sendExecutor.execute { drainLatestDatagrams() }
        }
    }

    private fun sendReliableDatagram(
            udpSocket: DatagramSocket,
            endpoint: InetSocketAddress,
            messageId: String,
            reliableData: String,
            bytes: ByteArray,
            fallback: (String) -> Unit
    ) {
        val latch = CountDownLatch(1)
        pendingReliableAcks[messageId] = latch
        try {
            repeat(RELIABLE_SEND_ATTEMPTS) { attempt ->
                try {
                    udpSocket.send(
                            DatagramPacket(bytes, bytes.size, endpoint.address, endpoint.port)
                    )
                    if (loggedActiveSend.compareAndSet(false, true)) {
                        Log.d(TAG, "Network controller UDP send active")
                    }
                } catch (e: Exception) {
                    Log.d(
                            TAG,
                            "Reliable network controller send failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }

                if (latch.await(RELIABLE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return
                }

                if (attempt == 0) {
                    Log.d(TAG, "Reliable UDP keyboard message retrying after missing ack")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pendingReliableAcks.remove(messageId)
        }

        Log.d(TAG, "Reliable UDP keyboard message failed; falling back to Bluetooth")
        fallback(reliableData)
    }

    private fun receiveLoop(udpSocket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (isRunning.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket.receive(packet)
                val line = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val json =
                        try {
                            JSONObject(line)
                        } catch (_: Exception) {
                            continue
                        }
                when (json.optString("type")) {
                    TYPE_ENDPOINT -> {
                        val port = json.optInt("port", GLASSES_INPUT_PORT)
                        updateEndpoint(InetSocketAddress(packet.address, port))
                    }
                    TYPE_ACK -> {
                        lastAckAtMs.set(System.currentTimeMillis())
                        val messageId = json.optString(FIELD_MESSAGE_ID)
                        if (messageId.isNotEmpty()) {
                            pendingReliableAcks[messageId]?.countDown()
                        }
                        Log.d(TAG, "Network controller UDP ack from ${packet.address.hostAddress}")
                    }
                }
            } catch (_: SocketException) {
                if (isRunning.get()) Log.d(TAG, "Network controller socket closed")
                break
            } catch (e: Exception) {
                Log.d(TAG, "Network controller receive failed: ${e.message}")
            }
        }
    }

    private fun updateEndpoint(endpoint: InetSocketAddress) {
        val previous = activeEndpoint.getAndSet(endpoint)
        if (previous != endpoint) {
            Log.d(TAG, "Network controller endpoint: ${endpoint.address.hostAddress}:${endpoint.port}")
        }
        sendProbe(endpoint, force = true)
    }

    private fun isUdpReachable(): Boolean =
            System.currentTimeMillis() - lastAckAtMs.get() <= UDP_ACK_STALE_MS

    private fun sendProbe(endpoint: InetSocketAddress, force: Boolean = false) {
        val udpSocket = socket ?: return
        val now = System.currentTimeMillis()
        if (!force) {
            val previousProbeAt = lastProbeAtMs.get()
            if (now - previousProbeAt < UDP_PROBE_INTERVAL_MS) return
            if (!lastProbeAtMs.compareAndSet(previousProbeAt, now)) return
        } else {
            lastProbeAtMs.set(now)
        }
        val data = """{"type":"$TYPE_PING"}"""
        val bytes = data.toByteArray(Charsets.UTF_8)
        sendExecutor.execute {
            try {
                udpSocket.send(DatagramPacket(bytes, bytes.size, endpoint.address, endpoint.port))
            } catch (e: Exception) {
                Log.d(TAG, "Network controller probe failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TapLinkNetworkCtrl"
        const val PHONE_DISCOVERY_PORT = 37692
        const val GLASSES_INPUT_PORT = 37693
        const val TYPE_ENDPOINT = "controllerNetworkEndpoint"
        const val TYPE_ACK = "controllerNetworkAck"
        const val TYPE_PING = "controllerNetworkPing"
        const val FIELD_MESSAGE_ID = "messageId"
        private const val UDP_ACK_STALE_MS = 3000L
        private const val UDP_PROBE_INTERVAL_MS = 500L
        private const val RELIABLE_SEND_ATTEMPTS = 5
        private const val RELIABLE_ACK_TIMEOUT_MS = 120L
    }

    private data class PendingDatagram(val bytes: ByteArray, val endpoint: InetSocketAddress)
}
