package com.example.deepseekvoice;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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

    // 电话模式监听间隔（毫秒）——像打电话：无需唤醒词，说话即输入；说完继续听
    private static final long CALL_INTERVAL_HIT = 900;    // 识别到内容后：快速续听下一句
    private static final long CALL_INTERVAL_IDLE = 3000;  // 无语音：安静期后继续听
    private static final long CALL_INTERVAL_ERROR = 3000; // 识别错误：安静期后继续听
    private static final long CALL_INTERVAL_BUSY = 1500;  // 识别器忙：稍等重试

    private final MainActivity activity;
    private final BluetoothAudio bluetoothAudio;
    private SpeechRecognizer recognizer;
    private android.speech.tts.TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening = false;

    // 免手模式（关键字唤醒）
    private volatile boolean wakeActive = false;
    private volatile boolean wakePaused = false;   // 播报期间暂停监听，防止采集到播报声
    private String wakeKeyword = "小深";

    // 电话模式（免唤醒直通：说话即输入，说完自动发，回复播报后继续听）
    private volatile boolean callActive = false;
    private volatile boolean callPaused = false;   // 播报期间暂停监听
    private int callErrStreak = 0;                 // 连续识别错误次数（用于向网页上报"真的坏了"）

    // 诊断信息（网页「关于→语音诊断」可查看，定位"提示已接通但没声音"这类问题）
    private ComponentName recognizerComponent;     // 实际使用的识别服务组件
    private boolean onDeviceRecognizer = false;    // 是否用的设备端离线识别
    private String lastError = "";                 // 最近一次失败原因

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
                // 播报完成回调：网页据此决定"是否恢复免手监听、继续接收语音转文字"
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        emit("done", "");
                    }

                    @SuppressWarnings("deprecation")
                    @Override
                    public void onError(String utteranceId) {
                        emit("done", "");
                    }
                });
            }
        });

        // 语音识别：懒创建（构造时先试一次；失败也不放弃，授权/切换识别服务后可再试）
        ensureRecognizer();
    }

    /**
     * 确保识别器可用。覆盖三种常见"语音不可用"场景：
     *  1. 系统报告可用 → 创建默认识别器；
     *  2. Android 11+ 包可见性 / ROM 未设默认识别服务 → 显式指定已安装的 RecognitionService；
     *  3. 仍拿不到 → Android 13+ 尝试设备端离线识别。
     */
    private boolean ensureRecognizer() {
        if (recognizer != null) return true;
        try {
            if (SpeechRecognizer.isRecognitionAvailable(activity)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            }
            if (recognizer == null) {
                ComponentName cn = findRecognitionService();
                if (cn != null) {
                    recognizerComponent = cn;
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity, cn);
                }
            }
            if (recognizer == null && Build.VERSION.SDK_INT >= 33) {
                try {
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
                        onDeviceRecognizer = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (recognizer != null) {
                recognizer.setRecognitionListener(buildListener());
                lastError = "";
                return true;
            }
            lastError = "本机未安装可用的语音识别服务";
        } catch (Exception e) {
            recognizer = null;
            lastError = "识别器创建失败：" + e.getClass().getSimpleName();
        }
        return false;
    }

    /** 查询本机所有 RecognitionService 实现（优先 Google/系统），返回可直接使用的组件。 */
    private ComponentName findRecognitionService() {
        try {
            PackageManager pm = activity.getPackageManager();
            List<ResolveInfo> list =
                    pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            if (list == null || list.isEmpty()) return null;
            ResolveInfo best = null;
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo == null || ri.serviceInfo.packageName == null) continue;
                if (ri.serviceInfo.packageName.contains("google")) {
                    return new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                }
                if (best == null) best = ri;
            }
            if (best == null) return null;
            return new ComponentName(best.serviceInfo.packageName, best.serviceInfo.name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 本机可用识别服务列表（诊断用）。 */
    private JSONArray listRecognitionServices() {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = activity.getPackageManager();
            List<ResolveInfo> list =
                    pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.serviceInfo != null) arr.put(ri.serviceInfo.packageName);
                }
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private RecognitionListener buildListener() {
        return new RecognitionListener() {
                public void onReadyForSpeech(android.os.Bundle params) {
                    // 免手/电话模式待命中不发 start（避免误点亮 mic 按钮）
                    if (callActive) emit("callready", "");
                    else if (!wakeActive) emit("start", "");
                }

                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rms) { }
                public void onBufferReceived(byte[] buf) { }
                public void onEndOfSpeech() { }

                public void onError(int error) {
                    listening = false;
                    endSco();
                    if (callActive) {
                        // 权限被拒：不要静默死循环，直接挂断并告诉网页真实原因
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            callActive = false;
                            lastError = "麦克风权限被拒绝";
                            emit("callerr", "麦克风权限被拒绝，请在系统设置→应用权限里允许录音后重新接通");
                            handler.post(activity::ensureMicPermission);
                            return;
                        }
                        // 无语音/超时属正常空转；客户端/服务端/网络错误连续出现说明真的坏了 → 上报网页
                        if (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            callErrStreak = 0;
                        } else {
                            callErrStreak++;
                            lastError = errText(error);
                            if (callErrStreak == 3 || callErrStreak % 10 == 0) {
                                emit("callerr", "语音识别异常：" + errText(error)
                                        + "（已重试 " + callErrStreak + " 次）");
                            }
                        }
                        // 电话模式：安静期后继续监听
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            handler.postDelayed(VoiceBridge.this::callListenOnce, CALL_INTERVAL_BUSY);
                        } else {
                            handler.postDelayed(VoiceBridge.this::callListenOnce, CALL_INTERVAL_ERROR);
                        }
                    } else if (wakeActive) {
                        // 免手模式：无语音/超时/其他错误 → 安静期后继续待命（减少采集）
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, WAKE_INTERVAL_BUSY);
                        } else {
                            handler.postDelayed(VoiceBridge.this::wakeListenOnce, WAKE_INTERVAL_ERROR);
                        }
                    } else {
                        lastError = errText(error);
                        emit("error", errText(error));
                    }
                }

                public void onResults(android.os.Bundle results) {
                    listening = false;
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                    if (callActive) {
                        callErrStreak = 0;
                        handleCallResult(text);
                    } else if (wakeActive) {
                        handleWakeResult(text);
                    } else {
                        // 识别完成：final 供网页"说完即发"（partial 已做流式预览）
                        if (!text.isEmpty()) emit("final", text);
                        endSco();
                    }
                }

                public void onPartialResults(android.os.Bundle results) {
                    // 免手/电话模式不逐字回传；手动模式才实时填充
                    if (wakeActive || callActive) return;
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        emit("partial", matches.get(0));
                    }
                }

                public void onEvent(int event, android.os.Bundle params) { }
        };
    }

    // ---------- 权限 / 诊断 ----------

    /** 麦克风权限是否已授予（网页据此提示用户，而不是空转）。 */
    @JavascriptInterface
    public boolean hasMicPermission() {
        return androidx.core.content.ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 主动拉起麦克风权限弹窗。 */
    @JavascriptInterface
    public void requestMicPermission() {
        handler.post(activity::ensureMicPermission);
    }

    /** 语音链路诊断（网页「关于→语音诊断」显示），用于定位"提示已接通但没声音"。 */
    @JavascriptInterface
    public String getVoiceDiagnostics() {
        try {
            JSONObject o = new JSONObject();
            o.put("micPermission", hasMicPermission());
            o.put("systemReportsAvailable", SpeechRecognizer.isRecognitionAvailable(activity));
            o.put("recognizerReady", recognizer != null);
            o.put("component", recognizerComponent == null
                    ? "(系统默认)" : recognizerComponent.flattenToShortString());
            o.put("onDevice", onDeviceRecognizer);
            o.put("installedServices", listRecognitionServices());
            o.put("callActive", callActive);
            o.put("listening", listening);
            o.put("ttsReady", tts != null);
            o.put("lastError", lastError == null ? "" : lastError);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** SpeechRecognizer 错误码 → 中文可读文案。 */
    private static String errText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音失败（麦克风被占用）";
            case SpeechRecognizer.ERROR_CLIENT: return "识别客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误（在线识别需联网）";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没听清";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务端错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到说话";
            default: return "未知错误(" + error + ")";
        }
    }

    /** 开始任何监听前的统一前置检查：返回 "ok" 或失败原因码。 */
    private String preflight() {
        if (!hasMicPermission()) {
            lastError = "麦克风权限未授予";
            handler.post(activity::ensureMicPermission);
            return "noperm";
        }
        if (!ensureRecognizer()) {
            return "norecog";
        }
        return "ok";
    }

    // ---------- 手动模式 ----------

    @JavascriptInterface
    public String startRecognition() {
        String pre = preflight();
        if (!"ok".equals(pre)) return pre;
        if (listening || wakeActive || callActive) return "busy";
        listening = true;
        enableScoIfBt();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        handler.post(() -> {
            try {
                recognizer.startListening(intent);
            } catch (Exception e) {
                listening = false;
                lastError = "启动识别失败：" + e.getClass().getSimpleName();
                emit("error", lastError);
            }
        });
        return "ok";
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
    public String startWake(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            wakeKeyword = keyword.trim();
        }
        String pre = preflight();
        if (!"ok".equals(pre)) return pre;
        wakeActive = true;
        handler.post(this::wakeListenOnce);
        return "ok";
    }

    @JavascriptInterface
    public void stopWake() {
        wakeActive = false;
        wakePaused = false;
        stopRecognition();
    }

    /** 暂停免手监听（播报期间调用，避免播报声音被采集）。 */
    @JavascriptInterface
    public void pauseWake() {
        wakePaused = true;
        stopRecognition();
    }

    /** 恢复免手监听（播报完毕、输入开关开启时调用，继续接收语音转文字）。 */
    @JavascriptInterface
    public void resumeWake() {
        wakePaused = false;
        if (wakeActive) {
            handler.post(this::wakeListenOnce);
        }
    }

    // ---------- 电话模式（免唤醒直通：像打电话一样） ----------

    /**
     * 接通电话模式：无需唤醒词，说话即转文字输入；说完自动发送，回复播报后继续听。
     * 返回状态码供网页判断"是不是真的接通了"：
     *   ok      = 已开始监听
     *   noperm  = 缺麦克风权限（已弹窗申请，授权后网页会收到 perm 事件自动重连）
     *   norecog = 本机没有可用语音识别服务
     */
    @JavascriptInterface
    public String startCall() {
        if (callActive) return "ok";
        String pre = preflight();
        if (!"ok".equals(pre)) return pre;
        // 清理残留状态：手动/免手模式若卡在 listening=true，会导致电话模式永远起不来
        wakeActive = false;
        wakePaused = false;
        callErrStreak = 0;
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (Exception ignored) {
        }
        listening = false;
        callActive = true;
        callPaused = false;
        handler.post(this::callListenOnce);
        return "ok";
    }

    /** 挂断电话模式：停止监听。 */
    @JavascriptInterface
    public void stopCall() {
        callActive = false;
        callPaused = false;
        stopRecognition();
    }

    /** 暂停电话监听（播报期间调用，避免播报声被采集）。 */
    @JavascriptInterface
    public void pauseCall() {
        callPaused = true;
        stopRecognition();
    }

    /** 恢复电话监听（播报完毕、输入开关开启时调用，继续接收语音转文字）。 */
    @JavascriptInterface
    public void resumeCall() {
        callPaused = false;
        if (callActive) {
            handler.post(this::callListenOnce);
        }
    }

    /** 电话模式监听一轮（识别完成后按节流策略续接下一轮）。 */
    private void callListenOnce() {
        if (!callActive || listening || callPaused) return;
        if (!ensureRecognizer()) {
            callActive = false;
            emit("callerr", "语音识别服务不可用：" + lastError);
            return;
        }
        listening = true;
        enableScoIfBt();
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            handler.postDelayed(this::callListenOnce, CALL_INTERVAL_IDLE);
        }
    }

    /** 电话模式识别结果：非空即作为输入交给网页（不要求唤醒词），说完快速续听。 */
    private void handleCallResult(String text) {
        endSco();
        if (!callActive) return;
        if (text == null || text.isEmpty()) {
            // 空结果（只有环境音）：安静期后继续听，不当作输入
            handler.postDelayed(this::callListenOnce, CALL_INTERVAL_IDLE);
            return;
        }
        playTone("in");
        emit("wake", text);   // 复用 wake 事件：网页收到后自动填入输入框并发送
        handler.postDelayed(this::callListenOnce, CALL_INTERVAL_HIT);
    }

    /** 免手模式待命监听一轮（识别完成后按节流策略续接下一轮）。 */
    private void wakeListenOnce() {
        if (!wakeActive || listening || wakePaused) return;
        if (!ensureRecognizer()) {
            wakeActive = false;
            emit("error", "语音识别服务不可用：" + lastError);
            return;
        }
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
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "ds");
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

    /** 当前 App 版本号，供网页"关于"面板显示（如 1.1.0）。 */
    @JavascriptInterface
    public String getVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    /** 把识别结果以 JS 对象回传网页（切到主线程执行 evaluateJavascript，TTS/识别回调可能不在主线程） */
    private void emit(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("text", text);
            final String js = "window.__onSpeech&&window.__onSpeech(" + o.toString() + ");";
            handler.post(() -> {
                WebView wv = activity.getWebView();
                if (wv != null) {
                    wv.evaluateJavascript(js, null);
                }
            });
        } catch (Exception ignored) {
        }
    }
}
