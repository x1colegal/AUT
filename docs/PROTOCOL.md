# AUT/4 and RATP protocol reference

AUT/4 carries control messages and layer-3 IP packets over Android Open
Accessory USB Bulk. A readable cleartext handshake selects the session before
compact binary framing begins.

## Cleartext handshake

Negotiation is mandatory for Direct and RATP. Lines are ASCII with
CRLF; an empty line ends the block.

```text
HANDSHAKE AUT/4.0
Versions: 4.0
Transport: ratp
Mode: internet-only
Framing: binary-h2


```

```text
AUT/4.0 200 OK
Versions: 4.0; selected
Transport: ratp; accepted
Framing: binary-h2; accepted
Mode: internet-only; accepted
Rejected-Options: none
RATP: window=1024; sack; ack+nack; adaptive-rto; ordered


```

A lower version can be selected only when the client's `Versions` option lists
it. The current implementation supports AUT/4.0. The handshake is limited to
8 KiB and may share a USB transfer with the first binary frame.

## Binary frame

The nine-byte header is inspired by HTTP/2. Integers are unsigned big endian.

| Field | Bytes | Meaning |
| --- | ---: | --- |
| Length | 3 | Timestamp plus payload length. |
| Type | 1 | Frame type. |
| Flags | 1 | `0x01` means `END_MESSAGE`. |
| Sequence | 4 | Nonzero 31-bit sequence; high bit is reserved. |
| Timestamp | 8 | Sender monotonic clock in nanoseconds. |
| Payload | variable | At most 65,536 bytes. |

USB Bulk already has link CRC and retry, so AUT/4 does not duplicate CRC32 in
every binary frame. Decoders retain incomplete data across USB reads.

## Binary types

| Type | Name | Purpose |
| ---: | --- | --- |
| 1/2 | `PING` / `PONG` | Protocol-level diagnostic. |
| 3/4 | `CONFIG_REQUEST` / `CONFIG_RESPONSE` | Client identity and dual-stack lease. |
| 5 | `IP_PACKET` | Packet for the Direct path. |
| 6 | `SESSION_STOP` | Return the USB connection to handshake phase. |
| 7 | `CONFIG_REQUIRED` | Request lease renewal. |
| 8/9 | `CLIENT_READY` / `CLIENT_READY_ACK` | Activate dispatcher registration. |
| 10 | `ICMP6_ECHO` | Dedicated ICMPv6 diagnostic request. |
| 11 | `RATP_DATA` | One IP packet managed by RATP reliability. |

## RATP

RATP means **Reliable AUT Transport Protocol**. It does not create a TCP or UDP
socket and does not add another IP header. `RATP_DATA` contains the original IP
packet obtained from TUN.

### Sliding window

Each direction has an independent 31-bit packet sequence space and allows up
to 1,024 unacknowledged packets in flight. Sending continues while earlier
packets await acknowledgement; RATP is not Stop-and-Wait.

### Cleartext SACK and NACK

RATP control records are raw ASCII lines interleaved with binary frames:

```text
ACK 00000001,00000002,00000005
NACK 00000003,00000004
```

Every value is an eight-digit hexadecimal packet sequence. One line can carry
1 through 256 selected sequences. `ACK` selectively removes those packets from
the sender window. `NACK` requests immediate retransmission of those packets.
Lines are emitted after a short delayed-ACK interval or immediately when a
256-sequence batch fills.

### Ordering and loss detection

The receiver delivers the next expected packet immediately. Later packets are
kept in a 1,024-entry reorder window. A detected sequence gap queues NACKs for
missing packets. When the missing packet arrives, it and every now-contiguous
buffered packet are delivered to TUN in order. Duplicates are ACKed again but
never delivered twice.

### RTO

Every outstanding packet stores its send time. ACK samples update smoothed RTT
and RTT variation; `RTO = SRTT + 4 * RTTVAR`, clamped from 50 ms to 2 seconds.
Retransmitted packets do not contribute RTT samples (Karn's rule). An RTO
retransmits every expired packet, doubles the current RTO up to the maximum,
and aborts a sequence after 12 retries.

### Mixed stream decoding

Binary frames and ASCII ACK/NACK lines can be fragmented, combined, or
interleaved in USB reads. The mixed decoder distinguishes an ACK/NACK record by
its leading ASCII `A`/`N`; valid binary length bytes cannot begin with those
values because AUT's maximum frame length is much smaller.

## Session lifecycle

```text
Android                         Linux
   | cleartext handshake          |
   |----------------------------->|
   | negotiated response          |
   |<-----------------------------|
   | CONFIG_REQUEST/RESPONSE      |
   |<---------------------------->|
   | CLIENT_READY/ACK             |
   |<---------------------------->|
   | binary data + ASCII ACK/NACK |
   |<============================>|
```

`SESSION_STOP` clears RATP windows and reorder state, unregisters routes, and
returns the still-open USB accessory to the cleartext handshake phase. A new
handshake can follow Stop in the same USB read.

## Limits and errors

- Frame payload: 65,536 bytes.
- RATP window: 1,024 packets per direction.
- ACK/NACK batch: 256 sequences per ASCII line.
- RATP retries: 12 per sequence.
- Invalid syntax, sequence, length, window position, or retry exhaustion is a
  protocol error.
- USB timeout remains an idle poll, not a disconnect.
