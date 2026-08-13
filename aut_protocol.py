"""AUT/4 cleartext negotiation and HTTP/2-inspired binary framing."""

from __future__ import annotations

import dataclasses
import struct

PROTOCOL_VERSION = "4.0"
SUPPORTED_VERSIONS = (PROTOCOL_VERSION,)

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
TYPE_RUDP_PACKET = 11
TYPE_RTCP_DATA = 12

FLAG_END_MESSAGE = 0x01
MAX_PAYLOAD = 65_536
MAX_HANDSHAKE = 8_192
HEADER_SIZE = 9
METADATA = struct.Struct(">Q")
RTCP_LENGTH = struct.Struct(">H")


class ProtocolError(ValueError):
    pass


@dataclasses.dataclass(frozen=True, slots=True)
class Frame:
    type: int
    sequence: int
    timestamp_ns: int
    payload: bytes = b""
    flags: int = 0


def encode(frame: Frame) -> bytes:
    """Encode one AUT/4 binary frame with a compact nine-byte header."""
    if not 0 <= frame.type <= 255:
        raise ValueError("frame type is out of range")
    if not 0 <= frame.flags <= 255:
        raise ValueError("frame flags are out of range")
    if not 0 <= frame.sequence <= 0x7FFF_FFFF:
        raise ValueError("sequence is out of range")
    if len(frame.payload) > MAX_PAYLOAD:
        raise ValueError("payload is too large")
    body = METADATA.pack(frame.timestamp_ns) + frame.payload
    length = len(body)
    return (
        length.to_bytes(3, "big")
        + bytes((frame.type, frame.flags))
        + frame.sequence.to_bytes(4, "big")
        + body
    )


class Decoder:
    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes, max_frames: int | None = None) -> list[Frame]:
        self._buffer.extend(data)
        frames: list[Frame] = []
        while len(self._buffer) >= HEADER_SIZE:
            length = int.from_bytes(self._buffer[0:3], "big")
            frame_type = self._buffer[3]
            flags = self._buffer[4]
            sequence = int.from_bytes(self._buffer[5:9], "big")
            if sequence & 0x8000_0000:
                raise ProtocolError("reserved stream-id bit is set")
            if length < METADATA.size or length > MAX_PAYLOAD + METADATA.size:
                raise ProtocolError(f"invalid frame length: {length}")
            frame_length = HEADER_SIZE + length
            if len(self._buffer) < frame_length:
                break
            timestamp = METADATA.unpack_from(self._buffer, HEADER_SIZE)[0]
            payload_start = HEADER_SIZE + METADATA.size
            payload = bytes(self._buffer[payload_start:frame_length])
            frames.append(Frame(frame_type, sequence, timestamp, payload, flags))
            del self._buffer[:frame_length]
            if max_frames is not None and len(frames) >= max_frames:
                break
        return frames

    def take_pending(self) -> bytes:
        """Transfer undecoded bytes to a different protocol phase."""
        pending = bytes(self._buffer)
        self._buffer.clear()
        return pending


def encode_handshake_request(options: dict[str, str], versions=SUPPORTED_VERSIONS) -> bytes:
    lines = [f"HANDSHAKE AUT/{versions[0]}", f"Versions: {', '.join(versions)}"]
    lines.extend(f"{name}: {value}" for name, value in options.items())
    return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")


def encode_handshake_response(
    version: str, status: int, reason: str, options: dict[str, str]
) -> bytes:
    lines = [f"AUT/{version} {status} {reason}"]
    lines.extend(f"{name}: {value}" for name, value in options.items())
    return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")


class HandshakeDecoder:
    """Incrementally split one cleartext handshake from following binary data."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> tuple[tuple[str, dict[str, str]] | None, bytes]:
        self._buffer.extend(data)
        marker = self._buffer.find(b"\r\n\r\n")
        if marker < 0:
            if len(self._buffer) > MAX_HANDSHAKE:
                raise ProtocolError("handshake is too large")
            return None, b""
        if marker + 4 > MAX_HANDSHAKE:
            raise ProtocolError("handshake is too large")
        block = bytes(self._buffer[:marker])
        remainder = bytes(self._buffer[marker + 4:])
        self._buffer.clear()
        try:
            lines = block.decode("ascii").split("\r\n")
        except UnicodeDecodeError as error:
            raise ProtocolError("handshake is not ASCII") from error
        if not lines or not lines[0]:
            raise ProtocolError("empty handshake")
        options: dict[str, str] = {}
        for line in lines[1:]:
            if ":" not in line:
                raise ProtocolError(f"invalid handshake option: {line}")
            name, value = line.split(":", 1)
            name = name.strip().lower()
            if not name or name in options:
                raise ProtocolError(f"invalid duplicate handshake option: {name}")
            options[name] = value.strip()
        return (lines[0], options), remainder


def negotiate_request(first_line: str, options: dict[str, str]) -> tuple[str, str]:
    """Return the selected version and validated packet transport."""
    prefix = "HANDSHAKE AUT/"
    if not first_line.startswith(prefix):
        raise ProtocolError("expected HANDSHAKE AUT/<version>")
    requested = first_line[len(prefix):]
    offered = [item.strip() for item in options.get("versions", requested).split(",")]
    selected = next((version for version in SUPPORTED_VERSIONS if version in offered), None)
    if selected is None:
        raise ProtocolError("no mutually supported AUT version")
    transport = options.get("transport", "direct").lower()
    if transport not in {"direct", "udp", "tcp", "rudp", "rtcp"}:
        raise ProtocolError(f"unsupported transport: {transport}")
    if options.get("framing", "binary-h2").lower() != "binary-h2":
        raise ProtocolError("unsupported framing; AUT/4 requires binary-h2")
    return selected, transport


def encode_rtcp_packet(packet: bytes) -> bytes:
    if not packet or len(packet) > 65_535:
        raise ProtocolError("RTCP packet length is out of range")
    return RTCP_LENGTH.pack(len(packet)) + packet


class RtcpDecoder:
    """Rebuild length-prefixed IP packets from an AUT RTCP byte stream."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self._buffer.extend(data)
        packets: list[bytes] = []
        while len(self._buffer) >= RTCP_LENGTH.size:
            length = RTCP_LENGTH.unpack_from(self._buffer)[0]
            if length == 0:
                raise ProtocolError("zero-length RTCP packet")
            if len(self._buffer) < RTCP_LENGTH.size + length:
                break
            packets.append(bytes(self._buffer[2:2 + length]))
            del self._buffer[:2 + length]
        return packets
