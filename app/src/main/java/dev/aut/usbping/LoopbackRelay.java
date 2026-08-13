package dev.aut.usbping;

import android.net.VpnService;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/** Local ::1 hop between the USB bridge and VpnService packet pump. */
final class LoopbackRelay implements Closeable {
    interface PacketHandler {
        void handle(byte[] packet) throws IOException;
    }

    private final VpnService vpnService;
    private final String protocol;
    private final PacketHandler toUsb;
    private final PacketHandler toTun;
    private volatile boolean running;

    private DatagramSocket udpBridge;
    private DatagramSocket udpVpn;
    private InetSocketAddress udpBridgeAddress;
    private InetSocketAddress udpVpnAddress;
    private ServerSocket tcpServer;
    private Socket tcpBridge;
    private Socket tcpVpn;
    private DataOutputStream tcpBridgeOutput;
    private DataOutputStream tcpVpnOutput;

    LoopbackRelay(VpnService vpnService, String protocol,
                  PacketHandler toUsb, PacketHandler toTun) {
        this.vpnService = vpnService;
        this.protocol = protocol;
        this.toUsb = toUsb;
        this.toTun = toTun;
    }

    void start() throws IOException {
        running = true;
        if ("tcp".equals(protocol)) startTcp();
        else startUdp();
    }

    String description() {
        return protocol.toUpperCase() + " over [::1]";
    }

    private void startUdp() throws IOException {
        InetAddress loopback = Inet6Address.getByName("::1");
        udpBridge = new DatagramSocket(null);
        udpVpn = new DatagramSocket(null);
        if (!vpnService.protect(udpBridge) || !vpnService.protect(udpVpn)) {
            throw new IOException("could not protect IPv6 loopback UDP sockets");
        }
        udpBridge.bind(new InetSocketAddress(loopback, 0));
        udpVpn.bind(new InetSocketAddress(loopback, 0));
        udpBridgeAddress = new InetSocketAddress(loopback, udpBridge.getLocalPort());
        udpVpnAddress = new InetSocketAddress(loopback, udpVpn.getLocalPort());
        udpBridge.connect(udpVpnAddress);
        udpVpn.connect(udpBridgeAddress);
        new Thread(() -> udpReceiveLoop(udpBridge, toUsb), "aut-udp-to-usb").start();
        new Thread(() -> udpReceiveLoop(udpVpn, toTun), "aut-udp-to-tun").start();
    }

    private void udpReceiveLoop(DatagramSocket socket, PacketHandler handler) {
        byte[] buffer = new byte[65535];
        while (running) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);
                handler.handle(Arrays.copyOfRange(
                        datagram.getData(), datagram.getOffset(),
                        datagram.getOffset() + datagram.getLength()));
            } catch (IOException error) {
                if (running) closeQuietly();
                return;
            }
        }
    }

    private void startTcp() throws IOException {
        InetAddress loopback = Inet6Address.getByName("::1");
        tcpServer = new ServerSocket();
        tcpServer.bind(new InetSocketAddress(loopback, 0));
        tcpVpn = new Socket();
        tcpVpn.connect(new InetSocketAddress(loopback, tcpServer.getLocalPort()));
        // Pixel kernels may reject protect() for an unconnected TCP socket.
        // Connect to ::1 first, then protect the established local flow. This
        // still happens before the new VPN TUN and its default routes exist.
        if (!vpnService.protect(tcpVpn)) {
            throw new IOException("could not protect connected TCP loopback socket");
        }
        tcpBridge = tcpServer.accept();
        // The accepted side belongs to the already-protected ::1 connection.
        // Some Android kernels reject protect() on accepted local sockets.
        tcpVpn.setTcpNoDelay(true);
        tcpBridge.setTcpNoDelay(true);
        tcpVpnOutput = new DataOutputStream(tcpVpn.getOutputStream());
        tcpBridgeOutput = new DataOutputStream(tcpBridge.getOutputStream());
        new Thread(() -> tcpReceiveLoop(tcpBridge, toUsb), "aut-tcp-to-usb").start();
        new Thread(() -> tcpReceiveLoop(tcpVpn, toTun), "aut-tcp-to-tun").start();
    }

    private void tcpReceiveLoop(Socket socket, PacketHandler handler) {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (running) {
                int length = input.readUnsignedShort();
                if (length == 0) throw new IOException("zero-length IP packet");
                byte[] packet = new byte[length];
                input.readFully(packet);
                handler.handle(packet);
            }
        } catch (EOFException ignored) {
            closeQuietly();
        } catch (IOException error) {
            if (running) closeQuietly();
        }
    }

    void sendFromVpn(byte[] packet) throws IOException {
        if ("tcp".equals(protocol)) writeTcp(tcpVpnOutput, packet);
        else udpVpn.send(new DatagramPacket(packet, packet.length));
    }

    void sendFromUsb(byte[] packet) throws IOException {
        if ("tcp".equals(protocol)) writeTcp(tcpBridgeOutput, packet);
        else udpBridge.send(new DatagramPacket(packet, packet.length));
    }

    private static void writeTcp(DataOutputStream output, byte[] packet) throws IOException {
        if (packet.length > 65535) throw new IOException("IP packet exceeds TCP relay frame");
        synchronized (output) {
            output.writeShort(packet.length);
            output.write(packet);
            output.flush();
        }
    }

    @Override public void close() {
        running = false;
        if (udpBridge != null) udpBridge.close();
        if (udpVpn != null) udpVpn.close();
        closeValue(tcpBridge);
        closeValue(tcpVpn);
        closeValue(tcpServer);
    }

    private void closeQuietly() {
        try { close(); } catch (Exception ignored) {}
    }

    private static void closeValue(Closeable value) {
        try { if (value != null) value.close(); } catch (IOException ignored) {}
    }
}
