package com.example.androidhaterminal

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var screenOverlay: View

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var cameraExecutor: ExecutorService? = null

    private var lastFrameLuminance: Double = -1.0
    private val motionThreshold = 10.0

    private val CAMERA_PERMISSION_REQUEST = 100
    private val PREFS_NAME = "HATerminalPrefs"
    private val KEY_URL = "url"
    private val KEY_TIMEOUT = "timeout_seconds"
    private val DEFAULT_URL = "http://homeassistant.local:8123"
    private val DEFAULT_TIMEOUT = 30

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressDuration = 5000L
    private var longPressRunnable: Runnable? = null

    private val sleepRunnable = Runnable { sleepScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        screenOverlay = findViewById(R.id.screenOverlay)

        setupWebView()
        setupTouchListener()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        webView.loadUrl(url)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }

        resetIdleTimer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.webViewClient = WebViewClient()
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val touchTarget = window.decorView
        touchTarget.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isScreenOn) {
                        wakeScreen()
                    }
                    resetIdleTimer()
                    startLongPressTimer()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPressTimer()
                }
                MotionEvent.ACTION_MOVE -> {
                    resetIdleTimer()
                }
            }
            false
        }
    }

    private fun startLongPressTimer() {
        longPressRunnable = Runnable { showSettingsDialog() }
        longPressRunnable?.let { longPressHandler.postDelayed(it, longPressDuration) }
    }

    private fun cancelLongPressTimer() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(sleepRunnable)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timeoutSeconds = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT)
        handler.postDelayed(sleepRunnable, timeoutSeconds * 1000L)
    }

    private fun wakeScreen() {
        if (!isScreenOn) {
            isScreenOn = true
            screenOverlay.visibility = View.GONE
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
        resetIdleTimer()
    }

    private fun sleepScreen() {
        isScreenOn = false
        screenOverlay.visibility = View.VISIBLE
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
    }

    private fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                analyzeFrame(imageProxy)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                // Front camera may not exist, try default back camera
                try {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        imageAnalysis
                    )
                } catch (ex: Exception) {
                    // No camera available
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val luminance = data.map { it.toInt() and 0xFF }.average()
        if (lastFrameLuminance >= 0) {
            val diff = abs(luminance - lastFrameLuminance)
            if (diff > motionThreshold) {
                handler.post { wakeScreen() }
            }
        }
        lastFrameLuminance = luminance
        imageProxy.close()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val urlField = dialogView.findViewById<EditText>(R.id.editUrl)
        val timeoutField = dialogView.findViewById<EditText>(R.id.editTimeout)
        val saveButton = dialogView.findViewById<Button>(R.id.btnSave)
        val exitButton = dialogView.findViewById<Button>(R.id.btnExit)

        urlField.setText(prefs.getString(KEY_URL, DEFAULT_URL))
        timeoutField.setText(prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT).toString())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        saveButton.setOnClickListener {
            val newUrl = urlField.text.toString().trim()
            if (newUrl.isEmpty() || (!newUrl.startsWith("http://") && !newUrl.startsWith("https://"))) {
                urlField.error = "URL must start with http:// or https://"
                return@setOnClickListener
            }
            val timeoutText = timeoutField.text.toString().trim()
            val newTimeout = timeoutText.toIntOrNull()
            if (newTimeout == null || newTimeout <= 0) {
                timeoutField.error = "Must be a positive number"
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_URL, newUrl)
                .putInt(KEY_TIMEOUT, newTimeout)
                .apply()
            webView.loadUrl(newUrl)
            resetIdleTimer()
            dialog.dismiss()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        exitButton.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }

        dialog.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
        handler.removeCallbacks(sleepRunnable)
        cancelLongPressTimer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }
}
