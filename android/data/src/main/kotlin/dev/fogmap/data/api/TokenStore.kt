package dev.fogmap.data.api

import android.content.Context

/**
 * Токены и отметка последней синхронизации.
 *
 * Токены лежат зашифрованными ключом из Android Keystore: файл настроек читается на устройстве с
 * root как есть, и в открытом виде это была бы выдача доступа к аккаунту вместе с историей
 * перемещений. Отметка времени синка не секрет и хранится как есть.
 */
internal class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("fogmap-auth", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto("fogmap-tokens")

    var accessToken: String?
        get() = read(KEY_ACCESS)
        set(value) = write(KEY_ACCESS, value)

    var refreshToken: String?
        get() = read(KEY_REFRESH)
        set(value) = write(KEY_REFRESH, value)

    /** Время сервера с прошлого синка — параметр `since` следующего запроса. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    val isAuthenticated: Boolean get() = accessToken != null

    fun save(tokens: TokensResponse) {
        prefs.edit()
            .putString(KEY_ACCESS, crypto.encrypt(tokens.accessToken))
            .putString(KEY_REFRESH, crypto.encrypt(tokens.refreshToken))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun read(key: String): String? = prefs.getString(key, null)?.let { crypto.decrypt(it) }

    private fun write(key: String, value: String?) {
        prefs.edit().putString(key, value?.let { crypto.encrypt(it) }).apply()
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_LAST_SYNC = "lastSync"
    }
}
