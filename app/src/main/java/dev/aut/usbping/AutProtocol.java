package dev.aut.usbping;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

final class AutProtocol {
    static final byte VERSION = 1;
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
    static final int HEADER_SIZE = 24;
    static final int CRC_SIZE = 4;
    static final int MAX_PAYLOAD = 65536;
    private static final byte[] MAGIC = {'A', 'U', 'T', '1'};

    private AutProtocol() {}

    static byte[] encode(byte type, int sequence, long timestampNanos, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload is too large");
        }
        ByteBuffer body = ByteBuffer.allocate(HEADER_SIZE + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        body.put(MAGIC);
        body.put(VERSION);
        body.put(type);
        body.putShort((short) 0);
        body.putInt(sequence);
        body.putInt(payload.length);
        body.putLong(timestampNanos);
        body.put(payload);

        CRC32 crc = new CRC32();
        crc.update(body.array());
        ByteBuffer frame = ByteBuffer.allocate(body.capacity() + CRC_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        frame.put(body.array());
        frame.putInt((int) crc.getValue());
        return frame.array();
    }

    static final class Frame {
        final byte type;
        final int sequence;
        final long timestampNanos;
        final byte[] payload;

        Frame(byte type, int sequence, long timestampNanos, byte[] payload) {
            this.type = type;
            this.sequence = sequence;
            this.timestampNanos = timestampNanos;
            this.payload = payload;
        }
    }

    static final class Decoder {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        synchronized List<Frame> feed(byte[] bytes, int count) throws ProtocolException {
            pending.write(bytes, 0, count);
            byte[] data = pending.toByteArray();
            int offset = 0;
            List<Frame> frames = new ArrayList<>();

            while (data.length - offset >= HEADER_SIZE + CRC_SIZE) {
                ByteBuffer header = ByteBuffer.wrap(data, offset, HEADER_SIZE)
                        .order(ByteOrder.BIG_ENDIAN);
                byte[] magic = new byte[4];
                header.get(magic);
                if (!Arrays.equals(magic, MAGIC)) {
                    throw new ProtocolException("invalid AUT magic");
                }
                byte version = header.get();
                if (version != VERSION) {
                    throw new ProtocolException("unsupported AUT version: " + version);
                }
                byte type = header.get();
                header.getShort();
                int sequence = header.getInt();
                int payloadLength = header.getInt();
                long timestamp = header.getLong();
                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
                    throw new ProtocolException("invalid payload length: " + payloadLength);
                }
                int frameLength = HEADER_SIZE + payloadLength + CRC_SIZE;
                if (data.length - offset < frameLength) {
                    break;
                }

                CRC32 crc = new CRC32();
                crc.update(data, offset, HEADER_SIZE + payloadLength);
                int receivedCrc = ByteBuffer.wrap(
                        data, offset + HEADER_SIZE + payloadLength, CRC_SIZE)
                        .order(ByteOrder.BIG_ENDIAN).getInt();
                if ((int) crc.getValue() != receivedCrc) {
                    throw new ProtocolException("invalid CRC32");
                }
                byte[] payload = Arrays.copyOfRange(
                        data, offset + HEADER_SIZE, offset + HEADER_SIZE + payloadLength);
                frames.add(new Frame(type, sequence, timestamp, payload));
                offset += frameLength;
            }

            pending.reset();
            pending.write(data, offset, data.length - offset);
            return frames;
        }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) {
            super(message);
        }
    }
}
