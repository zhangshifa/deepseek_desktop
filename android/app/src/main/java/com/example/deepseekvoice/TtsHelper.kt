package com.example.deepseekvoice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 封装 Android 原生文字转语音（TextToSpeech）。
 * 默认使用中文（Locale.CHINESE）。长文本会按标点/长度分段朗读，避免超出单次长度限制。
 */
class TtsHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                isReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        for (chunk in splitText(text, 4000)) {
            tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "dsv_${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private fun splitText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = (i + maxLen).coerceAtMost(text.length)
            val cut = text.indexOfAny(listOf('。', '！', '？', '\n', '；'), i, end)
            if (cut != -1 && cut > i) end = cut + 1
            result.add(text.substring(i, end))
            i = end
        }
        return result
    }
}
