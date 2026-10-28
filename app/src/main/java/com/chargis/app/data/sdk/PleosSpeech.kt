package com.chargis.app.data.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.VoiceCommand
import com.chargis.app.port.SttPort
import com.chargis.app.port.TtsPort
import com.chargis.app.voice.VoiceCommandParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech SDK로 안내 음성을 낸다. 합성된 음성은 차량 오디오로 나온다.
 * On-device 모드라 네트워크와 앱 등록(Client ID)이 필요 없다(docs/DECISIONS.md 2026-10-28).
 * 퍼미션: TTS_SERVICE.
 *
 * 자동차용 안드로이드가 아니거나 초기화에 실패하면 [fallback](안드로이드 기본 TTS)으로 말한다.
 * SDK에는 말하기 대기열이 없어, 보통 안내는 앞 문장이 끝난 뒤에 말하고 급한 안내(HIGH)는 앞 문장을 끊는다.
 */
class PleosTts(context: Context, private val fallback: TtsPort) : TtsPort {
    private val app = context.applicationContext
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    // 지금 말하는 문장이 끝나면 완료된다. 끝남(onDone)·멈춤(onStop)·오류(onError) 모두 끝으로 본다.
    @Volatile private var speaking: CompletableDeferred<Unit>? = null

    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { speaking?.complete(Unit) }
        override fun onStop() { speaking?.complete(Unit) }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "tts error $errorType")
            speaking?.complete(Unit)
            // 권한이나 엔진 문제는 다시 해도 안 되므로 기본 TTS로 넘어간다.
            if (errorType == TtsError.PERMISSION_DENIED || errorType == TtsError.ON_DEVICE_ENGINE_ERROR ||
                errorType == TtsError.NOT_SUPPORTED_API) ready = false
        }
    }

    /** 자동차용 안드로이드면 SDK를 초기화한다. 결과를 기다리지 않는다(준비되기 전 안내는 기본 TTS가 말한다). */
    fun start() {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        runCatching {
            val t = TextToSpeech(app, TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    runCatching {
                        t.addEventListener(listener)
                        t.setLocale(Locale.KOREA)
                        ready = true
                        Log.i(TAG, "pleos tts ready")
                    }.onFailure { Log.w(TAG, "tts setup failed", it) }
                }
                override fun onFailure(errorType: TtsError) { Log.w(TAG, "tts init failed $errorType") }
            })
        }.onFailure { Log.w(TAG, "tts init threw", it) }
    }

    val isReady: Boolean get() = ready

    override suspend fun speak(text: String, priority: Priority) {
        val t = tts
        if (!ready || t == null) return fallback.speak(text, priority)
        if (priority == Priority.HIGH) {
            runCatching { t.stop() }
        } else {
            // 앞 문장이 끝나기를 기다린다. 끝 알림이 오지 않아도 글자 수로 어림한 시간이 지나면 말한다.
            speaking?.let { withTimeoutOrNull(maxSpeakMs(text)) { it.await() } }
        }
        val done = CompletableDeferred<Unit>()
        speaking = done
        runCatching { t.speak(text, true) }.onFailure {
            Log.w(TAG, "speak failed", it)
            done.complete(Unit)
            fallback.speak(text, priority)
        }
    }

    private fun maxSpeakMs(text: String) = 3_000L + text.length * 250L

    private companion object { const val TAG = "PleosTts" }
}

/**
 * Pleos SpeechToText SDK로 음성 명령을 듣는다(F5). 차량 마이크를 쓰고, 들은 문장은 [VoiceCommandParser]로 명령으로 바꾼다.
 * On-device 모드라 네트워크와 앱 등록이 필요 없다. 퍼미션: STT_SERVICE.
 *
 * 한 문장이 끝나면(인식 종료) 곧바로 다시 듣는다. 구독을 멈추면 인식을 멈춘다.
 * 자동차용 안드로이드가 아니거나 초기화에 실패하면 [fallback](안드로이드 기본 음성 인식)으로 듣는다.
 * 마이크 사용 여부는 설정의 음성 명령 스위치와 마이크 권한([hasPermission])을 그대로 따른다.
 */
class PleosStt(
    context: Context,
    private val fallback: SttPort,
    private val hasPermission: () -> Boolean
) : SttPort {
    private val app = context.applicationContext
    @Volatile private var stt: SpeechToText? = null
    private val ready = CompletableDeferred<Boolean>()

    /** 자동차용 안드로이드면 SDK를 초기화한다. 아니면 처음부터 기본 음성 인식을 쓴다. */
    fun start() {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) { ready.complete(false); return }
        runCatching {
            val s = SpeechToText(app, SttMode.ON_DEVICE)
            stt = s
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    runCatching { s.setLocale(Locale.KOREA) }
                    Log.i(TAG, "pleos stt ready")
                    ready.complete(true)
                }
                override fun onFailure(errorType: SttError) { Log.w(TAG, "stt init failed $errorType"); ready.complete(false) }
            })
        }.onFailure { Log.w(TAG, "stt init threw", it); ready.complete(false) }
    }

    override fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand> = flow {
        val ok = withTimeoutOrNull(INIT_WAIT_MS) { ready.await() } ?: false
        val s = stt
        if (!ok || s == null) emitAll(fallback.listenForCommand(allowed))
        else if (hasPermission()) emitAll(listen(s, allowed))
    }

    private fun listen(s: SpeechToText, allowed: Set<VoiceCommand>): Flow<VoiceCommand> = callbackFlow {
        var closed = false
        fun again() {
            if (closed) return
            // 바로 다시 부르면 끝난 세션과 겹칠 수 있어 잠깐 쉰다.
            launch { delay(RESTART_DELAY_MS); if (!closed) runCatching { s.request() } }
        }
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                if (isFinal) VoiceCommandParser.parse(text, allowed)?.let { trySend(it) }
            }
            override fun onEndedRecognition() = again()
            override fun onError(errorType: SttError) {
                Log.w(TAG, "stt error $errorType")
                // 권한이 없으면 멈추고, 그 밖의 오류(못 알아들음 등)는 조금 쉬었다가 다시 듣는다.
                if (errorType == SttError.PERMISSION_DENIED || errorType == SttError.NOT_SUPPORTED_API) close() else again()
            }
            override fun onUpdatedEpdData(start: Long, end: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        s.addListener(listener)
        runCatching { s.request() }.onFailure { Log.w(TAG, "request failed", it); close() }
        awaitClose {
            closed = true
            runCatching { s.stop() }
            runCatching { s.removeListener(listener) }
        }
    }

    private companion object {
        const val TAG = "PleosStt"
        const val INIT_WAIT_MS = 3_000L
        const val RESTART_DELAY_MS = 300L
    }
}
