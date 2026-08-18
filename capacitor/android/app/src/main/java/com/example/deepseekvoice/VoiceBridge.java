package com.example.deepseekvoice;

import android.content.Intent;
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
 * 网页通过 window.VoiceBridge.startRecognition() / stopRecognition() / speak(text) 调用。
 */
public class VoiceBridge {

    private final MainActivity activity;
    private SpeechRecognizer recognizer;
    private android.speech.tts.TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;

    public VoiceBridge(MainActivity activity) {
        this.activity = activity;

        // 文本转语音（中文播报）
        tts = new android.speech.tts.TextToSpeech(activity, status -> {
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
            }
        });

        // 语音识别
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(android.os.Bundle params) { emit("start", ""); }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { }
                public void onBufferReceived(byte[] buf) { }
                public void onEndOfSpeech() { }
                public void onError(int error) { listening = false; emit("error", String.valueOf(error)); }
                public void onResults(android.os.Bundle results) { deliver(results); }
                public void onPartialResults(android.os.Bundle results) { deliver(results); }
                public void onEvent(int event, android.os.Bundle params) { }

                private void deliver(android.os.Bundle results) {
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        emit("partial", matches.get(0));
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void startRecognition() {
        if (recognizer == null || listening) return;
        listening = true;
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
        }
    }

    @JavascriptInterface
    public void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        handler.post(() -> {
            tts.stop();
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
        });
    }

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
