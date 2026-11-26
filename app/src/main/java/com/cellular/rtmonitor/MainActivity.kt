// Update to version 4.1.4
package com.cellular.rtmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var modemController: RealModemController
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val SHIZUKU_CODE = 1001
    private var allowExitApp = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data?.data != null) {
            arrayOf(data.data!!)
        } else {
            null
        }
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_CODE) {
            updateShizukuUiState()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (fineLocationGranted && phoneStateGranted) {
            startRealModemStream()
        }
        startBackgroundMonitorServiceSafely()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        modemController = RealModemController(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    updateShizukuUiState()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    val res = result ?: return false
                    runOnUiThread {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("אישור פעולת ניקוי")
                            .setMessage(message ?: "האם אתה בטוח שברצונך לנקות את הנתונים?")
                            .setPositiveButton("כן, נקה") { _, _ -> res.confirm() }
                            .setNegativeButton("ביטול") { _, _ -> res.cancel() }
                            .setOnCancelListener { res.cancel() }
                            .show()
                    }
                    return true
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidModem")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

        // The recorder (modem polling + GPS + sample store) lives in RecorderHub / the foreground service.
        // The activity only listens while it exists, so recording never depends on the UI.
        RecorderHub.addPayloadListener(uiPayloadListener)
        RecorderHub.addLocationListener(uiLocationListener)

        checkAndRequestPermissions()
        requestShizukuPermissionIfNeeded()
        pollingHandler.postDelayed(shizukuWatchRunnable, 3000L)
        requestNotificationPermissionIfNeeded()
        startBackgroundMonitorServiceSafely()
    }

    private val uiPayloadListener: (String) -> Unit = { json ->
        if (::webView.isInitialized) {
            webView.evaluateJavascript("if (window.onRealModemData) { window.onRealModemData($json); }", null)
        }
    }

    private val uiLocationListener: (Double, Double, Float) -> Unit = { lat, lon, acc ->
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.onRealLocation && window.onRealLocation($lat, $lon, $acc)", null)
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuUiState()
        startBackgroundMonitorServiceSafely()
        RecorderHub.start(this)
        // everything recorded while we were in the background is in the store: re-sync the UI from it
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.rtResync && window.rtResync()", null)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun startBackgroundMonitorServiceSafely() {
        try {
            BackgroundMonitorService.startService(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private var lastShizukuState = ""
    private var autoRequestedPermission = false

    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (_: Exception) { false }

    /** NOT_INSTALLED | NOT_RUNNING | NO_PERMISSION | DENIED | READY */
    private fun computeShizukuState(): String {
        return try {
            if (!Shizuku.pingBinder()) {
                if (isShizukuInstalled()) "NOT_RUNNING" else "NOT_INSTALLED"
            } else if (Shizuku.isPreV11()) {
                "NOT_RUNNING"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "READY"
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                "DENIED"
            } else {
                "NO_PERMISSION"
            }
        } catch (_: Throwable) {
            if (isShizukuInstalled()) "NOT_RUNNING" else "NOT_INSTALLED"
        }
    }

    private fun isShizukuReady(): Boolean = computeShizukuState() == "READY"

    /** Pushes the current Shizuku state to the UI; when the binder is up but permission missing, asks once. */
    private fun updateShizukuUiState() {
        val state = computeShizukuState()
        if (state == "NO_PERMISSION" && !autoRequestedPermission) {
            autoRequestedPermission = true
            try { Shizuku.requestPermission(SHIZUKU_CODE) } catch (_: Throwable) {}
        }
        if (state == "READY") autoRequestedPermission = false
        lastShizukuState = state
        if (!::webView.isInitialized) return
        val ready = state == "READY"
        webView.post {
            webView.evaluateJavascript(
                "if (window.onShizukuState) { window.onShizukuState('$state'); } if (window.onShizukuStatus) { window.onShizukuStatus($ready); }",
                null
            )
        }
    }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener {
        autoRequestedPermission = false
        runOnUiThread { updateShizukuUiState() }
    }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateShizukuUiState() }
    }

    /** Re-checks every 3 s until Shizuku is READY, so starting Shizuku after the app is picked up automatically. */
    private val shizukuWatchRunnable = object : Runnable {
        override fun run() {
            val prev = lastShizukuState
            val now = computeShizukuState()
            if (now != prev) updateShizukuUiState()
            pollingHandler.postDelayed(this, if (now == "READY") 10000L else 3000L)
        }
    }

    private fun requestShizukuPermissionIfNeeded() {
        updateShizukuUiState()
    }

    private fun openShizukuInstallPage() {
        val targets = listOf(
            "market://details?id=$SHIZUKU_PKG",
            "https://play.google.com/store/apps/details?id=$SHIZUKU_PKG",
            "https://github.com/RikkaApps/Shizuku/releases"
        )
        for (url in targets) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
    }

    private fun openShizukuManager() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) startActivity(launch) else openShizukuInstallPage()
    }

    private fun playBeep(count: Int) {
        Thread {
            try {
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
                repeat(count.coerceIn(1, 3)) {
                    tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180)
                    Thread.sleep(260)
                }
                Thread.sleep(150)
                tg.release()
            } catch (_: Exception) {}
        }.start()
    }

    inner class WebAppInterface {

                @JavascriptInterface
                fun saveStateToDevice(jsonStr: String) {
                    try {
                        val f = File(filesDir, "rt_persistent_state.json")
                        f.writeText(jsonStr, Charsets.UTF_8)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                @JavascriptInterface
                fun loadStateFromDevice(): String {
                    return try {
                        val f = File(filesDir, "rt_persistent_state.json")
                        if (f.exists()) f.readText(Charsets.UTF_8) else ""
                    } catch (e: Exception) {
                        ""
                    }
                }

                @JavascriptInterface
                fun clearStateOnDevice() {
                    try {
                        val f = File(filesDir, "rt_persistent_state.json")
                        if (f.exists()) f.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

        @JavascriptInterface
        fun setRatMode(ratMode: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modemController.setRealNetworkTypeLock(ratMode)
            } else {
                modemController.openDeviceNetworkSettings()
            }
        }

        @JavascriptInterface
        fun setAllowedGenerations(csv: String) {
            val gens = csv.split(",").map { it.trim() }.filter { it in setOf("2G", "3G", "4G", "5G") }.toSet()
            if (gens.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Thread { modemController.setRealAllowedGenerations(gens) }.start()
            } else {
                modemController.openDeviceNetworkSettings()
            }
        }

        @JavascriptInterface
        fun setAirplaneMode(enable: Boolean) {
            Thread {
                modemController.setRealAirplaneMode(enable)
                // report the real resulting state back to the UI
                pollingHandler.postDelayed({ pushAirplaneState() }, 1500)
            }.start()
        }

        @JavascriptInterface
        fun getAirplaneMode(): Boolean = modemController.isAirplaneModeOn()

        @JavascriptInterface
        fun openUrl(url: String) {
            if (!url.startsWith("http://documents.sviva.gov.il/") && !url.startsWith("https://documents.sviva.gov.il/")) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun openDeveloperOptions() {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread {
                allowExitApp = true
                try {
                    RecorderHub.stop()
                    BackgroundMonitorService.stopService(this@MainActivity)
                } catch (_: Exception) {
                }
                try {
                    finishAffinity()
                } catch (_: Exception) {
                    finish()
                }
                pollingHandler.postDelayed({
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: Exception) {
                    }
                }, 250)
            }
        }
        @JavascriptInterface
        fun dialTechnicianCode(code: String = "*#*#4636#*#*") {
            runOnUiThread {
                try {
                    val encoded = android.net.Uri.encode(code)
                    val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$encoded")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun openShizukuApp() {
            runOnUiThread { openShizukuManager() }
        }

        @JavascriptInterface
        fun getShizukuState(): String = computeShizukuState()

        /** One button that does the right next step for the current Shizuku state. */
        @JavascriptInterface
        fun shizukuConnect() {
            runOnUiThread {
                when (computeShizukuState()) {
                    "NOT_INSTALLED" -> openShizukuInstallPage()
                    "NOT_RUNNING" -> openShizukuManager()
                    "NO_PERMISSION" -> try { Shizuku.requestPermission(SHIZUKU_CODE) } catch (_: Throwable) {}
                    "DENIED" -> {
                        Toast.makeText(this@MainActivity, "ב-Shizuku: אפליקציות מורשות ← אפשר ל-RT-MON", Toast.LENGTH_LONG).show()
                        openShizukuManager()
                    }
                    else -> {}
                }
                updateShizukuUiState()
            }
        }

        @JavascriptInterface
        fun shizukuSelfTest() {
            Thread {
                val res = modemController.shizukuSelfTest()
                runOnUiThread {
                    webView.evaluateJavascript("window.onShizukuTest && window.onShizukuTest(${org.json.JSONObject.quote(res)})", null)
                }
            }.start()
        }

        @JavascriptInterface
        fun probeTxPower() {
            Thread {
                val res = modemController.probeTxPower()
                runOnUiThread {
                    webView.evaluateJavascript("window.onTxProbe && window.onTxProbe(${org.json.JSONObject.quote(res)})", null)
                }
            }.start()
        }

        // ---- persistent RF sample store (every sample is kept until the user presses "clear") ----
        @JavascriptInterface
        fun getSampleCount(): Long = sampleStore.total()

        @JavascriptInterface
        fun getSamplesPage(beforeId: Long, limit: Int): String = sampleStore.page(beforeId, limit)

        @JavascriptInterface
        fun getSamplesSince(afterId: Long, limit: Int): String = sampleStore.since(afterId, limit)

        @JavascriptInterface
        fun getSamplesChunk(afterId: Long, limit: Int): String = sampleStore.chunkAsc(afterId, limit)

        @JavascriptInterface
        fun clearSamples() { sampleStore.clear() }

        /** Streams ALL stored samples to Downloads as CSV or JSON; reports back via window.onExportDone. */
        @JavascriptInterface
        fun exportSamples(format: String) {
            val json = format == "json"
            Thread {
                var name = ""
                var rows = 0L
                var err = ""
                try {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    name = "cellular_rf_$stamp." + (if (json) "json" else "csv")
                    val mime = if (json) "application/json" else "text/csv"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) err = "לא ניתן ליצור קובץ" else
                            contentResolver.openOutputStream(uri)?.use { rows = sampleStore.export(it, json) }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        File(dir, name).outputStream().use { rows = sampleStore.export(it, json) }
                    }
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onExportDone && window.onExportDone(${org.json.JSONObject.quote(name)}, $rows, ${org.json.JSONObject.quote(err)})", null
                    )
                }
            }.start()
        }

        /** Opens the system print dialog for the current page ("Save as PDF"). */
        @JavascriptInterface
        fun printPage(title: String) {
            runOnUiThread {
                try {
                    val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
                    val attrs = android.print.PrintAttributes.Builder()
                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                        .build()
                    pm.print(title, webView.createPrintDocumentAdapter(title), attrs)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "הדפסה נכשלה: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ---- background recording status / exemptions ----
        @JavascriptInterface
        fun getHoCount(): Long = sampleStore.hoCount()

        @JavascriptInterface
        fun getBackgroundStatus(): String {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val bgLoc = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            return org.json.JSONObject()
                .put("recording", RecorderHub.isRunning())
                .put("batteryExempt", pm.isIgnoringBatteryOptimizations(packageName))
                .put("backgroundLocation", bgLoc)
                .toString()
        }

        @JavascriptInterface
        fun requestBatteryExemption() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun requestBackgroundLocation() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this@MainActivity, "הרשאות ← מיקום ← \"אפשר תמיד\"", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
        }

        @JavascriptInterface
        fun beep(count: Int) {
            playBeep(count)
        }

        @JavascriptInterface
        fun saveExportFile(filename: String, content: String) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, if (filename.endsWith(".json")) "application/json" else "text/csv")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File(dir, filename).writeText(content, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRealModemStream()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun pushAirplaneState() {
        if (!::webView.isInitialized) return
        val on = modemController.isAirplaneModeOn()
        webView.evaluateJavascript("window.onAirplaneState && window.onAirplaneState($on)", null)
    }

    private val sampleStore: SampleStore get() = RecorderHub.store(applicationContext)

    private fun startRealModemStream() {
        RecorderHub.start(this)
        pushAirplaneState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (allowExitApp) {
            super.onBackPressed()
        } else {
            Toast.makeText(this, "יציאה אפשרית רק דרך כפתור היציאה באפליקציה", Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        if (allowExitApp) {
            super.finish()
        } else {
            Toast.makeText(this, "סגירה חיצונית חסומה — השתמש בכפתור היציאה באפליקציה", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.rtPersistFullStateNow && window.rtPersistFullStateNow(true)", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        pollingHandler.removeCallbacksAndMessages(null)
        RecorderHub.removePayloadListener(uiPayloadListener)
        RecorderHub.removeLocationListener(uiLocationListener)
    }
}
