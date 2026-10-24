package com.chargis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.chargis.app.domain.model.VoiceCommand
import com.chargis.app.port.SttPort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 들은 문장을 명령으로 바꾼다(F5). 순수 함수라 테스트하기 쉽다.
 * 지금 받을 수 있는 명령([allowed]) 중에서만 고르고, 긴 표현을 먼저 본다("충전 시작"이 "시작"보다 먼저).
 * 예) "응 수락할게" → ACCEPT, "다음 휴게소로" → NEXT_STOP, "출발하자" → DEPART
 */
object VoiceCommandParser {
    private val words: List<Pair<String, VoiceCommand>> = listOf(
        "충전 시작" to VoiceCommand.START_CHARGING,
        "충전해" to VoiceCommand.START_CHARGING,
        "충전" to VoiceCommand.START_CHARGING,
        "수락" to VoiceCommand.ACCEPT,
        "좋아" to VoiceCommand.ACCEPT,
        "그래" to VoiceCommand.ACCEPT,
        "다음" to VoiceCommand.NEXT_STOP,
        "거절" to VoiceCommand.NEXT_STOP,
        "넘어가" to VoiceCommand.NEXT_STOP,
        "출발" to VoiceCommand.DEPART,
        "가자" to VoiceCommand.DEPART
    )

    fun parse(text: String, allowed: Set<VoiceCommand>): VoiceCommand? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        return words.firstOrNull { (w, cmd) -> cmd in allowed && t.contains(w) }?.second
    }
}

/**
 * 안드로이드 기본 음성 인식으로 듣는 [SttPort]. 차량 STT SDK를 받기 전 대체 구현이다.
 * 한 문장을 듣고 나면(또는 조용해서 끝나면) 곧바로 다시 듣는다. 구독을 멈추면 인식기를 닫는다.
 * 마이크 권한이 없거나 기기에 인식기가 없으면 아무 명령도 내보내지 않는다.
 * SpeechRecognizer는 메인 스레드에서만 쓸 수 있어, 메인 스레드에서 구독해야 한다(viewModelScope 기본값).
 */
class AndroidSpeechStt(private val context: Context, private val hasPermission: () -> Boolean) : SttPort {

    override fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand> {
        if (!hasPermission() || !SpeechRecognizer.isRecognitionAvailable(context)) return emptyFlow()
        return callbackFlow {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            var closed = false
            fun again() {
                if (!closed) runCatching { recognizer.startListening(intent) }
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    texts.firstNotNullOfOrNull { VoiceCommandParser.parse(it, allowed) }?.let { trySend(it) }
                    again()
                }
                override fun onError(error: Int) {
                    // 권한이 없으면 멈추고, 그 밖의 오류(못 알아들음, 조용함)는 다시 듣는다.
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) close() else again()
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            again()
            awaitClose {
                closed = true
                runCatching { recognizer.cancel() }
                recognizer.destroy()
            }
        }
    }
}
