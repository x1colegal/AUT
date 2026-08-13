# AUT/3 protocol reference

AUT/3 multiplexes control messages and layer-3 IP packets over Android Open
Accessory USB Bulk endpoints. It does not provide an Ethernet layer.

## Transport

- Android Open Accessory mode identifies the Linux host as the USB host and
  Android as the USB accessory endpoint provider.
- Frames can be fragmented across USB reads.
- Multiple frames can be combined in one USB transfer.
- Both decoders keep pending bytes until a complete frame exists.
- Linux batches downlink frames to reduce one-transfer-per-packet overhead.

## Binary frame

All integers are unsigned and encoded in network byte order (big endian).

| Field | Bytes | Description |
| --- | ---: | --- |
| Magic | 4 | Historical literal `AUT1`. |
| Framing version | 1 | Currently `1`. |
| Type | 1 | Message type listed below. |
| Flags | 2 | Reserved; currently zero. |
| Sequence | 4 | Sender-selected sequence number. |
| Payload length | 4 | Bytes following the 24-byte header, maximum 65,536. |
| Monotonic timestamp | 8 | Sender clock value in nanoseconds. |
| Payload | variable | Type-specific data. |
| CRC32 | 4 | CRC32 over header and payload. |

The framing magic/version remained stable while the application protocol
evolved to AUT/3. This preserves decoder compatibility.

## Frame types

| Type | Name | Direction | Payload |
| ---: | --- | --- | --- |
| 1 | `PING` | Android → Linux | Optional opaque bytes. |
| 2 | `PONG` | Linux → Android | Mirrors PING sequence, timestamp, and payload. |
| 3 | `CONFIG_REQUEST` | Android → Linux | UTF-8 JSON identity and requested relay. |
| 4 | `CONFIG_RESPONSE` | Linux → Android | UTF-8 JSON dual-stack lease. |
| 5 | `IP_PACKET` | Either | One complete IPv4 or IPv6 packet. |
| 6 | `SESSION_STOP` | Android → Linux | Empty. |
| 7 | `CONFIG_REQUIRED` | Linux → Android | Empty; requests identity/lease renewal. |
| 8 | `CLIENT_READY` | Android → Linux | Empty; Android TUN is established. |
| 9 | `CLIENT_READY_ACK` | Linux → Android | Empty; dispatcher registration is active. |
| 10 | `ICMP6_ECHO` | Android → Linux | Complete IPv6 ICMPv6 Echo Request. |

## Configuration request

Example:

```json
{
  "protocol": 3,
  "relay": "direct",
  "client_id": "c3aa0e16-0000-4000-8000-000000000000",
  "host_id": "60a3f97cc7895fea"
}
```

- `client_id` is generated once by the Android installation and persisted.
- `host_id` is a nonzero random 64-bit hexadecimal interface identifier.
- `relay` is `direct`, `udp`, or `tcp` and describes Android's internal path.

## Configuration response

Example shape:

```json
{
  "lease_seconds": 86400,
  "mtu": 1400,
  "dhcp4": {"address": "10.77.0.229", "prefix": 24},
  "dhcp6": {"address": "fd77:4155:5400:f48c:60a3:f97c:c789:5fea", "prefix": 64},
  "gateway6": "fd77:4155:5400::1",
  "slaac": {
    "site_prefix": "fd77:4155:5400::/48",
    "prefix": "fd77:4155:5400:f48c::/64"
  },
  "dns": ["192.168.1.1"],
  "routes": ["0.0.0.0/0", "::/0"]
}
```

These names describe DHCP/SLAAC-equivalent control-plane behavior. Android's
layer-3 VpnService does not exchange Ethernet DHCP broadcasts or IPv6 Router
Advertisements; the app applies the received values directly to its TUN.

## Internet handshake

```text
Android                         Linux
   │ CONFIG_REQUEST               │
   ├─────────────────────────────>│ allocate/restore lease
   │ CONFIG_RESPONSE              │
   │<─────────────────────────────┤
   │ establish VpnService TUN     │
   │ CLIENT_READY                 │
   ├─────────────────────────────>│ register destination addresses
   │ CLIENT_READY_ACK             │
   │<─────────────────────────────┤
   │ IP_PACKET traffic            │
   │<────────────────────────────>│
```

Android retransmits `CLIENT_READY` until acknowledged. Linux queues up to 128
early client packets during readiness. `CONFIG_REQUIRED` repairs a live Android
VPN after a USB or Python session restart.

## Multiclient leases and dispatch

Linux derives candidates from keyed BLAKE2s hashes of `client_id`:

- one address from the configured IPv4 pool;
- one `/64` index inside the configured site ULA.

Active collisions are resolved by scanning for the next free candidate. The
Android-provided Host ID becomes the low 64 bits of its IPv6 address.

The shared TUN reader extracts the destination address from each downlink IP
packet and finds the owning client session. Packets for different clients are
grouped and written to their respective USB connections.

## ICMPv6 diagnostic

`ICMP6_ECHO` carries a real IPv6 packet but uses the AUT control plane so the
diagnostic does not depend on TUN dispatcher readiness, Linux routing, or NAT.
Linux verifies that it is an Echo Request for the configured AUT gateway,
swaps source/destination addresses, changes ICMPv6 type 128 to 129, and
recalculates the pseudo-header checksum. It returns the Echo Reply in an
`IP_PACKET` frame.

## Error handling

- Invalid magic, version, length, or CRC raises a protocol error.
- Invalid IP packet shapes are rejected before writing to TUN.
- USB read timeouts are idle polls, not disconnects.
- `errno 110` and libusb timeout code `-7` are also treated as idle timeouts.
- Intentional `SESSION_STOP` unregisters the client but keeps the USB accessory
  connection available for the next mode.
