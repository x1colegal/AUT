# AUT/4 protocol reference

AUT/4 carries control messages and layer-3 IP traffic over Android Open
Accessory USB Bulk. It separates a readable cleartext handshake from a compact
binary data phase. AUT does not emulate Ethernet; DHCP and SLAAC names describe
configuration semantics, not Ethernet broadcasts.

## Phase 1: cleartext handshake

Android speaks first. Lines are ASCII with CRLF, and an empty line ends the
handshake, like an HTTP/1.1 header block.

This negotiation is mandatory for every path: Direct, UDP, TCP, RUDP, and
RTCP. It establishes the AUT version and binary framing before any
path-specific traffic begins.

```text
HANDSHAKE AUT/4.0
Versions: 4.0
Transport: rudp
Mode: internet-only
Framing: binary-h2


```

The server selects a mutually supported version and reports its decisions:

```text
AUT/4.0 200 OK
Versions: 4.0; selected
Transport: rudp; accepted
Framing: binary-h2; accepted
Mode: internet-only; accepted
Rejected-Options: none
RUDP: supported
RTCP: supported


```

A lower version may be selected only if it appears in the client's `Versions`
list. The current implementation supports `4.0`; it never silently starts an
incompatible older wire format. The handshake is limited to 8 KiB.

USB reads are not message boundaries. The decoder accepts a fragmented
handshake and preserves binary bytes arriving after the empty line in the same
USB transfer.

## Phase 2: binary frames

Every subsequent message has a nine-byte header inspired by HTTP/2. Integers
are unsigned and big endian.

| Field | Bytes | Description |
| --- | ---: | --- |
| Length | 3 | Bytes after the header: timestamp plus payload. |
| Type | 1 | Message type. |
| Flags | 1 | Type flags; `0x01` means `END_MESSAGE`. |
| Stream ID | 4 | 31-bit sequence; the high reserved bit is zero. |
| Monotonic timestamp | 8 | Sender clock in nanoseconds. |
| Payload | variable | Type-specific binary data, at most 65,536 bytes. |

AUT/4 has no per-frame magic or CRC32. Negotiation establishes the format, and
USB Bulk already provides link CRC, retry, ordering, and duplicate protection.
Removing duplicated bytes lowers hot-path overhead. Frames can cross USB reads,
and one transfer can contain many frames.

## Frame types

| Type | Name | Direction | Payload |
| ---: | --- | --- | --- |
| 1 | `PING` | Android to Linux | Optional opaque bytes. |
| 2 | `PONG` | Linux to Android | Mirrored PING metadata and payload. |
| 3 | `CONFIG_REQUEST` | Android to Linux | UTF-8 JSON client identity. |
| 4 | `CONFIG_RESPONSE` | Linux to Android | UTF-8 JSON dual-stack lease. |
| 5 | `IP_PACKET` | Either | One IP packet for Direct/UDP/TCP. |
| 6 | `SESSION_STOP` | Android to Linux | Empty. |
| 7 | `CONFIG_REQUIRED` | Linux to Android | Requests lease renewal. |
| 8 | `CLIENT_READY` | Android to Linux | Android TUN is ready. |
| 9 | `CLIENT_READY_ACK` | Linux to Android | Dispatcher is active. |
| 10 | `ICMP6_ECHO` | Android to Linux | Complete ICMPv6 Echo Request. |
| 11 | `RUDP_PACKET` | Either | Exactly one complete IP datagram. |
| 12 | `RTCP_DATA` | Either | Segment of the reliable packet stream. |

## Packet-path negotiation

| Transport | Android local path | USB representation |
| --- | --- | --- |
| `direct` | TUN directly to bridge | `IP_PACKET` per packet. |
| `udp` | Protected UDP sockets on `[::1]` | `IP_PACKET` after relay. |
| `tcp` | Protected TCP connection on `[::1]` | `IP_PACKET` after relay. |
| `rudp` | TUN directly to bridge | `RUDP_PACKET` per datagram. |
| `rtcp` | TUN directly to bridge | `RTCP_DATA` stream segments. |

RUDP and RTCP are AUT profiles, not IP sockets. They terminate in the Python
server inside USB frames and use USB Bulk reliability and ordering. They avoid
a redundant acknowledgement layer that would reduce throughput.

### RUDP

Each `RUDP_PACKET` contains one whole IPv4 or IPv6 packet and sets
`END_MESSAGE`. Datagram boundaries are preserved.

### RTCP

Each IP packet enters an ordered byte stream as a two-byte big-endian length
followed by packet bytes. The stream is split into `RTCP_DATA` frames up to
16 KiB. A persistent decoder rebuilds packets across AUT-frame and USB-read
boundaries.

## Configuration

The binary `CONFIG_REQUEST` follows a successful handshake:

```json
{
  "protocol": 4,
  "relay": "rtcp",
  "client_id": "c3aa0e16-0000-4000-8000-000000000000",
  "host_id": "60a3f97cc7895fea"
}
```

`relay` remains for diagnostics; the handshake is authoritative. The response
contains `mtu`, DHCPv4-equivalent address/prefix, DHCPv6-equivalent address,
gateway, delegated SLAAC `/64`, inherited DNS, and routes.

```text
Android                              Linux
   | HANDSHAKE AUT/4.0 + options       |
   |----------------------------------->|
   | AUT/4.0 200 OK + decisions         |
   |<-----------------------------------|
   | CONFIG_REQUEST                     |
   |----------------------------------->| allocate or restore lease
   | CONFIG_RESPONSE                    |
   |<-----------------------------------|
   | establish VpnService TUN           |
   | CLIENT_READY                       |
   |----------------------------------->| register destination addresses
   | CLIENT_READY_ACK                   |
   |<-----------------------------------|
   | negotiated binary packet frames    |
   |<==================================>|
```

Android repeats `CLIENT_READY` until acknowledged. Linux queues up to 128 early
packets. `CONFIG_REQUIRED` repairs a live VPN after a server restart.

## Errors and limits

- Invalid handshake syntax, version, transport, framing, reserved stream bit,
  or frame length is a protocol error.
- Frame payload is limited to 65,536 bytes; an RTCP IP packet to 65,535.
- Invalid IP shapes are rejected before entering the Linux TUN.
- USB timeout is an idle poll, not a disconnect.
- `SESSION_STOP` removes routing, resets binary decoding, and returns the
  reusable accessory connection to the cleartext handshake phase.
