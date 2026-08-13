package dev.aut.usbping;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** AUT/4 handshake, binary framing, and cleartext RATP controls. */
final class AutProtocol {
    static final String VERSION = "4.0";
    static final byte PING = 1;
    static final byte PONG = 2;
    static final byte CONFIG_REQUEST = 3;
    static final byte CONFIG_RESPONSE = 4;
    static final byte IP_PACKET = 5;
    static final byte SESSION_STOP = 6;
    static final byte CONFIG_REQUIRED = 7;
    static final byte CLIENT_READY = 8;
    static final byte CLIENT_READY_ACK = 9;
    static final byte ICMP6_ECHO = 10;
    static final byte RATP_DATA = 11;
    static final byte END_MESSAGE = 1;
    static final int HEADER_SIZE = 9;
    static final int METADATA_SIZE = 8;
    static final int MAX_PAYLOAD = 65536;
    static final int MAX_HANDSHAKE = 8192;
    static final int MAX_CONTROL_SEQUENCES = 256;
    static final int MAX_CONTROL_LINE = 2400;

    private AutProtocol() {}

    static byte[] encode(byte type, int sequence, long timestampNanos, byte[] payload) {
        return encode(type, (byte) 0, sequence, timestampNanos, payload);
    }

    static byte[] encode(byte type, byte flags, int sequence,
                         long timestampNanos, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload is too large");
        }
        if (sequence <= 0) throw new IllegalArgumentException("sequence is out of range");
        int length = METADATA_SIZE + payload.length;
        ByteBuffer frame = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.BIG_ENDIAN);
        frame.put((byte) (length >>> 16));
        frame.put((byte) (length >>> 8));
        frame.put((byte) length);
        frame.put(type);
        frame.put(flags);
        frame.putInt(sequence & 0x7fffffff);
        frame.putLong(timestampNanos);
        frame.put(payload);
        return frame.array();
    }

    static byte[] encode(Frame frame) {
        return encode(frame.type, frame.flags, frame.sequence, frame.timestampNanos, frame.payload);
    }

    static byte[] controlLine(String kind, Iterable<Integer> values) {
        if (!"ACK".equals(kind) && !"NACK".equals(kind)) {
            throw new IllegalArgumentException("control kind must be ACK or NACK");
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (int value : values) unique.add(value);
        if (unique.isEmpty() || unique.size() > MAX_CONTROL_SEQUENCES) {
            throw new IllegalArgumentException("control line must contain 1 through 256 sequences");
        }
        StringBuilder line = new StringBuilder(kind).append(' ');
        int index = 0;
        for (int value : unique) {
            if (value <= 0) throw new IllegalArgumentException("control sequence is out of range");
            if (index++ > 0) line.append(',');
            line.append(String.format(Locale.US, "%08X", value));
        }
        return line.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] handshakeRequest(String transport, String mode) {
        String text = "HANDSHAKE AUT/" + VERSION + "\r\n"
                + "Versions: " + VERSION + "\r\n"
                + "Transport: " + transport + "\r\n"
                + "Mode: " + mode + "\r\n"
                + "Framing: binary-h2\r\n\r\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    static final class Frame {
        final byte type;
        final byte flags;
        final int sequence;
        final long timestampNanos;
        final byte[] payload;

        Frame(byte type, byte flags, int sequence, long timestampNanos, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.sequence = sequence;
            this.timestampNanos = timestampNanos;
            this.payload = payload;
        }
    }

    static final class ControlRecord {
        final String kind;
        final int[] sequences;

        ControlRecord(String kind, int[] sequences) {
            this.kind = kind;
            this.sequences = sequences;
        }
    }

    /** Mixed decoder: binary frames and raw ASCII ACK/NACK lines share USB. */
    static final class WireDecoder {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        synchronized List<Object> feed(byte[] bytes, int count) throws ProtocolException {
            pending.write(bytes, 0, count);
            byte[] data = pending.toByteArray();
            int offset = 0;
            List<Object> records = new ArrayList<>();
            while (offset < data.length) {
                int first = data[offset] & 0xff;
                if (first == 'A' || first == 'N') {
                    int marker = findCrlf(data, offset);
                    if (marker < 0) {
                        if (data.length - offset > MAX_CONTROL_LINE) {
                            throw new ProtocolException("RATP control line is too large");
                        }
                        break;
                    }
                    records.add(parseControl(new String(
                            data, offset, marker - offset, StandardCharsets.US_ASCII)));
                    offset = marker + 2;
                    continue;
                }
                if (data.length - offset < HEADER_SIZE) break;
                int length = ((data[offset] & 0xff) << 16)
                        | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff);
                if (length < METADATA_SIZE || length > MAX_PAYLOAD + METADATA_SIZE) {
                    throw new ProtocolException("invalid frame length: " + length);
                }
                int frameLength = HEADER_SIZE + length;
                if (data.length - offset < frameLength) break;
                ByteBuffer header = ByteBuffer.wrap(data, offset + 3, frameLength - 3)
                        .order(ByteOrder.BIG_ENDIAN);
                byte type = header.get();
                byte flags = header.get();
                int sequence = header.getInt();
                if (sequence <= 0) throw new ProtocolException("invalid or reserved stream ID");
                long timestamp = header.getLong();
                byte[] payload = Arrays.copyOfRange(
                        data, offset + HEADER_SIZE + METADATA_SIZE, offset + frameLength);
                records.add(new Frame(type, flags, sequence, timestamp, payload));
                offset += frameLength;
            }
            pending.reset();
            pending.write(data, offset, data.length - offset);
            return records;
        }

        private static ControlRecord parseControl(String line) throws ProtocolException {
            int space = line.indexOf(' ');
            if (space < 0) throw new ProtocolException("invalid RATP control line");
            String kind = line.substring(0, space);
            if (!"ACK".equals(kind) && !"NACK".equals(kind)) {
                throw new ProtocolException("invalid RATP control kind");
            }
            String[] tokens = line.substring(space + 1).split(",", -1);
            if (tokens.length == 0 || tokens.length > MAX_CONTROL_SEQUENCES) {
                throw new ProtocolException("invalid RATP control sequence count");
            }
            int[] sequences = new int[tokens.length];
            try {
                for (int i = 0; i < tokens.length; i++) {
                    if (tokens[i].length() != 8) {
                        throw new ProtocolException("invalid RATP control sequence");
                    }
                    long parsed = Long.parseLong(tokens[i], 16);
                    if (parsed <= 0 || parsed > 0x7fffffffL) {
                        throw new ProtocolException("invalid RATP control sequence");
                    }
                    sequences[i] = (int) parsed;
                }
            } catch (NumberFormatException error) {
                throw new ProtocolException("invalid RATP control sequence");
            }
            return new ControlRecord(kind, sequences);
        }

        private static int findCrlf(byte[] data, int offset) {
            for (int i = offset; i + 1 < data.length; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n') return i;
            }
            return -1;
        }
    }

    static final class Handshake {
        final String firstLine;
        final Map<String, String> options;
        final byte[] remainder;

        Handshake(String firstLine, Map<String, String> options, byte[] remainder) {
            this.firstLine = firstLine;
            this.options = options;
            this.remainder = remainder;
        }
    }

    static final class HandshakeDecoder {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        synchronized Handshake feed(byte[] bytes, int count) throws ProtocolException {
            pending.write(bytes, 0, count);
            byte[] data = pending.toByteArray();
            int marker = findHeaderEnd(data);
            if (marker < 0) {
                if (pending.size() > MAX_HANDSHAKE) {
                    throw new ProtocolException("handshake is too large");
                }
                return null;
            }
            if (marker + 4 > MAX_HANDSHAKE) {
                throw new ProtocolException("handshake is too large");
            }
            String block = new String(data, 0, marker, StandardCharsets.US_ASCII);
            String[] lines = block.split("\\r\\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                throw new ProtocolException("empty handshake");
            }
            Map<String, String> options = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) throw new ProtocolException("invalid handshake option");
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
                if (options.containsKey(name)) {
                    throw new ProtocolException("duplicate handshake option: " + name);
                }
                options.put(name, lines[i].substring(colon + 1).trim());
            }
            return new Handshake(lines[0], options,
                    Arrays.copyOfRange(data, marker + 4, data.length));
        }

        private static int findHeaderEnd(byte[] data) {
            for (int i = 0; i + 3 < data.length; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n'
                        && data[i + 2] == '\r' && data[i + 3] == '\n') return i;
            }
            return -1;
        }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) { super(message); }
    }
}
