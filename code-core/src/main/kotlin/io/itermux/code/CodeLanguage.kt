package io.itermux.code

import java.util.Locale
import java.util.regex.Pattern

enum class TokenType {
    KEYWORD, TYPE, STRING, NUMBER, COMMENT, FUNCTION, PROPERTY, VARIABLE, CONSTANT, META,
    TAG, ATTRIBUTE, PUNCTUATION, HEADING, STRONG, EMPHASIS, LINK, INSERTED, DELETED, ERROR, WARNING, INFO
}

class StringRule(val open: String, val close: String = open, val escape: Char? = '\\',
    val multiline: Boolean = false, val maxLength: Int = Int.MAX_VALUE)

/** Matched with lookingAt() from the start of each line; listed groups become tokens. */
class LineRule(regex: String, vararg val groups: Pair<Int, TokenType>) {
    val pattern: Pattern = Pattern.compile(regex)
}

/** Tried at any token boundary whose first character passes [trigger]. */
class TokenPattern(regex: String, val type: TokenType, val trigger: (Char) -> Boolean) {
    val pattern: Pattern = Pattern.compile(regex)
}

enum class LanguageMode { PLAIN, CODE, MARKUP, MARKDOWN }

class CodeLanguage(
    val id: String,
    val displayName: String,
    val mode: LanguageMode = LanguageMode.CODE,
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val strings: List<StringRule> = emptyList(),
    val words: Map<String, TokenType> = emptyMap(),
    val caseInsensitive: Boolean = false,
    /** A word right after one of these keywords names a function or type. */
    val declarators: Map<String, TokenType> = emptyMap(),
    val annotationPrefix: Char? = null,
    val variablePrefixes: String = "",
    /** Make-style `$(NAME)` is one variable; shell `$(command)` is not. */
    val parenVariables: Boolean = false,
    val preprocessor: Boolean = false,
    /** Shell-style comments start only at a word boundary (`a#b` is not a comment). */
    val commentNeedsBoundary: Boolean = false,
    val capitalizedTypes: Boolean = false,
    /** JSON-style `"key":` strings are properties. */
    val keyStrings: Boolean = false,
    /** Characters like `r`, `f`, `b` that may prefix a string literal. */
    val stringPrefixes: String = "",
    val macroBang: Boolean = false,
    val identifierExtra: String = "_",
    val numbers: Boolean = true,
    /** CSS: words outside braces are selectors, `name:` inside braces are properties. */
    val css: Boolean = false,
    /** Words like `import` whose dotted path to the end of the line stays plain text. */
    val pathKeywords: Set<String> = emptySet(),
    val lineRules: List<LineRule> = emptyList(),
    val patterns: List<TokenPattern> = emptyList()
) {
    fun classify(word: String): TokenType? = words[if (caseInsensitive) word.lowercase(Locale.ROOT) else word]
}

private fun wordMap(caseInsensitive: Boolean = false, vararg groups: Pair<TokenType, String>): Map<String, TokenType> =
    buildMap {
        groups.forEach { (type, list) ->
            list.split(' ').filter { it.isNotEmpty() }.forEach { put(if (caseInsensitive) it.lowercase(Locale.ROOT) else it, type) }
        }
    }

private val cStrings = listOf(StringRule("\""), StringRule("'", maxLength = 12))
private val slashComments = listOf("//")
private val cBlock = listOf("/*" to "*/")
private val hashComment = listOf("#")
private val declare = mapOf("class" to TokenType.TYPE, "interface" to TokenType.TYPE, "struct" to TokenType.TYPE,
    "enum" to TokenType.TYPE, "trait" to TokenType.TYPE, "object" to TokenType.TYPE, "record" to TokenType.TYPE,
    "protocol" to TokenType.TYPE, "typealias" to TokenType.TYPE, "fun" to TokenType.FUNCTION,
    "function" to TokenType.FUNCTION, "func" to TokenType.FUNCTION, "fn" to TokenType.FUNCTION, "def" to TokenType.FUNCTION)
private val url = TokenPattern("https?://[^\\s\"'<>()\\[\\]]+", TokenType.LINK) { it == 'h' }

object Languages {
    val PLAIN = CodeLanguage("plaintext", "Plain Text", LanguageMode.PLAIN)

    val KOTLIN = CodeLanguage("kotlin", "Kotlin", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\"\"\"", escape = null, multiline = true)) + cStrings,
        words = wordMap(false, TokenType.KEYWORD to "package import class interface fun val var object typealias if else when for while do return break continue throw try catch finally in is as by get set constructor init companion data sealed enum annotation open abstract override private protected public internal lateinit inline noinline crossinline reified suspend operator infix tailrec external const vararg where out actual expect value this super it",
            TokenType.CONSTANT to "true false null"),
        pathKeywords = setOf("package", "import"), declarators = declare, annotationPrefix = '@', capitalizedTypes = true)

    val JAVA = CodeLanguage("java", "Java", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\"\"\"", escape = null, multiline = true)) + cStrings,
        words = wordMap(false, TokenType.KEYWORD to "abstract assert break case catch class continue default do else enum extends final finally for if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record yield sealed permits",
            TokenType.TYPE to "void boolean byte char double float int long short",
            TokenType.CONSTANT to "true false null"),
        pathKeywords = setOf("package", "import"), declarators = declare, annotationPrefix = '@', capitalizedTypes = true, identifierExtra = "_$")

    private const val JS_KEYWORDS = "break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while with yield async await get set as"
    val JAVASCRIPT = CodeLanguage("javascript", "JavaScript", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\""), StringRule("'"), StringRule("`", multiline = true)),
        words = wordMap(false, TokenType.KEYWORD to JS_KEYWORDS, TokenType.CONSTANT to "true false null undefined NaN Infinity"),
        declarators = declare, annotationPrefix = '@', capitalizedTypes = true, identifierExtra = "_$")

    val TYPESCRIPT = CodeLanguage("typescript", "TypeScript", lineComments = slashComments, blockComments = cBlock,
        strings = JAVASCRIPT.strings,
        words = wordMap(false, TokenType.KEYWORD to "$JS_KEYWORDS interface type enum implements namespace declare abstract private protected public readonly keyof infer is satisfies module",
            TokenType.TYPE to "string number boolean any unknown never object symbol bigint",
            TokenType.CONSTANT to "true false null undefined NaN Infinity"),
        declarators = declare + ("type" to TokenType.TYPE), annotationPrefix = '@', capitalizedTypes = true, identifierExtra = "_$")

    val PYTHON = CodeLanguage("python", "Python", lineComments = hashComment,
        strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("'''", multiline = true), StringRule("\""), StringRule("'")),
        words = wordMap(false, TokenType.KEYWORD to "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case",
            TokenType.TYPE to "int float str bool list dict set tuple bytes object type",
            TokenType.CONSTANT to "True False None self cls"),
        declarators = declare, annotationPrefix = '@', stringPrefixes = "rRbBfFuU", capitalizedTypes = true)

    val GO = CodeLanguage("go", "Go", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\""), StringRule("`", escape = null, multiline = true), StringRule("'", maxLength = 12)),
        words = wordMap(false, TokenType.KEYWORD to "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var",
            TokenType.TYPE to "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any",
            TokenType.CONSTANT to "true false nil iota"),
        declarators = declare + ("type" to TokenType.TYPE))

    val RUST = CodeLanguage("rust", "Rust", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\"", multiline = true), StringRule("'", maxLength = 10)),
        words = wordMap(false, TokenType.KEYWORD to "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self static struct super trait type unsafe use where while",
            TokenType.TYPE to "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str Self",
            TokenType.CONSTANT to "true false None Some Ok Err"),
        declarators = declare + ("impl" to TokenType.TYPE) + ("type" to TokenType.TYPE), macroBang = true, capitalizedTypes = true,
        patterns = listOf(TokenPattern("#!?\\[[^\\]\\n]*\\]", TokenType.META) { it == '#' }))

    private const val C_KEYWORDS = "auto break case const continue default do else enum extern for goto if inline register restrict return signed sizeof static struct switch typedef union unsigned volatile while"
    private const val C_TYPES = "char double float int long short void bool size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t FILE"
    val C = CodeLanguage("c", "C", lineComments = slashComments, blockComments = cBlock, strings = cStrings,
        words = wordMap(false, TokenType.KEYWORD to C_KEYWORDS, TokenType.TYPE to C_TYPES, TokenType.CONSTANT to "true false NULL"),
        declarators = declare, preprocessor = true)

    val CPP = CodeLanguage("cpp", "C++", lineComments = slashComments, blockComments = cBlock, strings = cStrings,
        words = wordMap(false, TokenType.KEYWORD to "$C_KEYWORDS alignas alignof and asm catch class constexpr const_cast decltype delete dynamic_cast explicit export friend mutable namespace new noexcept operator or private protected public reinterpret_cast static_assert static_cast template this thread_local throw try typeid typename using virtual override final concept requires co_await co_return co_yield",
            TokenType.TYPE to "$C_TYPES string vector map std", TokenType.CONSTANT to "true false NULL nullptr"),
        declarators = declare, preprocessor = true)

    val CSHARP = CodeLanguage("csharp", "C#", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("@\"", escape = null, multiline = true)) + cStrings,
        words = wordMap(false, TokenType.KEYWORD to "abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await var get set init record yield when where",
            TokenType.TYPE to "bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic",
            TokenType.CONSTANT to "true false null"),
        declarators = declare, preprocessor = true, capitalizedTypes = true)

    val SWIFT = CodeLanguage("swift", "Swift", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\"")),
        words = wordMap(false, TokenType.KEYWORD to "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch is super self Self throw throws try async await some any",
            TokenType.CONSTANT to "true false nil"),
        declarators = declare, annotationPrefix = '@', capitalizedTypes = true)

    val DART = CodeLanguage("dart", "Dart", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("'''", multiline = true), StringRule("\"\"\"", multiline = true), StringRule("\""), StringRule("'")),
        words = wordMap(false, TokenType.KEYWORD to "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory final finally for get if implements import in interface is late library mixin new on operator part required rethrow return set show static super switch sync this throw try typedef var void while with yield",
            TokenType.TYPE to "int double num bool", TokenType.CONSTANT to "true false null"),
        declarators = declare, annotationPrefix = '@', capitalizedTypes = true)

    val PHP = CodeLanguage("php", "PHP", lineComments = slashComments + hashComment, blockComments = cBlock,
        strings = listOf(StringRule("\"", multiline = true), StringRule("'", multiline = true)),
        words = wordMap(true, TokenType.KEYWORD to "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield",
            TokenType.CONSTANT to "true false null"),
        caseInsensitive = true, declarators = declare, variablePrefixes = "$", capitalizedTypes = true)

    val RUBY = CodeLanguage("ruby", "Ruby", lineComments = hashComment, blockComments = listOf("=begin" to "=end"),
        strings = listOf(StringRule("\"", multiline = true), StringRule("'", multiline = true), StringRule("`")),
        words = wordMap(false, TokenType.KEYWORD to "alias and begin break case class def do else elsif end ensure for if in module next not or redo rescue retry return self super then undef unless until when while yield require require_relative attr_accessor attr_reader attr_writer",
            TokenType.CONSTANT to "true false nil"),
        declarators = declare + ("module" to TokenType.TYPE), variablePrefixes = "@$", capitalizedTypes = true)

    val LUA = CodeLanguage("lua", "Lua", lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"),
        strings = listOf(StringRule("[[", "]]", escape = null, multiline = true), StringRule("\""), StringRule("'")),
        words = wordMap(false, TokenType.KEYWORD to "and break do else elseif end for function goto if in local not or repeat return then until while",
            TokenType.CONSTANT to "true false nil"),
        declarators = declare)

    val SQL = CodeLanguage("sql", "SQL", lineComments = listOf("--"), blockComments = cBlock,
        strings = listOf(StringRule("'", escape = null, multiline = true), StringRule("\"", escape = null), StringRule("`", escape = null)),
        words = wordMap(true, TokenType.KEYWORD to "select from where and or not insert into values update set delete create table drop alter add column index view join inner left right outer full cross on as group by order having limit offset distinct union all case when then else end is like in between exists primary key foreign references default constraint unique if begin commit rollback transaction grant revoke with returning database schema use show describe explain asc desc cascade trigger procedure function returns declare replace temporary",
            TokenType.TYPE to "int integer bigint smallint tinyint varchar char text boolean bool date datetime timestamp float double decimal numeric serial json jsonb uuid blob real",
            TokenType.CONSTANT to "true false null"),
        caseInsensitive = true)

    val SHELL = CodeLanguage("shell", "Shell", lineComments = hashComment, commentNeedsBoundary = true,
        strings = listOf(StringRule("\"", multiline = true), StringRule("'", escape = null, multiline = true)),
        words = wordMap(false, TokenType.KEYWORD to "if then else elif fi case esac for select while until do done in function time return exit break continue local export readonly declare typeset unset shift source alias eval exec trap set",
            TokenType.FUNCTION to "echo printf cd pwd read test sudo grep sed awk cat ls rm mkdir cp mv chmod chown curl wget git docker kubectl apt apt-get yum dnf brew ssh scp tar make npm pip python3 systemctl",
            TokenType.CONSTANT to "true false"),
        declarators = mapOf("function" to TokenType.FUNCTION), variablePrefixes = "$", identifierExtra = "_-")

    val POWERSHELL = CodeLanguage("powershell", "PowerShell", lineComments = hashComment, blockComments = listOf("<#" to "#>"),
        strings = listOf(StringRule("\"", escape = '`', multiline = true), StringRule("'", escape = null, multiline = true)),
        words = wordMap(true, TokenType.KEYWORD to "begin break catch class continue data define do dynamicparam else elseif end exit filter finally for foreach from function if in param process return switch throw trap try until using var while"),
        caseInsensitive = true, declarators = declare, variablePrefixes = "$", identifierExtra = "_-")

    val BATCH = CodeLanguage("bat", "Batch", lineComments = listOf("::", "rem ", "REM ", "@rem ", "@REM "),
        strings = listOf(StringRule("\"", escape = null)),
        words = wordMap(true, TokenType.KEYWORD to "echo set if else goto call exit for in do not exist defined errorlevel setlocal endlocal pause shift off on"),
        caseInsensitive = true,
        patterns = listOf(TokenPattern("%[^%\\s]+%|%~?\\d", TokenType.VARIABLE) { it == '%' }))

    val DOCKERFILE = CodeLanguage("dockerfile", "Dockerfile", lineComments = hashComment, commentNeedsBoundary = true,
        strings = listOf(StringRule("\""), StringRule("'", escape = null)),
        words = wordMap(false, TokenType.KEYWORD to "FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS from run cmd label expose env add copy entrypoint volume user workdir arg as"),
        variablePrefixes = "$")

    val MAKEFILE = CodeLanguage("makefile", "Makefile", lineComments = hashComment,
        strings = listOf(StringRule("\""), StringRule("'")),
        words = wordMap(false, TokenType.KEYWORD to "ifeq ifneq ifdef ifndef else endif include define endef export override unexport vpath"),
        variablePrefixes = "$", parenVariables = true, numbers = false,
        lineRules = listOf(LineRule("([^\\s:#=$][^:#=]*?)\\s*::?(?![=:])", 1 to TokenType.FUNCTION)))

    val JSON = CodeLanguage("json", "JSON", lineComments = slashComments, blockComments = cBlock,
        strings = listOf(StringRule("\"")), keyStrings = true,
        words = wordMap(false, TokenType.CONSTANT to "true false null"))

    val YAML = CodeLanguage("yaml", "YAML", lineComments = hashComment, commentNeedsBoundary = true,
        strings = listOf(StringRule("\""), StringRule("'", escape = null)),
        words = wordMap(false, TokenType.CONSTANT to "true false null yes no on off True False TRUE FALSE Null NULL Yes No"),
        lineRules = listOf(
            LineRule("(---|\\.\\.\\.)(?=\\s|$)", 1 to TokenType.META),
            LineRule("\\s*(?:-\\s+)?([^\\s#'\"\\-{\\[][^:#]*?|\"[^\"]*\"|'[^']*')\\s*(:)(?=\\s|$)", 1 to TokenType.PROPERTY)),
        patterns = listOf(TokenPattern("[&*][\\w.-]+", TokenType.VARIABLE) { it == '&' || it == '*' },
            TokenPattern("!!?[\\w/.-]+", TokenType.META) { it == '!' }))

    val TOML = CodeLanguage("toml", "TOML", lineComments = hashComment,
        strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("'''", escape = null, multiline = true),
            StringRule("\""), StringRule("'", escape = null)),
        words = wordMap(false, TokenType.CONSTANT to "true false"),
        lineRules = listOf(LineRule("\\s*(\\[\\[?[^\\]\\n]*\\]\\]?)", 1 to TokenType.TYPE),
            LineRule("\\s*([A-Za-z0-9_.\"'-]+)\\s*(?==)", 1 to TokenType.PROPERTY)))

    val INI = CodeLanguage("ini", "Config", lineComments = listOf("#", ";"), commentNeedsBoundary = true,
        strings = listOf(StringRule("\""), StringRule("'", escape = null)),
        words = wordMap(false, TokenType.CONSTANT to "true false yes no on off True False"),
        variablePrefixes = "$",
        lineRules = listOf(LineRule("\\s*(\\[[^\\]\\n]*\\])", 1 to TokenType.TYPE),
            LineRule("\\s*(export\\s+)?([A-Za-z_][\\w.\\-]*)(?=\\s*[=:]|\\s|$)", 1 to TokenType.KEYWORD, 2 to TokenType.PROPERTY)))

    val CSS = CodeLanguage("css", "CSS", blockComments = cBlock, strings = listOf(StringRule("\""), StringRule("'")),
        words = wordMap(false, TokenType.CONSTANT to "inherit initial unset none auto transparent currentColor important"),
        annotationPrefix = '@', identifierExtra = "_-", css = true,
        patterns = listOf(TokenPattern("#[0-9a-fA-F]{3,8}\\b", TokenType.NUMBER) { it == '#' }))

    val SCSS = CodeLanguage("scss", "SCSS", lineComments = slashComments, blockComments = cBlock, strings = CSS.strings,
        words = CSS.words, annotationPrefix = '@', variablePrefixes = "$", identifierExtra = "_-", css = true, patterns = CSS.patterns)

    val HTML = CodeLanguage("html", "HTML", LanguageMode.MARKUP)
    val XML = CodeLanguage("xml", "XML", LanguageMode.MARKUP)
    val MARKDOWN = CodeLanguage("markdown", "Markdown", LanguageMode.MARKDOWN)

    val DIFF = CodeLanguage("diff", "Diff", numbers = false, lineRules = listOf(
        LineRule("((?:\\+\\+\\+|---) .*)", 1 to TokenType.STRONG),
        LineRule("(\\+.*)", 1 to TokenType.INSERTED),
        LineRule("(-.*)", 1 to TokenType.DELETED),
        LineRule("(@@.*)", 1 to TokenType.META),
        LineRule("((?:diff|index) .*)", 1 to TokenType.KEYWORD)))

    val LOG = CodeLanguage("log", "Log", strings = listOf(StringRule("\"", maxLength = 400)),
        words = wordMap(false, TokenType.ERROR to "ERROR ERR FATAL CRITICAL CRIT PANIC SEVERE EMERG ALERT Error Fatal Panic error fatal panic failed Failed FAILED FAILURE Exception",
            TokenType.WARNING to "WARN WARNING Warning Warn warning warn",
            TokenType.INFO to "INFO NOTICE Info Notice info notice",
            TokenType.COMMENT to "DEBUG TRACE VERBOSE Debug Trace debug trace"),
        patterns = listOf(url,
            TokenPattern("\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?|\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?",
                TokenType.CONSTANT) { it.isDigit() }))

    private val byExtension = mapOf(
        "kt" to KOTLIN, "kts" to KOTLIN, "gradle" to KOTLIN, "java" to JAVA, "groovy" to JAVA, "scala" to JAVA,
        "js" to JAVASCRIPT, "mjs" to JAVASCRIPT, "cjs" to JAVASCRIPT, "jsx" to JAVASCRIPT,
        "ts" to TYPESCRIPT, "tsx" to TYPESCRIPT, "mts" to TYPESCRIPT, "cts" to TYPESCRIPT,
        "py" to PYTHON, "pyi" to PYTHON, "pyw" to PYTHON, "go" to GO, "rs" to RUST,
        "c" to C, "h" to C, "cc" to CPP, "cpp" to CPP, "cxx" to CPP, "hpp" to CPP, "hh" to CPP, "hxx" to CPP, "m" to C, "mm" to CPP,
        "cs" to CSHARP, "swift" to SWIFT, "dart" to DART, "php" to PHP, "rb" to RUBY, "rake" to RUBY, "lua" to LUA,
        "sql" to SQL, "sh" to SHELL, "bash" to SHELL, "zsh" to SHELL, "ksh" to SHELL, "fish" to SHELL,
        "ps1" to POWERSHELL, "psm1" to POWERSHELL, "bat" to BATCH, "cmd" to BATCH,
        "json" to JSON, "jsonc" to JSON, "json5" to JSON, "geojson" to JSON, "webmanifest" to JSON, "ipynb" to JSON,
        "yaml" to YAML, "yml" to YAML, "toml" to TOML,
        "ini" to INI, "conf" to INI, "cfg" to INI, "properties" to INI, "env" to INI, "service" to INI, "desktop" to INI,
        "css" to CSS, "scss" to SCSS, "less" to SCSS, "sass" to SCSS,
        "html" to HTML, "htm" to HTML, "xhtml" to HTML, "vue" to HTML, "svelte" to HTML,
        "xml" to XML, "svg" to XML, "plist" to XML, "xsd" to XML, "xsl" to XML,
        "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
        "diff" to DIFF, "patch" to DIFF, "log" to LOG, "out" to LOG, "err" to LOG, "tf" to INI, "dockerfile" to DOCKERFILE
    )

    private val byName = mapOf(
        "dockerfile" to DOCKERFILE, "containerfile" to DOCKERFILE, "makefile" to MAKEFILE, "gnumakefile" to MAKEFILE,
        "gemfile" to RUBY, "rakefile" to RUBY, "cmakelists.txt" to MAKEFILE,
        ".bashrc" to SHELL, ".bash_profile" to SHELL, ".zshrc" to SHELL, ".profile" to SHELL, ".bash_aliases" to SHELL,
        ".bash_logout" to SHELL, ".zprofile" to SHELL, ".zshenv" to SHELL, "gradlew" to SHELL,
        ".gitconfig" to INI, ".editorconfig" to INI, ".npmrc" to INI, ".gitmodules" to INI, ".tmux.conf" to INI,
        "config" to INI, "ssh_config" to INI, "sshd_config" to INI, ".gitignore" to INI, ".dockerignore" to INI,
        "jenkinsfile" to JAVA, "cargo.lock" to TOML, "pipfile" to TOML, "go.mod" to GO
    )

    private val fenceAliases = mapOf("sh" to SHELL, "bash" to SHELL, "shell" to SHELL, "console" to SHELL, "zsh" to SHELL,
        "js" to JAVASCRIPT, "javascript" to JAVASCRIPT, "ts" to TYPESCRIPT, "typescript" to TYPESCRIPT, "py" to PYTHON,
        "python" to PYTHON, "kotlin" to KOTLIN, "java" to JAVA, "go" to GO, "golang" to GO, "rust" to RUST, "c" to C,
        "cpp" to CPP, "c++" to CPP, "csharp" to CSHARP, "cs" to CSHARP, "swift" to SWIFT, "dart" to DART, "php" to PHP,
        "ruby" to RUBY, "rb" to RUBY, "lua" to LUA, "sql" to SQL, "json" to JSON, "yaml" to YAML, "yml" to YAML,
        "toml" to TOML, "ini" to INI, "css" to CSS, "scss" to SCSS, "html" to HTML, "xml" to XML, "diff" to DIFF,
        "dockerfile" to DOCKERFILE, "docker" to DOCKERFILE, "makefile" to MAKEFILE, "make" to MAKEFILE,
        "powershell" to POWERSHELL, "ps1" to POWERSHELL, "kt" to KOTLIN, "md" to MARKDOWN, "markdown" to MARKDOWN)

    fun forFence(info: String): CodeLanguage? = fenceAliases[info.trim().lowercase(Locale.ROOT).substringBefore(' ').substringBefore('{')]

    fun detect(fileName: String, firstLine: String = ""): CodeLanguage {
        val lower = fileName.lowercase(Locale.ROOT)
        byName[lower]?.let { return it }
        if (lower.startsWith(".env")) return INI
        if (lower.startsWith("dockerfile")) return DOCKERFILE
        if (lower.endsWith("_history")) return SHELL
        val extension = lower.substringAfterLast('.', "")
        if (extension.isNotEmpty()) byExtension[extension]?.let { return it }
        if (firstLine.startsWith("#!")) {
            val interpreter = firstLine.substringAfterLast('/').removePrefix("env ").trim().substringBefore(' ')
            return when {
                interpreter.startsWith("python") -> PYTHON
                interpreter.startsWith("node") || interpreter.startsWith("deno") || interpreter.startsWith("bun") -> JAVASCRIPT
                interpreter.startsWith("ruby") -> RUBY
                interpreter.startsWith("php") -> PHP
                interpreter.startsWith("lua") -> LUA
                interpreter.startsWith("pwsh") -> POWERSHELL
                else -> SHELL
            }
        }
        if (firstLine.startsWith("<?xml") || firstLine.startsWith("<svg")) return XML
        if (firstLine.startsWith("<!DOCTYPE html", ignoreCase = true) || firstLine.startsWith("<html", ignoreCase = true)) return HTML
        return PLAIN
    }
}
