package com.example.deepseekvoice

import android.app.Activity
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 封装 Android 原生语音识别（SpeechRecognizer）。
 * 识别结果通过 onResult 回调，错误通过 onError 回调。
 * 注意：SpeechRecognizer 必须在主线程创建。
 */
class SpeechHelper(private val activity: Activity) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    onError("语音识别错误码: $error")
                }

                override fun onResults(results: Bundle?) {
                    val matches =
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // 可选用部分结果做实时回填，这里仅用最终结果
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
