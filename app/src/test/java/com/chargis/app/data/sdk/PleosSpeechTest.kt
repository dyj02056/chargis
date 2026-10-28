package com.chargis.app.data.sdk

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.VoiceCommand
import com.chargis.app.port.SttPort
import com.chargis.app.port.TtsPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pleos가 아닌 기기(자동차용 안드로이드가 아님)에서는 Pleos 음성 SDK를 건드리지 않고 기본 엔진으로 넘긴다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PleosSpeechTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ttsFallsBackToDefaultEngineOffPleos() = runTest {
        val spoken = mutableListOf<Pair<String, Priority>>()
        val fallback = object : TtsPort {
            override suspend fun speak(text: String, priority: Priority) { spoken += text to priority }
        }
        val tts = PleosTts(context, fallback).also { it.start() }

        tts.speak("B 휴게소를 추천합니다", Priority.NORMAL)
        tts.speak("5분 뒤 충전이 종료됩니다", Priority.HIGH)

        assertFalse(tts.isReady)
        assertEquals(listOf("B 휴게소를 추천합니다" to Priority.NORMAL, "5분 뒤 충전이 종료됩니다" to Priority.HIGH), spoken)
    }

    @Test
    fun sttFallsBackToDefaultRecognizerOffPleos() = runTest {
        val fallback = object : SttPort {
            override fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand> = flowOf(VoiceCommand.ACCEPT)
        }
        val stt = PleosStt(context, fallback, hasPermission = { true }).also { it.start() }

        assertEquals(listOf(VoiceCommand.ACCEPT), stt.listenForCommand(setOf(VoiceCommand.ACCEPT)).toList())
    }
}
