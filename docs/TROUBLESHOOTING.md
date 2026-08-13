# Troubleshooting and performance

Start with the Python terminal output and the Android Activity history. AUT's
messages intentionally identify handshake and dataplane stages.

## `aut0 does not match the advertised AUT networks`

The server arguments differ from the addresses prepared by `setup-linux.sh`.
Use the exact corrective command printed by the server, or manually ensure the
Linux `.1` addresses and prefixes match.

Example:

```bash
sudo ./setup-linux.sh wlp2s0 "$USER" \
  192.168.255.1/24 fdfe:cafe:cafe::1/48

.venv/bin/python aut_server.py --uplink wlp2s0 \
  --ipv4-network 192.168.255.0/24 \
  --ula-prefix fdfe:cafe:cafe::/48
```

## `--ula-prefix must be an IPv6 ULA prefix`

ULA space is `fc00::/7`. The first byte must begin with `fc` or `fd`.

- Valid example: `fd73:cafe:cafe::/48`
- Invalid example: `fe73:cafe:cafe::/48`

## `nft: command not found`

Install nftables:

```bash
sudo apt install nftables
```

On Debian, `nft` may live in `/usr/sbin`, which is absent from a regular user's
PATH. AUT setup scripts define a complete system PATH internally. `sudo nft`
working while plain `nft` fails is therefore not evidence that setup is broken.

## Permission denied or USB device busy

- Confirm no other AUT server instance owns the accessory interface.
- Accept the Android USB Accessory permission dialog.
- Check that the Linux user has USB device access through the distribution's
  udev/logind policy.
- Disconnect and reconnect the cable if Android is stuck between MTP and AOA.
- Use a data-capable USB cable and a direct port before testing through a hub.

## Server waits while the app is closed

This is normal:

```text
[usb-1-39] USB accessory ready; waiting for the AUT app
```

Android can stay in AOA mode after the service stops. USB timeouts while no app
reader is active are treated as idle polls and should not cause reconnect spam.

## Internet mode establishes but no packets move

Expected handshake:

```text
lease offered ...
Shared internet gateway attached to aut0
client ready; internet forwarding active
dataplane android->aut0=1 aut0->android=0
```

Checks:

```bash
ip address show dev aut0
ip route get 1.1.1.1
sudo nft list table inet aut
sysctl net.ipv4.ip_forward
sysctl net.ipv6.conf.all.forwarding
```

Common causes:

- setup and server prefixes do not match;
- wrong uplink passed to `setup-linux.sh`;
- upstream network blocks forwarding or captive-portal traffic;
- advertised DNS is unreachable from forwarded clients;
- another firewall table rejects forwarding before/after AUT's accepting chain.

## IPv4 works but IPv6 does not

NAT66 requires a functioning IPv6 uplink. Verify Linux itself can reach an IPv6
destination. The ULA and NAT rule alone cannot create IPv6 connectivity.

ICMPv6 Ping-only is different: it tests the AUT gateway over the control plane
and can work even when public IPv6 internet is unavailable.

## ICMPv6 diagnostic stays on `waiting`

Use matching current Python and APK versions, restart the Python server, and
start **ICMPv6 Ping only** again. Progress should move through:

```text
ICMPv6 Ping · requesting an IP lease
ICMPv6 Ping · armed · sending first Echo Request
ICMPv6 Ping · sent 1 ...
```

Linux should report:

```text
[client-id] ICMPv6 Echo Reply=1
```

If Android sends but Linux never prints a reply, verify the Python server was
restarted after updating `aut_server.py`.

## TCP path reports `could not protect connected TCP loopback socket`

Some Android kernels reject `VpnService.protect()` for a TCP socket connected
to loopback. This is device-specific. Select **Direct** or **UDP**; Direct is the
recommended production path.

## Android reports that AUT is not responding

Current AUT closes USB and TUN resources on an `aut-shutdown` worker so Stop
does not block Android's main thread. If an older APK still shows an ANR, update
and reinstall it. Also capture Android system logs and mention the phone/vendor
because USB driver close behavior is kernel-specific.

## Throughput tuning

AUT batches Linux-to-Android frames for up to 1.5 ms by default. This reduces
libusb calls without adding a large latency penalty.

```bash
.venv/bin/python aut_server.py --uplink wlp2s0 --usb-batch-ms 1.5
```

Larger is not always faster. On at least one tested path, 1.5 ms outperformed
3 ms. Test Direct mode first and change one variable at a time. Valid values are
0 through 20 ms.

Performance depends on:

- Android AOA implementation and USB controller;
- cable, hub, and negotiated USB speed;
- Python/libusb scheduling;
- phone CPU and VpnService packet handling;
- packet size and workload;
- Linux TUN/nftables processing;
- optional Android loopback relay overhead.

Upload and download can differ because they use different USB endpoint and
software scheduling paths.

## What to include in a bug report

- complete Python output from connection through failure;
- Android activity history and live diagnostics text;
- selected mode and Direct/UDP/TCP path;
- server command and setup command;
- phone model and Android version;
- Linux distribution and kernel;
- USB controller/hub information when performance is involved.
