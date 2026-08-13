import unittest
import argparse
import json

from aut_protocol import (
    ControlRecord,
    Decoder,
    Frame,
    HandshakeDecoder,
    ProtocolError,
    RatpEngine,
    TYPE_CONFIG_RESPONSE,
    TYPE_CONFIG_REQUIRED,
    TYPE_CLIENT_READY,
    TYPE_CLIENT_READY_ACK,
    TYPE_ICMP6_ECHO,
    TYPE_IP_PACKET,
    TYPE_PING,
    TYPE_PONG,
    TYPE_SESSION_STOP,
    TYPE_RATP_DATA,
    encode,
    encode_control,
    encode_handshake_request,
    encode_handshake_response,
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
            "Transport": "ratp", "Mode": "internet-only", "Framing": "binary-h2"
        })
        binary = encode(Frame(TYPE_RATP_DATA, 8, 123, b"packet"))
        decoder = HandshakeDecoder()
        handshake, remainder = decoder.feed(request + binary)
        self.assertEqual(handshake[0], "HANDSHAKE AUT/4.0")
        self.assertEqual(handshake[1]["transport"], "ratp")
        self.assertEqual(negotiate_request(*handshake), ("4.0", "ratp"))
        self.assertEqual(Decoder().feed(remainder)[0].payload, b"packet")

    def test_handshake_response_is_cleartext(self):
        wire = encode_handshake_response("4.0", 200, "OK", {
            "Transport": "ratp; accepted", "Framing": "binary-h2; accepted"
        })
        self.assertTrue(wire.startswith(b"AUT/4.0 200 OK\r\n"))
        self.assertTrue(wire.endswith(b"\r\n\r\n"))

    def test_every_packet_path_is_negotiated(self):
        for transport in ("direct", "ratp"):
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
            "Transport": "ratp",
            "Mode": "internet-only",
            "Framing": "binary-h2",
        })
        decoder = Decoder()
        self.assertEqual(
            decoder.feed(stop + request, max_records=1),
            [Frame(TYPE_SESSION_STOP, 10, 55)],
        )
        handshake, remainder = HandshakeDecoder().feed(decoder.take_pending())
        self.assertEqual(remainder, b"")
        self.assertEqual(negotiate_request(*handshake), ("4.0", "ratp"))

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
            "Transport": "ratp", "Mode": "internet-only", "Framing": "binary-h2"
        })
        session.consume(first + stop + second)
        self.assertTrue(session.handshake_complete)
        self.assertEqual(session.transport, "ratp")
        self.assertEqual(len(connection.responses), 2)
        self.assertTrue(all(response.startswith(b"AUT/4.0 200 OK")
                            for response in connection.responses))

    def test_cleartext_ack_and_binary_frame_can_share_every_split(self):
        ack = encode_control("ACK", (1, 2, 9))
        frame = Frame(TYPE_RATP_DATA, 10, 123, b"packet")
        wire = ack + encode(frame)
        for split in range(len(wire)):
            decoder = Decoder()
            records = decoder.feed(wire[:split]) + decoder.feed(wire[split:])
            self.assertEqual(records, [ControlRecord("ACK", (1, 2, 9)), frame])

    def test_ack_line_carries_256_selective_sequences(self):
        sequences = tuple(range(1, 257))
        wire = encode_control("ACK", sequences)
        self.assertTrue(wire.startswith(b"ACK 00000001,00000002"))
        self.assertEqual(Decoder().feed(wire), [ControlRecord("ACK", sequences)])

    def test_ratp_orders_packets_and_selectively_acks_them(self):
        sender = RatpEngine()
        receiver = RatpEngine()
        first = sender.send_packet(b"one")
        second = sender.send_packet(b"two")
        third = sender.send_packet(b"three")
        self.assertEqual(receiver.receive(second), [])
        self.assertEqual(receiver.receive(first), [b"one", b"two"])
        self.assertEqual(receiver.receive(third), [b"three"])
        work = receiver.maintenance(force_controls=True)
        records = []
        for line in work.controls:
            records.extend(Decoder().feed(line))
        self.assertEqual(records, [ControlRecord("ACK", (1, 2, 3))])
        for control in records:
            self.assertEqual(sender.handle_control(control), ())
        self.assertEqual(sender.pending_count, 0)

    def test_ratp_nack_and_rto_retransmit_without_stop_and_wait(self):
        sender = RatpEngine()
        frames = [sender.send_packet(bytes((index,))) for index in range(1, 5)]
        self.assertEqual(sender.pending_count, 4)
        retransmitted = sender.handle_control(ControlRecord("NACK", (2, 4)))
        self.assertEqual(retransmitted, (frames[1], frames[3]))
        sender.rto_ns = 0
        self.assertEqual(len(sender.maintenance().retransmissions), 4)

    def test_ratp_splits_a_large_gap_into_256_sequence_nacks(self):
        receiver = RatpEngine()
        receiver.receive(Frame(TYPE_RATP_DATA, 600, 1, b"late"))
        work = receiver.maintenance(force_controls=True)
        nacks = [record for line in work.controls
                 for record in Decoder().feed(line) if record.kind == "NACK"]
        self.assertEqual([len(record.sequences) for record in nacks], [256, 256, 87])
        self.assertEqual(nacks[0].sequences[0], 1)
        self.assertEqual(nacks[-1].sequences[-1], 599)

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
