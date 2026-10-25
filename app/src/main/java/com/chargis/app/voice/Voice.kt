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

/**
 * 음성 문구 조립. 문구 원문은 strings.xml에만 둔다.
 * 함수마다 상황 하나에 해당하는 안내 문장을 만든다. 예) recommend → "다음 충전은 B 휴게소를 추천합니다. 목적지까지는 50퍼센트면 충분합니다."
 * 문구를 코드 밖(strings.xml)에 두면 개발자가 아니어도 문구를 고칠 수 있고, 다른 언어로 옮기기도 쉽다.
 */
interface VoicePhrases {
    fun tripSummary(stops: Int): String
    fun recommend(km: Double, name: String, targetSoc: Double): String
    fun needAnother(): String
    fun congested(name: String, waitMin: Int, altName: String): String
    fun waypointAdded(name: String): String
    fun waypointFailed(): String
    fun waypointsFull(): String
    fun ruleNotice(limitMin: Int): String
    fun limitWarn(minutesLeft: Int, nowSoc: Double, endSoc: Double): String
    fun targetSoon(): String
    fun limitShortfall(endSoc: Double, targetSoc: Double): String
    fun targetReached(): String
    fun resume(): String
    fun noSafeStop(): String
    fun planTwoStops(nextName: String, nextTarget: Double): String
    fun targetChanged(target: Double): String
    /** 경로를 벗어나는 충전소를 추천할 때 덧붙이는 말. [roundTripKm]는 갔다 오는 거리. */
    fun offRoute(name: String, roundTripKm: Int): String
    /** 수락한 경유지를 더 빠른 곳으로 바꿨을 때. [fasterMin]은 목적지에 몇 분 빨리 닿는지. */
    fun waypointSwitched(fromName: String, toName: String, fasterMin: Int): String
    /** 수락한 곳이 갈 수 없게 되어 경유지를 바꿨을 때. */
    fun waypointSwitchedUnreachable(fromName: String, toName: String): String
    /** 충전을 시작했는데 이 충전기의 시간 제한 정보가 없을 때. */
    fun ruleUnknown(): String
    /** 배터리가 차가워 충전이 늦어질 때 예열을 권한다. [savedMin]은 예열로 줄일 수 있는 충전 시간(분). */
    fun preconditionAdvice(savedMin: Int): String
    /** 예열을 권하지만 앱에서 켤 수 없는 차량일 때(차량 화면에서 켜 달라고 한다). */
    fun preconditionAdviceManual(savedMin: Int): String
    /** 차량에 예열을 켰을 때. */
    fun preconditionStarted(): String
    /** 앱에서 예열을 켤 수 없을 때(차량에서 직접 켜야 함). */
    fun preconditionManual(): String
    /** 배터리를 직접 넣는 중일 때 충전소에 도착하면 계기판 값을 확인해 달라고 한다. */
    fun checkSoc(): String
}

/** strings.xml에서 문구를 꺼내 숫자·이름을 끼워 넣는 실제 구현체. */
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
    override fun waypointsFull() = s(R.string.tts_waypoints_full)
    override fun ruleNotice(limitMin: Int) = s(R.string.tts_rule_notice, limitMin)
    override fun limitWarn(minutesLeft: Int, nowSoc: Double, endSoc: Double) =
        s(R.string.tts_limit_warn, minutesLeft, nowSoc.roundToInt(), endSoc.roundToInt())
    override fun targetSoon() = s(R.string.tts_target_soon)
    override fun limitShortfall(endSoc: Double, targetSoc: Double) =
        s(R.string.tts_limit_shortfall, endSoc.roundToInt(), targetSoc.roundToInt())
    override fun targetReached() = s(R.string.tts_target_reached)
    override fun resume() = s(R.string.tts_resume)
    override fun noSafeStop() = s(R.string.tts_no_safe_stop)
    override fun planTwoStops(nextName: String, nextTarget: Double) =
        s(R.string.tts_plan_two_stops, nextName, nextTarget.roundToInt())
    override fun targetChanged(target: Double) = s(R.string.tts_target_changed, target.roundToInt())
    override fun offRoute(name: String, roundTripKm: Int) = s(R.string.tts_off_route, name, roundTripKm)
    override fun waypointSwitched(fromName: String, toName: String, fasterMin: Int) =
        s(R.string.tts_waypoint_switched, fromName, toName, fasterMin)
    override fun waypointSwitchedUnreachable(fromName: String, toName: String) =
        s(R.string.tts_waypoint_switched_unreachable, fromName, toName)
    override fun ruleUnknown() = s(R.string.tts_rule_unknown)
    override fun preconditionAdvice(savedMin: Int) = s(R.string.tts_precondition_advice, savedMin)
    override fun preconditionAdviceManual(savedMin: Int) = s(R.string.tts_precondition_advice_manual, savedMin)
    override fun preconditionStarted() =s(R.string.tts_precondition_started)
    override fun preconditionManual() = s(R.string.tts_precondition_manual)
    override fun checkSoc() = s(R.string.tts_check_soc)

    // strings.xml의 문구 [id]에 [args]를 차례로 끼워 넣는다. 예) "%1$s를 추천합니다" → "B 휴게소를 추천합니다"
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
}

/**
 * 발화 순서/중복 관리(plan.md §11). 한 번에 한 문장만, 같은 문장이 연달아 오면 한 번만 말한다.
 * HIGH 우선순위는 앞선 발화를 끊고 먼저 말하도록 TtsPort에 그대로 전달한다.
 */
class VoiceAnnouncer(private val tts: TtsPort) {
    // Mutex(자물쇠): 여러 안내가 동시에 들어와도 한 번에 하나씩만 처리하게 한다.
    private val mutex = Mutex()
    private var last: String? = null   // 바로 전에 말한 문장

    suspend fun announce(text: String, priority: Priority = Priority.NORMAL) = mutex.withLock {
        // 방금 한 말과 같으면 건너뛴다. 단, 급한 안내(HIGH)는 반복해도 말한다.
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
    // TTS 엔진은 켜지는 데 시간이 걸린다. 준비되기 전에 들어온 문장은 소리 없이 넘긴다(자막은 따로 뜬다).
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.KOREAN
    }

    override suspend fun speak(text: String, priority: Priority) {
        if (!ready) return
        // QUEUE_FLUSH = 말하던 것을 끊고 바로 말함(급한 안내), QUEUE_ADD = 앞 문장이 끝난 뒤 이어서 말함
        val mode = if (priority == Priority.HIGH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts.speak(text, mode, null, text.hashCode().toString()) }
    }
}
