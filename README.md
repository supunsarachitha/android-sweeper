# Android Sweeper malware signatures

Built 2026-11-14 11:36 UTC by the "Malware signatures" workflow on main. Replaced on every change; no history.

- `signatures.bin`: SHA-256 hashes of known Android malware (APK/DEX). Used by the quick malware check.
- `signatures-all.bin`: SHA-256 hashes of known malware of every file type. Used by the deep scan.
- `*.bin.sig`: ECDSA P-256 / SHA-256 signatures (DER) over each list.
- `signatures-public.pem`: the verification key (the app has its own built-in copy).
- `info.json`, `info-all.json`: build time, entry count, SHA-256.

Source: MalwareBazaar (abuse.ch), CC0, accumulated daily. Format: tools/signatures/sigformat.py on main.
