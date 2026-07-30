package dev.fogmap.data

import android.content.Context
import android.util.Base64
import dev.fogmap.data.api.KeystoreCrypto
import java.security.SecureRandom

/**
 * Ключ шифрования локальной базы.
 *
 * <p>Сам ключ случайный и хранится зашифрованным ключом из Android Keystore — то есть на диске
 * лежит шифротекст, а расшифровать его можно только на этом устройстве. Маска тумана это подробная
 * история перемещений, и держать её открытым текстом нельзя.
 */
internal object DatabaseKey {

    private const val PREFS = "fogmap-db"
    private const val KEY = "passphrase"
    private const val KEY_BYTES = 32

    fun obtain(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crypto = KeystoreCrypto("fogmap-db-key")

        prefs.getString(KEY, null)
            ?.let { crypto.decrypt(it) }
            ?.let { return Base64.decode(it, Base64.NO_WRAP) }

        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY, crypto.encrypt(Base64.encodeToString(fresh, Base64.NO_WRAP)))
            .apply()
        return fresh
    }
}
