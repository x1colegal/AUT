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
- Use Direct, UDP-over-`[::1]`, or TCP-over-`[::1]` packet paths inside Android.
- Reconnect clients and restore sessions without restarting the server.

## How it fits together

```text
Android applications
        │ IPv4 / IPv6
Android VpnService TUN
        │ Direct, or optional TCP/UDP relay on [::1]
AUT Android bridge
        │ AUT/3 binary frames
Android Open Accessory USB Bulk
        │ AUT/3 binary frames
Python multiclient server
        │ shared Linux TUN: aut0
Linux forwarding + nftables NAT44/NAT66
        │
Wi-Fi / Ethernet / another Linux uplink
        │
Internet
```

The optional TCP and UDP relays exist only on Android loopback. The physical
phone-to-computer link remains raw USB Bulk in every mode.

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
AUT/3 multiclient server ready; connect any AOA-capable Android device.
[usb-1-39] USB accessory ready; waiting for the AUT app
[usb-1-39] AUT protocol active
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

## Android packet paths

| Path | Description | Recommendation |
| --- | --- | --- |
| **Direct** | Moves each TUN packet directly between VpnService and AUT framing. | Best default and lowest overhead. |
| **UDP** | Adds a protected UDP loopback hop on `[::1]`. | Useful for testing an alternate local path. |
| **TCP** | Adds a protected, length-framed TCP loopback hop on `[::1]`. | Experimental; some Android kernels reject socket protection. |

These paths do not change the USB transport.

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
- [AUT/3 protocol reference](docs/PROTOCOL.md)
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
- TCP loopback protection is rejected by some Android kernels; Direct and UDP
  are more portable.
- This repository currently builds a debug APK; it does not provide a signed
  production release.

## Project status

AUT is an experimental project under active development. Bug reports should
include the Python server output, Android activity history, selected mode/path,
phone model, Android version, Linux distribution, and USB controller if known.
