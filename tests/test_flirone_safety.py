"""FlirOne routing / frames() behavior through the real driver code with fake transports.

The real ``FlirOne.__init__``, ``_handle``, ``_iap_loop`` and ``frames`` run unmodified; only the
USB device, the iAP2 link object, the clock and ``usb.util`` are replaced by in-memory fakes, so no
USB call (including the constructor's reset) ever reaches hardware.

Pinned behavior:

* Only packets on the negotiated iAP2 EA link session (``iap.ea_sid``) AND big-endian logical EA
  session 1 reach the config parser; other sessions never poison a valid partial message.
* An empty EA body is a no-op, a header-only config notice is not an event, a matching-link packet
  shorter than the 2-byte session prefix fails, and malformed config aborts instead of producing a
  lenient/raw fallback.
* frames() reads 128 KiB chunks, yields exactly ``thermal``/``jpeg``/``status``, never yields a
  frame from a corrupt header or bad JSON, and surfaces background iAP2/config failures before
  enabling the frame interface and before every yield.
* A failed constructor releases what it claimed through close().

Run from the repo root:  python3 -m unittest discover -s tests -v
"""

import collections
import contextlib
import os
import struct
import sys
import time as real_time
import unittest
import zlib
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import usb.core  # noqa: E402
import usb.util  # noqa: E402

import flirone_ios  # noqa: E402
from rbprotocol import MAX_CHUNK_BYTES  # noqa: E402

EA_SID = 3


# --- wire fixtures (from scratch) -------------------------------------------------------------


def sealed(fields, bad_crc=False):
    head = struct.pack("<%dI" % len(fields), *fields)
    return head + struct.pack("<I", (zlib.crc32(head) & 0xFFFFFFFF) ^ int(bad_crc))


def js(text):
    return text.encode() + b"\0"


def cfg(payload):
    return sealed((0x1CC, 1, len(payload))) + payload


def frame(thermal, visual, meta, bad_crc=False):
    return sealed((0xBEEF, 1, len(thermal) + len(visual) + len(meta), len(thermal), len(visual), len(meta)), bad_crc) + thermal + visual + meta


def ea(session, body):
    return struct.pack(">H", session) + body


THERMAL = bytes((i * 11 + 5) & 0xFF for i in range(10332))
JPEG = b"\xff\xd8visible\xff\xd9"
META = js('{"shutter":"open","t":31.25}')
GOOD_FRAME = frame(THERMAL, JPEG, META)


# --- fakes ------------------------------------------------------------------------------------


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Exhausted(Exception):
    pass


class FakeDev:
    def __init__(self):
        self.calls = []
        self.reads = []
        self.chunks = collections.deque()
        self.on_read = None

    def reset(self):
        self.calls.append(("reset",))

    def set_interface_altsetting(self, interface, alternate_setting):
        self.calls.append(("alt", interface, alternate_setting))

    def read(self, endpoint, size, timeout):
        self.reads.append((endpoint, size, timeout))
        if self.on_read:
            self.on_read()
        if not self.chunks:
            raise Exhausted("fake frame transport drained")
        item = self.chunks.popleft()
        if isinstance(item, BaseException):
            raise item
        return item


class FakeIap:
    def __init__(self, rig):
        self.rig = rig
        self.ea_sid = rig.ea_sid
        self.polls = collections.deque()  # lists of (link_session, payload) or an Exception

    def handshake(self):
        if self.rig.handshake_error:
            raise self.rig.handshake_error
        return {"fake": True}

    def start_ea(self, protocol_id, session_id):
        self.rig.dev.calls.append(("start_ea", protocol_id, session_id))

    def poll(self, timeout_ms):
        self.rig.clock.now += timeout_ms / 1000.0
        try:
            item = self.polls.popleft()
        except IndexError:
            real_time.sleep(0.001)
            return []
        if callable(item):
            return item()
        if isinstance(item, BaseException):
            raise item
        return item


class Rig:
    """Context manager: patches flirone_ios to run entirely against in-memory fakes."""

    def __init__(self, init_polls=(), ea_sid=EA_SID, handshake_error=None):
        self.ea_sid = ea_sid
        self.handshake_error = handshake_error
        self.clock = FakeClock()
        self.dev = FakeDev()
        self.iap = FakeIap(self)
        self.iap.polls.extend(init_polls)
        self.claimed = []
        self.disposed = 0
        self.cam = None
        self._stack = contextlib.ExitStack()

    def __enter__(self):
        s = self._stack
        s.enter_context(mock.patch.object(flirone_ios, "_find", lambda timeout=10.0: self.dev))
        s.enter_context(mock.patch.object(flirone_ios, "time", self.clock))
        s.enter_context(mock.patch.object(flirone_ios, "IAP2Device", lambda dev: self.iap))
        s.enter_context(mock.patch.object(usb.util, "claim_interface", lambda d, i: self.claimed.append(i)))
        s.enter_context(mock.patch.object(usb.util, "dispose_resources", lambda d: setattr(self, "disposed", self.disposed + 1)))
        return self

    def build(self):
        self.cam = flirone_ios.FlirOne()
        return self.cam

    def __exit__(self, *exc):
        if self.cam is not None:
            self.cam._stop = True
            if self.cam._iap_thread is not None:
                self.cam._iap_thread.join(2)
        self._stack.close()


def new_cam(rig):
    return rig.build()


def wait_for(pred, timeout=3.0):
    end = real_time.time() + timeout
    while real_time.time() < end:
        if pred():
            return True
        real_time.sleep(0.005)
    return pred()


# --- routing ----------------------------------------------------------------------------------


class ConfigRoutingTests(unittest.TestCase):
    def test_valid_config_event_in_init_pump_and_notice_is_not_an_event(self):
        polls = [[(EA_SID, ea(1, cfg(b"") + cfg(js('{"state":"ready"}'))))]]
        with Rig(init_polls=polls) as rig:
            cam = new_cam(rig)
            self.assertEqual(cam.events, [{"state": "ready"}])

    def test_wrong_sessions_do_not_poison_a_valid_partial_message(self):
        msg = cfg(js('{"n":42}'))
        with Rig() as rig:
            cam = new_cam(rig)
            cam._handle([(EA_SID, ea(1, msg[:11]))])
            cam._handle([(EA_SID + 1, ea(1, b"\xde\xad\xbe\xef" * 8))])  # other link session
            cam._handle([(EA_SID, ea(2, b"\xde\xad\xbe\xef" * 8))])  # other logical EA session
            cam._handle([(EA_SID, ea(0x0100, b"junk"))])  # BE: 0x0100 is not session 1
            cam._handle([(EA_SID + 1, b"")])  # wrong link session: even a truncated prefix is ignored
            cam._handle([(EA_SID, ea(1, b""))])  # empty body: valid, no-op
            self.assertEqual(cam.events, [])
            cam._handle([(EA_SID, ea(1, msg[11:]))])
            self.assertEqual(cam.events, [{"n": 42}])

    def test_no_negotiated_ea_link_session_ignores_everything(self):
        with Rig(ea_sid=None) as rig:
            cam = new_cam(rig)
            cam._handle([(0, ea(1, b"\xff" * 20)), (3, ea(1, b"\xff" * 20))])
            self.assertEqual(cam.events, [])

    def test_matching_link_with_truncated_prefix_discards_partial_config(self):
        with Rig() as rig:
            cam = new_cam(rig)
            packet = cfg(js('{"value":17}'))
            for payload in (b"", b"\x00"):
                cam._handle([(EA_SID, ea(1, packet[:10]))])
                with self.assertRaises(ValueError):
                    cam._handle([(EA_SID, payload)])
                cam._handle([(EA_SID, ea(1, packet))])
            self.assertEqual(cam.events, [{"value": 17}, {"value": 17}])

    def test_malformed_config_raises_instead_of_raw_fallback(self):
        good = cfg(js('{"ok":1}'))
        for name, bad in {
            "header crc": sealed((0x1CC, 1, 3), True) + js("{}"),
            "version": sealed((0x1CC, 9, 0)),
            "magic": b"\x01\x02\x03\x04" + b"\0" * 12,
            "json": cfg(b"not-json\0"),
            "nan": cfg(js('{"v":NaN}')),
        }.items():
            with self.subTest(name), Rig() as rig:
                cam = new_cam(rig)
                with self.assertRaises(ValueError):
                    cam._handle([(EA_SID, ea(1, good + bad))])
                self.assertEqual(cam.events, [{"ok": 1}])  # only the valid message before it
                self.assertNotIn("raw", repr(cam.events))
                cam._handle([(EA_SID, ea(1, good))])  # parser was cleared: next stream starts clean
                self.assertEqual(len(cam.events), 2)

    def test_malformed_config_during_init_aborts_and_releases_usb(self):
        bad = cfg(js("{oops}"))
        with Rig(init_polls=[[(EA_SID, ea(1, bad))]]) as rig:
            with self.assertRaises(ValueError):
                new_cam(rig)
            self.assertEqual(rig.disposed, 1)
            self.assertIn(("alt", 1, 0), rig.dev.calls)
            self.assertNotIn(("alt", 1, 1), rig.dev.calls)  # fileio never enabled after the failure


    def test_background_iap_failure_is_recorded_and_stops_the_loop(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.iap.polls.append([(EA_SID, ea(1, cfg(b"bad\0")))])
            self.assertTrue(wait_for(lambda: cam._iap_error is not None))
            self.assertIsInstance(cam._iap_error, ValueError)
            self.assertTrue(cam._stop)
            cam._iap_thread.join(2)
            self.assertFalse(cam._iap_thread.is_alive())

    def test_usb_error_raised_while_closing_is_not_reported_as_failure(self):
        with Rig() as rig:
            cam = new_cam(rig)

            def closing_poll():
                cam._stop = True  # close() ran while this poll was in flight
                raise usb.core.USBError("device went away")

            rig.iap.polls.append(closing_poll)
            cam._iap_thread.join(2)
            self.assertFalse(cam._iap_thread.is_alive())
            self.assertIsNone(cam._iap_error)


# --- frames() ---------------------------------------------------------------------------------


class FramesTests(unittest.TestCase):
    def test_good_stream_yields_exact_dicts_with_128kib_reads(self):
        stream = b"\x00junk" + GOOD_FRAME + frame(THERMAL, b"", js("{}"))
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.chunks.extend([stream[:40], usb.core.USBTimeoutError("t"), stream[40:]])
            gen = cam.frames()
            first, second = next(gen), next(gen)
            self.assertEqual(set(first), {"thermal", "jpeg", "status"})
            self.assertEqual(first["thermal"], THERMAL)
            self.assertEqual(first["jpeg"], JPEG)
            self.assertEqual(first["status"], {"shutter": "open", "t": 31.25})
            self.assertEqual((second["jpeg"], second["status"]), (b"", {}))
            self.assertIn(("alt", 2, 1), rig.dev.calls)
            self.assertTrue(all(0 < r[1] <= MAX_CHUNK_BYTES for r in rig.dev.reads))
            with self.assertRaises(Exhausted):  # transport exhaustion propagates untouched
                next(gen)

    def test_corrupt_header_crc_raises_and_never_yields_a_frame(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.chunks.append(frame(THERMAL, JPEG, META, bad_crc=True))
            gen = cam.frames()
            with self.assertRaises(ValueError):
                next(gen)

    def test_bad_status_json_raises_without_raw_fallback(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.chunks.append(frame(THERMAL, JPEG, b"{not json}\0"))
            with self.assertRaises(ValueError):
                next(cam.frames())

    def test_valid_frame_before_a_corrupt_one_is_delivered_then_error_raised(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.chunks.append(GOOD_FRAME + frame(THERMAL, JPEG, META, bad_crc=True) + GOOD_FRAME)
            gen = cam.frames()
            self.assertEqual(next(gen)["thermal"], THERMAL)
            with self.assertRaises(ValueError):
                next(gen)
            with self.assertRaises(StopIteration):  # generator is finished; no later frame leaks
                next(gen)

    def test_oversize_usb_read_is_rejected_by_the_cap(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.chunks.append(b"\0" * (131072 + 1))
            with self.assertRaises(ValueError):
                next(cam.frames())

    def test_background_error_blocks_frame_interface_and_reads(self):
        with Rig() as rig:
            cam = new_cam(rig)
            cam._iap_error = ValueError("link failed")
            rig.dev.chunks.append(GOOD_FRAME)
            with self.assertRaises(RuntimeError):
                next(cam.frames())
            self.assertNotIn(("alt", 2, 1), rig.dev.calls)
            self.assertEqual(rig.dev.reads, [])

    def test_background_error_arriving_mid_read_is_checked_before_yield(self):
        with Rig() as rig:
            cam = new_cam(rig)
            rig.dev.on_read = lambda: setattr(cam, "_iap_error", ValueError("late"))
            rig.dev.chunks.append(GOOD_FRAME)
            with self.assertRaises(RuntimeError) as cm:
                next(cam.frames())
            self.assertIsInstance(cm.exception.__cause__, ValueError)
            self.assertEqual(len(rig.dev.reads), 1)

    def test_timeouts_keep_checking_the_background_error(self):
        with Rig() as rig:
            cam = new_cam(rig)
            state = {"n": 0}

            def on_read():
                state["n"] += 1
                if state["n"] == 3:
                    cam._iap_error = ValueError("while idle")

            rig.dev.on_read = on_read
            rig.dev.chunks.extend([usb.core.USBTimeoutError("t")] * 5)
            with self.assertRaises(RuntimeError):
                next(cam.frames())
            self.assertEqual(state["n"], 3)


if __name__ == "__main__":
    unittest.main()
