package com.hunter.screentranslator.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感值（API Key / Token）的加解密 —— v1.18.0 新增。
 *
 * ## 为什么需要
 *
 * v1.17.0 把用户填的 API Key 明文写进 `SharedPreferences`（`screen_translator.xml`），
 * 同时清单里 `allowBackup="true"` 且没有 dataExtractionRules。这意味着设备 root、
 * 或经备份通道导出后，用户的付费密钥就是明文。本 App 的特点是密钥**全部由用户
 * 自备**，泄漏成本由用户承担，所以必须就地加密。
 *
 * ## 为什么不用 androidx.security:security-crypto
 *
 * 它长期停留在 alpha（1.1.0-alpha06），且 EncryptedSharedPreferences 在部分
 * 定制 ROM 上有已知的 Keystore 兼容问题。本工程一贯的取向是"依赖能少则少"
 * （见 build.gradle.kts 里对 moshi / llama AAR 的处理），而这里要的能力
 * （AndroidKeyStore + AES-GCM）平台本身就完整提供了，自己封装约一百行，
 * 失败模式完全可控。
 *
 * ## 方案
 *
 * - 密钥：AndroidKeyStore 里的 AES-256，`setUserAuthenticationRequired(false)`
 *   （后台服务需要在不解锁时也能读密钥；要求解锁会让悬浮球/无障碍在锁屏后失效）。
 * - 加密：AES/GCM/NoPadding，每次加密由系统生成 12 字节随机 IV。
 * - 存储格式：`enc:v1:` + Base64(IV ‖ 密文‖GCM tag)。
 *   带前缀是为了**区分新旧数据** —— 老版本留下的明文没有前缀，读到就直接用，
 *   并在下次写入时自动转成密文（见 [Prefs.migrateSecrets]）。
 *
 * ## 已知边界（如实说明）
 *
 * AndroidKeyStore 的密钥**不可导出且绑定本机**。因此从备份恢复、或换机迁移后，
 * 旧密文解不开 —— 此时 [decrypt] 返回 null，[Prefs] 会退回默认值（空串），
 * 用户需要重新填一次密钥。这是该方案的固有代价，也是我们把 prefs 排除出
 * 备份的原因（见 res/xml/backup_rules.xml）。
 */
internal object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "screen_translator_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 密文前缀，用于区分"已加密"与"旧版明文"。 */
    const val PREFIX = "enc:v1:"

    /**
     * Keystore 不可用（极少数定制 ROM）。
     *
     * 一旦置位就不再重试 —— 每次启动都去撞一遍没有意义，而且会让写操作变慢。
     * 置位后退化为明文存储，并在 [Prefs] 里把这件事暴露给「诊断信息」，
     * 而不是静默假装加密成功。
     */
    @Volatile
    var unavailable: Boolean = false
        private set

    /** 上一次失败原因，供诊断信息展示。 */
    @Volatile
    var lastError: String? = null
        private set

    fun isEncrypted(raw: String?): Boolean = raw != null && raw.startsWith(PREFIX)

    private fun secretKey(): SecretKey? {
        if (unavailable) return null
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    private fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求解锁：悬浮窗服务、无障碍服务、实时翻译都可能在锁屏后继续跑，
                // 那时读不到密钥等于功能直接失效。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** 加密失败返回 null —— 调用方应退回明文写入，绝不能因此丢数据。 */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return plain
        val key = secretKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(body, 0, packed, iv.size, body.size)
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    /**
     * 解密失败返回 null。
     *
     * 失败是**预期内**的情况：换机、恢复备份、用户清除应用数据后重建 Keystore，
     * 都会让旧密文解不开。调用方必须把 null 当作"这个值没了"，退回默认值，
     * 而不是抛异常把 App 打崩。
     */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        val key = secretKey() ?: return null
        return try {
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES)
            )
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 这里**不**置 unavailable：能解不开的多半是数据问题（换机/清数据），
            // 而不是 Keystore 坏了。置位会让同一次启动里其它还能解的值也一起失效。
            Log.w(TAG, "decrypt failed, value dropped: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun fail(t: Throwable) {
        unavailable = true
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, "AndroidKeyStore unavailable, falling back to plaintext", t)
    }
}
