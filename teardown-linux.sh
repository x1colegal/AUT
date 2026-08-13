#!/bin/sh
set -eu

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH

if [ "$(id -u)" -ne 0 ]; then
    echo "Run with sudo: sudo $0" >&2
    exit 2
fi

if nft list table inet aut >/dev/null 2>&1; then
    nft delete table inet aut
fi
if ip link show dev aut0 >/dev/null 2>&1; then
    ip link set dev aut0 down
    ip tuntap del dev aut0 mode tun
fi
echo "AUT interface and nftables rules removed"
