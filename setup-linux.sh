#!/bin/sh
set -eu

# Debian commonly installs nft in /usr/sbin, which may not be in a regular
# user's PATH even though it is available through sudo.
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH

if [ "$(id -u)" -ne 0 ]; then
    echo "Run with sudo: sudo $0 INTERNET_INTERFACE [USER]" >&2
    exit 2
fi

UPLINK=${1:-}
AUT_USER=${2:-${SUDO_USER:-}}
AUT_IPV4=${3:-10.77.0.1/24}
AUT_ULA=${4:-fd77:4155:5400::1/48}
if [ -z "$UPLINK" ] || [ -z "$AUT_USER" ]; then
    echo "Usage: sudo $0 INTERNET_INTERFACE [USER] [AUT_IPV4_CIDR] [AUT_ULA_CIDR]" >&2
    echo "Example: sudo $0 wlan0 x1colegal 10.77.0.1/24 fd77:4155:5400::1/48" >&2
    exit 2
fi

if ! ip link show dev "$UPLINK" >/dev/null 2>&1; then
    echo "Internet interface does not exist: $UPLINK" >&2
    exit 1
fi

if ! command -v ip >/dev/null 2>&1; then
    echo "The ip command is missing; install the iproute2 package" >&2
    exit 1
fi
if ! command -v nft >/dev/null 2>&1; then
    echo "The nft command is missing; install it with: sudo apt install nftables" >&2
    exit 1
fi

if ip link show dev aut0 >/dev/null 2>&1; then
    ip link set dev aut0 down
    ip tuntap del dev aut0 mode tun
fi
ip tuntap add dev aut0 mode tun user "$AUT_USER"
ip address add "$AUT_IPV4" dev aut0
ip -6 address add "$AUT_ULA" dev aut0
ip link set dev aut0 mtu 1400 up

IPV4_NETWORK=$(ip -4 route show dev aut0 scope link proto kernel | awk 'NR == 1 { print $1 }')
ULA_NETWORK=$(ip -6 route show dev aut0 proto kernel | awk 'NR == 1 { print $1 }')
if [ -z "$IPV4_NETWORK" ] || [ -z "$ULA_NETWORK" ]; then
    echo "Could not derive AUT networks from $AUT_IPV4 and $AUT_ULA" >&2
    exit 1
fi

sysctl -w net.ipv4.ip_forward=1 >/dev/null
sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null

if nft list table inet aut >/dev/null 2>&1; then
    nft delete table inet aut
fi
nft add table inet aut
nft "add flowtable inet aut fastpath { hook ingress priority filter; devices = { aut0, $UPLINK }; }"
nft 'add chain inet aut forward { type filter hook forward priority filter; policy accept; }'
nft add rule inet aut forward meta l4proto '{ tcp, udp }' ct state established flow add @fastpath
nft add rule inet aut forward iifname aut0 oifname "$UPLINK" accept
nft add rule inet aut forward iifname "$UPLINK" oifname aut0 ct state established,related accept
nft 'add chain inet aut postrouting { type nat hook postrouting priority srcnat; policy accept; }'
nft add rule inet aut postrouting ip saddr "$IPV4_NETWORK" oifname "$UPLINK" masquerade
nft add rule inet aut postrouting ip6 saddr "$ULA_NETWORK" oifname "$UPLINK" masquerade

echo "AUT Linux ready: $IPV4_NETWORK + $ULA_NETWORK -> $UPLINK; owner=$AUT_USER; NAT44/NAT66 and nftables flow offload enabled"
