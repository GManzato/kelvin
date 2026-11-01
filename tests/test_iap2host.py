"""Hardware-free regression tests for iap2host.IAP2Device.

These drive the real byte-stream parser and link-layer state machine through an
in-memory USB transport that only moves bytes: queued read chunks in, captured
write frames out. All accessory->host wire fixtures are encoded here from
scratch, independent of iap2host's own encoders, so the tests verify the
device's actual parsing, state transitions, and protocol-required wire frames.

Run from the repo root:  python3 -m unittest discover -s tests -v
"""

import errno
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import usb.core  # noqa: E402  (pyusb; required by iap2host itself)

from iap2host import ACK, DETECT, RST, SYN, IAP2Device  # noqa: E402


# ---------------------------------------------------------------------------
# Wire fixtures for accessory -> host packets (independent of iap2host helpers)
# ---------------------------------------------------------------------------

def _checksum(data):
    """Two's-complement byte checksum, computed locally for fixtures."""
    return (-sum(data)) & 0xFF


def acc_packet(control, seq, ack, session, payload=b""):
    """Encode one accessory->host packet: FF5A header + checksum + payload + checksum."""
    length = 9 + (len(payload) + 1 if payload else 0)
    hdr = struct.pack(">HHBBBB", 0xFF5A, length, control, seq, ack, session)
    frame = hdr + bytes([_checksum(hdr)])
    if payload:
        frame += payload + bytes([_checksum(payload)])
    return frame


def lsp_with_sessions(triples):
    """Link parameters payload: 10 reserved bytes then (id, type, version) triples."""
    body = bytearray(10)
    for sid, typ in triples:
        body += bytes([sid, typ, 0])
    return bytes(body)


def decode_host_frame(frame):
    """Decode one host->accessory frame written by IAP2Device._write/send."""
    magic, length, control, seq, ack, session = struct.unpack(">HHBBBB", frame[:8])
    hdr_ok = sum(frame[:9]) & 0xFF == 0
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
        "hdr_ok": hdr_ok,
        "pay_ok": pay_ok,
    }


class FakeTransport:
    """Byte pipe only: queued read chunks out, captured write frames in.

    Raises the real usb.core.USBTimeoutError when the read queue is empty, so
    _pull()'s timeout handling is exercised without any sleeping. Contains no
    protocol logic and never echoes anything back.
    """

    def __init__(self, reads=()):
        self._reads = [bytes(chunk) for chunk in reads]
        self.writes = []  # host->accessory frames, in order

    def queue(self, *chunks):
        self._reads.extend(bytes(c) for c in chunks)

    def read(self, ep, size, timeout):
        if not self._reads:
            raise usb.core.USBTimeoutError("synthetic timeout", errno=errno.ETIMEDOUT)
        chunk = self._reads.pop(0)
        assert 0 < len(chunk) <= size, "chunk must fit requested size"
        return list(chunk)  # pyusb yields ints; iap2host wraps in bytes()

    def write(self, ep, data, timeout):
        self.writes.append(bytes(data))


class LinkReceiveTests(unittest.TestCase):
    def setUp(self):
        self.t = FakeTransport()
        self.dev = IAP2Device(self.t)
        self.dev.tx_seq = 0x2B
        self.dev.rx_seq = 0


    def test_data_packet_delivered_once_and_acked_on_wire(self):
        payload = b"FLIR\x00\x01"
        self.t.queue(acc_packet(ACK, seq=5, ack=0x2B, session=3, payload=payload))
        out = self.dev.poll(timeout_ms=1)
        self.assertEqual(out, [(3, payload)])
        self.assertEqual(self.dev.rx_seq, 5)
        self.assertEqual(self.dev.tx_seq, 0x2B)  # rx path must not bump tx_seq
        self.assertEqual(bytes(self.dev.buf), b"")
        self.assertEqual(len(self.t.writes), 1)
        f = decode_host_frame(self.t.writes[0])
        self.assertEqual((f["magic"], f["control"]), (0xFF5A, ACK))
        self.assertEqual((f["seq"], f["ack"], f["session"]), (0x2B, 5, 0))
        self.assertEqual((f["length"], f["payload"]), (9, b""))
        self.assertTrue(f["hdr_ok"] and f["pay_ok"])

    def test_header_and_payload_split_across_reads(self):
        pkt = acc_packet(ACK, seq=9, ack=0, session=2, payload=b"thermal-frame")
        self.t.queue(pkt[:4])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(bytes(self.dev.buf), pkt[:4])  # partial header retained
        self.assertEqual(self.dev.rx_seq, 0)
        self.t.queue(pkt[4:12])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])  # header done, payload short
        self.assertEqual(self.t.writes, [])
        self.t.queue(pkt[12:])
        out = self.dev.poll(timeout_ms=1)
        self.assertEqual(out, [(2, b"thermal-frame")])  # reassembled exactly once
        self.assertEqual(self.dev.rx_seq, 9)
        self.assertEqual(len(self.t.writes), 1)
        self.assertEqual(decode_host_frame(self.t.writes[0])["ack"], 9)

    def test_coalesced_packets_preserve_order_and_sessions(self):
        a = acc_packet(ACK, seq=1, ack=0, session=1, payload=b"AAA")
        b = acc_packet(ACK, seq=2, ack=0, session=4, payload=b"BBBB")
        self.t.queue(a + b)  # two whole frames in one read
        out = self.dev.poll(timeout_ms=1)
        self.assertEqual(out, [(1, b"AAA"), (4, b"BBBB")])
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [1, 2])
        self.assertEqual(self.dev.rx_seq, 2)

    def test_garbage_and_bad_header_checksum_resynchronize(self):
        fake_detect = b"\x00\xff\x55\x00"  # 0xFF55 that is not the detect marker
        corrupt = bytearray(
            acc_packet(ACK, seq=7, ack=0, session=1, payload=b"\x01\x02")
        )
        corrupt[8] ^= 0xFF  # break header checksum only
        good = acc_packet(ACK, seq=8, ack=0, session=1, payload=b"resync-me")
        self.t.queue(fake_detect + bytes(corrupt) + b"\xff\x01" + good)
        out = self.dev.poll(timeout_ms=1)
        self.assertEqual(out, [(1, b"resync-me")])
        self.assertEqual(len(self.t.writes), 1)
        self.assertEqual(decode_host_frame(self.t.writes[0])["ack"], 8)
        self.assertEqual(bytes(self.dev.buf), b"")
        self.assertEqual(self.dev.rx_seq, 8)

    def test_duplicate_sequence_acked_but_not_redelivered_with_wrap(self):
        p = lambda s, d: acc_packet(ACK, seq=s, ack=0, session=1, payload=d)
        # rx_seq starts at 0x00: 0xFF is new, 0x00 after it proves 8-bit wrap.
        self.t.queue(p(0xFF, b"first") + p(0xFF, b"first") + p(0x00, b"second") + p(0x00, b"second"))
        out = self.dev.poll(timeout_ms=1)
        self.assertEqual(out, [(1, b"first"), (1, b"second")])  # no redelivery
        self.assertEqual(self.dev.rx_seq, 0x00)
        self.assertEqual(
            [decode_host_frame(w)["ack"] for w in self.t.writes],
            [0xFF, 0xFF, 0x00, 0x00],
        )  # retransmits still get the required ACK frame

    def test_detect_marker_split_across_reads_then_midstream(self):
        self.t.queue(DETECT[:3])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])  # too short to identify
        self.assertEqual(bytes(self.dev.buf), DETECT[:3])
        self.assertEqual(self.t.writes, [])
        self.t.queue(DETECT[3:] + b"\x00" + DETECT + acc_packet(ACK, seq=1, ack=0, session=2, payload=b"post-detect"))
        out = self.dev.poll(timeout_ms=1)
        # completed detect answered, stray 0x00 then midstream detect answered,
        # and the data packet still delivered in order.
        self.assertEqual(out, [(2, b"post-detect")])
        self.assertEqual(self.t.writes[:2], [DETECT, DETECT])
        self.assertEqual(len(self.t.writes), 3)  # + link-layer ACK for the packet
        ack = decode_host_frame(self.t.writes[2])
        self.assertEqual((ack["control"], ack["ack"], ack["session"]), (ACK, 1, 0))
        self.assertEqual(bytes(self.dev.buf), b"")

    def test_syn_discovers_sessions_and_links_on_next_ack(self):
        lsp = lsp_with_sessions([(1, 0), (2, 2)])  # control sid 1, EA sid 2
        syn = acc_packet(SYN, seq=0x11, ack=0x2B, session=0, payload=lsp)
        self.t.queue(syn)
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(self.dev.lsp, lsp)
        self.assertEqual((self.dev.control_sid, self.dev.ea_sid), (1, 2))
        self.assertEqual(self.dev.rx_seq, 0x11)
        self.assertFalse(self.dev.linked)  # SYN alone must not mark the link up
        self.assertEqual(len(self.t.writes), 1)
        f = decode_host_frame(self.t.writes[0])
        self.assertEqual((f["control"], f["seq"], f["ack"], f["session"]), (SYN | ACK, 0x2B, 0x11, 0))
        self.assertEqual(f["payload"], lsp)  # link params accepted as-is
        self.assertTrue(f["hdr_ok"] and f["pay_ok"])
        # An ACK-only frame from the accessory completes the link handshake.
        self.t.queue(acc_packet(ACK, seq=0x12, ack=0x2B, session=0))
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertTrue(self.dev.linked)
        self.assertEqual(len(self.t.writes), 1)  # no extra ACK for the ACK frame

    def test_rst_raises_after_acking_preceding_data(self):
        good = acc_packet(ACK, seq=3, ack=0, session=1, payload=b"keep")
        rst = acc_packet(RST, seq=4, ack=0x2B, session=0)
        self.t.queue(good + rst)
        with self.assertRaises(RuntimeError):
            self.dev.poll(timeout_ms=1)
        self.assertEqual(len(self.t.writes), 1)  # data got its ACK before the reset
        self.assertEqual(decode_host_frame(self.t.writes[0])["ack"], 3)

    def test_usb_timeout_preserves_a_partially_received_packet(self):
        packet = acc_packet(ACK, seq=6, ack=0, session=2, payload=b"after-timeout")
        self.t.queue(packet[:11])
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        # No queued read: the transport raises the real USBTimeoutError.
        self.assertEqual(self.dev.poll(timeout_ms=1), [])
        self.assertEqual(self.t.writes, [])

        self.t.queue(packet[11:])
        self.assertEqual(self.dev.poll(timeout_ms=1), [(2, b"after-timeout")])
        self.assertEqual([decode_host_frame(w)["ack"] for w in self.t.writes], [6])


class SendEncodingTests(unittest.TestCase):
    def setUp(self):
        self.t = FakeTransport()
        self.dev = IAP2Device(self.t)

    def test_send_wraps_sequence_255_to_0_with_valid_checksums(self):
        self.dev.control_sid = 1
        self.dev.rx_seq = 0x44
        self.dev.tx_seq = 0xFE
        self.dev.send(1, b"auth-challenge")
        self.dev.send(2, b"frame\x01")
        self.assertEqual(self.dev.tx_seq, 0x00)
        self.assertEqual(len(self.t.writes), 2)
        f1, f2 = (decode_host_frame(w) for w in self.t.writes)
        self.assertEqual((f1["magic"], f1["control"], f1["session"]), (0xFF5A, ACK, 1))
        self.assertEqual((f1["seq"], f1["ack"]), (0xFF, 0x44))
        self.assertEqual(f1["payload"], b"auth-challenge")
        self.assertEqual((f2["control"], f2["session"], f2["seq"]), (ACK, 2, 0x00))
        self.assertEqual(f2["payload"], b"frame\x01")
        for f, w in ((f1, self.t.writes[0]), (f2, self.t.writes[1])):
            self.assertTrue(f["hdr_ok"], "header checksum must sum to zero mod 256")
            self.assertTrue(f["pay_ok"], "payload checksum byte must match")
            self.assertEqual(f["length"], len(w))


if __name__ == "__main__":
    unittest.main()
