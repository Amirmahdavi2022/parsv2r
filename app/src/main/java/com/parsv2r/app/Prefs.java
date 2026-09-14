package com.parsv2r.app;

import android.content.Context;
import android.content.SharedPreferences;

/** The handful of settings the app keeps, in one place so the keys cannot drift. */
final class Prefs {

    private static final String NAME = "parsv2r-settings";

    static final String KEY_LANGUAGE = "language";
    static final String KEY_LOG_LEVEL = "log_level";
    static final String KEY_BYPASS_LAN = "bypass_lan";

    private Prefs() {
    }

    static SharedPreferences of(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** "system", "en" or "fa". */
    static String language(Context context) {
        return of(context).getString(KEY_LANGUAGE, "system");
    }

    static void setLanguage(Context context, String tag) {
        of(context).edit().putString(KEY_LANGUAGE, tag).apply();
    }

    static String logLevel(Context context) {
        return of(context).getString(KEY_LOG_LEVEL, "warning");
    }

    static void setLogLevel(Context context, String level) {
        of(context).edit().putString(KEY_LOG_LEVEL, level).apply();
    }

    static boolean bypassLan(Context context) {
        return of(context).getBoolean(KEY_BYPASS_LAN, true);
    }

    static void setBypassLan(Context context, boolean value) {
        of(context).edit().putBoolean(KEY_BYPASS_LAN, value).apply();
    }
}
