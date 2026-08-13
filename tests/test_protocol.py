import unittest
import argparse
import json

from aut_protocol import (
    Decoder,
    Frame,
    HandshakeDecoder,
    ProtocolError,
    RtcpDecoder,
    TYPE_CONFIG_RESPONSE,
    TYPE_CONFIG_REQUIRED,
    TYPE_CLIENT_READY,
    TYPE_CLIENT_READY_ACK,
    TYPE_ICMP6_ECHO,
    TYPE_IP_PACKET,
    TYPE_PING,
    TYPE_PONG,
    TYPE_SESSION_STOP,
    TYPE_RTCP_DATA,
    TYPE_RUDP_PACKET,
    encode,
    encode_handshake_request,
    encode_handshake_response,
    encode_rtcp_packet,
    negotiate_request,
)
from aut_server import ClientSession, LeaseManager, configure_networks, make_client_config


class ProtocolTests(unittest.TestCase):
    def test_round_trip_split_in_every_position(self):
        original = Frame(TYPE_PING, 0x71234567, 123456789, b"pure usb")
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

    def test_reserved_stream_id_bit_is_rejected(self):
        wire = bytearray(encode(Frame(TYPE_PING, 7, 99, b"abc")))
        wire[5] |= 0x80
        with self.assertRaisesRegex(ProtocolError, "reserved"):
            Decoder().feed(wire)

    def test_cleartext_handshake_can_share_transfer_with_binary_frame(self):
        request = encode_handshake_request({
            "Transport": "rudp", "Mode": "internet-only", "Framing": "binary-h2"
        })
        binary = encode(Frame(TYPE_RUDP_PACKET, 8, 123, b"packet"))
        decoder = HandshakeDecoder()
        handshake, remainder = decoder.feed(request + binary)
        self.assertEqual(handshake[0], "HANDSHAKE AUT/4.0")
        self.assertEqual(handshake[1]["transport"], "rudp")
        self.assertEqual(negotiate_request(*handshake), ("4.0", "rudp"))
        self.assertEqual(Decoder().feed(remainder)[0].payload, b"packet")

    def test_handshake_response_is_cleartext(self):
        wire = encode_handshake_response("4.0", 200, "OK", {
            "Transport": "rtcp; accepted", "Framing": "binary-h2; accepted"
        })
        self.assertTrue(wire.startswith(b"AUT/4.0 200 OK\r\n"))
        self.assertTrue(wire.endswith(b"\r\n\r\n"))

    def test_every_packet_path_is_negotiated(self):
        for transport in ("direct", "udp", "tcp", "rudp", "rtcp"):
            request = encode_handshake_request({
                "Transport": transport,
                "Mode": "internet-only",
                "Framing": "binary-h2",
            })
            handshake, remainder = HandshakeDecoder().feed(request)
            self.assertEqual(remainder, b"")
            self.assertEqual(negotiate_request(*handshake), ("4.0", transport))

    def test_session_stop_can_be_followed_by_handshake_in_same_usb_read(self):
        stop = encode(Frame(TYPE_SESSION_STOP, 10, 55))
        request = encode_handshake_request({
            "Transport": "rtcp",
            "Mode": "internet-only",
            "Framing": "binary-h2",
        })
        decoder = Decoder()
        self.assertEqual(
            decoder.feed(stop + request, max_frames=1),
            [Frame(TYPE_SESSION_STOP, 10, 55)],
        )
        handshake, remainder = HandshakeDecoder().feed(decoder.take_pending())
        self.assertEqual(remainder, b"")
        self.assertEqual(negotiate_request(*handshake), ("4.0", "rtcp"))

    def test_server_renegotiates_after_stop_without_reopening_usb(self):
        class FakeGateway:
            def unregister(self, _session):
                pass

        class FakeServer:
            gateway = FakeGateway()

        class FakeConnection:
            def __init__(self):
                self.responses = []

            def write_raw(self, wire):
                self.responses.append(wire)

        connection = FakeConnection()
        session = ClientSession(FakeServer(), (1, 2, 3, 4), connection)
        first = encode_handshake_request({
            "Transport": "direct", "Mode": "internet-only", "Framing": "binary-h2"
        })
        stop = encode(Frame(TYPE_SESSION_STOP, 10, 55))
        second = encode_handshake_request({
            "Transport": "rtcp", "Mode": "internet-only", "Framing": "binary-h2"
        })
        session.consume(first + stop + second)
        self.assertTrue(session.handshake_complete)
        self.assertEqual(session.transport, "rtcp")
        self.assertEqual(len(connection.responses), 2)
        self.assertTrue(all(response.startswith(b"AUT/4.0 200 OK")
                            for response in connection.responses))

    def test_rtcp_reassembles_every_split(self):
        packet = bytes(range(256)) * 5
        stream = encode_rtcp_packet(packet)
        for split in range(len(stream)):
            decoder = RtcpDecoder()
            self.assertEqual(decoder.feed(stream[:split]), [])
            self.assertEqual(decoder.feed(stream[split:]), [packet])

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
