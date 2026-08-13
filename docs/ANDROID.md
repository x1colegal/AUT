# Android app guide

AUT's Android side is a terminal-buildable Java application using Material 3,
Android USB Accessory APIs, and `VpnService`.

## Requirements

- Android 6.0/API 23 or newer on the phone.
- Android SDK with compile SDK 36.
- A JDK compatible with Android Gradle Plugin 8.13; JDK 17 is recommended.
- USB cable capable of data, not charge-only.
- A phone whose USB device stack supports Android Open Accessory mode.

## Build without Android Studio

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The current project produces a debug-signed APK. Do not treat the debug signing
key as a production release identity.

## Permissions

AUT uses:

- `INTERNET` for local loopback sockets and normal networking APIs;
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` to keep the
  USB/VPN bridge alive in the background;
- `POST_NOTIFICATIONS` on Android versions that require it;
- `BIND_VPN_SERVICE` on the non-exported VPN service;
- Android USB Accessory permission for the attached AUT accessory.

The app does not need Android root.

## Modes

### AUTPing only

AUTPing is a protocol-level diagnostic. Android sends AUT `PING` frames and
Linux returns `PONG` frames with the same sequence and timestamp. It measures
the framing/USB path without creating a VPN or depending on IP.

Statistics include:

- packets sent and received;
- loss after a three-second timeout;
- last, minimum, average, and maximum RTT;
- average absolute RTT variation shown as jitter.

### ICMPv6 Ping only

The app requests an address lease and establishes a TUN with only a `/128` route
to the AUT IPv6 gateway. It constructs a complete IPv6 packet containing an
ICMPv6 Echo Request, including pseudo-header checksum, identifier, sequence, and
timestamp.

The diagnostic packet uses the dedicated AUT control frame `ICMP6_ECHO`, so it
can test IPv6/ICMPv6 independently from NAT and internet forwarding. Linux
returns the complete Echo Reply as an `IP_PACKET` frame.

### Internet only

The app requests the dual-stack lease, adds configured routes and DNS servers,
establishes the VPN TUN, and forwards application packets over AUT. Internet
mode intentionally runs no AUTPing or ICMPv6 periodic diagnostic.

## Packet path choices

### Direct

The TUN reader sends packets directly into AUT frames. Incoming AUT IP packets
are written directly to the TUN. This is the recommended path and has the least
overhead.

### RATP

The TUN connects directly to the AUT bridge without a loopback socket. RATP
wraps each IP packet in `RATP_DATA`, keeps multiple packets in a sliding window,
processes cleartext selective ACK/NACK records, retransmits on NACK or adaptive
RTO, and reorders received packets before writing them to TUN. It does not call
`VpnService.protect()` because it creates no IP socket.

## Session lifecycle

The service runs in the foreground and stores the selected mode and path. If
Android recreates the service, AUT attempts to restore that configuration.

Intentional Stop or mode change sends `SESSION_STOP` before closing USB. Linux
then removes the session from the shared dispatcher without treating the close
as a fault. Blocking USB/TUN cleanup happens on an `aut-shutdown` worker instead
of Android's main thread to avoid application-not-responding dialogs.

An unexpected USB, TUN, RATP, handshake, lease, or protocol error does not stop
the selected mode. AUT immediately closes the broken descriptors and attempts
to reopen USB with the same mode and Direct/RATP selection. It renegotiates
AUT/4, requests the lease again, recreates the TUN, and resumes forwarding
without another user tap. Failed attempts retry immediately with no backoff.
Only an explicit **Stop AUT**, a mode change, or revoked VPN permission cancels
the current recovery cycle.

AOA re-enumeration is received by a no-display attachment dispatcher. When AUT
is already active, reconnecting USB does not launch or bring the control screen
to the foreground. Opening the app manually restores the persisted running
status and Direct/RATP selection instead of presenting an idle “choose a mode”
state. Clearing activity history removes only old entries and preserves the
current connection status.

The latest 80 activity entries are persisted in app preferences. Closing and
reopening the activity does not erase them. **Clear history** removes activity
entries; **Clear diagnostics** resets ping counters independently.

## VPN behavior to remember

- Android normally allows only one active VPN per user/profile.
- Starting AUT Internet or ICMPv6 mode can replace another VPN.
- Always-on VPN support is declared, but AUT still depends on a connected AOA
  USB accessory and Linux server.
- Vendor battery management can still affect foreground services; behavior
  varies by device.
