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
 * 在线版本检测与升级（公开仓库，免令牌）。
 * 检测清单 apk/latest.json 与下载 APK 均多源兜底，任一可用即成功：
 *   1) raw.githubusercontent.com 直连（实时、无 CDN 缓存）
 *   2) api.github.com contents API（Accept: application/vnd.github.raw）
 *   3) jsdelivr CDN（国内快，但发布后可能有短时缓存延迟）
 * 工作流（.github/workflows/build-apk.yml）每次构建自动发布最新清单与 APK 到仓库 main。
 */
public class Updater {

    private static final String TAG = "DeepSeekVoiceUpdater";
    private static final String REPO = "zhangshifa/deepseek_desktop";

    /**
     * 内嵌只读令牌：fine-grained、仅本仓库 Contents: Read-only。
     * 私有仓库 App 端必须带令牌才能读更新源（api.github.com）。
     * ⚠️ APK 可反编译提取，仅限只读令牌；泄漏风险仅限读取该仓库文件。
     * 留空则走免令牌路径（仓库公开时可用）。
     */
    private static final String TOKEN = "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";

    // api.github.com 放第一（私有仓库带令牌实时可读）；jsdelivr/raw 兜底（公开仓库时可用）
    private static final String[] MANIFEST_URLS = {
            "https://api.github.com/repos/" + REPO + "/contents/apk/latest.json?ref=main",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/apk/latest.json",
            "https://raw.githubusercontent.com/" + REPO + "/main/apk/latest.json"
    };
    private static final String[] APK_URLS = {
            "https://api.github.com/repos/" + REPO + "/contents/apk/app-debug.apk?ref=main",
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/apk/app-debug.apk",
            "https://raw.githubusercontent.com/" + REPO + "/main/apk/app-debug.apk"
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int localVersionCode;
    private final String localVersionName;
    private final boolean silent;   // true=启动静默：仅发现新版本才提示

    public Updater(Context context, int localVersionCode, String localVersionName, boolean silent) {
        this.context = context;
        this.localVersionCode = localVersionCode;
        this.localVersionName = localVersionName;
        this.silent = silent;
    }

    /** 异步检查更新。 */
    public void check() {
        new Thread(() -> {
            int remoteCode = -1;
            String remoteName = "", notes = "";
            for (String url : MANIFEST_URLS) {
                try {
                    JSONObject jo = new JSONObject(httpGet(url));
                    int code = jo.optInt("version_code", 0);
                    if (code > 0) {
                        remoteCode = code;
                        remoteName = jo.optString("version_name", "");
                        notes = jo.optString("notes", "");
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            final int rc = remoteCode;
            final String rn = remoteName, nt = notes;
            main.post(() -> {
                if (rc < 0) {
                    if (!silent) toast("检查更新失败，请稍后重试");
                    return;
                }
                if (rc > localVersionCode) {
                    promptUpdate(rn, nt);
                } else if (!silent) {
                    toast("已是最新版本 v" + rn);
                }
            });
        }).start();
    }

    private void promptUpdate(String versionName, String notes) {
        new AlertDialog.Builder(context)
                .setTitle("发现新版本 v" + versionName)
                .setMessage("当前版本 v" + localVersionName + " → 新版本 v" + versionName
                        + (notes == null || notes.isEmpty() ? "。\n\n是否立即下载更新？" : "。\n\n" + notes))
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(versionName))
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void downloadAndInstall(String versionName) {
        toast("正在下载 v" + versionName + "…");
        final File apk = new File(context.getCacheDir(), "app-update.apk");
        new Thread(() -> {
            File ok = null;
            for (String url : APK_URLS) {
                try {
                    httpGetToFile(url, apk);
                    if (apk.exists() && apk.length() > 100_000) { ok = apk; break; }
                } catch (Exception ignored) {
                }
            }
            final File downloaded = ok;
            main.post(() -> {
                if (downloaded == null) {
                    toast("下载 v" + versionName + " 失败，请检查网络后重试");
                } else {
                    toast("v" + versionName + " 下载完成，开始安装…");
                    install(downloaded);
                }
            });
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
            toast("无法打开安装器，请在设置中允许安装未知应用");
        }
    }

    private void toast(String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
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

    private void httpGetToFile(String url, File out) throws Exception {
        HttpURLConnection c = open(url);
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

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (TOKEN != null && !TOKEN.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        }
        c.setRequestProperty("Accept", "application/vnd.github.raw");
        c.setRequestProperty("User-Agent", "DeepSeekVoice-Android/1.0");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        return c;
    }
}
