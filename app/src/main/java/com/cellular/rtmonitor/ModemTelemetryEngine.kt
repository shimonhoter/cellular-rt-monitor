// Update to version 4.1.7
package com.cellular.rtmonitor

import android.annotation.SuppressLint
import android.content.Context
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
            "Restricted (No Permission)"
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

            val payload = JSONObject()
            payload.put("plmn", formattedPlmn)
            payload.put("imsi", resolveDeviceImsi())
            payload.put("timestamp", System.currentTimeMillis())

            val neighborsArray = JSONArray()
            var servingFound = false

            for (info in cellInfoList) {
                if (info.isRegistered && !servingFound) {
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

                val rawNrSinr = when {
                    isValidSinr(sig.ssSinr) -> sig.ssSinr
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isValidSinr(sig.csiSinr) -> sig.csiSinr
                    else -> fallbackSinrFromSignalStrength(preferNr = true)
                }

                obj.put("rat", "5G")
                obj.put("tech", "5G NR")
                obj.put("band", bands)
                obj.put("pci", id.pci)
                obj.put("gnb", id.nci shr 14)
                obj.put("arfcn", id.nrarfcn)
                obj.put("tac", id.tac)
                obj.put("rsrp", sig.ssRsrp)
                obj.put("rsrq", sig.ssRsrq)
                if (rawNrSinr != null) {
                    obj.put("sinr", rawNrSinr)
                } else {
                    obj.put("sinr", JSONObject.NULL)
                }
                obj.put("rssi", sig.ssRsrp + 22)
            }

            info is CellInfoLte -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id.bands.isNotEmpty()) {
                    "B${id.bands.joinToString("+B")}"
                } else {
                    "LTE"
                }

                val resolvedSinr = when {
                    isValidSinr(sig.rssnr) -> sig.rssnr
                    else -> fallbackSinrFromSignalStrength(preferNr = false)
                }

                obj.put("rat", "4G")
                obj.put("tech", "4G LTE")
                obj.put("band", bands)
                obj.put("pci", id.pci)
                obj.put("gnb", id.ci shr 8)
                obj.put("arfcn", id.earfcn)
                obj.put("tac", id.tac)
                obj.put("rsrp", sig.rsrp)
                obj.put("rsrq", sig.rsrq)
                if (resolvedSinr != null) {
                    obj.put("sinr", resolvedSinr)
                } else {
                    obj.put("sinr", JSONObject.NULL)
                }
                obj.put("rssi", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sig.rssi != CellInfo.UNAVAILABLE) sig.rssi else (sig.rsrp + 24))
                obj.put("ta", if (sig.timingAdvance != CellInfo.UNAVAILABLE) sig.timingAdvance else 0)
            }

            info is CellInfoWcdma -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                obj.put("rat", "3G")
                obj.put("tech", "3G UMTS")
                obj.put("band", "U2100 / U900")
                obj.put("pci", id.psc)
                obj.put("gnb", id.cid)
                obj.put("arfcn", id.uarfcn)
                obj.put("tac", id.lac)
                obj.put("rsrp", sig.dbm)
                obj.put("rsrq", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) sig.ecNo else -8)
                obj.put("sinr", 10)
                obj.put("rssi", sig.dbm + 10)
            }

            info is CellInfoGsm -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                obj.put("rat", "2G")
                obj.put("tech", "2G GSM")
                obj.put("band", "GSM 900/1800")
                obj.put("pci", id.bsic)
                obj.put("gnb", id.cid)
                obj.put("arfcn", id.arfcn)
                obj.put("tac", id.lac)
                obj.put("rsrp", sig.dbm)
                obj.put("rsrq", sig.bitErrorRate)
                obj.put("sinr", 15)
                obj.put("rssi", sig.dbm)
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
                obj.put("rsrp", sig.ssRsrp)
                obj.put("rsrq", sig.ssRsrq)
            }

            info is CellInfoLte -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                if (id.pci == CellInfo.UNAVAILABLE) return null
                obj.put("pci", id.pci)
                obj.put("tech", "4G LTE")
                obj.put("arfcn", id.earfcn)
                obj.put("rsrp", sig.rsrp)
                obj.put("rsrq", sig.rsrq)
            }

            info is CellInfoWcdma -> {
                val id = info.cellIdentity
                val sig = info.cellSignalStrength
                if (id.psc == CellInfo.UNAVAILABLE) return null
                obj.put("pci", id.psc)
                obj.put("tech", "3G UMTS")
                obj.put("arfcn", id.uarfcn)
                obj.put("rsrp", sig.dbm)
                obj.put("rsrq", -10)
            }

            else -> return null
        }
        return obj
    }
}
