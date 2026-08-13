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
import java.util.concurrent.ScheduledFuture;
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
    static final String PREFS = "aut_service";
    private static final String PREF_MODE = "mode";
    private static final String PREF_TRANSPORT = "transport";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_RUNTIME_STATUS = "runtime_status";
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
    private volatile boolean desiredActive;
    private int reconnectAttempt;
    private ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectFuture;
    private boolean pingEnabled;
    private boolean internetEnabled;
    private boolean routeInternetEnabled;
    private boolean icmp6Enabled;
    private String transportProtocol = "direct";
    private ParcelFileDescriptor accessoryFd;
    private ParcelFileDescriptor tunFd;
    private FileInputStream usbInput;
    private FileOutputStream usbOutput;
    private FileInputStream tunInput;
    private FileOutputStream tunOutput;
    private ScheduledExecutorService scheduler;
    private long packetsUp;
    private long packetsDown;
    private volatile boolean clientReadyAcknowledged;
    private volatile boolean handshakeComplete;
    private RatpTransport ratp = new RatpTransport();
    private byte[] clientIpv6;
    private byte[] gatewayIpv6;
    private final AtomicInteger icmp6Sequence = new AtomicInteger(1);

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLEAR_DIAGNOSTICS.equals(intent.getAction())) {
            clearDiagnostics();
            if (!desiredActive) stopSelf();
            return desiredActive ? START_STICKY : START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            desiredActive = false;
            cancelReconnect();
            getPreferences().edit().putBoolean(PREF_ACTIVE, false).apply();
            status("AUT stopped");
            shutdownAsync(true);
            return START_NOT_STICKY;
        }
        desiredActive = true;
        cancelReconnect();
        reconnectAttempt = 0;
        if (running) shutdown(true);
        String mode = intent == null
                ? getPreferences().getString(PREF_MODE, MODE_INTERNET)
                : intent.getStringExtra(EXTRA_MODE);
        if (!MODE_PING.equals(mode) && !MODE_ICMP6.equals(mode)
                && !MODE_INTERNET.equals(mode)) mode = MODE_INTERNET;
        transportProtocol = intent == null
                ? getPreferences().getString(PREF_TRANSPORT, "direct")
                : intent.getStringExtra(EXTRA_TRANSPORT);
        if (!"ratp".equals(transportProtocol)) {
            transportProtocol = "direct";
        }
        pingEnabled = MODE_PING.equals(mode);
        routeInternetEnabled = MODE_INTERNET.equals(mode);
        icmp6Enabled = MODE_ICMP6.equals(mode);
        internetEnabled = icmp6Enabled || routeInternetEnabled;
        getPreferences().edit()
                .putString(PREF_MODE, mode)
                .putString(PREF_TRANSPORT, transportProtocol)
                .putBoolean(PREF_ACTIVE, true)
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
        running = true;
        startTransportSafely(generation);
        return START_REDELIVER_INTENT;
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean isActive(android.content.Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ACTIVE, false);
    }

    static String runtimeStatus(android.content.Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_RUNTIME_STATUS, "AUT is running");
    }

    static String selectedTransport(android.content.Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_TRANSPORT, "direct");
    }

    private void startTransport(int generation) {
        if (!desiredActive || !isCurrent(generation)) return;
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbAccessory[] list = manager.getAccessoryList();
        if (list == null || list.length == 0) {
            connectionLost("AUT USB accessory not found", generation);
            return;
        }
        accessoryFd = manager.openAccessory(list[0]);
        if (accessoryFd == null) {
            connectionLost("USB permission is missing or the accessory is busy", generation);
            return;
        }
        clientReadyAcknowledged = false;
        handshakeComplete = false;
        ratp = new RatpTransport();
        usbInput = new FileInputStream(accessoryFd.getFileDescriptor());
        usbOutput = new FileOutputStream(accessoryFd.getFileDescriptor());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        new Thread(() -> usbLoop(generation), "aut-usb-reader").start();
        sendRaw(AutProtocol.handshakeRequest(transportProtocol,
                pingEnabled ? MODE_PING : icmp6Enabled ? MODE_ICMP6 : MODE_INTERNET), generation);
        if (!isCurrent(generation)) return;
        status("Negotiating AUT/" + AutProtocol.VERSION + " · "
                + transportProtocol.toUpperCase(Locale.US));
        scheduler.schedule(() -> {
            if (isCurrent(generation) && !handshakeComplete) {
                connectionLost("AUT handshake timeout", generation);
            }
        }, 6, TimeUnit.SECONDS);
    }

    private void startTransportSafely(int generation) {
        try {
            startTransport(generation);
        } catch (Exception error) {
            connectionLost("AUT connection error: " + usefulMessage(error), generation);
        }
    }

    private void onHandshakeComplete(int generation) throws Exception {
        if ("ratp".equals(transportProtocol)) {
            scheduler.scheduleWithFixedDelay(
                    () -> ratpMaintenance(generation), 5, 5, TimeUnit.MILLISECONDS);
        }
        if (pingEnabled) {
            scheduler.scheduleWithFixedDelay(
                    () -> sendPing(generation), 0, 1, TimeUnit.SECONDS);
        }
        if (internetEnabled) {
            status("USB ready — requesting the dual-stack lease");
            sendFrame(AutProtocol.CONFIG_REQUEST, configRequest(), generation);
            scheduler.schedule(() -> {
                if (isCurrent(generation) && tunFd == null) {
                    connectionLost(
                            "Gateway timeout — no DHCP/SLAAC lease received", generation);
                }
            }, 6, TimeUnit.SECONDS);
        } else status("AUT/" + AutProtocol.VERSION + " Ping-only link active");
        if (!internetEnabled) markConnectionStable();
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
                    .put("protocol", 4)
                    .put("transport", transportProtocol)
                    .put("client_id", clientId)
                    .put("host_id", hostId)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Could not build the AUT identity", error);
        }
    }

    private void usbLoop(int generation) {
        AutProtocol.WireDecoder decoder = new AutProtocol.WireDecoder();
        AutProtocol.HandshakeDecoder handshakeDecoder = new AutProtocol.HandshakeDecoder();
        byte[] buffer = new byte[64 * 1024];
        try {
            while (isCurrent(generation)) {
                int count = usbInput.read(buffer);
                if (count < 0) throw new IOException("end of USB stream");
                if (!isCurrent(generation)) break;
                byte[] binary = buffer;
                int binaryCount = count;
                if (!handshakeComplete) {
                    AutProtocol.Handshake handshake = handshakeDecoder.feed(buffer, count);
                    if (handshake == null) continue;
                    validateHandshake(handshake);
                    handshakeComplete = true;
                    status("AUT/" + AutProtocol.VERSION + " negotiated · "
                            + transportProtocol.toUpperCase(Locale.US));
                    onHandshakeComplete(generation);
                    binary = handshake.remainder;
                    binaryCount = binary.length;
                }
                for (Object record : decoder.feed(binary, binaryCount)) {
                    if (record instanceof AutProtocol.ControlRecord) {
                        onRatpControl((AutProtocol.ControlRecord) record, generation);
                    } else onFrame((AutProtocol.Frame) record, generation);
                }
            }
        } catch (Exception error) {
            if (isCurrent(generation)) {
                connectionLost("AUT connection error: " + usefulMessage(error), generation);
            }
        }
    }

    private void onRatpControl(AutProtocol.ControlRecord control, int generation)
            throws Exception {
        if (!"ratp".equals(transportProtocol)) {
            throw new AutProtocol.ProtocolException("RATP ACK/NACK was not negotiated");
        }
        for (AutProtocol.Frame frame : ratp.handleControl(control)) {
            sendEncoded(AutProtocol.encode(frame), generation);
        }
    }

    private void ratpMaintenance(int generation) {
        if (!isCurrent(generation) || !"ratp".equals(transportProtocol)) return;
        try {
            RatpTransport.Work work = ratp.maintenance();
            for (byte[] control : work.controls) sendRaw(control, generation);
            for (AutProtocol.Frame frame : work.retransmissions) {
                sendEncoded(AutProtocol.encode(frame), generation);
            }
        } catch (Exception error) {
            connectionLost("RATP error: " + usefulMessage(error), generation);
        }
    }

    private void validateHandshake(AutProtocol.Handshake handshake) throws Exception {
        if (!handshake.firstLine.startsWith("AUT/" + AutProtocol.VERSION + " 200 ")) {
            throw new AutProtocol.ProtocolException(
                    "server rejected handshake: " + handshake.firstLine);
        }
        String transport = handshake.options.get("transport");
        if (transport == null || !transport.split(";", 2)[0].trim().equals(transportProtocol)) {
            throw new AutProtocol.ProtocolException("server selected a different transport");
        }
        String framing = handshake.options.get("framing");
        if (framing == null || !framing.startsWith("binary-h2")) {
            throw new AutProtocol.ProtocolException("server rejected binary-h2 framing");
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
            markConnectionStable();
            status(routeInternetEnabled
                    ? "Internet active · "
                            + packetPathDescription()
                            + " · IPv4 + IPv6"
                    : "ICMPv6 diagnostic link active");
        } else if ((frame.type == AutProtocol.IP_PACKET
                || frame.type == AutProtocol.RATP_DATA) && internetEnabled) {
            if (frame.type == AutProtocol.RATP_DATA && !"ratp".equals(transportProtocol)) {
                throw new AutProtocol.ProtocolException("unexpected RATP_DATA frame");
            }
            if (frame.type == AutProtocol.IP_PACKET && "ratp".equals(transportProtocol)) {
                throw new AutProtocol.ProtocolException("unexpected plain IP frame in RATP");
            }
            java.util.List<byte[]> packets = frame.type == AutProtocol.RATP_DATA
                    ? ratp.receive(frame)
                    : java.util.Collections.singletonList(frame.payload);
            for (byte[] packet : packets) receiveIpPacket(packet, generation);
        }
    }

    private void receiveIpPacket(byte[] packet, int generation) throws IOException {
        if (receiveIcmp6Reply(packet)) return;
        writeIpToTun(packet, generation);
    }

    private synchronized void configureVpn(JSONObject config, int generation) throws Exception {
        if (!isCurrent(generation) || tunFd != null) return;
        if ("ratp".equals(transportProtocol)) {
            status("Preparing Reliable AUT Transport Protocol");
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
            throw new IOException("TUN setup failed: " + usefulMessage(error), error);
        }
        if (tunFd == null) {
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
        status("TUN ready · waiting for the Linux gateway · " + packetPathDescription());
    }

    private String packetPathDescription() {
        if ("ratp".equals(transportProtocol)) {
            return "RATP reliable window over USB";
        }
        return "Direct USB";
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
                sendIpToUsb(copy, generation);
            }
        } catch (IOException error) {
            if (isCurrent(generation)) {
                connectionLost("VPN I/O error: " + usefulMessage(error), generation);
            }
        }
    }

    private void sendIpToUsb(byte[] packet, int generation) {
        if (!isCurrent(generation)) return;
        if ("ratp".equals(transportProtocol)) {
            try {
                sendEncoded(AutProtocol.encode(ratp.sendPacket(packet)), generation);
            } catch (IOException error) {
                connectionLost("RATP send error: " + usefulMessage(error), generation);
                return;
            }
        } else sendFrame(AutProtocol.IP_PACKET, AutProtocol.END_MESSAGE, packet, generation);
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
        int seq = sequence.getAndIncrement() & 0x7fffffff;
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
            if (entry.getValue() < cutoff) {
                Long removed = pending.remove(entry.getKey());
                if (entry.getValue().equals(removed)) stats.timeout();
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
        sendFrame(type, (byte) 0, payload, generation);
    }

    private void sendFrame(byte type, byte flags, byte[] payload, int generation) {
        sendEncoded(AutProtocol.encode(type, flags, sequence.getAndIncrement() & 0x7fffffff,
                SystemClock.elapsedRealtimeNanos(), payload), generation);
    }

    private void sendRaw(byte[] bytes, int generation) {
        sendEncoded(bytes, generation);
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
                connectionLost("USB write error: " + usefulMessage(error), generation);
            }
        }
    }

    private void status(String message) {
        getPreferences().edit().putString(PREF_RUNTIME_STATUS, message).apply();
        AutEventLog.append(this, message);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification(message));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, message));
    }

    private synchronized void fail(String message, int generation) {
        if (!isCurrent(generation)) return;
        desiredActive = false;
        cancelReconnect();
        getPreferences().edit().putBoolean(PREF_ACTIVE, false).apply();
        status(message);
        shutdown(false);
        stopSelf();
    }

    private void connectionLost(String message, int generation) {
        synchronized (this) {
            if (!isCurrent(generation) || !desiredActive) return;
            running = false;
            handshakeComplete = false;
            clientReadyAcknowledged = false;
            sessionGeneration.incrementAndGet();
        }
        status(message + " — reconnecting automatically");
        new Thread(() -> {
            cleanupSession(false);
            scheduleReconnect();
        }, "aut-reconnect-cleanup").start();
    }

    private synchronized void scheduleReconnect() {
        if (!desiredActive || reconnectExecutor == null || reconnectExecutor.isShutdown()) return;
        reconnectAttempt++;
        cancelReconnect();
        reconnectFuture = reconnectExecutor.schedule(() -> {
            synchronized (AutVpnService.this) {
                reconnectFuture = null;
                if (!desiredActive || running) return;
                running = true;
            }
            int generation = sessionGeneration.incrementAndGet();
            status("Reconnect attempt " + reconnectAttempt + " · "
                    + transportProtocol.toUpperCase(Locale.US));
            startTransportSafely(generation);
        }, 0, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        reconnectFuture = null;
    }

    private synchronized void markConnectionStable() {
        if (reconnectAttempt > 0) status("AUT connection restored automatically");
        reconnectAttempt = 0;
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
                sequence.getAndIncrement() & 0x7fffffff,
                SystemClock.elapsedRealtimeNanos(), new byte[0]);
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
        if (notifyPeer && running && handshakeComplete) sendSessionStopBestEffort();
        running = false;
        handshakeComplete = false;
        clientReadyAcknowledged = false;
        sessionGeneration.incrementAndGet();
        cleanupSession(true);
    }

    private synchronized void cleanupSession(boolean removeForeground) {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        close(tunInput); close(tunOutput); close(tunFd);
        close(usbInput); close(usbOutput); close(accessoryFd);
        tunInput = null; tunOutput = null; tunFd = null;
        usbInput = null; usbOutput = null; accessoryFd = null;
        pings.clear();
        icmp6Pings.clear();
        clientIpv6 = null;
        gatewayIpv6 = null;
        if (removeForeground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        }
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
        desiredActive = false;
        cancelReconnect();
        getPreferences().edit().putBoolean(PREF_ACTIVE, false).apply();
        status("VPN permission revoked");
        shutdownAsync(true);
    }

    @Override public void onDestroy() {
        cancelReconnect();
        shutdown(running);
        if (reconnectExecutor != null) reconnectExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
