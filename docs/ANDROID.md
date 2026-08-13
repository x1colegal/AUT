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

### UDP

AUT creates two protected UDP sockets connected over IPv6 loopback `[::1]`.
Packets cross that local relay before entering AUT framing or the TUN. USB is
still the external transport.

### TCP

AUT creates a protected TCP connection on `[::1]`. Each packet receives a
two-byte length prefix. Some Android kernels reject `VpnService.protect()` for
local TCP sockets; use Direct or UDP when the app reports a protection error.

## Session lifecycle

The service runs in the foreground and stores the selected mode and path. If
Android recreates the service, AUT attempts to restore that configuration.

Intentional Stop or mode change sends `SESSION_STOP` before closing USB. Linux
then removes the session from the shared dispatcher without treating the close
as a fault. Blocking USB/TUN cleanup happens on an `aut-shutdown` worker instead
of Android's main thread to avoid application-not-responding dialogs.

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
