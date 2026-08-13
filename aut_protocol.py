"""Transport-independent AUT binary framing."""

from __future__ import annotations

import dataclasses
import struct
import zlib

MAGIC = b"AUT1"
VERSION = 1
TYPE_PING = 1
TYPE_PONG = 2
TYPE_CONFIG_REQUEST = 3
TYPE_CONFIG_RESPONSE = 4
TYPE_IP_PACKET = 5
TYPE_SESSION_STOP = 6
TYPE_CONFIG_REQUIRED = 7
TYPE_CLIENT_READY = 8
TYPE_CLIENT_READY_ACK = 9
TYPE_ICMP6_ECHO = 10
MAX_PAYLOAD = 65_536
HEADER = struct.Struct(">4sBBHIIQ")
CRC = struct.Struct(">I")


class ProtocolError(ValueError):
    pass


@dataclasses.dataclass(frozen=True, slots=True)
class Frame:
    type: int
    sequence: int
    timestamp_ns: int
    payload: bytes = b""


def encode(frame: Frame) -> bytes:
    if not 0 <= frame.type <= 255:
        raise ValueError("frame type is out of range")
    if not 0 <= frame.sequence <= 0xFFFF_FFFF:
        raise ValueError("sequence is out of range")
    if len(frame.payload) > MAX_PAYLOAD:
        raise ValueError("payload is too large")
    body = HEADER.pack(
        MAGIC,
        VERSION,
        frame.type,
        0,
        frame.sequence,
        len(frame.payload),
        frame.timestamp_ns,
    ) + frame.payload
    return body + CRC.pack(zlib.crc32(body))


class Decoder:
    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> list[Frame]:
        self._buffer.extend(data)
        frames: list[Frame] = []
        minimum = HEADER.size + CRC.size
        while len(self._buffer) >= minimum:
            magic, version, frame_type, _flags, sequence, length, timestamp = (
                HEADER.unpack_from(self._buffer)
            )
            if magic != MAGIC:
                raise ProtocolError("invalid AUT magic")
            if version != VERSION:
                raise ProtocolError(f"unsupported AUT version: {version}")
            if length > MAX_PAYLOAD:
                raise ProtocolError(f"payload is too large: {length}")
            frame_length = HEADER.size + length + CRC.size
            if len(self._buffer) < frame_length:
                break
            expected_crc = CRC.unpack_from(self._buffer, HEADER.size + length)[0]
            actual_crc = zlib.crc32(self._buffer[: HEADER.size + length])
            if expected_crc != actual_crc:
                raise ProtocolError("invalid CRC32")
            payload = bytes(self._buffer[HEADER.size : HEADER.size + length])
            frames.append(Frame(frame_type, sequence, timestamp, payload))
            del self._buffer[:frame_length]
        return frames
