"""
tts.py — 中文语音播报（edge-tts）
用微软在线 TTS（中文 XiaoxiaoNeural），音质自然、国内可用、免费。
🔊 开关控制是否播报；stop() 可随时打断。
若网络不通，speak() 会静默失败（仅打印），不影响其它功能。
"""
import asyncio
import os
import tempfile
import threading

import edge_tts
from PySide6.QtCore import QObject, Signal

# 中文自然女声；可改为 zh-CN-YunxiNeural(男) / zh-CN-XiaoyiNeural 等
VOICE = "zh-CN-XiaoxiaoNeural"


class TTS(QObject):
    finished = Signal()
    error = Signal(str)

    def __init__(self):
        super().__init__()
        try:
            import pygame
            pygame.mixer.init()
            self._pg = pygame
            self._ok = True
        except Exception as e:
            self._ok = False
            self.error.emit(f"音频播放初始化失败（将只静音）：{e}")

    def speak(self, text: str):
        text = (text or "").strip()
        if not text:
            return
        if not self._ok:
            return
        # 轻量去除 Markdown 标记，让朗读更自然
        clean = self._strip_markdown(text)
        if not clean:
            return
        threading.Thread(target=self._speak_sync, args=(clean,), daemon=True).start()

    def _speak_sync(self, text: str):
        try:
            asyncio.run(self._stream_and_play(text))
        except Exception as e:
            self.error.emit(f"播报失败：{e}")
        finally:
            self.finished.emit()

    async def _stream_and_play(self, text: str):
        tmp = tempfile.mktemp(suffix=".mp3")
        try:
            communicate = edge_tts.Communicate(text, VOICE)
            await communicate.stream_to_file(tmp)
            self._pg.mixer.music.load(tmp)
            self._pg.mixer.music.play()
            # 阻塞直至播完（在 worker 线程内，不卡 UI）
            while self._pg.mixer.music.get_busy():
                await asyncio.sleep(0.1)
            self._pg.mixer.music.unload()
        finally:
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass

    def stop(self):
        if self._ok:
            try:
                self._pg.mixer.music.stop()
            except Exception:
                pass

    @staticmethod
    def _strip_markdown(text: str) -> str:
        import re
        t = text
        t = re.sub(r"```[\s\S]*?```", " ", t)        # 代码块
        t = re.sub(r"`([^`]+)`", r"\1", t)            # 行内代码
        t = re.sub(r"[#*_>~`|]", " ", t)              # 常见标记符号
        t = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", t)   # 图片
        t = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", t)  # 链接
        t = re.sub(r"\s+", " ", t)
        return t.strip()
