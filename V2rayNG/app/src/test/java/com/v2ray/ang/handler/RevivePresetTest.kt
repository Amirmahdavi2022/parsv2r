package com.v2ray.ang.handler

import com.google.gson.JsonParser
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevivePresetTest {

    private fun rangeMin(s: String): Long {
        // Xray range strings: "5" or "5-10"
        val parts = s.split("-").filter { it.isNotEmpty() }
        return parts.minOf { it.toLong() }
    }

    @Test
    fun finalMaskIsTwoTcpFragmentsXrayWillAccept() {
        val root = JsonParser.parseString(RevivePreset.FINAL_MASK).asJsonObject
        assertEquals(setOf("tcp"), root.keySet())
        val masks = root.getAsJsonArray("tcp")
        assertEquals(2, masks.size())

        masks.forEach { m ->
            val o = m.asJsonObject
            assertEquals("fragment", o.get("type").asString)
            val st = o.getAsJsonObject("settings")
            // Same rules infra/conf/transport_finalmask.go enforces before it will start:
            val packets = st.get("packets").asString
            if (packets != "tlshello") assertTrue("packets range must not start at 0", rangeMin(packets) > 0)
            val lengths = st.getAsJsonArray("lengths").map { it.asString }
            assertTrue("last lengths entry min can't be 0", rangeMin(lengths.last()) > 0)
            assertTrue(st.getAsJsonArray("delays").size() > 0)
            st.get("maxSplit").asString.toLong()
        }
        assertEquals("tlshello", masks[0].asJsonObject.getAsJsonObject("settings").get("packets").asString)
        assertEquals("1-1", masks[1].asJsonObject.getAsJsonObject("settings").get("packets").asString)
    }

    @Test
    fun cipherListIsTheThirteenSuitesInOrder() {
        val names = RevivePreset.CIPHER_SUITES.split(":")
        assertEquals(13, names.size)
        assertEquals("TLS_AES_256_GCM_SHA384", names.first())
        assertEquals("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", names.last())
        assertTrue(names.none { it.isBlank() || it.contains(" ") })
    }

    @Test
    fun appliesOnlyToPlainTlsConfigsWithoutTheirOwnMask() {
        fun a(on: Boolean = true, t: EConfigType = EConfigType.VLESS, sec: String? = "tls", mask: String? = null) =
            RevivePreset.appliesTo(on, t, sec, mask)

        assertTrue(a())
        assertTrue(a(t = EConfigType.VMESS))
        assertTrue(a(t = EConfigType.TROJAN))
        assertTrue(a(mask = "  "))
        assertFalse("switch off", a(on = false))
        assertFalse("reality left alone", a(sec = "reality"))
        assertFalse("no tls", a(sec = null))
        assertFalse("own mask wins", a(mask = "{\"tcp\":[]}"))
        assertFalse(a(t = EConfigType.HYSTERIA2))
        assertFalse(a(t = EConfigType.WIREGUARD))
        assertFalse(a(t = EConfigType.SHADOWSOCKS))
    }

    @Test
    fun ownCipherListWins() {
        assertEquals("X:Y", RevivePreset.cipherSuitesFor("X:Y"))
        assertEquals(RevivePreset.CIPHER_SUITES, RevivePreset.cipherSuitesFor(null))
        assertEquals(RevivePreset.CIPHER_SUITES, RevivePreset.cipherSuitesFor(" "))
    }
}
