# DeepSeekVoice（Capacitor 网页打包版 / 非原生 UI）

把 `chat.deepseek.com` 装进一个 Android App，**账号密码登录，免 API Key**。
界面用 **Web 技术**（HTML/CSS/JS）写的浮动语音工具条，打包成 APK，类似「闪光点」这类网页壳 App。

## 功能
- 🌐 WebView 承载 DeepSeek 网页：网页返回什么，App 就显示什么（完全复用网页界面与登录）。
- 🎤 **语音输入**：点麦克风说话 → 识别成文字 → 自动填进 DeepSeek 输入框（顶部「输入」开关控制）。
- 🔊 **语音播报**：AI 回复自动用系统中文 TTS 朗读（顶部「播报」开关控制，防抖只读最终完整文本）。
- 🔐 麦克风权限运行时申请，可在系统设置里关闭（关后语音输入自动禁用）。

## 目录结构
```
capacitor/
├── package.json / capacitor.config.ts   # Capacitor 工程配置
├── www/index.html                        # 占位页（原生加载后会跳到 DeepSeek）
└── android/                             # Android 工程（由 Capacitor 生成/手动维护）
    └── app/src/main/
        ├── java/com/example/deepseekvoice/
        │   ├── MainActivity.java         # 承载网页 + 注入 Web 语音层
        │   └── VoiceBridge.java          # 原生语音桥接（SpeechRecognizer/TTS）
        ├── assets/overlay.js             # ★ 界面本体：浮动工具条 + 开关（Web 写的）
        └── res/ AndroidManifest.xml ...
```

## 本机构建 APK（必须，沙箱无法编译）
1. 安装 **Android Studio**（自带 SDK + Gradle）。
2. 打开 `capacitor/android/` 目录（File → Open）。
3. 首次打开若缺 `gradle-wrapper.jar`：在该目录命令行执行
   ```
   gradle wrapper --gradle-version 8.5
   ```
   （需本机已装 Gradle；或让 Android Studio 自动下载）
4. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
5. 编译完成后在 `capacitor/android/app/build/outputs/apk/debug/app-debug.apk` 拿到安装包。
6. 手机开启「未知来源安装」，传过去装上即可。

## 运行
- 打开 App → 进入 DeepSeek 登录页 → 用**网页账号密码**登录。
- 右上角出现浮动工具条：🎤 麦克风、输入开关、播报开关。
- 首次用麦克风会弹权限请求，允许即可。

## 已知限制
- **沙箱无 Android SDK，未实际编译**，代码按 Capacitor 6 / Android 14 规范编写，需你本机验证。
- **overlay.js 是启发式抓取**：靠 `message/reply/assistant/bubble` 等类名匹配回复，DeepSeek 网页改版可能要改 `assets/overlay.js` 里的选择器。
- **语音输入**依赖 Android 原生 `SpeechRecognizer`（需联网、系统有语音引擎）；语音播报用系统 TTS。
- 这是「网页增强层」，不是官方接口；如不朗读/不填字，关掉对应开关手动操作即可。

## 与 `android/`（Kotlin 原生版）的区别
- `android/`：纯 Kotlin 原生实现。
- `capacitor/`：**界面用 Web 技术写（overlay.js）**，由 Capacitor 打包，更符合「非原生 / 类似闪光点 app」的需求，后续改界面直接改 `overlay.js`。
