package dev.offlinezec.wallet.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The wallet's BIP 39 mnemonic and birthday, encrypted with a key in the Android Keystore. */
class SeedStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "seed",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val mnemonic: String? get() = prefs.getString(KEY_MNEMONIC, null)

    /** Height the wallet was created at (its birthday), if known. */
    val birthday: Long? get() = prefs.getLong(KEY_BIRTHDAY, -1).takeIf { it >= 0 }

    fun save(mnemonic: String, birthday: Long?) {
        prefs.edit().putString(KEY_MNEMONIC, mnemonic).apply {
            if (birthday != null) putLong(KEY_BIRTHDAY, birthday) else remove(KEY_BIRTHDAY)
        }.commit()
    }

    fun saveBirthday(birthday: Long) {
        prefs.edit().putLong(KEY_BIRTHDAY, birthday).commit()
    }

    private companion object {
        const val KEY_MNEMONIC = "mnemonic"
        const val KEY_BIRTHDAY = "birthday"
    }
}
