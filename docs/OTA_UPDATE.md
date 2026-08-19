# Android APK 在线 OTA 升级方案（DeepSeekVoice 实践）

> 用途：WebView/原生 Android App 内置"检测新版本 → 下载 APK → 系统安装器升级"的完整闭环，
> 更新源托管在 GitHub 仓库（公开或私有均可），发布由 GitHub Actions 自动完成。
> 本仓库为参考实现，可直接照抄复用。

---

## 1. 架构总览

```
GitHub Actions (build-apk.yml)
   │ ① 编译 APK（带版本号命名 DSV-x.y.z-debug.apk）
   │ ② 写版本清单 apk/latest.json（version_code / version_name / download_url / notes）
   │ ③ 推送 apk/ 目录回 main（发布名固定 app-debug.apk，供 App 稳定拉取）
   ▼
GitHub 仓库 apk/（更新源）
   ├── app-debug.apk      ← 最新 APK（文件名保持稳定）
   └── latest.json        ← 版本清单（App 检查更新读它）
        ▲
App 端（Updater.java + overlay.js）
   启动静默检查 / 手动点"检查更新"
   → 读 latest.json → 比较 version_code
   → 有新版：弹窗 → 下载 APK → FileProvider 拉起系统安装器
```

## 2. 更新源清单格式（apk/latest.json）

```json
{
  "version_code": 13,
  "version_name": "1.1.0",
  "notes": "DeepSeekVoice 在线更新包 v1.1.0（GitHub Actions 自动发布）",
  "download_url": "https://raw.githubusercontent.com/OWNER/REPO/main/apk/app-debug.apk",
  "apk": "apk/app-debug.apk",
  "updated_at": "2026-08-19T07:35:09Z"
}
```

## 3. 服务端：GitHub Actions 自动发布（build-apk.yml 关键段）

```yaml
permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: 17 }
      - uses: android-actions/setup-android@v3
      - run: |
          yes | sdkmanager --licenses >/dev/null || true
          sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"
      - name: Build debug APK
        uses: gradle/gradle-build-action@v3
        with:
          gradle-version: '8.5'
          build-root-directory: capacitor/android
          arguments: ':app:assembleDebug --no-daemon'
      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        if: success()
        with: { name: app-debug-apk, path: capacitor/android/app/build/outputs/apk/debug/*.apk }
      # 在线更新源：APK + 版本清单推回 main（只动 apk/ 目录，不在 push paths 里，不会递归触发构建）
      - name: Publish update manifest to repo
        if: success()
        run: |
          set -e
          VC=$(sed -n "s/.*versionCode[[:space:]]*\([0-9]*\).*/\1/p" capacitor/android/app/build.gradle | head -1)
          VN=$(sed -n "s/.*versionName[[:space:]]*'\([^']*\)'.*/\1/p" capacitor/android/app/build.gradle | head -1)
          mkdir -p apk
          cp capacitor/android/app/build/outputs/apk/debug/*.apk apk/app-debug.apk
          cat > apk/latest.json <<EOF
          {
            "version_code": ${VC},
            "version_name": "${VN}",
            "notes": "DeepSeekVoice 在线更新包 v${VN}（GitHub Actions 自动发布）",
            "download_url": "https://raw.githubusercontent.com/OWNER/REPO/main/apk/app-debug.apk",
            "apk": "apk/app-debug.apk",
            "updated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
          }
          EOF
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add -f apk/
          git commit -m "chore: 发布更新包 v${VN} (code ${VC})" || echo "no change to commit"
          git push origin HEAD:main
```

注意：workflow 触发条件 `paths:` 不要包含 `apk/**`，否则发布动作会递归触发构建。

## 4. 客户端：Updater.java（完整实现）

```java
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
 * 在线版本检测与升级。
 * 多源兜底：1) api.github.com contents（私有仓库须带 TOKEN，公开仓库免令牌）
 *          2) jsdelivr CDN（国内快，发布后短时缓存延迟） 3) raw.githubusercontent.com
 * 版本比较用 version_code（单调递增），升级流程：弹窗 → 下载 → FileProvider 安装。
 */
public class Updater {

    private static final String TAG = "DeepSeekVoiceUpdater";
    private static final String REPO = "OWNER/REPO";

    /** 内嵌只读令牌：fine-grained、仅本仓库 Contents: Read-only；公开仓库可留空。 */
    private static final String TOKEN = "";

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
    private final boolean silent;   // true=启动静默：仅发现新版本才提示

    public Updater(Context context, int localVersionCode, boolean silent) {
        this.context = context;
        this.localVersionCode = localVersionCode;
        this.silent = silent;
    }

    public void check() {
        new Thread(() -> {
            int remoteCode = -1;
            String remoteName = "", notes = "";
            for (String url : MANIFEST_URLS) {
                try {
                    JSONObject jo = new JSONObject(httpGet(url));
                    int code = jo.optInt("version_code", 0);
                    if (code > 0) { remoteCode = code; remoteName = jo.optString("version_name", ""); notes = jo.optString("notes", ""); break; }
                } catch (Exception ignored) { }
            }
            final int rc = remoteCode; final String rn = remoteName, nt = notes;
            main.post(() -> {
                if (rc < 0) { if (!silent) toast("检查更新失败，请稍后重试"); return; }
                if (rc > localVersionCode) promptUpdate(rn, nt);
                else if (!silent) toast("已是最新版本 v" + rn);
            });
        }).start();
    }

    private void promptUpdate(String versionName, String notes) {
        new AlertDialog.Builder(context)
                .setTitle("发现新版本 v" + versionName)
                .setMessage(notes == null || notes.isEmpty() ? "是否立即下载更新？" : notes)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall())
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void downloadAndInstall() {
        toast("正在下载新版本…");
        final File apk = new File(context.getCacheDir(), "app-update.apk");
        new Thread(() -> {
            File ok = null;
            for (String url : APK_URLS) {
                try { httpGetToFile(url, apk); if (apk.exists() && apk.length() > 100_000) { ok = apk; break; } }
                catch (Exception ignored) { }
            }
            final File downloaded = ok;
            main.post(() -> {
                if (downloaded == null) toast("下载失败，请检查网络后重试");
                else { toast("下载完成，开始安装…"); install(downloaded); }
            });
        }).start();
    }

    private void install(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
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

    private void toast(String msg) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close(); return sb.toString();
        } finally { c.disconnect(); }
    }

    private void httpGetToFile(String url, File out) throws Exception {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close(); in.close();
        } finally { c.disconnect(); }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (TOKEN != null && !TOKEN.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        c.setRequestProperty("Accept", "application/vnd.github.raw");
        c.setRequestProperty("User-Agent", "App-OTA/1.0");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        return c;
    }
}
```

## 5. 客户端集成点

### 5.1 AndroidManifest.xml（FileProvider + 安装权限）

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<!-- application 内：把缓存目录 APK 授权给系统安装器 -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### 5.2 res/xml/file_paths.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apk_cache" path="updates/" />
    <external-files-path name="apk_files" path="updates/" />
</paths>
```

### 5.3 MainActivity（启动静默 + 手动协议）

```java
// 启动静默检查（仅发现新版本才弹窗）
new Updater(this, BuildConfig.VERSION_CODE, true).check();

// WebChromeClient.onJsPrompt 拦截（网页 overlay 点"检查更新"）
if ("__DS_CHECK_UPDATE__".equals(message)) {
    new Updater(MainActivity.this, BuildConfig.VERSION_CODE, false).check();
    result.confirm("");
    return true;
}
```

> ⚠️ AGP 8+ 默认不生成 BuildConfig：必须在 build.gradle 加
> `buildFeatures { buildConfig true }`，否则 `BuildConfig.VERSION_CODE` 编译报 cannot find symbol。

### 5.4 overlay.js（网页端"检查更新"按钮 → prompt 协议）

```js
// ⋯ 关于面板内
document.getElementById('dsAboutUpd').onclick = function () {
    try { prompt('__DS_CHECK_UPDATE__'); status.textContent = '已触发在线检查，请留意提示'; }
    catch (e) { status.textContent = '检查更新不可用'; }
};
```

## 6. 踩坑记录（务必注意）

| 坑 | 现象 | 解决 |
|---|---|---|
| **私有仓库无令牌** | 检查更新一直失败；api/raw/jsdelivr 全 404（GitHub 对私有仓库 contents 统一返回 404 隐藏存在性） | api.github.com 源带 `Authorization: Bearer <只读令牌>`；或把仓库改公开免令牌 |
| **内嵌令牌权限过大** | APK 可反编译提取令牌 | 只用 fine-grained **Contents: Read-only、仅限本仓库**；用完可撤销，泄露风险仅限读文件 |
| **AGP 8 BuildConfig 缺失** | `cannot find symbol: BuildConfig` | `buildFeatures { buildConfig true }` |
| **workflow 递归触发** | 发布 apk/ 又触发构建，无限循环 | 构建触发的 `paths:` 排除 `apk/**` |
| **jsdelivr 缓存延迟** | 刚发布几分钟内 CDN 返回 404/旧版 | 只作兜底源，主源用 api.github.com（实时） |
| **下载超时过长** | 单源 15s 超时 × 3 源 = 等很久 | 连接 10s / 读取 30s，api 源优先通常秒回 |

## 7. 复用步骤（新项目照做）

1. 复制 `Updater.java`，改 `REPO`、`TOKEN`（公开仓库留空）
2. 复制 FileProvider + file_paths.xml + REQUEST_INSTALL_PACKAGES
3. `build.gradle` 开 `buildConfig true`，APK 命名 `DSV-${versionName}-${buildType.name}.apk`
4. MainActivity 加启动静默检查 + `__DS_CHECK_UPDATE__` 协议
5. 复制 workflow 的"Publish update manifest"步骤（改仓库地址）
6. 构建 → 线上 `apk/latest.json` 自动生成 → App 即可在线升级
