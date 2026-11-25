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
    private lateinit var telemetryEngine: ModemTelemetryEngine
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

        telemetryEngine = ModemTelemetryEngine(this) { jsonPayload ->
            val jsCall = "if (window.onRealModemData) { window.onRealModemData($jsonPayload); }"
            webView.evaluateJavascript(jsCall, null)
        }

        checkAndRequestPermissions()
        requestShizukuPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
        startBackgroundMonitorServiceSafely()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuUiState()
        startBackgroundMonitorServiceSafely()
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

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun updateShizukuUiState() {
        val ready = isShizukuReady()
        webView.post {
            webView.evaluateJavascript(
                "if (window.onShizukuStatus) { window.onShizukuStatus($ready); }",
                null
            )
        }
    }

    private fun requestShizukuPermissionIfNeeded() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_CODE)
                }
            }
        } catch (e: Exception) {
            // Shizuku not running
        }
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
        fun setAirplaneMode(enable: Boolean) {
            modemController.setRealAirplaneMode(enable)
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
            val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this@MainActivity, "אפליקציית Shizuku לא מותקנת במכשיר", Toast.LENGTH_SHORT).show()
            }
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

    private fun startRealModemStream() {
        telemetryEngine.startListening()
        val pollRunnable = object : Runnable {
            override fun run() {
                telemetryEngine.requestFreshAndEmit()
                pollingHandler.postDelayed(this, 1000)
            }
        }
        pollingHandler.post(pollRunnable)
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
            webView.evaluateJavascript("window.rtPersistFullStateNow && window.rtPersistFullStateNow()", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        pollingHandler.removeCallbacksAndMessages(null)
    }
}
