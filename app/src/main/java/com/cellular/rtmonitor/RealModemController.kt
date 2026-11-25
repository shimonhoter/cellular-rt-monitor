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
    fun isShizukuAvailable(): Boolean {
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

    /** Runs a shell command with Shizuku (shell uid). Retries once on binder errors. Returns exit code + merged output. */
    fun runShizukuShell(command: String): Pair<Int, String> {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                Log.i(TAG, "runShizukuShell[$attempt]: $command")
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
                if (errText.isNotEmpty()) Log.w(TAG, "sh stderr: $errText")
                Log.i(TAG, "sh exitCode=$exitCode for cmd: $command")
                return exitCode to (if (errText.isEmpty()) outText else "$outText\n$errText").trim()
            } catch (e: Exception) {
                last = e
                Log.e(TAG, "runShizukuShell exception: ${e.message}", e)
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            }
        }
        return -1 to "ERROR: ${last?.javaClass?.simpleName}: ${last?.message}"
    }

    private fun runShizukuShellCommand(command: String): Boolean = runShizukuShell(command).first == 0

    /** Connection self-test: should print uid=2000(shell). */
    fun shizukuSelfTest(): String {
        if (!isShizukuAvailable()) return "Shizuku לא מוכן (לא פועל או חסרה הרשאה)"
        val (code, out) = runShizukuShell("id")
        return if (code == 0) "OK: $out" else "נכשל (קוד $code): $out"
    }

    /** Looks for any UE transmit-power value the platform exposes. Android has no public API for it. */
    fun probeTxPower(): String {
        if (!isShizukuAvailable()) return "Shizuku לא מוכן"
        val (_, out) = runShizukuShell("dumpsys telephony.registry 2>/dev/null | grep -i -E 'txpower|tx_power|transmit' | head -n 8")
        return if (out.isBlank()) "לא נמצא שדה Tx power ב-telephony.registry (המכשיר לא חושף אותו)" else out
    }
    // [END: ShizukuHelpers]

    // [START: NetworkTypeLock]
    /** Single-generation / AUTO convenience wrapper (kept for older callers). */
    @RequiresApi(Build.VERSION_CODES.S)
    fun setRealNetworkTypeLock(ratMode: String) {
        setRealAllowedGenerations(if (ratMode == "AUTO") setOf("2G", "3G", "4G", "5G") else setOf(ratMode))
    }

    /** Allow exactly the given generations ("2G","3G","4G","5G"); all four = no lock. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun setRealAllowedGenerations(gens: Set<String>) {
        val ratMode = if (gens.size >= 4) "AUTO" else gens.sorted().joinToString("+")
        var networkTypeBitmask = 0L
        if ("5G" in gens) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_NR
        if ("4G" in gens) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
        if ("3G" in gens) networkTypeBitmask = networkTypeBitmask or
                TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP or
                TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA
        if ("2G" in gens) networkTypeBitmask = networkTypeBitmask or
                TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
                TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or
                TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
        if (networkTypeBitmask == 0L) return

        // Legacy RILConstants preferred_network_mode, only for combinations with a well-known value
        val legacyNetworkMode: Int? = when (gens.sorted().joinToString("+")) {
            "5G" -> 23              // NR_ONLY
            "4G" -> 11              // LTE_ONLY
            "3G" -> 2               // WCDMA_ONLY
            "2G" -> 1               // GSM_ONLY
            "4G+5G" -> 24           // NR_LTE
            "2G+3G" -> 3            // GSM_WCDMA
            "2G+3G+4G" -> 9         // LTE_GSM_WCDMA
            "2G+3G+4G+5G" -> 26
            "3G+4G" -> 12           // LTE_WCDMA
            else -> null
        }

        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val userId = AndroidProcess.myUserHandle().hashCode()
        Log.i(
            TAG,
            "setRealNetworkTypeLock: ratMode=$ratMode, bitmask=$networkTypeBitmask, legacyMode=$legacyNetworkMode, subId=$subId, userId=$userId"
        )

        if (isShizukuAvailable()) {
            val applySettingsCmd = if (legacyNetworkMode == null) "true" else buildString {
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

    fun isAirplaneModeOn(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (e: Exception) { false }

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
