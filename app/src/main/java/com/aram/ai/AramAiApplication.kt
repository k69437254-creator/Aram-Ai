package com.aram.ai

import android.app.Application
import com.aram.ai.data.ApiKeyStore

class AramAiApplication : Application() {
    lateinit var apiKeyStore: ApiKeyStore
        private set

    override fun onCreate() {
        super.onCreate()
        apiKeyStore = ApiKeyStore(this)
    }
}
