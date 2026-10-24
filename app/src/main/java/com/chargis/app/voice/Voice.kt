package com.chargis.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.chargis.app.R
import com.chargis.app.domain.model.Priority
import com.chargis.app.port.TtsPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

/** 음성 문구 조립. 문구 원문은 strings.xml에만 둔다. */
interface VoicePhrases {
    fun tripSummary(stops: Int): String
    fun recommend(km: Double, name: String, targetSoc: Double): String
    fun needAnother(): String
    fun congested(name: String, waitMin: Int, altName: String): String
    fun waypointAdded(name: String): String
    fun waypointFailed(): String
    fun ruleNotice(limitMin: Int): String
    fun limitWarn(minutesLeft: Int, nowSoc: Double, endSoc: Double): String
    fun targetSoon(): String
    fun limitShortfall(endSoc: Double, targetSoc: Double): String
    fun targetReached(): String
    fun resume(): String
    fun noSafeStop(): String
}

class AndroidVoicePhrases(private val context: Context) : VoicePhrases {
    override fun tripSummary(stops: Int) =
        if (stops == 0) s(R.string.tts_trip_no_charge) else s(R.string.tts_trip_summary, stops)
    override fun recommend(km: Double, name: String, targetSoc: Double) =
        // 거리는 말하지 않는다: 자막이 남아 있는 동안 화면의 거리와 어긋나기 때문.
        s(R.string.tts_recommend, name, targetSoc.roundToInt())
    override fun needAnother() = s(R.string.tts_need_another)
    override fun congested(name: String, waitMin: Int, altName: String) =
        s(R.string.tts_congested, name, waitMin, altName)
    override fun waypointAdded(name: String) = s(R.string.tts_waypoint_added, name)
    override fun waypointFailed() = s(R.string.tts_waypoint_failed)
    override fun ruleNotice(limitMin: Int) = s(R.string.tts_rule_notice, limitMin)
    override fun limitWarn(minutesLeft: Int, nowSoc: Double, endSoc: Double) =
        s(R.string.tts_limit_warn, minutesLeft, nowSoc.roundToInt(), endSoc.roundToInt())
    override fun targetSoon() = s(R.string.tts_target_soon)
    override fun limitShortfall(endSoc: Double, targetSoc: Double) =
        s(R.string.tts_limit_shortfall, endSoc.roundToInt(), targetSoc.roundToInt())
    override fun targetReached() = s(R.string.tts_target_reached)
    override fun resume() = s(R.string.tts_resume)
    override fun noSafeStop() = s(R.string.tts_no_safe_stop)

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
}

/**
 * 발화 순서/중복 관리(plan.md §11). 한 번에 한 문장만, 같은 문장이 연달아 오면 한 번만 말한다.
 * HIGH 우선순위는 앞선 발화를 끊고 먼저 말하도록 TtsPort에 그대로 전달한다.
 */
class VoiceAnnouncer(private val tts: TtsPort) {
    private val mutex = Mutex()
    private var last: String? = null

    suspend fun announce(text: String, priority: Priority = Priority.NORMAL) = mutex.withLock {
        if (text == last && priority != Priority.HIGH) return@withLock
        last = text
        tts.speak(text, priority)
    }

    fun reset() {
        last = null
    }
}

/** Pleos TextToSpeech SDK가 없을 때 쓰는 Android 기본 TTS 폴백. 초기화 실패 시 조용히 무시한다. */
class AndroidTts(context: Context) : TtsPort {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.KOREAN
    }

    override suspend fun speak(text: String, priority: Priority) {
        if (!ready) return
        val mode = if (priority == Priority.HIGH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts.speak(text, mode, null, text.hashCode().toString()) }
    }
}
