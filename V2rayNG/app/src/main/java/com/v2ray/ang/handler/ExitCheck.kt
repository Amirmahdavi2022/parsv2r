package com.v2ray.ang.handler

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Exit check: where the connected config comes out, and which services accept that exit.
//
// This file holds only data and pure decisions, with no Android and no network, so the same
// code runs in the JVM unit tests and in the CI probe that tries it against the real services.
// The network side is ExitCheckHttp.kt.

/** Where the exit IP is, as an IP geolocation service sees it. City is approximate by nature. */
data class ExitLocation(
    val ip: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val countryCode: String?,
    val isp: String?,
    val asn: Int? = null,
)

/**
 * The location shown next to a config: what several independent IP databases agree on.
 * [anycast] marks an exit that has no single place (Cloudflare and the like); it is shown
 * as unknown instead of whatever city a database guessed for it.
 */
data class ExitPlace(
    val ip: String?,
    val countryCode: String?,
    val city: String?,
    val anycast: Boolean,
    /** How many databases answered, and how many of them agree on the country shown. */
    val sources: Int,
    val agreeing: Int,
) {
    val known: Boolean get() = !anycast && countryCode != null
}

enum class ServiceVerdict {
    /** Opened normally. */
    OK,

    /** The service answered and refused this region or this kind of IP. */
    BLOCKED,

    /** Works in one place and not the other (e.g. ChatGPT web but not the app). */
    LIMITED,

    /** The service answered with a captcha or a bot challenge for this IP. */
    CAPTCHA,

    /** No usable answer: timeout, connection error, or a page we can't read. */
    FAILED,
}

enum class CheckedService {
    CHATGPT,
    CLAUDE,
    GOOGLE,
    YOUTUBE,
    INSTAGRAM,
    TELEGRAM,
    BINANCE,
}

data class ServiceResult(
    val service: CheckedService,
    val verdict: ServiceVerdict,
    val latencyMs: Long? = null,
)

/**
 * One HTTP answer, reduced to what the decisions need. Header names are lower-case.
 * A probe that never got an answer is represented by null, not by an instance.
 */
data class ProbeResponse(
    val code: Int,
    val finalUrl: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val latencyMs: Long = 0,
)

object ExitCheckLogic {

    /** Tried in order; each is free, keyless and returns the city. */
    val LOCATION_URLS = listOf(
        "https://ipwho.is/",
        "https://ipinfo.io/json",
        "https://api.ip.sb/geoip",
    )

    /** The word searched on Google. Its presence in the page shows real results came back. */
    const val GOOGLE_QUERY = "parsv2r"

    /**
     * Reads the answer of any service in [LOCATION_URLS]. Their field names differ:
     * ipwho.is uses country/country_code/connection.isp, ipinfo.io puts the two-letter code
     * in "country" and the ASN in front of "org", ip.sb uses country_code/isp/organization.
     * Returns null for a failure answer or one carrying neither an IP nor a country.
     */
    fun parseLocation(json: String?): ExitLocation? {
        if (json.isNullOrBlank()) return null
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        if (o.has("success") && o.get("success").isJsonPrimitive && !o.get("success").asBoolean) return null

        val rawCountry = o.str("country")
        val code = (o.str("country_code") ?: o.str("countryCode")
            ?: rawCountry?.takeIf { it.length == 2 })?.uppercase()
        val countryName = rawCountry?.takeIf { it.length > 2 } ?: o.str("country_name")
        val connection = o.get("connection")?.takeIf { it.isJsonObject }?.asJsonObject
        val isp = connection?.str("isp") ?: connection?.str("org")
            ?: o.str("isp") ?: o.str("organization") ?: o.str("org")?.let(::stripAsn)
        val ip = o.str("ip") ?: o.str("query")

        if (ip == null && code == null) return null
        return ExitLocation(
            ip = ip,
            city = o.str("city"),
            region = o.str("region") ?: o.str("region_name") ?: o.str("regionName"),
            country = countryName,
            countryCode = code,
            isp = isp,
            asn = parseAsn(o, connection),
        )
    }

    private fun parseAsn(o: JsonObject, connection: JsonObject?): Int? {
        fun num(e: JsonElement?): Int? =
            if (e != null && e.isJsonPrimitive) e.asString.trim().removePrefix("AS").toIntOrNull() else null
        return num(connection?.get("asn")) ?: num(o.get("asn"))
            ?: o.str("org")?.let { Regex("^AS(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    /**
     * Cloudflare's networks: 13335 is Cloudflare itself (CDN and most WARP exits), 209242 is
     * Cloudflare's WARP egress network. An exit on these is anycast or shared, so a city for
     * it says nothing about where the traffic really leaves.
     */
    val ANYCAST_ASNS = setOf(13335, 209242)

    fun isAnycast(l: ExitLocation): Boolean =
        (l.asn != null && l.asn in ANYCAST_ASNS) || l.isp?.contains("cloudflare", ignoreCase = true) == true

    /**
     * Combines the answers of several IP databases into one place:
     * - any database seeing Cloudflare makes the exit anycast, shown as unknown;
     * - a country is shown only when at least two databases agree on it (one database alone
     *   is not trusted, since that is exactly how "fixed IP in Germany" ends up being elsewhere);
     * - a city is added only when at least two of the databases that agree on the country
     *   also agree on the city.
     */
    fun consensus(locations: List<ExitLocation>): ExitPlace? {
        if (locations.isEmpty()) return null
        val ip = locations.firstNotNullOfOrNull { it.ip }
        if (locations.any { isAnycast(it) }) {
            return ExitPlace(ip, null, null, anycast = true, sources = locations.size, agreeing = 0)
        }
        val byCountry = locations.filter { it.countryCode != null }.groupBy { it.countryCode!! }
        val best = byCountry.maxByOrNull { it.value.size }
        if (best == null || best.value.size < 2 || byCountry.count { it.value.size == best.value.size } > 1) {
            return ExitPlace(ip, null, null, anycast = false, sources = locations.size, agreeing = best?.value?.size ?: 0)
        }
        val city = best.value.mapNotNull { it.city?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .groupBy { normalizeCity(it) }
            .maxByOrNull { it.value.size }
            ?.takeIf { it.value.size >= 2 }
            ?.value?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key
        return ExitPlace(ip, best.key, city, anycast = false, sources = locations.size, agreeing = best.value.size)
    }

    /** "Frankfurt am Main" and "Frankfurt" are the same answer; so are case and accents. */
    fun normalizeCity(c: String): String {
        val plain = java.text.Normalizer.normalize(c.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return plain.substringBefore(" am ").substringBefore(" (").substringBefore(",").trim()
            .removeSuffix(" city").trim()
    }

    /** Short English country names for the badge, the way people say them. */
    private val SHORT_COUNTRY = mapOf(
        "US" to "USA", "GB" to "UK", "AE" to "UAE", "RU" to "Russia", "KR" to "South Korea",
        "NL" to "Netherlands", "CZ" to "Czechia", "IR" to "Iran", "TR" to "Turkey",
    )

    fun countryLabel(code: String): String =
        SHORT_COUNTRY[code.uppercase()]
            ?: java.util.Locale("", code.uppercase()).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { code.uppercase() }

    /** "🇩🇪 Germany · Frankfurt", or null when the place isn't known. */
    fun placeLabel(p: ExitPlace?): String? {
        if (p == null || !p.known) return null
        val cc = p.countryCode!!
        val country = countryLabel(cc)
        val head = "${flagEmoji(cc)} $country".trim()
        return if (p.city != null) "$head · ${p.city}" else head
    }

    /** "AS13335 Cloudflare, Inc." -> "Cloudflare, Inc." */
    fun stripAsn(org: String): String =
        org.replaceFirst(Regex("^AS\\d+\\s+"), "").trim().ifEmpty { org }

    /** Two-letter country code to its flag emoji; empty for anything else. */
    fun flagEmoji(countryCode: String?): String {
        val c = countryCode?.trim()?.uppercase() ?: return ""
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + c[0].code)) + String(Character.toChars(base + c[1].code))
    }

    /** Cloudflare sets this header on the page it serves when it challenges the visitor. */
    fun isChallenge(p: ProbeResponse): Boolean =
        p.headers["cf-mitigated"]?.equals("challenge", ignoreCase = true) == true

    /**
     * ChatGPT is judged on two answers, the signals the RegionRestrictionCheck script reads:
     * the API's compliance endpoint says "unsupported_country" for a refused region, and the
     * app host (ios.chat.openai.com) refuses VPN and datacenter IPs. One refusal of the two
     * is LIMITED: the website may work while the app doesn't, or the reverse.
     */
    fun classifyChatGpt(compliance: ProbeResponse?, ios: ProbeResponse?): ServiceVerdict {
        if (compliance == null || ios == null) return ServiceVerdict.FAILED
        val apiRefused = compliance.body.contains("unsupported_country", ignoreCase = true)
        // Seen from a datacenter IP on 2026-10-01: 403 with
        // {"cf_details":"Request is not allowed...","type":"dc"} -- the app refuses the IP
        // while the API side still answers normally.
        val appRefused = ios.body.contains("VPN") ||
            (ios.code == 403 && ios.body.contains("cf_details"))
        return when {
            apiRefused && appRefused -> ServiceVerdict.BLOCKED
            apiRefused || appRefused -> ServiceVerdict.LIMITED
            compliance.body.isBlank() && ios.body.isBlank() -> ServiceVerdict.FAILED
            else -> ServiceVerdict.OK
        }
    }

    /** claude.ai redirects a refused region to anthropic.com/app-unavailable-in-region. */
    fun classifyClaude(p: ProbeResponse?): ServiceVerdict {
        if (p == null) return ServiceVerdict.FAILED
        if (p.finalUrl.contains("app-unavailable-in-region")) return ServiceVerdict.BLOCKED
        if (isChallenge(p)) return ServiceVerdict.CAPTCHA
        return if (hostOf(p.finalUrl) == "claude.ai") ServiceVerdict.OK else ServiceVerdict.FAILED
    }

    /**
     * Google search sends a flagged IP to its /sorry/ captcha page ("unusual traffic") or
     * blocks it outright. Real results contain the searched word.
     */
    fun classifyGoogleSearch(p: ProbeResponse?): ServiceVerdict {
        if (p == null) return ServiceVerdict.FAILED
        val flagged = p.finalUrl.contains("/sorry/") || p.code == 429 ||
            Regex("unusual traffic from|is blocked|unaddressed abuse", RegexOption.IGNORE_CASE)
                .containsMatchIn(p.body)
        return when {
            flagged -> ServiceVerdict.CAPTCHA
            p.code in 200..299 && p.body.contains(GOOGLE_QUERY, ignoreCase = true) -> ServiceVerdict.OK
            else -> ServiceVerdict.FAILED
        }
    }

    /** YouTube's generate_204 answers 204 when the service is reachable. */
    fun classifyYoutube(p: ProbeResponse?): ServiceVerdict = when {
        p == null -> ServiceVerdict.FAILED
        p.code == 204 -> ServiceVerdict.OK
        else -> ServiceVerdict.FAILED
    }

    /** Reachability only: any normal web answer counts, a challenge page is reported as such. */
    fun classifyReachable(p: ProbeResponse?): ServiceVerdict = when {
        p == null -> ServiceVerdict.FAILED
        isChallenge(p) -> ServiceVerdict.CAPTCHA
        p.code in 200..399 -> ServiceVerdict.OK
        else -> ServiceVerdict.FAILED
    }

    /**
     * Binance's public API answers its ping with 200 and "{}", and refuses a restricted
     * location with HTTP 451 (or 403 from its edge) and a "restricted location" message.
     */
    fun classifyBinance(p: ProbeResponse?): ServiceVerdict = when {
        p == null -> ServiceVerdict.FAILED
        p.code == 451 || p.body.contains("restricted location", ignoreCase = true) -> ServiceVerdict.BLOCKED
        p.code == 403 -> ServiceVerdict.BLOCKED
        p.code in 200..299 -> ServiceVerdict.OK
        else -> ServiceVerdict.FAILED
    }

    fun hostOf(url: String): String? =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase()

    /** Share text in the user's language; the labels come from the caller. */
    fun shareText(
        location: ExitLocation?,
        results: List<ServiceResult>,
        header: String,
        locationLabel: String,
        nameOf: (CheckedService) -> String,
        footer: String,
    ): String = buildString {
        appendLine(header)
        if (location != null) {
            val place = listOfNotNull(location.city, location.country ?: location.countryCode)
                .joinToString(", ")
            appendLine("$locationLabel ${flagEmoji(location.countryCode)} $place".replace("  ", " ").trimEnd())
        }
        appendLine()
        results.forEach { appendLine("${symbolOf(it.verdict)} ${nameOf(it.service)}") }
        appendLine()
        append(footer)
    }

    fun symbolOf(v: ServiceVerdict): String = when (v) {
        ServiceVerdict.OK -> "✅"
        ServiceVerdict.BLOCKED -> "⛔"
        ServiceVerdict.LIMITED -> "🟡"
        ServiceVerdict.CAPTCHA -> "⚠️"
        ServiceVerdict.FAILED -> "❌"
    }

    private fun JsonObject.str(key: String): String? {
        val e: JsonElement = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        return e.asString.trim().takeIf { it.isNotEmpty() }
    }
}

/** What is kept per config between checks (stored as JSON by ExitPlaceStore). */
data class StoredPlace(
    val ip: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val anycast: Boolean = false,
    val checkedAt: Long = 0,
    /** The exit IP was different on the previous check, so it is not a fixed IP. */
    val ipChanged: Boolean = false,
    val previousIp: String? = null,
) {
    fun toPlace(): ExitPlace = ExitPlace(ip, countryCode, city, anycast, sources = 0, agreeing = 0)

    companion object {
        /**
         * The record after a new check. An IP change is remembered: once a config has shown two
         * different exits it stays flagged, even if a later check lands on the old IP again.
         */
        fun next(previous: StoredPlace?, now: ExitPlace, at: Long): StoredPlace {
            val changedNow = previous?.ip != null && now.ip != null && previous.ip != now.ip
            return StoredPlace(
                ip = now.ip,
                countryCode = now.countryCode,
                city = now.city,
                anycast = now.anycast,
                checkedAt = at,
                ipChanged = changedNow || (previous?.ipChanged == true),
                previousIp = if (changedNow) previous?.ip else previous?.previousIp,
            )
        }
    }
}
