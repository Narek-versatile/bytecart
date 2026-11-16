# ByteCart

A tiny game console whose cartridges are an NFC sticker or a QR code.

A game is written in a small C-like language, compiled to bytecode for a stack VM, and then
compressed with a context-modelling range coder whose statistics were trained on real games.
The result is small enough that a whole playable game fits in the 144 user bytes of an NTAG213
NFC tag, and a much larger one fits in a single QR code (up to 2953 bytes, version 40-L).
Tap the tag on the phone, or scan the code, and the game starts.

| Game | Medium | Cart size |
|---|---|---|
| Lights, Meteor, Duel, Flap, Echo, Serpent, Dash | NFC tag (144 B) | 54 to 92 B |
| Mines, Stackfall, Brickfall, Crates, Depths | QR code (2953 B) | 254 to 403 B |

All 12 games together are 5309 bytes of plain bytecode and 2140 bytes as carts
(2354 bytes when each game is compressed with statistics trained without it).

## Install the Android app

Download `bytecart.apk` from the [Releases page](https://github.com/Narek-versatile/bytecart/releases)
and open it on the phone, or install it from a computer onto every connected phone
(USB debugging on, `adb` installed):

```bash
curl -fsSL https://raw.githubusercontent.com/Narek-versatile/bytecart/main/scripts/install-android.sh | bash
```

Requires Android 8.0 or newer. NFC is optional (QR and built-in games work without it).

In the app: **Library** plays the built-in games, **Write tag** puts one on an NFC tag,
**QR** shows a code to scan or share, **Scan QR** loads a game from a code, and **Editor**
lets you write a game on the phone with a live size meter.

Writing a game to a tag overwrites whatever the tag held before (for example a URL). The app only
writes the user pages and never locks the tag, so it can be rewritten as often as you like.

## Desktop compiler

Download `bytecart-cli.jar` from the Releases page (Java 8 or newer):

```bash
java -jar bytecart-cli.jar compile games/serpent.bcs -o serpent.bcart
java -jar bytecart-cli.jar info games/serpent.bcs     # sizes, fits NFC / QR
java -jar bytecart-cli.jar dis games/serpent.bcs      # disassembly
java -jar bytecart-cli.jar qr games/depths.bcs -o depths.png
java -jar bytecart-cli.jar push games/serpent.bcs     # open it on a phone over adb
java -jar bytecart-cli.jar sizes games                # size table for a folder of games
```

## A game

The complete source of Serpent. It compiles to 191 bytes of bytecode and an 84-byte cart.

```c
// @name Serpent
// @desc Swipe to steer. Eat apples, grow longer, never bite yourself.
// @medium nfc
fill(0, 0, 16, 16, WALL)
fill(1, 1, 14, 14, 0)
var p = 136, h = 0, t = 0, g = 2, d = 0
seti(rndempty(), APPLE)
loop {
  if (dir + 2) % 4 != d { d = dir }
  seti(p, BODY)
  p += xy(dx(d), dy(d))
  var c = geti(p)
  if c != 0 && c != APPLE { over() }
  seti(p, HEAD)
  mem[h] = p
  h++
  if c == APPLE {
    score++
    g += 2
    sfx(0)
    seti(rndempty(), APPLE)
  } else if g > 0 {
    g--
  } else {
    seti(mem[t], 0)
    t++
  }
  wait(max(2, 6 - score / 4))
}
```

See `games/` for all twelve games.

## Build from source

JDK 21 and the Android SDK (only for the app).

```bash
./gradlew :core:jvmTest                  # VM, compiler, codec tests; every game compiles, fits and survives random input
./gradlew :cli:installDist               # cli/build/install/bytecart/bin/bytecart
./gradlew :cli:fatJar                    # cli/build/libs/bytecart-cli.jar
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
scripts/install-android.sh --local       # install that build on every connected phone
./gradlew :core:jsBrowserProductionLibraryDistribution   # the same core for the browser
```

## Layout

- `core/` Kotlin Multiplatform (JVM and JS): VM, compiler, codec, cart format, QR encoder.
- `app/` Android app (Jetpack Compose).
- `cli/` desktop compiler.
- `games/` game sources (`.bcs`).
- `docs/` project status and plan. `tools/` helper scripts.

## Status

Version 0.1. The cart format is not frozen yet: carts written with 0.1 may need to be rewritten
after 1.0. Tested on a Galaxy S24+ with an NTAG213 tag. Website, playground and full docs are in progress
(see `docs/PLAN.md`).

## License

MIT
