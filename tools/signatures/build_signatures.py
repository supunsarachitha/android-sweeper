"""Build signatures.bin from a MalwareBazaar CSV export (Android samples only).

usage: build_signatures.py <export.csv> <out signatures.bin> <out info.json> [--previous-info URL]

Keeps rows whose file type is apk or dex (or whose MIME type is the APK type). Refuses to publish when the
result is empty or less than half the size of the previous published file (a truncated download).
"""
import csv
import hashlib
import io
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


def build(csv_path):
    entries = {}
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
            sha = row[header["sha256_hash"]].lower()
            ftype = row[header["file_type_guess"]].lower()
            mime = row[header["mime_type"]].lower()
            family = row[header["signature"]]
        except IndexError:
            continue
        if ftype not in ANDROID_TYPES and mime != APK_MIME:
            continue
        if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
            continue
        if not family or family.lower() in ("n/a", "null", "none"):
            family = UNKNOWN_NAME
        entries[bytes.fromhex(sha)] = family
    return entries


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    csv_path, out_bin, out_info = argv[1:4]
    prev_count = 0
    if "--previous-info" in argv:
        url = argv[argv.index("--previous-info") + 1]
        try:
            prev_count = json.load(urllib.request.urlopen(url, timeout=30)).get("count", 0)
        except Exception as e:  # first run: nothing published yet
            print(f"no previous info ({e.__class__.__name__}); skipping the shrink check")
    entries = build(csv_path)
    count = len(entries)
    print(f"android hashes: {count} (previous {prev_count})")
    if count == 0:
        print("::error::no Android entries found; refusing to publish")
        return 1
    if prev_count and count < prev_count // 2:
        print(f"::error::only {count} entries vs {prev_count} last time; refusing to publish a shrunken list")
        return 1
    created = int(time.time())
    data = encode(created, entries)
    decode(data)  # round-trip check with the strict parser
    with open(out_bin, "wb") as f:
        f.write(data)
    families = len(set(entries.values()))
    info = {"format": 1, "created_at": created, "count": count, "families": families,
            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "source": "MalwareBazaar (abuse.ch), CC0"}
    with open(out_info, "w") as f:
        json.dump(info, f, indent=2)
        f.write("\n")
    print(json.dumps(info))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
