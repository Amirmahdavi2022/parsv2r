// Runs the app's own exit-check code (handler/ExitCheck.kt + ExitCheckHttp.kt, compiled as-is)
// against the live services from a CI runner, and prints both the raw answers and the verdicts,
// so the classifiers are checked against what the services really send rather than assumed.
import com.v2ray.ang.handler.ExitCheckHttp
import com.v2ray.ang.handler.ExitCheckLogic

fun main() {
    val http = ExitCheckHttp(httpPort = 0)
    val raw = listOf(
        "https://ipwho.is/", "https://ipinfo.io/json", "https://api.ip.sb/geoip",
        "https://api.openai.com/compliance/cookie_requirements", "https://ios.chat.openai.com/",
        "https://claude.ai/", "https://www.google.com/search?q=${ExitCheckLogic.GOOGLE_QUERY}&hl=en",
        "https://www.youtube.com/generate_204", "https://www.instagram.com/", "https://telegram.org/",
        "https://api.binance.com/api/v3/ping",
    )
    for (u in raw) {
        val p = http.fetch(u, accept = if (u.contains("ipwho") || u.contains("ipinfo") || u.contains("geoip") || u.contains("binance")) "application/json" else ExitCheckHttp.HTML_ACCEPT)
        if (p == null) { println("RAW $u -> NO ANSWER"); continue }
        val snippet = p.body.take(220).replace("\n", " ")
        println("RAW $u -> ${p.code} final=${p.finalUrl} cf-mitigated=${p.headers["cf-mitigated"]} ms=${p.latencyMs} len=${p.body.length}")
        println("    body: $snippet")
    }
    println()
    val report = http.run()
    println("LOCATION ${report.location}")
    val all = http.lookupLocations()
    all.forEach { println("SOURCE $it") }
    val place = ExitCheckLogic.consensus(all)
    println("PLACE $place")
    println("LABEL ${ExitCheckLogic.placeLabel(place)}")
    println("FLAG ${ExitCheckLogic.flagEmoji(report.location?.countryCode)}")
    report.results.forEach { println("VERDICT ${it.service} ${it.verdict} ${it.latencyMs}ms") }
    println()
    println(ExitCheckLogic.shareText(report.location, report.results, "Exit check", "Exit:", { it.name }, "t.me/parsv2r"))
}
