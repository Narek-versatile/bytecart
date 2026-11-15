package bytecart

class CompileError(val line: Int, val col: Int, msg: String) : Exception("$line:$col: $msg")

// ---------------------------------------------------------------- lexer

private enum class T { NUM, STR, ID, OP, NL, EOF }

private class Tok(val t: T, val s: String, val v: Int, val line: Int, val col: Int, val bytes: ByteArray? = null) {
    override fun toString() = when (t) { T.NL -> "newline"; T.EOF -> "end of file"; else -> "'$s'" }
}

private val OPS3 = setOf("<<=", ">>=")
private val OPS2 = setOf("==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "++", "--", "..")
private const val OPS1 = "+-*/%&|^~!<>=(){}[],;?:"

private fun lex(src: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0; var line = 1; var lineStart = 0; var depth = 0
    fun err(msg: String): Nothing = throw CompileError(line, i - lineStart + 1, msg)
    while (i < src.length) {
        val c = src[i]; val col = i - lineStart + 1
        when {
            c == '\n' -> {
                if (depth == 0 && out.isNotEmpty() && out.last().t != T.NL) out.add(Tok(T.NL, "\n", 0, line, col))
                i++; line++; lineStart = i
            }
            c == ' ' || c == '\t' || c == '\r' -> i++
            c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
            c == '/' && src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2); if (end < 0) err("unclosed comment")
                for (k in i until end) if (src[k] == '\n') { line++; lineStart = k + 1 }
                i = end + 2
            }
            c.isDigit() -> {
                val st = i
                val v: Long = if (src.startsWith("0x", i) || src.startsWith("0X", i)) {
                    i += 2; val s = i; while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    src.substring(s, i).replace("_", "").toLongOrNull(16) ?: err("bad hex number")
                } else if (src.startsWith("0b", i)) {
                    i += 2; val s = i; while (i < src.length && (src[i] == '0' || src[i] == '1' || src[i] == '_')) i++
                    src.substring(s, i).replace("_", "").toLongOrNull(2) ?: err("bad binary number")
                } else {
                    while (i < src.length && (src[i].isDigit() || src[i] == '_')) i++
                    src.substring(st, i).replace("_", "").toLongOrNull() ?: err("bad number")
                }
                if (v > 0xFFFFFFFFL) err("number too large")
                out.add(Tok(T.NUM, src.substring(st, i), v.toInt(), line, col))
            }
            c.isLetter() || c == '_' -> {
                val st = i; while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                out.add(Tok(T.ID, src.substring(st, i), 0, line, col))
            }
            c == '"' || c == '\'' -> {
                val q = c; i++
                val bytes = ArrayList<Byte>()
                while (true) {
                    if (i >= src.length || src[i] == '\n') err("unclosed string")
                    val ch = src[i]
                    if (ch == q) { i++; break }
                    if (ch == '\\') {
                        i++
                        when (val e = src.getOrNull(i) ?: err("bad escape")) {
                            'n' -> bytes.add(10); 't' -> bytes.add(9); '0' -> bytes.add(0)
                            '\\' -> bytes.add(92); '"' -> bytes.add(34); '\'' -> bytes.add(39)
                            'x' -> {
                                val h = src.substring(i + 1, minOf(i + 3, src.length)).toIntOrNull(16) ?: err("bad \\x escape")
                                bytes.add(h.toByte()); i += 2
                            }
                            else -> err("unknown escape \\$e")
                        }
                        i++
                    } else {
                        if (ch.code > 255) err("only Latin-1 characters are allowed in strings")
                        bytes.add(ch.code.toByte()); i++
                    }
                }
                val b = bytes.toByteArray()
                if (q == '\'') {
                    if (b.size != 1) err("character literal must hold one character")
                    out.add(Tok(T.NUM, "'", b[0].toInt() and 0xFF, line, col))
                } else out.add(Tok(T.STR, "\"", 0, line, col, b))
            }
            else -> {
                val s3 = if (i + 3 <= src.length) src.substring(i, i + 3) else ""
                val s2 = if (i + 2 <= src.length) src.substring(i, i + 2) else ""
                val s = when { s3 in OPS3 -> s3; s2 in OPS2 -> s2; c in OPS1 -> c.toString(); else -> err("unexpected character '$c'") }
                if (s == "(" || s == "[") depth++
                if ((s == ")" || s == "]") && depth > 0) depth--
                out.add(Tok(T.OP, s, 0, line, col)); i += s.length
            }
        }
    }
    out.add(Tok(T.EOF, "", 0, line, i - lineStart + 1))
    return out
}

// ---------------------------------------------------------------- AST

private sealed class E(val line: Int, val col: Int)
private class ENum(val v: Int, l: Int, c: Int) : E(l, c)
private class EStr(val bytes: ByteArray, l: Int, c: Int) : E(l, c)
private class EName(val name: String, l: Int, c: Int) : E(l, c)
private class ECall(val name: String, val args: List<E>, l: Int, c: Int) : E(l, c)
private class EIndex(val name: String, val index: E, l: Int, c: Int) : E(l, c)
private class EUn(val op: String, val e: E, l: Int, c: Int) : E(l, c)
private class EBin(val op: String, val a: E, val b: E, l: Int, c: Int) : E(l, c)
private class ECond(val c0: E, val a: E, val b: E, l: Int, c: Int) : E(l, c)

private sealed class S(val line: Int, val col: Int)
private class SVar(val names: List<Pair<String, E?>>, l: Int, c: Int) : S(l, c)
private class SAssign(val target: E, val op: String, val value: E?, l: Int, c: Int) : S(l, c) // op "=", "+=", "++"...
private class SExpr(val e: E, l: Int, c: Int) : S(l, c)
private class SIf(val cond: E, val then: List<S>, val els: List<S>?, l: Int, c: Int) : S(l, c)
private class SWhile(val cond: E?, val body: List<S>, l: Int, c: Int) : S(l, c)
private class SFor(val v: String, val from: E, val to: E, val body: List<S>, l: Int, c: Int) : S(l, c)
private class SJump(val kind: String, val value: E?, l: Int, c: Int) : S(l, c) // break continue return
private class SBlock(val body: List<S>, l: Int, c: Int) : S(l, c)

private class FnDecl(val name: String, val params: List<String>, val body: List<S>, val line: Int, val col: Int)

// ---------------------------------------------------------------- parser

private class Parser(val toks: List<Tok>) {
    var p = 0
    val consts = LinkedHashMap<String, E>()
    val datas = LinkedHashMap<String, ByteArray>()
    val fns = LinkedHashMap<String, FnDecl>()
    val main = ArrayList<S>()

    val peek get() = toks[p]
    fun err(t: Tok, msg: String): Nothing = throw CompileError(t.line, t.col, msg)
    fun isOp(s: String) = peek.t == T.OP && peek.s == s
    fun isId(s: String) = peek.t == T.ID && peek.s == s
    fun next() = toks[p++]
    fun expectOp(s: String): Tok { if (!isOp(s)) err(peek, "expected '$s' but found $peek"); return next() }
    fun ident(): Tok { if (peek.t != T.ID) err(peek, "expected a name but found $peek"); return next() }
    fun skipNl() { while (peek.t == T.NL || isOp(";")) p++ }
    fun endStmt() {
        when {
            peek.t == T.NL || isOp(";") -> skipNl()
            isOp("}") || peek.t == T.EOF -> {}
            p > 0 && toks[p - 1].t == T.OP && toks[p - 1].s == "}" -> {}
            else -> err(peek, "expected end of statement but found $peek")
        }
    }

    fun parseProgram() {
        skipNl()
        while (peek.t != T.EOF) {
            val t = peek
            when {
                isId("fn") -> {
                    next(); val name = ident()
                    if (name.s in fns) err(name, "function '${name.s}' defined twice")
                    expectOp("(")
                    val params = ArrayList<String>()
                    while (!isOp(")")) { params.add(ident().s); if (!isOp(")")) expectOp(",") }
                    expectOp(")")
                    fns[name.s] = FnDecl(name.s, params, block(), t.line, t.col)
                    endStmt()
                }
                isId("const") -> {
                    next()
                    do {
                        val n = ident(); expectOp("=")
                        if (n.s in consts) err(n, "constant '${n.s}' defined twice")
                        consts[n.s] = expr()
                    } while (isOp(",").also { if (it) next() })
                    endStmt()
                }
                isId("data") -> {
                    next(); val n = ident(); expectOp("=")
                    if (n.s in datas) err(n, "data '${n.s}' defined twice")
                    datas[n.s] = dataLiteral(); endStmt()
                }
                else -> main.add(stmt())
            }
            skipNl()
        }
    }

    fun dataLiteral(): ByteArray {
        val out = ArrayList<Byte>()
        fun item() {
            val t = peek
            when {
                t.t == T.STR -> { next(); t.bytes!!.forEach { out.add(it) } }
                else -> {
                    val e = expr()
                    val v = (e as? ENum)?.v ?: foldConst(e) ?: err(t, "data items must be constants")
                    out.add(v.toByte())
                }
            }
        }
        if (isOp("[")) {
            next()
            while (!isOp("]")) { item(); if (!isOp("]")) expectOp(",") }
            next()
        } else {
            if (peek.t != T.STR) err(peek, "expected a string or [list]")
            while (peek.t == T.STR) item()
        }
        return out.toByteArray()
    }

    fun foldConst(e: E): Int? = Folder(consts).fold(e)

    fun block(): List<S> {
        expectOp("{"); skipNl()
        val out = ArrayList<S>()
        while (!isOp("}")) {
            if (peek.t == T.EOF) err(peek, "missing '}'")
            out.add(stmt()); skipNl()
        }
        next()
        return out
    }

    fun stmt(): S {
        val t = peek
        if (t.t == T.ID) when (t.s) {
            "var" -> {
                next()
                val names = ArrayList<Pair<String, E?>>()
                do {
                    val n = ident()
                    val init = if (isOp("=")) { next(); expr() } else null
                    names.add(n.s to init)
                } while (isOp(",").also { if (it) next() })
                endStmt(); return SVar(names, t.line, t.col)
            }
            "if" -> return ifStmt()
            "while" -> { next(); val c = expr(); val b = block(); endStmt(); return SWhile(c, b, t.line, t.col) }
            "loop" -> { next(); val b = block(); endStmt(); return SWhile(null, b, t.line, t.col) }
            "for" -> {
                next(); val v = ident()
                if (!isId("in")) err(peek, "expected 'in'")
                next(); val a = expr(); expectOp(".."); val b = expr()
                val body = block(); endStmt()
                return SFor(v.s, a, b, body, t.line, t.col)
            }
            "break", "continue" -> { next(); endStmt(); return SJump(t.s, null, t.line, t.col) }
            "return" -> {
                next()
                val v = if (peek.t == T.NL || isOp(";") || isOp("}") || peek.t == T.EOF) null else expr()
                endStmt(); return SJump("return", v, t.line, t.col)
            }
            "fn", "const", "data" -> err(t, "'${t.s}' is only allowed at the top level")
        }
        if (isOp("{")) { val b = block(); endStmt(); return SBlock(b, t.line, t.col) }
        val e = expr()
        val op = peek
        if (op.t == T.OP && op.s in setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")) {
            next(); skipNlInExpr()
            val v = expr(); checkLvalue(e); endStmt()
            return SAssign(e, op.s, v, t.line, t.col)
        }
        if (op.t == T.OP && (op.s == "++" || op.s == "--")) {
            next(); checkLvalue(e); endStmt()
            return SAssign(e, op.s, null, t.line, t.col)
        }
        endStmt()
        return SExpr(e, t.line, t.col)
    }

    fun checkLvalue(e: E) {
        if (e !is EName && !(e is EIndex && e.name == "mem")) throw CompileError(e.line, e.col, "can only assign to a variable or mem[...]")
    }

    fun ifStmt(): S {
        val t = next()
        val c = expr(); val then = block()
        var els: List<S>? = null
        val save = p
        skipNl()
        if (isId("else")) {
            next()
            els = if (isId("if")) listOf(ifStmt()) else { val b = block(); endStmt(); b }
            return SIf(c, then, els, t.line, t.col)
        }
        p = save
        endStmt()
        return SIf(c, then, els, t.line, t.col)
    }

    fun skipNlInExpr() { while (peek.t == T.NL) p++ }

    // precedence climbing
    private val levels = listOf(
        listOf("||"), listOf("&&"), listOf("|"), listOf("^"), listOf("&"),
        listOf("==", "!="), listOf("<", "<=", ">", ">="), listOf("<<", ">>"),
        listOf("+", "-"), listOf("*", "/", "%"),
    )

    fun expr(): E {
        val c = binary(0)
        if (isOp("?")) {
            val t = next(); skipNlInExpr()
            val a = expr(); skipNlInExpr(); expectOp(":"); skipNlInExpr()
            val b = expr()
            return ECond(c, a, b, t.line, t.col)
        }
        return c
    }

    fun binary(level: Int): E {
        if (level == levels.size) return unary()
        var a = binary(level + 1)
        while (peek.t == T.OP && peek.s in levels[level]) {
            val t = next(); skipNlInExpr()
            val b = binary(level + 1)
            a = EBin(t.s, a, b, t.line, t.col)
        }
        return a
    }

    fun unary(): E {
        val t = peek
        if (t.t == T.OP && (t.s == "-" || t.s == "!" || t.s == "~")) { next(); return EUn(t.s, unary(), t.line, t.col) }
        return postfix()
    }

    fun postfix(): E {
        val t = next()
        return when (t.t) {
            T.NUM -> ENum(t.v, t.line, t.col)
            T.STR -> {
                var b = t.bytes!!
                while (peek.t == T.STR) b += next().bytes!!
                EStr(b, t.line, t.col)
            }
            T.ID -> when {
                isOp("(") -> {
                    next()
                    val args = ArrayList<E>()
                    while (!isOp(")")) { args.add(expr()); if (!isOp(")")) expectOp(",") }
                    next()
                    ECall(t.s, args, t.line, t.col)
                }
                isOp("[") -> { next(); val i = expr(); expectOp("]"); EIndex(t.s, i, t.line, t.col) }
                else -> EName(t.s, t.line, t.col)
            }
            T.OP -> if (t.s == "(") { val e = expr(); expectOp(")"); e } else err(t, "unexpected $t")
            else -> err(t, "unexpected $t")
        }
    }
}

/** Compile-time evaluation shared by the parser and code generator. Same semantics as the VM. */
private class Folder(val consts: Map<String, E>) {
    private val visiting = HashSet<String>()
    fun fold(e: E): Int? = when (e) {
        is ENum -> e.v
        is EName -> consts[e.name]?.let {
            if (!visiting.add(e.name)) throw CompileError(e.line, e.col, "constant '${e.name}' refers to itself")
            val r = fold(it); visiting.remove(e.name); r
        } ?: Tiles.constants[e.name] ?: BUILTIN_CONSTS[e.name]
        is EUn -> fold(e.e)?.let { Alu.unary(e.op, it) }
        is EBin -> {
            val a = fold(e.a); val b = fold(e.b)
            if (a != null && b != null) Alu.binary(e.op, a, b) else null
        }
        is ECond -> fold(e.c0)?.let { if (it != 0) fold(e.a) else fold(e.b) }
        else -> null
    }
}

internal val BUILTIN_CONSTS = mapOf("RIGHT" to 0, "DOWN" to 1, "LEFT" to 2, "UP" to 3, "A" to 4, "B" to 5, "true" to 1, "false" to 0)

/** Integer semantics used by both the VM and the constant folder. */
object Alu {
    fun binary(op: String, a: Int, b: Int): Int = when (op) {
        "+" -> a + b; "-" -> a - b; "*" -> a * b
        "/" -> if (b == 0) 0 else if (a == Int.MIN_VALUE && b == -1) a else a / b
        "%" -> if (b == 0) 0 else Math2.mod(a, b)
        "&" -> a and b; "|" -> a or b; "^" -> a xor b
        "<<" -> a shl (b and 31); ">>" -> a shr (b and 31)
        "==" -> b2i(a == b); "!=" -> b2i(a != b); "<" -> b2i(a < b); "<=" -> b2i(a <= b)
        ">" -> b2i(a > b); ">=" -> b2i(a >= b)
        "&&" -> if (a != 0) b else 0
        "||" -> if (a != 0) a else b
        else -> error(op)
    }

    fun unary(op: String, a: Int) = when (op) { "-" -> -a; "!" -> b2i(a == 0); "~" -> a.inv(); else -> error(op) }
    fun b2i(b: Boolean) = if (b) 1 else 0
}

object Math2 {
    /** Floored modulo: result has the sign of the divisor, so (-1) % 16 == 15 (handy for wrap-around). */
    fun mod(a: Int, b: Int): Int { val r = a % b; return if (r != 0 && (r xor b) < 0) r + b else r }
}

// ---------------------------------------------------------------- code generation

private class Label { var at = -1 }

private class Ins(val op: Op, var a: Int = 0, var b: Int = 0, val label: Label? = null) {
    var isDataRef = false   // LIT holding a data-block id (renumbered at the end)
}

class CompileResult(val program: Program, val warnings: List<String>)

object Compiler {
    private val SPECIAL_GET = mapOf(
        "score" to Builtin.SCORE, "dir" to Builtin.DIR, "tapx" to Builtin.TAPX, "tapy" to Builtin.TAPY,
        "tick" to Builtin.TICK, "W" to Builtin.W, "H" to Builtin.H, "hiscore" to Builtin.HISCORE, "key" to Builtin.KEY,
        "touchx" to Builtin.TOUCHX, "touchy" to Builtin.TOUCHY,
    )
    private val SPECIAL_SET = mapOf("score" to Builtin.SETSCORE, "dir" to Builtin.SETDIR)
    private val SPECIAL_IDS = setOf(Builtin.SCORE, Builtin.SETSCORE, Builtin.DIR, Builtin.SETDIR, Builtin.TAPX, Builtin.TAPY,
        Builtin.TICK, Builtin.W, Builtin.H, Builtin.HISCORE, Builtin.KEY, Builtin.TOUCHX, Builtin.TOUCHY)
    private val BUILTINS = Builtin.all.filter { it !in SPECIAL_IDS }.associateBy { it.name.lowercase() }
    private val RESERVED = setOf("var", "fn", "const", "data", "if", "else", "while", "loop", "for", "in", "break", "continue", "return", "mem")

    fun compile(source: String): CompileResult {
        val parser = Parser(lex(source))
        parser.parseProgram()
        return Gen(parser).run()
    }

    private class FnInfo(val decl: FnDecl, val index: Int) { var calls = 0 }

    private class Gen(val ps: Parser) {
        val warnings = ArrayList<String>()
        val folder = Folder(ps.consts)
        val globals = LinkedHashMap<String, Int>()
        val fns = LinkedHashMap<String, FnInfo>()
        val dataIds = LinkedHashMap<String, Int>()
        val dataBlocks = ArrayList<ByteArray>()
        // per-function state
        var locals: MutableMap<String, Int>? = null
        var nlocals = 0
        val loops = ArrayList<Pair<Label, Label>>() // continue, break
        var out = ArrayList<Ins>()

        fun err(line: Int, col: Int, msg: String): Nothing = throw CompileError(line, col, msg)

        fun run(): CompileResult {
            for ((n, b) in ps.datas) { dataIds[n] = dataBlocks.size; dataBlocks.add(b) }
            ps.fns.values.forEachIndexed { i, f -> fns[f.name] = FnInfo(f, i) }
            for (n in ps.consts.keys + ps.datas.keys + ps.fns.keys) {
                if (n in RESERVED || n in BUILTINS || n in SPECIAL_GET) err(0, 0, "'$n' is a reserved name")
            }
            // main
            val mainCode = ArrayList<Ins>(); out = mainCode
            stmts(ps.main)
            emit(Op.HALT)
            // functions
            val bodies = HashMap<String, List<Ins>>()
            for (f in fns.values) {
                val code = ArrayList<Ins>(); out = code
                locals = LinkedHashMap(); nlocals = 0
                for (prm in f.decl.params) {
                    if (prm in locals!!) err(f.decl.line, f.decl.col, "duplicate parameter '$prm'")
                    locals!![prm] = nlocals++
                }
                val head = Ins(Op.FUNC, f.decl.params.size, 0); out.add(head)
                stmts(f.decl.body)
                if (out.lastOrNull()?.op != Op.RET && out.lastOrNull()?.op != Op.RET0) emit(Op.RET0)
                head.b = nlocals - f.decl.params.size
                renumberLocals(code, f.decl.params.size)
                bodies[f.decl.name] = code
                locals = null
            }
            for (f in fns.values) if (f.calls == 0) warnings.add("function '${f.decl.name}' is never called")
            // order functions by call count so frequent calls get small numbers
            val order = fns.values.sortedWith(compareByDescending<FnInfo> { it.calls }.thenBy { it.index }).filter { it.calls > 0 }
            val fnRemap = HashMap<Int, Int>()
            order.forEachIndexed { k, f -> fnRemap[f.index] = k }
            val all = ArrayList<Ins>(mainCode)
            for (f in order) all.addAll(bodies[f.decl.name]!!)
            for (ins in all) if (ins.op == Op.CALL) ins.a = fnRemap[ins.a]!!
            return CompileResult(finish(all), warnings)
        }

        fun renumberLocals(code: List<Ins>, nargs: Int) {
            val counts = IntArray(nlocals)
            for (i in code) if (i.op in LOCAL_OPS) counts[i.a]++
            val extra = (nargs until nlocals).sortedByDescending { counts[it] }
            val map = IntArray(nlocals) { it }
            extra.forEachIndexed { k, old -> map[old] = nargs + k }
            for (i in code) if (i.op in LOCAL_OPS) i.a = map[i.a]
        }

        fun finish(all: List<Ins>): Program {
            // globals by use count
            val gCount = IntArray(globals.size)
            for (i in all) if (i.op in GLOBAL_OPS) gCount[i.a]++
            val gOrder = gCount.indices.sortedByDescending { gCount[it] }
            val gMap = IntArray(globals.size); gOrder.forEachIndexed { k, old -> gMap[old] = k }
            // data blocks by use count, unused dropped
            val dCount = IntArray(dataBlocks.size)
            for (i in all) if (i.op == Op.LDD) dCount[i.a]++ else if (i.isDataRef) dCount[i.a]++
            val dOrder = dCount.indices.filter { dCount[it] > 0 }.sortedByDescending { dCount[it] }
            val dMap = IntArray(dataBlocks.size) { -1 }; dOrder.forEachIndexed { k, old -> dMap[old] = k }
            // label resolution: instruction numbers
            val labelAt = HashMap<Label, Int>()
            val real = ArrayList<Ins>()
            for (i in all) {
                if (i.op == Op.END) { labelAt[i.label!!] = real.size; continue } // END used as label marker
                real.add(i)
            }
            val code = ArrayList<Int>()
            for (i in real) {
                code.add(i.op.ordinal)
                when {
                    i.op in GLOBAL_OPS -> code.add(gMap[i.a])
                    i.op == Op.LDD -> code.add(dMap[i.a])
                    i.op == Op.LIT && i.isDataRef -> code.add(dMap[i.a])
                    i.op.args.firstOrNull() == Arg.JUMP -> code.add(labelAt[i.label!!]!!)
                    else -> { if (i.op.args.isNotEmpty()) code.add(i.a); if (i.op.args.size > 1) code.add(i.b) }
                }
            }
            return Program(code.toIntArray(), dOrder.map { dataBlocks[it] })
        }

        fun emit(op: Op, a: Int = 0, b: Int = 0) { out.add(Ins(op, a, b)) }
        fun jump(op: Op, l: Label) { out.add(Ins(op, label = l)) }
        fun place(l: Label) { out.add(Ins(Op.END, label = l)) }

        fun stmts(list: List<S>) { for (s in list) stmt(s) }

        fun declare(name: String, line: Int, col: Int): Pair<Boolean, Int> {
            if (name in RESERVED || name in BUILTINS || name in SPECIAL_GET || name in ps.consts || name in dataIds ||
                Tiles.constants.containsKey(name) || BUILTIN_CONSTS.containsKey(name)) err(line, col, "'$name' is a reserved or already used name")
            val l = locals
            return if (l != null) {
                if (name in l) err(line, col, "variable '$name' already declared")
                l[name] = nlocals++; true to l[name]!!
            } else {
                if (name in globals) err(line, col, "variable '$name' already declared")
                globals[name] = globals.size; false to globals[name]!!
            }
        }

        fun store(isLocal: Boolean, idx: Int) = emit(if (isLocal) Op.STL else Op.ST, idx)

        fun stmt(s: S) {
            when (s) {
                is SVar -> for ((n, init) in s.names) {
                    val (loc, idx) = declare(n, s.line, s.col)
                    if (init != null) { expr(init); store(loc, idx) }
                    else if (loc) { emit(Op.LIT, 0); store(true, idx) } // locals are reused between calls
                }
                is SAssign -> assign(s)
                is SExpr -> {
                    val e = s.e
                    if (e !is ECall) warnings.add("${s.line}:${s.col}: expression result is unused")
                    if (expr(e)) emit(Op.DROP)
                }
                is SIf -> {
                    val els = Label(); val end = Label()
                    condJump(s.cond, els, false)
                    stmts(s.then)
                    if (s.els != null) { jump(Op.JMP, end); place(els); stmts(s.els); place(end) } else place(els)
                }
                is SWhile -> {
                    val top = Label(); val end = Label()
                    place(top)
                    if (s.cond != null) condJump(s.cond, end, false)
                    loops.add(top to end); stmts(s.body); loops.removeAt(loops.size - 1)
                    jump(Op.JMP, top); place(end)
                }
                is SFor -> {
                    val v = lookupVar(s.v) ?: declare(s.v, s.line, s.col)
                    expr(s.from); store(v.first, v.second)
                    val top = Label(); val cont = Label(); val end = Label()
                    place(top)
                    condJump(EBin("<", EName(s.v, s.line, s.col), s.to, s.line, s.col), end, false)
                    loops.add(cont to end); stmts(s.body); loops.removeAt(loops.size - 1)
                    place(cont)
                    emit(if (v.first) Op.INCL else Op.INCV, v.second)
                    jump(Op.JMP, top); place(end)
                }
                is SJump -> when (s.kind) {
                    "break" -> jump(Op.JMP, (loops.lastOrNull() ?: err(s.line, s.col, "break outside a loop")).second)
                    "continue" -> jump(Op.JMP, (loops.lastOrNull() ?: err(s.line, s.col, "continue outside a loop")).first)
                    else -> {
                        if (locals == null) { emit(Op.HALT); return }
                        if (s.value == null || folder.fold(s.value) == 0) emit(Op.RET0) else { expr(s.value); emit(Op.RET) }
                    }
                }
                is SBlock -> stmts(s.body)
            }
        }

        fun lookupVar(name: String): Pair<Boolean, Int>? {
            locals?.get(name)?.let { return true to it }
            globals[name]?.let { return false to it }
            return null
        }

        fun assign(s: SAssign) {
            val t = s.target
            if (t is EIndex) { // mem[i]
                expr(t.index)
                if (s.op == "=") { expr(s.value!!) }
                else {
                    emit(Op.DUP); emit(Op.LDM)
                    if (s.op == "++" || s.op == "--") emit(Op.LIT, 1) else expr(s.value!!)
                    binop(opOf(s.op), t.line, t.col)
                }
                emit(Op.STM); return
            }
            val name = (t as EName).name
            val v = lookupVar(name)
            if (v == null) {
                val set = SPECIAL_SET[name] ?: err(t.line, t.col, if (name in SPECIAL_GET) "'$name' is read-only" else "unknown variable '$name' (declare it with var)")
                if (s.op == "=") expr(s.value!!) else {
                    emit(Op.SYS, SPECIAL_GET[name]!!.id)
                    if (s.op == "++" || s.op == "--") emit(Op.LIT, 1) else expr(s.value!!)
                    binop(opOf(s.op), t.line, t.col)
                }
                emit(Op.SYS, set.id); return
            }
            val (loc, idx) = v
            when (s.op) {
                "=" -> { expr(s.value!!); store(loc, idx) }
                "++" -> emit(if (loc) Op.INCL else Op.INCV, idx)
                "--" -> emit(if (loc) Op.DECL else Op.DECV, idx)
                "+=", "-=" -> {
                    val c = folder.fold(s.value!!)
                    val neg = s.op == "-="
                    when {
                        c != null && (if (neg) -c else c) == 1 -> emit(if (loc) Op.INCL else Op.INCV, idx)
                        c != null && (if (neg) -c else c) == -1 -> emit(if (loc) Op.DECL else Op.DECV, idx)
                        c != null -> { emit(Op.LIT, if (neg) -c else c); emit(if (loc) Op.ADDL else Op.ADDV, idx) }
                        else -> { expr(s.value); if (neg) emit(Op.NEG); emit(if (loc) Op.ADDL else Op.ADDV, idx) }
                    }
                }
                else -> {
                    emit(if (loc) Op.LDL else Op.LD, idx); expr(s.value!!)
                    binop(opOf(s.op), t.line, t.col); store(loc, idx)
                }
            }
        }

        fun opOf(assignOp: String) = when (assignOp) { "++" -> "+"; "--" -> "-"; else -> assignOp.dropLast(1) }

        fun binop(op: String, line: Int, col: Int) = emit(
            when (op) {
                "+" -> Op.ADD; "-" -> Op.SUB; "*" -> Op.MUL; "/" -> Op.DIV; "%" -> Op.MOD
                "&" -> Op.AND; "|" -> Op.OR; "^" -> Op.XOR; "<<" -> Op.SHL; ">>" -> Op.SHR
                "==" -> Op.EQ; "!=" -> Op.NE; "<" -> Op.LT; "<=" -> Op.LE; ">" -> Op.GT; ">=" -> Op.GE
                else -> err(line, col, "bad operator $op")
            }
        )

        /** Jump to [target] when [e] is false (or true when [onTrue]). */
        fun condJump(e: E, target: Label, onTrue: Boolean) {
            val c = folder.fold(e)
            if (c != null) { if ((c != 0) == onTrue) jump(Op.JMP, target); return }
            when {
                e is EUn && e.op == "!" -> condJump(e.e, target, !onTrue)
                e is EBin && e.op == "&&" -> if (!onTrue) { condJump(e.a, target, false); condJump(e.b, target, false) }
                    else { val skip = Label(); condJump(e.a, skip, false); condJump(e.b, target, true); place(skip) }
                e is EBin && e.op == "||" -> if (onTrue) { condJump(e.a, target, true); condJump(e.b, target, true) }
                    else { val skip = Label(); condJump(e.a, skip, true); condJump(e.b, target, false); place(skip) }
                e is EBin && e.op == "!=" && folder.fold(e.b) == 0 -> { expr(e.a); jump(if (onTrue) Op.JNZ else Op.JZ, target) }
                e is EBin && e.op == "==" && folder.fold(e.b) == 0 -> { expr(e.a); jump(if (onTrue) Op.JZ else Op.JNZ, target) }
                else -> { expr(e); jump(if (onTrue) Op.JNZ else Op.JZ, target) }
            }
        }

        fun dataRef(id: Int) { out.add(Ins(Op.LIT, id).also { it.isDataRef = true }) }

        fun stringBlock(b: ByteArray): Int {
            val i = dataBlocks.indexOfFirst { it.contentEquals(b) }
            if (i >= 0) return i
            dataBlocks.add(b); return dataBlocks.size - 1
        }

        /** Emit code for [e]; returns true if it left a value on the stack. */
        fun expr(e: E): Boolean {
            folder.fold(e)?.let { emit(Op.LIT, it); return true }
            when (e) {
                is ENum -> emit(Op.LIT, e.v)
                is EStr -> dataRef(stringBlock(e.bytes))
                is EName -> {
                    val v = lookupVar(e.name)
                    when {
                        v != null -> emit(if (v.first) Op.LDL else Op.LD, v.second)
                        e.name in dataIds -> dataRef(dataIds[e.name]!!)
                        e.name in SPECIAL_GET -> emit(Op.SYS, SPECIAL_GET[e.name]!!.id)
                        e.name in fns || e.name in BUILTINS -> err(e.line, e.col, "'${e.name}' is a function, call it with ()")
                        else -> err(e.line, e.col, "unknown name '${e.name}'")
                    }
                }
                is EIndex -> {
                    if (e.name == "mem") { expr(e.index); emit(Op.LDM) }
                    else {
                        val id = dataIds[e.name] ?: err(e.line, e.col, "'${e.name}' is not a data block or mem")
                        expr(e.index); out.add(Ins(Op.LDD, id))
                    }
                }
                is EUn -> {
                    expr(e.e)
                    emit(when (e.op) { "-" -> Op.NEG; "!" -> Op.LNOT; else -> Op.BNOT })
                }
                is EBin -> when (e.op) {
                    "&&" -> { val end = Label(); expr(e.a); emit(Op.DUP); jump(Op.JZ, end); emit(Op.DROP); expr(e.b); place(end) }
                    "||" -> { val end = Label(); expr(e.a); emit(Op.DUP); jump(Op.JNZ, end); emit(Op.DROP); expr(e.b); place(end) }
                    else -> { expr(e.a); expr(e.b); binop(e.op, e.line, e.col) }
                }
                is ECond -> {
                    val els = Label(); val end = Label()
                    condJump(e.c0, els, false); expr(e.a); jump(Op.JMP, end); place(els); expr(e.b); place(end)
                }
                is ECall -> return call(e)
            }
            return true
        }

        fun call(e: ECall): Boolean {
            fns[e.name]?.let { f ->
                if (e.args.size != f.decl.params.size) err(e.line, e.col, "'${e.name}' takes ${f.decl.params.size} arguments, got ${e.args.size}")
                for (a in e.args) if (!expr(a)) err(a.line, a.col, "argument has no value")
                f.calls++
                emit(Op.CALL, f.index); return true
            }
            if (lookupVar(e.name) != null) err(e.line, e.col, "'${e.name}' is a variable, not a function")
            val b = BUILTINS[e.name] ?: err(e.line, e.col, "unknown function '${e.name}'")
            if (e.args.size != b.args) err(e.line, e.col, "'${e.name}' takes ${b.args} arguments, got ${e.args.size}")
            if (b == Builtin.LEN) {
                val n = (e.args[0] as? EName)?.name
                val id = dataIds[n] ?: err(e.line, e.col, "len() needs a data block name")
                emit(Op.LIT, dataBlocks[id].size); return true
            }
            for (a in e.args) if (!expr(a)) err(a.line, a.col, "argument has no value")
            emit(Op.SYS, b.id)
            return b.ret
        }
    }

    private val LOCAL_OPS = setOf(Op.LDL, Op.STL, Op.ADDL, Op.INCL, Op.DECL)
    private val GLOBAL_OPS = setOf(Op.LD, Op.ST, Op.ADDV, Op.INCV, Op.DECV)
}
