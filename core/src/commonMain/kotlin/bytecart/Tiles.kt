package bytecart

/**
 * Built-in graphics. Tile ids:
 *   0        empty
 *   1..15    solid colour blocks (palette index)
 *   16..47   built-in 8x8 icons
 *   48..63   custom tiles, defined at runtime with tile(id, data, color)
 *   64..127  text glyphs: tile = ASCII code + 32 (codes 32..95, lowercase is folded to upper)
 *   128..255 the same tile (id - 128), drawn dimmed
 */
object Tiles {
    /** ARGB palette. Index 0 is the background. */
    val palette = intArrayOf(
        0xFF0B0D17.toInt(), 0xFFF4F4F4.toInt(), 0xFFE8434F.toInt(), 0xFFF28C28.toInt(),
        0xFFF7D038.toInt(), 0xFF3FBF5F.toInt(), 0xFF2BB3A6.toInt(), 0xFF3B7DDD.toInt(),
        0xFF8A52D6.toInt(), 0xFFF06FB0.toInt(), 0xFF9A6438.toInt(), 0xFF9AA3B5.toInt(),
        0xFF4A5068.toInt(), 0xFFA7E04A.toInt(), 0xFF6FD0F2.toInt(), 0xFF1F3A8A.toInt(),
    )

    val colorNames = listOf(
        "EMPTY", "WHITE", "RED", "ORANGE", "YELLOW", "GREEN", "TEAL", "BLUE",
        "PURPLE", "PINK", "BROWN", "GREY", "DARK", "LIME", "SKY", "NAVY",
    )

    const val FIRST_ICON = 16
    const val FIRST_CUSTOM = 48
    const val FIRST_GLYPH = 64
    const val DIM = 128

    /** Icon art: '.' = transparent, hex digit = palette index. */
    private val icons: List<Pair<String, String>> = listOf(
        "WALL" to "aaaacaaa aaaacaaa cccccccc caaaaaaa caaaaaaa cccccccc aaaacaaa aaaacaaa",
        "APPLE" to "....5... ...5.... .22.22.. 2222222. 2122222. 2222222. .22222.. ..2.2...",
        "COIN" to "..4444.. .444444. 44433444 44434444 44434444 44433444 .444444. ..4444..",
        "GEM" to "........ .eeeeee. e1eeee7e eeeeee7e .eeee7e. ..eee7.. ...e7... ........",
        "HEART" to "........ .22..22. 22222222 21222222 22222222 .222222. ..2222.. ...22...",
        "STAR" to "...44... ...44... 44444444 .444444. ..4444.. .44..44. 44....44 ........",
        "SKULL" to ".111111. 11111111 1cc11cc1 1cc11cc1 11111111 .111111. ..1c1c.. ..1111..",
        "GHOST" to "..8888.. .888888. 81188118 87188718 88888888 88888888 88888888 8.88.8.8",
        "PLAYER" to "..4444.. .444444. 44c44c44 44444444 4c4444c4 44cccc44 .444444. ..4444..",
        "ALIEN" to "..d..d.. ...dd... ..dddd.. .dcddcd. dddddddd d.dddd.d d.d..d.d ...dd...",
        "SHIP" to "...11... ...11... ..1ee1.. ..1111.. .111111. 11111111 11.33.11 1..22..1",
        "ROCK" to "........ ..bbb... .bbbbbb. bbcbbbbb bbbbbcbb bbbbbbbb .bbbbbb. ........",
        "TREE" to "...55... ..5555.. .555555. 55555555 .555555. 55555555 ...aa... ...aa...",
        "BOMB" to "......4. .....3.. ..cccc.. .cccccc. cc1ccccc cccccccc .cccccc. ..cccc..",
        "FLAG" to "1222.... 12222... 122222.. 12222... 1222.... 1....... 1....... 1.......",
        "KEY" to "........ .444.... 4...4... 4...4444 4...4.44 .444.... ........ ........",
        "DOOR" to "..aaaa.. .aaaaaa. .a3aa3a. .aaaaaa. .aaaa4a. .aaaaaa. .a3aa3a. .aaaaaa.",
        "BOX" to "33333333 3aaaaaa3 3a3aa3a3 3aa33aa3 3aa33aa3 3a3aa3a3 3aaaaaa3 33333333",
        "GOAL" to "........ ..2222.. .2....2. .2.22.2. .2.22.2. .2....2. ..2222.. ........",
        "BALL" to "........ ..1111.. .111111. .111111. .11111b. .1111bb. ..1bbb.. ........",
        "BIRD" to "........ ..4444.. .444c44. 44444433 11444444 .1144444 .444444. ..4444..",
        "FISH" to "........ ...eee.. e.eeee1e eeeeeeee e.eeeeee ...eee.. ........ ........",
        "CACTUS" to "...55... 5..55... 5..55..5 55555..5 ...55555 ...55... ...55... ...55...",
        "FIRE" to "...3.... ..33.... ..333.3. .33433.. .344433. 33444433 34444443 .344443.",
        "WATER" to "........ .7...7.. 7e7.7e7. 77777777 7e777e77 77777777 77777777 77777777",
        "GRASS" to "........ ........ 5...5..5 5.5.5.55 55555555 5d55d555 55555555 aaaaaaaa",
        "SWORD" to "......11 .....1b1 ....1b1. ...1b1.. 3.1b1... .331.... .a33.... a..3....",
        "POTION" to "...aa... ...11... ..1..1.. .1....1. 19999991 19999991 .199991. ..1111..",
        "STAIRS" to "......bb ......bb ....bbbb ....bbbb ..bbbbbb ..bbbbbb bbbbbbbb bbbbbbbb",
        "HEAD" to ".555555. 55555555 5c5555c5 55555555 55555555 55522555 55555555 .555555.",
        "BODY" to "........ .555555. .5d55d5. .555555. .555555. .5d55d5. .555555. ........",
        "BLOCK" to "11111111 1bbbbbbc 1bbbbbbc 1bbbbbbc 1bbbbbbc 1bbbbbbc 1bbbbbbc cccccccc",
    )

    val iconNames: List<String> = icons.map { it.first }

    private val iconArt: Array<IntArray> = Array(icons.size) { i ->
        val rows = icons[i].second.split(' ')
        require(rows.size == 8 && rows.all { it.length == 8 }) { "bad icon ${icons[i].first}" }
        IntArray(64) { p ->
            val ch = rows[p / 8][p % 8]
            if (ch == '.') -1 else ch.digitToInt(16)
        }
    }

    /** Every named tile constant the compiler knows about. */
    val constants: Map<String, Int> = buildMap {
        colorNames.forEachIndexed { i, n -> put(n, i) }
        iconNames.forEachIndexed { i, n -> put(n, FIRST_ICON + i) }
        for (i in 0 until 16) put("T$i", FIRST_CUSTOM + i)
        put("DIM", DIM)
        put("GLYPH", 32)
    }

    private fun mix(c: Int, with: Int, t: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        fun m(s: Int) = (ch(c, s) + (ch(with, s) - ch(c, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private val solidArt: Array<IntArray> = Array(16) { idx ->
        val base = palette[idx]
        val light = mix(base, 0xFFFFFFFF.toInt(), 0.35f)
        val dark = mix(base, 0xFF000000.toInt(), 0.35f)
        IntArray(64) { p ->
            val x = p % 8; val y = p / 8
            when {
                idx == 0 -> 0
                x == 7 || y == 7 -> dark
                x == 0 || y == 0 -> light
                else -> base
            }
        }
    }

    /**
     * 64 ARGB pixels (0 = transparent) for any non-glyph tile, or null for empty/glyph tiles.
     * Custom tiles are read from [custom] (16 x 8 row bytes) and [customColor].
     */
    fun bitmap(tile: Int, custom: IntArray, customColor: IntArray): IntArray? {
        val t = tile and 0x7F
        return when {
            t == 0 -> null
            t < FIRST_ICON -> solidArt[t]
            t < FIRST_CUSTOM -> {
                val art = iconArt[t - FIRST_ICON]
                IntArray(64) { p -> if (art[p] < 0) 0 else palette[art[p]] }
            }
            t < FIRST_GLYPH -> {
                val k = t - FIRST_CUSTOM
                val color = palette[customColor[k] and 15]
                IntArray(64) { p ->
                    val row = custom[k * 8 + p / 8]
                    if ((row shr (7 - p % 8)) and 1 == 1) color else 0
                }
            }
            else -> null
        }
    }

    /** Character drawn by a glyph tile, or null if the tile is not a glyph. */
    fun glyph(tile: Int): Char? {
        val t = tile and 0x7F
        return if (t >= FIRST_GLYPH) (t - 32).toChar() else null
    }

    fun isDim(tile: Int) = tile and DIM != 0
}
