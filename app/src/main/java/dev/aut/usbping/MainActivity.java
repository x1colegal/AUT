package dev.aut.usbping;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 100;
    private static final String ACTION_USB_PERMISSION = "dev.aut.usbping.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbAccessory accessory;
    private BroadcastReceiver receiver;
    private TextView statusView;
    private TextView eventLog;
    private TextView pathSupport;
    private TextView autPingView;
    private TextView icmp6PingView;
    private CircularProgressIndicator linkIndicator;
    private MaterialButtonToggleGroup pathToggle;
    private String pendingMode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bindViews();
        restoreEventLog();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        configureActions();
        registerStatusReceiver();
        discoverAccessory(getIntent());
    }

    private void bindViews() {
        statusView = findViewById(R.id.status_text);
        eventLog = findViewById(R.id.event_log);
        pathSupport = findViewById(R.id.path_support);
        autPingView = findViewById(R.id.aut_ping_stats);
        icmp6PingView = findViewById(R.id.icmp6_ping_stats);
        linkIndicator = findViewById(R.id.link_indicator);
        pathToggle = findViewById(R.id.path_toggle);
    }

    private void configureActions() {
        pathToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            updatePathSupport(checkedId);
        });
        configureModeActions();
    }

    private void updatePathSupport(int checkedId) {
        pathSupport.setText(checkedId == R.id.path_direct
                ? R.string.direct_support
                : R.string.reliable_usb_support);
    }

    private void configureModeActions() {
        findViewById(R.id.mode_ping).setOnClickListener(
                view -> selectMode(AutVpnService.MODE_PING));
        findViewById(R.id.mode_icmp6).setOnClickListener(
                view -> selectMode(AutVpnService.MODE_ICMP6));
        findViewById(R.id.mode_internet).setOnClickListener(
                view -> selectMode(AutVpnService.MODE_INTERNET));
        findViewById(R.id.stop_button).setOnClickListener(view -> stopAut());
        findViewById(R.id.clear_diagnostics).setOnClickListener(view -> {
            Intent clear = new Intent(this, AutVpnService.class)
                    .setAction(AutVpnService.ACTION_CLEAR_DIAGNOSTICS);
            startService(clear);
            renderPingStats("AUTPing · waiting",
                    "ICMPv6 Ping · waiting for an IP lease");
        });
        findViewById(R.id.clear_history).setOnClickListener(view -> {
            AutEventLog.clear(this);
            renderOperationalState();
        });
    }

    private void registerStatusReceiver() {
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    accessory = getAccessory(intent);
                    showStatus(intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? "USB permission granted" : "USB permission denied");
                } else if (AutVpnService.ACTION_STATUS.equals(intent.getAction())) {
                    String message = intent.getStringExtra(AutVpnService.EXTRA_STATUS);
                    if (message != null) renderEventLog();
                    renderPingStats(intent.getStringExtra(AutVpnService.EXTRA_AUT_PING),
                            intent.getStringExtra(AutVpnService.EXTRA_ICMP6_PING));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(AutVpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else registerReceiverLegacy(filter);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiverLegacy(IntentFilter filter) {
        registerReceiver(receiver, filter);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        discoverAccessory(intent);
    }

    private void discoverAccessory(Intent intent) {
        accessory = getAccessory(intent);
        if (accessory == null) {
            UsbAccessory[] list = usbManager.getAccessoryList();
            if (list != null && list.length > 0) accessory = list[0];
        }
        if (accessory == null) {
            if (AutVpnService.isActive(this)) {
                renderOperationalState();
                return;
            }
            showStatus("Waiting for the AUT USB accessory");
            return;
        }
        if (usbManager.hasPermission(accessory)) {
            if (AutVpnService.isActive(this)) {
                renderOperationalState();
                return;
            }
            showStatus("USB ready — choose a mode");
            linkIndicator.setVisibility(View.INVISIBLE);
            return;
        }
        Intent permission = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, permission,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        usbManager.requestPermission(accessory, pending);
        showStatus("Waiting for USB permission");
    }

    private UsbAccessory getAccessory(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }

    private void selectMode(String mode) {
        if (accessory == null || !usbManager.hasPermission(accessory)) {
            discoverAccessory(getIntent());
            showStatus("USB is not ready yet");
            return;
        }
        pendingMode = mode;
        if (AutVpnService.MODE_PING.equals(mode)) startAut();
        else {
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, VPN_REQUEST);
            else startAut();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VPN_REQUEST && result == RESULT_OK) startAut();
        else if (request == VPN_REQUEST) showStatus("VPN permission denied");
    }

    private void startAut() {
        int checked = pathToggle.getCheckedButtonId();
        String path = checked == R.id.path_ratp ? "ratp" : "direct";
        Intent intent = new Intent(this, AutVpnService.class)
                .setAction(AutVpnService.ACTION_START)
                .putExtra(AutVpnService.EXTRA_MODE, pendingMode)
                .putExtra(AutVpnService.EXTRA_TRANSPORT, path);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        linkIndicator.setVisibility(View.VISIBLE);
        showStatus("Starting " + pendingMode + " mode…");
    }

    private void stopAut() {
        startService(new Intent(this, AutVpnService.class).setAction(AutVpnService.ACTION_STOP));
        linkIndicator.setVisibility(View.INVISIBLE);
        showStatus("AUT stopped");
    }

    private void showStatus(String message) {
        AutEventLog.append(this, message);
        renderEventLog();
    }

    private void restoreEventLog() {
        renderOperationalState();
        renderPingStats(null, null);
    }

    private void renderOperationalState() {
        boolean active = AutVpnService.isActive(this);
        if (active) {
            statusView.setText(AutVpnService.runtimeStatus(this));
            linkIndicator.setVisibility(View.VISIBLE);
            int button = "ratp".equals(AutVpnService.selectedTransport(this))
                    ? R.id.path_ratp : R.id.path_direct;
            if (pathToggle.getCheckedButtonId() != button) pathToggle.check(button);
        } else {
            statusView.setText(AutEventLog.latest(this));
            linkIndicator.setVisibility(View.INVISIBLE);
        }
        eventLog.setText(AutEventLog.history(this));
    }

    private void renderEventLog() {
        statusView.setText(AutEventLog.latest(this));
        eventLog.setText(AutEventLog.history(this));
    }

    private void renderPingStats(String aut, String icmp6) {
        android.content.SharedPreferences preferences = getSharedPreferences(
                "aut_service", MODE_PRIVATE);
        autPingView.setText(aut == null
                ? preferences.getString(AutVpnService.PREF_AUT_PING, "AUTPing · waiting") : aut);
        icmp6PingView.setText(icmp6 == null
                ? preferences.getString(AutVpnService.PREF_ICMP6_PING,
                        "ICMPv6 Ping · waiting for an IP lease") : icmp6);
    }

    @Override protected void onResume() {
        super.onResume();
        renderOperationalState();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
