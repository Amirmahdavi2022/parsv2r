package com.parsv2r.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

public final class SettingsActivity extends AppCompatActivity {

    private static final String[] LANGUAGE_VALUES = {"system", "en", "fa"};
    private static final String[] LOG_VALUES = {"none", "error", "warning", "info", "debug"};

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        TextView languageValue = findViewById(R.id.language_value);
        languageValue.setText(languageLabel(Prefs.language(this)));
        findViewById(R.id.language_row).setOnClickListener(v -> pickLanguage(languageValue));

        TextView logValue = findViewById(R.id.log_value);
        logValue.setText(Prefs.logLevel(this));
        findViewById(R.id.log_row).setOnClickListener(v -> pickLogLevel(logValue));

        MaterialSwitch bypass = findViewById(R.id.bypass_switch);
        bypass.setChecked(Prefs.bypassLan(this));
        bypass.setOnCheckedChangeListener((v, checked) -> Prefs.setBypassLan(this, checked));

        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.app_version));

        findViewById(R.id.channel_row).setOnClickListener(v -> openChannel());
    }

    /**
     * Opens the Telegram channel, falling back to a browser when Telegram is not installed and
     * doing nothing visible when there is no browser either -- an About screen should never be
     * able to crash the app.
     */
    private void openChannel() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.about_channel_url)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Snackbar.make(findViewById(R.id.root), R.string.about_channel_handle,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    private String languageLabel(String tag) {
        switch (tag) {
            case "en":
                return getString(R.string.language_english);
            case "fa":
                return getString(R.string.language_persian);
            default:
                return getString(R.string.language_system);
        }
    }

    private void pickLanguage(TextView value) {
        String[] labels = {
                getString(R.string.language_system),
                getString(R.string.language_english),
                getString(R.string.language_persian),
        };
        int current = indexOf(LANGUAGE_VALUES, Prefs.language(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_language)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    String tag = LANGUAGE_VALUES[which];
                    Prefs.setLanguage(this, tag);
                    value.setText(labels[which]);
                    dialog.dismiss();
                    ParsApp.applyLanguage(tag);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickLogLevel(TextView value) {
        int current = indexOf(LOG_VALUES, Prefs.logLevel(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.setting_log_level)
                .setSingleChoiceItems(LOG_VALUES, current, (dialog, which) -> {
                    Prefs.setLogLevel(this, LOG_VALUES[which]);
                    value.setText(LOG_VALUES[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return 0;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
