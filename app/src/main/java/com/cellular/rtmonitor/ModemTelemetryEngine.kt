// Update to version 4.1.7
package com.cellular.rtmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.*
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class ModemTelemetryEngine(
    private val context: Context,
    private val onTelemetryUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "ModemTelemetryEngine"
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("rtmonitor_telemetry_prefs", Context.MODE_PRIVATE)
    private var lastServingPci: Int = prefs.getInt("last_serving_pci", -1)

    fun resetPersistedHandoverState() {
        lastServingPci = -1
        prefs.edit().remove("last_serving_pci").apply()
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        collectAndEmitTelemetry()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.CellInfoListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    collectAndEmitTelemetry()
                }

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    collectAndEmitTelemetry(cellInfo)
                }
            }
            telephonyManager.registerTelephonyCallback(executor, callback)
        } else {
            @Suppress("DEPRECATION")
            val legacyListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    collectAndEmitTelemetry()
                }

                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    collectAndEmitTelemetry(cellInfo)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(
                legacyListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CELL_INFO
            )
        }
    }

    /** Put the value as reported by the modem; JSON null when the modem reports UNAVAILABLE. */
    private fun JSONObject.putReported(key: String, v: Int) {
        if (v == CellInfo.UNAVAILABLE || v == Int.MAX_VALUE) put(key, JSONObject.NULL) else put(key, v)
    }

    /** Current LTE signal as reported by the modem via SignalStrength (fills gaps in a CellInfo that lacks values). */
    private fun liveLte(): CellSignalStrengthLte? = try {
        telephonyManager.signalStrength?.cellSignalStrengths?.filterIsInstance<CellSignalStrengthLte>()?.firstOrNull()
    } catch (_: Exception) { null }

    private fun validDbm(v: Int) = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE

    private fun isValidSinr(value: Int): Boolean {
        return value != CellInfo.UNAVAILABLE && value != Int.MAX_VALUE && value in -30..50
    }

    @SuppressLint("MissingPermission")
    private fun fallbackSinrFromSignalStrength(preferNr: Boolean): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ssList = telephonyManager.signalStrength?.cellSignalStrengths ?: return null
                if (preferNr) {
                    for (s in ssList) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && s is CellSignalStrengthNr) {
                            if (isValidSinr(s.ssSinr)) return s.ssSinr
                            if (isValidSinr(s.csiSinr)) return s.csiSinr
                        }
                    }
                }
                for (s in ssList) {
                    if (s is CellSignalStrengthLte && isValidSinr(s.rssnr)) {
                        return s.rssnr
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && s is CellSignalStrengthNr) {
                        if (isValidSinr(s.ssSinr)) return s.ssSinr
                        if (isValidSinr(s.csiSinr)) return s.csiSinr
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun resolveDeviceImsi(): String {
        val hasPhonePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhonePerm) {
            return "Missing READ_PHONE_STATE Permission"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId().let {
                    if (it != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) it
                    else subMgr?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId ?: -1
                }
                if (subId != -1) {
                    val tmSub = telephonyManager.createForSubscriptionId(subId)
                    val sId = tmSub.subscriberId
                    if (!sId.isNullOrBlank()) return sId
                }
            }
            val direct = telephonyManager.subscriberId
            if (!direct.isNullOrBlank()) direct else "Unavailable"
        } catch (_: SecurityException) {
            "Restricted by OS/Carrier (SecurityException)"
        } catch (_: Exception) {
            "Unavailable"
        }
    }

    @SuppressLint("MissingPermission")
    fun collectAndEmitTelemetry(cachedCellList: List<CellInfo>? = null) {
        try {
            val operatorNumeric = telephonyManager.networkOperator ?: ""
            val operatorName = telephonyManager.networkOperatorName.ifEmpty {
                telephonyManager.simOperatorName.ifEmpty { "Unknown" }
            }
            val formattedPlmn = if (operatorNumeric.length >= 5) {
                "${operatorNumeric.substring(0, 3)}-${operatorNumeric.substring(3)} ($operatorName)"
            } else {
                "$operatorNumeric ($operatorName)"
            }

            val cellInfoList = cachedCellList ?: telephonyManager.allCellInfo ?: emptyList()
            emitFromList(cellInfoList, formattedPlmn)
        } catch (e: Exception) {
            Log.e(TAG, "collectAndEmitTelemetry error: ${e.message}", e)
        }
    }

    /** Ask the modem for a fresh measurement (bypasses the OS cache), then emit it. Falls back to cached data. */
    @SuppressLint("MissingPermission")
    fun requestFreshAndEmit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                telephonyManager.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        collectAndEmitTelemetry(cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        collectAndEmitTelemetry()
                    }
                })
                return
            } catch (_: Exception) {
            }
        }
        collectAndEmitTelemetry()
    }

    private fun emitFromList(cellInfoList: List<CellInfo>, formattedPlmn: String) {
        try {

            val payload = JSONObject()
            payload.put("plmn", formattedPlmn)
            payload.put("imsi", resolveDeviceImsi())
            payload.put("timestamp", System.currentTimeMillis())

            val neighborsArray = JSONArray()
            // RT-FALLBACK-STRONGEST-CELL
            var servingFound = false
            val targetServingInfo: CellInfo? = cellInfoList.firstOrNull { it.isRegistered }
                ?: cellInfoList.maxByOrNull { c ->
                    when (c) {
                        is CellInfoLte -> c.cellSignalStrength.rsrp.let { if (it != CellInfo.UNAVAILABLE) it else -999 }
                        is CellInfoWcdma -> c.cellSignalStrength.dbm.let { if (it != CellInfo.UNAVAILABLE) it else -999 }
                        is CellInfoGsm -> c.cellSignalStrength.dbm.let { if (it != CellInfo.UNAVAILABLE) it else -999 }
                        else -> -999
                    }
                }

            for (info in cellInfoList) {
                if (!servingFound && info === targetServingInfo) {
                    servingFound = true
                    val servingJson = parseServingCell(info, formattedPlmn)
                    val currentPci = servingJson.optInt("pci", -1)

                    if (lastServingPci != -1 && currentPci != -1 && currentPci != lastServingPci) {
                        servingJson.put("handoverOccurred", true)
                        servingJson.put("previousPci", lastServingPci)
                    } else {
                        servingJson.put("handoverOccurred", false)
                    }
                    lastServingPci = currentPci
                    if (currentPci != -1) {
                        prefs.edit().putInt("last_serving_pci", currentPci).apply()
                    }
                    Log.d(
                        TAG,
                        "Serving cell: tech=${servingJson.optString("tech")} pci=${servingJson.optInt("pci")} rsrp=${servingJson.opt("rsrp")} sinr=${servingJson.opt("sinr")}"
                    )
                    payload.put("servingCell", servingJson)
                } else {
                    val neighborJson = parseNeighborCell(info)
                    if (neighborJson != null) {
                        neighborsArray.put(neighborJson)
                    }
                }
            }

            payload.put("neighbors", neighborsArray)
            val jsonString = payload.toString()
            mainHandler.post {
                onTelemetryUpdate(jsonString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "collectAndEmitTelemetry error: ${e.message}", e)
        }
    }

    private fun parseServingCell(info: CellInfo, plmn: String): JSONObject {
        val obj = JSONObject()
        obj.put("plmn", plmn)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val sig = info.cellSignalStrength as CellSignalStrengthNr
                val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty()) {
                    "n${id.bands.joinToString("+n")}"
                } else {
                    "5G NR"
                }

                val rawNrSinr: Int? = when {
                    isValidSinr(sig.ssSinr) -> sig.ssSinr
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isValidSinr(sig.csiSinr) -> sig.csiSinr
                    else -> null   // not reported in this read: leave empty
                }

                obj.put("rat", "5G")
                obj.put("tech", "5G NR")
                obj.put("band", bands)
                obj.putReported("pci", id.pci)
                if (id.nci != CellInfo.UNAVAILABLE_LONG) obj.put("gnb", id.nci shr 14) else obj.put("gnb", JSONObject.NULL)
                obj.putReported("arfcn", id.nrarfcn)
                obj.putReported("tac", id.tac)
                obj.putReported("rsrp", sig.ssRsrp)
                obj.putReported("rsrq", sig.ssRsrq)
                if (rawNrSinr != null) {
                    obj.put("sinr", rawNrSinr)
                } else {
                    obj.put("sinr", JSONObject.NULL)
                }
                obj.put("rssi", JSONObject.NULL)
            }

            info is CellInfoLte -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty()) {
                    "B${id.bands.joinToString("+B")}"
                } else {
                    "LTE"
                }

                val resolvedSinr: Int? = if (isValidSinr(sig.rssnr)) sig.rssnr else null   // empty if not reported

                obj.put("rat", "4G")
                obj.put("tech", "4G LTE")
                obj.put("band", bands)
                obj.putReported("pci", id.pci)
                if (id.ci != CellInfo.UNAVAILABLE) obj.put("gnb", id.ci shr 8) else obj.put("gnb", JSONObject.NULL)
                obj.putReported("arfcn", id.earfcn)
                obj.putReported("tac", id.tac)
                obj.putReported("rsrp", sig.rsrp)
                obj.putReported("rsrq", sig.rsrq)
                if (resolvedSinr != null) {
                    obj.put("sinr", resolvedSinr)
                } else {
                    obj.put("sinr", JSONObject.NULL)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) obj.putReported("rssi", sig.rssi) else obj.put("rssi", JSONObject.NULL)
                obj.putReported("ta", sig.timingAdvance)
            }

            info is CellInfoWcdma -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                obj.put("rat", "3G")
                obj.put("tech", "3G UMTS")
                obj.put("band", "U2100 / U900")
                obj.putReported("pci", id.psc)
                obj.putReported("gnb", id.cid)
                obj.putReported("arfcn", id.uarfcn)
                obj.putReported("tac", id.lac)
                obj.putReported("rsrp", sig.dbm)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) obj.putReported("rsrq", sig.ecNo) else obj.put("rsrq", JSONObject.NULL)
                obj.put("sinr", JSONObject.NULL)
                obj.put("rssi", JSONObject.NULL)
            }

            info is CellInfoGsm -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                obj.put("rat", "2G")
                obj.put("tech", "2G GSM")
                obj.put("band", "GSM 900/1800")
                obj.putReported("pci", id.bsic)
                obj.putReported("gnb", id.cid)
                obj.putReported("arfcn", id.arfcn)
                obj.putReported("tac", id.lac)
                obj.putReported("rsrp", sig.dbm)
                obj.put("rsrq", JSONObject.NULL)
                obj.putReported("ber", sig.bitErrorRate)
                obj.put("sinr", JSONObject.NULL)
                obj.putReported("rssi", sig.dbm)
            }
        }
        return obj
    }

    private fun parseNeighborCell(info: CellInfo): JSONObject? {
        val obj = JSONObject()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val sig = info.cellSignalStrength as CellSignalStrengthNr
                if (id.pci == CellInfo.UNAVAILABLE) return null
                obj.put("pci", id.pci)
                obj.put("tech", "5G NR")
                obj.put("arfcn", id.nrarfcn)
                obj.putReported("rsrp", sig.ssRsrp)
                obj.putReported("rsrq", sig.ssRsrq)
            }

            info is CellInfoLte -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                if (id.pci == CellInfo.UNAVAILABLE) return null
                obj.put("pci", id.pci)
                obj.put("tech", "4G LTE")
                obj.put("arfcn", id.earfcn)
                obj.putReported("rsrp", sig.rsrp)
                obj.putReported("rsrq", sig.rsrq)
            }

            info is CellInfoWcdma -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                if (id.psc == CellInfo.UNAVAILABLE) return null
                obj.put("pci", id.psc)
                obj.put("tech", "3G UMTS")
                obj.put("arfcn", id.uarfcn)
                obj.putReported("rsrp", sig.dbm)
                obj.put("rsrq", JSONObject.NULL)
            }

            else -> return null
        }
        return obj
    }
}
