package dev.aut.usbping;

import android.app.Activity;
import android.os.Bundle;

/** Silently consumes AOA attachment events without opening the control UI. */
public final class AccessoryAttachActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}
