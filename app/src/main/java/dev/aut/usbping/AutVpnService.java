package dev.aut.usbping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutVpnService extends VpnService {
    static final String ACTION_START = "dev.aut.usbping.START";
    static final String ACTION_STOP = "dev.aut.usbping.STOP";
    static final String ACTION_CLEAR_DIAGNOSTICS = "dev.aut.usbping.CLEAR_DIAGNOSTICS";
    static final String ACTION_STATUS = "dev.aut.usbping.STATUS";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_AUT_PING = "aut_ping";
    static final String EXTRA_ICMP6_PING = "icmp6_ping";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_TRANSPORT = "transport";
    static final String MODE_PING = "ping-only";
    static final String MODE_ICMP6 = "icmp6-only";
    static final String MODE_INTERNET = "internet-only";

    private static final String CHANNEL = "aut_vpn";
    private static final int NOTIFICATION_ID = 77;
    private static final String PREFS = "aut_service";
    private static final String PREF_MODE = "mode";
    private static final String PREF_TRANSPORT = "transport";
    private static final String PREF_CLIENT_ID = "client_id";
    private static final String PREF_HOST_ID = "host_id";
    static final String PREF_AUT_PING = "aut_ping_status";
    static final String PREF_ICMP6_PING = "icmp6_ping_status";
    private final Object usbWriteLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final AtomicInteger sessionGeneration = new AtomicInteger();
    private final Map<Integer, Long> pings = new ConcurrentHashMap<>();
    private final Map<Integer, Long> icmp6Pings = new ConcurrentHashMap<>();
    private final PingStats autPingStats = new PingStats();
    private final PingStats icmp6PingStats = new PingStats();
    private final int icmp6Identifier = new SecureRandom().nextInt(0x10000);

    private volatile boolean running;
    private boolean pingEnabled;
    private boolean internetEnabled;
    private boolean routeInternetEnabled;
    private boolean icmp6Enabled;
    private String relayProtocol = "direct";
    private ParcelFileDescriptor accessoryFd;
    private ParcelFileDescriptor tunFd;
    private FileInputStream usbInput;
    private FileOutputStream usbOutput;
    private FileInputStream tunInput;
    private FileOutputStream tunOutput;
    private ScheduledExecutorService scheduler;
    private LoopbackRelay relay;
    private long packetsUp;
    private long packetsDown;
    private volatile boolean clientReadyAcknowledged;
    private byte[] clientIpv6;
    private byte[] gatewayIpv6;
    private final AtomicInteger icmp6Sequence = new AtomicInteger(1);

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLEAR_DIAGNOSTICS.equals(intent.getAction())) {
            clearDiagnostics();
            if (!running) stopSelf();
            return running ? START_STICKY : START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            status("AUT stopped");
            shutdownAsync(true);
            return START_NOT_STICKY;
        }
        if (running) shutdown(true);
        String mode = intent == null
                ? getPreferences().getString(PREF_MODE, MODE_INTERNET)
                : intent.getStringExtra(EXTRA_MODE);
        if (!MODE_PING.equals(mode) && !MODE_ICMP6.equals(mode)
                && !MODE_INTERNET.equals(mode)) mode = MODE_INTERNET;
        relayProtocol = intent == null
                ? getPreferences().getString(PREF_TRANSPORT, "direct")
                : intent.getStringExtra(EXTRA_TRANSPORT);
        if (!"tcp".equals(relayProtocol) && !"udp".equals(relayProtocol)) {
            relayProtocol = "direct";
        }
        pingEnabled = MODE_PING.equals(mode);
        routeInternetEnabled = MODE_INTERNET.equals(mode);
        icmp6Enabled = MODE_ICMP6.equals(mode);
        internetEnabled = icmp6Enabled || routeInternetEnabled;
        getPreferences().edit()
                .putString(PREF_MODE, mode)
                .putString(PREF_TRANSPORT, relayProtocol)
                .apply();
        if (routeInternetEnabled) {
            publishDiagnosticState(
                    "AUTPing · disabled in Internet only",
                    "ICMPv6 Ping · disabled in Internet only");
        } else if (pingEnabled) {
            publishDiagnosticState(
                    "AUTPing · starting",
                    "ICMPv6 Ping · disabled in AUTPing only");
        } else {
            publishDiagnosticState(
                    "AUTPing · disabled in ICMPv6 only",
                    "ICMPv6 Ping · requesting an IP lease");
        }
        startForeground(NOTIFICATION_ID, notification("Connecting to USB…"));
        int generation = sessionGeneration.incrementAndGet();
        startTransport(generation);
        return START_REDELIVER_INTENT;
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void startTransport(int generation) {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbAccessory[] list = manager.getAccessoryList();
        if (list == null || list.length == 0) {
            fail("AUT USB accessory not found", generation);
            return;
        }
        accessoryFd = manager.openAccessory(list[0]);
        if (accessoryFd == null) {
            fail("USB permission is missing or the accessory is busy", generation);
            return;
        }
        running = true;
        clientReadyAcknowledged = false;
        usbInput = new FileInputStream(accessoryFd.getFileDescriptor());
        usbOutput = new FileOutputStream(accessoryFd.getFileDescriptor());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        new Thread(() -> usbLoop(generation), "aut-usb-reader").start();
        if (pingEnabled) {
            scheduler.scheduleWithFixedDelay(
                    () -> sendPing(generation), 0, 1, TimeUnit.SECONDS);
        }
        if (internetEnabled) {
            status("USB ready — requesting the dual-stack lease");
            sendFrame(AutProtocol.CONFIG_REQUEST, configRequest(), generation);
            scheduler.schedule(() -> {
                if (isCurrent(generation) && tunFd == null) {
                    fail("Gateway timeout — no DHCP/SLAAC lease received", generation);
                }
            }, 6, TimeUnit.SECONDS);
        } else status("Ping-only link active");
    }

    private byte[] configRequest() {
        android.content.SharedPreferences preferences = getPreferences();
        String clientId = preferences.getString(PREF_CLIENT_ID, null);
        String hostId = preferences.getString(PREF_HOST_ID, null);
        if (clientId == null || hostId == null) {
            clientId = UUID.randomUUID().toString();
            long generated;
            SecureRandom random = new SecureRandom();
            do generated = random.nextLong(); while (generated == 0);
            hostId = String.format(Locale.US, "%016x", generated);
            preferences.edit()
                    .putString(PREF_CLIENT_ID, clientId)
                    .putString(PREF_HOST_ID, hostId)
                    .apply();
        }
        try {
            return new JSONObject()
                    .put("protocol", 3)
                    .put("relay", relayProtocol)
                    .put("client_id", clientId)
                    .put("host_id", hostId)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Could not build the AUT identity", error);
        }
    }

    private void usbLoop(int generation) {
        AutProtocol.Decoder decoder = new AutProtocol.Decoder();
        byte[] buffer = new byte[64 * 1024];
        try {
            while (isCurrent(generation)) {
                int count = usbInput.read(buffer);
                if (count < 0) throw new IOException("end of USB stream");
                if (!isCurrent(generation)) break;
                for (AutProtocol.Frame frame : decoder.feed(buffer, count)) {
                    onFrame(frame, generation);
                }
            }
        } catch (Exception error) {
            if (isCurrent(generation)) {
                fail("USB I/O error: " + usefulMessage(error), generation);
            }
        }
    }

    private void onFrame(AutProtocol.Frame frame, int generation) throws Exception {
        if (!isCurrent(generation)) return;
        if (frame.type == AutProtocol.PONG && pingEnabled) {
            Long began = pings.remove(frame.sequence);
            if (began != null) {
                double rtt = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0;
                autPingStats.received(rtt);
                publishPingStats();
            }
        } else if (frame.type == AutProtocol.CONFIG_RESPONSE && internetEnabled) {
            if (tunFd == null) {
                configureVpn(new JSONObject(new String(
                        frame.payload, StandardCharsets.UTF_8)), generation);
            } else {
                clientReadyAcknowledged = false;
                sendClientReady(generation);
            }
        } else if (frame.type == AutProtocol.CONFIG_REQUIRED && internetEnabled) {
            status("USB session restored — renewing the AUT lease");
            clientReadyAcknowledged = false;
            sendFrame(AutProtocol.CONFIG_REQUEST, configRequest(), generation);
        } else if (frame.type == AutProtocol.CLIENT_READY_ACK && internetEnabled) {
            clientReadyAcknowledged = true;
            status(routeInternetEnabled
                    ? "Internet active · "
                            + (relay == null ? "Direct USB" : relay.description())
                            + " · IPv4 + IPv6"
                    : "ICMPv6 diagnostic link active");
        } else if (frame.type == AutProtocol.IP_PACKET && internetEnabled) {
            if (receiveIcmp6Reply(frame.payload)) return;
            LoopbackRelay current = relay;
            if (current == null) writeIpToTun(frame.payload, generation);
            else current.sendFromUsb(frame.payload);
        }
    }

    private synchronized void configureVpn(JSONObject config, int generation) throws Exception {
        if (!isCurrent(generation) || tunFd != null) return;
        if (!"direct".equals(relayProtocol)) {
            status("Preparing " + relayProtocol.toUpperCase() + " relay on [::1]");
            relay = new LoopbackRelay(
                    this, relayProtocol,
                    packet -> sendIpToUsb(packet, generation),
                    packet -> writeIpToTun(packet, generation));
            relay.start();
        } else status("Preparing direct TUN-to-USB packet path");
        Builder builder = new Builder()
                .setSession("AUT USB")
                .setMtu(config.getInt("mtu"))
                .setBlocking(true);
        JSONObject ipv4 = config.getJSONObject("dhcp4");
        builder.addAddress(ipv4.getString("address"), ipv4.getInt("prefix"));
        JSONObject ipv6 = config.getJSONObject("dhcp6");
        builder.addAddress(ipv6.getString("address"), ipv6.getInt("prefix"));
        clientIpv6 = InetAddress.getByName(ipv6.getString("address")).getAddress();
        gatewayIpv6 = InetAddress.getByName(config.getString("gateway6")).getAddress();
        if (icmp6Enabled) {
            publishIcmp6State("ICMPv6 Ping · armed · sending first Echo Request");
        }
        if (routeInternetEnabled) {
            JSONArray routes = config.getJSONArray("routes");
            for (int i = 0; i < routes.length(); i++) {
                String[] route = routes.getString(i).split("/", 2);
                builder.addRoute(route[0], Integer.parseInt(route[1]));
            }
        } else {
            builder.addRoute(config.getString("gateway6"), 128);
        }
        JSONArray dns = config.getJSONArray("dns");
        for (int i = 0; i < dns.length(); i++) builder.addDnsServer(dns.getString(i));
        status("Establishing the Android dual-stack TUN");
        try {
            tunFd = builder.establish();
        } catch (Exception error) {
            if (relay != null) relay.close();
            relay = null;
            throw new IOException("TUN setup failed: " + usefulMessage(error), error);
        }
        if (tunFd == null) {
            if (relay != null) relay.close();
            relay = null;
            throw new IOException("VpnService.establish() returned null");
        }
        tunInput = new FileInputStream(tunFd.getFileDescriptor());
        tunOutput = new FileOutputStream(tunFd.getFileDescriptor());
        clientReadyAcknowledged = false;
        sendClientReady(generation);
        scheduler.scheduleWithFixedDelay(() -> sendClientReady(generation),
                500, 500, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> sendIcmp6Ping(generation),
                0, 1, TimeUnit.SECONDS);
        new Thread(() -> tunLoop(generation), "aut-tun-reader").start();
        String packetPath = relay == null ? "Direct USB" : relay.description();
        status("TUN ready · waiting for the Linux gateway · " + packetPath);
    }

    private void sendClientReady(int generation) {
        if (!isCurrent(generation) || clientReadyAcknowledged || tunFd == null) return;
        sendFrame(AutProtocol.CLIENT_READY, new byte[0], generation);
    }

    private void tunLoop(int generation) {
        byte[] packet = new byte[65535];
        try {
            while (isCurrent(generation)) {
                int count = tunInput.read(packet);
                if (count < 0) throw new IOException("end of TUN stream");
                if (!isCurrent(generation)) break;
                byte[] copy = new byte[count];
                System.arraycopy(packet, 0, copy, 0, count);
                LoopbackRelay current = relay;
                if (current == null) sendIpToUsb(copy, generation);
                else current.sendFromVpn(copy);
            }
        } catch (IOException error) {
            if (isCurrent(generation)) {
                fail("VPN I/O error: " + usefulMessage(error), generation);
            }
        }
    }

    private void sendIpToUsb(byte[] packet, int generation) {
        if (!isCurrent(generation)) return;
        sendFrame(AutProtocol.IP_PACKET, packet, generation);
        packetsUp++;
    }

    private void writeIpToTun(byte[] packet, int generation) throws IOException {
        if (!isCurrent(generation)) return;
        FileOutputStream output = tunOutput;
        if (output == null) throw new IOException("TUN output is closed");
        output.write(packet);
        packetsDown++;
    }

    private void sendPing(int generation) {
        if (!isCurrent(generation)) return;
        expirePings(pings, autPingStats);
        int seq = sequence.getAndIncrement();
        long now = SystemClock.elapsedRealtimeNanos();
        pings.put(seq, now);
        autPingStats.sent();
        sendEncoded(AutProtocol.encode(AutProtocol.PING, seq, now, new byte[0]), generation);
        publishPingStats();
    }

    private void sendIcmp6Ping(int generation) {
        if (!isCurrent(generation) || !icmp6Enabled
                || clientIpv6 == null || gatewayIpv6 == null) return;
        expirePings(icmp6Pings, icmp6PingStats);
        int seq = icmp6Sequence.getAndIncrement() & 0xffff;
        long now = SystemClock.elapsedRealtimeNanos();
        icmp6Pings.put(seq, now);
        icmp6PingStats.sent();
        sendFrame(AutProtocol.ICMP6_ECHO, buildIcmp6Echo(seq, now), generation);
        publishPingStats();
    }

    private void expirePings(Map<Integer, Long> pending, PingStats stats) {
        long cutoff = SystemClock.elapsedRealtimeNanos() - TimeUnit.SECONDS.toNanos(3);
        for (Map.Entry<Integer, Long> entry : pending.entrySet()) {
            if (entry.getValue() < cutoff && pending.remove(entry.getKey(), entry.getValue())) {
                stats.timeout();
            }
        }
    }

    private byte[] buildIcmp6Echo(int seq, long timestamp) {
        ByteBuffer packet = ByteBuffer.allocate(56).order(ByteOrder.BIG_ENDIAN);
        packet.putInt(0x60000000);
        packet.putShort((short) 16);
        packet.put((byte) 58);
        packet.put((byte) 64);
        packet.put(clientIpv6);
        packet.put(gatewayIpv6);
        packet.put((byte) 128);
        packet.put((byte) 0);
        packet.putShort((short) 0);
        packet.putShort((short) icmp6Identifier);
        packet.putShort((short) seq);
        packet.putLong(timestamp);
        byte[] bytes = packet.array();
        int checksum = icmp6Checksum(bytes);
        bytes[42] = (byte) (checksum >>> 8);
        bytes[43] = (byte) checksum;
        return bytes;
    }

    private int icmp6Checksum(byte[] packet) {
        long sum = 0;
        for (int i = 8; i < 40; i += 2) {
            sum += ((packet[i] & 0xff) << 8) | (packet[i + 1] & 0xff);
        }
        sum += packet.length - 40;
        sum += 58;
        for (int i = 40; i < packet.length; i += 2) {
            sum += (packet[i] & 0xff) << 8;
            if (i + 1 < packet.length) sum += packet[i + 1] & 0xff;
        }
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    private boolean receiveIcmp6Reply(byte[] packet) {
        if (packet.length < 48 || (packet[0] >>> 4) != 6 || (packet[6] & 0xff) != 58
                || (packet[40] & 0xff) != 129) return false;
        int identifier = ((packet[44] & 0xff) << 8) | (packet[45] & 0xff);
        if (identifier != icmp6Identifier) return false;
        int seq = ((packet[46] & 0xff) << 8) | (packet[47] & 0xff);
        Long began = icmp6Pings.remove(seq);
        if (began == null) return true;
        icmp6PingStats.received(
                (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0);
        publishPingStats();
        return true;
    }

    private void publishPingStats() {
        String aut = autPingStats.format("AUTPing");
        String icmp6 = icmp6PingStats.format("ICMPv6 Ping");
        getPreferences().edit()
                .putString(PREF_AUT_PING, aut)
                .putString(PREF_ICMP6_PING, icmp6)
                .apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_AUT_PING, aut)
                .putExtra(EXTRA_ICMP6_PING, icmp6));
    }

    private void publishIcmp6State(String state) {
        getPreferences().edit().putString(PREF_ICMP6_PING, state).apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_ICMP6_PING, state));
    }

    private void publishDiagnosticState(String aut, String icmp6) {
        getPreferences().edit()
                .putString(PREF_AUT_PING, aut)
                .putString(PREF_ICMP6_PING, icmp6)
                .apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_AUT_PING, aut)
                .putExtra(EXTRA_ICMP6_PING, icmp6));
    }

    private void clearDiagnostics() {
        pings.clear();
        icmp6Pings.clear();
        autPingStats.reset();
        icmp6PingStats.reset();
        getPreferences().edit()
                .remove(PREF_AUT_PING)
                .remove(PREF_ICMP6_PING)
                .apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_AUT_PING, "AUTPing · waiting")
                .putExtra(EXTRA_ICMP6_PING, "ICMPv6 Ping · waiting for an IP lease"));
    }

    private void sendFrame(byte type, byte[] payload, int generation) {
        sendEncoded(AutProtocol.encode(type, sequence.getAndIncrement(),
                SystemClock.elapsedRealtimeNanos(), payload), generation);
    }

    private void sendEncoded(byte[] frame, int generation) {
        if (!isCurrent(generation)) return;
        try {
            synchronized (usbWriteLock) {
                if (usbOutput == null) return;
                usbOutput.write(frame);
                usbOutput.flush();
            }
        } catch (IOException error) {
            if (isCurrent(generation)) {
                fail("USB write error: " + usefulMessage(error), generation);
            }
        }
    }

    private void status(String message) {
        AutEventLog.append(this, message);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification(message));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, message));
    }

    private synchronized void fail(String message, int generation) {
        if (!isCurrent(generation)) return;
        status(message);
        shutdown(false);
        stopSelf();
    }

    private void shutdownAsync(boolean notifyPeer) {
        // USB/TUN close and the final USB write can block inside a device
        // driver. Never perform them on Android's main service thread.
        new Thread(() -> {
            shutdown(notifyPeer);
            stopSelf();
        }, "aut-shutdown").start();
    }

    private boolean isCurrent(int generation) {
        return running && sessionGeneration.get() == generation;
    }

    private void sendSessionStopBestEffort() {
        byte[] frame = AutProtocol.encode(AutProtocol.SESSION_STOP,
                sequence.getAndIncrement(), SystemClock.elapsedRealtimeNanos(), new byte[0]);
        try {
            synchronized (usbWriteLock) {
                if (usbOutput != null) {
                    usbOutput.write(frame);
                    usbOutput.flush();
                }
            }
        } catch (IOException ignored) {
            // The cable may already be gone. Shutdown remains intentional.
        }
    }

    private synchronized void shutdown(boolean notifyPeer) {
        if (notifyPeer && running) sendSessionStopBestEffort();
        running = false;
        clientReadyAcknowledged = false;
        sessionGeneration.incrementAndGet();
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        if (relay != null) relay.close();
        relay = null;
        close(tunInput); close(tunOutput); close(tunFd);
        close(usbInput); close(usbOutput); close(accessoryFd);
        tunInput = null; tunOutput = null; tunFd = null;
        usbInput = null; usbOutput = null; accessoryFd = null;
        pings.clear();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private static String usefulMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void close(java.io.Closeable value) {
        try { if (value != null) value.close(); } catch (IOException ignored) {}
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_aut_notification)
                .setContentTitle("AUT over USB")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "AUT VPN", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onRevoke() {
        status("VPN permission revoked");
        shutdownAsync(true);
    }

    @Override public void onDestroy() {
        shutdown(running);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
