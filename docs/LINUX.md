# Linux gateway guide

This guide explains the privileged setup, normal-user server, address plans,
DNS selection, multiclient behavior, and command-line options.

## Packages

Debian/Ubuntu example:

```bash
sudo apt install python3 python3-venv python3-pip libusb-1.0-0 nftables iproute2
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

AUT currently depends on PyUSB `1.3.1`.

## Why setup needs root

The Python process is designed to run as a regular user, but an unprivileged
process cannot normally create persistent TUN interfaces, enable system
forwarding, or install nftables rules. `setup-linux.sh` performs only that
preparation:

1. Removes an older `aut0`, if present.
2. Creates persistent TUN `aut0` owned by the selected user.
3. Adds Linux-side IPv4 and IPv6 addresses.
4. Sets MTU 1400 and brings the interface up.
5. Enables IPv4 and IPv6 forwarding.
6. Creates nftables forwarding, NAT44, NAT66, and flowtable rules.

Run it once after boot or whenever you change the address plan/uplink:

```bash
sudo ./setup-linux.sh UPLINK [USER] [AUT_IPV4_CIDR] [AUT_ULA_CIDR]
```

Example:

```bash
sudo ./setup-linux.sh wlp2s0 "$USER" 10.77.0.1/24 fd77:4155:5400::1/48
```

`setup-linux.sh` recreates `aut0` and the nftables table named `aut`. Do not run
it while active AUT clients are using the gateway.

## Start the server

```bash
.venv/bin/python aut_server.py --uplink wlp2s0
```

Run the server as the user passed to `setup-linux.sh`, not with `sudo`. The
server validates the configured `aut0` addresses when a client requests an IP
lease. A mismatch produces a complete corrective setup command.

## Automatic USB/AOA discovery

The server scans USB devices once per second by default. For each device it:

- ignores USB hubs;
- reuses devices already in Android Open Accessory mode;
- probes other devices for AOA support;
- sends the AUT manufacturer/model/accessory identity;
- waits for Android to re-enumerate as an AOA device;
- starts an independent reader thread for every connected client.

No VID:PID flag is required. The Linux user still needs permission to access
the USB device. Distribution/vendor USB rules can affect this permission.

Seeing `USB accessory ready; waiting for the AUT app` while the app is closed
is normal: the phone can remain enumerated in AOA mode without an active AUT
service.

## Custom IPv4 and ULA networks

The setup script receives Linux interface addresses, while the server receives
network prefixes. They must describe the same networks.

```bash
sudo ./setup-linux.sh wlp2s0 "$USER" \
  192.168.255.1/24 \
  fdfe:cafe:cafe::1/48

.venv/bin/python aut_server.py \
  --uplink wlp2s0 \
  --ipv4-network 192.168.255.0/24 \
  --ula-prefix fdfe:cafe:cafe::/48
```

Rules:

- IPv4 must be a real network prefix and must be `/30` or larger in capacity
  (for example `/30`, `/24`, or `/16`).
- Linux uses the first address (`network + 1`).
- Client leases start at `network + 2` and exclude the IPv4 broadcast address.
- IPv6 must be inside `fc00::/7`; use an `fc...` or `fd...` ULA.
- The ULA prefix may be `/7` through `/64`; `/48` is recommended because it
  provides 65,536 possible client `/64` subnets.
- Each Android installation supplies its own persistent 64-bit Host ID.

## DNS behavior

If no `--dns` is supplied, AUT attempts these sources in order:

1. `resolvectl dns UPLINK`;
2. NetworkManager via `nmcli`;
3. `/etc/resolv.conf`.

When `--uplink` is omitted, AUT first tries to discover the interface from the
default IPv4 route. Loopback, link-local, and unspecified resolvers are not
advertised, so a local stub such as `127.0.0.53` is never sent to Android.

Override DNS explicitly by repeating `--dns`:

```bash
.venv/bin/python aut_server.py \
  --dns 1.1.1.1 \
  --dns 2606:4700:4700::1111
```

Providing any `--dns` value replaces automatic discovery.

## Routes and private subnets

The default Android routes are `0.0.0.0/0` and `::/0`. Repeat `--route` to
replace them and include other networks:

```bash
.venv/bin/python aut_server.py \
  --route 0.0.0.0/0 \
  --route ::/0 \
  --route 10.20.0.0/16 \
  --route fd20:1234::/48
```

AUT does not enforce source-address anti-spoofing on client IP packets. This is
intentional so custom addressing and delegated subnets can cross the link. The
Linux administrator is responsible for any desired nftables isolation policy.

## NAT and flow offload

The nftables table `inet aut` contains:

- an ingress flowtable across `aut0` and the selected uplink;
- an accepting forward chain;
- established/related return handling;
- IPv4 masquerading for the AUT IPv4 network;
- IPv6 masquerading for the AUT ULA.

This is software flow offload. Hardware flow offload is not requested because
a TUN interface cannot be offloaded like a physical NIC.

NAT66 does not create IPv6 connectivity when the uplink has none. In that case,
IPv4 may work while IPv6 internet destinations remain unreachable.

## Server option reference

| Option | Default | Purpose |
| --- | --- | --- |
| `--tun` | `aut0` | Prepared shared Linux TUN name. |
| `--mtu` | `1400` | MTU advertised to Android. |
| `--usb-batch-ms` | `1.5` | Maximum Linux-to-Android USB coalescing window, 0–20 ms. |
| `--ipv4-network` | `10.77.0.0/24` | Shared client IPv4 pool. |
| `--ula-prefix` | `fd77:4155:5400::/48` | Site ULA split into client `/64` prefixes. |
| `--uplink` | automatic | Interface used for DNS discovery. |
| `--dns` | inherited | Repeatable DNS override. |
| `--route` | both default routes | Repeatable Android route override. |
| `--scan-interval` | `1.0` | USB discovery interval; minimum 0.2 seconds. |

## Cleanup

```bash
sudo ./teardown-linux.sh
```

This deletes only nftables table `inet aut` and TUN interface `aut0`. Forwarding
sysctls remain enabled because they may be shared with other network services.
