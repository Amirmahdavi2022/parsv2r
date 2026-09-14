package com.parsv2r.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Small helpers for asking whether something is actually listening on a local port. */
final class SocksProbe {

    private SocksProbe() {
    }

    static boolean opens(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Polls until the port answers. This is what "connected" is measured against, rather than the
     * core merely having been launched -- a process that exists but never binds is the single most
     * common way a tunnel looks up while carrying nothing.
     */
    static boolean waitForListener(String host, int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (opens(host, port, 400)) return true;
            Thread.sleep(150);
        }
        return false;
    }
}
