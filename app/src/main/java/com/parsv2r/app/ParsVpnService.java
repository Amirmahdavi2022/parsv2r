package com.parsv2r.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.parsv2r.core.JsonImport;
import com.parsv2r.core.XrayConfig;

import java.io.File;
import java.io.IOException;

/**
 * Brings the tunnel up and keeps it up.
 *
 * <p>The order matters and is deliberate: the proxy core is started and proved to be listening
 * <em>before</em> the VPN interface is established. Establishing first would capture every app's
 * traffic and then discover the core never came up, which looks to the user like the internet
 * breaking rather than a server failing.
 */
public final class ParsVpnService extends VpnService {

    private static final String TAG = "ParsV2R/Vpn";

    static final String ACTION_CONNECT = "com.parsv2r.app.CONNECT";
    static final String ACTION_DISCONNECT = "com.parsv2r.app.DISCONNECT";
    static final String ACTION_STATE = "com.parsv2r.app.STATE";

    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_LABEL = "label";

    static final String STATE_IDLE = "idle";
    static final String STATE_CONNECTING = "connecting";
    static final String STATE_CONNECTED = "connected";
    static final String STATE_ERROR = "error";

    private static final String CHANNEL_ID = "parsv2r.tunnel";
    private static final int NOTIFICATION_ID = 0x9001;

    /** Matches the address hev's bridge assumes for its side of the interface. */
    private static final String TUN_ADDRESS = "198.18.0.1";
    private static final int TUN_PREFIX = 32;
    private static final int TUN_MTU = 8500;
    private static final long CORE_TIMEOUT_MS = 20_000L;

    private static volatile String currentState = STATE_IDLE;
    private static volatile String currentLabel = "";

    private ParcelFileDescriptor tun;
    private XrayProcess core;
    private Thread worker;

    static String state() {
        return currentState;
    }

    static String label() {
        return currentLabel;
    }

    static boolean isRunning() {
        return STATE_CONNECTED.equals(currentState) || STATE_CONNECTING.equals(currentState);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_CONNECT : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            shutdown(STATE_IDLE, null);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            return START_STICKY;
        }
        startForegroundNotice(getString(R.string.state_connecting));
        broadcast(STATE_CONNECTING, null);
        worker = new Thread(this::connect, "tunnel-start");
        worker.start();
        return START_STICKY;
    }

    private void connect() {
        ConfigStore store = new ConfigStore(this);
        SavedConfig entry = store.active();
        if (entry == null) {
            fail(getString(R.string.error_no_config));
            return;
        }
        currentLabel = entry.label;

        String json;
        try {
            json = store.buildXrayConfig(entry, Prefs.logLevel(this), Prefs.bypassLan(this));
        } catch (JsonImport.InvalidConfigException e) {
            fail(e.getMessage());
            return;
        }

        core = new XrayProcess(this);
        File configFile;
        try {
            configFile = core.writeConfig(json);
        } catch (IOException e) {
            fail(getString(R.string.error_write_config));
            return;
        }

        String reason = core.start(configFile, CORE_TIMEOUT_MS);
        if (reason != null) {
            fail(reason);
            return;
        }

        try {
            tun = buildInterface().establish();
        } catch (Exception e) {
            Log.e(TAG, "the interface would not come up", e);
            fail(getString(R.string.error_interface));
            return;
        }
        if (tun == null) {
            // establish() returns null when VPN permission was revoked while we were starting.
            fail(getString(R.string.error_permission));
            return;
        }

        try {
            File bridgeConfig = TunBridge.writeConfig(getFilesDir(), XrayConfig.SOCKS_PORT, TUN_MTU);
            if (!TunBridge.start(bridgeConfig.getAbsolutePath(), tun.getFd())) {
                fail(getString(R.string.error_bridge));
                return;
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            Log.e(TAG, "the bridge would not start", e);
            fail(getString(R.string.error_bridge));
            return;
        }

        currentState = STATE_CONNECTED;
        broadcast(STATE_CONNECTED, null);
        updateNotice(getString(R.string.state_connected_to, entry.label));
        Log.i(TAG, "connected via " + entry.label);
    }

    private Builder buildInterface() {
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));
        builder.setMtu(TUN_MTU);
        builder.addAddress(TUN_ADDRESS, TUN_PREFIX);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("8.8.8.8");
        builder.setBlocking(false);

        // Our own traffic must not be captured, or the proxy core would be tunnelling itself.
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
            // Only fails if our own package is somehow unknown, which cannot happen here.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        PendingIntent configure = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(configure);
        return builder;
    }

    private void fail(String message) {
        Log.w(TAG, "connect failed: " + message);
        shutdown(STATE_ERROR, message);
    }

    private void shutdown(String state, String message) {
        TunBridge.stop();
        if (core != null) {
            core.stop();
            core = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
                // Closing a descriptor that is already gone is not a problem.
            }
            tun = null;
        }
        currentState = state;
        broadcast(state, message);
        if (!STATE_ERROR.equals(state)) {
            currentLabel = "";
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "the system revoked our VPN permission");
        shutdown(STATE_IDLE, null);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        TunBridge.stop();
        if (core != null) core.stop();
        currentState = STATE_IDLE;
        super.onDestroy();
    }

    // ---------------------------------------------------------- notifications

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_tunnel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification notice(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ParsVpnService.class).setAction(ACTION_DISCONNECT);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tunnel)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.action_disconnect), stop)
                .build();
    }

    private void startForegroundNotice(String text) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notice(text), type);
    }

    private void updateNotice(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notice(text));
    }

    private void broadcast(String state, String message) {
        currentState = state;
        Intent intent = new Intent(ACTION_STATE);
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_LABEL, currentLabel);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // -------------------------------------------------------------- launching

    static void start(Context context) {
        Intent intent = new Intent(context, ParsVpnService.class).setAction(ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.startService(new Intent(context, ParsVpnService.class).setAction(ACTION_DISCONNECT));
    }
}
