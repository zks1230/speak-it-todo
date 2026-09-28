package com.saym.shared.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64

/**
 * AES-256-GCM 加密工具。
 * 用于运行时配置的敏感字段（API key 等）在 Neon app_config 表中加密存储（D13）。
 * 加密密钥本身来自启动配置（本地加密文件 / 环境变量），不进 DB。
 */
object CryptoUtil {
    private const val KEY_LEN_BYTES = 32      // 256 位
    private const val IV_LEN_BYTES = 12
    private const val TAG_BITS = 128

    /** 把人类可读的密钥串（任意长度）派生为 32 字节 AES 密钥（SHA-256） */
    fun deriveKey(passphrase: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))

    /** 加密 → 输出 "iv:base64Ciphertext" 形式字符串 */
    fun encrypt(plain: String, key: ByteArray): String {
        require(key.size == KEY_LEN_BYTES) { "AES-256 密钥必须为 32 字节" }
        val iv = ByteArray(IV_LEN_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val full = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        return Base64.getEncoder().encodeToString(full)
    }

    /** 解密（与 encrypt 配对） */
    fun decrypt(payload: String, key: ByteArray): String {
        require(key.size == KEY_LEN_BYTES) { "AES-256 密钥必须为 32 字节" }
        val data = Base64.getDecoder().decode(payload)
        val iv = data.copyOfRange(0, IV_LEN_BYTES)
        val ct = data.copyOfRange(IV_LEN_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}

/** bcrypt 工具（主密码哈希，F6.1）。 */
object BcryptUtil {
    private val GENSALT_ROUNDS = 10
    fun hash(password: String): String = org.mindrot.jbcrypt.BCrypt.hashpw(password, org.mindrot.jbcrypt.BCrypt.gensalt(GENSALT_ROUNDS))
    fun check(password: String, hash: String): Boolean =
        try { org.mindrot.jbcrypt.BCrypt.checkpw(password, hash) } catch (e: Exception) { false }
}
