// Update to version 4.1.2
package com.cellular.rtmonitor

// [START: Imports]
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process as AndroidProcess
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
// [END: Imports]

// [START: RealModemControllerClass]
class RealModemController(private val context: Context) {

    companion object {
        private const val TAG = "RealModemController"
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // [START: ShizukuHelpers]
    private fun isShizukuAvailable(): Boolean {
        return try {
            val binderOk = Shizuku.pingBinder()
            val perm = Shizuku.checkSelfPermission()
            val granted = perm == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "isShizukuAvailable: binder=$binderOk, perm=$perm, granted=$granted")
            binderOk && granted
        } catch (e: Exception) {
            Log.e(TAG, "isShizukuAvailable error: ${e.message}", e)
            false
        }
    }

    private fun runShizukuShellCommand(command: String): Boolean {
        return try {
            Log.i(TAG, "runShizukuShellCommand start: $command")
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            val outText = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            val errText = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText().trim() }
            val exitCode = process.waitFor()

            if (outText.isNotEmpty()) {
                Log.i(TAG, "sh stdout: $outText")
            }
            if (errText.isNotEmpty()) {
                Log.w(TAG, "sh stderr: $errText")
            }
            Log.i(TAG, "sh exitCode=$exitCode for cmd: $command")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "runShizukuShellCommand exception for [$command]: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }
    // [END: ShizukuHelpers]

    // [START: NetworkTypeLock]
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

        val legacyNetworkMode: Int = when (ratMode) {
            "5G" -> 23   // NR_ONLY
            "4G" -> 11   // LTE_ONLY
            "3G" -> 2    // WCDMA_ONLY
            "2G" -> 1    // GSM_ONLY
            else -> 26   // NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (Auto 5G/4G/3G/2G)
        }

        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val userId = AndroidProcess.myUserHandle().hashCode()
        Log.i(
            TAG,
            "setRealNetworkTypeLock: ratMode=$ratMode, bitmask=$networkTypeBitmask, legacyMode=$legacyNetworkMode, subId=$subId, userId=$userId"
        )

        if (isShizukuAvailable()) {
            val applySettingsCmd = buildString {
                append("settings put global preferred_network_mode $legacyNetworkMode; ")
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    append("settings put global preferred_network_mode$subId $legacyNetworkMode; ")
                }
                append("settings put global preferred_network_mode1 $legacyNetworkMode")
            }

            val cmdPhoneAttempt = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "(cmd phone set-allowed-network-types-for-users $userId $networkTypeBitmask || cmd phone set-allowed-network-types-for-users 0 $networkTypeBitmask || cmd phone set-allowed-network-types-for-users $subId $networkTypeBitmask)"
            } else {
                "(cmd phone set-allowed-network-types-for-users $userId $networkTypeBitmask || cmd phone set-allowed-network-types-for-users 0 $networkTypeBitmask)"
            }

            val phoneOk = runShizukuShellCommand(cmdPhoneAttempt)
            val settingsOk = runShizukuShellCommand(applySettingsCmd)
            Log.i(TAG, "Shizuku results: phoneOk=$phoneOk, settingsOk=$settingsOk")

            if (phoneOk) {
                return
            }
        }

        try {
            Log.i(TAG, "Fallback to TelephonyManager.setAllowedNetworkTypesForReason($networkTypeBitmask)")
            telephonyManager.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                networkTypeBitmask
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in setAllowedNetworkTypesForReason: ${e.message}")
            openDeviceNetworkSettings()
        }
    }
    // [END: NetworkTypeLock]

    // [START: AirplaneAndSettings]
    fun setRealAirplaneMode(enable: Boolean) {
        val state = if (enable) 1 else 0
        val boolStr = if (enable) "enable" else "disable"
        Log.i(TAG, "setRealAirplaneMode: enable=$enable, state=$state")

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
            Log.w(TAG, "SecurityException in setRealAirplaneMode: ${e.message}")
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openDeviceNetworkSettings() {
        Log.i(TAG, "openDeviceNetworkSettings")
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    // [END: AirplaneAndSettings]
}
// [END: RealModemControllerClass]
