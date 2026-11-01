"""Minimal iAP2 *Apple-device side* over USB host mode, enough to bring up a FLIR ONE.

The FLIR ONE (iOS flavour) exposes IF0 'iAP Interface' (bulk 0x81/0x02) and expects the host
to behave like an iPhone: answer the iAP2 detect, sync the link, authenticate, identify,
then open External Accessory sessions. Without that it re-enumerates every ~5 s.
"""
import os, struct, time
import usb.core, usb.util

DETECT = b"\xff\x55\x02\x00\xee\x10"
SYN, ACK, EAK, RST = 0x80, 0x40, 0x20, 0x10
VERBOSE = os.environ.get("IAP2_DEBUG") == "1"


def csum(b):
    return (-sum(b)) & 0xFF


def tlv(pid, payload=b""):
    return struct.pack(">HH", len(payload) + 4, pid) + payload


def csm(msg_id, params=b""):
    return struct.pack(">HHH", 0x4040, len(params) + 6, msg_id) + params


def parse_params(b):
    out = []
    while len(b) >= 4:
        ln, pid = struct.unpack_from(">HH", b)
        out.append((pid, b[4:ln]))
        b = b[ln:]
    return out


class IAP2Device:
    def __init__(self, dev, ep_in=0x81, ep_out=0x02):
        self.d, self.ep_in, self.ep_out = dev, ep_in, ep_out
        self.buf = bytearray()
        self.tx_seq = 0x2B
        self.rx_seq = 0
        self.lsp = None
        self.control_sid = None
        self.ea_sid = None
        self.linked = False

    def log(self, *a):
        if VERBOSE:
            print(*a, flush=True)

    # ---- link layer -------------------------------------------------------
    def _write(self, control, payload=b"", session=0, seq=None):
        seq = self.tx_seq if seq is None else seq
        length = 9 + (len(payload) + 1 if payload else 0)
        hdr = struct.pack(">HHBBBB", 0xFF5A, length, control, seq, self.rx_seq, session)
        pkt = hdr + bytes([csum(hdr)])
        if payload:
            pkt += payload + bytes([csum(payload)])
        self.log(">", hex(control), seq, self.rx_seq, session, payload[:64].hex())
        self.d.write(self.ep_out, pkt, 1000)

    def send(self, session, payload):
        self.tx_seq = (self.tx_seq + 1) & 0xFF
        self._write(ACK, payload, session)

    def send_control(self, msg_id, params=b""):
        self.send(self.control_sid, csm(msg_id, params))

    def _pull(self, timeout_ms):
        try:
            self.buf += bytes(self.d.read(self.ep_in, 65536, timeout_ms))
        except usb.core.USBTimeoutError:
            pass

    def poll(self, timeout_ms=100):
        """Return list of (session_id, payload) data packets received."""
        self._pull(timeout_ms)
        out = []
        while True:
            if self.buf.startswith(DETECT):
                self.log("< detect")
                del self.buf[:6]
                self.d.write(self.ep_out, DETECT, 1000)
                continue
            i = self.buf.find(b"\xff\x5a")
            j = self.buf.find(b"\xff\x55")
            if i < 0:
                if j >= 0 and len(self.buf) - j >= 6:
                    del self.buf[: j + 1]
                    continue
                return out
            if 0 <= j < i:
                del self.buf[:j]
                if len(self.buf) < 6:
                    return out
                if not self.buf.startswith(DETECT):
                    del self.buf[:1]
                continue
            del self.buf[:i]
            if len(self.buf) < 9:
                return out
            length, control, seq, ack, session = struct.unpack_from(">HBBBB", self.buf, 2)
            if sum(self.buf[:9]) & 0xFF:
                del self.buf[:1]
                continue
            if len(self.buf) < length:
                return out
            payload = bytes(self.buf[9 : length - 1]) if length > 9 else b""
            del self.buf[:length]
            self.log("<", hex(control), seq, ack, session, payload[:64].hex())
            if control & RST:
                raise RuntimeError("accessory sent RST")
            if control & SYN:
                self._on_syn(payload, seq)
                continue
            if payload and not (control & ~ACK):
                if seq == self.rx_seq:  # retransmit of something we already have
                    self._write(ACK, seq=self.tx_seq)
                    continue
                self.rx_seq = seq
                self._write(ACK, seq=self.tx_seq)
                out.append((session, payload))
            elif control & ACK and not self.linked and self.lsp:
                self.linked = True

    def _on_syn(self, lsp, seq):
        self.lsp = lsp
        self.rx_seq = seq
        # sessions: triples (id, type, version); type 0 = control, 2 = EA
        for k in range(10, len(lsp), 3):
            sid, typ = lsp[k], lsp[k + 1]
            if typ == 0:
                self.control_sid = sid
            elif typ == 2:
                self.ea_sid = sid
        self.log("LSP", lsp.hex(), "control", self.control_sid, "ea", self.ea_sid)
        self._write(SYN | ACK, lsp)  # accept the accessory's link parameters as-is

    def wait_for(self, pred, timeout=5.0):
        t = time.time() + timeout
        while time.time() < t:
            for sess, payload in self.poll():
                r = pred(sess, payload)
                if r is not None:
                    return r
        raise TimeoutError("iAP2: timed out")

    def wait_csm(self, *msg_ids, timeout=5.0, on_other=None):
        def pred(sess, p):
            if sess == self.control_sid and p[:2] == b"\x40\x40":
                mid = struct.unpack_from(">H", p, 4)[0]
                if mid in msg_ids:
                    return mid, parse_params(p[6:])
                self.log("unhandled csm", hex(mid))
            elif on_other:
                on_other(sess, p)
            return None

        return self.wait_for(pred, timeout)

    # ---- bring-up -----------------------------------------------------------
    def handshake(self):
        t = time.time() + 6
        while not self.linked and time.time() < t:
            self.poll()
        if not self.linked:
            raise TimeoutError("iAP2 link sync failed")
        self.send_control(0xAA00)  # RequestAuthenticationCertificate
        _, params = self.wait_csm(0xAA01)
        self.certificate = dict(params).get(0, b"")
        self.send_control(0xAA02, tlv(0, os.urandom(32)))  # challenge
        self.wait_csm(0xAA03)
        self.send_control(0xAA05)  # AuthenticationSucceeded
        self.send_control(0x1D00)  # StartIdentification
        _, params = self.wait_csm(0x1D01)
        self.ident = params
        self.send_control(0x1D02)  # IdentificationAccepted
        return self.describe()

    def describe(self):
        names = {0: "name", 1: "model", 2: "manufacturer", 3: "serial", 4: "firmware", 5: "hardware"}
        info = {names[p]: v.rstrip(b"\0").decode(errors="replace") for p, v in self.ident if p in names}
        protos = []
        for p, v in self.ident:
            if p == 10:
                d = dict(parse_params(v))
                protos.append(
                    dict(
                        id=d[0][0],
                        name=d[1].rstrip(b"\0").decode(),
                        native=struct.unpack(">H", d[3])[0] if 3 in d else None,
                    )
                )
        info["protocols"] = protos
        return info

    def start_ea(self, protocol_id, session_id):
        self.send_control(0xEA00, tlv(0, bytes([protocol_id])) + tlv(1, struct.pack(">H", session_id)))

    def send_ea(self, session_id, data):
        self.send(self.ea_sid, struct.pack(">H", session_id) + data)
