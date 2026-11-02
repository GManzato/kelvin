"""Minimal iAP2 *Apple-device side* over USB host mode, enough to bring up a FLIR ONE.

The FLIR ONE (iOS flavour) exposes IF0 'iAP Interface' (bulk 0x81/0x02) and expects the host
to behave like an iPhone: answer the iAP2 detect, sync the link, authenticate, identify,
then open External Accessory sessions. Without that it re-enumerates every ~5 s.
"""
import os, struct, time
import usb.core, usb.util

DETECT = b"\xff\x55\x02\x00\xee\x10"
SYN, ACK, EAK, RST = 0x80, 0x40, 0x20, 0x10
MAX_PACKET = 0xFFFF  # largest legal frame a u16 length field may declare
MAX_READ = 0x10000  # a single USB read never asks for more
MAX_BUFFER = MAX_PACKET + MAX_READ  # receive cap: max partial frame + one read
VERBOSE = os.environ.get("IAP2_DEBUG") == "1"


def csum(b):
    return (-sum(b)) & 0xFF


def tlv(pid, payload=b""):
    return struct.pack(">HH", len(payload) + 4, pid) + payload


def csm(msg_id, params=b""):
    return struct.pack(">HHH", 0x4040, len(params) + 6, msg_id) + params


def parse_params(b):
    """Parse a TLV block (u16 total length incl. header, u16 id, value).

    Strict and all-or-nothing: raises ValueError on a declared length < 4,
    one running past the block's end, or a 1..3-byte trailing fragment,
    instead of returning partial results.
    """
    out = []
    off = 0
    n = len(b)
    while off < n:
        if n - off < 4:
            raise ValueError(f"iAP2 TLV: {n - off}-byte trailing fragment at offset {off}")
        ln, pid = struct.unpack_from(">HH", b, off)
        if ln < 4:
            raise ValueError(f"iAP2 TLV: declared length {ln} < 4 at offset {off}")
        if off + ln > n:
            raise ValueError(f"iAP2 TLV: length {ln} exceeds {n - off} bytes at offset {off}")
        out.append((pid, bytes(b[off + 4 : off + ln])))
        off += ln
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
        room = MAX_BUFFER - len(self.buf)
        if room <= 0:
            return
        size = min(MAX_READ, room)
        try:
            chunk = self.d.read(self.ep_in, size, timeout_ms)
            if len(chunk) > size:
                raise ValueError("iAP2 input exceeds requested read length")
            self.buf.extend(chunk)
        except usb.core.USBTimeoutError:
            pass

    def poll(self, timeout_ms=100):
        """Return complete, checksum-validated (session_id, payload) packets."""
        self._pull(timeout_ms)
        out = []
        buf = self.buf
        n = len(buf)
        c = 0
        try:
            while c < n:
                p = buf.find(b"\xff", c)
                if p < 0:
                    c = n
                    break
                if p + 1 == n:
                    c = p
                    break
                nxt = buf[p + 1]
                if nxt == 0x55:
                    prefix_length = min(n - p, len(DETECT))
                    if buf[p:p + prefix_length] != DETECT[:prefix_length]:
                        c = p + 1
                        continue
                    if prefix_length < len(DETECT):
                        c = p
                        break
                    c = p + len(DETECT)
                    self.log("< detect")
                    self.d.write(self.ep_out, DETECT, 1000)
                    continue
                if nxt != 0x5A:
                    c = p + 1
                    continue
                if n - p < 9:
                    c = p
                    break
                length, control, seq, ack, session = struct.unpack_from(">HBBBB", buf, p + 2)
                if length < 9 or length == 10 or sum(buf[p:p + 9]) & 0xFF:
                    self.log("< bad header/length:", hex(control), length)
                    c = p + 1
                    continue
                if n - p < length:
                    c = p
                    break
                c = p + length
                if length > 9 and sum(buf[p + 9:c]) & 0xFF:
                    self.log("< bad payload checksum:", hex(control), "seq", seq, "len", length)
                    continue  # Discard exactly this frame; don't scan its corrupt payload.
                self.log("<", hex(control), seq, ack, session, "length", length)
                if control & RST:
                    raise RuntimeError("accessory sent RST")
                if control & SYN:
                    payload = bytes(buf[p + 9:c - 1]) if length > 9 else b""
                    self._on_syn(payload, seq)
                    continue
                if length > 9 and not (control & ~ACK):
                    if seq == self.rx_seq:
                        self._write(ACK, seq=self.tx_seq)
                        continue
                    payload = bytes(buf[p + 9:c - 1])
                    self.rx_seq = seq
                    self._write(ACK, seq=self.tx_seq)
                    out.append((session, payload))
                elif length == 9 and control == ACK and not self.linked and self.lsp is not None:
                    self.linked = True
        finally:
            del buf[:c]  # Compact once, even if a validated packet triggers an error.
        return out

    def _on_syn(self, lsp, seq):
        # Link parameters: >=10 reserved bytes, then whole (id, type, version)
        # triples. Reject malformed LSPs *before* touching any device state.
        if len(lsp) < 10 or (len(lsp) - 10) % 3:
            raise ValueError(f"iAP2 SYN: malformed link parameters ({len(lsp)} bytes)")
        control_sid = ea_sid = None
        # sessions: triples (id, type, version); type 0 = control, 2 = EA
        for k in range(10, len(lsp), 3):
            sid, typ = lsp[k], lsp[k + 1]
            if typ == 0:
                control_sid = sid
            elif typ == 2:
                ea_sid = sid
        self.lsp = lsp
        self.linked = False
        self.rx_seq = seq
        self.control_sid, self.ea_sid = control_sid, ea_sid
        self.log("LSP", "control", self.control_sid, "ea", self.ea_sid)
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
                if len(p) < 6:
                    raise ValueError(f"iAP2 CSM: truncated header ({len(p)} bytes)")
                if struct.unpack_from(">H", p, 2)[0] != len(p):
                    raise ValueError("iAP2 CSM: declared length != payload size")
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
        if self.control_sid is None:
            raise ValueError("Accessory has no iAP2 control session")
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
                # Validate required nested fields before indexing so a short/
                # missing field raises ValueError, never IndexError/KeyError.
                if len(d.get(0, b"")) != 1:
                    raise ValueError("iAP2 protocol TLV: id field must contain one byte")
                if 1 not in d:
                    raise ValueError("iAP2 protocol TLV: name field missing")
                if 3 in d and len(d[3]) != 2:
                    raise ValueError(f"iAP2 protocol TLV: native field is {len(d[3])} bytes, want 2")
                protos.append(
                    dict(
                        id=d[0][0],
                        name=d[1].rstrip(b"\0").decode(errors="replace"),
                        native=struct.unpack(">H", d[3])[0] if 3 in d else None,
                    )
                )
        info["protocols"] = protos
        return info

    def start_ea(self, protocol_id, session_id):
        if self.ea_sid is None:
            raise ValueError("Accessory has no external accessory link session")
        self.send_control(0xEA00, tlv(0, bytes([protocol_id])) + tlv(1, struct.pack(">H", session_id)))

    def send_ea(self, session_id, data):
        if self.ea_sid is None:
            raise ValueError("Accessory has no external accessory link session")
        self.send(self.ea_sid, struct.pack(">H", session_id) + data)
