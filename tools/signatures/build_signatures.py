"""Build signatures.bin (Android malware SHA-256 hashes) from MalwareBazaar data.

usage: build_signatures.py <out signatures.bin> <out info.json>
           [--csv FILE]...            MalwareBazaar CSV exports (full or recent)
           [--api-json FILE]...       MalwareBazaar API replies (query=get_file_type)
           [--previous FILE]          the currently published signatures.bin, ALREADY signature-checked;
                                      its entries are kept (the list only grows)
           [--previous-info URL]      info.json of the published list, for the shrink check
           [--types android|all]      android (default): APK/DEX only, family names as given;
                                      all: every file type, names like "AgentTesla (exe)"

With --types android, keeps rows whose file type is apk or dex (or whose MIME type is the APK type). Refuses to publish when the
result is empty or less than half the size of the previous published list.
"""
import csv
import argparse
import hashlib
import json
import sys
import time
import urllib.request

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from sigformat import UNKNOWN_NAME, decode, encode  # noqa: E402

ANDROID_TYPES = {"apk", "dex"}
APK_MIME = "application/vnd.android.package-archive"


def rows(path):
    """CSV rows; a commented header line ("# first_seen_utc, sha256_hash, ...") is returned as a row too."""
    def lines(f):
        for line in f:
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("#"):
                if "sha256_hash" in stripped:
                    yield stripped.lstrip("#").strip() + "\n"
                continue
            yield line

    with open(path, encoding="utf-8", errors="replace", newline="") as f:
        for row in csv.reader(lines(f), skipinitialspace=True):
            yield [c.strip() for c in row]


MODE = {"types": "android"}
SAFE_TYPE = __import__("re").compile(r"[a-z0-9]{1,12}")


def is_unknown(name):
    return name is None or name == UNKNOWN_NAME or name.startswith("Unknown malware")


def add(entries, sha, ftype, mime, family):
    """Adds one sample if it fits the mode and has a valid SHA-256. Returns True if it was added."""
    sha = (sha or "").strip().lower()
    ftype = (ftype or "").strip().lower()
    mime = (mime or "").strip().lower()
    family = (family or "").strip()
    is_android = ftype in ANDROID_TYPES or mime == APK_MIME
    if MODE["types"] == "android" and not is_android:
        return False
    if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        return False
    known = bool(family) and family.lower() not in ("n/a", "null", "none")
    if MODE["types"] == "android":
        name = family if known else UNKNOWN_NAME
    else:
        kind = ftype if SAFE_TYPE.fullmatch(ftype or "") else "file"
        name = f"{family} ({kind})" if known else f"Unknown malware ({kind})"
    key = bytes.fromhex(sha)
    # A named family beats "unknown" when the same sample shows up again.
    if is_unknown(entries.get(key)):
        entries[key] = name
    return True


def add_api_json(entries, path):
    """MalwareBazaar API reply: {"query_status": "ok", "data": [{"sha256_hash", "file_type", "signature", ...}]}"""
    with open(path, encoding="utf-8") as f:
        reply = json.load(f)
    status = reply.get("query_status")
    if status != "ok":
        print(f"::warning::{path}: query_status={status}")
        return 0
    n = 0
    for item in reply.get("data") or []:
        if add(entries, item.get("sha256_hash"), item.get("file_type"), item.get("file_type_mime"),
               item.get("signature")):
            n += 1
    return n


def build(csv_path, entries=None):
    entries = {} if entries is None else entries
    header = None
    for row in rows(csv_path):
        if header is None and "sha256_hash" in row:
            header = {name: i for i, name in enumerate(row)}
            continue
        if header is None:
            # MalwareBazaar's export has its header as a comment line: use the documented column order.
            header = {"first_seen_utc": 0, "sha256_hash": 1, "md5_hash": 2, "sha1_hash": 3, "reporter": 4,
                      "file_name": 5, "file_type_guess": 6, "mime_type": 7, "signature": 8}
        try:
            add(entries, row[header["sha256_hash"]], row[header["file_type_guess"]], row[header["mime_type"]],
                row[header["signature"]])
        except IndexError:
            continue
    return entries


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("out_bin")
    ap.add_argument("out_info")
    ap.add_argument("--csv", action="append", default=[])
    ap.add_argument("--api-json", action="append", default=[])
    ap.add_argument("--previous")
    ap.add_argument("--previous-info")
    ap.add_argument("--types", choices=["android", "all"], default="android")
    args = ap.parse_args(argv[1:])
    MODE["types"] = args.types

    entries = {}
    if args.previous:
        _, prev_entries = decode(open(args.previous, "rb").read())
        entries.update(prev_entries)
        print(f"previous list: {len(prev_entries)} entries")
    for path in args.csv:
        before = len(entries)
        build(path, entries)
        print(f"{path}: +{len(entries) - before}")
    for path in args.api_json:
        before = len(entries)
        add_api_json(entries, path)
        print(f"{path}: +{len(entries) - before}")

    prev_count = 0
    if args.previous_info:
        try:
            prev_count = json.load(urllib.request.urlopen(args.previous_info, timeout=30)).get("count", 0)
        except Exception as e:  # first run: nothing published yet
            print(f"no previous info ({e.__class__.__name__}); skipping the shrink check")
    count = len(entries)
    print(f"{args.types} hashes: {count} (previous published {prev_count})")
    if count == 0:
        print("::error::no entries found; refusing to publish")
        return 1
    if prev_count and count < prev_count // 2:
        print(f"::error::only {count} entries vs {prev_count} last time; refusing to publish a shrunken list")
        return 1
    created = int(time.time())
    data = encode(created, entries)
    decode(data)  # round-trip check with the strict parser
    with open(args.out_bin, "wb") as f:
        f.write(data)
    info = {"format": 1, "created_at": created, "count": count, "families": len(set(entries.values())),
            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "source": "MalwareBazaar (abuse.ch), CC0"}
    with open(args.out_info, "w") as f:
        json.dump(info, f, indent=2)
        f.write("\n")
    print(json.dumps(info))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
