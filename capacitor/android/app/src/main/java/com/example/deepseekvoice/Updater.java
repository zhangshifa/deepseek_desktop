package com.example.deepseekvoice;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线更新：启动/手动检查 GitHub 仓库内 apk/latest.json 版本清单，
 * 发现新版本提示用户 → 下载 app-debug.apk → 调用系统安装器安装。
 *
 * 更新源（私有仓库，走 api.github.com + 内嵌只读令牌）：
 *   GET /repos/{owner}/{repo}/contents/apk/latest.json?ref=main   (Accept: application/vnd.github.raw)
 *   GET /repos/{owner}/{repo}/contents/apk/app-debug.apk?ref=main (Accept: application/vnd.github.raw)
 *
 * ⚠️ 内嵌令牌为 fine-grained 只读（仅本仓库 Contents: Read-only），
 * 反编译可提取，泄漏风险仅限于读取该仓库文件；请勿改成有写权限的令牌。
 */
public class Updater {

    private static final String TAG = "DeepSeekVoiceUpdater";
    private static final String OWNER = "zhangshifa";
    private static final String REPO = "deepseek_desktop";
    private static final String API_BASE = "https://api.github.com";
    private static final String USER_AGENT = "DeepSeekVoice-Android/1.0";

    /** 内嵌只读令牌：只读、限本仓库，见类注释。 */
    private static final String TOKEN = "github_pat_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int localVersionCode;

    public Updater(Context context, int localVersionCode) {
        this.context = context;
        this.localVersionCode = localVersionCode;
    }

    /** 异步检查更新：有新版弹提示，无新版 Toast 提示（手动触发时可见）。 */
    public void check() {
        new Thread(() -> {
            try {
                String raw = httpGet("/repos/" + OWNER + "/" + REPO + "/contents/apk/latest.json?ref=main",
                        "application/vnd.github.raw");
                JSONObject jo = new JSONObject(raw);
                int remoteCode = jo.getInt("version_code");
                String remoteName = jo.optString("version_name", "");
                String notes = jo.optString("notes", "");
                if (remoteCode > localVersionCode) {
                    main.post(() -> promptUpdate(remoteName, notes));
                } else {
                    main.post(() -> Toast.makeText(context, "已是最新版本 v" + remoteName,
                            Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.w(TAG, "check update failed", e);
                main.post(() -> Toast.makeText(context, "检查更新失败，请稍后重试",
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void promptUpdate(String versionName, String notes) {
        new AlertDialog.Builder(context)
                .setTitle("发现新版本 v" + versionName)
                .setMessage(notes == null || notes.isEmpty() ? "是否立即更新？" : notes)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall())
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void downloadAndInstall() {
        final File apk = new File(context.getCacheDir(), "app-debug.apk");
        new Thread(() -> {
            try {
                httpGetToFile("/repos/" + OWNER + "/" + REPO + "/contents/apk/app-debug.apk?ref=main",
                        "application/vnd.github.raw", apk);
                main.post(() -> {
                    Toast.makeText(context, "下载完成，开始安装…", Toast.LENGTH_SHORT).show();
                    install(apk);
                });
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                main.post(() -> Toast.makeText(context, "下载失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** 拉起系统安装器（FileProvider 授权给系统安装器读取缓存 APK）。 */
    private void install(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            Toast.makeText(context, "无法打开安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String httpGet(String path, String accept) throws Exception {
        HttpURLConnection c = open(path, accept);
        try {
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private void httpGetToFile(String path, String accept, File out) throws Exception {
        HttpURLConnection c = open(path, accept);
        try {
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.close();
            in.close();
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path, String accept) throws Exception {
        URL url = new URL(API_BASE + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        return c;
    }
}
