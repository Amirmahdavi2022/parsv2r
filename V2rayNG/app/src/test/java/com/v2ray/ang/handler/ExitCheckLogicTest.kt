package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures marked "live" are the answers the services really gave the CI runner
 * (an Azure IP in Iowa) on 2026-10-01, captured by tools/exitcheck-probe.
 */
class ExitCheckLogicTest {

    private fun p(code: Int, url: String, body: String = "", headers: Map<String, String> = emptyMap()) =
        ProbeResponse(code, url, body, headers)

    // ---- location ----

    @Test
    fun parsesIpwhoIs() {
        val live = """{"ip":"132.196.36.82","success":true,"type":"IPv4","country":"United States",
            "country_code":"US","region":"Iowa","city":"Des Moines",
            "connection":{"asn":8075,"org":"Microsoft Azure","isp":"Microsoft Corporation"}}"""
        val l = ExitCheckLogic.parseLocation(live)!!
        assertEquals("132.196.36.82", l.ip)
        assertEquals("Des Moines", l.city)
        assertEquals("Iowa", l.region)
        assertEquals("United States", l.country)
        assertEquals("US", l.countryCode)
        assertEquals("Microsoft Corporation", l.isp)
    }

    @Test
    fun parsesIpinfoWhereCountryIsTheCode() {
        val live = """{"ip":"132.196.36.82","city":"Des Moines","region":"Iowa","country":"US",
            "org":"AS8075 Microsoft Corporation"}"""
        val l = ExitCheckLogic.parseLocation(live)!!
        assertEquals("US", l.countryCode)
        assertNull(l.country)
        assertEquals("Microsoft Corporation", l.isp)
        assertEquals("Des Moines", l.city)
    }

    @Test
    fun parsesIpSb() {
        val live = """{"region":"Iowa","organization":"Microsoft Azure","isp":"Microsoft Azure",
            "city":"Des Moines","ip":"132.196.36.82","country":"United States","country_code":"US"}"""
        val l = ExitCheckLogic.parseLocation(live)!!
        assertEquals("US", l.countryCode)
        assertEquals("Microsoft Azure", l.isp)
    }

    @Test
    fun rejectsFailureAndJunk() {
        assertNull(ExitCheckLogic.parseLocation("""{"success":false,"message":"Reserved range"}"""))
        assertNull(ExitCheckLogic.parseLocation("<html>nope</html>"))
        assertNull(ExitCheckLogic.parseLocation(""))
        assertNull(ExitCheckLogic.parseLocation(null))
        assertNull(ExitCheckLogic.parseLocation("""{"city":"Somewhere"}"""))
        assertNull(ExitCheckLogic.parseLocation("[1,2]"))
    }

    @Test
    fun flags() {
        assertEquals("🇺🇸", ExitCheckLogic.flagEmoji("US"))
        assertEquals("🇩🇪", ExitCheckLogic.flagEmoji("de"))
        assertEquals("", ExitCheckLogic.flagEmoji(null))
        assertEquals("", ExitCheckLogic.flagEmoji("USA"))
        assertEquals("", ExitCheckLogic.flagEmoji("1A"))
    }

    @Test
    fun stripsAsn() {
        assertEquals("Cloudflare, Inc.", ExitCheckLogic.stripAsn("AS13335 Cloudflare, Inc."))
        assertEquals("Hetzner", ExitCheckLogic.stripAsn("Hetzner"))
    }

    // ---- ChatGPT ----

    private val apiOk = p(200, "https://api.openai.com/compliance/cookie_requirements", """{"cookie_consent_required": false}""")
    private val apiRefused = p(403, "https://api.openai.com/compliance/cookie_requirements", """{"error":{"code":"unsupported_country_region_territory"}}""")
    private val appOk = p(200, "https://ios.chat.openai.com/", "<html>ok</html>")
    private val appDatacenter = p(403, "https://ios.chat.openai.com/", """{"cf_details":"Request is not allowed. Please try again later.", "type":"dc"}""")
    private val appVpn = p(403, "https://ios.chat.openai.com/", "You may be connected to a disallowed ISP. If you are using a VPN, try disabling it.")

    @Test
    fun chatGpt() {
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyChatGpt(apiOk, appOk))
        assertEquals(ServiceVerdict.LIMITED, ExitCheckLogic.classifyChatGpt(apiOk, appDatacenter)) // live
        assertEquals(ServiceVerdict.LIMITED, ExitCheckLogic.classifyChatGpt(apiOk, appVpn))
        assertEquals(ServiceVerdict.LIMITED, ExitCheckLogic.classifyChatGpt(apiRefused, appOk))
        assertEquals(ServiceVerdict.BLOCKED, ExitCheckLogic.classifyChatGpt(apiRefused, appVpn))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyChatGpt(null, appOk))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyChatGpt(apiOk, null))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyChatGpt(p(502, "u"), p(502, "u")))
    }

    // ---- Claude ----

    @Test
    fun claude() {
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyClaude(p(200, "https://claude.ai/login")))
        assertEquals(
            ServiceVerdict.BLOCKED,
            ExitCheckLogic.classifyClaude(p(200, "https://www.anthropic.com/app-unavailable-in-region"))
        )
        // live: Cloudflare challenge in front of claude.ai
        assertEquals(
            ServiceVerdict.CAPTCHA,
            ExitCheckLogic.classifyClaude(p(403, "https://claude.ai/login", "<title>Just a moment...</title>", mapOf("cf-mitigated" to "challenge")))
        )
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyClaude(p(200, "https://example.com/")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyClaude(null))
    }

    // ---- Google ----

    @Test
    fun google() {
        val q = ExitCheckLogic.GOOGLE_QUERY
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyGoogleSearch(p(200, "https://www.google.com/search?q=$q", "<title>$q - Google Search</title>")))
        assertEquals(ServiceVerdict.CAPTCHA, ExitCheckLogic.classifyGoogleSearch(p(429, "https://www.google.com/sorry/index?continue=x")))
        assertEquals(
            ServiceVerdict.CAPTCHA,
            ExitCheckLogic.classifyGoogleSearch(p(200, "https://www.google.com/search", "Our systems have detected unusual traffic from your computer network"))
        )
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyGoogleSearch(p(500, "https://www.google.com/search")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyGoogleSearch(null))
    }

    // ---- reachability, YouTube, Binance ----

    @Test
    fun youtube() {
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyYoutube(p(204, "https://www.youtube.com/generate_204")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyYoutube(p(200, "https://www.youtube.com/generate_204")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyYoutube(null))
    }

    @Test
    fun reachable() {
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyReachable(p(200, "https://telegram.org/")))
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyReachable(p(302, "https://telegram.org/")))
        assertEquals(ServiceVerdict.CAPTCHA, ExitCheckLogic.classifyReachable(p(403, "https://x/", headers = mapOf("cf-mitigated" to "challenge"))))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyReachable(p(403, "https://x/")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyReachable(null))
    }

    @Test
    fun binance() {
        assertEquals(ServiceVerdict.OK, ExitCheckLogic.classifyBinance(p(200, "https://api.binance.com/api/v3/ping", "{}")))
        // live: a US IP
        assertEquals(
            ServiceVerdict.BLOCKED,
            ExitCheckLogic.classifyBinance(p(451, "https://api.binance.com/api/v3/ping", """{"code":0,"msg":"Service unavailable from a restricted location according to 'b. Eligibility'"}"""))
        )
        assertEquals(ServiceVerdict.BLOCKED, ExitCheckLogic.classifyBinance(p(403, "https://api.binance.com/api/v3/ping")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyBinance(p(500, "https://api.binance.com/api/v3/ping")))
        assertEquals(ServiceVerdict.FAILED, ExitCheckLogic.classifyBinance(null))
    }

    @Test
    fun hosts() {
        assertEquals("claude.ai", ExitCheckLogic.hostOf("https://claude.ai/login"))
        assertEquals("www.anthropic.com", ExitCheckLogic.hostOf("https://WWW.Anthropic.com:443/x"))
        assertNull(ExitCheckLogic.hostOf("not a url"))
    }

    @Test
    fun shareTextCarriesPlaceVerdictsAndFooter() {
        val loc = ExitLocation("1.2.3.4", "Frankfurt", "Hesse", "Germany", "DE", "Hetzner")
        val text = ExitCheckLogic.shareText(
            loc,
            listOf(ServiceResult(CheckedService.CHATGPT, ServiceVerdict.OK), ServiceResult(CheckedService.BINANCE, ServiceVerdict.BLOCKED)),
            header = "H", locationLabel = "Exit:", nameOf = { it.name }, footer = "t.me/parsv2r"
        )
        assertTrue(text.contains("Exit: 🇩🇪 Frankfurt, Germany"))
        assertTrue(text.contains("✅ CHATGPT"))
        assertTrue(text.contains("⛔ BINANCE"))
        assertTrue(text.endsWith("t.me/parsv2r"))
        // the IP is deliberately not in the shared text
        assertTrue(!text.contains("1.2.3.4"))
    }
}
