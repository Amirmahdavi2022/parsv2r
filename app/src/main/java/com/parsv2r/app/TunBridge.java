package com.parsv2r.app;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import hev.htproxy.TProxyService;

/**
 * The bridge between the VPN interface and the local SOCKS proxy.
 *
 * <p>hev-socks5-tunnel is loaded as a shared library and handed the tun file descriptor directly,
 * so there is no second process and no passing descriptors over a socket. Its configuration is a
 * small YAML file, written here from the schema in the project's own conf/main.yml.
 */
final class TunBridge {

    private static final String TAG = "ParsV2R/Tun";
    private static boolean loaded;

    private TunBridge() {
    }

    static synchronized void load() {
        if (loaded) return;
        System.loadLibrary("hev-socks5-tunnel");
        loaded = true;
    }

    /**
     * Writes the bridge configuration.
     *
     * <p>{@code udp: 'udp'} makes it negotiate a real SOCKS5 UDP ASSOCIATE. That is the right
     * choice here because the proxy underneath is Xray, whose SOCKS inbound implements it; a
     * proxy that does not would black-hole every DNS lookup and every QUIC connection instead.
     */
    static File writeConfig(File dir, int socksPort, int mtu) throws IOException {
        File file = new File(dir, "tun.yml");
        String yaml = "tunnel:\n"
                + "  mtu: " + mtu + "\n"
                + "socks5:\n"
                + "  address: 127.0.0.1\n"
                + "  port: " + socksPort + "\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                + "  task-stack-size: 20480\n"
                + "  log-level: warn\n";
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    static boolean start(String configPath, int fd) {
        load();
        boolean ok = TProxyService.TProxyStartService(configPath, fd);
        Log.i(TAG, ok ? "tun bridge is up" : "tun bridge refused to start");
        return ok;
    }

    static void stop() {
        if (!loaded) return;
        try {
            TProxyService.TProxyStopService();
        } catch (Throwable t) {
            Log.w(TAG, "stopping the tun bridge failed", t);
        }
    }

    static boolean isRunning() {
        if (!loaded) return false;
        try {
            return TProxyService.TProxyIsRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Bytes sent and received, or null when the bridge has never run. */
    static long[] traffic() {
        if (!loaded) return null;
        try {
            long[] stats = TProxyService.TProxyGetStats();
            if (stats == null || stats.length < 4) return null;
            // The array is {tx packets, tx bytes, rx packets, rx bytes}.
            return new long[]{stats[1], stats[3]};
        } catch (Throwable t) {
            return null;
        }
    }
}
