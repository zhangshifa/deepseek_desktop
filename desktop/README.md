# DeepSeek 桌面语音助手（Windows）

把 chat.deepseek.com 装进桌面窗口，加上**离线语音输入**和**可开关的语音播报**。
无需 API key，用你自己的 DeepSeek 网页账号登录即可。

## 功能
- 🎤 **离线语音输入**：话筒说话 → 中文文字 → 自动填入并发送（说完即发，无需按键）
- ⌨️ **文本输入**：底部输入框可手动打字，回车或点「发送」
- 🔊 **输出播报开关**：助手回复用微软中文 TTS 朗读；可随时开/关、可「停止朗读」
- 麦克风由本程序直接采集，**不需要给网页授权麦克风**

## 运行
1. 双击 `run.bat`（首次会自动建虚拟环境并安装依赖，约几分钟）
2. 在打开的窗口里登录你的 DeepSeek 账号
3. 点 🎤 开始说话；点 🔊 控制是否朗读回复

> 语音模型（FunASR paraformer-zh）首次点 🎤 时自动从 ModelScope 下载（约 200MB，仅一次）。

## 目录
- `main.py`        界面与逻辑（PySide6 + QWebEngineView）
- `voice.py`       离线语音识别（FunASR + sounddevice）
- `tts.py`          中文语音播报（edge-tts）
- `web_client.py`   控制网页：填字 / 发送 / 提取最新回复
- `requirements.txt` 依赖
- `run.bat`         一键启动

## 说明
- 语音识别与播报均走本地/国内可达服务，**不上传你的录音**。
- 若企业网络屏蔽 ModelScope，首次模型下载可能失败：可手动从 ModelScope 下载
  `iic/speech_paraformer-large_asr_nat_zh-cn-16k-common-vocab8404-pytorch` 等并放置到 FunASR 缓存目录。
- 网页结构若变动，可能导致「自动发送 / 提取回复」失效，届时需要微调 `web_client.py` 里的选择器。
