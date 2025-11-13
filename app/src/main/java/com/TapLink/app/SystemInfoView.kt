package com.TapLink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class SystemInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var connectivityIcon: ImageView? = null
    private var batteryIcon: ImageView? = null
    private var ringIcon: ImageView? = null
    private var timeText: TextView? = null
    private var dateText: TextView? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        val batteryPct = (level * 100f / scale).toInt()
                        post { updateBattery(batteryPct) }
                    }
                }
            } catch (e: Exception) {
                Log.e("SystemInfoView", "Battery update error", e)
            }
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Intent.ACTION_TIME_TICK) {
                    post { updateTimeAndDate() }
                }
            } catch (e: Exception) {
                Log.e("SystemInfoView", "Time update error", e)
            }
        }
    }

    init {
        try {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END    // Ensure parent layout centers children
            setPadding(4, 0, 4, 0)    // Removed vertical padding to let height control spacing
            setBackgroundColor(Color.parseColor("#202020"))
            minimumHeight = 24    // Ensure consistent height

            setupViews()

            post { startUpdates() }
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Initialization error", e)
        }
    }

    private fun setupViews() {
        removeAllViews()

        // Create and add ring icon
        ringIcon = createIconView().also { addView(it) }

        // Create and add connectivity icon
        connectivityIcon = createIconView().also { addView(it) }

        // Create and add battery icon
        batteryIcon = createIconView().also { addView(it) }

        // Create and add text views
        timeText = createTextView().also { addView(it) }
        dateText = createTextView().also { addView(it) }

        // Set initial values
        connectivityIcon?.setImageResource(R.drawable.wifi_off)
        batteryIcon?.setImageResource(R.drawable.battery_full)
        // Add this line to set initial ring icon state
        ringIcon?.setImageResource(R.drawable.ic_ring_disabled)  // Initially disabled
        timeText?.text = "--:--"
        dateText?.text = "--/--"
    }

    private fun createIconView(): ImageView {
        return ImageView(context).apply {
            layoutParams = LayoutParams(
                24,  // width in dp
                24   // height in dp
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(6, 0, 6, 0)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun createTextView(): TextView {
        return TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL  // Changed from CENTER to CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT      // Changed from WRAP_CONTENT to MATCH_PARENT
            ).apply {
                setMargins(6, 0, 6, 0)
            }
            includeFontPadding = false        // Removes extra padding around text
        }
    }

    private fun startUpdates() {
        try {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            context.registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK))

            updateConnectivity()
            updateTimeAndDate()

            postDelayed(object : Runnable {
                override fun run() {
                    if (isAttachedToWindow) {
                        updateConnectivity()
                        updateTimeAndDate()
                        postDelayed(this, 1000)
                    }
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Error starting updates", e)
        }
    }

    private fun updateConnectivity() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val networkCapabilities = cm.getNetworkCapabilities(activeNetwork)
            val hasVpnTransport = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
            val hasTunInterface = hasTunInterface()

            val iconResource = when {
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                    R.drawable.wifi_on
                }
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true ||
                        (hasVpnTransport && hasTunInterface) -> {
                    R.drawable.wifi_bluetooth
                }
                else -> {
                    R.drawable.wifi_off
                }
            }
            connectivityIcon?.setImageResource(iconResource)
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Connectivity update error", e)
            connectivityIcon?.setImageResource(R.drawable.wifi_off)
        }
    }

    private fun updateBattery(level: Int) {
        try {
            val iconResource = when {
                level > 80 -> R.drawable.battery_full
                level > 60 -> R.drawable.battery_75
                level > 40 -> R.drawable.battery_50
                level > 20 -> R.drawable.battery_25
                else -> R.drawable.battery_low
            }
            batteryIcon?.setImageResource(iconResource)
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Battery update error", e)
            batteryIcon?.setImageResource(R.drawable.battery_full)
        }
    }

    fun updateRingStatus(isConnected: Boolean) {
        ringIcon?.setImageResource(
            if (isConnected) R.drawable.ic_ring_enabled
            else R.drawable.ic_ring_disabled
        )
    }

    private fun updateTimeAndDate() {
        try {
            val now = Calendar.getInstance()
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateFormat = SimpleDateFormat("EEE dd MMM yyyy", Locale.ENGLISH)

            timeText?.text = timeFormat.format(now.time)
            dateText?.text = dateFormat.format(now.time)
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Time/date update error", e)
            timeText?.text = "--:--"
            dateText?.text = "--- -- --- ----"
        }
    }

    private fun hasTunInterface(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val hasTun = interfaces.any { networkInterface ->
                networkInterface.name == "tun0" && networkInterface.isUp
            }
            //Log.d("SystemInfoView", "Has TUN interface: $hasTun")
            hasTun
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Error checking TUN interface", e)
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { startUpdates() }
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(batteryReceiver)
            context.unregisterReceiver(timeReceiver)
        } catch (e: Exception) {
            Log.e("SystemInfoView", "Error unregistering receivers", e)
        }
        super.onDetachedFromWindow()
    }
}