# Architecture and contributor map

This document maps AUT's source files, threads, ownership rules, and common
change points.

## Repository layout

```text
AUT/
├── aut_protocol.py          Python frame encoder/decoder
├── aut_server.py            USB discovery, leases, sessions, shared TUN
├── setup-linux.sh           privileged TUN/nftables preparation
├── teardown-linux.sh        remove AUT Linux resources
├── requirements.txt         Python dependency pin
├── tests/                   Python protocol and lease tests
├── app/
│   ├── build.gradle         Android application configuration
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/aut/usbping/
│       │   ├── AutApplication.java
│       │   ├── AutEventLog.java
│       │   ├── AutProtocol.java
│       │   ├── AutVpnService.java
│       │   ├── RatpTransport.java
│       │   ├── MainActivity.java
│       │   └── PingStats.java
│       └── res/             Material 3 UI, icons, strings, USB filter
└── docs/                    user and protocol documentation
```

## Python components

### `BulkConnection`

Claims the AOA USB interface, selects its Bulk IN/OUT endpoints, serializes
writes with a lock, and releases libusb resources on close. A USB transfer may
contain one or many encoded AUT frames.

### `LeaseManager`

Validates the shared IPv4 and ULA prefixes. It derives stable candidates from
the Android `client_id`, resolves collisions among active leases, and combines
the selected client `/64` with Android's Host ID.

### `TunGateway`

Owns the single `aut0` file descriptor and one downlink dispatcher thread.
Android-to-Linux packets are serialized into the shared TUN. Linux-to-Android
packets are read in short batches, grouped by destination owner, encoded as AUT
frames, and written to each matching USB connection.

### `ClientSession`

Each AOA phone receives one session thread and decoder. The session handles
PING/PONG, configuration, readiness, ICMPv6 diagnostics, IP packets, counters,
and graceful stop. It registers/unregisters its assigned addresses with the
shared TUN dispatcher.

### `AutServer`

Owns global leases, the shared TUN, active session mapping, USB probe memory,
and the periodic USB scanner. It does not block one client while another is
idle.

## Android components

### `MainActivity`

Material 3 control surface. It discovers the AOA accessory, requests USB/VPN
permission, starts modes, selects Direct/RATP, renders persisted logs and
diagnostics, and exposes independent clear actions.

### `AutVpnService`

The foreground service owns the accessory file descriptor, VpnService TUN,
protocol reader, packet pumps, ping schedules, RATP, session generation, and
shutdown. A generation integer prevents threads from an old mode from touching
a newer session after a fast mode switch.

Important worker threads:

| Thread | Responsibility |
| --- | --- |
| `aut-usb-reader` | Negotiate cleartext AUT/4, then decode binary frames. |
| `aut-tun-reader` | Read Android TUN packets and send them toward USB. |
| scheduler | AUTPing, ICMPv6 Ping, readiness retries, and lease timeout. |
| `aut-shutdown` | Potentially blocking USB/TUN cleanup. |

### `AutProtocol`

Java counterpart to `aut_protocol.py`. Both implement the cleartext handshake,
nine-byte binary header and mixed ASCII RATP controls and must be updated together.

### `RatpTransport`

Implements RATP's sliding send window, selective ACK/NACK handling, reorder
buffer, adaptive RTO, and retransmission state on Android.

### `PingStats` and `AutEventLog`

`PingStats` calculates timeout-based loss, RTT summaries, and jitter.
`AutEventLog` keeps the last 80 timestamped status events in preferences so the
activity can be destroyed without losing diagnostics.

## Concurrency and ownership

- Only `TunGateway` owns and reads the shared Linux TUN.
- TUN writes from clients use a lock so packet writes cannot interleave.
- Each `BulkConnection` serializes USB OUT writes.
- The dispatcher mapping is protected by `clients_lock`.
- Lease allocation is protected by the lease-manager lock.
- Android USB writes synchronize on `usbWriteLock`.
- Android mode switches invalidate older threads through `sessionGeneration`.
- Shutdown must not perform driver operations on Android's main thread.

## Adding a protocol frame

1. Add the numeric constant to `aut_protocol.py`.
2. Add the same constant to `AutProtocol.java`.
3. Implement send/receive behavior on both applicable sides.
4. Add a decoder round-trip frame to `tests/test_protocol.py`.
5. Update `docs/PROTOCOL.md` and the README when user-visible.
6. Build Android and run Python tests.

Never renumber existing frame types; deployed clients may still use them.

## Changing addressing

Address-plan changes affect more than the JSON lease. Review:

- `LeaseManager` allocation and validation;
- `make_client_config()`;
- `validate_tun_configuration()`;
- `setup-linux.sh` addresses and nftables source prefixes;
- Android `VpnService.Builder` address/route application;
- downlink destination registration;
- Linux and protocol documentation.

## Verification checklist

```bash
python3 -m unittest discover -v
./gradlew lintDebug assembleDebug
```

Runtime verification should cover:

1. App closed while AOA remains connected: no timeout/reconnect spam.
2. AUTPing only: PING/PONG statistics and no VPN prompt.
3. ICMPv6 Ping only: Echo Replies and no default-route capture.
4. Internet only Direct: IPv4, IPv6 when available, DNS, upload, download.
5. Stop and rapid mode changes: no ANR and clean `SESSION_STOP` handling.
6. Two phones: distinct leases and correct destination dispatch.

Local unit tests and compilation do not prove a particular phone/kernel/USB
controller combination; include real-device logs with runtime results.
