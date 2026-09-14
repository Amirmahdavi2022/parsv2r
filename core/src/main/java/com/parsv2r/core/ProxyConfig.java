package com.parsv2r.core;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One proxy server, parsed out of a share link.
 *
 * <p>Everything here is deliberately forgiving: share links are copied out of Telegram messages,
 * wrapped by mail clients, double-encoded by web panels and truncated by people's thumbs. A line
 * that cannot be understood returns null rather than throwing, so one bad line in a subscription
 * of four hundred does not cost the other three hundred and ninety nine.
 */
public final class ProxyConfig {

    public static final String VLESS = "vless";
    public static final String VMESS = "vmess";
    public static final String TROJAN = "trojan";
    public static final String SHADOWSOCKS = "shadowsocks";
    public static final String SOCKS = "socks";

    public final String protocol;
    public final String host;
    public final int port;
    /** UUID for vless/vmess, password for trojan/shadowsocks, username for socks. */
    public final String id;
    /** Shadowsocks cipher, or the socks password. Null elsewhere. */
    public final String secret;
    public final String remark;
    /** Transport and security settings, lower-cased keys, already percent-decoded. */
    public final Map<String, String> params;

    private ProxyConfig(String protocol, String host, int port, String id, String secret,
                        String remark, Map<String, String> params) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.id = id;
        this.secret = secret;
        this.remark = remark;
        this.params = params;
    }

    public String param(String key) {
        return params.get(key);
    }

    public String param(String key, String fallback) {
        String v = params.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** Transport: tcp, ws, grpc, http, httpupgrade, xhttp, quic, kcp. */
    public String network() {
        return param("type", param("net", "tcp")).toLowerCase(Locale.ROOT);
    }

    /** Security layer: none, tls, reality. */
    public String security() {
        String s = param("security", "");
        if (s.isEmpty()) {
            // vmess links carry it as tls=tls or tls=""
            s = param("tls", "");
        }
        s = s.toLowerCase(Locale.ROOT);
        if (s.equals("1") || s.equals("true")) return "tls";
        return s.isEmpty() ? "none" : s;
    }

    /**
     * A stable identity used to remove duplicates. Feeds republish the same server under a
     * different remark constantly, so the remark is deliberately not part of it.
     */
    public String identity() {
        return protocol + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|"
                + (id == null ? "" : id) + "|" + network() + "|" + security();
    }

    public String displayName() {
        if (remark != null && !remark.isEmpty()) return remark;
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return protocol + "://" + host + ":" + port + " (" + network() + "/" + security() + ")";
    }

    // ------------------------------------------------------------ single link

    /** Parses one share link. Returns null if the line is not a config we can use. */
    public static ProxyConfig parse(String line) {
        if (line == null) return null;
        String raw = line.trim();
        if (raw.isEmpty()) return null;
        int scheme = raw.indexOf("://");
        if (scheme <= 0) return null;
        String protocol = raw.substring(0, scheme).toLowerCase(Locale.ROOT);
        String body = raw.substring(scheme + 3);
        try {
            switch (protocol) {
                case "vless":
                    return parseUserinfoStyle(VLESS, body);
                case "trojan":
                    return parseUserinfoStyle(TROJAN, body);
                case "vmess":
                    return parseVmess(body);
                case "ss":
                case "shadowsocks":
                    return parseShadowsocks(body);
                case "socks":
                case "socks5":
                    return parseSocks(body);
                default:
                    return null;
            }
        } catch (RuntimeException ignored) {
            // A malformed link is data, not a bug. Drop it.
            return null;
        }
    }

    /** vless:// and trojan:// share one shape: credential@host:port?params#remark */
    private static ProxyConfig parseUserinfoStyle(String protocol, String body) {
        Split s = Split.of(body);
        int at = s.main.lastIndexOf('@');
        if (at <= 0) return null;
        String credential = decode(s.main.substring(0, at));
        HostPort hp = HostPort.of(s.main.substring(at + 1));
        if (hp == null) return null;
        if (credential.isEmpty()) return null;
        return new ProxyConfig(protocol, hp.host, hp.port, credential, null, s.remark, s.params);
    }

    /** vmess:// carries a base64 JSON object rather than a URI. */
    private static ProxyConfig parseVmess(String body) {
        String json = decodeBase64(stripQueryAndFragment(body));
        if (json == null) return null;
        Map<String, Object> o;
        try {
            o = Json.parseObject(json);
        } catch (RuntimeException ignored) {
            return null;
        }
        String host = Json.str(o.get("add"));
        int port = Json.intOf(o.get("port"), 0);
        String id = Json.str(o.get("id"));
        if (host == null || host.isEmpty() || port <= 0 || port > 65535 || id == null) return null;

        Map<String, String> params = new LinkedHashMap<>();
        putIfPresent(params, "type", o.get("net"));
        putIfPresent(params, "headerType", o.get("type"));
        putIfPresent(params, "host", o.get("host"));
        putIfPresent(params, "path", o.get("path"));
        putIfPresent(params, "security", o.get("tls"));
        putIfPresent(params, "sni", o.get("sni"));
        putIfPresent(params, "alpn", o.get("alpn"));
        putIfPresent(params, "fp", o.get("fp"));
        putIfPresent(params, "serviceName", o.get("path"));
        putIfPresent(params, "encryption", o.get("scy"));
        putIfPresent(params, "aid", o.get("aid"));
        String remark = Json.str(o.get("ps"));
        return new ProxyConfig(VMESS, host, port, id, null, remark, params);
    }

    /**
     * ss:// comes in two shapes: the modern {@code base64(method:password)@host:port} and the
     * legacy {@code base64(method:password@host:port)}. Both are in the wild.
     */
    private static ProxyConfig parseShadowsocks(String body) {
        Split s = Split.of(body);
        String main = s.main;
        int at = main.lastIndexOf('@');
        String method;
        String password;
        String hostPort;

        if (at > 0) {
            String userinfo = main.substring(0, at);
            hostPort = main.substring(at + 1);
            String decoded = looksBase64(userinfo) ? decodeBase64(userinfo) : decode(userinfo);
            if (decoded == null) return null;
            int colon = decoded.indexOf(':');
            if (colon < 0) return null;
            method = decoded.substring(0, colon);
            password = decoded.substring(colon + 1);
        } else {
            String decoded = decodeBase64(main);
            if (decoded == null) return null;
            int lastAt = decoded.lastIndexOf('@');
            if (lastAt <= 0) return null;
            String userinfo = decoded.substring(0, lastAt);
            hostPort = decoded.substring(lastAt + 1);
            int colon = userinfo.indexOf(':');
            if (colon < 0) return null;
            method = userinfo.substring(0, colon);
            password = userinfo.substring(colon + 1);
        }

        HostPort hp = HostPort.of(hostPort);
        if (hp == null || method.isEmpty()) return null;
        return new ProxyConfig(SHADOWSOCKS, hp.host, hp.port, password, method, s.remark, s.params);
    }

    private static ProxyConfig parseSocks(String body) {
        Split s = Split.of(body);
        String main = s.main;
        String user = "";
        String pass = "";
        int at = main.lastIndexOf('@');
        if (at > 0) {
            String userinfo = main.substring(0, at);
            main = main.substring(at + 1);
            String decoded = looksBase64(userinfo) ? decodeBase64(userinfo) : decode(userinfo);
            if (decoded != null) {
                int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    user = decoded.substring(0, colon);
                    pass = decoded.substring(colon + 1);
                } else {
                    user = decoded;
                }
            }
        }
        HostPort hp = HostPort.of(main);
        if (hp == null) return null;
        return new ProxyConfig(SOCKS, hp.host, hp.port, user, pass, s.remark, s.params);
    }

    // --------------------------------------------------------------- document

    /**
     * Pulls every usable config out of a pasted blob: a single link, a list of links, or a
     * base64-wrapped subscription body. Duplicates are removed and order is preserved.
     */
    public static List<ProxyConfig> parseDocument(String document) {
        List<ProxyConfig> out = new ArrayList<>();
        if (document == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : expand(document)) {
            ProxyConfig c = parse(line);
            if (c == null) continue;
            if (seen.add(c.identity())) out.add(c);
        }
        return out;
    }

    /**
     * Returns the lines to try. A subscription URL usually answers with one long base64 blob, so
     * if the text contains no scheme at all we try decoding the whole thing once before giving up.
     */
    private static List<String> expand(String document) {
        List<String> lines = new ArrayList<>();
        String text = document.trim();
        if (text.isEmpty()) return lines;

        if (!containsScheme(text)) {
            String decoded = decodeBase64(text.replaceAll("\\s+", ""));
            if (decoded != null && containsScheme(decoded)) {
                text = decoded;
            }
        }
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        return lines;
    }

    private static boolean containsScheme(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("vless://") || lower.contains("vmess://") || lower.contains("trojan://")
                || lower.contains("ss://") || lower.contains("socks://") || lower.contains("socks5://");
    }

    // ---------------------------------------------------------------- helpers

    /** The part before '?', the query params, and the '#' remark. */
    private static final class Split {
        final String main;
        final Map<String, String> params;
        final String remark;

        private Split(String main, Map<String, String> params, String remark) {
            this.main = main;
            this.params = params;
            this.remark = remark;
        }

        static Split of(String body) {
            String remark = "";
            int hash = body.indexOf('#');
            if (hash >= 0) {
                remark = decode(body.substring(hash + 1));
                body = body.substring(0, hash);
            }
            Map<String, String> params = new LinkedHashMap<>();
            int q = body.indexOf('?');
            if (q >= 0) {
                String query = body.substring(q + 1);
                body = body.substring(0, q);
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String key = eq < 0 ? pair : pair.substring(0, eq);
                    String value = eq < 0 ? "" : pair.substring(eq + 1);
                    params.put(decode(key).toLowerCase(Locale.ROOT), decode(value));
                }
            }
            return new Split(body, params, remark);
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort of(String text) {
            if (text == null) return null;
            String t = text.trim();
            if (t.isEmpty()) return null;
            // strip anything a sloppy link left behind
            int slash = t.indexOf('/');
            if (slash >= 0) t = t.substring(0, slash);
            String host;
            String portText;
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                if (close < 0) return null;
                host = t.substring(1, close);
                int colon = t.indexOf(':', close);
                if (colon < 0) return null;
                portText = t.substring(colon + 1);
            } else {
                int colon = t.lastIndexOf(':');
                if (colon <= 0) return null;
                host = t.substring(0, colon);
                portText = t.substring(colon + 1);
            }
            int port;
            try {
                port = Integer.parseInt(portText.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (host.isEmpty() || port <= 0 || port > 65535) return null;
            return new HostPort(host, port);
        }
    }

    private static String stripQueryAndFragment(String body) {
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        int q = body.indexOf('?');
        if (q >= 0) body = body.substring(0, q);
        return body.trim();
    }

    private static void putIfPresent(Map<String, String> params, String key, Object value) {
        String s = Json.str(value);
        if (s != null && !s.isEmpty()) params.put(key, s);
    }

    /**
     * Percent-decodes, but only when a '%' is actually present.
     *
     * <p>This guard matters: URLDecoder turns a bare '+' into a space, and '+' is a normal
     * character in base64 passwords. Decoding unconditionally silently corrupts credentials, and
     * the result is a server that looks fine and can never authenticate.
     */
    static String decode(String text) {
        if (text == null) return "";
        if (text.indexOf('%') < 0) return text;
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            return text;
        }
    }

    /** Decodes base64 in either alphabet, with or without padding. Returns null on failure. */
    static String decodeBase64(String text) {
        if (text == null) return null;
        String t = text.trim().replaceAll("\\s+", "");
        if (t.isEmpty()) return null;
        if (t.indexOf('%') >= 0) t = decode(t);
        t = t.replace('-', '+').replace('_', '/');
        int pad = t.length() % 4;
        if (pad == 1) return null;
        if (pad != 0) {
            StringBuilder sb = new StringBuilder(t);
            while (sb.length() % 4 != 0) sb.append('=');
            t = sb.toString();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(t);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean looksBase64(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.indexOf(':') >= 0) return false; // already method:password in the clear
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '-' || c == '_' || c == '=' || c == '%';
            if (!ok) return false;
        }
        return true;
    }
}
