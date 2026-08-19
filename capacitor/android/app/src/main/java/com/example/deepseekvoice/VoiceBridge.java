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
 *  2. 免手模式（关键字唤醒）：startWake("小深") → 循环监听，但**避免随意采集**：
 *     唤醒词必须在开头（"小深，xxx"）才触发输入（提示音 → 关键字后的内容作为输入）；
 *     无唤醒词/识别无语音时进入 3 秒安静期（期间不采集），每次录音窗口也尽量短。
 *  3. 播报：speak(text)，播报前由 overlay 先 playTone("out") 提示。
 *
 * 网页通过 window.VoiceBridge.* 调用。
 */
public class VoiceBridge {

    private static final int TONE_IN_MS = 180;    // 输入提示音时长
    private static final int TONE_OUT_MS = 320;   // 输出提示音时长

    // 免手模式监听间隔（毫秒）——避免随意采集周围声音：
    // 唤醒命中后快速续听；无唤醒词/无语音时进入较长安静期，期间不录音不上传。
    private static final long WAKE_INTERVAL_HIT = 800;    // 唤醒后：可马上说下一句
    private static final long WAKE_INTERVAL_IDLE = 3000;  // 无唤醒词：安静期（采集频率降 73%+）
    private static final long WAKE_INTERVAL_ERROR = 3000; // 无语音/识别错误：安静期
    private static final long WAKE_INTERVAL_BUSY = 1500;  // 识别器忙：稍等重试

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
                        // 免手模式：无语音/超时/其他错误 → 安静期后继续待命（减少采集）
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, WAKE_INTERVAL_BUSY);
                        } else {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, WAKE_INTERVAL_ERROR);
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

    /** 免手模式待命监听一轮（识别完成后按节流策略续接下一轮）。 */
    private void wakeListenOnce() {
        if (!wakeActive || recognizer == null || listening) return;
        listening = true;
        enableScoIfBt();
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            // 缩短每次录音窗口：没听到话尽早结束，减少周围声音被采集的时间
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            handler.postDelayed(this::wakeListenOnce, WAKE_INTERVAL_IDLE);
        }
    }

    /** 唤醒词命中处理：提示音 + 把关键字之后的内容作为输入交给网页。 */
    private void handleWakeResult(String text) {
        endSco();
        if (!wakeActive) return;
        if (text == null || text.isEmpty()) {
            // 空结果（只有环境音）：安静期后继续待命，不当作输入
            handler.postDelayed(this::wakeListenOnce, WAKE_INTERVAL_IDLE);
            return;
        }
        // 唤醒词必须在开头（"小深，xxx"），避免周围说话声被当成输入
        String rest = stripWakeKeyword(text.trim(), wakeKeyword);
        if (rest != null) {
            playTone("in");
            emit("wake", rest);
            // 唤醒成功后快速续听，支持连续免手对话
            handler.postDelayed(this::wakeListenOnce, WAKE_INTERVAL_HIT);
        } else {
            // 没以唤醒词开头：这是"周围的声音"，不采集不处理，进入安静期
            handler.postDelayed(this::wakeListenOnce, WAKE_INTERVAL_IDLE);
        }
    }

    /** 若文本以唤醒词开头（允许常见分隔符），返回关键字后的内容；否则 null。 */
    private static String stripWakeKeyword(String text, String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        if (text.startsWith(keyword)) {
            return text.substring(keyword.length()).trim();
        }
        // 唤醒词后跟常见分隔符（逗号/顿号/空格）
        for (String sep : new String[]{"，", ",", "、", " ", "　"}) {
            if (text.startsWith(keyword + sep)) {
                return text.substring(keyword.length() + sep.length()).trim();
            }
        }
        return null;
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
