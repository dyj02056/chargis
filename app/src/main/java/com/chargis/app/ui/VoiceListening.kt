package com.chargis.app.ui

import com.chargis.app.domain.model.VoiceCommand
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.port.SttPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 음성 명령 듣기(F5, TripController에서 분리). 지금 단계에서 말할 수 있는 명령만 듣고, 들은 명령은 [onCommand]로 넘긴다.
 * [stt]가 없거나(버튼만) 설정에서 끄면 듣지 않는다.
 */
class VoiceListening(
    private val scope: CoroutineScope,
    private val stt: SttPort?,
    private val canControlPreconditioning: Boolean,   // false면 "예열" 명령을 듣지 않는다(켤 수 없으니)
    private val onCommand: (VoiceCommand) -> Unit
) {
    private var job: Job? = null

    /** 지금 단계에서 말할 수 있는 명령. 없으면 듣지 않는다. */
    fun allowedCommands(m: TripMemory): Set<VoiceCommand> = when (m.phase) {
        TripPhase.AwaitingDecision -> setOf(VoiceCommand.ACCEPT, VoiceCommand.NEXT_STOP)
        // 예열을 권한 뒤 아직 꺼져 있으면 "예열"이라고 말해 켤 수 있다(주행 중 버튼을 누르지 않아도 되게).
        TripPhase.NavigatingToCharger ->
            if (canControlPreconditioning && m.preconditionAdvised && m.lastVehicle?.preconditioning != true) setOf(VoiceCommand.PRECONDITION) else emptySet()
        TripPhase.Arrived -> setOf(VoiceCommand.START_CHARGING, VoiceCommand.DEPART)
        TripPhase.Done -> setOf(VoiceCommand.DEPART)
        else -> emptySet()
    }

    /** 음성 명령을 켜 두었고 지금 받을 명령이 있으면 듣기 시작하고, 아니면 멈춘다. 화면에 보여 줄 상태를 돌려준다. */
    fun update(m: TripMemory, enabled: Boolean): VoiceUi {
        job?.cancel()
        job = null
        val allowed = allowedCommands(m)
        val source = stt
        val listening = enabled && source != null && allowed.isNotEmpty()
        if (listening && source != null) {
            job = scope.launch { source.listenForCommand(allowed).collect(onCommand) }
        }
        return VoiceUi(enabled, listening, allowed.map { it.name })
    }
}
