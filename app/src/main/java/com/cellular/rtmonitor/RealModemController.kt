package com.cellular.rtmonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import rikka.shizuku.Shizuku

class RealModemController(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun runShizukuShellCommand(command: String): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            process.waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun setRealNetworkTypeLock(ratMode: String) {
        val networkTypeBitmask: Long = when (ratMode) {
            "5G" -> TelephonyManager.NETWORK_TYPE_BITMASK_NR
            "4G" -> TelephonyManager.NETWORK_TYPE_BITMASK_LTE
            "3G" -> (TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
                     TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or
                     TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
                     TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or
                     TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP)
            "2G" -> (TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
                     TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or
                     TelephonyManager.NETWORK_TYPE_BITMASK_EDGE)
            else -> (TelephonyManager.NETWORK_TYPE_BITMASK_NR or
                     TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                     TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
                     TelephonyManager.NETWORK_TYPE_BITMASK_GSM)
        }

        // Legacy RIL preferred_network_mode integer constants for AOSP / MIUI / OneUI
        val legacyNetworkMode: Int = when (ratMode) {
            "5G" -> 23   // NR_ONLY
            "4G" -> 11   // LTE_ONLY
            "3G" -> 2    // WCDMA_ONLY
            "2G" -> 1    // GSM_ONLY
            else -> 26   // NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (Auto 5G/4G/3G/2G)
        }

        val subId = SubscriptionManager.getDefaultDataSubscriptionId()

        if (isShizukuAvailable()) {
            // Try modern cmd phone with subId, plus direct global settings fallback via Shizuku shell
            val shellCmd = buildString {
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    append("cmd phone set-allowed-network-types-for-users -s $subId $networkTypeBitmask || ")
                }
                append("cmd phone set-allowed-network-types-for-users $networkTypeBitmask; ")
                append("settings put global preferred_network_mode $legacyNetworkMode; ")
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    append("settings put global preferred_network_mode$subId $legacyNetworkMode; ")
                }
                append("settings put global preferred_network_mode1 $legacyNetworkMode")
            }

            if (runShizukuShellCommand(shellCmd)) {
                return
            }
        }

        try {
            telephonyManager.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                networkTypeBitmask
            )
        } catch (e: SecurityException) {
            openDeviceNetworkSettings()
        }
    }

    fun setRealAirplaneMode(enable: Boolean) {
        val state = if (enable) 1 else 0
        val boolStr = if (enable) "enable" else "disable"

        if (isShizukuAvailable()) {
            val cmd = "cmd connectivity airplane-mode $boolStr || (settings put global airplane_mode_on $state && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable)"
            if (runShizukuShellCommand(cmd)) return
        }

        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                state
            )
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                putExtra("state", enable)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openDeviceNetworkSettings() {
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
