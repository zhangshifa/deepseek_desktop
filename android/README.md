# DeepSeek Voice — Android 语音客户端

原生 Android (Kotlin) 应用：用 **WebView 承载 `chat.deepseek.com`**，叠加**语音输入 / 语音播报**增强层。
**无需 DeepSeek API Key**——直接用你的 DeepSeek 网页账号密码登录即可访问会话（语音只是界面增强，不能绕过登录）。

## 功能
- 🌐 WebView 加载 DeepSeek 网页，登录方式与浏览器完全一致（账号密码，免 API Key）。
- 🎤 **语音输入**：点麦克风说话，识别文字自动填入网页输入框（开关控制）。
- 🔊 **语音播报**：AI 回复自动朗读（开关控制），流式回复做了防抖，仅朗读最终完整文本。
- 🎛️ 顶部悬浮条两个开关：**语音输入**、**语音播报**，可独立开关。

## 技术栈
- Kotlin + AndroidX（AppCompat / Material3 / ConstraintLayout）
- `WebView`（系统 WebView，无需额外依赖）
- `SpeechRecognizer`（系统语音识别，需 Google 语音服务）
- `TextToSpeech`（系统 TTS，默认中文）
- JS 桥接：`addJavascriptInterface` 暴露 `window.AndroidBridge`，注入脚本监听回复并填输入框

## 构建步骤（需在你本机 Android Studio 完成）
> 沙箱环境无法编译 APK，这里提供完整工程源码 + 构建说明。

1. 安装 **Android Studio**（含 Android SDK），SDK Platform **API 34**，JDK **17**。
2. 打开项目：Android Studio → Open → 选择本目录 `android/`。
3. 若缺少 `gradle/wrapper/gradle-wrapper.jar`：
   - 本目录已提供 `gradle-wrapper.properties`；
   - 在 `android/` 下执行 `gradle wrapper`（需本机已装 Gradle），或让 Android Studio 自动生成。
4. **Sync Project with Gradle Files**（等待依赖下载）。
5. 连接 Android 设备（API 24+）或启动模拟器。
6. **Build → Build APK(s)**，生成 `app/build/outputs/apk/debug/app-debug.apk`。
7. 安装到设备（`adb install` 或拖入）。

## 权限
- `INTERNET`：联网访问 DeepSeek。
- `RECORD_AUDIO`：语音输入；首次使用时运行时申请，可在系统设置中关闭（关闭后"语音输入"自动禁用）。
- TTS 无需额外权限（使用系统引擎，首次使用可能需下载中文语音包）。

## 使用
1. 打开 App，登录 DeepSeek 网页账号。
2. 顶部开关：**语音输入**(开/关)、**语音播报**(开/关)。
3. 点麦克风说话 → 文字填入输入框 → 发送。
4. AI 回复出现 → 若"语音播报"开，自动朗读。

## 已知局限
- 注入的 JS 用启发式选择器（`message/bubble/reply/answer/assistant` 类名）抓取回复，DeepSeek 网页改版可能需调整 `VoiceInjector.kt` 中的选择器。
- 语音识别/合成依赖设备自带的 Google 服务（国内设备可能需自行安装 GMS / 替换为厂商 TTS）。
- 自动朗读可能误朗读你自己的消息气泡（启发式无法 100% 区分角色），遇此可关闭"语音播报"。
