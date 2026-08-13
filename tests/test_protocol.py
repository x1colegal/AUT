import unittest
import argparse
import json

from aut_protocol import (
    Decoder,
    Frame,
    ProtocolError,
    TYPE_CONFIG_RESPONSE,
    TYPE_CONFIG_REQUIRED,
    TYPE_CLIENT_READY,
    TYPE_CLIENT_READY_ACK,
    TYPE_ICMP6_ECHO,
    TYPE_IP_PACKET,
    TYPE_PING,
    TYPE_PONG,
    TYPE_SESSION_STOP,
    encode,
)
from aut_server import LeaseManager, configure_networks, make_client_config


class ProtocolTests(unittest.TestCase):
    def test_round_trip_split_in_every_position(self):
        original = Frame(TYPE_PING, 0xF1234567, 123456789, b"usb puro")
        wire = encode(original)
        for split in range(len(wire)):
            decoder = Decoder()
            self.assertEqual(decoder.feed(wire[:split]), [])
            self.assertEqual(decoder.feed(wire[split:]), [original])

    def test_multiple_frames_in_one_transfer(self):
        frames = [
            Frame(TYPE_PING, 1, 10),
            Frame(TYPE_PONG, 1, 10),
            Frame(TYPE_SESSION_STOP, 2, 11),
            Frame(TYPE_CONFIG_REQUIRED, 3, 12),
            Frame(TYPE_CLIENT_READY, 4, 13),
            Frame(TYPE_CLIENT_READY_ACK, 5, 14),
            Frame(TYPE_ICMP6_ECHO, 6, 15, b"ipv6"),
        ]
        self.assertEqual(Decoder().feed(b"".join(map(encode, frames))), frames)

    def test_crc_corruption_is_rejected(self):
        wire = bytearray(encode(Frame(TYPE_PING, 7, 99, b"abc")))
        wire[-5] ^= 1
        with self.assertRaisesRegex(ProtocolError, "CRC32"):
            Decoder().feed(wire)

    def test_ip_packet_round_trip(self):
        ipv4 = bytes.fromhex("4500001400000000400100000a4d000208080808")
        frame = Frame(TYPE_IP_PACKET, 9, 42, ipv4)
        self.assertEqual(Decoder().feed(encode(frame)), [frame])

    def test_dual_stack_lease_and_extra_routes(self):
        args = argparse.Namespace(
            mtu=1400,
            ipv4_network="10.88.0.0/20",
            ula_prefix="fd42:1234:5678::/48",
            dns=["1.1.1.1", "fd20::53"],
            route=["0.0.0.0/0", "::/0", "10.20.0.0/16"],
        )
        configure_networks(args)
        lease = LeaseManager(args.ipv4_network, args.ula_prefix).allocate(
            "client-a", "123456789abcdef0"
        )
        payload = make_client_config(args, lease)
        wire = encode(Frame(TYPE_CONFIG_RESPONSE, 3, 7, payload))
        decoded = Decoder().feed(wire)[0]
        lease = json.loads(decoded.payload)
        self.assertEqual(lease["dhcp4"]["prefix"], 20)
        self.assertTrue(lease["dhcp4"]["address"].startswith("10.88."))
        self.assertTrue(lease["dhcp6"]["address"].endswith("1234:5678:9abc:def0"))
        self.assertEqual(lease["slaac"]["site_prefix"], "fd42:1234:5678::/48")
        self.assertIn("10.20.0.0/16", lease["routes"])

    def test_multiclient_leases_have_distinct_addresses_and_slaac_subnets(self):
        manager = LeaseManager("10.77.0.0/24", "fd77:4155:5400::/48")
        first = manager.allocate("client-a", "1111222233334444")
        second = manager.allocate("client-b", "aaaabbbbccccdddd")
        self.assertNotEqual(first.ipv4, second.ipv4)
        self.assertNotEqual(first.ipv6, second.ipv6)
        self.assertNotEqual(first.slaac_prefix, second.slaac_prefix)
        self.assertEqual(int(first.ipv6) & ((1 << 64) - 1), 0x1111222233334444)


if __name__ == "__main__":
    unittest.main()
