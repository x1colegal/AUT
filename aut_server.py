#!/usr/bin/env python3
"""AUT multiclient Linux gateway using Android Open Accessory USB Bulk."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
import select
import shutil
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass

try:
    import usb.core
    import usb.util
except ImportError:
    print(
        "Error: PyUSB is not installed. Run: "
        "python3 -m pip install --user --break-system-packages -r requirements.txt",
        file=sys.stderr,
    )
    raise SystemExit(2)

from aut_protocol import (
    Decoder,
    Frame,
    ProtocolError,
    TYPE_CONFIG_REQUEST,
    TYPE_CONFIG_REQUIRED,
    TYPE_CLIENT_READY,
    TYPE_CLIENT_READY_ACK,
    TYPE_ICMP6_ECHO,
    TYPE_CONFIG_RESPONSE,
    TYPE_IP_PACKET,
    TYPE_PING,
    TYPE_PONG,
    TYPE_SESSION_STOP,
    encode,
)

AOA_GET_PROTOCOL = 51
AOA_SEND_IDENT = 52
AOA_START = 53
AOA_VID = 0x18D1
AOA_PIDS = {0x2D00, 0x2D01, 0x2D04, 0x2D05}
IDENT = (
    "AUT Project",
    "Advanced USB Tethering",
    "AUT userspace USB protocol",
    "1",
    "https://example.invalid/aut",
    "AUT0001",
)


def is_accessory(device) -> bool:
    return device.idVendor == AOA_VID and device.idProduct in AOA_PIDS


def device_key(device) -> tuple[int, int, int, int]:
    return (device.bus or 0, device.address or 0, device.idVendor, device.idProduct)


def is_usb_timeout(error: usb.core.USBError) -> bool:
    """Handle backends that report ETIMEDOUT as a generic USBError."""
    return (
        isinstance(error, usb.core.USBTimeoutError)
        or getattr(error, "errno", None) == 110
        or getattr(error, "backend_error_code", None) == -7
    )


def try_enable_accessory(device) -> bool:
    """Probe one USB device and switch it to AOA when supported."""
    if is_accessory(device) or getattr(device, "bDeviceClass", 0) == 9:
        return False
    try:
        reply = device.ctrl_transfer(0xC0, AOA_GET_PROTOCOL, 0, 0, 2, timeout=250)
        if len(reply) != 2:
            return False
        protocol = int(reply[0]) | (int(reply[1]) << 8)
        if protocol < 1:
            return False
        for index, value in enumerate(IDENT):
            device.ctrl_transfer(
                0x40, AOA_SEND_IDENT, 0, index,
                value.encode("utf-8") + b"\0", timeout=500,
            )
        device.ctrl_transfer(0x40, AOA_START, 0, 0, None, timeout=500)
        print(
            f"AOA v{protocol} requested for {device.idVendor:04x}:{device.idProduct:04x} "
            f"on bus {device.bus} address {device.address}",
            flush=True,
        )
        return True
    except (usb.core.USBError, ValueError, IndexError):
        return False
    finally:
        usb.util.dispose_resources(device)


@dataclass
class BulkConnection:
    device: object
    endpoint_in: object
    endpoint_out: object
    interface_number: int
    detached_kernel_driver: bool
    write_lock: threading.Lock

    @classmethod
    def open(cls, device) -> "BulkConnection":
        try:
            configuration = device.get_active_configuration()
        except usb.core.USBError:
            device.set_configuration()
            configuration = device.get_active_configuration()
        selected = None
        for interface in configuration:
            if not (
                interface.bInterfaceClass == 0xFF
                and interface.bInterfaceSubClass == 0xFF
                and interface.bInterfaceProtocol == 0x00
            ):
                continue
            endpoints = list(interface)
            endpoint_in = next(
                (ep for ep in endpoints if usb.util.endpoint_direction(ep.bEndpointAddress)
                 == usb.util.ENDPOINT_IN and usb.util.endpoint_type(ep.bmAttributes)
                 == usb.util.ENDPOINT_TYPE_BULK),
                None,
            )
            endpoint_out = next(
                (ep for ep in endpoints if usb.util.endpoint_direction(ep.bEndpointAddress)
                 == usb.util.ENDPOINT_OUT and usb.util.endpoint_type(ep.bmAttributes)
                 == usb.util.ENDPOINT_TYPE_BULK),
                None,
            )
            if endpoint_in is not None and endpoint_out is not None:
                selected = (interface, endpoint_in, endpoint_out)
                break
        if selected is None:
            raise RuntimeError("USB Bulk IN/OUT endpoints were not found")
        interface, endpoint_in, endpoint_out = selected
        number = interface.bInterfaceNumber
        detached = False
        try:
            if device.is_kernel_driver_active(number):
                device.detach_kernel_driver(number)
                detached = True
        except (NotImplementedError, usb.core.USBError):
            pass
        usb.util.claim_interface(device, number)
        return cls(device, endpoint_in, endpoint_out, number, detached, threading.Lock())

    def write_frame(self, frame: Frame) -> None:
        self.write_frames((frame,))

    def write_frames(self, frames) -> None:
        wire = b"".join(encode(frame) for frame in frames)
        with self.write_lock:
            self.endpoint_out.write(wire, timeout=2000)

    def close(self) -> None:
        try:
            try:
                usb.util.release_interface(self.device, self.interface_number)
            except usb.core.USBError:
                pass
            if self.detached_kernel_driver:
                try:
                    self.device.attach_kernel_driver(self.interface_number)
                except usb.core.USBError:
                    pass
        finally:
            usb.util.dispose_resources(self.device)


@dataclass(frozen=True)
class ClientLease:
    client_id: str
    ipv4: ipaddress.IPv4Address
    ipv6: ipaddress.IPv6Address
    slaac_prefix: ipaddress.IPv6Network


class LeaseManager:
    """Allocate stable IPv4 leases and /64s from one site ULA prefix."""

    def __init__(self, ipv4_network: str, ula_prefix: str) -> None:
        self.ipv4_network = ipaddress.ip_network(ipv4_network, strict=True)
        self.ula_prefix = ipaddress.ip_network(ula_prefix, strict=True)
        if self.ipv4_network.version != 4 or self.ipv4_network.prefixlen > 30:
            raise RuntimeError("--ipv4-network must be an IPv4 prefix no longer than /30")
        if (
            self.ula_prefix.version != 6
            or not self.ula_prefix.subnet_of(ipaddress.ip_network("fc00::/7"))
            or self.ula_prefix.prefixlen > 64
        ):
            raise RuntimeError("--ula-prefix must be an IPv6 ULA prefix from /7 through /64")
        self._lock = threading.Lock()
        self._leases: dict[str, ClientLease] = {}

    @staticmethod
    def _hash(client_id: str, purpose: bytes) -> int:
        return int.from_bytes(
            hashlib.blake2s(client_id.encode("utf-8"), key=purpose, digest_size=8).digest(),
            "big",
        )

    def allocate(self, client_id: str, host_id_hex: str) -> ClientLease:
        if not client_id or len(client_id) > 128:
            raise ProtocolError("client_id must contain between 1 and 128 characters")
        try:
            host_id = int(host_id_hex, 16)
        except (TypeError, ValueError) as error:
            raise ProtocolError("host_id must be a 64-bit hexadecimal value") from error
        if not 0 < host_id <= 0xFFFF_FFFF_FFFF_FFFF:
            raise ProtocolError("host_id must be a nonzero 64-bit value")

        with self._lock:
            existing = self._leases.get(client_id)
            if existing is not None:
                return existing

            used_v4 = {lease.ipv4 for lease in self._leases.values()}
            slots_v4 = self.ipv4_network.num_addresses - 3
            start_v4 = self._hash(client_id, b"AUT IPv4") % slots_v4
            ipv4 = None
            for step in range(slots_v4):
                candidate = self.ipv4_network.network_address + 2 + ((start_v4 + step) % slots_v4)
                if candidate not in used_v4:
                    ipv4 = candidate
                    break
            if ipv4 is None:
                raise RuntimeError("the AUT IPv4 pool is full")

            subnet_bits = 64 - self.ula_prefix.prefixlen
            subnet_slots = 1 << subnet_bits
            used_subnets = {int(lease.slaac_prefix.network_address) >> 64
                            for lease in self._leases.values()}
            start_subnet = self._hash(client_id, b"AUT IPv6") % subnet_slots
            subnet = start_subnet
            if subnet_slots > 1:
                base = int(self.ula_prefix.network_address) >> 64
                for step in range(subnet_slots):
                    candidate = (base | ((start_subnet + step) % subnet_slots))
                    if candidate not in used_subnets:
                        subnet = (start_subnet + step) % subnet_slots
                        break
                else:
                    raise RuntimeError("the AUT ULA subnet pool is full")
            network_value = int(self.ula_prefix.network_address) | (subnet << 64)
            slaac_prefix = ipaddress.ip_network((network_value, 64))
            ipv6 = ipaddress.ip_address(network_value | host_id)
            lease = ClientLease(client_id, ipv4, ipv6, slaac_prefix)
            self._leases[client_id] = lease
            return lease


class TunGateway:
    """One shared TUN with destination-based dispatch to many USB clients."""

    TUNSETIFF = 0x400454CA
    IFF_TUN = 0x0001
    IFF_NO_PI = 0x1000

    def __init__(self, name: str, batch_ms: float = 1.5) -> None:
        self.name = name
        self.batch_seconds = batch_ms / 1000.0
        self.fd: int | None = None
        self.running = False
        self.thread: threading.Thread | None = None
        self.clients_lock = threading.Lock()
        self.open_lock = threading.Lock()
        self.tun_write_lock = threading.Lock()
        self.by_address: dict[ipaddress._BaseAddress, ClientSession] = {}

    def ensure_open(self) -> None:
        with self.open_lock:
            if self.fd is not None:
                return
            fd = os.open("/dev/net/tun", os.O_RDWR)
            try:
                request = struct.pack(
                    "16sH", self.name.encode("ascii"), self.IFF_TUN | self.IFF_NO_PI
                )
                fcntl.ioctl(fd, self.TUNSETIFF, request)
            except Exception:
                os.close(fd)
                raise
            self.fd = fd
            self.running = True
            self.thread = threading.Thread(
                target=self._read_loop, name="aut-tun-dispatcher", daemon=True
            )
            self.thread.start()
            print(f"Shared internet gateway attached to {self.name}", flush=True)

    def register(self, session: "ClientSession", lease: ClientLease) -> None:
        self.ensure_open()
        with self.clients_lock:
            self.by_address[lease.ipv4] = session
            self.by_address[lease.ipv6] = session

    def unregister(self, session: "ClientSession") -> None:
        with self.clients_lock:
            self.by_address = {
                address: owner for address, owner in self.by_address.items()
                if owner is not session
            }

    def write_packet(self, session: "ClientSession", packet: bytes) -> None:
        if self.fd is None or session.lease is None:
            raise RuntimeError("the shared TUN is not ready")
        # Validate only the packet shape. AUT intentionally permits arbitrary
        # source addresses so clients can carry delegated subnets, extra ULAs,
        # link-local traffic, and custom addressing policies.
        packet_source(packet)
        with self.tun_write_lock:
            os.write(self.fd, packet)
        session.rx_packets += 1

    def _read_loop(self) -> None:
        assert self.fd is not None
        fd = self.fd
        try:
            while self.running:
                readable, _, _ = select.select([fd], [], [], 0.5)
                if not readable:
                    continue
                packets = [os.read(fd, 65535)]
                wire_size = len(packets[0]) + 28
                # Give a download burst a tiny window to coalesce. At high
                # rates, a zero-time poll produced mostly one-frame libusb
                # writes and left USB throughput on the table.
                batch_deadline = time.monotonic() + self.batch_seconds
                while wire_size < 60 * 1024:
                    remaining = batch_deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    more, _, _ = select.select([fd], [], [], remaining)
                    if not more:
                        break
                    packet = os.read(fd, 65535)
                    if wire_size + len(packet) + 28 > 64 * 1024:
                        # Normal AUT MTUs never reach this branch because the
                        # loop stops near 60 KiB before reading another packet.
                        break
                    packets.append(packet)
                    wire_size += len(packet) + 28

                grouped: dict[ClientSession, list[bytes]] = {}
                with self.clients_lock:
                    for packet in packets:
                        owner = self.by_address.get(packet_destination(packet))
                        if owner is not None:
                            grouped.setdefault(owner, []).append(packet)
                for session, client_packets in grouped.items():
                    try:
                        frames = [session.ip_frame(packet) for packet in client_packets]
                        session.connection.write_frames(frames)
                        session.tx_packets += len(client_packets)
                    except (usb.core.USBError, OSError):
                        self.unregister(session)
        except (OSError, usb.core.USBError) as error:
            if self.running:
                print(f"TUN dispatcher stopped: {error}", file=sys.stderr, flush=True)

    def close(self) -> None:
        self.running = False
        if self.fd is not None:
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.fd = None


def packet_source(packet: bytes):
    if not packet:
        raise ProtocolError("empty IP packet")
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        return ipaddress.ip_address(packet[12:16])
    if version == 6 and len(packet) >= 40:
        return ipaddress.ip_address(packet[8:24])
    raise ProtocolError("invalid IP packet")


def packet_destination(packet: bytes):
    if not packet:
        return None
    version = packet[0] >> 4
    if version == 4 and len(packet) >= 20:
        return ipaddress.ip_address(packet[16:20])
    if version == 6 and len(packet) >= 40:
        return ipaddress.ip_address(packet[24:40])
    return None


def icmp6_echo_reply(packet: bytes, gateway: ipaddress.IPv6Address) -> bytes | None:
    """Answer an ICMPv6 Echo Request addressed to the AUT gateway."""
    if (
        len(packet) < 48
        or packet[0] >> 4 != 6
        or packet[6] != 58
        or packet[40] != 128
        or packet_destination(packet) != gateway
    ):
        return None
    reply = bytearray(packet)
    reply[8:24], reply[24:40] = packet[24:40], packet[8:24]
    reply[40] = 129
    reply[42:44] = b"\0\0"
    pseudo = bytes(reply[8:40]) + len(reply[40:]).to_bytes(4, "big") + b"\0\0\0\x3a"
    checksum_data = pseudo + bytes(reply[40:])
    if len(checksum_data) & 1:
        checksum_data += b"\0"
    total = sum(struct.unpack(f"!{len(checksum_data) // 2}H", checksum_data))
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    reply[42:44] = struct.pack("!H", (~total) & 0xFFFF)
    return bytes(reply)


def _usable_dns(value: str) -> str | None:
    value = value.strip().split("%", 1)[0]
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return None
    if address.is_loopback or address.is_link_local or address.is_unspecified:
        return None
    return str(address)


def _command_dns(command: list[str]) -> list[str]:
    try:
        result = subprocess.run(
            command, check=False, capture_output=True, text=True, timeout=2
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    if result.returncode != 0:
        return []
    found = []
    for token in result.stdout.replace("\n", " ").split():
        address = _usable_dns(token.rstrip(","))
        if address and address not in found:
            found.append(address)
    return found


def discover_dns(uplink: str | None = None) -> list[str]:
    if uplink is None and shutil.which("ip"):
        try:
            route = subprocess.run(
                ["ip", "route", "show", "default"], check=False,
                capture_output=True, text=True, timeout=2,
            ).stdout.split()
            if "dev" in route:
                uplink = route[route.index("dev") + 1]
        except (OSError, subprocess.TimeoutExpired, IndexError):
            pass
    found = []
    if shutil.which("resolvectl"):
        command = ["resolvectl", "dns"]
        if uplink:
            command.append(uplink)
        found.extend(_command_dns(command))
    if not found and uplink and shutil.which("nmcli"):
        found.extend(_command_dns(
            ["nmcli", "-g", "IP4.DNS,IP6.DNS", "device", "show", uplink]
        ))
    if not found:
        try:
            with open("/etc/resolv.conf", encoding="utf-8") as resolv:
                for line in resolv:
                    fields = line.split()
                    if len(fields) >= 2 and fields[0] == "nameserver":
                        address = _usable_dns(fields[1])
                        if address and address not in found:
                            found.append(address)
        except OSError:
            pass
    if not found:
        raise RuntimeError("no usable upstream DNS was found; provide --dns")
    return found


def configure_networks(args) -> None:
    """Validate network arguments and expose parsed networks for compatibility."""
    manager = LeaseManager(args.ipv4_network, args.ula_prefix)
    args.ipv4_prefix = manager.ipv4_network.prefixlen
    args.ipv6_network = str(manager.ula_prefix.network_address)
    args.ipv6_prefix = manager.ula_prefix.prefixlen


def validate_tun_configuration(args) -> None:
    """Ensure the root-created TUN matches the networks advertised by AUT."""
    if not shutil.which("ip"):
        raise RuntimeError("the ip command is required to validate the AUT TUN")
    try:
        result = subprocess.run(
            ["ip", "-j", "address", "show", "dev", args.tun],
            check=False, capture_output=True, text=True, timeout=2,
        )
        links = json.loads(result.stdout) if result.returncode == 0 else []
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        raise RuntimeError(f"could not inspect {args.tun}: {error}") from error

    ipv4_network = ipaddress.ip_network(args.ipv4_network, strict=True)
    ula_network = ipaddress.ip_network(args.ula_prefix, strict=True)
    expected_ipv4 = ipv4_network.network_address + 1
    expected_ipv6 = ula_network.network_address + 1
    configured = set()
    if links:
        for address in links[0].get("addr_info", []):
            try:
                configured.add((
                    ipaddress.ip_address(address["local"]),
                    int(address["prefixlen"]),
                ))
            except (KeyError, ValueError):
                continue

    missing = []
    if (expected_ipv4, ipv4_network.prefixlen) not in configured:
        missing.append(f"{expected_ipv4}/{ipv4_network.prefixlen}")
    if (expected_ipv6, ula_network.prefixlen) not in configured:
        missing.append(f"{expected_ipv6}/{ula_network.prefixlen}")
    if missing:
        raise RuntimeError(
            f"{args.tun} does not match the advertised AUT networks; missing "
            f"{', '.join(missing)}. Recreate it with: sudo ./setup-linux.sh "
            f"INTERNET_INTERFACE USER {expected_ipv4}/{ipv4_network.prefixlen} "
            f"{expected_ipv6}/{ula_network.prefixlen}"
        )


def make_client_config(args, lease: ClientLease) -> bytes:
    ula_network = ipaddress.ip_network(args.ula_prefix, strict=True)
    config = {
        "lease_seconds": 86400,
        "mtu": args.mtu,
        "dhcp4": {"address": str(lease.ipv4), "prefix": args.ipv4_prefix},
        "dhcp6": {"address": str(lease.ipv6), "prefix": 64},
        "gateway6": str(ula_network.network_address + 1),
        "slaac": {"site_prefix": str(args.ula_prefix), "prefix": str(lease.slaac_prefix)},
        "dns": args.dns,
        "routes": args.route,
    }
    return json.dumps(config, separators=(",", ":")).encode("utf-8")


class ClientSession:
    def __init__(self, server: "AutServer", key, connection: BulkConnection) -> None:
        self.server = server
        self.key = key
        self.connection = connection
        self.decoder = Decoder()
        self.client_id: str | None = None
        self.lease: ClientLease | None = None
        self.sequence = 1
        self.answered = 0
        self.rx_packets = 0
        self.tx_packets = 0
        self.registered = False
        self.dropped_packets = 0
        self.config_requested = False
        self.protocol_active = False
        self.pending_packets: list[bytes] = []
        self.icmp6_replies = 0

    @property
    def label(self) -> str:
        if self.client_id:
            return self.client_id[:8]
        return f"usb-{self.key[0]}-{self.key[1]}"

    def ip_frame(self, packet: bytes) -> Frame:
        frame = Frame(TYPE_IP_PACKET, self.sequence, time.monotonic_ns(), packet)
        self.sequence = (self.sequence + 1) & 0xFFFF_FFFF
        return frame

    def write_control(self, frame: Frame) -> bool:
        try:
            self.connection.write_frame(frame)
            return True
        except usb.core.USBError as error:
            if is_usb_timeout(error):
                return False
            raise

    def note_drop(self, reason: str) -> None:
        self.dropped_packets += 1
        if self.dropped_packets == 1 or self.dropped_packets % 1000 == 0:
            print(
                f"[{self.label}] dropped local/unassigned IP packet ({reason}); "
                f"total={self.dropped_packets}",
                flush=True,
            )

    def request_config(self) -> None:
        if self.config_requested:
            return
        self.config_requested = True
        self.write_control(
            Frame(TYPE_CONFIG_REQUIRED, self.sequence, time.monotonic_ns())
        )
        self.sequence = (self.sequence + 1) & 0xFFFF_FFFF

    def run(self) -> None:
        print(f"[{self.label}] USB accessory ready; waiting for the AUT app", flush=True)
        try:
            while True:
                try:
                    chunk = bytes(self.connection.endpoint_in.read(64 * 1024, timeout=1000))
                except usb.core.USBError as error:
                    if is_usb_timeout(error):
                        continue
                    raise
                for frame in self.decoder.feed(chunk):
                    if not self.protocol_active:
                        self.protocol_active = True
                        print(f"[{self.label}] AUT protocol active", flush=True)
                    self.handle(frame)
        except (
            usb.core.USBError, OSError, ProtocolError, RuntimeError,
            ValueError, TypeError, KeyError,
        ) as error:
            if not self.server.stopping:
                print(f"[{self.label}] disconnected: {error}", flush=True)
        finally:
            self.server.gateway.unregister(self)
            try:
                self.connection.close()
            finally:
                self.server.remove(self.key, self)

    def handle(self, frame: Frame) -> None:
        if frame.type == TYPE_PING:
            self.write_control(
                Frame(TYPE_PONG, frame.sequence, frame.timestamp_ns, frame.payload)
            )
            self.answered += 1
            if self.answered == 1 or self.answered % 10 == 0:
                print(
                    f"[{self.label}] PING/PONG={self.answered} "
                    f"IP android->linux={self.rx_packets} linux->android={self.tx_packets}",
                    flush=True,
                )
        elif frame.type == TYPE_CONFIG_REQUEST:
            self.server.ensure_network_ready()
            request = json.loads(frame.payload.decode("utf-8"))
            self.client_id = str(request["client_id"])
            self.lease = self.server.leases.allocate(
                self.client_id, str(request["host_id"])
            )
            self.config_requested = False
            config = make_client_config(self.server.args, self.lease)
            delivered = self.write_control(
                Frame(TYPE_CONFIG_RESPONSE, frame.sequence, time.monotonic_ns(), config)
            )
            print(
                f"[{self.label}] lease offered IPv4={self.lease.ipv4} "
                f"IPv6={self.lease.ipv6}/64"
                + ("" if delivered else " (Android reader is not active yet)"),
                flush=True,
            )
        elif frame.type == TYPE_CLIENT_READY:
            if self.lease is None:
                self.request_config()
                return
            self.server.gateway.register(self, self.lease)
            self.registered = True
            self.write_control(
                Frame(TYPE_CLIENT_READY_ACK, frame.sequence, time.monotonic_ns())
            )
            pending, self.pending_packets = self.pending_packets, []
            for packet in pending:
                self.server.gateway.write_packet(self, packet)
            print(f"[{self.label}] client ready; internet forwarding active", flush=True)
        elif frame.type == TYPE_ICMP6_ECHO:
            if self.lease is None:
                self.request_config()
                return
            gateway6 = ipaddress.ip_network(
                self.server.args.ula_prefix, strict=True
            ).network_address + 1
            echo_reply = icmp6_echo_reply(frame.payload, gateway6)
            if echo_reply is None:
                raise ProtocolError("invalid ICMPv6 Echo Request")
            self.connection.write_frame(self.ip_frame(echo_reply))
            self.icmp6_replies += 1
            if self.icmp6_replies == 1 or self.icmp6_replies % 10 == 0:
                print(
                    f"[{self.label}] ICMPv6 Echo Reply={self.icmp6_replies}",
                    flush=True,
                )
        elif frame.type == TYPE_IP_PACKET:
            if not self.registered:
                if self.lease is None:
                    self.request_config()
                    self.note_drop("client registration is being restored")
                else:
                    if len(self.pending_packets) < 128:
                        self.pending_packets.append(frame.payload)
                    else:
                        self.note_drop("CLIENT_READY queue is full")
                return
            self.server.gateway.write_packet(self, frame.payload)
            if self.rx_packets == 1 or self.rx_packets % 1000 == 0:
                print(
                    f"[{self.label}] dataplane android->aut0={self.rx_packets} "
                    f"aut0->android={self.tx_packets}",
                    flush=True,
                )
        elif frame.type == TYPE_SESSION_STOP:
            self.server.gateway.unregister(self)
            self.registered = False
            self.pending_packets.clear()
            print(f"[{self.label}] session stopped; USB remains available", flush=True)
        else:
            print(f"[{self.label}] ignored frame type={frame.type} seq={frame.sequence}")


class AutServer:
    def __init__(self, args) -> None:
        self.args = args
        self.leases = LeaseManager(args.ipv4_network, args.ula_prefix)
        self.gateway = TunGateway(args.tun, args.usb_batch_ms)
        self.sessions: dict[tuple[int, int, int, int], ClientSession] = {}
        self.probed: set[tuple[int, int, int, int]] = set()
        self.lock = threading.Lock()
        self.stopping = False
        self.network_validated = False
        self.network_validation_lock = threading.Lock()

    def ensure_network_ready(self) -> None:
        with self.network_validation_lock:
            if self.network_validated:
                return
            validate_tun_configuration(self.args)
            self.network_validated = True

    def remove(self, key, session: ClientSession) -> None:
        with self.lock:
            if self.sessions.get(key) is session:
                del self.sessions[key]

    def scan(self) -> None:
        devices = list(usb.core.find(find_all=True) or [])
        current = {device_key(device) for device in devices}
        self.probed.intersection_update(current)
        for device in devices:
            key = device_key(device)
            if is_accessory(device):
                with self.lock:
                    if key in self.sessions:
                        continue
                    try:
                        connection = BulkConnection.open(device)
                    except usb.core.USBError as error:
                        print(f"USB {key[0]}:{key[1]} is busy: {error}", file=sys.stderr)
                        continue
                    session = ClientSession(self, key, connection)
                    self.sessions[key] = session
                threading.Thread(
                    target=session.run, name=f"aut-client-{key[0]}-{key[1]}", daemon=True
                ).start()
            elif key not in self.probed:
                self.probed.add(key)
                try_enable_accessory(device)

    def run(self) -> None:
        print(
            "AUT/3 multiclient server ready; connect any AOA-capable Android device. "
            f"USB downlink batching={self.args.usb_batch_ms:g} ms. Press Ctrl+C to stop.",
            flush=True,
        )
        while True:
            self.scan()
            time.sleep(self.args.scan_interval)

    def close(self) -> None:
        self.stopping = True
        self.gateway.close()
        with self.lock:
            sessions = list(self.sessions.values())
        for session in sessions:
            session.connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="AUT multiclient gateway: IPv4, IPv6 and ping over USB Bulk"
    )
    parser.add_argument("--tun", default="aut0", help="prepared shared TUN (default: aut0)")
    parser.add_argument("--mtu", type=int, default=1400)
    parser.add_argument(
        "--usb-batch-ms", type=float, default=1.5,
        help="maximum downlink coalescing window in milliseconds (default: 1.5)",
    )
    parser.add_argument(
        "--ipv4-network", default="10.77.0.0/24",
        help="shared AUT private IPv4 pool (default: 10.77.0.0/24)",
    )
    parser.add_argument(
        "--ula-prefix", default="fd77:4155:5400::/48",
        help="site ULA delegated as per-client /64 prefixes (default: fd77:4155:5400::/48)",
    )
    parser.add_argument("--uplink", help="upstream interface used for DNS discovery")
    parser.add_argument(
        "--dns", action="append", default=None,
        help="advertised DNS; repeatable (default: DNS received by the Linux uplink)",
    )
    parser.add_argument(
        "--route", action="append", default=None,
        help="route sent to Android; repeatable (defaults to 0.0.0.0/0 and ::/0)",
    )
    parser.add_argument(
        "--scan-interval", type=float, default=1.0,
        help="seconds between USB discovery scans (default: 1)",
    )
    args = parser.parse_args()
    try:
        configure_networks(args)
        if args.dns is None:
            args.dns = discover_dns(args.uplink)
        if args.route is None:
            args.route = ["0.0.0.0/0", "::/0"]
        if args.scan_interval < 0.2:
            parser.error("--scan-interval must be at least 0.2")
        if not 0 <= args.usb_batch_ms <= 20:
            parser.error("--usb-batch-ms must be between 0 and 20")
        print(f"Upstream DNS inherited by Android: {', '.join(args.dns)}", flush=True)
        server = AutServer(args)
        try:
            server.run()
        finally:
            server.close()
    except KeyboardInterrupt:
        print("\nServer stopped.")
    except (RuntimeError, usb.core.USBError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
