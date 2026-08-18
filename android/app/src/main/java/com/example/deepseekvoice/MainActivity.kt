package com.example.deepseekvoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnMic: ImageButton
    private lateinit var toggleInput: ToggleButton
    private lateinit var toggleOutput: ToggleButton

    private lateinit var speechHelper: SpeechHelper
    private lateinit var ttsHelper: TtsHelper

    private var inputEnabled = true
    private var outputEnabled = true

    // 语音播报防抖：流式回复会触发多次 onReply，仅朗读停顿后的最终文本
    private val ttsHandler = Handler(Looper.getMainLooper())
    private var pendingReply: String? = null

    companion object {
        private const val REQ_AUDIO = 1001
        private const val DEEPSEEK_URL = "https://chat.deepseek.com"
        private const val TTS_DEBOUNCE_MS = 1200L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        btnMic = findViewById(R.id.btnMic)
        toggleInput = findViewById(R.id.toggleInput)
        toggleOutput = findViewById(R.id.toggleOutput)

        ttsHelper = TtsHelper(this)
        speechHelper = SpeechHelper(this)

        setupWebView()
        setupControls()
        requestAudioPermission()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 伪装成移动端 Chrome，确保 DeepSeek 网页正常加载
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectVoiceScript()
            }
        }
        webView.addJavascriptInterface(WebAppBridge { onReplyFromWeb(it) }, "AndroidBridge")
        webView.loadUrl(DEEPSEEK_URL)
    }

    private fun injectVoiceScript() {
        webView.evaluateJavascript(VoiceInjector.script, null)
    }

    private fun setupControls() {
        toggleInput.isChecked = inputEnabled
        toggleOutput.isChecked = outputEnabled
        btnMic.isEnabled = inputEnabled

        toggleInput.setOnCheckedChangeListener { _, isChecked ->
            inputEnabled = isChecked
            btnMic.isEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "语音输入：开" else "语音输入：关",
                Toast.LENGTH_SHORT
            ).show()
        }

        toggleOutput.setOnCheckedChangeListener { _, isChecked ->
            outputEnabled = isChecked
            if (!isChecked) {
                ttsHelper.stop()
                ttsHandler.removeCallbacksAndMessages(null)
            }
            Toast.makeText(
                this,
                if (isChecked) "语音播报：开" else "语音播报：关",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnMic.setOnClickListener {
            if (!inputEnabled) return@setOnClickListener
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestAudioPermission()
                return@setOnClickListener
            }
            speechHelper.start(
                onResult = { text ->
                    // 把识别到的文字填进网页输入框
                    webView.evaluateJavascript(
                        "window.VoiceFill(${JSONObject.quote(text)})",
                        null
                    )
                },
                onError = { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /** 来自网页的回复文本（已在 JS 侧尽力去重） */
    private fun onReplyFromWeb(text: String) {
        if (!outputEnabled) return
        pendingReply = text
        ttsHandler.removeCallbacksAndMessages(null)
        ttsHandler.postDelayed({
            if (outputEnabled) ttsHelper.speak(pendingReply ?: "")
        }, TTS_DEBOUNCE_MS)
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                inputEnabled = false
                toggleInput.isChecked = false
                btnMic.isEnabled = false
                Toast.makeText(this, "未授权麦克风，语音输入不可用", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ttsHandler.removeCallbacksAndMessages(null)
        speechHelper.destroy()
        ttsHelper.shutdown()
        webView.destroy()
        super.onDestroy()
    }
}
