# DeepSeekVoice（Android WebView 语音版 / 非原生 UI）

把 `chat.deepseek.com` 装进一个 Android App，**账号密码登录，免 API Key**。
界面用 **Web 技术**（HTML/CSS/JS）写的浮动语音工具条，由原生 WebView 承载，打包成 APK，类似「闪光点」这类网页壳 App。

> 说明：本工程最初按 Capacitor 规划，但因 Capacitor 的 npm 构建链在云编译环境缺运行时依赖，
> 已改为**标准 AndroidX WebView 工程**（`MainActivity extends AppCompatActivity`）。
> 运行效果与「非原生网页壳」完全一致，且用标准 Android Gradle 就能稳定编译出 APK。
> 界面仍是 Web 技术写的 `assets/overlay.js`，改 UI 只动这一个文件。

## 功能
- 🌐 WebView 承载 DeepSeek 网页：网页返回什么，App 就显示什么（完全复用网页界面与登录）。
- 🎤 **语音输入**：点麦克风说话 → 识别成文字 → 自动填进 DeepSeek 输入框（顶部「输入」开关控制）。
- 🔊 **语音播报**：AI 回复自动用系统中文 TTS 朗读（顶部「播报」开关控制，防抖只读最终完整文本）。
- 🔐 麦克风权限运行时申请，可在系统设置里关闭（关后语音输入自动禁用）。

## 目录结构
```
capacitor/
├── www/index.html                        # 占位页（原生加载后会跳到 DeepSeek）
└── android/                             # 标准 Android Gradle 工程（不再依赖 Capacitor CLI）
    └── app/src/main/
        ├── java/com/example/deepseekvoice/
        │   ├── MainActivity.java         # 承载网页 + 注入 Web 语音层（extends AppCompatActivity）
        │   └── VoiceBridge.java          # 原生语音桥接（SpeechRecognizer/TTS）暴露给 JS
        ├── assets/overlay.js             # ★ 界面本体：浮动工具条 + 开关（Web 写的）
        ├── res/layout/activity_main.xml  # WebView 布局
        └── AndroidManifest.xml
```

## 本机构建 APK（方式一：Android Studio）
1. 安装 **Android Studio**（自带 SDK + Gradle）。
2. 打开 `capacitor/android/` 目录（File → Open）。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 在 `capacitor/android/app/build/outputs/apk/debug/app-debug.apk` 拿到安装包。
5. 手机开启「未知来源安装」，传过去装上即可。

## 云端构建 APK（方式二：GitHub Actions，无需本机装 SDK）
仓库已内置 `.github/workflows/build-apk.yml`，推送 `capacitor/android/**` 改动会**自动触发云编译**，
或到 GitHub → Actions → 选 "Build APK (Capacitor)" → **Run workflow** 手动触发。
编译完成后自动发布到 Releases，下载 `app-debug.apk` 即可。

## 运行
- 打开 App → 进入 DeepSeek 登录页 → 用**网页账号密码**登录。
- 右上角出现浮动工具条：🎤 麦克风、输入开关、播报开关。
- 首次用麦克风会弹权限请求，允许即可。

## 已知限制
- **overlay.js 是启发式抓取**：靠 `message/reply/assistant/bubble` 等类名匹配回复，DeepSeek 网页改版可能要改 `assets/overlay.js` 里的选择器。
- **语音输入**依赖 Android 原生 `SpeechRecognizer`（需联网、系统有语音引擎）；语音播报用系统 TTS。
- 这是「网页增强层」，不是官方接口；如不朗读/不填字，关掉对应开关手动操作即可。

## 与 `android/`（Kotlin 原生版）的区别
- `android/`：纯 Kotlin 原生实现，界面也是原生。
- `capacitor/`：界面用 Web 技术写（`overlay.js`），改 UI 直接改该文件，更符合「非原生 / 类似闪光点 app」的需求。
