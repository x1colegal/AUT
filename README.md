# AUT — Advanced USB Tethering

AUT carries IPv4 and IPv6 packets directly over **USB Bulk** transfers between
multiple Android `VpnService` clients and one shared Linux TUN interface. The
USB link does not use normal tethering, ADB forwarding, TCP, or UDP.

TCP, UDP, and ICMP are carried inside the IP packets. On Android, the user can
choose a direct TUN-to-USB path or an internal TCP/UDP relay over IPv6 loopback
`[::1]` between the USB bridge and `VpnService`.

## Data path

```text
Android applications
        | IPv4 / IPv6
Android VpnService (TUN)
        | direct, or TCP/UDP on [::1]
optional AUT packet relay
        | AUT/3 frames
USB Open Accessory Bulk
        | AUT/3 frames
Python + Linux TUN aut0
        | kernel forwarding
nftables NAT44 / NAT66
        | software flow offload
        |
Linux internet interface
```

The control plane supplies a DHCPv4/DHCPv6/SLAAC-equivalent lease containing
addresses, prefixes, DNS servers, MTU, and routes. Android `VpnService` is a
layer-3 interface and does not run Ethernet DHCP broadcasts or IPv6 Router
Advertisements, so the received lease is applied directly to its TUN.

Default network:

- Shared IPv4 pool: `10.77.0.0/24`
- Linux IPv4: `10.77.0.1/24`
- Site ULA: `fd77:4155:5400::/48`
- Linux IPv6: `fd77:4155:5400::1/48`
- MTU: `1400`
- Routes: `0.0.0.0/0` and `::/0`

Each Android installation creates a persistent random 64-bit Host ID. The
server deterministically assigns that client a `/64` inside the site `/48`,
combines it with the client-generated Host ID, and leases a unique IPv4 address
from the shared pool. The shared TUN dispatcher routes downlink packets to the
correct USB connection by destination address.

## Android modes

- **Ping only:** tests AUT framing, USB stability, loss, and RTT without a TUN.
- **Internet only:** starts the dual-stack VPN without periodic AUT pings.

The diagnostics card separates two probes. `AUTPing` is an AUT protocol
PING/PONG and requires no IP stack. `ICMPv6 Ping` sends a checksummed IPv6 Echo
Request from the leased Android ULA to the Linux AUT gateway. Both report sent,
received, timed-out loss, last/minimum/average/maximum RTT, and average jitter.
Internet-only mode runs neither diagnostic probe; it carries user traffic only.

Internet modes offer three packet paths:

- **DIRECT:** TUN packets become AUT USB frames without a local socket hop.
- **UDP:** packets cross a protected UDP relay on `[::1]`.
- **TCP:** packets cross a protected, length-framed TCP relay on `[::1]`.

The loopback relay is a local Android hop; USB remains the actual
device-to-host transport.

## Build without Android Studio

```bash
export ANDROID_HOME=/home/x1colegal/Android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Linux dependencies

```bash
sudo apt install nftables iproute2
python3 -m pip install --user --break-system-packages -r requirements.txt
```

Debian normally installs `nft` as `/usr/sbin/nft`. A regular shell may not
include `/usr/sbin` in `PATH` even when `sudo nft` works. AUT setup scripts set
an explicit system PATH.

## Enable internet forwarding

Find the Linux upstream interface:

```bash
ip route get 1.1.1.1
```

The interface is the value after `dev`. Prepare TUN and NAT once per boot:

```bash
sudo ./setup-linux.sh wlp2s0
```

The setup creates an nftables flowtable named `fastpath` across `aut0` and the
selected upstream interface. Established flows enter the nftables software
fast path while keeping connection tracking and NAT44/NAT66. Hardware offload
is intentionally not requested because a TUN interface cannot be offloaded by
a physical NIC.

To choose different AUT networks, pass the Linux-side interface addresses to
the setup script, then pass the matching networks to the server:

```bash
sudo ./setup-linux.sh wlp2s0 x1colegal 10.88.0.1/20 fd42:1234:5678::1/48
python3 aut_server.py --uplink wlp2s0 \
  --ipv4-network 10.88.0.0/20 \
  --ula-prefix fd42:1234:5678::/48
```

Every client receives a distinct `/64` carved from that `/48`.

Start the server:

```bash
python3 aut_server.py
```

The server validates that `aut0` contains the Linux-side `.1` addresses for
the exact `--ipv4-network` and `--ula-prefix` being advertised. It exits with a
complete `setup-linux.sh` command instead of starting a VPN whose routes and
NAT do not match the lease.

No VID:PID is required. AUT scans USB devices, probes Android Open Accessory
support, switches compatible phones from MTP to AOA, and starts one independent
worker per connected phone. New clients can be plugged in without restarting
the server.

Open the APK, grant USB and VPN permission, select TCP or UDP, and choose a
mode. The foreground notification reports RTT and IP packet counters.

To remove only resources created by AUT:

```bash
sudo ./teardown-linux.sh
```

## Additional routes and DNS

By default AUT inherits the DNS servers received by Linux. Pass `--uplink`
to select the source interface explicitly; AUT queries systemd-resolved or
NetworkManager and falls back to `/etc/resolv.conf`. Loopback stubs such as
`127.0.0.53` are never advertised to Android.

`--route` and `--dns` are repeatable. Providing either option replaces its
automatically selected values:

```bash
python3 aut_server.py \
  --route 0.0.0.0/0 \
  --route ::/0 \
  --route 10.20.0.0/16 \
  --route fd20:1234::/48 \
  --dns 1.1.1.1 \
  --dns 2606:4700:4700::1111
```

## AUT/3 frame

All integers use network byte order:

| Field | Bytes |
| --- | ---: |
| Historical `AUT1` framing magic | 4 |
| Framing version | 1 |
| Type | 1 |
| Flags | 2 |
| Sequence | 4 |
| Payload length | 4 |
| Monotonic timestamp | 8 |
| Payload | variable |
| CRC32 | 4 |

Frame types are `1 PING`, `2 PONG`, `3 CONFIG_REQUEST`,
`4 CONFIG_RESPONSE`, `5 IP_PACKET`, `6 SESSION_STOP`, and
`7 CONFIG_REQUIRED`, `8 CLIENT_READY`, and `9 CLIENT_READY_ACK`. Android sends
`SESSION_STOP` before an intentional stop or mode change so Linux can discard
the old TUN queue without reporting USB timeouts. Linux sends `CONFIG_REQUIRED`
when a live Android VPN must restore its identity after a server or USB-session
restart. Both decoders handle fragmented frames and multiple frames in one USB
transfer.

Internet setup uses a two-phase handshake: Linux offers a lease with
`CONFIG_RESPONSE`, Android establishes its VPN TUN, and only then sends
`CLIENT_READY`. Linux attaches the client to the shared TUN dispatcher and
answers with `CLIENT_READY_ACK`. Android retransmits readiness until it receives
the acknowledgement; Linux briefly queues IP packets that arrive during this
handshake.

Type `10 ICMP6_ECHO` carries a complete checksummed IPv6 Echo Request on the
control plane. Linux returns the complete IPv6 Echo Reply as `IP_PACKET`. This
keeps the ICMPv6 diagnostic independent from TUN registration, routing and NAT.

The Linux downlink coalesces packets for up to 1.5 milliseconds before each USB
Bulk write. This reduces libusb calls under load while keeping interactive
latency low. Use `--usb-batch-ms` to tune the window for a specific phone and
USB controller. Android performs stop-time USB and TUN cleanup outside its main
thread so a slow device-driver close cannot cause an application-not-responding
dialog.

The Android service persists the latest 80 timestamped events. Closing and
reopening the control activity restores the log while the VPN continues to run.

## Verification

```bash
python3 -m unittest discover -v
./gradlew lintDebug assembleDebug
```
