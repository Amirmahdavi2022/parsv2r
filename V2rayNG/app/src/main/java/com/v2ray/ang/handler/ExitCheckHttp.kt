package com.v2ray.ang.handler

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The network half of the exit check. Every request goes out through the app's own local
 * HTTP inbound, so it takes exactly the path a real app's traffic takes, routing rules
 * included. With [httpPort] 0 it goes direct, which is how the CI probe runs it.
 *
 * Plain JVM on purpose (OkHttp and Gson only, no Android, no coroutines) so the CI probe can
 * compile and run this same file against the live services.
 */
class ExitCheckHttp(
    private val httpPort: Int,
    private val proxyUsername: String? = null,
    private val proxyPassword: String? = null,
    private val timeoutMs: Long = 10_000,
) {

    data class Report(
        val location: ExitLocation?,
        val results: List<ServiceResult>,
    )

    private val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (httpPort != 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort)))
            val user = proxyUsername
            val pass = proxyPassword
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                b.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, pass))
                        .build()
                }
            }
        }
        b.build()
    }

    /** Runs the location lookup and every service check in parallel. */
    fun run(): Report {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val location = pool.submit(Callable { lookupLocation() })
            val checks = CheckedService.entries.map { s -> s to pool.submit(Callable { check(s) }) }
            val results = checks.map { (s, f) ->
                try {
                    f.get(timeoutMs * 3, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    ServiceResult(s, ServiceVerdict.FAILED)
                }
            }
            val loc = try {
                location.get(timeoutMs * 4, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            return Report(loc, results)
        } finally {
            pool.shutdownNow()
        }
    }

    fun lookupLocation(): ExitLocation? {
        for (url in ExitCheckLogic.LOCATION_URLS) {
            val p = fetch(url, accept = "application/json") ?: continue
            if (p.code !in 200..299) continue
            ExitCheckLogic.parseLocation(p.body)?.let { return it }
        }
        return null
    }

    fun check(service: CheckedService): ServiceResult {
        val (verdict, latency) = when (service) {
            CheckedService.CHATGPT -> {
                val api = fetch(
                    "https://api.openai.com/compliance/cookie_requirements",
                    accept = "*/*",
                    extra = mapOf(
                        "Authorization" to "Bearer null",
                        "Origin" to "https://platform.openai.com",
                        "Referer" to "https://platform.openai.com/",
                    ),
                )
                val ios = fetch("https://ios.chat.openai.com/")
                ExitCheckLogic.classifyChatGpt(api, ios) to api?.latencyMs
            }

            CheckedService.CLAUDE -> fetch("https://claude.ai/").let { ExitCheckLogic.classifyClaude(it) to it?.latencyMs }
            CheckedService.GOOGLE -> fetch("https://www.google.com/search?q=${ExitCheckLogic.GOOGLE_QUERY}&hl=en")
                .let { ExitCheckLogic.classifyGoogleSearch(it) to it?.latencyMs }

            CheckedService.YOUTUBE -> fetch("https://www.youtube.com/generate_204").let { ExitCheckLogic.classifyYoutube(it) to it?.latencyMs }
            CheckedService.INSTAGRAM -> fetch("https://www.instagram.com/").let { ExitCheckLogic.classifyReachable(it) to it?.latencyMs }
            CheckedService.TELEGRAM -> fetch("https://telegram.org/").let { ExitCheckLogic.classifyReachable(it) to it?.latencyMs }
            CheckedService.BINANCE -> fetch("https://api.binance.com/api/v3/ping", accept = "application/json")
                .let { ExitCheckLogic.classifyBinance(it) to it?.latencyMs }
        }
        return ServiceResult(service, verdict, if (verdict == ServiceVerdict.FAILED) null else latency)
    }

    /** One GET; null when no HTTP answer arrived at all. The body is capped at [MAX_BODY]. */
    fun fetch(url: String, accept: String = HTML_ACCEPT, extra: Map<String, String> = emptyMap()): ProbeResponse? {
        val rb = Request.Builder().url(url).get()
            .header("User-Agent", BROWSER_UA)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
        extra.forEach { (k, v) -> rb.header(k, v) }
        val start = System.nanoTime()
        return try {
            client.newCall(rb.build()).execute().use { r ->
                val body = r.body?.let { b ->
                    val source = b.source()
                    source.request(MAX_BODY)
                    val n = minOf(source.buffer.size, MAX_BODY)
                    source.buffer.readUtf8(n)
                }.orEmpty()
                ProbeResponse(
                    code = r.code,
                    finalUrl = r.request.url.toString(),
                    body = body,
                    headers = r.headers.names().associate { it.lowercase() to (r.header(it) ?: "") },
                    latencyMs = (System.nanoTime() - start) / 1_000_000,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_BODY = 512L * 1024
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
}
