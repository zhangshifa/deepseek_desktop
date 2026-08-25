"""
test_headless.py — 无界面校验语音链路（无需显示器/麦克风）
用法：D:/ds_venv/Scripts/python.exe test_headless.py
校验项：
  1) Vosk 模型目录能被自动定位
  2) Model + KaldiRecognizer 能正常加载
  3) 向识别器喂入音频字节不报错，且能解析结果
（真正的"说话识别"需在用户机器上用真实麦克风测试）
"""
import os
import sys
import numpy as np

# 避免创建任何窗口（无界面环境）
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication
import voice


def main():
    app = QCoreApplication(sys.argv)
    v = voice.VoiceRecognizer()

    # 1) 模型目录定位
    mdir = v._find_model_dir()
    print(f"[1] 模型目录: {mdir}")
    assert mdir and os.path.isdir(mdir), "未找到 Vosk 模型目录"

    # 2) 加载模型 + 构造识别器
    ok = v._ensure_model()
    print(f"[2] 模型加载: {'OK' if ok else 'FAIL'}")
    assert ok, "模型加载失败"
    assert v.recognizer is not None, "识别器未创建"

    # 3) 喂入 1 秒静音（int16 16k 单声道），确认不崩溃且能解析结果
    rec = v.recognizer
    silent = np.zeros(SAMPLE := 16000, dtype=np.int16).tobytes()
    for _ in range(3):
        rec.AcceptWaveform(silent)
    res = rec.FinalResult()
    import json
    parsed = json.loads(res)
    print(f"[3] 静音识别结果(应为空): {parsed!r}")

    # 4) 设备列表（确认话筒可被枚举）
    import sounddevice as sd
    devs = sd.query_devices()
    ins = [d for d in devs if int(d.get("max_input_channels", 0)) > 0]
    print(f"[4] 可用输入设备数: {len(ins)}")
    for d in ins:
        print(f"    - {d['name']} (idx={d['index']})")

    print("\n✅ 语音链路无界面校验通过（模型加载/识别器/音频接口均正常）。")
    print("   真实说话识别请在用户机器上运行 dev_check.py 或主程序。")


if __name__ == "__main__":
    main()
