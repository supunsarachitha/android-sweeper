"""The signatures.bin format (version 1). Big-endian, read by the app with DataInputStream.

    magic        8 bytes  b"SWSIG1\\0\\0"
    version      u32      1
    created_at   u64      Unix seconds (UTC) when the file was built
    count        u32      number of hash entries
    name_count   u32      number of family names
    names        name_count x (u16 length + UTF-8 bytes, length <= 200)
    entries      count x (32-byte SHA-256 + u16 name index), sorted ascending by hash bytes, no duplicates

signatures.bin.sig is an ECDSA P-256 / SHA-256 signature (DER) over the complete signatures.bin.
"""
import struct

MAGIC = b"SWSIG1\0\0"
VERSION = 1
MAX_NAME_BYTES = 200
UNKNOWN_NAME = "Unknown Android malware"


def encode(created_at, entries):
    """entries: dict {sha256_bytes(32): family_name}"""
    names = sorted(set(entries.values()))
    index = {n: i for i, n in enumerate(names)}
    out = bytearray(MAGIC)
    out += struct.pack(">IQII", VERSION, created_at, len(entries), len(names))
    for n in names:
        b = n.encode("utf-8")[:MAX_NAME_BYTES]
        out += struct.pack(">H", len(b)) + b
    for h in sorted(entries):
        if len(h) != 32:
            raise ValueError("bad hash length")
        out += h + struct.pack(">H", index[entries[h]])
    return bytes(out)


def decode(data):
    """Strict parser mirroring the app's. Returns (created_at, {hash: name})."""
    if data[:8] != MAGIC:
        raise ValueError("bad magic")
    version, created_at, count, name_count = struct.unpack_from(">IQII", data, 8)
    if version != VERSION:
        raise ValueError("unsupported version")
    pos = 8 + 20
    names = []
    for _ in range(name_count):
        (n,) = struct.unpack_from(">H", data, pos)
        if n > MAX_NAME_BYTES:
            raise ValueError("name too long")
        names.append(data[pos + 2:pos + 2 + n].decode("utf-8"))
        pos += 2 + n
    if len(data) != pos + count * 34:
        raise ValueError("length mismatch")
    entries = {}
    prev = None
    for i in range(count):
        h = data[pos:pos + 32]
        (idx,) = struct.unpack_from(">H", data, pos + 32)
        if idx >= name_count:
            raise ValueError("bad name index")
        if prev is not None and h <= prev:
            raise ValueError("entries not strictly sorted")
        entries[h] = names[idx]
        prev = h
        pos += 34
    return created_at, entries
