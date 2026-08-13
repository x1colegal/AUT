package dev.aut.usbping;

import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sliding-window Reliable AUT Transport Protocol endpoint. */
final class RatpTransport {
    static final int WINDOW = 1024;
    private static final long ACK_DELAY_NS = 5_000_000L;
    private static final long MIN_RTO_NS = 50_000_000L;
    private static final long MAX_RTO_NS = 2_000_000_000L;
    private static final int MAX_RETRIES = 12;

    static final class Work {
        final List<byte[]> controls;
        final List<AutProtocol.Frame> retransmissions;

        Work(List<byte[]> controls, List<AutProtocol.Frame> retransmissions) {
            this.controls = controls;
            this.retransmissions = retransmissions;
        }
    }

    private static final class Pending {
        final AutProtocol.Frame frame;
        long sentNanos;
        int retries;
        boolean retransmitted;

        Pending(AutProtocol.Frame frame, long sentNanos) {
            this.frame = frame;
            this.sentNanos = sentNanos;
        }
    }

    private int sendSequence = 1;
    private int receiveNext = 1;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Map<Integer, byte[]> reorder = new HashMap<>();
    private final Set<Integer> acks = new LinkedHashSet<>();
    private final Set<Integer> nacks = new LinkedHashSet<>();
    private long controlSinceNanos;
    private double srttNanos = -1;
    private double rttVariationNanos;
    private long rtoNanos = 250_000_000L;

    synchronized AutProtocol.Frame sendPacket(byte[] packet) throws IOException {
        if (packet.length == 0 || packet.length > AutProtocol.MAX_PAYLOAD) {
            throw new IOException("RATP packet length is out of range");
        }
        long deadline = SystemClock.elapsedRealtimeNanos() + 2_000_000_000L;
        while (pending.size() >= WINDOW) {
            long remaining = deadline - SystemClock.elapsedRealtimeNanos();
            if (remaining <= 0) throw new IOException("RATP send window is full");
            try {
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                wait(millis, nanos);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("RATP send interrupted", error);
            }
        }
        if (sendSequence <= 0) {
            throw new IOException("RATP sequence space exhausted; renegotiate the session");
        }
        long now = SystemClock.elapsedRealtimeNanos();
        AutProtocol.Frame frame = new AutProtocol.Frame(
                AutProtocol.RATP_DATA, AutProtocol.END_MESSAGE,
                sendSequence++, now, packet);
        pending.put(frame.sequence, new Pending(frame, now));
        return frame;
    }

    synchronized List<byte[]> receive(AutProtocol.Frame frame)
            throws AutProtocol.ProtocolException {
        if (frame.type != AutProtocol.RATP_DATA) {
            throw new AutProtocol.ProtocolException("expected RATP_DATA");
        }
        long now = SystemClock.elapsedRealtimeNanos();
        int sequence = frame.sequence;
        queue(acks, sequence, now);
        nacks.remove(sequence);
        List<byte[]> delivered = new ArrayList<>();
        if (sequence < receiveNext) return delivered;
        if ((long) sequence >= (long) receiveNext + WINDOW) {
            throw new AutProtocol.ProtocolException("RATP packet is outside receive window");
        }
        if (sequence > receiveNext) {
            if (!reorder.containsKey(sequence)) reorder.put(sequence, frame.payload);
            for (int missing = receiveNext; missing < sequence; missing++) {
                if (!reorder.containsKey(missing)) queue(nacks, missing, now);
            }
            return delivered;
        }
        delivered.add(frame.payload);
        receiveNext++;
        while (reorder.containsKey(receiveNext)) {
            delivered.add(reorder.remove(receiveNext));
            nacks.remove(receiveNext);
            receiveNext++;
        }
        return delivered;
    }

    synchronized List<AutProtocol.Frame> handleControl(AutProtocol.ControlRecord control)
            throws IOException {
        long now = SystemClock.elapsedRealtimeNanos();
        List<AutProtocol.Frame> retransmissions = new ArrayList<>();
        if ("ACK".equals(control.kind)) {
            for (int sequence : control.sequences) {
                Pending value = pending.remove(sequence);
                if (value != null && !value.retransmitted) updateRto(now - value.sentNanos);
            }
            notifyAll();
        } else {
            for (int sequence : control.sequences) {
                Pending value = pending.get(sequence);
                if (value != null) {
                    retransmit(value, now);
                    retransmissions.add(value.frame);
                }
            }
        }
        return retransmissions;
    }

    synchronized Work maintenance() throws IOException {
        long now = SystemClock.elapsedRealtimeNanos();
        List<AutProtocol.Frame> retransmissions = new ArrayList<>();
        boolean timedOut = false;
        for (Pending value : pending.values()) {
            if (now - value.sentNanos >= rtoNanos) {
                retransmit(value, now);
                retransmissions.add(value.frame);
                timedOut = true;
            }
        }
        if (timedOut) rtoNanos = Math.min(MAX_RTO_NS, rtoNanos * 2);
        boolean controlsReady = acks.size() >= AutProtocol.MAX_CONTROL_SEQUENCES
                || nacks.size() >= AutProtocol.MAX_CONTROL_SEQUENCES
                || (controlSinceNanos != 0 && now - controlSinceNanos >= ACK_DELAY_NS);
        return new Work(controlsReady ? drainControls() : Collections.emptyList(),
                retransmissions);
    }

    private void queue(Set<Integer> target, int sequence, long now) {
        target.add(sequence);
        if (controlSinceNanos == 0) controlSinceNanos = now;
    }

    private List<byte[]> drainControls() {
        List<byte[]> lines = new ArrayList<>();
        drainKind("ACK", acks, lines);
        drainKind("NACK", nacks, lines);
        controlSinceNanos = 0;
        return lines;
    }

    private static void drainKind(String kind, Set<Integer> source, List<byte[]> output) {
        List<Integer> ordered = new ArrayList<>(source);
        Collections.sort(ordered);
        for (int offset = 0; offset < ordered.size(); offset += 256) {
            output.add(AutProtocol.controlLine(
                    kind, ordered.subList(offset, Math.min(offset + 256, ordered.size()))));
        }
        source.clear();
    }

    private static void retransmit(Pending value, long now) throws IOException {
        if (value.retries >= MAX_RETRIES) {
            throw new IOException(String.format(
                    "RATP sequence %08X exceeded retry limit", value.frame.sequence));
        }
        value.sentNanos = now;
        value.retransmitted = true;
        value.retries++;
    }

    private void updateRto(long sampleNanos) {
        if (srttNanos < 0) {
            srttNanos = sampleNanos;
            rttVariationNanos = sampleNanos / 2.0;
        } else {
            rttVariationNanos = 0.75 * rttVariationNanos
                    + 0.25 * Math.abs(srttNanos - sampleNanos);
            srttNanos = 0.875 * srttNanos + 0.125 * sampleNanos;
        }
        long calculated = (long) (srttNanos + 4 * rttVariationNanos);
        rtoNanos = Math.max(MIN_RTO_NS, Math.min(MAX_RTO_NS, calculated));
    }
}
