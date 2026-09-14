package com.parsv2r.core;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ProxyConfig} into a complete Xray configuration.
 *
 * <p>Two things here are load-bearing and should not be "tidied" later:
 *
 * <ul>
 *   <li>The SOCKS inbound always listens on {@link #SOCKS_PORT}. The tun bridge is pointed at that
 *       port once, at connect time. Keeping it fixed is what lets the server underneath change
 *       without the tun interface being touched, so apps on the phone never see a drop.
 *   <li>The config carries its own DNS block, and DNS is routed explicitly. A Go binary on Android
 *       has no {@code /etc/resolv.conf}, so without this every hostname server silently fails to
 *       resolve; and without the routing rule, lookups fall to the first outbound, which is the
 *       server we have not connected to yet.
 * </ul>
 *
 * <p>Note also what is deliberately absent: {@code allowInsecure}. Current Xray has removed it and
 * rejects the whole config on sight if it appears, so a link carrying {@code allowInsecure=1} has
 * that parameter dropped rather than honoured.
 */
public final class XrayConfig {

    /** The local SOCKS port the tun bridge connects to. Never change this at runtime. */
    public static final int SOCKS_PORT = 10808;
    /** A plain HTTP proxy on loopback, handy for other apps on the device. */
    public static final int HTTP_PORT = 10809;

    public static final String TAG_SOCKS_IN = "socks-in";
    public static final String TAG_HTTP_IN = "http-in";
    public static final String TAG_PROXY = "proxy";
    public static final String TAG_DIRECT = "direct";
    public static final String TAG_BLOCK = "block";
    public static final String TAG_DNS_IN = "dns-in";

    /** Everything that must never be sent through the proxy. */
    static final List<String> PRIVATE_RANGES = Arrays.asList(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/4",
            "240.0.0.0/4", "255.255.255.255/32", "::1/128", "fc00::/7", "fe80::/10");

    private static final List<String> KNOWN_NETWORKS = Arrays.asList(
            "tcp", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp", "splithttp", "kcp", "quic");

    private XrayConfig() {
    }

    /** Log levels Xray accepts. Anything else makes it refuse to start. */
    public static String normaliseLogLevel(String level) {
        if (level == null) return "warning";
        String l = level.trim().toLowerCase(Locale.ROOT);
        switch (l) {
            case "debug":
            case "info":
            case "warning":
            case "error":
            case "none":
                return l;
            default:
                return "warning";
        }
    }

    /** True when we can actually dial this protocol with the Xray core. */
    public static boolean supports(ProxyConfig c) {
        if (c == null) return false;
        switch (c.protocol) {
            case ProxyConfig.VLESS:
            case ProxyConfig.VMESS:
            case ProxyConfig.TROJAN:
            case ProxyConfig.SHADOWSOCKS:
            case ProxyConfig.SOCKS:
                return true;
            default:
                return false;
        }
    }

    public static String build(ProxyConfig c, String logLevel) {
        return Json.writePretty(buildObject(c, logLevel));
    }

    public static Map<String, Object> buildObject(ProxyConfig c, String logLevel) {
        if (!supports(c)) {
            throw new IllegalArgumentException("unsupported protocol: " + (c == null ? "null" : c.protocol));
        }
        Map<String, Object> root = Json.newObject();

        Map<String, Object> log = Json.newObject();
        log.put("loglevel", normaliseLogLevel(logLevel));
        root.put("log", log);

        root.put("dns", dnsBlock());
        root.put("inbounds", inbounds());

        List<Object> outbounds = Json.newArray();
        outbounds.add(proxyOutbound(c));
        outbounds.add(simpleOutbound("freedom", TAG_DIRECT));
        outbounds.add(simpleOutbound("blackhole", TAG_BLOCK));
        root.put("outbounds", outbounds);

        root.put("routing", routing());
        return root;
    }

    // -------------------------------------------------------------- inbounds

    static List<Object> inbounds() {
        List<Object> inbounds = Json.newArray();

        Map<String, Object> socks = Json.newObject();
        socks.put("tag", TAG_SOCKS_IN);
        socks.put("listen", "127.0.0.1");
        socks.put("port", SOCKS_PORT);
        socks.put("protocol", "socks");
        Map<String, Object> socksSettings = Json.newObject();
        socksSettings.put("auth", "noauth");
        socksSettings.put("udp", true);
        socksSettings.put("userLevel", 8);
        socks.put("settings", socksSettings);
        socks.put("sniffing", sniffing());
        inbounds.add(socks);

        Map<String, Object> http = Json.newObject();
        http.put("tag", TAG_HTTP_IN);
        http.put("listen", "127.0.0.1");
        http.put("port", HTTP_PORT);
        http.put("protocol", "http");
        Map<String, Object> httpSettings = Json.newObject();
        httpSettings.put("userLevel", 8);
        http.put("settings", httpSettings);
        http.put("sniffing", sniffing());
        inbounds.add(http);

        return inbounds;
    }

    private static Map<String, Object> sniffing() {
        Map<String, Object> sniffing = Json.newObject();
        sniffing.put("enabled", true);
        List<Object> dest = Json.newArray();
        dest.add("http");
        dest.add("tls");
        sniffing.put("destOverride", dest);
        sniffing.put("routeOnly", false);
        return sniffing;
    }

    // ------------------------------------------------------------------- dns

    static Map<String, Object> dnsBlock() {
        Map<String, Object> dns = Json.newObject();
        List<Object> servers = Json.newArray();
        servers.add("1.1.1.1");
        servers.add("8.8.8.8");
        dns.put("servers", servers);
        dns.put("queryStrategy", "UseIP");
        dns.put("disableCache", false);
        return dns;
    }

    static Map<String, Object> routing() {
        Map<String, Object> routing = Json.newObject();
        routing.put("domainStrategy", "AsIs");
        List<Object> rules = Json.newArray();

        // Anything an app sends to port 53 is handled as DNS rather than leaking out as raw UDP.
        Map<String, Object> appDns = Json.newObject();
        appDns.put("type", "field");
        List<Object> inboundTags = Json.newArray();
        inboundTags.add(TAG_SOCKS_IN);
        inboundTags.add(TAG_HTTP_IN);
        appDns.put("inboundTag", inboundTags);
        appDns.put("port", "53");
        appDns.put("outboundTag", TAG_PROXY);
        rules.add(appDns);

        // The core's own lookups leave directly, not through a server we have not reached yet.
        Map<String, Object> coreDns = Json.newObject();
        coreDns.put("type", "field");
        List<Object> dnsIn = Json.newArray();
        dnsIn.add("dns-module");
        coreDns.put("inboundTag", dnsIn);
        coreDns.put("outboundTag", TAG_DIRECT);
        rules.add(coreDns);

        // Loopback and LAN never go through the tunnel.
        //
        // These are written out rather than expressed as geoip:private on purpose: that shorthand
        // needs geoip.dat shipped alongside the core and copied out of the APK at first run, and
        // a routing rule that silently depends on a data file is a routing rule that fails on the
        // one device where the copy did not happen. Four megabytes saved, one moving part removed.
        Map<String, Object> privateRule = Json.newObject();
        privateRule.put("type", "field");
        List<Object> ips = Json.newArray();
        for (String cidr : PRIVATE_RANGES) ips.add(cidr);
        privateRule.put("ip", ips);
        privateRule.put("outboundTag", TAG_DIRECT);
        rules.add(privateRule);

        routing.put("rules", rules);
        return routing;
    }

    // ------------------------------------------------------------- outbounds

    static Map<String, Object> simpleOutbound(String protocol, String tag) {
        Map<String, Object> out = Json.newObject();
        out.put("tag", tag);
        out.put("protocol", protocol);
        Map<String, Object> settings = Json.newObject();
        if ("freedom".equals(protocol)) {
            settings.put("domainStrategy", "UseIP");
        }
        out.put("settings", settings);
        return out;
    }

    static Map<String, Object> proxyOutbound(ProxyConfig c) {
        Map<String, Object> out = Json.newObject();
        out.put("tag", TAG_PROXY);
        out.put("protocol", c.protocol);
        out.put("settings", protocolSettings(c));
        Map<String, Object> stream = streamSettings(c);
        if (stream != null) out.put("streamSettings", stream);
        Map<String, Object> mux = Json.newObject();
        mux.put("enabled", false);
        mux.put("concurrency", -1);
        out.put("mux", mux);
        return out;
    }

    private static Map<String, Object> protocolSettings(ProxyConfig c) {
        Map<String, Object> settings = Json.newObject();
        switch (c.protocol) {
            case ProxyConfig.VLESS: {
                Map<String, Object> user = Json.newObject();
                user.put("id", c.id);
                user.put("encryption", c.param("encryption", "none"));
                String flow = c.param("flow", "");
                // flow only means anything over a real TLS-like layer
                if (!flow.isEmpty() && !"none".equals(c.security())) user.put("flow", flow);
                user.put("level", 8);
                settings.put("vnext", vnext(c, user));
                return settings;
            }
            case ProxyConfig.VMESS: {
                Map<String, Object> user = Json.newObject();
                user.put("id", c.id);
                user.put("alterId", Json.intOf(c.param("aid", "0"), 0));
                String scy = c.param("encryption", "auto");
                user.put("security", scy.isEmpty() ? "auto" : scy);
                user.put("level", 8);
                settings.put("vnext", vnext(c, user));
                return settings;
            }
            case ProxyConfig.TROJAN: {
                Map<String, Object> server = Json.newObject();
                server.put("address", c.host);
                server.put("port", c.port);
                server.put("password", c.id);
                server.put("level", 8);
                List<Object> servers = Json.newArray();
                servers.add(server);
                settings.put("servers", servers);
                return settings;
            }
            case ProxyConfig.SHADOWSOCKS: {
                Map<String, Object> server = Json.newObject();
                server.put("address", c.host);
                server.put("port", c.port);
                server.put("method", c.secret);
                server.put("password", c.id);
                server.put("level", 8);
                List<Object> servers = Json.newArray();
                servers.add(server);
                settings.put("servers", servers);
                return settings;
            }
            case ProxyConfig.SOCKS: {
                Map<String, Object> server = Json.newObject();
                server.put("address", c.host);
                server.put("port", c.port);
                if (c.id != null && !c.id.isEmpty()) {
                    Map<String, Object> user = Json.newObject();
                    user.put("user", c.id);
                    user.put("pass", c.secret == null ? "" : c.secret);
                    user.put("level", 8);
                    List<Object> users = Json.newArray();
                    users.add(user);
                    server.put("users", users);
                }
                List<Object> servers = Json.newArray();
                servers.add(server);
                settings.put("servers", servers);
                return settings;
            }
            default:
                throw new IllegalArgumentException("unsupported protocol: " + c.protocol);
        }
    }

    private static List<Object> vnext(ProxyConfig c, Map<String, Object> user) {
        Map<String, Object> node = Json.newObject();
        node.put("address", c.host);
        node.put("port", c.port);
        List<Object> users = Json.newArray();
        users.add(user);
        node.put("users", users);
        List<Object> vnext = Json.newArray();
        vnext.add(node);
        return vnext;
    }

    // -------------------------------------------------------------- transport

    static Map<String, Object> streamSettings(ProxyConfig c) {
        String network = normaliseNetwork(c.network());
        String security = c.security();
        Map<String, Object> stream = Json.newObject();
        stream.put("network", network);

        if ("tls".equals(security)) {
            stream.put("security", "tls");
            stream.put("tlsSettings", tlsSettings(c));
        } else if ("reality".equals(security)) {
            stream.put("security", "reality");
            stream.put("realitySettings", realitySettings(c));
        } else {
            stream.put("security", "none");
        }

        switch (network) {
            case "ws":
                stream.put("wsSettings", wsSettings(c));
                break;
            case "httpupgrade":
                stream.put("httpupgradeSettings", httpUpgradeSettings(c));
                break;
            case "xhttp":
                stream.put("xhttpSettings", xhttpSettings(c));
                break;
            case "grpc":
                stream.put("grpcSettings", grpcSettings(c));
                break;
            case "http":
                stream.put("httpSettings", httpSettings(c));
                break;
            case "kcp":
                stream.put("kcpSettings", kcpSettings(c));
                break;
            case "tcp":
            default:
                Map<String, Object> tcp = tcpSettings(c);
                if (tcp != null) stream.put("tcpSettings", tcp);
                break;
        }
        return stream;
    }

    static String normaliseNetwork(String network) {
        if (network == null) return "tcp";
        String n = network.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return "tcp";
        if (n.equals("h2")) return "http";
        if (n.equals("splithttp")) return "xhttp";
        if (!KNOWN_NETWORKS.contains(n)) return "tcp";
        return n;
    }

    /** The name presented in the handshake: explicit sni, else the Host header, else the address. */
    static String serverName(ProxyConfig c) {
        String sni = c.param("sni", "");
        if (!sni.isEmpty()) return sni;
        String host = c.param("host", "");
        if (!host.isEmpty()) {
            int comma = host.indexOf(',');
            return comma > 0 ? host.substring(0, comma).trim() : host.trim();
        }
        return c.host;
    }

    private static Map<String, Object> tlsSettings(ProxyConfig c) {
        Map<String, Object> tls = Json.newObject();
        tls.put("serverName", serverName(c));
        String fp = c.param("fp", "chrome");
        tls.put("fingerprint", fp.isEmpty() ? "chrome" : fp);
        String alpn = c.param("alpn", "");
        if (!alpn.isEmpty()) {
            List<Object> list = Json.newArray();
            for (String part : alpn.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) list.add(t);
            }
            if (!list.isEmpty()) tls.put("alpn", list);
        }
        return tls;
    }

    private static Map<String, Object> realitySettings(ProxyConfig c) {
        Map<String, Object> reality = Json.newObject();
        reality.put("serverName", serverName(c));
        String fp = c.param("fp", "chrome");
        reality.put("fingerprint", fp.isEmpty() ? "chrome" : fp);
        reality.put("publicKey", c.param("pbk", ""));
        reality.put("shortId", c.param("sid", ""));
        reality.put("spiderX", c.param("spx", "/"));
        return reality;
    }

    private static Map<String, Object> wsSettings(ProxyConfig c) {
        Map<String, Object> ws = Json.newObject();
        ws.put("path", c.param("path", "/"));
        String host = c.param("host", "");
        if (!host.isEmpty()) {
            Map<String, Object> headers = Json.newObject();
            headers.put("Host", host);
            ws.put("headers", headers);
        }
        return ws;
    }

    private static Map<String, Object> httpUpgradeSettings(ProxyConfig c) {
        Map<String, Object> hu = Json.newObject();
        hu.put("path", c.param("path", "/"));
        String host = c.param("host", "");
        if (!host.isEmpty()) hu.put("host", host);
        return hu;
    }

    private static Map<String, Object> xhttpSettings(ProxyConfig c) {
        Map<String, Object> x = Json.newObject();
        x.put("path", c.param("path", "/"));
        String host = c.param("host", "");
        if (!host.isEmpty()) x.put("host", host);
        String mode = c.param("mode", "auto");
        x.put("mode", mode.isEmpty() ? "auto" : mode);
        return x;
    }

    private static Map<String, Object> grpcSettings(ProxyConfig c) {
        Map<String, Object> grpc = Json.newObject();
        grpc.put("serviceName", c.param("servicename", c.param("path", "")));
        String mode = c.param("mode", "gun");
        grpc.put("multiMode", "multi".equalsIgnoreCase(mode));
        return grpc;
    }

    private static Map<String, Object> httpSettings(ProxyConfig c) {
        Map<String, Object> http = Json.newObject();
        http.put("path", c.param("path", "/"));
        String host = c.param("host", "");
        if (!host.isEmpty()) {
            List<Object> hosts = Json.newArray();
            for (String part : host.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) hosts.add(t);
            }
            if (!hosts.isEmpty()) http.put("host", hosts);
        }
        return http;
    }

    private static Map<String, Object> kcpSettings(ProxyConfig c) {
        Map<String, Object> kcp = Json.newObject();
        kcp.put("mtu", 1350);
        kcp.put("tti", 50);
        kcp.put("uplinkCapacity", 12);
        kcp.put("downlinkCapacity", 100);
        kcp.put("congestion", false);
        kcp.put("readBufferSize", 1);
        kcp.put("writeBufferSize", 1);
        Map<String, Object> header = Json.newObject();
        header.put("type", c.param("headertype", "none"));
        kcp.put("header", header);
        String seed = c.param("seed", "");
        if (!seed.isEmpty()) kcp.put("seed", seed);
        return kcp;
    }

    /** Only emitted when the link asks for HTTP camouflage; plain tcp needs no settings at all. */
    private static Map<String, Object> tcpSettings(ProxyConfig c) {
        String headerType = c.param("headertype", "none");
        if (!"http".equalsIgnoreCase(headerType)) return null;
        Map<String, Object> tcp = Json.newObject();
        Map<String, Object> header = Json.newObject();
        header.put("type", "http");
        Map<String, Object> request = Json.newObject();
        List<Object> path = Json.newArray();
        path.add(c.param("path", "/"));
        request.put("path", path);
        String host = c.param("host", "");
        if (!host.isEmpty()) {
            Map<String, Object> headers = Json.newObject();
            List<Object> hosts = Json.newArray();
            for (String part : host.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) hosts.add(t);
            }
            headers.put("Host", hosts);
            request.put("headers", headers);
        }
        header.put("request", request);
        tcp.put("header", header);
        return tcp;
    }
}
