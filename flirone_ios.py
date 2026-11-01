"""FLIR ONE (iOS/Lightning variant, 09cb:1996) driver for macOS/Linux via libusb.

Bring-up (reverse-engineered from FLIR ThermalSDK.framework + iAP2):
  1. USB reset, claim IF0..2.
  2. Act as the Apple device on IF0 (iAP2): link sync, auth (challenge answered, never verified),
     identification -> camera advertises com.flir.rosebud.{config,fileio,frame}.
  3. Open only the 'config' EA session over iAP2 (StartExternalAccessoryProtocolSession).
  4. fileio / frame are *native transport*: select alt setting 1 on IF1 / IF2. No EA start message
     (sending one reboots the camera).
  5. Frames arrive on EP 0x85: 28-byte header (magic 0xBEEF) + thermal + JPEG + status JSON.
"""
import json, struct, threading, time
import usb.core, usb.util
from iap2host import IAP2Device

VID, PID = 0x09CB, 0x1996
FRAME_MAGIC = b"\xef\xbe\x00\x00"
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
        for i in (0, 1, 2):
            usb.util.claim_interface(d, i)
        self.iap = IAP2Device(d)
        self.info = self.iap.handshake()
        self.events = []  # config-channel JSON messages from the camera
        self._cfg_buf = bytearray()
        self._stop = False
        self.iap.start_ea(0, CONFIG_SESSION)  # protocol id 0 = com.flir.rosebud.config
        self._pump(0.3)
        d.set_interface_altsetting(interface=1, alternate_setting=1)  # fileio (native)
        self._pump(0.5)
        self._iap_thread = threading.Thread(target=self._iap_loop, daemon=True)
        self._iap_thread.start()

    def _pump(self, sec):
        t = time.time() + sec
        while time.time() < t:
            self._handle(self.iap.poll(20))

    def _iap_loop(self):
        while not self._stop:
            try:
                self._handle(self.iap.poll(50))
            except usb.core.USBError:
                return

    def _handle(self, packets):
        for _link_session, p in packets:
            if len(p) < 2:
                continue
            self._cfg_buf += p[2:]
            while len(self._cfg_buf) >= 16:
                magic, _proto, length, _crc = struct.unpack_from("<IIII", self._cfg_buf)
                if magic != 0x1CC:
                    self._cfg_buf.clear()
                    break
                if len(self._cfg_buf) < 16 + length:
                    break
                js = bytes(self._cfg_buf[16 : 16 + length]).rstrip(b"\0")
                del self._cfg_buf[: 16 + length]
                try:
                    self.events.append(json.loads(js))
                except ValueError:
                    pass

    def frames(self):
        """Yield dicts: thermal (bytes), jpeg (bytes), status (dict)."""
        self.d.set_interface_altsetting(interface=2, alternate_setting=1)  # frame (native)
        buf = bytearray()
        while True:
            try:
                buf += bytes(self.d.read(0x85, 512 * 1024, 1000))
            except usb.core.USBTimeoutError:
                continue
            while True:
                i = buf.find(FRAME_MAGIC)
                if i < 0:
                    del buf[:-3]
                    break
                del buf[:i]
                if len(buf) < 28:
                    break
                _m, _proto, total, t_len, j_len, s_len, _crc = struct.unpack_from("<7I", buf)
                if len(buf) < 28 + total:
                    break
                body = bytes(buf[28 : 28 + total])
                del buf[: 28 + total]
                status = body[t_len + j_len : t_len + j_len + s_len].rstrip(b"\0")
                try:
                    status = json.loads(status)
                except ValueError:
                    status = {"raw": status.decode(errors="replace")}
                yield dict(thermal=body[:t_len], jpeg=body[t_len : t_len + j_len], status=status)

    def close(self):
        self._stop = True
        for intf in (2, 1):
            try:
                self.d.set_interface_altsetting(interface=intf, alternate_setting=0)
            except usb.core.USBError:
                pass
        usb.util.dispose_resources(self.d)
