"""AUT/4 handshake, binary framing, and Reliable AUT Transport Protocol."""

from __future__ import annotations

import dataclasses
import struct
import threading
import time

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
TYPE_RATP_DATA = 11

FLAG_END_MESSAGE = 0x01
MAX_PAYLOAD = 65_536
MAX_HANDSHAKE = 8_192
MAX_CONTROL_SEQUENCES = 256
MAX_CONTROL_LINE = 2_400
HEADER_SIZE = 9
METADATA = struct.Struct(">Q")


class ProtocolError(ValueError):
    pass


@dataclasses.dataclass(frozen=True, slots=True)
class Frame:
    type: int
    sequence: int
    timestamp_ns: int
    payload: bytes = b""
    flags: int = 0


@dataclasses.dataclass(frozen=True, slots=True)
class ControlRecord:
    kind: str
    sequences: tuple[int, ...]


def encode(frame: Frame) -> bytes:
    """Encode one AUT/4 binary frame with a compact nine-byte header."""
    if not 0 <= frame.type <= 255:
        raise ValueError("frame type is out of range")
    if not 0 <= frame.flags <= 255:
        raise ValueError("frame flags are out of range")
    if not 0 < frame.sequence <= 0x7FFF_FFFF:
        raise ValueError("sequence is out of range")
    if len(frame.payload) > MAX_PAYLOAD:
        raise ValueError("payload is too large")
    body = METADATA.pack(frame.timestamp_ns) + frame.payload
    return (
        len(body).to_bytes(3, "big")
        + bytes((frame.type, frame.flags))
        + frame.sequence.to_bytes(4, "big")
        + body
    )


def encode_control(kind: str, sequences) -> bytes:
    """Encode one cleartext selective ACK/NACK line."""
    kind = kind.upper()
    if kind not in {"ACK", "NACK"}:
        raise ValueError("control kind must be ACK or NACK")
    values = tuple(dict.fromkeys(int(value) for value in sequences))
    if not values or len(values) > MAX_CONTROL_SEQUENCES:
        raise ValueError("control line must contain 1 through 256 sequences")
    if any(value <= 0 or value > 0x7FFF_FFFF for value in values):
        raise ValueError("control sequence is out of range")
    return f"{kind} {','.join(f'{value:08X}' for value in values)}\r\n".encode("ascii")


def _parse_control(line: bytes) -> ControlRecord:
    try:
        text = line.decode("ascii")
    except UnicodeDecodeError as error:
        raise ProtocolError("RATP control line is not ASCII") from error
    kind, separator, values = text.partition(" ")
    if kind not in {"ACK", "NACK"} or not separator or not values:
        raise ProtocolError("invalid RATP control line")
    tokens = values.split(",")
    if len(tokens) > MAX_CONTROL_SEQUENCES:
        raise ProtocolError("RATP control line exceeds 256 sequences")
    try:
        sequences = tuple(int(token, 16) for token in tokens)
    except ValueError as error:
        raise ProtocolError("invalid RATP control sequence") from error
    if any(len(token) != 8 for token in tokens) or any(
        sequence <= 0 or sequence > 0x7FFF_FFFF for sequence in sequences
    ):
        raise ProtocolError("invalid RATP control sequence")
    return ControlRecord(kind, sequences)


class WireDecoder:
    """Decode interleaved AUT binary frames and cleartext RATP controls."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(
        self, data: bytes, max_records: int | None = None
    ) -> list[Frame | ControlRecord]:
        self._buffer.extend(data)
        records: list[Frame | ControlRecord] = []
        while self._buffer:
            if self._buffer[0] in (ord("A"), ord("N")):
                marker = self._buffer.find(b"\r\n")
                if marker < 0:
                    if len(self._buffer) > MAX_CONTROL_LINE:
                        raise ProtocolError("RATP control line is too large")
                    break
                records.append(_parse_control(bytes(self._buffer[:marker])))
                del self._buffer[:marker + 2]
            else:
                if len(self._buffer) < HEADER_SIZE:
                    break
                length = int.from_bytes(self._buffer[0:3], "big")
                frame_type = self._buffer[3]
                flags = self._buffer[4]
                sequence = int.from_bytes(self._buffer[5:9], "big")
                if sequence == 0 or sequence & 0x8000_0000:
                    raise ProtocolError("invalid or reserved stream ID")
                if length < METADATA.size or length > MAX_PAYLOAD + METADATA.size:
                    raise ProtocolError(f"invalid frame length: {length}")
                frame_length = HEADER_SIZE + length
                if len(self._buffer) < frame_length:
                    break
                timestamp = METADATA.unpack_from(self._buffer, HEADER_SIZE)[0]
                payload_start = HEADER_SIZE + METADATA.size
                payload = bytes(self._buffer[payload_start:frame_length])
                records.append(Frame(frame_type, sequence, timestamp, payload, flags))
                del self._buffer[:frame_length]
            if max_records is not None and len(records) >= max_records:
                break
        return records

    def take_pending(self) -> bytes:
        pending = bytes(self._buffer)
        self._buffer.clear()
        return pending


# The old name remains an alias for callers that only expect binary frames.
Decoder = WireDecoder


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
    prefix = "HANDSHAKE AUT/"
    if not first_line.startswith(prefix):
        raise ProtocolError("expected HANDSHAKE AUT/<version>")
    requested = first_line[len(prefix):]
    offered = [item.strip() for item in options.get("versions", requested).split(",")]
    selected = next((version for version in SUPPORTED_VERSIONS if version in offered), None)
    if selected is None:
        raise ProtocolError("no mutually supported AUT version")
    transport = options.get("transport", "direct").lower()
    if transport not in {"direct", "ratp"}:
        raise ProtocolError(f"unsupported transport: {transport}")
    if options.get("framing", "binary-h2").lower() != "binary-h2":
        raise ProtocolError("unsupported framing; AUT/4 requires binary-h2")
    return selected, transport


@dataclasses.dataclass(slots=True)
class _Pending:
    frame: Frame
    sent_ns: int
    retransmitted: bool = False
    retries: int = 0


@dataclasses.dataclass(frozen=True, slots=True)
class RatpWork:
    controls: tuple[bytes, ...] = ()
    retransmissions: tuple[Frame, ...] = ()


class RatpEngine:
    """Sliding-window reliable, selective-ACKed, ordered packet transport."""

    WINDOW = 1024
    ACK_DELAY_NS = 5_000_000
    MIN_RTO_NS = 50_000_000
    MAX_RTO_NS = 2_000_000_000
    INITIAL_RTO_NS = 250_000_000
    MAX_RETRIES = 12

    def __init__(self) -> None:
        self._lock = threading.Condition()
        self._send_sequence = 1
        self._receive_next = 1
        self._pending: dict[int, _Pending] = {}
        self._reorder: dict[int, bytes] = {}
        self._acks: set[int] = set()
        self._nacks: set[int] = set()
        self._control_since_ns: int | None = None
        self._srtt_ns: float | None = None
        self._rttvar_ns: float | None = None
        self.rto_ns = self.INITIAL_RTO_NS

    @property
    def pending_count(self) -> int:
        with self._lock:
            return len(self._pending)

    def send_packet(self, packet: bytes, wait_seconds: float = 2.0) -> Frame:
        if not packet or len(packet) > MAX_PAYLOAD:
            raise ProtocolError("RATP packet length is out of range")
        deadline = time.monotonic() + wait_seconds
        with self._lock:
            while len(self._pending) >= self.WINDOW:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ProtocolError("RATP send window is full")
                self._lock.wait(remaining)
            if self._send_sequence > 0x7FFF_FFFF:
                raise ProtocolError("RATP sequence space exhausted; renegotiate the session")
            now = time.monotonic_ns()
            frame = Frame(
                TYPE_RATP_DATA, self._send_sequence, now, packet, FLAG_END_MESSAGE
            )
            self._pending[frame.sequence] = _Pending(frame, now)
            self._send_sequence += 1
            return frame

    def receive(self, frame: Frame) -> list[bytes]:
        if frame.type != TYPE_RATP_DATA:
            raise ProtocolError("expected RATP_DATA")
        now = time.monotonic_ns()
        delivered: list[bytes] = []
        with self._lock:
            sequence = frame.sequence
            self._queue_control(self._acks, sequence, now)
            self._nacks.discard(sequence)
            if sequence < self._receive_next:
                return delivered
            if sequence >= self._receive_next + self.WINDOW:
                raise ProtocolError("RATP packet is outside the receive window")
            if sequence > self._receive_next:
                self._reorder.setdefault(sequence, frame.payload)
                for missing in range(self._receive_next, sequence):
                    if missing not in self._reorder:
                        self._queue_control(self._nacks, missing, now)
                return delivered
            delivered.append(frame.payload)
            self._receive_next += 1
            while self._receive_next in self._reorder:
                delivered.append(self._reorder.pop(self._receive_next))
                self._nacks.discard(self._receive_next)
                self._receive_next += 1
            return delivered

    def handle_control(self, control: ControlRecord) -> tuple[Frame, ...]:
        now = time.monotonic_ns()
        retransmissions: list[Frame] = []
        with self._lock:
            if control.kind == "ACK":
                for sequence in control.sequences:
                    pending = self._pending.pop(sequence, None)
                    if pending is not None and not pending.retransmitted:
                        self._update_rto(now - pending.sent_ns)
                self._lock.notify_all()
            else:
                for sequence in control.sequences:
                    pending = self._pending.get(sequence)
                    if pending is not None:
                        self._retransmit(pending, now)
                        retransmissions.append(pending.frame)
            return tuple(retransmissions)

    def maintenance(self, force_controls: bool = False) -> RatpWork:
        now = time.monotonic_ns()
        retransmissions: list[Frame] = []
        timed_out = False
        with self._lock:
            for pending in self._pending.values():
                if now - pending.sent_ns >= self.rto_ns:
                    self._retransmit(pending, now)
                    retransmissions.append(pending.frame)
                    timed_out = True
            if timed_out:
                self.rto_ns = min(self.MAX_RTO_NS, self.rto_ns * 2)
            ready = (
                force_controls
                or len(self._acks) >= MAX_CONTROL_SEQUENCES
                or len(self._nacks) >= MAX_CONTROL_SEQUENCES
                or (
                    self._control_since_ns is not None
                    and now - self._control_since_ns >= self.ACK_DELAY_NS
                )
            )
            controls = self._drain_controls() if ready else []
        return RatpWork(tuple(controls), tuple(retransmissions))

    def _queue_control(self, target: set[int], sequence: int, now: int) -> None:
        target.add(sequence)
        if self._control_since_ns is None:
            self._control_since_ns = now

    def _drain_controls(self) -> list[bytes]:
        lines: list[bytes] = []
        for kind, values in (("ACK", self._acks), ("NACK", self._nacks)):
            ordered = sorted(values)
            for offset in range(0, len(ordered), MAX_CONTROL_SEQUENCES):
                lines.append(encode_control(kind, ordered[offset:offset + 256]))
            values.clear()
        self._control_since_ns = None
        return lines

    def _retransmit(self, pending: _Pending, now: int) -> None:
        if pending.retries >= self.MAX_RETRIES:
            raise ProtocolError(
                f"RATP sequence {pending.frame.sequence:08X} exceeded retry limit"
            )
        pending.sent_ns = now
        pending.retransmitted = True
        pending.retries += 1

    def _update_rto(self, sample_ns: int) -> None:
        if self._srtt_ns is None:
            self._srtt_ns = float(sample_ns)
            self._rttvar_ns = sample_ns / 2.0
        else:
            assert self._rttvar_ns is not None
            self._rttvar_ns = 0.75 * self._rttvar_ns + 0.25 * abs(
                self._srtt_ns - sample_ns
            )
            self._srtt_ns = 0.875 * self._srtt_ns + 0.125 * sample_ns
        calculated = int(self._srtt_ns + 4 * self._rttvar_ns)
        self.rto_ns = max(self.MIN_RTO_NS, min(self.MAX_RTO_NS, calculated))
