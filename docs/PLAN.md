# Plan for the remaining work

Every agent: read `CLAUDE.md` (rules) and `docs/STATUS.md` (state) first. Pick one workstream, stay
inside its files, commit small, then update `docs/STATUS.md` with evidence. Workstreams A, B and C can
run in parallel because they touch different folders. D runs last.

## A. Website (owns `web/`, `scripts/build-site.sh`)
1. `scripts/build-site.sh`: builds the core JS (`:core:jsBrowserProductionLibraryDistribution`), copies
   `web/` + `kotlin-kotlin-stdlib.js` + `bytecart-core.js` into `site-dist/`, and writes `site-dist/games.json`
   (name, desc, medium, source of each `games/*.bcs`).
2. Landing `web/index.html`: hero with a live playable game (core JS) next to its real cart bytes in hex;
   "how it fits" with the real size table; three ways to play (tag, QR, browser) as a plain list;
   downloads (APK, CLI jar, source); footer with GitHub, docs, privacy note (site collects nothing) and MIT license.
   Palette from `Tiles.palette` (navy #0B0D17, yellow #F7D038), system monospace headings, no web fonts.
3. Playground `web/play/`: editor (textarea + line numbers), example picker from games.json, live compile
   with NFC/QR size bars and error line, canvas player (arrows/WASD, Z=A, X=B, mouse tap/drag, swipe on touch),
   WebAudio sounds, QR panel using the exported core `QrCode` (add a JsExport wrapper in `jsMain/Web.kt`
   only, coordinate with C), download/upload `.bcart`, hex and disassembly views.
4. Docs `web/docs/`: getting started; language reference; builtins and tiles (generate from `Builtin.doc`
   and `Tiles.constants`); cart format and codec (how 191 B of bytecode becomes 84 B); NFC tags (raw pages,
   safety); QR codes; Android app; CLI; building from source; writing your first game tutorial.
5. Verify: open `site-dist` in the browser pane via a local static server, play a game, compile an
   edited game, check the QR decodes (scan with the phone app), check phone-width layout.

## B. Android on device (owns `app/`)
1. Ask the user to hold the NTAG213 to the phone: test read of a blank tag (expect the "not a game" message), write
   Serpent from the Library, read it back and play. Record `adb logcat -s ByteCart` output.
2. QR: `bytecart qr games/depths.bcs -o /tmp/q.png`, open it on the Mac screen, scan with the app.
   Also test the largest QR available (flagship game from C, or a padded test cart near 2953 B).
3. Play every game on the phone; fix control or feel issues (report game-logic bugs to C).
4. Check TECH_DISCOVERED launch with the app closed; sound and haptics; editor typing/IME; back navigation.
5. Release build: unsigned-for-store is fine; ship a debug-signed APK for the GitHub release (note it in docs).

## C. Games and core (owns `games/`, `core/`; the only workstream allowed to edit core)
1. Flagship QR game that uses at least 2 KB: a multi-screen adventure (world map data, keys/doors,
   NPC lines, items, a boss) or a large level pack. Prove levels/puzzles are solvable with a tool in `tools/`.
2. Enrich NFC games toward ~130–140 B (title banner, speed-up, extra mechanics) while keeping them under 144.
3. Headless gameplay tests: feed scripted inputs (e.g. crate solver moves) into `Vm` and assert progress.
4. Optional codec work: try extra contexts (LIT value by previous opcode, SYS id by previous SYS); keep only
   if `bytecart sizes` held-out total improves.
5. Freeze games, retrain priors, rerun all tests and the JS/JVM identity check. This defines format v1.

## D. Publish (after A, B and C are done; already approved by the user)
1. README.md (what it is, screenshots from the phone, quick start), LICENSE (MIT), `.github/workflows/build.yml`
   (JDK 21: core tests, APK, CLI fat jar, site).
2. `gh repo create Narek-versatile/bytecart --public --source . --push`; check no secrets or personal email in history.
3. `gh release create v1.0.0` with `app-debug.apk` (renamed `bytecart-1.0.0.apk`), `bytecart-cli.jar`, site zip.
4. Deploy: rsync `site-dist/` to `root@jarvis:/var/www/narek.actcollege.am/bytecart/`; add the nginx
   location block (backup, `nginx -t`, reload). Verify with `curl -sI https://narek.actcollege.am/bytecart/` and the docs URL.
5. Final report to the user: what changed, how verified, what is open; side effects outside the repo.
