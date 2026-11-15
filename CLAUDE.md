# ByteCart: rules for every agent working in this repo

ByteCart is a tiny game console. Games are compiled to a compressed "cart" image that fits on an
NTAG213 NFC tag (144 bytes) or in a QR code (up to 2953 bytes). One Kotlin core runs on Android,
the desktop JVM (CLI) and the browser (Kotlin/JS). Read `docs/STATUS.md` first, then take a task from `docs/PLAN.md`.

## Layout
- `core/`: Kotlin Multiplatform (jvm + js). `Ops.kt` instruction set and builtins, `Compiler.kt`,
  `Vm.kt`, `Codec.kt` (range coder + context models), `Priors.kt` (generated), `Cart.kt` (image format),
  `QrEncode.kt`, `Tiles.kt`, `jsMain/Web.kt` (JS API).
- `games/*.bcs`: game sources. Header comments `// @name`, `// @desc`, `// @medium nfc|qr`.
- `cli/`: desktop compiler (`bytecart` command). `app/`: Android app (Compose, package `dev.bytecart`).
- `web/`: website sources (landing, playground, docs). `tools/`: helper scripts. `docs/`: plan and status.

## Commands (always set JAVA_HOME; the system java is 1.8)
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :core:jvmTest                         # unit tests + every game compiles, fits, survives random input
./gradlew :cli:installDist && ./cli/build/install/bytecart/bin/bytecart sizes games
./gradlew :app:assembleDebug && adb -s R5CX34D90DF install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:jsBrowserProductionLibraryDistribution   # -> core/build/dist/js/productionLibrary/
./cli/build/install/bytecart/bin/bytecart push games/serpent.bcs   # open a game on the phone
```
Test results: `core/build/test-results/jvmTest/*.xml` (the -q output hides passing tests).

## Hard rules
1. **Format stability.** `Op`/`Builtin` order, the codec model layout and `Priors.V1` define the bitstream.
   Until the first public release you may change them, but then rerun `bytecart train games -o
   core/src/commonMain/kotlin/bytecart/Priors.kt` and all tests. After release: append only, and bump `Cart.FORMAT_TAG` on any change.
2. **One writer in `core/` at a time.** Agents working on web, app or games must not edit `core/`
   without coordinating; retraining priors happens once, after games are frozen (see PLAN).
3. **JS and JVM must stay byte-identical.** After core changes run `node tools/js_cart_hex.js <dist dir> games/*.bcs`
   and diff against `bytecart hex` for each game.
4. **NFC safety.** Only write user pages 4..(4+size/4-1). Never write pages 0-3 or config/lock pages, never call makeReadOnly.
5. **Evidence.** Never claim something works without running it; show the command and output. Label guesses INFERRED.
6. **Git.** Small commits with prefixes (feat:, fix:, docs:, chore:). Repo-local identity is already set
   (Narek-versatile, GitHub noreply email). **No `Co-Authored-By` trailer.** Do not push unless the plan step says so.
7. **Server.** `ssh root@jarvis`. Only touch `/var/www/narek.actcollege.am/bytecart/` and one
   `location ^~ /bytecart/` block in `/etc/nginx/sites-available/narek.actcollege.am.conf` (backup first, `nginx -t`, then reload).
   Do not add `add_header` inside the location (it would drop the server-level security headers).
8. **Copy style.** No em dashes, no fake testimonials, no emoji icons. Use real numbers from `bytecart sizes`.
9. **Privacy.** Never put the user's real email or name in commits, site or docs.
