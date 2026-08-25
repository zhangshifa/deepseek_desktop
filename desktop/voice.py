"""
voice.py — 离线中文语音识别（Vosk）
- 话筒音频由 sounddevice 直接采集（不经过浏览器，无需给网页授权麦克风）
- 用 Vosk 流式识别（KaldiRecognizer），其内置端点检测自动切句
- 完全离线，不联网、不上传录音；模型需放在 models/ 目录下
  （首次会自动从 alphacephei.com 下载 vosk-model-small-cn-0.22，约 44MB）
"""
import os
import queue
import threading
import json

import numpy as np
from PySide6.QtCore import QObject, Signal

import sounddevice as sd
from vosk import Model, KaldiRecognizer

SAMPLE_RATE = 16000
# 中文小模型：识别率够用、体积小（~44MB）。如需更高精度可换 vosk-model-cn-0.22
MODEL_VERSION = "vosk-model-small-cn-0.22"
MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

# 端点/超时参数（Vosk 内置端点为主，以下为兜底与提示）
SILENCE_SEC = 1.2       # 静音持续这么久 -> 认为一句话结束（兜底）
MAX_SEC = 25.0          # 单句最长，超过强制结束
RMS_SPEECH = 300.0      # int16 能量阈值（高于=在说话），仅用于“聆听中”提示


def _app_models_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")


class VoiceRecognizer(QObject):
    partial = Signal(str)   # 实时中间结果 / 状态提示（如“聆听中…”）
    final = Signal(str)     # 整句识别结果
    error = Signal(str)     # 错误
    state = Signal(str)     # 状态：listening / stopped / loading
    progress = Signal(str)  # 加载/下载进度提示

    def __init__(self):
        super().__init__()
        self.model = None
        self.recognizer = None
        self.stream = None
        self._queue = queue.Queue(maxsize=600)
        self._running = False
        self._thread = None

    # ---------- 模型定位 / 加载 ----------
    def _find_model_dir(self):
        # 1) 环境变量优先
        envp = os.environ.get("VOSK_MODEL_PATH")
        if envp and os.path.isdir(envp):
            return envp
        # 2) models/ 下任意 vosk-model-* 目录
        mdir = _app_models_dir()
        if os.path.isdir(mdir):
            for name in sorted(os.listdir(mdir)):
                if name.startswith("vosk-model") and os.path.isdir(os.path.join(mdir, name)):
                    return os.path.join(mdir, name)
        return None

    def _auto_download(self):
        """首次缺失时自动下载并解压模型，返回解压后的目录路径。"""
        import zipfile
        import urllib.request
        mdir = _app_models_dir()
        os.makedirs(mdir, exist_ok=True)
        zip_path = os.path.join(mdir, MODEL_VERSION + ".zip")
        try:
            if not os.path.exists(zip_path) or os.path.getsize(zip_path) < 1_000_000:
                self.progress.emit("正在下载 Vosk 中文模型（约 44MB）…")
                urllib.request.urlretrieve(MODEL_ZIP_URL, zip_path)
            extract_dir = os.path.join(mdir, MODEL_VERSION)
            if not os.path.isdir(extract_dir):
                self.progress.emit("正在解压模型…")
                with zipfile.ZipFile(zip_path) as z:
                    z.extractall(mdir)
            return extract_dir if os.path.isdir(extract_dir) else None
        except Exception as e:
            self.error.emit(f"模型自动下载失败（可手动下载放到 models/）：{e}")
            return None

    def _ensure_model(self):
        if self.model is not None:
            return True
        try:
            self.state.emit("loading")
            mdir = self._find_model_dir()
            if mdir is None:
                mdir = self._auto_download()
            if not mdir or not os.path.isdir(mdir):
                self.error.emit("未找到 Vosk 中文模型，请将模型解压到 models/ 目录")
                return False
            self.model = Model(mdir)
            self.recognizer = KaldiRecognizer(self.model, SAMPLE_RATE)
            self.recognizer.SetWords(False)
            return True
        except Exception as e:
            self.error.emit(f"语音模型加载失败：{e}")
            return False

    # ---------- 麦克风回调 ----------
    def _audio_callback(self, indata, frames, time_info, status):
        if self._running:
            try:
                self._queue.put(indata.copy(), timeout=0.2)
            except Exception:
                pass

    @staticmethod
    def _pick_input_device():
        """优先选名字含 麦克风/Microphone/Mic 的输入设备，否则用系统默认。"""
        try:
            devs = sd.query_devices()
            keys = ("mic", "microphone", "麦克风", "话筒", "语音", "收音")
            for i, d in enumerate(devs):
                if int(d.get("max_input_channels", 0)) > 0:
                    name = (d.get("name") or "").lower()
                    if any(k in name for k in keys):
                        return i
            return None  # None -> sounddevice 用默认输入
        except Exception:
            return None

    # ---------- 主循环：采集 + 流式识别 + 切句 ----------
    def _loop(self):
        rec = self.recognizer
        speaking = False
        silent_samples = 0
        total_samples = 0
        try:
            while self._running:
                try:
                    chunk = self._queue.get(timeout=0.5)
                except queue.Empty:
                    # 持续静音：若之前在说话，兜底结束句子
                    if speaking:
                        silent_samples += int(0.5 * SAMPLE_RATE)
                        if silent_samples >= int(SILENCE_SEC * SAMPLE_RATE):
                            self._flush(rec)
                            speaking = False
                            silent_samples = 0
                    continue

                arr = chunk[:, 0] if chunk.ndim > 1 else chunk
                arr = arr.astype(np.int16)
                data = arr.tobytes()
                total_samples += len(arr)

                rms = float(np.sqrt(np.mean(arr.astype(np.float32) ** 2)))
                if rms > RMS_SPEECH:
                    if not speaking:
                        speaking = True
                        self.partial.emit("聆听中…")
                    silent_samples = 0
                else:
                    silent_samples += len(arr)

                # Vosk 内置端点：返回 True 表示一句结束
                if rec.AcceptWaveform(data):
                    res = json.loads(rec.Result())
                    text = (res.get("text", "") or "").strip()
                    if text:
                        self.final.emit(text)
                    speaking = False
                    silent_samples = 0
                else:
                    part = json.loads(rec.PartialResult()).get("text", "")
                    part = (part or "").strip()
                    if part:
                        self.partial.emit(part)

                # 单句超长强制结束
                if total_samples >= int(MAX_SEC * SAMPLE_RATE):
                    self._flush(rec)
                    total_samples = 0
                    speaking = False
                    silent_samples = 0
        except Exception as e:
            self.error.emit(f"识别线程异常：{e}")
        finally:
            try:
                self._flush(rec)
            except Exception:
                pass
            self.state.emit("stopped")

    def _flush(self, rec):
        """把当前已识别但未结束的内容作为最终结果抛出（切句兜底）。"""
        try:
            res = json.loads(rec.FinalResult())
            text = (res.get("text", "") or "").strip()
            if text:
                self.final.emit(text)
        except Exception:
            pass

    # ---------- 对外接口 ----------
    def start(self):
        if self._running:
            return
        if not self._ensure_model():
            return
        try:
            device = self._pick_input_device()
            self.stream = sd.InputStream(
                samplerate=SAMPLE_RATE,
                blocksize=4000,
                dtype="int16",
                channels=1,
                callback=self._audio_callback,
                device=device,
            )
            self.stream.start()
        except Exception as e:
            self.error.emit(f"无法打开麦克风：{e}")
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        self.state.emit("listening")

    def stop(self):
        self._running = False
        if self.stream is not None:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
            self.stream = None
        self.state.emit("stopped")

    @property
    def is_running(self):
        return self._running

    @staticmethod
    def available() -> bool:
        try:
            import vosk  # noqa
            return True
        except Exception:
            return False
