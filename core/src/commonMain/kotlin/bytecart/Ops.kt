package bytecart

/**
 * Operand kinds. The codec gives each (opcode, operand) pair its own adaptive integer model,
 * so the kind only decides signedness and how jump targets are converted.
 */
enum class Arg { INT, UINT, JUMP }

/**
 * VM instruction set. A decoded program is an IntArray: opcode id followed by its operands.
 * Order matters only for the codec's opcode tree (6 bits), never reorder without bumping
 * [Cart.FORMAT_TAG].
 */
enum class Op(vararg val args: Arg) {
    END,                // stream terminator, never executed
    HALT,
    LIT(Arg.INT),       // push n
    LD(Arg.UINT), ST(Arg.UINT),       // global var
    LDL(Arg.UINT), STL(Arg.UINT),     // local var (function frame)
    ADDV(Arg.UINT),     // global += pop
    INCV(Arg.UINT), DECV(Arg.UINT),
    ADDL(Arg.UINT), INCL(Arg.UINT), DECL(Arg.UINT),
    DUP, DROP, SWAP,
    ADD, SUB, MUL, DIV, MOD, NEG, AND, OR, XOR, SHL, SHR, BNOT, LNOT,
    EQ, NE, LT, LE, GT, GE,
    JMP(Arg.JUMP), JZ(Arg.JUMP), JNZ(Arg.JUMP),
    FUNC(Arg.UINT, Arg.UINT),   // function entry: nargs, nlocals (extra locals)
    CALL(Arg.UINT),             // function number (k-th FUNC in code order)
    RET,                        // return pop()
    RET0,                       // return 0
    LDM, STM,                   // mem[a] / mem[a] = v
    LDD(Arg.UINT),              // push data[block][pop()]
    SYS(Arg.UINT),              // builtin call
    ;

    companion object {
        val all = entries.toTypedArray()
        const val BITS = 6
        init { require(entries.size <= (1 shl BITS)) }
    }
}

/** Built-in functions. [args] values are popped (last arg on top), [ret] = pushes one result. */
enum class Builtin(val args: Int, val ret: Boolean, val doc: String) {
    SET(3, false, "set(x, y, tile) put a tile in a cell"),
    GET(2, true, "get(x, y) tile in a cell (outside the grid reads as WALL)"),
    FRAME(0, false, "frame() show the screen and wait one frame (1/30 s)"),
    RND(1, true, "rnd(n) random integer 0..n-1"),
    SETI(2, false, "seti(i, tile) set cell by index i = y*W+x"),
    GETI(1, true, "geti(i) tile at cell index i (outside reads as WALL)"),
    CLS(1, false, "cls(tile) fill the whole grid"),
    OVER(0, false, "over() game over: show score, tap to restart"),
    SFX(1, false, "sfx(n) play sound effect 0..7"),
    WAIT(1, false, "wait(n) show the screen and wait n frames"),
    HELD(1, true, "held(k) 1 while button k is held (RIGHT DOWN LEFT UP A B)"),
    PRESSED(1, true, "pressed(k) 1 if button k was pressed since the last frame"),
    SCORE(0, true, "score current score"),
    SETSCORE(1, false, "score = n"),
    DIR(0, true, "dir last direction pressed or swiped (0 right,1 down,2 left,3 up)"),
    SETDIR(1, false, "dir = d"),
    TAPX(0, true, "tapx column tapped this frame, -1 if none"),
    TAPY(0, true, "tapy row tapped this frame, -1 if none"),
    TICK(0, true, "tick frames since start"),
    W(0, true, "W grid width"),
    H(0, true, "H grid height"),
    FILL(5, false, "fill(x, y, w, h, tile) fill a rectangle"),
    TEXT(3, false, "text(x, y, \"STR\") draw text as glyph tiles"),
    NUM(3, false, "num(x, y, n) draw a number as glyph tiles"),
    MSG(1, false, "msg(\"STR\") centered banner over the grid, msg(-1) hides it"),
    WIN(0, false, "win() you-win screen, tap to restart"),
    GRID(2, false, "grid(w, h) set grid size (1..32), clears it"),
    RNDEMPTY(0, true, "rndempty() random empty cell index, -1 if full"),
    COUNT(1, true, "count(tile) number of cells holding tile"),
    FIND(1, true, "find(tile) index of first cell holding tile, -1 if none"),
    REPLACE(2, true, "replace(a, b) replace every a with b, returns count"),
    SCROLL(2, false, "scroll(dir, tile) shift grid one cell toward dir, fill gap with tile"),
    BEEP(2, false, "beep(note, frames) tone, note 0..63 semitones above C3"),
    TILE(3, false, "tile(id, data, color) define custom tile 48..63 from 8 rows of bits"),
    SPR(4, false, "spr(i, tile, x, y) sprite 0..15 at pixel x,y (8 px per cell); tile 0 hides"),
    XY(2, true, "xy(x, y) cell index y*W+x"),
    PX(1, true, "px(i) column of cell index"),
    PY(1, true, "py(i) row of cell index"),
    ABS(1, true, "abs(n)"),
    MIN(2, true, "min(a, b)"),
    MAX(2, true, "max(a, b)"),
    SGN(1, true, "sgn(n) -1, 0 or 1"),
    DX(1, true, "dx(d) x step for direction d"),
    DY(1, true, "dy(d) y step for direction d"),
    NBR(3, true, "nbr(x, y, tile) how many of the 8 neighbours hold tile"),
    INSIDE(2, true, "inside(x, y) 1 if the cell is on the grid"),
    ROWFULL(1, true, "rowfull(y) 1 if row y has no empty cell"),
    DELROW(1, false, "delrow(y) remove row y, rows above fall down"),
    CLAMP(3, true, "clamp(v, lo, hi)"),
    HISCORE(0, true, "hiscore best score saved for this game"),
    TAPPED(0, true, "tapped() 1 if the grid was tapped this frame"),
    KEY(0, true, "key bitmask of held buttons (1 right,2 down,4 left,8 up,16 A,32 B)"),
    FLOOD(3, true, "flood(x, y, tile) flood-fill the region at x,y with tile, returns cells changed"),
    LEN(1, true, "len(data) length of a data block"),
    TOUCHX(0, true, "touchx column under the finger while touching, -1 otherwise"),
    TOUCHY(0, true, "touchy row under the finger while touching, -1 otherwise"),
    ;

    val id get() = ordinal

    companion object {
        val all = entries.toTypedArray()
    }
}
