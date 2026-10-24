package com.chargis.app.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.port.SettingsStore
import com.chargis.app.port.TripStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/*
 * 설정과 진행 중인 여행을 기기에 저장한다(Jetpack DataStore).
 * DataStore는 작은 값을 파일에 안전하게 저장하는 안드로이드 도구다. 앱이 꺼져도 남는다.
 */

// 앱 전체에서 하나만 쓰는 저장 파일 "chargis.preferences_pb"
private val Context.chargisPrefs by preferencesDataStore(name = "chargis")

/** 운전자 설정을 DataStore에 저장한다. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val store = context.applicationContext.chargisPrefs

    override val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            profileId = p[PROFILE],
            maxTargetSocPercent = p[MAX_TARGET] ?: UserSettings().maxTargetSocPercent,
            safetyMarginPercent = p[MARGIN] ?: UserSettings().safetyMarginPercent,
            voiceCommands = p[VOICE] ?: false,
            onboarded = p[ONBOARDED] ?: false,
            includeNonRoadside = p[NON_ROADSIDE] ?: true
        )
    }

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        val next = transform(settings.first())
        store.edit { p ->
            if (next.profileId != null) p[PROFILE] = next.profileId else p.remove(PROFILE)
            p[MAX_TARGET] = next.maxTargetSocPercent
            p[MARGIN] = next.safetyMarginPercent
            p[VOICE] = next.voiceCommands
            p[ONBOARDED] = next.onboarded
            p[NON_ROADSIDE] = next.includeNonRoadside
        }
    }

    private companion object {
        val PROFILE = stringPreferencesKey("profile_id")
        val MAX_TARGET = doublePreferencesKey("max_target_soc")
        val MARGIN = intPreferencesKey("safety_margin_pct")
        val VOICE = booleanPreferencesKey("voice_commands")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val NON_ROADSIDE = booleanPreferencesKey("include_non_roadside")
    }
}

/** 진행 중인 여행(JSON 한 덩어리)을 DataStore에 저장한다. */
class PrefsTripStore(context: Context) : TripStore {
    private val store = context.applicationContext.chargisPrefs

    override suspend fun load(): String? = store.data.first()[TRIP]

    override suspend fun save(json: String?) {
        store.edit { p -> if (json != null) p[TRIP] = json else p.remove(TRIP) }
    }

    private companion object {
        val TRIP = stringPreferencesKey("active_trip")
    }
}
