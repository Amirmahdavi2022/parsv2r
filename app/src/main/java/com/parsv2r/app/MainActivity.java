package com.parsv2r.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {

    private ConfigStore store;
    private MaterialButton connectButton;
    private TextView statusText;
    private TextView activeName;
    private TextView trafficText;
    private View statusDot;
    private LinearLayout serverList;
    private TextView emptyHint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> vpnPermission;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(ParsVpnService.EXTRA_STATE);
            String message = intent.getStringExtra(ParsVpnService.EXTRA_MESSAGE);
            render(state, message);
        }
    };

    private final Runnable trafficTick = new Runnable() {
        @Override
        public void run() {
            updateTraffic();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        store = new ConfigStore(this);

        connectButton = findViewById(R.id.connect_button);
        statusText = findViewById(R.id.status_text);
        statusDot = findViewById(R.id.status_dot);
        activeName = findViewById(R.id.active_name);
        trafficText = findViewById(R.id.traffic_text);
        serverList = findViewById(R.id.server_list);
        emptyHint = findViewById(R.id.empty_hint);

        vpnPermission = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        ParsVpnService.start(this);
                    } else {
                        toast(getString(R.string.error_permission));
                    }
                });

        connectButton.setOnClickListener(v -> toggle());
        ExtendedFloatingActionButton add = findViewById(R.id.add_button);
        add.setOnClickListener(v -> startActivity(new Intent(this, AddConfigActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(stateReceiver, new IntentFilter(ParsVpnService.ACTION_STATE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        render(ParsVpnService.state(), null);
        handler.post(trafficTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(trafficTick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ state

    private void toggle() {
        if (ParsVpnService.isRunning()) {
            ParsVpnService.stop(this);
            return;
        }
        if (store.active() == null) {
            toast(getString(R.string.error_no_config));
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            vpnPermission.launch(consent);
        } else {
            ParsVpnService.start(this);
        }
    }

    private void render(String state, String message) {
        if (state == null) state = ParsVpnService.STATE_IDLE;
        SavedConfig active = store.active();
        activeName.setText(active == null ? getString(R.string.no_server) : active.label);

        switch (state) {
            case ParsVpnService.STATE_CONNECTED:
                statusText.setText(R.string.state_connected);
                connectButton.setText(R.string.action_disconnect);
                statusDot.setBackgroundResource(R.drawable.dot_connected);
                break;
            case ParsVpnService.STATE_CONNECTING:
                statusText.setText(R.string.state_connecting);
                connectButton.setText(R.string.action_cancel);
                statusDot.setBackgroundResource(R.drawable.dot_connecting);
                break;
            case ParsVpnService.STATE_ERROR:
                statusText.setText(message == null ? getString(R.string.state_error) : message);
                connectButton.setText(R.string.action_connect);
                statusDot.setBackgroundResource(R.drawable.dot_error);
                break;
            default:
                statusText.setText(R.string.state_idle);
                connectButton.setText(R.string.action_connect);
                statusDot.setBackgroundResource(R.drawable.dot_idle);
                break;
        }
        if (message != null && ParsVpnService.STATE_ERROR.equals(state)) {
            toast(message);
        }
    }

    private void updateTraffic() {
        long[] traffic = TunBridge.traffic();
        if (traffic == null || !ParsVpnService.isRunning()) {
            trafficText.setText(R.string.traffic_idle);
            return;
        }
        trafficText.setText(getString(R.string.traffic_format,
                humanBytes(traffic[0]), humanBytes(traffic[1])));
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }

    // ------------------------------------------------------------------- list

    private void refreshList() {
        serverList.removeAllViews();
        List<SavedConfig> configs = store.all();
        emptyHint.setVisibility(configs.isEmpty() ? View.VISIBLE : View.GONE);
        SavedConfig active = store.active();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (SavedConfig config : configs) {
            View row = inflater.inflate(R.layout.item_server, (ViewGroup) serverList, false);
            TextView title = row.findViewById(R.id.server_title);
            TextView subtitle = row.findViewById(R.id.server_subtitle);
            ImageView tick = row.findViewById(R.id.server_tick);
            title.setText(config.label);
            subtitle.setText(config.subtitle);
            tick.setVisibility(active != null && active.id.equals(config.id) ? View.VISIBLE : View.INVISIBLE);

            row.setOnClickListener(v -> {
                if (ParsVpnService.isRunning()) {
                    toast(getString(R.string.error_disconnect_first));
                    return;
                }
                store.setActive(config.id);
                refreshList();
                render(ParsVpnService.state(), null);
            });
            row.setOnLongClickListener(v -> {
                confirmRemove(config);
                return true;
            });
            serverList.addView(row);
        }
    }

    private void confirmRemove(SavedConfig config) {
        new AlertDialog.Builder(this)
                .setTitle(config.label)
                .setMessage(R.string.confirm_remove)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_remove, (d, w) -> {
                    store.remove(config.id);
                    refreshList();
                    render(ParsVpnService.state(), null);
                })
                .show();
    }

    private void toast(String message) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show();
    }
}
