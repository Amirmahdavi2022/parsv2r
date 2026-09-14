package com.parsv2r.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Xray core as a child process.
 *
 * <p>It ships as {@code libxray.so} in the native library directory rather than as a library to
 * link against. Two reasons: Android 10 and up will only execute a binary from that directory, and
 * keeping the core in its own process means it cannot collide with anything else in the app.
 *
 * <p>The core's own output is captured rather than thrown away. A core that dies in the first
 * second usually says exactly why, and discarding that line is the difference between a diagnosis
 * and a shrug.
 */
final class XrayProcess {

    private static final String TAG = "ParsV2R/Xray";
    private static final int LOG_LINES = 200;

    private final Context context;
    private Process process;
    private Thread reader;
    private final List<String> recentOutput = new ArrayList<>();
    private volatile String lastLine = "";

    XrayProcess(Context context) {
        this.context = context.getApplicationContext();
    }

    File binary() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libxray.so");
    }

    boolean available() {
        File f = binary();
        return f.exists() && f.canExecute();
    }

    /** Writes the config where the core can read it, inside the app's own storage. */
    File writeConfig(String json) throws IOException {
        File file = new File(context.getFilesDir(), "xray.json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /**
     * Starts the core and waits until it says it is serving, or until the timeout runs out.
     * Returns null on success, or a short reason to show the user.
     */
    String start(File config, long timeoutMs) {
        if (!available()) {
            return "the proxy core is missing from this build";
        }
        File assets = context.getFilesDir();
        List<String> command = Arrays.asList(binary().getAbsolutePath(), "run", "-c",
                config.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(assets);
        builder.redirectErrorStream(true);
        // Xray looks for geoip.dat and geosite.dat here. Without it the core starts and then fails
        // the first time a routing rule needs them.
        builder.environment().put("XRAY_LOCATION_ASSET", assets.getAbsolutePath());

        CountDownLatch ready = new CountDownLatch(1);
        try {
            process = builder.start();
        } catch (IOException e) {
            Log.e(TAG, "the core would not start", e);
            return "the proxy core would not start";
        }

        reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    remember(line);
                    if (line.contains("started") || line.contains("Xray") && line.contains("started")) {
                        ready.countDown();
                    }
                }
            } catch (IOException ignored) {
                // The stream closing is how a stopped core ends. Not an error.
            }
        }, "xray-output");
        reader.setDaemon(true);
        reader.start();

        try {
            // The core prints its banner almost immediately; what we are really waiting for is the
            // local listener, which is what the tun bridge will dial.
            if (!SocksProbe.waitForListener("127.0.0.1", com.parsv2r.core.XrayConfig.SOCKS_PORT,
                    timeoutMs)) {
                String why = lastLine.isEmpty() ? "it did not open its local port" : lastLine;
                stop();
                return why;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            return "interrupted";
        }
        Log.i(TAG, "core is listening on " + com.parsv2r.core.XrayConfig.SOCKS_PORT);
        return null;
    }

    boolean alive() {
        return process != null && process.isAlive();
    }

    void stop() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
    }

    private synchronized void remember(String line) {
        lastLine = line;
        recentOutput.add(line);
        while (recentOutput.size() > LOG_LINES) {
            recentOutput.remove(0);
        }
        Log.d(TAG, line);
    }

    synchronized List<String> output() {
        return new ArrayList<>(recentOutput);
    }
}
