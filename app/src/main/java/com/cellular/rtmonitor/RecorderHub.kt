package com.cellular.rtmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide owner of the modem polling, GPS and the persistent sample store.
 * It is started by the foreground service (and by the activity), so recording continues
 * when the app is in the background, the screen is off, or the activity has been destroyed.
 * The activity only *listens* while it exists.
 */
object RecorderHub {
    private const val TAG = "RecorderHub"
    private const val POLL_MS = 1000L

    private var storeRef: SampleStore? = null
    private var engine: ModemTelemetryEngine? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var locListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val payloadListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val locationListeners = CopyOnWriteArrayList<(Double, Double, Float) -> Unit>()

    @Volatile var lastLat: Double? = null
    @Volatile var lastLon: Double? = null
    @Volatile var lastAcc: Float? = null

    @Synchronized
    fun store(ctx: Context): SampleStore =
        storeRef ?: SampleStore(ctx.applicationContext).also { storeRef = it }

    fun addPayloadListener(l: (String) -> Unit) { payloadListeners.addIfAbsent(l) }
    fun removePayloadListener(l: (String) -> Unit) { payloadListeners.remove(l) }
    fun addLocationListener(l: (Double, Double, Float) -> Unit) {
        locationListeners.addIfAbsent(l)
        val la = lastLat; val lo = lastLon
        if (la != null && lo != null) l(la, lo, lastAcc ?: 0f)
    }
    fun removeLocationListener(l: (Double, Double, Float) -> Unit) { locationListeners.remove(l) }

    fun hasPermissions(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = started

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            try { engine?.requestFreshAndEmit() } catch (e: Exception) { Log.e(TAG, "poll error: ${e.message}", e) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Idempotent. Must be called on the main thread. No-op until the runtime permissions are granted. */
    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start(ctx: Context) {
        if (started) return
        val app = ctx.applicationContext
        if (!hasPermissions(app)) { Log.w(TAG, "start skipped: permissions missing"); return }
        val s = store(app)
        engine = ModemTelemetryEngine(app) { json ->
            try { s.addFromPayload(JSONObject(json), lastLat, lastLon, lastAcc) } catch (_: Exception) {}
            payloadListeners.forEach { try { it(json) } catch (_: Exception) {} }
        }
        startLocation(app)
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtmonitor:recording").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "wakelock: ${e.message}") }
        started = true
        engine?.startListening()
        handler.post(pollRunnable)
        Log.i(TAG, "recording started")
    }

    fun stop() {
        started = false
        handler.removeCallbacks(pollRunnable)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        locListener?.let { l -> try { appLocationManager?.removeUpdates(l) } catch (_: Exception) {} }
        locListener = null
        engine = null
        Log.i(TAG, "recording stopped")
    }

    private var appLocationManager: LocationManager? = null

    @SuppressLint("MissingPermission")
    private fun startLocation(app: Context) {
        if (locListener != null) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        appLocationManager = lm
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastLat = loc.latitude; lastLon = loc.longitude; lastAcc = loc.accuracy
                locationListeners.forEach { try { it(loc.latitude, loc.longitude, loc.accuracy) } catch (_: Exception) {} }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locListener = listener
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "location: ${e.message}") }
    }
}
