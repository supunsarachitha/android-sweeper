# Android Sweeper malware signatures

Built 2026-11-28 13:11 UTC by the "Malware signatures" workflow on main. Replaced on every change; no history.

- `signatures.bin`: SHA-256 hashes of known Android malware (APK/DEX). Used by the quick malware check.
- `signatures-all.bin`: SHA-256 hashes of known malware of every file type. Used by the deep scan.
- `signatures-all-base.bin` and `signatures-all-delta-NN.bin`: the same deep-scan list as a base (renewed about monthly) plus small update files with the new samples of the days since. The app downloads the base only when it changes.
- `*.bin.sig`: ECDSA P-256 / SHA-256 signatures (DER) over each file.
- `signatures-public.pem`: the verification key (the app has its own built-in copy).
- `info.json`, `info-all.json`, `info-all-base.json`: build time, entry count, SHA-256 (the last one also the number and sizes of the update files).

Source: MalwareBazaar (abuse.ch), CC0, accumulated daily. Format: tools/signatures/sigformat.py on main.
