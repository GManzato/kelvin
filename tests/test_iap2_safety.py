"""Adversarial boundary tests for iap2host framing, parameter parsing, and state.

These pin the safety contract of the link parser:

* Malformed TLV/CSM/SYN data raises ValueError (never struct.error, IndexError,
  a hang, or a partial result), and never mutates device state.
* A valid header whose payload checksum fails drops exactly the declared
  frame: no ACK, no rx_seq change, so a retransmitted good frame of the same
  sequence still delivers.
* Illegal declared lengths (0..8, 10) resync one byte forward; lengths 9 and
  11..65535 are the only legal ones.
* Between polls the receive buffer holds only possible marker/header prefixes
  or one valid bounded partial frame, and the total is capped at MAX_BUFFER.
* describe() validates required nested protocol field sizes before indexing.

Fixtures are encoded from scratch here (same style as test_iap2host.py) so the
parser is exercised through raw bytes, not iap2host's own encoders.

Run from the repo root:  python3 -m unittest discover -s tests -v
"""

import errno
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import usb.core  # noqa: E402  (pyusb; required by iap2host itself)

from iap2host import (  # noqa: E402
    ACK,
    DETECT,
    MAX_PACKET,
    MAX_READ,
    SYN,
    IAP2Device,
    parse_params,
)


# ---------------------------------------------------------------------------
# Wire fixtures (independent of iap2host's encoders)
# ---------------------------------------------------------------------------

def _checksum(data):
    return (-sum(data)) & 0xFF


def acc_packet(control, seq, ack, session, payload=b""):
    """Encode one well-formed accessory->host frame."""
    length = 9 + (len(payload) + 1 if payload else 0)
    hdr = struct.pack(">HHBBBB", 0xFF5A, length, control, seq, ack, session)
    frame = hdr + bytes([_checksum(hdr)])
    if payload:
        frame += payload + bytes([_checksum(payload)])
    return frame


def raw_frame(length, control=ACK, seq=0, ack=0, session=0, trailer=b""):
    """Header with an arbitrary (possibly illegal) declared length; header
    checksum is made valid so only the length itself is the fault."""
    hdr = struct.pack(">HHBBBB", 0xFF5A, length, control, seq, ack, session)
    return hdr + bytes([_checksum(hdr)]) + trailer


def corrupt_payload(frame):
    """Flip the payload-checksum byte of an otherwise valid frame."""
    bad = bytearray(frame)
    bad[-1] ^= 0xFF
    return bytes(bad)


def lsp_with_sessions(triples):
    body = bytearray(10)
    for sid, typ in triples:
        body += bytes([sid, typ, 0])
    return bytes(body)


def tlv(pid, payload):
    return struct.pack(">HH", len(payload) + 4, pid) + payload


def decode_host_frame(frame):
    magic, length, control, seq, ack, session = struct.unpack(">HHBBBB", frame[:8])
    if length > 9:
        payload = frame[9 : length - 1]
        pay_ok = frame[length - 1] == _checksum(payload)
    else:
        payload, pay_ok = b"", True
    return {
        "magic": magic,
        "length": length,
        "control": control,
        "seq": seq,
        "ack": ack,
        "session": session,
        "payload": payload,
        "hdr_ok": sum(frame[:9]) & 0xFF == 0,
        "pay_ok": pay_ok,
    }


class FakeTransport:
    """Byte pipe only: queued read chunks out, captured write frames in."""

    def __init__(self, reads=()):
        self._reads = [bytes(chunk) for chunk in reads]
        self.writes = []

    def queue(self, *chunks):
        self._reads.extend(bytes(c) for c in chunks)

    def read(self, ep, size, timeout):
        if not self._reads:
            raise usb.core.USBTimeoutError("synthetic timeout", errno=errno.ETIMEDOUT)
        chunk = self._reads.pop(0)
        assert 0 < len(chunk) <= size, "chunk must fit requested size"
        return list(chunk)

    def write(self, ep, data, timeout):
        self.writes.append(bytes(data))


class SafetyCase(unittest.TestCase):
    def setUp(self):
        self.t = FakeTransport()
        self.dev = IAP2Device(self.t)
        self.dev.tx_seq = 0x2B
        self.dev.rx_seq = 0


# ---------------------------------------------------------------------------
# TLV parameter parsing: strict, all-or-nothing, bounded
# ---------------------------------------------------------------------------

class TLVParsingTests(unittest.TestCase):
    def test_zero_length_tlv_raises_valueerror_immediately(self):
        # Bounded: returns at once instead of spinning on ln == 0.
        with self.assertRaises(ValueError):
            parse_params(struct.pack(">HH", 0, 1) + tlv(2, b"x"))
        with self.assertRaises(ValueError):
            parse_params(struct.pack(">HH", 1, 1))  # 1..3 also below the 4-byte minimum

    def test_tlv_length_past_block_end_raises(self):
        with self.assertRaises(ValueError):
            parse_params(struct.pack(">HH", 100, 5) + b"ab")  # truncated TLV

    def test_short_tlv_tail_and_empty_input(self):
        good = tlv(7, b"ABCD")
        for tail in (b"\x00", b"\x00\x00", b"\x00\x00\x00"):
            with self.subTest(tail=len(tail)):
                with self.assertRaises(ValueError):
                    parse_params(good + tail)
        self.assertEqual(parse_params(b""), [])
        self.assertEqual(parse_params(good), [(7, b"ABCD")])  # well-formed still parses


# ---------------------------------------------------------------------------
# CSM (0x4040) semantic validation in wait_csm
# ---------------------------------------------------------------------------

class CSMValidationTests(SafetyCase):
    def setUp(self):
        super().setUp()
        self.dev.control_sid = 1

    def _deliver(self, csm_payload):
        self.t.queue(acc_packet(ACK, seq=1, ack=0, session=1, payload=csm_payload))

    def test_truncated_csm_header_raises_valueerror(self):
        self._deliver(b"\x40\x40\x00")  # < 6-byte header: struct.error before
        with self.assertRaises(ValueError):
            self.dev.wait_csm(0xAA01, timeout=0.2)

    def test_csm_declared_length_mismatch_raises_valueerror(self):
        self._deliver(struct.pack(">HHH", 0x4040, 999, 0xAA01))
        with self.assertRaises(ValueError):
            self.dev.wait_csm(0xAA01, timeout=0.2)

    def test_wellformed_csm_still_parses(self):
        params = tlv(0, b"cert-bytes")
        payload = struct.pack(">HHH", 0x4040, 6 + len(params), 0xAA01) + params
        self._deliver(payload)
        self.assertEqual(self.dev.wait_csm(0xAA01, timeout=0.5), (0xAA01, [(0, b"cert-bytes")]))


# ---------------------------------------------------------------------------
# SYN link parameters: validated before any state mutation
# ---------------------------------------------------------------------------

class SynValidationTests(SafetyCase):
    def _assert_rejected(self, lsp):
        self.t.queue(acc_packet(SYN, seq=0x30, ack=0, session=0, payload=lsp))
        with self.assertRaises(ValueError):
            self.dev.poll(timeout_ms=1)
        self.assertIsNone(self.dev.lsp)
        self.assertIsNone(self.dev.control_sid)
        self.assertIsNone(self.dev.ea_sid)
        self.assertFalse(self.dev.linked)
        self.assertEqual(self.dev.rx_seq, 0)  # rx state untouched
        self.assertEqual(self.t.writes, [])  # no SYN|ACK echo

    def test_syn_shorter_than_10_reserved_bytes_rejected(self):
        self._assert_rejected(b"\x00" * 9)

    def test_syn_trailing_partial_triple_rejected(self):
        # 12 reserved-length bytes: the old code died with IndexError here.
        self._assert_rejected(bytes(range(12)))

    def test_new_syn_requires_a_fresh_plain_ack_to_establish_the_link(self):
        params = lsp_with_sessions([(1, 0), (2, 2)])
        self.t.queue(acc_packet(SYN, 30, 0, 0, params) + acc_packet(ACK, 31, 0, 0))
        self.dev.poll(timeout_ms=1)
        self.assertTrue(self.dev.linked)
        self.t.queue(acc_packet(SYN, 40, 0, 0, params))
        self.dev.poll(timeout_ms=1)
        self.assertFalse(self.dev.linked)
        self.t.queue(acc_packet(ACK | 0x20, 41, 0, 0, b"not-a-link-ack"))
        self.dev.poll(timeout_ms=1)
        self.assertFalse(self.dev.linked)
        self.t.queue(acc_packet(ACK, 42, 0, 0))
        self.dev.poll(timeout_ms=1)
        self.assertTrue(self.dev.linked)

    def test_missing_negotiated_sessions_fail_before_sending_requests(self):
        self.t.queue(
            acc_packet(SYN, 30, 0, 0, lsp_with_sessions([(2, 2)])) +
            acc_packet(ACK, 31, 0, 0)
        )
        self.dev.poll(timeout_ms=1)
        self.t.writes.clear()
        with self.assertRaises(ValueError):
            self.dev.handshake()
        self.assertEqual(self.t.writes, [])
        self.t.queue(acc_packet(SYN, 40, 0, 0, lsp_with_sessions([(1, 0)])))
        self.dev.poll(timeout_ms=1)
        self.t.writes.clear()
        with self.assertRaises(ValueError):
            self.dev.start_ea(0, 1)
        with self.assertRaises(ValueError):
            self.dev.send_ea(1, b"not-sent")
        self.assertEqual(self.t.writes, [])


# ---------------------------------------------------------------------------
# Payload checksum enforcement and frame-state integrity
# ---------------------------------------------------------------------------

class FrameIntegrityTests(SafetyCase):
    def test_corrupt_payload_no_ack_no_state_then_same_seq_retry_delivers(self):
        pkt = acc_packet(ACK, seq=7, ack=0, session=1, payload=b"first-attempt")
        self.t.queue(corrupt_payload(pkt))
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(self.t.writes, [])
        self.assertEqual(self.dev.rx_seq, 0)
        # The accessory retransmits the same sequence with a good payload:
        # it must not look like an already-seen frame.
        self.t.queue(pkt)
        self.assertEqual(self.dev.poll(timeout_ms=1), [(1, b"first-attempt")])
        self.assertEqual(self.dev.rx_seq, 7)
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [7])

    def test_corrupt_frame_dropped_exactly_decoy_inside_payload_ignored(self):
        decoy = acc_packet(ACK, seq=99, ack=0, session=5, payload=b"decoy")
        corrupt = corrupt_payload(acc_packet(ACK, seq=8, ack=0, session=1, payload=decoy))
        good = acc_packet(ACK, seq=9, ack=0, session=1, payload=b"after")
        self.t.queue(corrupt + good)  # one read, both frames
        self.assertEqual(self.dev.poll(timeout_ms=1), [(1, b"after")])
        self.assertEqual(self.dev.rx_seq, 9)  # never 99: inner header not re-scanned
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [9])
        self.assertEqual(bytes(self.dev.buf), b"")  # exactly length bytes consumed

    def test_corrupt_syn_payload_discarded_without_link_or_state(self):
        lsp = lsp_with_sessions([(1, 0), (2, 2)])
        self.t.queue(corrupt_payload(acc_packet(SYN, seq=0x11, ack=0, session=0, payload=lsp)))
        self.assertEqual(self.dev.poll(timeout_ms=1), [])  # no ValueError: dropped as bad frame
        self.assertIsNone(self.dev.lsp)
        self.assertIsNone(self.dev.control_sid)
        self.assertFalse(self.dev.linked)
        self.assertEqual(self.dev.rx_seq, 0)
        self.assertEqual(self.t.writes, [])

    def test_corrupt_rst_payload_does_not_disconnect_or_hide_following_data(self):
        bad_rst = corrupt_payload(acc_packet(0x10, 5, 0, 0, b"corrupt-reset"))
        good = acc_packet(ACK, 5, 0, 2, b"still-connected")
        self.t.queue(bad_rst + good)
        self.assertEqual(self.dev.poll(timeout_ms=1), [(2, b"still-connected")])
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [5])

    def test_illegal_length_headers_resync_forward(self):
        for bad_len in (0, 8, 10):
            with self.subTest(length=bad_len):
                t = FakeTransport()
                dev = IAP2Device(t)
                junk = raw_frame(bad_len, control=ACK, seq=0x07)
                if bad_len == 10:
                    junk += b"\x00"  # the phantom payload-checksum byte
                good = acc_packet(ACK, seq=7, ack=0, session=1, payload=b"len-recovery")
                t.queue(junk + good)
                self.assertEqual(dev.poll(timeout_ms=1), [(1, b"len-recovery")])
                self.assertEqual(dev.rx_seq, 7)
                self.assertEqual([decode_host_frame(w)["ack"] for w in t.writes], [7])
                self.assertEqual(bytes(dev.buf), b"")

    def test_length_nine_empty_frame_is_legal_and_state_free(self):
        # A legal empty frame must parse and must not deliver or change rx state.
        self.t.queue(raw_frame(9, control=ACK, seq=0x04) + acc_packet(ACK, seq=4, ack=0, session=1, payload=b"x"))
        self.assertEqual(self.dev.poll(timeout_ms=1), [(1, b"x")])
        self.assertEqual(self.dev.rx_seq, 4)
        self.assertEqual(bytes(self.dev.buf), b"")


# ---------------------------------------------------------------------------
# Marker handling and receive-buffer bounds
# ---------------------------------------------------------------------------

class MarkerAndGarbageTests(SafetyCase):
    def test_split_detect_after_large_garbage_keeps_only_marker_prefix(self):
        self.t.queue(b"\x11" * 5000 + DETECT[:2])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(bytes(self.dev.buf), DETECT[:2])  # garbage compacted once
        self.assertEqual(self.t.writes, [])
        self.t.queue(DETECT[2:] + acc_packet(ACK, seq=12, ack=0, session=2, payload=b"post-garbage"))
        self.assertEqual(self.dev.poll(timeout_ms=1), [(2, b"post-garbage")])
        self.assertEqual(self.t.writes[0], DETECT)  # split marker still answered
        self.assertEqual(bytes(self.dev.buf), b"")

    def test_sustained_garbage_leaves_empty_buffer(self):
        for _ in range(3):
            self.t.queue(b"\xaa" * 65536)  # full 64 KiB read of unstartable bytes
            self.assertEqual(self.dev.poll(timeout_ms=1), [])
            self.assertEqual(bytes(self.dev.buf), b"")  # retained state cannot accumulate
        self.assertEqual(self.t.writes, [])

    def test_max_length_bad_packet_is_discarded_and_good_retry_recovers(self):
        self.t.queue(raw_frame(MAX_PACKET, control=ACK, seq=1))
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertLess(len(self.dev.buf), MAX_PACKET)
        for _ in range(3):
            self.t.queue(b"\xaa" * MAX_READ)
            self.assertEqual(self.dev.poll(timeout_ms=1), [])
            self.assertLess(len(self.dev.buf), MAX_PACKET)
            self.assertEqual(self.dev.rx_seq, 0)
            self.assertEqual(self.t.writes, [])
        good = acc_packet(ACK, seq=1, ack=0, session=2, payload=b"recovered")
        self.t.queue(good)
        self.assertEqual(self.dev.poll(timeout_ms=1), [(2, b"recovered")])
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [1])

    def test_transport_cannot_exceed_the_requested_read_bound(self):
        class OversizedTransport(FakeTransport):
            def read(self, ep, size, timeout):
                return b"\xaa" * (size + 1)
        transport = OversizedTransport()
        device = IAP2Device(transport)
        with self.assertRaises(ValueError):
            device.poll(timeout_ms=1)
        self.assertEqual(bytes(device.buf), b"")
        self.assertEqual(transport.writes, [])
        self.assertEqual(device.rx_seq, 0)


class MaxFrameAndSequenceTests(SafetyCase):
    def test_max_16bit_frame_split_across_reads_delivers_once(self):
        body = (bytes(range(256)) * 256)[: 65535 - 10]  # payload for a 65535-byte frame
        pkt = acc_packet(ACK, seq=0x77, ack=0, session=6, payload=body)
        self.assertEqual(len(pkt), MAX_PACKET)
        self.t.queue(pkt[:40000])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(len(self.dev.buf), 40000)  # bounded partial frame
        self.assertEqual(self.t.writes, [])
        self.t.queue(pkt[40000:])
        self.assertEqual(self.dev.poll(timeout_ms=1), [(6, body)])
        self.assertEqual(self.dev.rx_seq, 0x77)
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [0x77])
        self.assertEqual(bytes(self.dev.buf), b"")

    def test_receive_sequence_wrap_delivers_each_frame_once(self):
        p = lambda s, d: acc_packet(ACK, seq=s, ack=0, session=1, payload=d)
        self.t.queue(p(0xFE, b"a") + p(0xFF, b"b") + p(0x00, b"c"))
        self.assertEqual(self.dev.poll(timeout_ms=1), [(1, b"a"), (1, b"b"), (1, b"c")])
        self.assertEqual(self.dev.rx_seq, 0x00)
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [0xFE, 0xFF, 0x00])


# ---------------------------------------------------------------------------
# describe(): nested protocol TLV field-size validation
# ---------------------------------------------------------------------------

class DescribeValidationTests(SafetyCase):
    def test_malformed_protocol_fields_raise_valueerror_not_indexerror(self):
        cases = {
            "empty-id": [(10, tlv(0, b"") + tlv(1, b"com.flir"))],
            "long-id": [(10, tlv(0, b"\x02\x03") + tlv(1, b"x"))],
            "missing-name": [(10, tlv(0, b"\x02"))],
            "wrong-native-size": [(10, tlv(0, b"\x02") + tlv(1, b"x") + tlv(3, b"\x00"))],
            "truncated-nested-tlv": [(10, tlv(0, b"\x02") + b"\x00\x02\x00\x09\x00")],
        }
        for label, ident in cases.items():
            with self.subTest(case=label):
                self.dev.ident = ident
                with self.assertRaises(ValueError):
                    self.dev.describe()

    def test_wellformed_protocols_parse_and_text_stays_tolerant(self):
        self.dev.ident = [
            (3, b"SN\xff\x00"),  # undecodable byte must not crash: U+FFFD
            (10, tlv(0, b"\x02") + tlv(1, b"com.flir.TLStream\x00") + tlv(3, b"\x01\x00")),
            (10, tlv(0, b"\x04") + tlv(1, b"com.flir.LENSDistortion")),
        ]
        info = self.dev.describe()
        self.assertEqual(info["serial"], "SN\ufffd")
        self.assertEqual(
            info["protocols"],
            [
                {"id": 2, "name": "com.flir.TLStream", "native": 256},
                {"id": 4, "name": "com.flir.LENSDistortion", "native": None},
            ],
        )


if __name__ == "__main__":
    unittest.main()
