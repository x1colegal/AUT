# AUT — Advanced USB Tethering

AUT gives Android a real IPv4/IPv6 connection through a Linux computer using
raw **USB Bulk** transfers. It does not use Android's built-in USB tethering,
RNDIS, ADB forwarding, or a TCP/UDP connection between the phone and computer.

The Android app creates a `VpnService` TUN interface. The Python server moves
those IP packets through Android Open Accessory (AOA) USB frames and a shared
Linux TUN named `aut0`. Linux then forwards and NATs the traffic to the real
internet connection.

> AUT is experimental networking software. It is useful, working, and fun to
> explore, but it is not yet a drop-in replacement for every production VPN or
> tethering implementation.

## What AUT can do

- Carry IPv4 and IPv6 directly over USB Bulk.
- Share one Linux gateway with multiple Android devices at the same time.
- Allocate a stable IPv4 address and a client-specific IPv6 `/64`.
- Inherit the DNS servers received by the Linux uplink.
- Provide NAT44, NAT66, forwarding, and nftables software flow offload.
- Run the Android app without root.
- Run the Python server as a normal user after Linux is prepared.
- Test the raw USB protocol with AUTPing, without any IP dependency.
- Test a complete checksummed ICMPv6 Echo Request/Reply.
- Choose either the lowest-overhead Direct path or reliable USB-native RATP.
- Reconnect clients and restore sessions without restarting the server.
- Reopen USB and rebuild the VPN automatically after an I/O failure.

## How it fits together

```text
Android applications
        │ IPv4 / IPv6
Android VpnService TUN
        │ Direct or USB-native RATP
AUT Android bridge
        │ AUT/4 cleartext handshake, then compact binary frames
Android Open Accessory USB Bulk
        │ raw AUT/4 frames
Python multiclient server
        │ shared Linux TUN: aut0
Linux forwarding + nftables NAT44/NAT66
        │
Wi-Fi / Ethernet / another Linux uplink
        │
Internet
```

Both paths connect VpnService directly to the AUT USB bridge. RATP additionally
provides AUT-level reliability around IP packets inside AUT/4 USB frames.
The physical phone-to-computer link remains raw USB Bulk in every mode.

## Root requirements

| Component | Root required? | Why? |
| --- | --- | --- |
| Android app | No | Uses the public `VpnService` and USB Accessory APIs. |
| Python server | No | Opens a TUN already assigned to the current Linux user. |
| `setup-linux.sh` | Yes, once per boot/setup | Creates `aut0`, enables forwarding, and installs nftables rules. |

## Quick start

### 1. Clone and install Linux dependencies

```bash
git clone https://github.com/x1colegal/AUT.git
cd AUT

sudo apt install python3 python3-venv python3-pip libusb-1.0-0 nftables iproute2
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### 2. Find the Linux internet interface

```bash
ip route get 1.1.1.1
```

Look for the name after `dev`. Examples include `wlp2s0`, `wlan0`, and `eth0`.
The documentation below uses `wlp2s0`; replace it with your real interface.

### 3. Prepare the Linux gateway

```bash
sudo ./setup-linux.sh wlp2s0 "$USER"
```

This creates:

- `aut0` owned by your user;
- `10.77.0.1/24` on Linux;
- `fd77:4155:5400::1/48` on Linux;
- IPv4 and IPv6 forwarding;
- nftables NAT44/NAT66 and a software flowtable.

### 4. Start the server as your normal user

```bash
.venv/bin/python aut_server.py --uplink wlp2s0
```

You do not need `--device`, `lsusb`, or a VID:PID. AUT scans USB devices,
requests AOA mode from compatible Android phones, and creates one worker per
connected phone.

### 5. Build and install the Android app

AUT builds entirely from the terminal; Android Studio is optional.

Requirements: Android SDK, platform/API 36, build tools, and a JDK supported by
Android Gradle Plugin 8.13 (JDK 17 is the safe choice).

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 6. Connect and choose a mode

1. Connect Android over USB.
2. Accept the Android USB Accessory permission dialog.
3. Open AUT and grant VPN permission when an IP-based mode requests it.
4. Choose a packet path. Start with **Direct**.
5. Choose AUTPing only, ICMPv6 Ping only, or Internet only.

Successful Internet mode produces server output similar to:

```text
AUT/4 multiclient server ready; connect any AOA-capable Android device.
[usb-1-39] USB accessory ready; waiting for the AUT app
[usb-1-39] AUT/4.0 negotiated; transport=ratp
[c3aa0e16] lease offered IPv4=10.77.0.229 IPv6=fd77:4155:5400:.../64
Shared internet gateway attached to aut0
[c3aa0e16] client ready; internet forwarding active
```

## App modes

| Mode | VPN/TUN | Internet traffic | Diagnostic traffic |
| --- | --- | --- | --- |
| **AUTPing only** | No | No | AUT protocol PING/PONG over raw USB. |
| **ICMPv6 Ping only** | Yes | No default route | Complete IPv6/ICMPv6 Echo Request/Reply to the AUT gateway. |
| **Internet only** | Yes | IPv4 and IPv6 | No periodic AUTPing or ICMPv6 diagnostic. |

Live diagnostics report sent, received, timed-out loss, last RTT,
minimum/average/maximum RTT, and average jitter. The app keeps the latest 80
timestamped activity events, even when its activity is closed. Diagnostics and
activity history have separate clear buttons.

## Direct vs. RATP

AUT has exactly two packet paths. Both connect Android's `VpnService` directly
to the AUT USB bridge; neither uses a local relay or a network socket between
Android and Linux.

| Property | Direct | RATP |
| --- | --- | --- |
| Data path | One IP packet in a normal AUT binary frame. | One IP packet in a sequenced `RATP_DATA` frame. |
| AUT send window | None. | Up to 1,024 unacknowledged packets in flight. |
| AUT ACK/NACK | No. | Selective cleartext ACK/NACK, up to 256 sequences per line. |
| AUT retransmission | No. | Immediate on NACK and automatic on adaptive RTO. |
| AUT reordering | No additional reorder buffer. | Delivers packets in sequence through a 1,024-packet reorder window. |
| Duplicate handling | Relies on USB Bulk. | Detects, acknowledges again, and does not deliver duplicates twice. |
| Overhead | Lowest. | Higher: sequence tracking, control lines, buffering and possible retransmission. |
| Best use | Normal operation and maximum throughput. | Testing explicit AUT-level reliability or links where endpoint recovery must be observable. |

Direct still benefits from USB Bulk's link-level CRC, retry, and ordered
delivery. RATP adds a separate end-to-end reliability mechanism between the
Android and Python AUT endpoints. It is an AUT transport protocol, not an IP
TCP or UDP socket.

### What RATP actually does

RATP adds an independent reliability layer between Android and Python:

- a sliding send window with up to 1,024 packets in flight;
- selective ACK and NACK instead of cumulative Stop-and-Wait;
- up to 256 packet sequences in one ASCII ACK or NACK line;
- a receive reorder buffer that delivers packets to TUN in sequence;
- immediate retransmission requested by NACK;
- retransmission after an adaptive RTO based on SRTT and RTT variation;
- exponential RTO backoff and a bounded retry count;
- duplicate detection and Karn-style exclusion of retransmitted RTT samples.

ACK and NACK records are cleartext ASCII directly on the AUT USB stream:

```text
ACK 00000001,00000002,00000005\r\n
NACK 00000003,00000004\r\n
```

They may acknowledge or request any selected packets; receiving one ACK is not
required before sending the next data packet. This is explicitly **not
Stop-and-Wait**.

Android's `VpnService` gives AUT an original layer-3 packet. That packet already
contains IPv4 or IPv6 and, inside it, the application's TCP, UDP, ICMP, or other
payload. AUT wraps the original packet only in an AUT/4 USB frame:

```text
AUT/4 RATP_DATA frame
└── original IPv4 or IPv6 packet
    └── original TCP, UDP, ICMP, ...
        └── application data
```

There is no second IP header: RATP is not IP-in-IP, TCP-in-TCP, or UDP-in-UDP.
USB Bulk is already reliable underneath, but RATP deliberately provides its
own observable end-to-end packet acknowledgement, loss recovery, and ordering
between the AUT endpoints.

## Default addressing

| Purpose | Default |
| --- | --- |
| Shared IPv4 network | `10.77.0.0/24` |
| Linux IPv4 gateway | `10.77.0.1/24` |
| Site ULA | `fd77:4155:5400::/48` |
| Linux IPv6 gateway | `fd77:4155:5400::1/48` |
| Client IPv6 subnet | One derived `/64` per client |
| MTU | `1400` |
| Routes sent in Internet mode | `0.0.0.0/0`, `::/0` |

Each app installation creates a persistent random client ID and 64-bit IPv6
Host ID. The server deterministically selects an IPv4 lease and a `/64` inside
the site ULA. The shared TUN dispatcher uses destination addresses to return
downlink packets to the correct USB client.

## Documentation

- [Linux setup and server configuration](docs/LINUX.md)
- [Android build, UI, modes, and lifecycle](docs/ANDROID.md)
- [AUT/4 protocol reference](docs/PROTOCOL.md)
- [Architecture and contributor map](docs/ARCHITECTURE.md)
- [Troubleshooting and performance](docs/TROUBLESHOOTING.md)

## Stop and remove AUT networking

Stop the Python server with `Ctrl+C`, stop AUT in the Android app, then remove
only the Linux resources created by AUT:

```bash
sudo ./teardown-linux.sh
```

## Verify a checkout

```bash
python3 -m unittest discover -v
./gradlew lintDebug assembleDebug
```

## Current limitations

- Linux needs privileged network preparation; AUT is not completely rootless
  on the gateway computer.
- Android allows only one active `VpnService` per user, so AUT replaces another
  active VPN while Internet or ICMPv6 mode is running.
- IPv6 internet access requires a usable IPv6 uplink and NAT66 support on Linux.
- AOA and USB permission behavior varies between vendors and kernels.
- This repository currently builds a debug APK; it does not provide a signed
  production release.

## Project status

AUT is an experimental project under active development. Bug reports should
include the Python server output, Android activity history, selected mode/path,
phone model, Android version, Linux distribution, and USB controller if known.
