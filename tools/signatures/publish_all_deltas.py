#!/usr/bin/env python3
"""Decides which deep-scan list files get published today: a base list plus small update files.

usage: publish_all_deltas.py --full out/signatures-all.bin --prev prev --out out
                             --needs-signing FILE [--now UNIX_SECONDS]

  --full           the list built today (the complete, newest deep-scan list)
  --prev           folder with the files published so far (already signature-checked by the caller):
                   signatures-all-base.bin(.sig) and signatures-all-delta-NN.bin(.sig); may be empty
  --out            folder that receives the files to publish, plus info-all-base.json
  --needs-signing  (keep it outside --out) receives the names of the new files (one per line) that still need a .sig

Rules:
  * A new base (a copy of --full) is written when no base was published, or the base is 28 days old or
    older, or 40 updates exist, or today's update would be bigger than the app accepts (8 MB). A new base
    has no updates.
  * Otherwise every entry of --full that is not in the base or any update, or whose family name differs from
    the newest value (the base first, then the updates in order), goes into the next update (the next number,
    created_at = now). No such entry: nothing new is written.
  * Files that were published before are copied byte for byte with their old .sig, so phones never download
    them again. A published file that is damaged or does not fit the chain ends the chain there; the files
    from that point on are not copied (a damaged base means a new base).

This script does not sign anything and uses only the Python standard library.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sigformat  # noqa: E402

BASE_NAME = "signatures-all-base.bin"
INFO_NAME = "info-all-base.json"
NEW_BASE_AFTER_SECONDS = 28 * 24 * 60 * 60


def _read(path):
    with open(path, "rb") as f:
        return f.read()


def _write(path, data):
    with open(path, "wb") as f:
        f.write(data)


def _check_list(data):
    """Validates a full list completely; returns (created_at, count, names, offset)."""
    created_at, count, names, offset = sigformat.read_list(data)
    for _ in sigformat.iter_entries(data, count, names, offset):
        pass
    return created_at, count, names, offset


def load_previous(prev_dir):
    """The chain published so far: (base_bytes, base_info, [(name, data, created_at, {hash: name})]).
    base_bytes is None when there is no usable base. Damaged or mismatching files end the chain."""
    base_path = os.path.join(prev_dir, BASE_NAME)
    if not (os.path.isfile(base_path) and os.path.isfile(base_path + ".sig")):
        return None, None, []
    base = _read(base_path)
    try:
        info = _check_list(base)
    except (ValueError, UnicodeDecodeError, IndexError) as e:
        print("previous base is damaged (%s); publishing a new base" % e)
        return None, None, []
    deltas = []
    last_created = info[0]
    for seq in range(1, sigformat.MAX_DELTAS + 1):
        name = sigformat.delta_name(seq)
        path = os.path.join(prev_dir, name)
        if not os.path.isfile(path):
            break
        if not os.path.isfile(path + ".sig"):
            print("%s has no signature; the chain ends before it" % name)
            break
        data = _read(path)
        try:
            base_created, got_seq, created, entries = sigformat.decode_delta(data)
        except ValueError as e:
            print("%s is damaged (%s); the chain ends before it" % (name, e))
            break
        if base_created != info[0] or got_seq != seq or created <= last_created:
            print("%s does not fit the chain; the chain ends before it" % name)
            break
        deltas.append((name, data, created, entries))
        last_created = created
    return base, info, deltas


def new_entries(full, base, base_info, deltas):
    """{hash: name} of entries in the full list that are new or have a different family name than the newest
    published value. Walks the base and the full list in step (both sorted), so no big dict is built."""
    overlay = {}
    for _, _, _, entries in deltas:
        overlay.update(entries)
    f_created, f_count, f_names, f_offset = _check_list(full)
    result = {}
    base_iter = sigformat.iter_entries(base, base_info[1], base_info[2], base_info[3])
    current = next(base_iter, None)
    for h, name in sigformat.iter_entries(full, f_count, f_names, f_offset):
        while current is not None and current[0] < h:
            current = next(base_iter, None)
        known = overlay.get(h)
        if known is None and current is not None and current[0] == h:
            known = current[1]
        if known is None or known != name:
            result[h] = name
    return result


def publish(full_path, prev_dir, out_dir, now):
    """Writes the files to publish into out_dir. Returns the names of the new files (they need a .sig)."""
    full = _read(full_path)
    f_created, f_count, _, _ = _check_list(full)
    os.makedirs(out_dir, exist_ok=True)
    base, base_info, deltas = load_previous(prev_dir)

    fresh = []
    new_delta = None
    reason = None
    if base is None:
        reason = "no base published yet"
    elif now - base_info[0] >= NEW_BASE_AFTER_SECONDS:
        reason = "the base is 28 days old or older"
    elif len(deltas) >= sigformat.MAX_DELTAS:
        reason = "%d updates exist" % len(deltas)
    else:
        added = new_entries(full, base, base_info, deltas)
        if added:
            created = max(now, deltas[-1][2] + 1 if deltas else base_info[0] + 1)
            data = sigformat.encode_delta(base_info[0], len(deltas) + 1, created, added)
            if len(data) > sigformat.MAX_DELTA_BYTES:
                reason = "today's update would be %d bytes, more than the app accepts" % len(data)
            else:
                new_delta = (sigformat.delta_name(len(deltas) + 1), data, created, added)

    if reason is not None:
        print("new base: " + reason)
        _write(os.path.join(out_dir, BASE_NAME), full)
        fresh.append(BASE_NAME)
        base, base_info, deltas = full, (f_created, f_count), []
    else:
        shutil.copyfile(os.path.join(prev_dir, BASE_NAME), os.path.join(out_dir, BASE_NAME))
        shutil.copyfile(os.path.join(prev_dir, BASE_NAME + ".sig"), os.path.join(out_dir, BASE_NAME + ".sig"))
        for name, _, _, _ in deltas:
            shutil.copyfile(os.path.join(prev_dir, name), os.path.join(out_dir, name))
            shutil.copyfile(os.path.join(prev_dir, name + ".sig"), os.path.join(out_dir, name + ".sig"))
        if new_delta is not None:
            print("new update %s: %d entries, %d bytes" % (new_delta[0], len(new_delta[3]), len(new_delta[1])))
            _write(os.path.join(out_dir, new_delta[0]), new_delta[1])
            fresh.append(new_delta[0])
            deltas = deltas + [new_delta]
        else:
            print("nothing new: base and %d update(s) stay as published" % len(deltas))

    info = {
        "format": 1,
        "created_at": base_info[0],
        "count": base_info[1],
        "sha256": hashlib.sha256(_read(os.path.join(out_dir, BASE_NAME))).hexdigest(),
        "bytes": os.path.getsize(os.path.join(out_dir, BASE_NAME)),
        "deltas": len(deltas),
        "delta_sizes": [len(d[1]) for d in deltas],
    }
    with open(os.path.join(out_dir, INFO_NAME), "w") as f:
        json.dump(info, f, indent=2)
        f.write("\n")
    return fresh


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--full", required=True)
    ap.add_argument("--prev", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--now", type=int, default=None)
    ap.add_argument("--needs-signing", required=True)
    args = ap.parse_args()
    now = int(time.time()) if args.now is None else args.now
    fresh = publish(args.full, args.prev, args.out, now)
    with open(args.needs_signing, "w") as f:
        for name in fresh:
            f.write(name + "\n")
    print("needs signing: %s" % (", ".join(fresh) if fresh else "nothing"))


if __name__ == "__main__":
    main()
