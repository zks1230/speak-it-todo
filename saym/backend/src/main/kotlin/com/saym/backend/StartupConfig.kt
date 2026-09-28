package com.saym.backend

import com.saym.shared.crypto.CryptoUtil
import java.io.File
import java.util.Properties

/**
 * 启动配置（D13 边界：鸡生蛋问题，不能进 DB）。
 * 加载顺序（后覆盖前）：
 *   1) 环境变量（最高优先级，便于容器部署）
 *   2) ./secrets.enc 同目录下的 .env 明文文件（本地兜底）
 *
 * 必须字段：
 *   NEON_JDBC_URL, NEON_USER, NEON_PASSWORD  - Neon 主库连接串
 *   SAYM_CONFIG_KEY                            - AES-256 加密密钥（用于 DB 内敏感字段）
 *   SAYM_MASTER_HASH                          - 主密码 bcrypt 哈希
 * 可选：
 *   SAYM_PORT (默认 8080), SAYM_DB_POOL (默认 5)
 *   SAYM_BIND_ADDR (默认 0.0.0.0)
 *   SAYM_DEFAULT_DEVICE_ID (默认启动时自动绑定的设备 ID)
 */
data class StartupConfig(
    val neonJdbcUrl: String,
    val neonUser: String,
    val neonPassword: String,
    val configKey: ByteArray,           // AES-256 密钥（32 字节）
    val masterHash: String,             // bcrypt 哈希
    val port: Int = 8080,
    val bindAddr: String = "0.0.0.0",
    val dbPool: Int = 5,
    val defaultDeviceId: String? = null
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object StartupConfigLoader {
    fun load(): StartupConfig {
        // 先读 .env（如有）
        val envProps = Properties()
        val envFile = File(".env")
        if (envFile.exists()) {
            envFile.bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#") && "=" in t) {
                        val i = t.indexOf('=')
                        envProps[t.substring(0, i).trim()] = t.substring(i + 1).trim().removeSurrounding("\"")
                    }
                    line = r.readLine()
                }
            }
        }
        fun g(key: String, def: String? = null): String? =
            System.getenv(key) ?: envProps.getProperty(key) ?: def
        fun gi(key: String, def: Int): Int = g(key)?.toIntOrNull() ?: def

        val jdbc = g("NEON_JDBC_URL")
            ?: error("启动配置缺失：NEON_JDBC_URL（Neon 主库连接串），请设为环境变量或写入 .env")
        val user = g("NEON_USER") ?: error("启动配置缺失：NEON_USER")
        val pwd = g("NEON_PASSWORD") ?: error("启动配置缺失：NEON_PASSWORD")
        val keyStr = g("SAYM_CONFIG_KEY") ?: error("启动配置缺失：SAYM_CONFIG_KEY（AES-256 密钥，任意长度字符串即可）")
        val hash = g("SAYM_MASTER_HASH") ?: error("启动配置缺失：SAYM_MASTER_HASH（主密码 bcrypt 哈希）")
        return StartupConfig(
            neonJdbcUrl = jdbc,
            neonUser = user,
            neonPassword = pwd,
            configKey = CryptoUtil.deriveKey(keyStr),
            masterHash = hash,
            port = gi("SAYM_PORT", 8080),
            bindAddr = g("SAYM_BIND_ADDR", "0.0.0.0")!!,
            dbPool = gi("SAYM_DB_POOL", 5),
            defaultDeviceId = g("SAYM_DEFAULT_DEVICE_ID")
        )
    }

    /** 生成主密码哈希（命令行工具：`java -cp backend.jar com.saym.backend.StartupConfigLoader hashPwd 你的密码`） */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("用法: hashPwd <明文主密码>")
            return
        }
        val h = com.saym.shared.crypto.BcryptUtil.hash(args[0])
        println("主密码 bcrypt 哈希（请设到 SAYM_MASTER_HASH）:\n$h")
        // 顺便生成一个 48 字符随机 AES 密钥串
        val sr = java.security.SecureRandom()
        val key = (1..48).map { (sr.nextInt(26) + 'a'.code).toChar() }.joinToString("")
        println("AES-256 密钥（请设到 SAYM_CONFIG_KEY）:\n$key")
    }
}
