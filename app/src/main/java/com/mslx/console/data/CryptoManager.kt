package com.mslx.console.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES-GCM 加解密工具。
 * 密钥保存在系统 Keystore 中（不可导出），用于加密 Daemon 配置。
 */
object CryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mslx_console_master_key"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    /** 密文前缀：用于区分"旧明文"与"新密文"，避免把密文误当明文展示。 */
    private const val ENC_PREFIX = "enc:v1:"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** 加密为 Base64(iv + ciphertext)，带 enc:v1: 前缀；失败时返回 null。 */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * 解密带 enc:v1: 前缀的密文。
     * - 无前缀（旧版本明文）→ 原样返回，兼容迁移；
     * - 有前缀但解密失败（如备份恢复后 Keystore 密钥丢失）→ 返回 null，
     *   由调用方清空该字段，避免把密文当明文展示。
     */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return encoded
        if (!encoded.startsWith(ENC_PREFIX)) return encoded
        return runCatching {
            val combined = Base64.decode(encoded.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (combined.size < IV_LENGTH_BYTES + 1) return null
            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncrypted(encoded: String): Boolean = encoded.startsWith(ENC_PREFIX)
}
