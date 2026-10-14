package io.github.zhuquan7237.musechat

import android.app.Application
import androidx.room.Room
import io.github.zhuquan7237.musechat.data.*
import io.github.zhuquan7237.musechat.network.*

class MuseApplication: Application() {
    val db by lazy { Room.databaseBuilder(this, MuseDatabase::class.java, "musechat.db").build() }
    val credentials by lazy { CredentialRepository(this) }
    val preferences by lazy { PreferenceRepository(this) }
    val http by lazy { HttpTransport() }
    val chatApi by lazy { ChatApiClient(http) }
    val imageApi by lazy { ImageApiClient(http) }
    val images by lazy { ImageRepository(this, http) }
    val chats by lazy { ChatRepository(db) }
}
