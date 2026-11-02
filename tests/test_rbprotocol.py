"""Behavior tests for the strict RB (rosebud) protocol reader in rbprotocol.py.

Wire fixtures are encoded from scratch here (struct + zlib only), independent of rbprotocol's own
parsing code, and exercised through a fake byte transport (plain bytes fed in chunks).  Nothing
touches USB or the filesystem.

Pinned contract:

* CONFIG16 / FRAME28 / FILEIO24 header layouts, magics, version 1, header CRC over the bytes
  before the CRC field, caps (config 1 MiB, frame 4,000,000, fileio 128 KiB, input chunk 128 KiB).
* Fail closed: once a full candidate header is present, CRC/version/length/sum violations raise
  ValueError, clear the parser, and the corrupt packet's body is never scanned for embedded frames.
* Frame resync happens only between packets, retaining at most 3 bytes when no full magic is seen.
* Strict JSON: one object + exactly one NUL, strict UTF-8, no NaN/Infinity, no trailing data,
  depth <= 32, tokens <= 1024 (prechecked), generic errors with no payload text or chained cause.

Run from the repo root:  python3 -m unittest discover -s tests -v
"""

import os
import struct
import sys
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import rbprotocol  # noqa: E402
from rbprotocol import (  # noqa: E402
    CONFIG,
    FILEIO,
    FRAME,
    MAX_CHUNK_BYTES,
    RbReader,
    decode_json_object,
    parse_stream,
)

# ---------------------------------------------------------------------------
# Wire fixtures (independent of rbprotocol's parser)
# ---------------------------------------------------------------------------

CFG_MAGIC, FRAME_MAGIC, FIO_MAGIC = 0x1CC, 0xBEEF, 0x5510
CFG_MAX, FRAME_MAX, FIO_MAX = 1048576, 4000000, 131072
MIN_THERMAL = 9840


def crc(b):
    return zlib.crc32(b) & 0xFFFFFFFF


def sealed(fields, *, bad_crc=False):
    """Pack u32 fields and append the header CRC over them (optionally corrupted)."""
    head = struct.pack("<%dI" % len(fields), *fields)
    c = crc(head) ^ (1 if bad_crc else 0)
    return head + struct.pack("<I", c)


def js(text):
    return text.encode("utf-8") + b"\0"


def config_pkt(payload, *, version=1, magic=CFG_MAGIC, length=None, bad_crc=False):
    n = len(payload) if length is None else length
    return sealed((magic, version, n), bad_crc=bad_crc) + payload


def frame_pkt(thermal, visual, meta, *, version=1, total=None, tl=None, vl=None, ml=None, bad_crc=False):
    t = len(thermal) if tl is None else tl
    v = len(visual) if vl is None else vl
    m = len(meta) if ml is None else ml
    tot = len(thermal) + len(visual) + len(meta) if total is None else total
    return sealed((FRAME_MAGIC, version, tot, t, v, m), bad_crc=bad_crc) + thermal + visual + meta


def fileio_pkt(payload, *, stream=1, version=1, pcrc=None, length=None, magic=FIO_MAGIC, bad_crc=False):
    n = len(payload) if length is None else length
    c = crc(payload) if pcrc is None else pcrc
    return sealed((magic, version, n, stream, c), bad_crc=bad_crc) + payload


def thermal_block(n=10332, seed=3):
    return bytes((i * 7 + seed) & 0xFF for i in range(n))


GOOD_META = js('{"fpa":{"temp":31.5},"ok":true}')


def good_frame(seed=3, visual=b"\xff\xd8JPEGDATA\xff\xd9", n=10332, meta=GOOD_META):
    return frame_pkt(thermal_block(n, seed), visual, meta)


class Collect:
    def __init__(self, channel):
        self.packets = []
        self.reader = RbReader(channel, self.packets.append)


def feed_all(col, data, step=MAX_CHUNK_BYTES):
    for i in range(0, len(data), step):
        col.reader.feed(data[i : i + step])


# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------


class ConfigTests(unittest.TestCase):
    def test_valid_packet_delivered_even_byte_by_byte(self):
        col = Collect(CONFIG)
        stream = config_pkt(js('{"event":"ready","n":2}'))
        for i in range(len(stream)):
            self.assertEqual(col.packets, [])  # nothing early
            col.reader.feed(stream, i, 1)  # source offset, no slicing
        self.assertEqual([p.json for p in col.packets], [{"event": "ready", "n": 2}])
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_coalesced_packets_and_zero_length_notice_are_ordered(self):
        col = Collect(CONFIG)
        stream = config_pkt(js('{"a":1}')) + config_pkt(b"") + config_pkt(js('{"b":2}'))
        feed_all(col, stream)
        self.assertEqual([p.json for p in col.packets], [{"a": 1}, None, {"b": 2}])
        self.assertEqual(col.packets[1].payload_length, 0)  # header-only notice, not a JSON error

    def test_notice_alone_is_valid_and_leaves_no_state(self):
        col = Collect(CONFIG)
        col.reader.feed(config_pkt(b""))
        self.assertEqual(len(col.packets), 1)
        self.assertIsNone(col.packets[0].json)
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_bad_header_crc_raises_and_clears(self):
        col = Collect(CONFIG)
        with self.assertRaises(ValueError):
            col.reader.feed(config_pkt(js("{}"), bad_crc=True))
        self.assertEqual(col.packets, [])
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_crc_checked_before_version_and_sizes_are_trusted(self):
        # version 9 with a valid CRC and a bad CRC both fail, but a bad-CRC huge length must
        # fail on the CRC without ever waiting for ~4 GB of body.
        col = Collect(CONFIG)
        with self.assertRaises(ValueError):
            col.reader.feed(config_pkt(b"", version=9))
        huge = sealed((CFG_MAGIC, 1, 0xFFFFFFFF), bad_crc=True)
        with self.assertRaises(ValueError):
            col.reader.feed(huge)
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_bad_magic_raises_at_first_differing_byte(self):
        col = Collect(CONFIG)
        with self.assertRaises(ValueError):
            col.reader.feed(b"\xcc\x02")  # 0x1cc LE is cc 01
        self.assertEqual(col.reader.buffered_bytes, 0)
        with self.assertRaises(ValueError):
            col.reader.feed(config_pkt(js("{}"), magic=0x1CD))

    def test_length_over_cap_rejected_without_waiting(self):
        col = Collect(CONFIG)
        with self.assertRaises(ValueError):
            col.reader.feed(sealed((CFG_MAGIC, 1, CFG_MAX + 1)))
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_exact_cap_payload_accepted_in_max_chunks(self):
        body = b'{"k":"' + b"a" * (CFG_MAX - 9) + b'"}\0'
        self.assertEqual(len(body), CFG_MAX)
        col = Collect(CONFIG)
        stream = config_pkt(body)
        for i in range(0, len(stream), MAX_CHUNK_BYTES):
            col.reader.feed(stream, i, min(MAX_CHUNK_BYTES, len(stream) - i))
            self.assertLessEqual(col.reader.buffered_bytes, CONFIG.cap)
        self.assertEqual(len(col.packets[0].json["k"]), CFG_MAX - 9)

    def test_bad_json_payload_never_delivered_and_clears(self):
        col = Collect(CONFIG)
        good = config_pkt(js('{"a":1}'))
        with self.assertRaises(ValueError):
            col.reader.feed(good + config_pkt(b"not json\0") + good)
        self.assertEqual([p.json for p in col.packets], [{"a": 1}])  # only the first, before it
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_input_range_and_chunk_limits_checked_before_append(self):
        col = Collect(CONFIG)
        for args in (
            (b"x" * (MAX_CHUNK_BYTES + 1), 0, None),
            (b"abc", 4, 0),
            (b"abc", 1, 3),
            (b"abc", -1, 1),
            (b"abc", 0, -1),
        ):
            with self.assertRaises(ValueError):
                col.reader.feed(*args)
            self.assertEqual(col.reader.buffered_bytes, 0)

    def test_truncated_stream_awaits_more_and_offline_replay_flags_it(self):
        stream = config_pkt(js('{"a":1}'))
        col = Collect(CONFIG)
        col.reader.feed(stream[:-1])
        self.assertEqual(col.packets, [])
        self.assertEqual(col.reader.buffered_bytes, len(stream) - 1)
        col.reader.clear()
        self.assertEqual(col.reader.buffered_bytes, 0)
        with self.assertRaises(ValueError):
            parse_stream(CONFIG, stream[:-1])
        self.assertEqual(len(parse_stream(CONFIG, stream, chunk_size=3)), 1)


# ---------------------------------------------------------------------------
# FRAME
# ---------------------------------------------------------------------------


class FrameTests(unittest.TestCase):
    def test_sections_and_metadata_are_exact(self):
        col = Collect(FRAME)
        col.reader.feed(good_frame(seed=5))
        (p,) = col.packets
        self.assertEqual(p.thermal, thermal_block(10332, 5))
        self.assertEqual(p.visual, b"\xff\xd8JPEGDATA\xff\xd9")
        self.assertEqual(p.json, {"fpa": {"temp": 31.5}, "ok": True})
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_empty_jpeg_and_minimum_sizes_are_legal(self):
        col = Collect(FRAME)
        col.reader.feed(frame_pkt(thermal_block(MIN_THERMAL), b"", js("{}")))
        self.assertEqual(col.packets[0].visual, b"")
        self.assertEqual(col.packets[0].json, {})

    def test_fragmentation_and_coalescence_with_garbage_between_frames(self):
        a, b = good_frame(seed=1), good_frame(seed=2)
        stream = b"\x00\x01junk" + a + b"\xef\xbe\x00zz" + b + b"\xef\xbe"
        for step in (1, 7, 4096, len(stream)):
            col = Collect(FRAME)
            feed_all(col, stream, step)
            self.assertEqual([p.thermal for p in col.packets], [thermal_block(10332, 1), thermal_block(10332, 2)])
            self.assertEqual(col.reader.buffered_bytes, 2)  # only the possible-magic suffix

    def test_garbage_scan_is_bounded_and_resyncs(self):
        col = Collect(FRAME)
        for _ in range(5):
            col.reader.feed(b"\xaa" * MAX_CHUNK_BYTES)
            self.assertLessEqual(col.reader.buffered_bytes, 3)
        col.reader.feed(b"\xef\xbe\x00")  # three bytes of magic kept
        self.assertEqual(col.reader.buffered_bytes, 3)
        col.reader.feed(b"\x00" + good_frame()[4:])  # magic completed across feeds
        self.assertEqual(len(col.packets), 1)

    def test_bad_header_crc_does_not_scan_body_for_embedded_valid_frame(self):
        # Outer frame's visual section contains a complete, valid inner frame.
        outer = frame_pkt(thermal_block(MIN_THERMAL), good_frame(seed=9), js("{}"), bad_crc=True)
        for step in (5, len(outer)):
            with self.subTest(step=step):
                col = Collect(FRAME)
                with self.assertRaises(ValueError):
                    feed_all(col, outer, step)
                self.assertEqual(col.packets, [])
                self.assertEqual(col.reader.buffered_bytes, 0)

    def test_valid_embedded_frame_inside_corrupt_body_is_not_reinterpreted(self):
        # Header fine, but the declared metadata is not JSON: whole packet rejected; the inner valid
        # frame sitting in the visual section must not be delivered either.
        inner = good_frame(seed=9)
        outer = frame_pkt(thermal_block(MIN_THERMAL), inner, b"garbage!\0")
        col = Collect(FRAME)
        with self.assertRaises(ValueError):
            col.reader.feed(outer)
        self.assertEqual(col.packets, [])
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_header_violations_all_fail_closed(self):
        th, meta = thermal_block(MIN_THERMAL), js("{}")
        cases = {
            "version": frame_pkt(th, b"", meta, version=9),
            "thermal too small": frame_pkt(thermal_block(MIN_THERMAL - 1), b"", meta),
            "metadata too small": frame_pkt(th, b"", b"{}", ml=2, total=len(th) + 2),
            "sum mismatch": frame_pkt(th, b"v", meta, total=len(th) + 1 + len(meta) + 1),
            "total too small": frame_pkt(th, b"v", meta, total=len(th) + len(meta)),
        }
        for name, data in cases.items():
            with self.subTest(name):
                col = Collect(FRAME)
                with self.assertRaises(ValueError):
                    col.reader.feed(data)
                self.assertEqual(col.packets, [])
                self.assertEqual(col.reader.buffered_bytes, 0)

    def test_declared_lengths_use_unbounded_ints_not_u32_wraparound(self):
        # t + v + m wraps to exactly `total` in 32-bit arithmetic; it must still be rejected, with
        # no wait for (or allocation of) gigabytes.
        t, v, m = 0xFFFFFFF0, 0x20, 3
        total = (t + v + m) & 0xFFFFFFFF
        hdr = sealed((FRAME_MAGIC, 1, total, t, v, m))
        col = Collect(FRAME)
        with self.assertRaises(ValueError):
            col.reader.feed(hdr)
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_total_over_cap_rejected_but_cap_itself_waits(self):
        # Lengths are self-consistent (sum == total), so only the 4,000,000 cap can reject.
        col = Collect(FRAME)
        with self.assertRaises(ValueError):
            col.reader.feed(sealed((FRAME_MAGIC, 1, FRAME_MAX + 1, FRAME_MAX - 2, 0, 3)))
        self.assertEqual(col.reader.buffered_bytes, 0)
        ok = Collect(FRAME)
        ok.reader.feed(sealed((FRAME_MAGIC, 1, FRAME_MAX, FRAME_MAX - 3, 0, 3)))
        self.assertEqual(ok.packets, [])
        self.assertEqual(ok.reader.buffered_bytes, 28)  # valid header at the cap: wait for the body

    def test_bad_metadata_json_drops_only_that_frame_and_keeps_earlier_ones(self):
        col = Collect(FRAME)
        bad = frame_pkt(thermal_block(MIN_THERMAL), b"", js("{bad}"))
        with self.assertRaises(ValueError):
            col.reader.feed(good_frame(seed=1) + bad + good_frame(seed=2))
        self.assertEqual([p.thermal for p in col.packets], [thermal_block(10332, 1)])
        self.assertEqual(col.reader.buffered_bytes, 0)

    def test_truncated_body_waits_and_replay_reports_truncation(self):
        data = good_frame()
        col = Collect(FRAME)
        col.reader.feed(data[:-1])
        self.assertEqual(col.packets, [])
        col.reader.feed(data[-1:])
        self.assertEqual(len(col.packets), 1)
        with self.assertRaises(ValueError):
            parse_stream(FRAME, data[:-1])
        self.assertEqual(len(parse_stream(FRAME, b"junk" + data + data, chunk_size=1000)), 2)


# ---------------------------------------------------------------------------
# FILEIO
# ---------------------------------------------------------------------------


class FileIoTests(unittest.TestCase):
    def test_payload_eof_and_coalescence(self):
        col = Collect(FILEIO)
        raw = bytes(range(256)) * 3 + b"\0\0\0"  # embedded NULs are legal in raw payloads
        feed_all(col, fileio_pkt(raw, stream=7) + fileio_pkt(b"", stream=7, pcrc=0), 5)
        self.assertEqual([(p.stream_id, p.payload) for p in col.packets], [(7, raw), (7, b"")])

    def test_payload_crc_mismatch_and_eof_crc_must_be_zero(self):
        for data in (fileio_pkt(b"abc", pcrc=crc(b"abd")), fileio_pkt(b"", pcrc=1)):
            col = Collect(FILEIO)
            with self.assertRaises(ValueError):
                col.reader.feed(data)
            self.assertEqual(col.packets, [])
            self.assertEqual(col.reader.buffered_bytes, 0)

    def test_header_violations(self):
        cases = {
            "stream 0": fileio_pkt(b"x", stream=0),
            "version": fileio_pkt(b"x", version=2),
            "header crc": fileio_pkt(b"x", bad_crc=True),
            "magic": fileio_pkt(b"x", magic=0x5511),
            "over cap": sealed((FIO_MAGIC, 1, FIO_MAX + 1, 1, 0)),
        }
        for name, data in cases.items():
            with self.subTest(name):
                col = Collect(FILEIO)
                with self.assertRaises(ValueError):
                    col.reader.feed(data)
                self.assertEqual(col.packets, [])

    def test_exact_cap_payload(self):
        raw = b"\x5a" * FIO_MAX
        col = Collect(FILEIO)
        feed_all(col, fileio_pkt(raw))
        self.assertEqual(col.packets[0].payload, raw)


# ---------------------------------------------------------------------------
# JSON
# ---------------------------------------------------------------------------


class JsonTests(unittest.TestCase):
    def test_accepts_object_with_utf8_escapes_whitespace_and_nested_values(self):
        doc = decode_json_object(js('\n {"k": "h\u00e9llo \u20ac \\u00e9 \\" \\\\ {[", "a": [1, -2.5e3, null, {"x": []}]} \r\n'))
        self.assertEqual(doc["k"], 'h\u00e9llo \u20ac \u00e9 " \\ {[')
        self.assertEqual(doc["a"], [1, -2500.0, None, {"x": []}])

    def test_rejects_malformed_inputs(self):
        bad = [
            b"",
            b"\0",
            b"{}",  # no NUL
            b'{"a":1}',
            b'{"a":1}\0\0',  # two NULs
            b'{"a":\x001}\0',  # embedded NUL
            b"[1]\0",  # not an object
            b'"s"\0',
            b"1\0",
            b'{"a":1} x\0',  # trailing garbage
            b'{"a":1}{"b":2}\0',
            b"{'a':1}\0",
            b'{a:1}\0',
            b'{"a":1,}\0',
            b'{"a":[1,]}\0',
            b'{"a":NaN}\0',
            b'{"a":Infinity}\0',
            b'{"a":-Infinity}\0',
            b'{"a":1e999}\0',
            b'{"a":01}\0',
            b'{"a":"\x01"}\0',  # raw control char
            b'{"a":"\\x"}\0',  # unknown escape
            b'/*c*/{"a":1}\0',
            b'{"a":"unterminated}\0',
            b'{"a":1\0',
            b'{"a":"\xff"}\0',  # invalid UTF-8
            b'{"a":"\xc0\x80"}\0',  # overlong
            b'{"a":"\xed\xa0\x80"}\0',  # encoded surrogate
        ]
        for data in bad:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    decode_json_object(data)

    def test_depth_limit_counts_containers_not_braces_in_strings(self):
        def nested(n):
            return js('{"a":' * (n - 1) + "{}" + "}" * (n - 1))

        self.assertEqual(type(decode_json_object(nested(32))), dict)
        with self.assertRaises(ValueError):
            decode_json_object(nested(33))
        deep = js('{"s":"' + "{" * 500 + '","t":"' + "[" * 500 + '\\"["}')
        self.assertIn("s", decode_json_object(deep))

    def test_token_limit_checked_before_parsing(self):
        def tokens(extra):  # object = 1 token; each "k":0 pair = 2 tokens; extra is one more key
            pairs = ",".join('"k%d":0' % i for i in range(510))
            return js("{" + pairs + extra + "}")

        # 1 + 1020 + ("e" + "[" + literal) = exactly 1024 tokens
        self.assertEqual(len(decode_json_object(tokens(',"e":[1]'))), 511)
        calls = []
        real_loads = rbprotocol.json.loads
        rbprotocol.json.loads = lambda *a, **k: calls.append(1) or real_loads(*a, **k)
        try:
            with self.assertRaises(ValueError):
                decode_json_object(tokens(',"e":[1,2]'))  # 1025 tokens
            self.assertEqual(calls, [])  # rejected by the lexical precheck, before json.loads
            decode_json_object(js("{}"))
            self.assertEqual(calls, [1])
        finally:
            rbprotocol.json.loads = real_loads
        # Pathological unterminated-quote pile must fail fast (linear scan), not hang.
        with self.assertRaises(ValueError):
            decode_json_object(b'{"a":"' + b'\\"' * 500000 + b"\0")

    def test_errors_are_generic_with_no_payload_and_no_chained_cause(self):
        secret = "S3CR3T-TOKEN-0451"
        for data in (js('{"a":"%s" garbage}' % secret), js('{"a":"%s","b":NaN}' % secret), ('{"%s":' % secret).encode() + b"\0"):
            with self.assertRaises(ValueError) as cm:
                decode_json_object(data)
            exc = cm.exception
            self.assertNotIn(secret, str(exc))
            self.assertNotIn(secret, repr(exc.args))
            self.assertIsNone(exc.__cause__)
            self.assertIsNone(exc.__context__)

    def test_reader_errors_do_not_leak_payload_either(self):
        secret = "S3CR3T-FRAME-0451"
        col = Collect(FRAME)
        bad = frame_pkt(thermal_block(MIN_THERMAL), b"", js('{"%s":' % secret))
        with self.assertRaises(ValueError) as cm:
            col.reader.feed(bad)
        self.assertNotIn(secret, str(cm.exception))
        self.assertIsNone(cm.exception.__cause__)
        self.assertIsNone(cm.exception.__context__)



if __name__ == "__main__":
    unittest.main()
