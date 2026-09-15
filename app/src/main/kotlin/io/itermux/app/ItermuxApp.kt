package io.itermux.app

import android.app.Application
import io.itermux.app.data.*

class ItermuxApp : Application() {
    val database by lazy { AppDatabase.open(this) }
    val vault by lazy { SecretVault() }
    val pins by lazy { RoomPinStore(database) }
}
