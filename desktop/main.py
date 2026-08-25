"""
main.py — DeepSeek 桌面语音助手（Windows）
- 内嵌网页 chat.deepseek.com（用你的网页账号登录，免 API key）
- 🎤 离线语音输入（Vosk）：话筒说话 -> 中文文字 -> 自动填入并发送
- 底部文本框：手动输入文字，回车/点发送
- 🔊 输出播报开关：助手回复用 edge-tts 朗读，可随时关/停

运行：双击 run.bat 或  venv/Scripts/python.exe main.py
"""
import os
import sys

from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QToolBar, QPushButton, QLabel, QLineEdit, QTextEdit, QSizePolicy,
)
from PySide6.QtCore import Qt, QSize
from PySide6.QtWebEngineWidgets import QWebEngineView

from voice import VoiceRecognizer
from tts import TTS
from web_client import WebClient

BASE = os.path.dirname(os.path.abspath(__file__))
# 语音模型（Vosk 中文小模型）放在 models/ 目录；缺失时首次运行自动下载（约 44MB）


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("DeepSeek 桌面语音助手  v1.0")
        self.resize(1100, 760)

        # ---- 中间 WebView ----
        self.web = QWebEngineView()
        self.web_client = WebClient(self.web)

        # ---- 顶部工具栏 ----
        self.toolbar = QToolBar()
        self.toolbar.setMovable(False)
        self.addToolBar(self.toolbar)

        self.mic_btn = QPushButton("🎤 语音")
        self.mic_btn.setCheckable(True)
        self.mic_btn.setToolTip("开启/关闭离线语音输入")
        self.mic_btn.clicked.connect(self.toggle_mic)

        self.tts_btn = QPushButton("🔊 播报: 开")
        self.tts_btn.setCheckable(True)
        self.tts_btn.setChecked(True)
        self.tts_btn.setToolTip("开启/关闭助手回复朗读")
        self.tts_btn.clicked.connect(self.toggle_tts)

        self.stop_btn = QPushButton("⏹ 停止朗读")
        self.stop_btn.clicked.connect(self.stop_speak)

        self.status = QLabel("就绪 · 请在网页登录 DeepSeek 账号")
        self.status.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)

        self.toolbar.addWidget(self.mic_btn)
        self.toolbar.addWidget(self.tts_btn)
        self.toolbar.addWidget(self.stop_btn)
        self.toolbar.addWidget(self.status)

        # ---- 底部输入条 ----
        bottom = QWidget()
        bl = QHBoxLayout(bottom)
        bl.setContentsMargins(8, 6, 8, 6)
        self.input = QLineEdit()
        self.input.setPlaceholderText("在这里输入文字，回车发送；或点 🎤 说话")
        self.input.returnPressed.connect(self.send_from_box)
        self.send_btn = QPushButton("发送")
        self.send_btn.clicked.connect(self.send_from_box)
        bl.addWidget(self.input, 1)
        bl.addWidget(self.send_btn)

        # ---- 布局 ----
        central = QWidget()
        vl = QVBoxLayout(central)
        vl.setContentsMargins(0, 0, 0, 0)
        vl.setSpacing(0)
        vl.addWidget(self.web, 1)
        vl.addWidget(bottom)
        self.setCentralWidget(central)

        # ---- 语音 / 播报 组件 ----
        self.voice = VoiceRecognizer()
        self.voice.partial.connect(self.on_partial)
        self.voice.final.connect(self.on_final)
        self.voice.error.connect(self.on_error)
        self.voice.state.connect(self.on_voice_state)

        self.tts = TTS()
        self.tts.error.connect(self.on_error)
        self.tts_on = True

        self.web_client.replyReady.connect(self.on_reply)
        self.web_client.pageReady.connect(lambda: self.set_status("页面已加载 · 可开始对话"))

        self.web_client.load()

    # ---------- 语音 ----------
    def toggle_mic(self, checked):
        if checked:
            self.voice.start()
        else:
            self.voice.stop()

    def on_voice_state(self, s):
        if s == "listening":
            self.set_status("🎤 聆听中…（说完会自动发送）")
        else:
            self.mic_btn.setChecked(False)
            self.set_status("语音已停止")

    def on_partial(self, text):
        self.set_status(f"识别中: {text}")

    def on_final(self, text):
        self.set_status(f"识别完成: {text}")
        # 自动填入底部框并发送
        self.input.setText(text)
        self.send_from_box()

    def on_error(self, msg):
        self.set_status(f"⚠️ {msg}")
        self.mic_btn.setChecked(False)

    # ---------- 文本输入 ----------
    def send_from_box(self):
        text = self.input.text().strip()
        if not text:
            return
        self.web_client.send_text(text)
        self.input.clear()

    # ---------- 播报 ----------
    def toggle_tts(self, checked):
        self.tts_on = checked
        self.tts_btn.setText("🔊 播报: 开" if checked else "🔇 播报: 关")

    def stop_speak(self):
        self.tts.stop()

    def on_reply(self, text):
        snippet = text[:40].replace("\n", " ")
        self.set_status(f"🤖 回复: {snippet}…")
        if self.tts_on:
            self.tts.speak(text)

    # ---------- 状态 ----------
    def set_status(self, text):
        self.status.setText(text)


def main():
    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
