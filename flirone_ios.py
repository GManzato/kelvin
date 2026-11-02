"""FLIR ONE (iOS/Lightning variant, 09cb:1996) driver for macOS/Linux via libusb.

Bring-up (reverse-engineered from FLIR ThermalSDK.framework + iAP2):
  1. USB reset, claim IF0..2.
  2. Act as the Apple device on IF0 (iAP2): link sync, auth (challenge answered, never verified),
     identification -> camera advertises com.flir.rosebud.{config,fileio,frame}.
  3. Open only the 'config' EA session over iAP2 (StartExternalAccessoryProtocolSession).
  4. fileio / frame are *native transport*: select alt setting 1 on IF1 / IF2. No EA start message
     (sending one reboots the camera).
  5. Frames arrive on EP 0x85: 28-byte header (magic 0xBEEF) + thermal + JPEG + status JSON.

Config and frame bytes go through the strict, fail-closed parsers in rbprotocol.py (header CRC,
version, length caps, strict JSON). A malformed stream raises; it is never reinterpreted.
"""
import threading, time
import usb.core, usb.util
from iap2host import IAP2Device
from rbprotocol import CONFIG, FRAME, MAX_CHUNK_BYTES, RbReader

VID, PID = 0x09CB, 0x1996
CONFIG_SESSION = 1


def _find(timeout=10.0):
    t = time.time() + timeout
    while time.time() < t:
        d = usb.core.find(idVendor=VID, idProduct=PID)
        if d is not None:
            return d
        time.sleep(0.1)
    raise RuntimeError("FLIR ONE not found")


class FlirOne:
    def __init__(self):
        d = _find()
        d.reset()  # drop any stale iAP2 link
        time.sleep(1.5)
        self.d = d = _find()
        self.events = []  # config-channel JSON messages from the camera
        self._stop = False
        self._iap_error = None  # first background iAP2/config failure, surfaced by frames()
        self._iap_thread = None
        self._cfg = RbReader(CONFIG, self._on_config)
        try:
            for i in (0, 1, 2):
                usb.util.claim_interface(d, i)
            self.iap = IAP2Device(d)
            self.info = self.iap.handshake()
            self.iap.start_ea(0, CONFIG_SESSION)  # protocol id 0 = com.flir.rosebud.config
            self._pump(0.3)
            d.set_interface_altsetting(interface=1, alternate_setting=1)  # fileio (native)
            self._pump(0.5)
            self._iap_thread = threading.Thread(target=self._iap_loop, daemon=True)
            self._iap_thread.start()
        except BaseException:
            try:
                self.close()  # release whatever was claimed; never leave a failed object holding USB
            except Exception:
                pass
            raise

    def _pump(self, sec):
        t = time.time() + sec
        while time.time() < t:
            self._handle(self.iap.poll(20))

    def _iap_loop(self):
        while not self._stop:
            try:
                self._handle(self.iap.poll(50))
            except Exception as e:  # USB or protocol failure: stop and let frames() report it
                if not self._stop:
                    self._iap_error = e
                    self._stop = True
                return

    def _raise_if_failed(self):
        err = self._iap_error
        if err is not None:
            raise RuntimeError("FLIR ONE iAP2/config channel failed") from err

    def _on_config(self, packet):
        if packet.json is not None:  # header-only notices are not events
            self.events.append(packet.json)

    def _handle(self, packets):
        ea_sid = self.iap.ea_sid
        for link_session, p in packets:
            if ea_sid is None or link_session != ea_sid:
                continue  # not the negotiated external-accessory link session
            if len(p) < 2:
                self._cfg.clear()
                raise ValueError("truncated external accessory session prefix")
            if (p[0] << 8 | p[1]) != CONFIG_SESSION:
                continue  # other EA session (big-endian logical id): must not touch config state
            self._cfg.feed(p, 2)  # empty body is a no-op; malformed config raises

    def frames(self):
        """Yield dicts: thermal (bytes), jpeg (bytes), status (dict).

        Raises on a malformed frame stream or when the background iAP2/config channel failed.
        """
        self._raise_if_failed()
        ready = []
        reader = RbReader(FRAME, ready.append)
        self.d.set_interface_altsetting(interface=2, alternate_setting=1)  # frame (native)
        while True:
            self._raise_if_failed()
            try:
                chunk = self.d.read(0x85, MAX_CHUNK_BYTES, 1000)
            except usb.core.USBTimeoutError:
                continue
            error = None
            try:
                reader.feed(chunk)
            except ValueError as e:
                error = e
            batch = ready[:]
            ready.clear()
            for packet in batch:  # frames validated before any failure are still delivered
                self._raise_if_failed()
                yield dict(thermal=packet.thermal, jpeg=packet.visual, status=packet.json)
            if error is not None:
                raise error

    def close(self):
        self._stop = True
        for intf in (2, 1):
            try:
                self.d.set_interface_altsetting(interface=intf, alternate_setting=0)
            except usb.core.USBError:
                pass
        usb.util.dispose_resources(self.d)
