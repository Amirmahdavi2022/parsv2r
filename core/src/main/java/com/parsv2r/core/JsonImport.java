package com.parsv2r.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accepts a raw Xray configuration written by hand or handed out by a panel, and makes it safe to
 * run underneath the tunnel.
 *
 * <p>People paste these in expecting them to just work, and mostly they nearly do. Three things
 * have to be corrected first, and all three fail quietly if they are not:
 *
 * <ol>
 *   <li><b>The inbound.</b> The tun bridge dials one fixed loopback port. A pasted config usually
 *       listens somewhere else, or on no port at all. Whatever it says, the SOCKS inbound on
 *       {@link XrayConfig#SOCKS_PORT} is put back, and anything squatting on that port or tag is
 *       removed rather than left to collide.
 *   <li><b>DNS.</b> A Go binary on Android has no {@code /etc/resolv.conf}. A config with no dns
 *       block resolves nothing, and the symptom is every hostname server looking dead.
 *   <li><b>{@code allowInsecure}.</b> Current Xray removed it and refuses the entire config the
 *       moment it sees it. Older panels still emit it everywhere, so it is stripped, at any depth.
 * </ol>
 *
 * <p>Everything else the user wrote is left exactly as it was. Their routing rules, their balancer,
 * their five outbounds and their observatory are their business.
 */
public final class JsonImport {

    /** What came back: the config to run, a label for the list, and anything worth telling them. */
    public static final class Result {
        public final String json;
        public final String label;
        public final List<String> notes;

        Result(String json, String label, List<String> notes) {
            this.json = json;
            this.label = label;
            this.notes = notes;
        }
    }

    /** Raised when the text cannot be run at all, with a message meant for a person to read. */
    public static class InvalidConfigException extends Exception {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    private JsonImport() {
    }

    /** True if the text looks like a JSON config rather than a share link. */
    public static boolean looksLikeJson(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("{") || t.startsWith("//") || t.startsWith("/*");
    }

    public static Result normalise(String text, String logLevel) throws InvalidConfigException {
        if (text == null || text.trim().isEmpty()) {
            throw new InvalidConfigException("empty");
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(text);
        } catch (Json.SyntaxException e) {
            throw new InvalidConfigException(e.getMessage());
        } catch (RuntimeException e) {
            throw new InvalidConfigException("not valid JSON");
        }

        List<String> notes = new ArrayList<>();

        List<Object> outbounds = outboundsOf(root);
        if (outbounds == null || outbounds.isEmpty()) {
            throw new InvalidConfigException("no outbounds");
        }
        Map<String, Object> first = firstRealOutbound(outbounds);
        if (first == null) {
            throw new InvalidConfigException("no usable outbound");
        }

        int stripped = stripAllowInsecure(root);
        if (stripped > 0) {
            notes.add("allowInsecure removed (" + stripped + ")");
        }

        Map<String, Object> log = Json.obj(root.get("log"));
        if (log == null) {
            log = Json.newObject();
            root.put("log", log);
        }
        log.put("loglevel", XrayConfig.normaliseLogLevel(logLevel));

        if (Json.obj(root.get("dns")) == null) {
            root.put("dns", XrayConfig.dnsBlock());
            notes.add("DNS block added");
        }

        int replaced = forceInbounds(root);
        if (replaced > 0) {
            notes.add("inbound port " + XrayConfig.SOCKS_PORT + " reclaimed (" + replaced + ")");
        }

        // Order matters for readability, not for the core: put inbounds before outbounds.
        reorder(root);

        return new Result(Json.writePretty(root), describe(first), notes);
    }

    // ---------------------------------------------------------------- pieces

    static List<Object> outboundsOf(Map<String, Object> root) {
        List<Object> outbounds = Json.arr(root.get("outbounds"));
        if (outbounds != null) return outbounds;
        // A few generators still emit the single-outbound form.
        Map<String, Object> single = Json.obj(root.get("outbound"));
        if (single != null) {
            outbounds = Json.newArray();
            outbounds.add(single);
            root.remove("outbound");
            root.put("outbounds", outbounds);
            return outbounds;
        }
        return null;
    }

    /** The first outbound that actually goes somewhere, skipping freedom/blackhole/dns. */
    static Map<String, Object> firstRealOutbound(List<Object> outbounds) {
        Map<String, Object> fallback = null;
        for (Object o : outbounds) {
            Map<String, Object> out = Json.obj(o);
            if (out == null) continue;
            if (fallback == null) fallback = out;
            String protocol = lower(Json.str(out.get("protocol")));
            if (protocol == null) continue;
            if (protocol.equals("freedom") || protocol.equals("blackhole") || protocol.equals("dns")) {
                continue;
            }
            return out;
        }
        return fallback;
    }

    /**
     * Puts our SOCKS and HTTP inbounds back at the front, dropping anything that would fight them
     * for the same port or tag. Returns how many entries were dropped.
     */
    static int forceInbounds(Map<String, Object> root) {
        List<Object> existing = Json.arr(root.get("inbounds"));
        int removed = 0;
        if (existing != null) {
            Iterator<Object> it = existing.iterator();
            while (it.hasNext()) {
                Map<String, Object> in = Json.obj(it.next());
                if (in == null) {
                    it.remove();
                    removed++;
                    continue;
                }
                int port = Json.intOf(in.get("port"), -1);
                String tag = Json.str(in.get("tag"));
                boolean collides = port == XrayConfig.SOCKS_PORT || port == XrayConfig.HTTP_PORT
                        || XrayConfig.TAG_SOCKS_IN.equals(tag) || XrayConfig.TAG_HTTP_IN.equals(tag);
                if (collides) {
                    it.remove();
                    removed++;
                }
            }
        }
        List<Object> merged = XrayConfig.inbounds();
        if (existing != null) merged.addAll(existing);
        root.put("inbounds", merged);
        return removed;
    }

    /**
     * Deletes every {@code allowInsecure} anywhere in the tree and returns how many were found.
     * Xray rejects the whole config if even one survives, so this is not cosmetic.
     */
    static int stripAllowInsecure(Object node) {
        int count = 0;
        if (node instanceof Map) {
            Map<String, Object> map = Json.obj(node);
            Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
            List<Object> children = new ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                if ("allowInsecure".equalsIgnoreCase(e.getKey())) {
                    it.remove();
                    count++;
                } else {
                    children.add(e.getValue());
                }
            }
            for (Object child : children) count += stripAllowInsecure(child);
        } else if (node instanceof List) {
            for (Object child : Json.arr(node)) count += stripAllowInsecure(child);
        }
        return count;
    }

    /** A short human label for the list: "vless · example.com:443". */
    static String describe(Map<String, Object> outbound) {
        String protocol = Json.str(outbound.get("protocol"));
        if (protocol == null || protocol.isEmpty()) protocol = "json";
        String address = addressOf(outbound);
        return address == null ? protocol : protocol + " · " + address;
    }

    static String addressOf(Map<String, Object> outbound) {
        Map<String, Object> settings = Json.obj(outbound.get("settings"));
        if (settings == null) return null;
        List<Object> vnext = Json.arr(settings.get("vnext"));
        if (vnext != null && !vnext.isEmpty()) return hostPort(Json.obj(vnext.get(0)));
        List<Object> servers = Json.arr(settings.get("servers"));
        if (servers != null && !servers.isEmpty()) return hostPort(Json.obj(servers.get(0)));
        return null;
    }

    private static String hostPort(Map<String, Object> node) {
        if (node == null) return null;
        String address = Json.str(node.get("address"));
        if (address == null || address.isEmpty()) return null;
        int port = Json.intOf(node.get("port"), 0);
        return port > 0 ? address + ":" + port : address;
    }

    private static void reorder(Map<String, Object> root) {
        Object log = root.remove("log");
        Object dns = root.remove("dns");
        Object inbounds = root.remove("inbounds");
        Object outbounds = root.remove("outbounds");
        Map<String, Object> rest = Json.newObject();
        rest.putAll(root);
        root.clear();
        if (log != null) root.put("log", log);
        if (dns != null) root.put("dns", dns);
        if (inbounds != null) root.put("inbounds", inbounds);
        if (outbounds != null) root.put("outbounds", outbounds);
        root.putAll(rest);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
