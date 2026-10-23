package io.itermux.app

import android.app.Application
import io.itermux.app.data.*

class ItermuxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.initialize(this)
        TerminalFonts.initialize(this)
    }
    val database by lazy { AppDatabase.open(this) }
    val vault by lazy { SecretVault() }
    val pins by lazy { RoomPinStore(database) }
    internal val recentUsage by lazy { RecentUsage(getSharedPreferences("recent-usage", MODE_PRIVATE)) }
}
