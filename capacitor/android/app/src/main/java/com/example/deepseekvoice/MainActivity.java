package com.example.deepseekvoice;

import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Capacitor 承载的 DeepSeek 语音客户端。
 * UI（语音按钮 + 输入/播报开关）是 Web 技术写的 overlay.js，由本机在页面加载后注入。
 */
public class MainActivity extends BridgeActivity {

    private VoiceBridge voiceBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 运行时申请麦克风权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        // 等 Capacitor 的 WebView 就绪后，承载 DeepSeek 网页并注入 Web 语音层
        mainHandler.postDelayed(() -> {
            try {
                WebView wv = getAppWebView();
                if (wv == null) return;

                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setAllowFileAccess(true);

                voiceBridge = new VoiceBridge(this);
                wv.addJavascriptInterface(voiceBridge, "VoiceBridge");

                // 页面加载完成后注入 overlay（Web 写的浮动工具条）
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        injectOverlay(view);
                    }
                });

                // 承载 DeepSeek 网页：账号密码登录，免 API Key
                wv.loadUrl("https://chat.deepseek.com");
            } catch (Exception e) {
                Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 600);
    }

    /** 供 VoiceBridge 拿到 WebView 回传识别结果 */
    public WebView getAppWebView() {
        return bridge != null ? bridge.getWebView() : null;
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
}
