package com.example.deepseekvoice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JsPromptResult;
import android.webkit.ValueCallback;
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

    private static final int REQ_MIC = 1;

    private WebView webView;
    private VoiceBridge voiceBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> uploadMessage;   // 网页图片上传回调（智能眼镜图片输入）

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        // 运行时申请麦克风权限（电话模式必需；未授权时识别器只会空转不出声）
        ensureMicPermission();
        // Android 12+ 蓝牙连接权限（蓝牙耳机输入/输出）
        if (android.os.Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
        }
        // 相册读取权限（智能眼镜图片输入：取最新照片上传给 DeepSeek）
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 3);
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3);
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
                // 防"语音不可用"：部分 ROM/跨域导航后 JS 桥对象丢失，重新挂载
                reattachBridge();
                injectOverlay(view);
            }
        });

        // 支持 overlay 的 prompt()（免手模式改唤醒词）→ 弹原生输入框
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                // 在线更新：overlay 点"检查更新"触发手动检查（silent=false，无新版/失败都会提示）
                if ("__DS_CHECK_UPDATE__".equals(message)) {
                    new Updater(MainActivity.this, BuildConfig.VERSION_CODE,
                            BuildConfig.VERSION_NAME, false).check();
                    result.confirm("");
                    return true;
                }
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

            // 智能眼镜图片输入：网页触发图片上传时，直接返回相册最新照片（优先眼镜相册）
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;
                boolean isImage = fileChooserParams != null
                        && fileChooserParams.getAcceptTypes() != null;
                if (isImage) {
                    isImage = false;
                    for (String t : fileChooserParams.getAcceptTypes()) {
                        if (t != null && t.toLowerCase().contains("image")) {
                            isImage = true;
                            break;
                        }
                    }
                }
                if (isImage) {
                    Uri latest = LatestPhotoFinder.findLatest(MainActivity.this);
                    if (latest != null) {
                        Toast.makeText(MainActivity.this,
                                "已使用最新照片（智能眼镜优先）作为输入", Toast.LENGTH_SHORT).show();
                        uploadMessage.onReceiveValue(new Uri[]{latest});
                        uploadMessage = null;
                        return true;
                    }
                    Toast.makeText(MainActivity.this, "相册没有照片，请先用眼镜拍照",
                            Toast.LENGTH_SHORT).show();
                }
                uploadMessage.onReceiveValue(null);
                uploadMessage = null;
                return true;
            }
        });

        // 承载 DeepSeek 网页：账号密码登录，免 API Key
        webView.loadUrl("https://chat.deepseek.com");

        // 在线更新检查（异步、静默：仅在发现新版本时弹窗，不打扰正常使用）
        try {
            new Updater(this, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, true).check();
        } catch (Exception ignored) {
        }
    }

    /** 供 VoiceBridge 拿到 WebView 回传识别结果 */
    public WebView getWebView() {
        return webView;
    }

    /** 确保麦克风权限：未授予则弹系统授权框（VoiceBridge 接通电话模式前会调用）。 */
    public void ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        // 通知网页：授权结果 → 已授权则自动重新接通电话模式，被拒则显示原因
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__onSpeech&&window.__onSpeech({type:'perm',text:'"
                            + (granted ? "granted" : "denied") + "'});", null);
        }
    }

    private void injectOverlay(WebView view) {
        try {
            String js = readAsset("overlay.js");
            view.evaluateJavascript(js, null);
        } catch (Exception ignored) {
            // 资源缺失时静默失败，不影响网页本身使用
        }
    }

    /** 重新挂载 JS 桥：addJavascriptInterface 跨域导航/部分 ROM 可能失效，重挂一次保证可用。 */
    private void reattachBridge() {
        try {
            if (voiceBridge == null) {
                voiceBridge = new VoiceBridge(this, new BluetoothAudio(this));
            }
            webView.removeJavascriptInterface("VoiceBridge");
            webView.addJavascriptInterface(voiceBridge, "VoiceBridge");
        } catch (Exception ignored) {
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
