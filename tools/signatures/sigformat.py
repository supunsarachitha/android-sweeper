"""The signatures.bin format (version 1). Big-endian, read by the app with DataInputStream.

    magic        8 bytes  b"SWSIG1\\0\\0"
    version      u32      1
    created_at   u64      Unix seconds (UTC) when the file was built
    count        u32      number of hash entries
    name_count   u32      number of family names
    names        name_count x (u16 length + UTF-8 bytes, length <= 200)
    entries      count x (32-byte SHA-256 + u16 name index), sorted ascending by hash bytes, no duplicates

signatures.bin.sig is an ECDSA P-256 / SHA-256 signature (DER) over the complete signatures.bin.

The deep-scan list (every file type, about 40 MB) is published as a base list plus small update files, so a
phone downloads the big file about once a month and a few KB on the other days:

    signatures-all-base.bin       exactly the format above: a full snapshot of the list
    signatures-all-delta-NN.bin   NN = 01..40, update number NN for the current base

Update file (version 1, big-endian):

    magic           8 bytes  b"SWDLT1\\0\\0"
    version         u32      1
    base_created_at u64      created_at of the base list this update belongs to
    seq             u32      1-based update number; the file name carries it as two digits
    created_at      u64      Unix seconds (UTC) when the update was built; later than the previous update
    count           u32      number of entries, at least 1 (an update without entries is never published)
    name_count      u32      number of family names
    names           name_count x (u16 length + UTF-8 bytes, length <= 200)
    entries         count x (32-byte SHA-256 + u16 name index), ascending by hash bytes, no duplicates

An entry is either a new hash or a hash whose family name changed; when a hash is in several files the
newest value wins (the base first, then the updates in order). The length of the file must match exactly.
Every file has a detached signature next to it (.sig, ECDSA P-256 / SHA-256, DER, same key).
"""
import struct

MAGIC = b"SWSIG1\0\0"
VERSION = 1
MAX_NAME_BYTES = 200
DELTA_MAGIC = b"SWDLT1\0\0"
DELTA_VERSION = 1
MAX_DELTAS = 40
MAX_DELTA_BYTES = 8 * 1024 * 1024  # the app refuses bigger update files
MAX_ENTRIES = 10_000_000
MAX_NAMES = 65_535
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


def _name_bytes(name):
    """UTF-8 of a family name, cut to MAX_NAME_BYTES on a character boundary (never a broken character)."""
    raw = name.encode("utf-8")
    if len(raw) <= MAX_NAME_BYTES:
        return raw
    return raw[:MAX_NAME_BYTES].decode("utf-8", "ignore").encode("utf-8")


def delta_name(seq):
    """File name of update number seq (1..40): signatures-all-delta-01.bin."""
    if not 1 <= seq <= MAX_DELTAS:
        raise ValueError("update number out of range")
    return "signatures-all-delta-%02d.bin" % seq


def encode_delta(base_created_at, seq, created_at, entries):
    """entries: dict {sha256_bytes(32): family_name}, at least one. Returns the update file bytes."""
    if not entries:
        raise ValueError("an update needs at least one entry")
    if not 1 <= seq <= MAX_DELTAS:
        raise ValueError("update number out of range")
    names = sorted(set(entries.values()))
    index = {n: i for i, n in enumerate(names)}
    out = bytearray(DELTA_MAGIC)
    out += struct.pack(">IQIQII", DELTA_VERSION, base_created_at, seq, created_at, len(entries), len(names))
    for n in names:
        b = _name_bytes(n)
        out += struct.pack(">H", len(b)) + b
    for h in sorted(entries):
        if len(h) != 32:
            raise ValueError("bad hash length")
        out += h + struct.pack(">H", index[entries[h]])
    return bytes(out)


def decode_delta(data):
    """Strict parser mirroring the app's. Returns (base_created_at, seq, created_at, {hash: name})."""
    header = 8 + 32
    if len(data) < header:
        raise ValueError("truncated header")
    if data[:8] != DELTA_MAGIC:
        raise ValueError("bad magic")
    version, base_created_at, seq, created_at, count, name_count = struct.unpack_from(">IQIQII", data, 8)
    if version != DELTA_VERSION:
        raise ValueError("unsupported version")
    if not 1 <= seq <= MAX_DELTAS:
        raise ValueError("bad sequence number")
    if count < 1:
        raise ValueError("empty update")
    if count > MAX_ENTRIES:
        raise ValueError("too many entries")
    if name_count > MAX_NAMES:
        raise ValueError("too many names")
    pos = header
    names = []
    for _ in range(name_count):
        if len(data) - pos < 2:
            raise ValueError("truncated names")
        (n,) = struct.unpack_from(">H", data, pos)
        if n > MAX_NAME_BYTES:
            raise ValueError("name too long")
        if len(data) - pos - 2 < n:
            raise ValueError("truncated names")
        try:
            names.append(data[pos + 2:pos + 2 + n].decode("utf-8"))
        except UnicodeDecodeError:
            raise ValueError("name is not valid UTF-8")
        pos += 2 + n
    if len(data) != pos + count * 34:
        raise ValueError("length mismatch")
    entries = {}
    prev = None
    for _ in range(count):
        h = data[pos:pos + 32]
        (idx,) = struct.unpack_from(">H", data, pos + 32)
        if idx >= name_count:
            raise ValueError("bad name index")
        if prev is not None and h <= prev:
            raise ValueError("entries not strictly sorted")
        entries[h] = names[idx]
        prev = h
        pos += 34
    return base_created_at, seq, created_at, entries


def read_list(data):
    """Validates a full list (header, names, exact length) without building a dict.
    Returns (created_at, count, names, entries_offset); iter_entries walks the entries."""
    if data[:8] != MAGIC:
        raise ValueError("bad magic")
    if len(data) < 28:
        raise ValueError("truncated header")
    version, created_at, count, name_count = struct.unpack_from(">IQII", data, 8)
    if version != VERSION:
        raise ValueError("unsupported version")
    pos = 28
    names = []
    for _ in range(name_count):
        (n,) = struct.unpack_from(">H", data, pos)
        if n > MAX_NAME_BYTES:
            raise ValueError("name too long")
        names.append(data[pos + 2:pos + 2 + n].decode("utf-8"))
        pos += 2 + n
    if len(data) != pos + count * 34:
        raise ValueError("length mismatch")
    return created_at, count, names, pos


def iter_entries(data, count, names, offset):
    """Yields (hash, name) in file order, checking order and name indexes as it goes."""
    prev = None
    unpack = struct.Struct(">32sH").unpack_from
    for i in range(count):
        h, idx = unpack(data, offset + i * 34)
        if idx >= len(names):
            raise ValueError("bad name index")
        if prev is not None and h <= prev:
            raise ValueError("entries not strictly sorted")
        prev = h
        yield h, names[idx]
