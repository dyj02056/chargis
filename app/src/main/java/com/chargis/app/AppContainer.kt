package com.chargis.app

import android.content.Context

/** 수동 DI. 포트 구현체(Fake ↔ SDK)는 여기서만 고른다. */
class AppContainer(private val context: Context)
