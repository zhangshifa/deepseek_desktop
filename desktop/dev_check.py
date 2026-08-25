"""
dev_check.py — 语音链路独立自检（无需 GUI）
用法：D:/ds_venv/Scripts/python.exe dev_check.py
会加载 Vosk 中文模型（缺失时首次自动下载），然后监听麦克风，
说出中文后会把识别结果打印出来。用于验证“话筒 -> 离线识别”是否可用。
Ctrl+C 退出。
"""
import sys
from PySide6.QtCore import QCoreApplication
from voice import VoiceRecognizer


def main():
    app = QCoreApplication(sys.argv)
    v = VoiceRecognizer()
    v.partial.connect(lambda s: print(f"[状态] {s}", flush=True))
    v.final.connect(lambda s: print(f"[识别] {s}", flush=True))
    v.error.connect(lambda s: print(f"[错误] {s}", flush=True))
    v.state.connect(lambda s: print(f"[state] {s}", flush=True))

    print("正在启动语音监听（首次会下载模型，请稍候）…说句中文试试，Ctrl+C 退出。")
    v.start()
    try:
        sys.exit(app.exec())
    except KeyboardInterrupt:
        v.stop()


if __name__ == "__main__":
    main()
