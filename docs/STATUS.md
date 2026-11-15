# Status (2026-11-15)

## Decisions (from the user)
- Name **bytecart** everywhere (not nfc-games). Repo `github.com/Narek-versatile/bytecart` (public, not created yet).
  Site `https://narek.actcollege.am/bytecart/`, docs at `/bytecart/docs/`. nginx change approved.
- NFC: one game per write, **raw pages, no NDEF** (max bytes: 143 coded + 1 check byte on NTAG213).
- QR games at maximum capacity (v40-L, 2953 B). Games should be "serious"; scope is large.
- Deliverables: Android app with on-phone editor, desktop compiler, web editor/playground, comprehensive docs, GitHub release.

## Done and verified
| Item | Evidence |
|---|---|
| Core VM, compiler, codec, cart format | `:core:jvmTest`: CoreTest 7/7, GamesTest 1/1, QrTest 3/3 pass |
| 12 games (7 NFC, 5 QR) compile, round-trip, run 9000 random-input frames without crash | GamesTest |
| Codec: 5309 B naive bytecode → 2354 B with held-out priors (−56%) | `bytecart sizes games` |
| JS build byte-identical to JVM for all 12 carts | `tools/js_cart_hex.js` vs `bytecart hex`, diff empty |
| QR encoder (Kotlin, for web) decodes in ZXing for versions 1–40 at full capacity | QrTest |
| CLI: compile, info, dis, qr, hex, push, train, sizes; runs on Java 8 | manual runs |
| Android APK builds (compileSdk 37, minSdk 26) and launches on SM-S926B (Android API 37), NFC on, no crash | adb install + logcat crash buffer empty + screenshot |

Current shipped cart sizes (bytes): lights 54, meteor 65, duel 70, flap 71, echo 81, serpent 84,
dash 92 (NFC, cap 144); mines 254, stackfall 272, brickfall 344, crates 350, depths 403 (QR, cap 2953).

## Not verified yet (do not claim these work)
- Writing/reading the real NTAG213 from the app (needs the user to hold the tag).
- QR scanning on the phone (CameraX + ZXing), QR share.
- TECH_DISCOVERED auto-launch when the app is closed (INFERRED to work for raw NTAG tags).
- Gameplay quality: games were only smoke-tested with random input, never played by a human.
  Crates levels are proven solvable by `tools/crate_solver.py`, but the in-VM level loader is untested with real moves.
- Sound, haptics, editor UX on device.

## Known gaps
- Website (landing, playground, docs) not started. GitHub repo, CI, release, deploy not started.
- No flagship QR game that uses most of the 2953 B. NFC games use 54–92 of 144 B (room to enrich).
- Priors trained on 12 games; must be retrained once after games are final (see CLAUDE.md rule 1).
- Kotlin/JS dist is UMD with globals `kotlin-kotlin-stdlib` and `bytecart:core` (~420 KB unminified).

## Commits so far
chore gradle build → feat core → feat games → feat CLI → feat Android app → feat JS API + QR encoder (+ docs).
