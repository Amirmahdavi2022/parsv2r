package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType

/**
 * "Revive" preset for Cloudflare-fronted TLS configs.
 *
 * Two pieces, both plain Xray settings:
 *  - FINAL_MASK: two TCP fragment masks. The first splits the TLS ClientHello so the SNI
 *    never arrives in one piece; the second keeps chopping the first data packet after it.
 *    Field names (lengths / delays arrays, packets "1-1", maxSplit) are the ones parsed by
 *    infra/conf/transport_finalmask.go in the xray-core this app ships.
 *  - CIPHER_SUITES: the TLS cipher list. Xray only uses it when the profile has NO uTLS
 *    fingerprint; with a fingerprint set, uTLS builds its own ClientHello and drops it.
 *
 * Kept free of Android and MMKV so the JVM tests can check it directly.
 */
object RevivePreset {

    const val FINAL_MASK =
        "{\"tcp\":[" +
            "{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\",\"lengths\":[\"0\",\"104\",\"1\"],\"delays\":[\"0\"],\"maxSplit\":\"0\"}}," +
            "{\"type\":\"fragment\",\"settings\":{\"packets\":\"1-1\",\"lengths\":[\"114\",\"1\"],\"delays\":[\"1\"],\"maxSplit\":\"11\"}}" +
            "]}"

    const val CIPHER_SUITES =
        "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    /** Protocols whose TLS rides a plain TCP connection, which is what the masks act on. */
    private val tcpTlsTypes = setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)

    /**
     * Whether the global switch should put the preset on this outbound.
     * A config's own final mask always wins. REALITY is left alone on purpose: it has its
     * own handshake and the older fragment setting already covers it.
     */
    fun appliesTo(
        enabled: Boolean,
        configType: EConfigType,
        security: String?,
        ownFinalMask: String?,
    ): Boolean =
        enabled &&
            configType in tcpTlsTypes &&
            security == AppConfig.TLS &&
            ownFinalMask.isNullOrBlank()

    /** The cipher list to use: the config's own if it has one, else the preset. */
    fun cipherSuitesFor(ownCipherSuites: String?): String =
        ownCipherSuites?.takeIf { it.isNotBlank() } ?: CIPHER_SUITES
}
