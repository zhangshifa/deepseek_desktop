package com.example.deepseekvoice;

import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 标准 AndroidX WebView 工程承载 DeepSeek 网页 + 原生语音层。
 * UI（语音按钮 + 输入/播报开关）是 Web 技术写的 overlay.js，由本机在页面加载后注入。
 * 账号密码登录 chat.deepseek.com，免 API Key。
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private VoiceBridge voiceBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        // 运行时申请麦克风权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        // Android 12+ 蓝牙连接权限（蓝牙耳机输入/输出）
        if (android.os.Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
        }

        WebView.setWebContentsDebuggingEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        voiceBridge = new VoiceBridge(this, new BluetoothAudio(this));
        webView.addJavascriptInterface(voiceBridge, "VoiceBridge");

        // 页面加载完成后注入 overlay（Web 写的浮动工具条）
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectOverlay(view);
            }
        });

        // 支持 overlay 的 prompt()（免手模式改唤醒词）→ 弹原生输入框
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(message)
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .show();
                return true;
            }
        });

        // 承载 DeepSeek 网页：账号密码登录，免 API Key
        webView.loadUrl("https://chat.deepseek.com");

        // 在线更新检查（异步，有新版才弹窗）
        try {
            int vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            new Updater(this, vc).check();
        } catch (Exception ignored) {
        }
    }

    /** 供 VoiceBridge 拿到 WebView 回传识别结果 */
    public WebView getWebView() {
        return webView;
    }

    private void injectOverlay(WebView view) {
        try {
            String js = readAsset("overlay.js");
            view.evaluateJavascript(js, null);
        } catch (Exception ignored) {
            // 资源缺失时静默失败，不影响网页本身使用
        }
    }

    private String readAsset(String name) throws Exception {
        InputStream is = getAssets().open(name);
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        is.close();
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
