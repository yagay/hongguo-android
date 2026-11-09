package com.yagay.hongguo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hongguotv.core.AccountCredentials
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Never store login cookies, SMS codes or passports in ordinary preferences,
 * user exports or diagnostic text. Android Keystore holds a non-exportable key.
 * The ciphertext lives in noBackupFilesDir to avoid restoring an undecipherable
 * session after reinstall or device migration.
 */
internal class SecureAccountStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "hongguo_account_v1.bin")
    private val alias = "com.yagay.hongguo.account.aes.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    @Synchronized fun read(): AccountCredentials? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size in 29..16384) { "会话密文无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return AccountCredentials.fromJson(String(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8
        )).takeIf { it.cookie.isNotBlank() }
    }

    @Synchronized fun save(credentials: AccountCredentials) {
        require(credentials.cookie.isNotBlank()) { "无法保存空登录状态" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(credentials.toJson().toByteArray(Charsets.UTF_8))
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    @Synchronized fun clear() {
        if (file.exists() && !file.delete()) throw java.io.IOException("无法清除登录态")
    }
}
