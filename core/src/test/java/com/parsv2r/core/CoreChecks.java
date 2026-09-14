package com.parsv2r.core;

import java.util.List;
import java.util.Map;

/**
 * Every check for the core module, written without a test framework so it can be compiled and run
 * on any plain JVM. {@link CoreChecksTest} is the JUnit entry point CI uses; the bodies live here
 * so that the same checks run in both places and cannot drift apart.
 */
public final class CoreChecks {

    private static int passed;
    private static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) {
        int failed = runAll();
        System.out.println();
        System.out.println(passed + " checks passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println(failures);
            System.exit(1);
        }
    }

    /** Runs everything and returns the number of failures. Never exits the JVM. */
    public static int runAll() {
        passed = 0;
        failures.setLength(0);
        int before = countFailures();

        jsonRoundTrip();
        jsonTolerance();
        vlessLinks();
        vmessLinks();
        trojanLinks();
        shadowsocksLinks();
        socksLinks();
        junkLinks();
        documents();
        xrayBuild();
        xrayTransports();
        jsonImport();

        return countFailures() - before;
    }

    private static int failureCount;

    private static int countFailures() {
        return failureCount;
    }

    // ----------------------------------------------------------------- Json

    private static void jsonRoundTrip() {
        Map<String, Object> o = Json.parseObject("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}");
        is("json int stays int", "1", Json.write(o.get("a")));
        is("json array size", 3, Json.arr(o.get("b")).size());
        is("json nested", 2.5, ((Number) Json.obj(o.get("c")).get("d")).doubleValue());
        is("json write round trip",
                "{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}", Json.write(o));

        // A port must never come back out as 443.0, because Xray reads the file as written.
        Map<String, Object> port = Json.parseObject("{\"port\":443}");
        is("port renders as integer", "{\"port\":443}", Json.write(port));

        is("escapes survive", "a\"b\\c\nd",
                Json.parse("\"a\\\"b\\\\c\\nd\""));
        is("unicode escape", "\u06cc", Json.parse("\"\\u06cc\""));
        is("writer escapes quotes", "\"a\\\"b\"", Json.write("a\"b"));
        is("pretty printing indents", true, Json.writePretty(o).contains("\n  \"a\": 1"));
        is("empty object", "{}", Json.write(Json.newObject()));
        is("empty array", "[]", Json.write(Json.newArray()));
    }

    private static void jsonTolerance() {
        // Xray's own documented samples carry comments, so a pasted one must not be rejected.
        String withComments = "{\n // leading\n \"a\": 1, /* inline */ \"b\": 2\n}";
        is("line and block comments", 2, Json.parseObject(withComments).size());
        is("trailing comma in object", 1, Json.parseObject("{\"a\":1,}").size());
        is("trailing comma in array", 2, Json.arr(Json.parse("[1,2,]")).size());

        threw("bare text is rejected", () -> Json.parse("hello"));
        threw("unterminated string", () -> Json.parse("{\"a\":\"b}"));
        threw("trailing junk", () -> Json.parse("{} extra"));
        threw("array at top level is not a config", () -> Json.parseObject("[1]"));
        threw("unterminated comment", () -> Json.parse("{\"a\":1} /* open"));
    }

    // ------------------------------------------------------------ ProxyConfig

    private static void vlessLinks() {
        ProxyConfig c = ProxyConfig.parse(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443"
                        + "?type=ws&security=tls&path=%2Fvideo&host=cdn.example.com&sni=cdn.example.com"
                        + "&fp=chrome&encryption=none#My%20Server");
        notNull("vless parses", c);
        is("vless protocol", ProxyConfig.VLESS, c.protocol);
        is("vless host", "example.com", c.host);
        is("vless port", 443, c.port);
        is("vless id", "11111111-2222-3333-4444-555555555555", c.id);
        is("vless network", "ws", c.network());
        is("vless security", "tls", c.security());
        is("vless path decoded", "/video", c.param("path"));
        is("vless remark decoded", "My Server", c.remark);

        ProxyConfig reality = ProxyConfig.parse(
                "vless://abc@1.2.3.4:8443?security=reality&pbk=KEY&sid=ab12&flow=xtls-rprx-vision&sni=www.apple.com");
        is("reality security", "reality", reality.security());
        is("reality falls back to tcp", "tcp", reality.network());
        is("reality remark empty", "", reality.remark);

        ProxyConfig ipv6 = ProxyConfig.parse("vless://abc@[2606:4700::1111]:443?security=tls");
        notNull("ipv6 literal parses", ipv6);
        is("ipv6 host has no brackets", "2606:4700::1111", ipv6.host);
        is("ipv6 port", 443, ipv6.port);
    }

    private static void vmessLinks() {
        String payload = "{\"v\":\"2\",\"ps\":\"node one\",\"add\":\"a.example.com\",\"port\":\"2087\","
                + "\"id\":\"aaaa-bbbb\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\","
                + "\"host\":\"a.example.com\",\"path\":\"/ws\",\"tls\":\"tls\"}";
        String link = "vmess://" + java.util.Base64.getEncoder()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProxyConfig c = ProxyConfig.parse(link);
        notNull("vmess parses", c);
        is("vmess host", "a.example.com", c.host);
        is("vmess port from string", 2087, c.port);
        is("vmess remark", "node one", c.remark);
        is("vmess network", "ws", c.network());
        is("vmess tls=tls becomes tls", "tls", c.security());
        is("vmess path", "/ws", c.param("path"));

        ProxyConfig unpadded = ProxyConfig.parse(
                "vmess://" + java.util.Base64.getEncoder().withoutPadding()
                        .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        notNull("vmess without base64 padding parses", unpadded);
        is("vmess unpadded host matches", "a.example.com", unpadded.host);
    }

    private static void trojanLinks() {
        ProxyConfig c = ProxyConfig.parse(
                "trojan://p%40ssw0rd@t.example.com:443?type=grpc&serviceName=gsvc&security=tls#Trojan");
        notNull("trojan parses", c);
        is("trojan password percent-decoded", "p@ssw0rd", c.id);
        is("trojan network", "grpc", c.network());
        is("trojan service name", "gsvc", c.param("servicename"));
    }

    private static void shadowsocksLinks() {
        String userinfo = java.util.Base64.getEncoder().encodeToString(
                "aes-256-gcm:secretpass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProxyConfig modern = ProxyConfig.parse("ss://" + userinfo + "@s.example.com:8388#SS");
        notNull("ss modern form parses", modern);
        is("ss method", "aes-256-gcm", modern.secret);
        is("ss password", "secretpass", modern.id);
        is("ss host", "s.example.com", modern.host);

        String whole = java.util.Base64.getEncoder().encodeToString(
                "chacha20-ietf-poly1305:pw@s2.example.com:443"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProxyConfig legacy = ProxyConfig.parse("ss://" + whole + "#Legacy");
        notNull("ss legacy form parses", legacy);
        is("ss legacy method", "chacha20-ietf-poly1305", legacy.secret);
        is("ss legacy port", 443, legacy.port);

        // The trap worth a regression test: a '+' in a base64 password must survive, and a
        // percent-encoded '=' must be decoded before the base64 decoder ever sees it.
        String plusPass = "ab+cd/ef==";
        String encoded = java.util.Base64.getEncoder().encodeToString(
                ("aes-128-gcm:" + plusPass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String percentEncoded = encoded.replace("=", "%3D");
        ProxyConfig tricky = ProxyConfig.parse("ss://" + percentEncoded + "@s3.example.com:990");
        notNull("ss with percent-encoded padding parses", tricky);
        is("ss password with plus survives", plusPass, tricky.id);
    }

    private static void socksLinks() {
        ProxyConfig bare = ProxyConfig.parse("socks://127.0.0.1:1080");
        notNull("bare socks parses", bare);
        is("bare socks has no user", "", bare.id);
        String auth = java.util.Base64.getEncoder().encodeToString(
                "user:pass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProxyConfig withAuth = ProxyConfig.parse("socks5://" + auth + "@p.example.com:1080#S");
        is("socks user", "user", withAuth.id);
        is("socks pass", "pass", withAuth.secret);
    }

    private static void junkLinks() {
        isNull("null is null", ProxyConfig.parse(null));
        isNull("empty is null", ProxyConfig.parse("   "));
        isNull("no scheme", ProxyConfig.parse("example.com:443"));
        isNull("unknown scheme", ProxyConfig.parse("hysteria2://a@b:443"));
        isNull("missing port", ProxyConfig.parse("vless://abc@example.com"));
        isNull("port out of range", ProxyConfig.parse("vless://abc@example.com:70000"));
        isNull("port not a number", ProxyConfig.parse("vless://abc@example.com:https"));
        isNull("no credential", ProxyConfig.parse("vless://@example.com:443"));
        isNull("vmess with rubbish base64", ProxyConfig.parse("vmess://!!!!"));
        isNull("vmess base64 that is not json", ProxyConfig.parse(
                "vmess://" + java.util.Base64.getEncoder().encodeToString(
                        "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        isNull("truncated link", ProxyConfig.parse("vless://"));
    }

    private static void documents() {
        String doc = "vless://a@one.example.com:443?security=tls\n"
                + "junk line that means nothing\n"
                + "trojan://pw@two.example.com:443\n"
                + "\n"
                + "vless://a@one.example.com:443?security=tls\n";
        List<ProxyConfig> parsed = ProxyConfig.parseDocument(doc);
        is("document keeps the good lines", 2, parsed.size());
        is("document drops the duplicate", "one.example.com", parsed.get(0).host);
        is("document order preserved", "two.example.com", parsed.get(1).host);

        String subscription = java.util.Base64.getEncoder().encodeToString(doc
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<ProxyConfig> fromSub = ProxyConfig.parseDocument(subscription);
        is("base64 subscription unwrapped", 2, fromSub.size());

        is("empty document", 0, ProxyConfig.parseDocument("").size());
        is("null document", 0, ProxyConfig.parseDocument(null).size());
        is("document of pure junk", 0, ProxyConfig.parseDocument("hello\nworld").size());

        // Identity ignores the remark, so the same server renamed twice is still one server.
        List<ProxyConfig> renamed = ProxyConfig.parseDocument(
                "vless://a@x.example.com:443#first\nvless://a@x.example.com:443#second");
        is("remark does not create a duplicate", 1, renamed.size());
    }

    // ------------------------------------------------------------- XrayConfig

    private static void xrayBuild() {
        ProxyConfig c = ProxyConfig.parse(
                "vless://uuid-1@example.com:443?type=ws&security=tls&path=/p&host=h.example.com&fp=chrome");
        Map<String, Object> root = XrayConfig.buildObject(c, "warning");

        List<Object> inbounds = Json.arr(root.get("inbounds"));
        Map<String, Object> socks = Json.obj(inbounds.get(0));
        is("socks inbound tag", XrayConfig.TAG_SOCKS_IN, Json.str(socks.get("tag")));
        is("socks inbound port is fixed", XrayConfig.SOCKS_PORT, Json.intOf(socks.get("port"), -1));
        is("socks inbound is loopback only", "127.0.0.1", Json.str(socks.get("listen")));
        is("socks inbound does udp", Boolean.TRUE, Json.obj(socks.get("settings")).get("udp"));

        is("a dns block is always present", true, Json.obj(root.get("dns")) != null);

        // The private-range rule has to be self-contained: no geoip.dat, no file to copy out of
        // the APK, nothing that can be missing on one device and present on another.
        String routingJson = Json.write(root.get("routing"));
        is("routing never leans on geoip.dat", false, routingJson.contains("geoip:"));
        is("routing never leans on geosite.dat", false, routingJson.contains("geosite:"));
        is("loopback is kept off the tunnel", true, routingJson.contains("127.0.0.0/8"));
        is("rfc1918 is kept off the tunnel", true, routingJson.contains("192.168.0.0/16"));
        is("the tun's own subnet is kept off the tunnel", true, routingJson.contains("198.18.0.0/15"));
        is("ipv6 link local is kept off the tunnel", true, routingJson.contains("fe80::/10"));

        // The LAN switch has to change the config, or it is a control that does nothing.
        String withBypass = Json.write(XrayConfig.routing(true));
        String withoutBypass = Json.write(XrayConfig.routing(false));
        is("bypass on keeps the private rule", true, withBypass.contains("192.168.0.0/16"));
        is("bypass off drops the private rule", false, withoutBypass.contains("192.168.0.0/16"));
        is("bypass off still answers app dns", true, withoutBypass.contains("\"port\":\"53\""));
        is("the two really differ", false, withBypass.equals(withoutBypass));
        is("the built config honours the flag", false,
                XrayConfig.build(c, "warning", false).contains("192.168.0.0/16"));
        is("the default still bypasses", true,
                XrayConfig.build(c, "warning").contains("192.168.0.0/16"));

        List<Object> outbounds = Json.arr(root.get("outbounds"));
        is("three outbounds", 3, outbounds.size());
        Map<String, Object> proxy = Json.obj(outbounds.get(0));
        is("proxy tag", XrayConfig.TAG_PROXY, Json.str(proxy.get("tag")));
        is("proxy protocol", "vless", Json.str(proxy.get("protocol")));

        Map<String, Object> vnextNode = Json.obj(
                Json.arr(Json.obj(proxy.get("settings")).get("vnext")).get(0));
        is("vnext address", "example.com", Json.str(vnextNode.get("address")));
        is("vnext port is a number", 443, Json.intOf(vnextNode.get("port"), -1));

        String rendered = XrayConfig.build(c, "warning");
        is("never emits allowInsecure", false, rendered.contains("allowInsecure"));
        is("renders port unquoted", true, rendered.contains("\"port\": 443"));
        is("log level applied", true, rendered.contains("\"loglevel\": \"warning\""));
        is("bad log level falls back", "warning", XrayConfig.normaliseLogLevel("chatty"));
        is("good log level kept", "debug", XrayConfig.normaliseLogLevel("DEBUG"));

        is("supports vless", true, XrayConfig.supports(c));
        is("supports null is false", false, XrayConfig.supports(null));
    }

    private static void xrayTransports() {
        is("h2 is normalised to http", "http", XrayConfig.normaliseNetwork("h2"));
        is("splithttp is normalised to xhttp", "xhttp", XrayConfig.normaliseNetwork("splithttp"));
        is("unknown network falls back to tcp", "tcp", XrayConfig.normaliseNetwork("carrier-pigeon"));
        is("empty network falls back to tcp", "tcp", XrayConfig.normaliseNetwork(""));

        ProxyConfig ws = ProxyConfig.parse("vless://a@e.com:443?type=ws&security=tls&path=/x&host=h.com");
        Map<String, Object> wsStream = XrayConfig.streamSettings(ws);
        is("ws settings emitted", true, wsStream.containsKey("wsSettings"));
        is("ws path", "/x", Json.str(Json.obj(wsStream.get("wsSettings")).get("path")));
        is("ws host header", "h.com",
                Json.str(Json.obj(Json.obj(wsStream.get("wsSettings")).get("headers")).get("Host")));
        is("sni defaults to the host header", "h.com", XrayConfig.serverName(ws));

        ProxyConfig plain = ProxyConfig.parse("vless://a@e.com:443");
        is("sni defaults to the address", "e.com", XrayConfig.serverName(plain));
        is("plain tcp emits no tcpSettings", false,
                XrayConfig.streamSettings(plain).containsKey("tcpSettings"));
        is("no security means none", "none",
                Json.str(XrayConfig.streamSettings(plain).get("security")));

        ProxyConfig reality = ProxyConfig.parse(
                "vless://a@e.com:443?security=reality&pbk=PUB&sid=00aa&sni=www.apple.com&fp=safari");
        Map<String, Object> rs = Json.obj(XrayConfig.streamSettings(reality).get("realitySettings"));
        is("reality public key", "PUB", Json.str(rs.get("publicKey")));
        is("reality short id", "00aa", Json.str(rs.get("shortId")));
        is("reality fingerprint", "safari", Json.str(rs.get("fingerprint")));
        is("reality server name", "www.apple.com", Json.str(rs.get("serverName")));

        // flow is meaningless without a security layer, and Xray complains if it is sent anyway.
        ProxyConfig flowNoTls = ProxyConfig.parse("vless://a@e.com:80?flow=xtls-rprx-vision");
        Map<String, Object> user = Json.obj(Json.arr(
                Json.obj(XrayConfig.proxyOutbound(flowNoTls).get("settings")).get("vnext")).get(0));
        Map<String, Object> firstUser = Json.obj(Json.arr(user.get("users")).get(0));
        is("flow dropped without tls", false, firstUser.containsKey("flow"));

        ProxyConfig flowTls = ProxyConfig.parse("vless://a@e.com:443?security=reality&flow=xtls-rprx-vision&pbk=K");
        Map<String, Object> node = Json.obj(Json.arr(
                Json.obj(XrayConfig.proxyOutbound(flowTls).get("settings")).get("vnext")).get(0));
        is("flow kept with reality", "xtls-rprx-vision",
                Json.str(Json.obj(Json.arr(node.get("users")).get(0)).get("flow")));

        ProxyConfig camouflage = ProxyConfig.parse("vless://a@e.com:80?type=tcp&headerType=http&host=x.com&path=/q");
        Map<String, Object> tcp = Json.obj(XrayConfig.streamSettings(camouflage).get("tcpSettings"));
        is("http camouflage emitted", "http", Json.str(Json.obj(tcp.get("header")).get("type")));

        ProxyConfig grpc = ProxyConfig.parse("vless://a@e.com:443?type=grpc&serviceName=svc&mode=multi&security=tls");
        Map<String, Object> gs = Json.obj(XrayConfig.streamSettings(grpc).get("grpcSettings"));
        is("grpc service name", "svc", Json.str(gs.get("serviceName")));
        is("grpc multi mode", Boolean.TRUE, gs.get("multiMode"));

        // Every protocol we claim to support must actually render.
        String[] links = {
                "vless://a@e.com:443?security=tls",
                "trojan://pw@e.com:443?security=tls",
                "ss://" + java.util.Base64.getEncoder().encodeToString(
                        "aes-256-gcm:pw".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "@e.com:8388",
                "socks://127.0.0.1:1080",
        };
        for (String link : links) {
            ProxyConfig p = ProxyConfig.parse(link);
            notNull("parses " + link.substring(0, link.indexOf(':')), p);
            String json = XrayConfig.build(p, "warning");
            is("renders valid json for " + p.protocol, true, Json.parseObject(json).size() > 0);
        }
    }

    // ------------------------------------------------------------- JsonImport

    private static void jsonImport() {
        is("json detected", true, JsonImport.looksLikeJson("  {\"a\":1}"));
        is("comment leading json detected", true, JsonImport.looksLikeJson("// hi\n{}"));
        is("share link is not json", false, JsonImport.looksLikeJson("vless://a@b:443"));

        String user = "{"
                + "\"inbounds\":[{\"port\":1080,\"protocol\":\"socks\",\"tag\":\"in\"}],"
                + "\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{"
                + "\"address\":\"j.example.com\",\"port\":443,\"users\":[{\"id\":\"u\"}]}]},"
                + "\"streamSettings\":{\"security\":\"tls\",\"tlsSettings\":{\"allowInsecure\":true,"
                + "\"serverName\":\"j.example.com\"}}}],"
                + "\"routing\":{\"rules\":[{\"type\":\"field\",\"outboundTag\":\"direct\"}]}}";

        JsonImport.Result r = must("a normal pasted config imports", () -> JsonImport.normalise(user, "warning"));
        notNull("import returned something", r);
        Map<String, Object> out = Json.parseObject(r.json);

        List<Object> inbounds = Json.arr(out.get("inbounds"));
        Map<String, Object> first = Json.obj(inbounds.get(0));
        is("our socks inbound is first", XrayConfig.TAG_SOCKS_IN, Json.str(first.get("tag")));
        is("our socks inbound keeps the fixed port", XrayConfig.SOCKS_PORT,
                Json.intOf(first.get("port"), -1));
        is("the user's own inbound is kept", 3, inbounds.size());

        is("allowInsecure is gone", false, r.json.contains("allowInsecure"));
        is("the removal is reported", true, String.join(",", r.notes).contains("allowInsecure"));
        is("a dns block was added", true, Json.obj(out.get("dns")) != null);
        is("the user's routing is untouched", true, Json.obj(out.get("routing")) != null);
        is("label describes the outbound", "vless · j.example.com:443", r.label);
        is("log level is ours", "warning", Json.str(Json.obj(out.get("log")).get("loglevel")));

        // An inbound squatting on our port has to lose, not collide.
        String squatter = "{\"inbounds\":[{\"port\":" + XrayConfig.SOCKS_PORT + ",\"protocol\":\"socks\"}],"
                + "\"outbounds\":[{\"protocol\":\"trojan\",\"settings\":{\"servers\":[{"
                + "\"address\":\"t.example.com\",\"port\":443,\"password\":\"p\"}]}}]}";
        JsonImport.Result s = must("config with a colliding inbound imports",
                () -> JsonImport.normalise(squatter, "warning"));
        List<Object> si = Json.arr(Json.parseObject(s.json).get("inbounds"));
        is("the squatter was removed", 2, si.size());
        is("the collision is reported", true, String.join(",", s.notes).contains("reclaimed"));
        is("trojan label", "trojan · t.example.com:443", s.label);

        // Freedom is not what the user meant by "my server".
        String withFreedom = "{\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"direct\"},"
                + "{\"protocol\":\"vmess\",\"settings\":{\"vnext\":[{\"address\":\"v.example.com\","
                + "\"port\":80,\"users\":[{\"id\":\"x\"}]}]}}]}";
        JsonImport.Result f = must("freedom is skipped when labelling",
                () -> JsonImport.normalise(withFreedom, "warning"));
        is("label skips freedom", "vmess · v.example.com:80", f.label);

        // The single-outbound spelling some generators still emit.
        String singular = "{\"outbound\":{\"protocol\":\"trojan\",\"settings\":{\"servers\":[{"
                + "\"address\":\"o.example.com\",\"port\":443,\"password\":\"p\"}]}}}";
        JsonImport.Result one = must("singular outbound is accepted",
                () -> JsonImport.normalise(singular, "warning"));
        is("singular became plural", 1, Json.arr(Json.parseObject(one.json).get("outbounds")).size());

        rejected("empty text is rejected", "");
        rejected("broken json is rejected", "{\"a\":");
        rejected("a config with no outbounds is rejected", "{\"inbounds\":[]}");
        rejected("an empty outbound list is rejected", "{\"outbounds\":[]}");

        // allowInsecure hides at any depth, including inside arrays.
        Map<String, Object> deep = Json.parseObject(
                "{\"a\":[{\"b\":{\"allowInsecure\":true}},{\"allowInsecure\":false}]}");
        is("nested allowInsecure all found", 2, JsonImport.stripAllowInsecure(deep));
        is("nothing left behind", false, Json.write(deep).contains("allowInsecure"));
    }

    // --------------------------------------------------------------- plumbing

    private interface Thrower {
        void run();
    }

    private interface Producer {
        JsonImport.Result get() throws Exception;
    }

    private static void is(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        record(name, ok, "expected <" + expected + "> but was <" + actual + ">");
    }

    private static void notNull(String name, Object value) {
        record(name, value != null, "was null");
    }

    private static void isNull(String name, Object value) {
        record(name, value == null, "expected null but was <" + value + ">");
    }

    private static void threw(String name, Thrower t) {
        try {
            t.run();
            record(name, false, "no exception was thrown");
        } catch (RuntimeException expected) {
            record(name, true, "");
        }
    }

    private static JsonImport.Result must(String name, Producer p) {
        try {
            JsonImport.Result r = p.get();
            record(name, true, "");
            return r;
        } catch (Exception e) {
            record(name, false, "threw " + e);
            return null;
        }
    }

    private static void rejected(String name, String text) {
        try {
            JsonImport.normalise(text, "warning");
            record(name, false, "it was accepted");
        } catch (JsonImport.InvalidConfigException expected) {
            record(name, true, "");
        } catch (RuntimeException e) {
            record(name, false, "threw the wrong kind of error: " + e);
        }
    }

    private static void record(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failureCount++;
            failures.append("  FAILED: ").append(name).append(" -- ").append(detail).append('\n');
        }
    }

    /** Message describing every failure, for the JUnit mirror to report. */
    public static String report() {
        return failures.toString();
    }

    public static int passedCount() {
        return passed;
    }
}
