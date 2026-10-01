package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- consensus place ----

    private fun loc(cc: String?, city: String?, asn: Int? = 24940, isp: String? = "Hetzner", ip: String = "5.6.7.8") =
        ExitLocation(ip, city, null, null, cc, isp, asn)

    @Test
    fun asnIsReadFromEachDatabase() {
        assertEquals(8075, ExitCheckLogic.parseLocation("""{"ip":"1.1.1.2","country_code":"US","connection":{"asn":8075,"isp":"Microsoft"}}""")!!.asn)
        assertEquals(13335, ExitCheckLogic.parseLocation("""{"ip":"1.1.1.2","country":"US","org":"AS13335 Cloudflare, Inc."}""")!!.asn)
        assertEquals(24940, ExitCheckLogic.parseLocation("""{"ip":"1.1.1.2","country_code":"DE","asn":24940}""")!!.asn)
    }

    @Test
    fun threeAgreeingDatabasesGiveCountryAndCity() {
        val p = ExitCheckLogic.consensus(listOf(loc("DE", "Frankfurt am Main"), loc("DE", "Frankfurt"), loc("DE", "Frankfurt")))!!
        assertEquals("DE", p.countryCode)
        assertTrue(p.city!!.startsWith("Frankfurt"))
        assertTrue(p.known)
        assertEquals(3, p.agreeing)
        assertEquals("🇩🇪 Germany · ${p.city}", ExitCheckLogic.placeLabel(p))
    }

    @Test
    fun cityDisagreementKeepsOnlyTheCountry() {
        val p = ExitCheckLogic.consensus(listOf(loc("US", "Des Moines"), loc("US", "San Jose"), loc("US", null)))!!
        assertEquals("US", p.countryCode)
        assertNull(p.city)
        assertEquals("🇺🇸 USA", ExitCheckLogic.placeLabel(p))
    }

    @Test
    fun oneDatabaseAloneIsNotTrusted() {
        val p = ExitCheckLogic.consensus(listOf(loc("NL", "Amsterdam")))!!
        assertFalse(p.known)
        assertNull(ExitCheckLogic.placeLabel(p))
        assertEquals("5.6.7.8", p.ip)
    }

    @Test
    fun countryDisagreementIsUnknown() {
        assertFalse(ExitCheckLogic.consensus(listOf(loc("NL", "A"), loc("DE", "B")))!!.known)
        // a tie between two countries is unknown too
        assertFalse(ExitCheckLogic.consensus(listOf(loc("NL", "A"), loc("NL", "A"), loc("DE", "B"), loc("DE", "B")))!!.known)
    }

    @Test
    fun cloudflareIsUnknownEvenWhenTheDatabasesAgree() {
        val viaAsn = ExitCheckLogic.consensus(listOf(loc("DE", "Frankfurt", 13335), loc("DE", "Frankfurt"), loc("DE", "Frankfurt")))!!
        assertTrue(viaAsn.anycast)
        assertFalse(viaAsn.known)
        assertNull(ExitCheckLogic.placeLabel(viaAsn))
        val warp = ExitCheckLogic.consensus(listOf(loc("NL", "Amsterdam", 209242), loc("NL", "Amsterdam")))!!
        assertTrue(warp.anycast)
        val viaName = ExitCheckLogic.consensus(listOf(loc("US", "X", null, "Cloudflare, Inc."), loc("US", "X", null, "x")))!!
        assertTrue(viaName.anycast)
    }

    @Test
    fun noAnswersIsNull() {
        assertNull(ExitCheckLogic.consensus(emptyList()))
    }

    @Test
    fun countryLabels() {
        assertEquals("USA", ExitCheckLogic.countryLabel("US"))
        assertEquals("UK", ExitCheckLogic.countryLabel("gb"))
        assertEquals("France", ExitCheckLogic.countryLabel("FR"))
    }

    // ---- per-config record ----

    private fun place(ip: String?) = ExitPlace(ip, "DE", "Berlin", anycast = false, sources = 3, agreeing = 3)

    @Test
    fun firstCheckIsNotAChange() {
        val r = StoredPlace.next(null, place("1.1.1.1"), 10)
        assertEquals("1.1.1.1", r.ip)
        assertFalse(r.ipChanged)
        assertEquals(10, r.checkedAt)
    }

    @Test
    fun aDifferentIpIsFlaggedAndStaysFlagged() {
        val first = StoredPlace.next(null, place("1.1.1.1"), 1)
        val second = StoredPlace.next(first, place("2.2.2.2"), 2)
        assertTrue(second.ipChanged)
        assertEquals("1.1.1.1", second.previousIp)
        val third = StoredPlace.next(second, place("1.1.1.1"), 3)
        assertTrue(third.ipChanged)
    }

    @Test
    fun sameIpOrMissingIpIsNotAChange() {
        val first = StoredPlace.next(null, place("1.1.1.1"), 1)
        assertFalse(StoredPlace.next(first, place("1.1.1.1"), 2).ipChanged)
        assertFalse(StoredPlace.next(first, place(null), 2).ipChanged)
    }

    @Test
    fun storedPlaceRoundTripsThroughLabel() {
        val r = StoredPlace.next(null, place("1.1.1.1"), 1)
        assertEquals("🇩🇪 Germany · Berlin", ExitCheckLogic.placeLabel(r.toPlace()))
    }
}
