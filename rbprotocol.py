"""Strict, pure-Python reader for the FLIR ONE "rosebud" (RB) transport framing.

Three channels share one reader:

  CONFIG  16-byte header  <magic 0x1cc,  version 1, payload_len, header_crc>
          payload: one JSON object + NUL.  payload_len == 0 is a header-only protocol notice
          (delivered with json=None; it is not a JSON error and not an event).
  FRAME   28-byte header  <magic 0xbeef, version 1, total, thermal_len, visual_len, metadata_len,
          header_crc>; body = thermal | visual (JPEG, may be empty) | metadata JSON + NUL.
          There is no image payload checksum in the protocol and none is invented here.
  FILEIO  24-byte header  <magic 0x5510, version 1, payload_len, stream_id >= 1, payload_crc,
          header_crc>; raw binary payload covered by payload_crc (also for the len 0 / crc 0 EOF).

All integers are little-endian u32; CRCs are zlib.crc32 (the standard CRC-32); every header CRC
covers the header bytes before the CRC field and is verified before any size/version is trusted.

Failure policy (fail closed): once a complete candidate header is present, any CRC, version,
length, or sum violation raises RbProtocolError (a ValueError), clears all parser state, and the
corrupt packet's body is NEVER scanned for embedded frames.  CONFIG/FILEIO have strict magic (a
mismatch raises as soon as the differing byte arrives).  FRAME alone scans, linearly, for the next
magic *between* packets and retains at most 3 suffix bytes when no full magic is present.
Packets are delivered only after every validation (including JSON) has passed.

Exceptions are generic: they never carry payload bytes, JSON text, or a chained parser exception.

Public offline API (no transport, no file reads): ``RbReader``, ``parse_stream``,
``decode_json_object``, ``CONFIG``, ``FRAME``, ``FILEIO``.
"""

import json
import re
import struct
import zlib
from dataclasses import dataclass
from typing import Callable, Optional

VERSION = 1
MAX_CHUNK_BYTES = 131072
MAX_JSON_BYTES = 1048576  # JSON text + its NUL
MAX_JSON_DEPTH = 32
MAX_JSON_TOKENS = 1024
MIN_THERMAL_BYTES = 9840
MIN_METADATA_BYTES = 3  # "{}" + NUL


class RbProtocolError(ValueError):
    """Controlled, payload-free protocol violation."""


@dataclass(frozen=True)
class ChannelSpec:
    name: str
    magic: int
    header_size: int
    max_payload: int
    fmt: str
    scan: bool  # True: search for the next magic between packets (FRAME only)

    @property
    def magic_bytes(self) -> bytes:
        return struct.pack("<I", self.magic)

    @property
    def cap(self) -> int:
        """Largest legal buffer: one maximal packet plus one maximal input chunk."""
        return self.max_payload + self.header_size + MAX_CHUNK_BYTES


CONFIG = ChannelSpec("config", 0x1CC, 16, 1048576, "<4I", False)
FRAME = ChannelSpec("frame", 0xBEEF, 28, 4000000, "<7I", True)
FILEIO = ChannelSpec("fileio", 0x5510, 24, 131072, "<6I", False)


@dataclass(frozen=True)
class RbPacket:
    """One fully validated packet.  Immutable; holds its own bytes."""

    channel: ChannelSpec
    payload_length: int
    thermal: bytes = b""  # FRAME
    visual: bytes = b""  # FRAME (JPEG); may be empty
    metadata_length: int = 0  # FRAME
    json: Optional[dict] = None  # CONFIG payload / FRAME metadata; None for a CONFIG notice
    stream_id: int = 0  # FILEIO
    payload: bytes = b""  # FILEIO raw payload

    @property
    def thermal_length(self) -> int:
        return len(self.thermal)

    @property
    def visual_length(self) -> int:
        return len(self.visual)


# ---------------------------------------------------------------------------------------------
# JSON
# ---------------------------------------------------------------------------------------------

# One alternative per lexical class; a lone quote means an unterminated string.  The string
# alternative is unambiguous (no nested quantifier ambiguity) and the first unterminated quote
# aborts the scan, so scanning is linear.  Commas, colons and whitespace are skipped by finditer.
_TOKEN_RE = re.compile(
    r'(?P<s>"[^"\\]*(?:\\.[^"\\]*)*")'
    r"|(?P<o>[{\[])"
    r"|(?P<c>[}\]])"
    r'|(?P<l>[^\s"{}\[\],:]+)'
    r'|(?P<bad>")',
    re.S,
)


def _reject_constant(_name):
    raise ValueError("non-finite constant")


def _parse_float(text):
    value = float(text)
    if value != value or value in (float("inf"), float("-inf")):
        raise ValueError("non-finite number")
    return value


def _precheck(text):
    depth = tokens = 0
    for m in _TOKEN_RE.finditer(text):
        kind = m.lastgroup
        if kind == "o":
            depth += 1
            tokens += 1
            if depth > MAX_JSON_DEPTH:
                raise ValueError("depth")
        elif kind == "c":
            depth -= 1
            if depth < 0:
                raise ValueError("unbalanced")
        elif kind == "bad":
            raise ValueError("unterminated string")
        else:
            tokens += 1
        if tokens > MAX_JSON_TOKENS:
            raise ValueError("tokens")


def _decode(data):
    n = len(data)
    if n < MIN_METADATA_BYTES or n > MAX_JSON_BYTES:
        raise ValueError("size")
    data = bytes(data)
    if data[-1] != 0 or data.find(b"\0") != n - 1:
        raise ValueError("NUL")
    text = str(memoryview(data)[: n - 1], "utf-8")  # strict: raises on malformed UTF-8
    _precheck(text)
    value = json.loads(text, parse_constant=_reject_constant, parse_float=_parse_float)
    if not isinstance(value, dict):
        raise ValueError("not an object")
    return value


def decode_json_object(data) -> dict:
    """Decode exactly one JSON object followed by exactly one trailing NUL.

    Rejects missing/embedded NUL, invalid UTF-8, non-objects, trailing garbage, bad syntax,
    NaN/Infinity, nesting deeper than 32 and more than 1024 tokens (checked before json.loads).
    The raised RbProtocolError is generic and has no cause/context.
    """
    value = None
    try:
        value = _decode(data)
    except (ValueError, TypeError, RecursionError, OverflowError):
        value = None
    if value is None:
        raise RbProtocolError("invalid RB JSON object")
    return value


# ---------------------------------------------------------------------------------------------
# Reader
# ---------------------------------------------------------------------------------------------


class RbReader:
    """Incremental, bounded, fail-closed parser for one RB channel.

    ``on_packet(RbPacket)`` is called only for fully validated packets.  After any
    RbProtocolError the reader is already cleared; the owner decides what to do next (the
    drivers treat it as fatal for the stream).
    """

    def __init__(self, channel: ChannelSpec, on_packet: Callable[[RbPacket], None]):
        self.channel = channel
        self._on_packet = on_packet
        self._magic = channel.magic_bytes
        self._struct = struct.Struct(channel.fmt)
        self._buf = bytearray()
        self._pos = 0
        self._hdr = None  # (fields, payload_len) of the validated header being completed

    @property
    def buffered_bytes(self) -> int:
        return len(self._buf) - self._pos

    def clear(self):
        """Drop all partial data (pause boundary).  Replaces the buffer, so never BufferError."""
        self._buf = bytearray()
        self._pos = 0
        self._hdr = None

    def feed(self, data, offset=0, length=None):
        """Append ``data[offset:offset+length]`` (no slicing by the caller) and deliver packets."""
        try:
            self._feed(data, offset, length)
        except BaseException:
            self.clear()
            raise

    def _feed(self, data, offset, length):
        with memoryview(data) as src:
            if src.ndim != 1 or src.itemsize != 1:
                raise RbProtocolError("RB input must be a flat byte buffer")
            if length is None:
                length = len(src) - offset
            if (
                not isinstance(offset, int)
                or not isinstance(length, int)
                or offset < 0
                or length < 0
                or offset + length > len(src)
                or length > MAX_CHUNK_BYTES
            ):
                raise RbProtocolError("RB input range invalid")
            if (len(self._buf) - self._pos) + length > self.channel.cap:
                raise RbProtocolError("RB buffer cap exceeded")
            buf = self._buf
            buf += src[offset : offset + length]
        with memoryview(buf) as mv:
            self._drain(buf, mv)
        if self._pos:
            del buf[: self._pos]  # compact once per feed; all views are released here
            self._pos = 0

    def _drain(self, buf, mv):
        ch = self.channel
        hs = ch.header_size
        magic = self._magic
        end = len(mv)
        while True:
            pos = self._pos
            if self._hdr is None:
                if ch.scan:
                    i = buf.find(magic, pos)
                    if i < 0:
                        avail = end - pos
                        keep = 0
                        for k in (3, 2, 1):
                            if k <= avail and buf.endswith(magic[:k]):
                                keep = k
                                break
                        self._pos = end - keep
                        return
                    pos = self._pos = i
                else:
                    k = min(end - pos, 4)
                    if k and buf[pos : pos + k] != magic[:k]:
                        raise RbProtocolError("RB bad magic")
                if end - pos < hs:
                    return
                self._hdr = self._check_header(mv, pos)
            fields, payload_len = self._hdr
            total = hs + payload_len
            if end - pos < total:
                return
            self._pos = pos + total
            self._hdr = None
            self._on_packet(self._build(mv, pos + hs, fields, payload_len))

    def _check_header(self, mv, pos):
        ch = self.channel
        fields = self._struct.unpack_from(mv, pos)
        if fields[0] != ch.magic:
            raise RbProtocolError("RB bad magic")
        if zlib.crc32(mv[pos : pos + ch.header_size - 4]) != fields[-1]:
            raise RbProtocolError("RB header CRC mismatch")
        if fields[1] != VERSION:
            raise RbProtocolError("RB unsupported version")
        if ch is FRAME:
            total, thermal, visual, meta = fields[2:6]
            if (
                total > ch.max_payload
                or thermal < MIN_THERMAL_BYTES
                or meta < MIN_METADATA_BYTES
                or meta > MAX_JSON_BYTES
                or thermal + visual + meta != total  # Python ints: no 32-bit wraparound
            ):
                raise RbProtocolError("RB frame lengths invalid")
            return fields, total
        length = fields[2]
        if length > ch.max_payload:
            raise RbProtocolError("RB payload too large")
        if ch is FILEIO and fields[3] < 1:
            raise RbProtocolError("RB bad stream id")
        return fields, length

    def _build(self, mv, start, fields, payload_len):
        ch = self.channel
        end = start + payload_len
        if ch is CONFIG:
            doc = decode_json_object(mv[start:end]) if payload_len else None
            return RbPacket(ch, payload_len, json=doc)
        if ch is FRAME:
            thermal_len, visual_len, meta_len = fields[3:6]
            v0 = start + thermal_len
            m0 = v0 + visual_len
            doc = decode_json_object(mv[m0:end])  # validate before copying image sections
            return RbPacket(
                ch,
                payload_len,
                thermal=bytes(mv[start:v0]),
                visual=bytes(mv[v0:m0]),
                metadata_length=meta_len,
                json=doc,
            )
        if zlib.crc32(mv[start:end]) != fields[4]:
            raise RbProtocolError("RB payload CRC mismatch")
        return RbPacket(ch, payload_len, stream_id=fields[3], payload=bytes(mv[start:end]))


def parse_stream(channel: ChannelSpec, data, chunk_size=MAX_CHUNK_BYTES):
    """Offline validation/replay of a complete recorded byte stream.

    Feeds ``data`` (bytes-like, already in memory; nothing is read from disk) in chunks of at
    most ``chunk_size`` through a fresh RbReader and returns the list of RbPackets.  Raises
    RbProtocolError on any violation or if the stream ends inside a packet/potential magic prefix.
    Non-prefix FRAME garbage is discarded using the normal between-packet search policy.
    """
    if not isinstance(chunk_size, int) or not 0 < chunk_size <= MAX_CHUNK_BYTES:
        raise RbProtocolError("RB chunk size invalid")
    out = []
    reader = RbReader(channel, out.append)
    with memoryview(data) as mv:
        for off in range(0, len(mv), chunk_size):
            reader.feed(mv, off, min(chunk_size, len(mv) - off))
    if reader.buffered_bytes:
        raise RbProtocolError("RB stream truncated")
    return out
