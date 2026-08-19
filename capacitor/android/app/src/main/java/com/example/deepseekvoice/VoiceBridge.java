package com.example.deepseekvoice;

import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 原生语音桥接：把 Android SpeechRecognizer / TextToSpeech 暴露给网页 overlay.js。
 *
 * 支持三种交互：
 *  1. 手动模式：overlay 点 🎤 → startRecognition()/stopRecognition()
 *  2. 免手模式（关键字唤醒）：startWake("小深") → 持续循环监听，识别文本包含唤醒词
 *     才触发输入（提示音 → 把关键字后的内容作为输入），否则继续待命。
 *  3. 播报：speak(text)，播报前由 overlay 先 playTone("out") 提示。
 *
 * 网页通过 window.VoiceBridge.* 调用。
 */
public class VoiceBridge {

    private static final int TONE_IN_MS = 180;    // 输入提示音时长
    private static final int TONE_OUT_MS = 320;   // 输出提示音时长

    private final MainActivity activity;
    private final BluetoothAudio bluetoothAudio;
    private SpeechRecognizer recognizer;
    private android.speech.tts.TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;

    // 免手模式（关键字唤醒）
    private volatile boolean wakeActive = false;
    private String wakeKeyword = "小深";

    public VoiceBridge(MainActivity activity, BluetoothAudio bluetoothAudio) {
        this.activity = activity;
        this.bluetoothAudio = bluetoothAudio;

        // 文本转语音（中文播报）
        tts = new android.speech.tts.TextToSpeech(activity, status -> {
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                // 输出路由：USAGE_MEDIA → 已连接 A2DP 蓝牙耳机自动接管播报
                tts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
        });

        // 语音识别
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(android.os.Bundle params) {
                    // 免手模式待命中不发 start（避免误点亮 mic 按钮）
                    if (!wakeActive) emit("start", "");
                }

                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { }
                public void onBufferReceived(byte[] buf) { }
                public void onEndOfSpeech() { }

                public void onError(int error) {
                    listening = false;
                    endSco();
                    if (wakeActive) {
                        // 免手模式：无匹配/超时/其他错误都继续待命监听
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, 1500);
                        } else {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, 500);
                        }
                    } else {
                        emit("error", String.valueOf(error));
                    }
                }

                public void onResults(android.os.Bundle results) {
                    listening = false;
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                    if (wakeActive) {
                        handleWakeResult(text);
                    } else {
                        if (!text.isEmpty()) emit("partial", text);
                        endSco();
                    }
                }

                public void onPartialResults(android.os.Bundle results) {
                    // 免手模式不逐字回传；手动模式才实时填充
                    if (wakeActive) return;
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        emit("partial", matches.get(0));
                    }
                }

                public void onEvent(int event, android.os.Bundle params) { }
            });
        }
    }

    // ---------- 手动模式 ----------

    @JavascriptInterface
    public void startRecognition() {
        if (recognizer == null || listening || wakeActive) return;
        listening = true;
        enableScoIfBt();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        handler.post(() -> recognizer.startListening(intent));
    }

    @JavascriptInterface
    public void stopRecognition() {
        if (recognizer != null && listening) {
            recognizer.stopListening();
            listening = false;
            endSco();
        }
    }

    // ---------- 免手模式（关键字唤醒） ----------

    /** 开启关键字唤醒监听；keyword 为空时沿用上次。 */
    @JavascriptInterface
    public void startWake(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            wakeKeyword = keyword.trim();
        }
        wakeActive = true;
        handler.post(this::wakeListenOnce);
    }

    @JavascriptInterface
    public void stopWake() {
        wakeActive = false;
        stopRecognition();
    }

    /** 免手模式待命监听一轮（识别完成后自动续接下一轮）。 */
    private void wakeListenOnce() {
        if (!wakeActive || recognizer == null || listening) return;
        listening = true;
        enableScoIfBt();
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            handler.postDelayed(this::wakeListenOnce, 1000);
        }
    }

    /** 唤醒词命中处理：提示音 + 把关键字之后的内容作为输入交给网页。 */
    private void handleWakeResult(String text) {
        endSco();
        if (!wakeActive) return;
        if (text == null || text.isEmpty()) {
            // 空结果：可能是环境音，继续待命
            handler.postDelayed(this::wakeListenOnce, 400);
            return;
        }
        int idx = text.indexOf(wakeKeyword);
        if (idx >= 0) {
            String rest = text.substring(idx + wakeKeyword.length()).trim();
            playTone("in");
            emit("wake", rest);
            // 唤醒成功后继续待命，支持连续免手对话
            handler.postDelayed(this::wakeListenOnce, 800);
        } else {
            // 没说到关键字：继续听
            handler.postDelayed(this::wakeListenOnce, 400);
        }
    }

    // ---------- 提示音 ----------

    /** 播放提示音：in=输入开始（哔），out=输出/播报前（叮咚）。 */
    @JavascriptInterface
    public void playTone(String kind) {
        handler.post(() -> {
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
                if ("in".equals(kind)) {
                    tg.startTone(ToneGenerator.TONE_PROP_ACK, TONE_IN_MS);
                } else {
                    tg.startTone(ToneGenerator.TONE_PROP_PROMPT, TONE_OUT_MS);
                }
                // 等播完再释放资源
                handler.postDelayed(tg::release, TONE_OUT_MS + 400);
            } catch (Exception ignored) {
            }
        });
    }

    // ---------- 播报 ----------

    @JavascriptInterface
    public void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        handler.post(() -> {
            tts.stop();
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
        });
    }

    // ---------- 蓝牙 ----------

    /** 蓝牙耳机（A2DP/HFP）是否已连接，供网页显示状态。 */
    @JavascriptInterface
    public boolean getBluetoothState() {
        return bluetoothAudio != null && bluetoothAudio.isHeadsetConnected();
    }

    private void enableScoIfBt() {
        if (bluetoothAudio != null && bluetoothAudio.isHeadsetConnected()) {
            bluetoothAudio.enableSco();
        }
    }

    /** 识别结束：恢复默认麦克风路由 */
    private void endSco() {
        if (bluetoothAudio != null) {
            bluetoothAudio.disableSco();
        }
    }

    // ---------- 工具 ----------

    @JavascriptInterface
    public void log(String msg) {
        android.util.Log.d("DeepSeekVoice", msg == null ? "" : msg);
    }

    /** 把识别结果以 JS 对象回传网页 */
    private void emit(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            WebView wv = activity.getWebView();
            if (wv != null) {
                String js = "window.__onSpeech&&window.__onSpeech(" + o.toString() + ");";
                wv.evaluateJavascript(js, null);
            }
        } catch (Exception ignored) {
        }
    }
}
