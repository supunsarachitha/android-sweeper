"""Checks for sigformat (update files) and publish_all_deltas.py. Standard library only, no signing.

Run: python3 -I tools/signatures/test_publish.py
"""
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import publish_all_deltas as pub  # noqa: E402
import sigformat  # noqa: E402

DAY = 86400
T0 = 1_800_000_000


def h(seed):
    return hashlib.sha256(str(seed).encode()).digest()


def entries(lo, hi, family="Fam"):
    return {h(i): "%s%d" % (family, i % 3) for i in range(lo, hi)}


class DeltaFormat(unittest.TestCase):
    def test_round_trip(self):
        e = {h(1): "A", h(2): "B (exe)", h(3): "Käfer-木马"}
        data = sigformat.encode_delta(T0, 3, T0 + 5, e)
        self.assertEqual(data[:8], b"SWDLT1\0\0")
        self.assertEqual(sigformat.decode_delta(data), (T0, 3, T0 + 5, e))

    def test_size_is_header_names_and_34_per_entry(self):
        data = sigformat.encode_delta(T0, 1, T0 + 1, {h(1): "A", h(2): "A"})
        self.assertEqual(len(data), 40 + 3 + 2 * 34)

    def test_long_names_are_cut_on_a_character_boundary(self):
        data = sigformat.encode_delta(T0, 1, T0, {h(1): "木" * 100})
        name = sigformat.decode_delta(data)[3][h(1)]
        self.assertEqual(name, "木" * 66)  # 198 bytes

    def test_names_are_shared(self):
        data = sigformat.encode_delta(T0, 1, T0, {h(i): "Same" for i in range(50)})
        self.assertEqual(len(data), 40 + 2 + 4 + 50 * 34)

    def bad(self, data, text):
        with self.assertRaises(ValueError) as ctx:
            sigformat.decode_delta(data)
        self.assertIn(text, str(ctx.exception))

    def raw(self, magic=b"SWDLT1\0\0", version=1, base=T0, seq=1, created=T0 + 1, count=1, names=(b"A",),
            name_count=None, entries_=None, trailing=b""):
        out = bytearray(magic)
        out += struct.pack(">IQIQII", version, base, seq, created, count, len(names) if name_count is None else name_count)
        for n in names:
            out += struct.pack(">H", len(n)) + n
        for hh, idx in (entries_ if entries_ is not None else [(h(1), 0)]):
            out += hh + struct.pack(">H", idx)
        return bytes(out + trailing)

    def test_good_raw_file_decodes(self):
        self.assertEqual(sigformat.decode_delta(self.raw())[3], {h(1): "A"})

    def test_bad_magic(self):
        self.bad(self.raw(magic=b"SWSIG1\0\0"), "bad magic")

    def test_bad_version(self):
        self.bad(self.raw(version=2), "unsupported version")

    def test_truncated_header(self):
        self.bad(self.raw()[:30], "truncated header")

    def test_length_mismatch(self):
        self.bad(self.raw(trailing=b"x"), "length mismatch")
        self.bad(self.raw()[:-1], "length mismatch")

    def test_unsorted_and_duplicate(self):
        a, b = sorted([h(1), h(2)])
        self.bad(self.raw(count=2, entries_=[(b, 0), (a, 0)]), "not strictly sorted")
        self.bad(self.raw(count=2, entries_=[(a, 0), (a, 0)]), "not strictly sorted")

    def test_bad_name_index(self):
        self.bad(self.raw(entries_=[(h(1), 1)]), "bad name index")

    def test_empty_name_table_edge(self):
        self.bad(self.raw(count=0, names=(), entries_=[]), "empty update")  # no entries at all
        self.bad(self.raw(names=(), entries_=[(h(1), 0)]), "bad name index")  # entries but no names

    def test_bad_sequence_number(self):
        self.bad(self.raw(seq=0), "bad sequence number")
        self.bad(self.raw(seq=41), "bad sequence number")

    def test_bad_utf8_name(self):
        self.bad(self.raw(names=(b"\xff\xfe",)), "UTF-8")

    def test_name_too_long(self):
        self.bad(self.raw(names=(b"a" * 201,)), "name too long")

    def test_encoder_refuses_empty(self):
        with self.assertRaises(ValueError):
            sigformat.encode_delta(T0, 1, T0, {})


class Publish(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.runs = 0
        self.last_out = None

    def tearDown(self):
        self.tmp.cleanup()

    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def run_at(self, full_entries, seconds):
        """One workflow run `seconds` after T0. The previous run's output folder becomes `prev`; fake .sig
        files are written for the files the script reports as new (the workflow signs them with openssl)."""
        self.runs += 1
        n = self.runs
        now = T0 + seconds
        prev = self.path("prev%d" % n)
        out = self.path("out%d" % n)
        os.makedirs(prev)
        if self.last_out:
            for name in os.listdir(self.last_out):
                if name.startswith("signatures-all-"):
                    with open(os.path.join(self.last_out, name), "rb") as src, open(os.path.join(prev, name), "wb") as dst:
                        dst.write(src.read())
        full = self.path("full%d.bin" % n)
        with open(full, "wb") as f:
            f.write(sigformat.encode(now, full_entries))
        listing = self.path("needs%d.txt" % n)
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "publish_all_deltas.py"), "--full", full,
                        "--prev", prev, "--out", out, "--now", str(now), "--needs-signing", listing],
                       check=True, capture_output=True)
        with open(listing) as f:
            fresh = f.read().split()
        for name in fresh:
            with open(os.path.join(out, name + ".sig"), "wb") as f:
                f.write(b"sig of " + name.encode() + str(n).encode())
        files = sorted(x for x in os.listdir(out) if x.startswith("signatures-all-") and not x.endswith(".sig"))
        self.last_out = out
        return out, prev, fresh, files

    def run_day(self, full_entries, day):
        return self.run_at(full_entries, day * DAY)

    def read(self, folder, name):
        with open(os.path.join(folder, name), "rb") as f:
            return f.read()

    def info(self, folder):
        return json.loads(self.read(folder, "info-all-base.json"))

    def test_day_one_publishes_only_a_base(self):
        full = entries(0, 100)
        out, _, fresh, files = self.run_day(full, 0)
        self.assertEqual(fresh, ["signatures-all-base.bin"])
        self.assertEqual(files, ["signatures-all-base.bin"])
        self.assertEqual(self.read(out, "signatures-all-base.bin"), sigformat.encode(T0, full))
        info = self.info(out)
        self.assertEqual((info["created_at"], info["count"], info["deltas"], info["delta_sizes"]), (T0, 100, 0, []))
        self.assertEqual(info["sha256"], hashlib.sha256(self.read(out, "signatures-all-base.bin")).hexdigest())
        self.assertEqual(info["bytes"], len(self.read(out, "signatures-all-base.bin")))

    def test_day_two_publishes_a_delta_with_only_the_new_entries(self):
        self.run_day(entries(0, 100), 0)
        out, _, fresh, files = self.run_day(entries(0, 130), 1)
        self.assertEqual(fresh, ["signatures-all-delta-01.bin"])
        self.assertEqual(files, ["signatures-all-base.bin", "signatures-all-delta-01.bin"])
        base_created, seq, created, got = sigformat.decode_delta(self.read(out, "signatures-all-delta-01.bin"))
        self.assertEqual((base_created, seq, created), (T0, 1, T0 + DAY))
        self.assertEqual(got, entries(100, 130))
        info = self.info(out)
        self.assertEqual(info["deltas"], 1)
        self.assertEqual(info["delta_sizes"], [len(self.read(out, "signatures-all-delta-01.bin"))])

    def test_unchanged_day_publishes_nothing_and_copies_files_unchanged(self):
        self.run_day(entries(0, 100), 0)
        out1, *_ = self.run_day(entries(0, 130), 1)
        out2, _, fresh, files = self.run_day(entries(0, 130), 2)
        self.assertEqual(fresh, [])
        self.assertEqual(files, ["signatures-all-base.bin", "signatures-all-delta-01.bin"])
        for name in files:
            self.assertEqual(self.read(out2, name), self.read(out1, name))
            self.assertEqual(self.read(out2, name + ".sig"), self.read(out1, name + ".sig"))
        self.assertEqual(self.read(out2, "info-all-base.json"), self.read(out1, "info-all-base.json"))

    def test_second_delta_holds_only_entries_not_in_base_or_first_delta(self):
        self.run_day(entries(0, 100), 0)
        self.run_day(entries(0, 130), 1)
        out, _, fresh, files = self.run_day(entries(0, 135), 2)
        self.assertEqual(fresh, ["signatures-all-delta-02.bin"])
        _, seq, created, got = sigformat.decode_delta(self.read(out, "signatures-all-delta-02.bin"))
        self.assertEqual((seq, created), (2, T0 + 2 * DAY))
        self.assertEqual(got, entries(130, 135))

    def test_renamed_family_goes_into_the_delta_and_again_when_renamed_back(self):
        base = entries(0, 100)
        self.run_day(base, 0)
        renamed = dict(base)
        renamed[h(5)] = "NewName"
        out, _, fresh, _ = self.run_day(renamed, 1)
        self.assertEqual(fresh, ["signatures-all-delta-01.bin"])
        self.assertEqual(sigformat.decode_delta(self.read(out, "signatures-all-delta-01.bin"))[3], {h(5): "NewName"})
        # the same name again: nothing new (newest value already matches)
        _, _, fresh, _ = self.run_day(renamed, 2)
        self.assertEqual(fresh, [])
        # back to the base's name: that differs from the newest value, so it is an update again
        out, _, fresh, _ = self.run_day(base, 3)
        self.assertEqual(fresh, ["signatures-all-delta-02.bin"])
        self.assertEqual(sigformat.decode_delta(self.read(out, "signatures-all-delta-02.bin"))[3], {h(5): base[h(5)]})

    def test_base_28_days_old_gets_a_new_base_and_no_deltas(self):
        self.run_day(entries(0, 100), 0)
        self.run_day(entries(0, 110), 1)
        self.run_day(entries(0, 120), 27)  # 27 days old: still a delta
        full = entries(0, 130)
        out, _, fresh, files = self.run_day(full, 28)
        self.assertEqual(fresh, ["signatures-all-base.bin"])
        self.assertEqual(files, ["signatures-all-base.bin"])
        self.assertFalse(os.path.exists(os.path.join(out, "signatures-all-delta-01.bin")))
        self.assertEqual(self.read(out, "signatures-all-base.bin"), sigformat.encode(T0 + 28 * DAY, full))
        info = self.info(out)
        self.assertEqual((info["created_at"], info["count"], info["deltas"]), (T0 + 28 * DAY, 130, 0))

    def test_27_days_old_base_still_gets_a_delta(self):
        self.run_day(entries(0, 100), 0)
        _, _, fresh, _ = self.run_day(entries(0, 110), 27)
        self.assertEqual(fresh, ["signatures-all-delta-01.bin"])

    def test_forty_deltas_give_a_new_base(self):
        self.run_at(entries(0, 100), 0)
        for n in range(1, 41):  # 16 hours apart: 40 updates fit in the 28 days
            _, _, fresh, _ = self.run_at(entries(0, 100 + n), n * 16 * 3600)
            self.assertEqual(fresh, ["signatures-all-delta-%02d.bin" % n])
        _, _, fresh, files = self.run_at(entries(0, 200), 41 * 16 * 3600 - 1)  # still under 28 days old
        self.assertEqual(fresh, ["signatures-all-base.bin"])
        self.assertEqual(files, ["signatures-all-base.bin"])

    def test_a_damaged_published_delta_ends_the_chain(self):
        self.run_day(entries(0, 100), 0)
        out1, *_ = self.run_day(entries(0, 110), 1)
        out2, *_ = self.run_day(entries(0, 120), 2)
        with open(os.path.join(out2, "signatures-all-delta-01.bin"), "ab") as f:
            f.write(b"x")  # damage it in what the next run sees as published
        out3, _, fresh, files = self.run_day(entries(0, 125), 3)
        # delta 01 is damaged, so it and delta 02 are not copied; the new update is numbered 01 and holds
        # everything that base alone does not have
        self.assertEqual(files, ["signatures-all-base.bin", "signatures-all-delta-01.bin"])
        self.assertEqual(fresh, ["signatures-all-delta-01.bin"])
        self.assertEqual(sigformat.decode_delta(self.read(out3, "signatures-all-delta-01.bin"))[3], entries(100, 125))

    def test_damaged_base_gives_a_new_base(self):
        out0, *_ = self.run_day(entries(0, 100), 0)
        with open(os.path.join(out0, "signatures-all-base.bin"), "ab") as f:
            f.write(b"x")
        _, _, fresh, files = self.run_day(entries(0, 120), 1)
        self.assertEqual((fresh, files), (["signatures-all-base.bin"], ["signatures-all-base.bin"]))

    def test_oversized_update_gives_a_new_base(self):
        self.run_day(entries(0, 10), 0)
        big = entries(0, 10)
        big.update({h(i): "Fam" for i in range(1000, 1000 + 250_000)})  # 8.5 MB of new entries
        _, _, fresh, files = self.run_day(big, 1)
        self.assertEqual((fresh, files), (["signatures-all-base.bin"], ["signatures-all-base.bin"]))

    def test_delta_time_is_always_later_than_the_previous_one(self):
        self.run_day(entries(0, 100), 0)
        self.run_day(entries(0, 110), 1)
        # a rerun within the same second: created_at still moves forward
        out, *_ = self.run_at(entries(0, 120), DAY)
        self.assertEqual(sigformat.decode_delta(self.read(out, "signatures-all-delta-02.bin"))[2], T0 + DAY + 1)

    def test_published_new_files_equal_the_current_full_list_when_a_base_is_made(self):
        full = entries(0, 50)
        out, *_ = self.run_day(full, 0)
        self.assertEqual(self.read(out, "signatures-all-base.bin"), self.read(self.root, "full1.bin"))


if __name__ == "__main__":
    unittest.main()
