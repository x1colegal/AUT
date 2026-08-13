package dev.aut.usbping;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;

/** Receives AOA attachment without forcing the control UI over other apps. */
public final class AccessoryAttachActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AutVpnService.isActive(this)) {
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            UsbAccessory accessory = getAccessory(getIntent());
            if (accessory != null) open.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            startActivity(open);
        }
        finish();
    }

    private static UsbAccessory getAccessory(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }
}
