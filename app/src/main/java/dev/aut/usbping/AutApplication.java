package dev.aut.usbping;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class AutApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
