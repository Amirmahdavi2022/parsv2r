package com.parsv2r.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import com.parsv2r.core.JsonImport;

/**
 * One box that takes anything: a share link, a pile of them, a base64 subscription body, or a
 * whole Xray JSON config. Working out which it is, is the app's job rather than the user's.
 */
public final class AddConfigActivity extends AppCompatActivity {

    private TextInputEditText input;
    private ConfigStore store;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_config);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_add);
        }
        store = new ConfigStore(this);
        input = findViewById(R.id.config_input);

        MaterialButton paste = findViewById(R.id.paste_button);
        paste.setOnClickListener(v -> pasteFromClipboard());

        MaterialButton save = findViewById(R.id.save_button);
        save.setOnClickListener(v -> save());

        prefillFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        prefillFromIntent(intent);
    }

    private void prefillFromIntent(Intent intent) {
        if (intent == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        }
        if (text != null && !text.isEmpty()) {
            input.setText(text);
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            toast(getString(R.string.error_clipboard_empty));
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(getString(R.string.error_clipboard_empty));
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            toast(getString(R.string.error_clipboard_empty));
            return;
        }
        input.setText(text.toString());
    }

    private void save() {
        String text = input.getText() == null ? "" : input.getText().toString();
        try {
            int added = store.add(text);
            if (added == 0) {
                toast(getString(R.string.error_already_saved));
                return;
            }
            Snackbar.make(findViewById(R.id.root),
                    getResources().getQuantityString(R.plurals.added_configs, added, added),
                    Snackbar.LENGTH_SHORT).show();
            input.postDelayed(this::finish, 700L);
        } catch (JsonImport.InvalidConfigException e) {
            toast(getString(R.string.error_bad_config, e.getMessage()));
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void toast(String message) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show();
    }
}
