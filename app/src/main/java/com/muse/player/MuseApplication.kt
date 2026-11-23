package com.muse.player

import android.app.Application
import com.muse.player.data.AspectRepository
import com.muse.player.data.HistoryRepository
import com.muse.player.data.SettingsRepository
import com.muse.player.data.ZoomRepository
import com.muse.player.data.network.NetworkRepository

class MuseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
        HistoryRepository.init(this)
        NetworkRepository.init(this)
        ZoomRepository.init(this)
        AspectRepository.init(this)
    }
}
