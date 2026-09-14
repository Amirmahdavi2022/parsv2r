package com.parsv2r.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.color.DynamicColors;

/**
 * Applies the saved language at startup.
 *
 * <p>AppCompat's per-app locales are used rather than swapping the base context by hand: they
 * survive configuration changes, they are what the system settings screen shows, and they do not
 * leave half the activity stack in the previous language after a switch.
 */
public final class ParsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyLanguage(Prefs.language(this));
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    static void applyLanguage(String tag) {
        if (tag == null || tag.isEmpty() || "system".equals(tag)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
        }
    }
}
