package dev.aut.usbping;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

final class AutEventLog {
    private static final String PREFS = "aut_event_log";
    private static final String HISTORY = "history";
    private static final String LATEST = "latest";
    private static final int MAX_LINES = 80;

    private AutEventLog() {}

    static synchronized void append(Context context, String message) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String timestamp = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        String entry = timestamp + "  " + message.replace('\n', ' ');
        String previous = preferences.getString(HISTORY, "");
        String combined = previous.isEmpty() ? entry : previous + "\n" + entry;
        String[] lines = combined.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - MAX_LINES; i < lines.length; i++) {
                if (trimmed.length() > 0) trimmed.append('\n');
                trimmed.append(lines[i]);
            }
            combined = trimmed.toString();
        }
        preferences.edit().putString(HISTORY, combined).putString(LATEST, message).apply();
    }

    static String history(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(HISTORY, "");
    }

    static String latest(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LATEST, "AUT is idle");
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
