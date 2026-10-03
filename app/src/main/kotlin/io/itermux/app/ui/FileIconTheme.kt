package io.itermux.app.ui

import io.itermux.ssh.RemoteFileKind
import io.itermux.ssh.remoteFileKind
import java.util.Locale

/**
 * Editor-style file icons: language files get a colored badge with a short label,
 * media and documents keep a glyph tinted in their conventional color.
 */
internal sealed interface FileIconSpec {
    val color: Long
    data class Badge(val label: String, override val color: Long, val labelColor: Long = 0xFFFFFFFF) : FileIconSpec
    data class Glyph(val kind: RemoteFileKind, override val color: Long) : FileIconSpec
    data class Folder(override val color: Long) : FileIconSpec
}

private fun badge(label: String, color: Long, labelColor: Long = 0xFFFFFFFF) = FileIconSpec.Badge(label, color, labelColor)
private const val DARK_LABEL = 0xFF1E1E1E

private val js = badge("JS", 0xFFF1DD35, DARK_LABEL)
private val ts = badge("TS", 0xFF3178C6)
private val shell = badge(">_", 0xFF4EAA25)
private val json = badge("{}", 0xFFCBCB41, DARK_LABEL)
private val yaml = badge("YML", 0xFFCB171E)
private val markdown = badge("M↓", 0xFF42A5F5)
private val config = badge("CFG", 0xFF6D8086)
private val git = badge("GIT", 0xFFF14E32)
private val docker = badge("DKR", 0xFF2496ED)
private val key = badge("KEY", 0xFFC49A00, DARK_LABEL)
private val font = badge("Aa", 0xFFE53935)

private val byName = mapOf(
    "dockerfile" to docker, "containerfile" to docker, ".dockerignore" to docker,
    "docker-compose.yml" to docker, "docker-compose.yaml" to docker, "compose.yml" to docker, "compose.yaml" to docker,
    "makefile" to badge("MK", 0xFF6D8086), "gnumakefile" to badge("MK", 0xFF6D8086), "justfile" to badge("JST", 0xFF6D8086),
    "cmakelists.txt" to badge("CM", 0xFF064F8C),
    "gemfile" to badge("RB", 0xFFCC342D), "rakefile" to badge("RB", 0xFFCC342D),
    "package.json" to badge("NPM", 0xFFCB3837), "package-lock.json" to badge("NPM", 0xFFCB3837),
    "yarn.lock" to badge("YRN", 0xFF2C8EBB), "pnpm-lock.yaml" to badge("PN", 0xFFF69220, DARK_LABEL),
    "cargo.toml" to badge("RS", 0xFFCE422B), "cargo.lock" to badge("RS", 0xFFCE422B),
    "go.mod" to badge("GO", 0xFF00ADD8), "go.sum" to badge("GO", 0xFF00ADD8),
    "tsconfig.json" to ts, "jsconfig.json" to js,
    ".gitignore" to git, ".gitattributes" to git, ".gitmodules" to git, ".gitconfig" to git,
    ".bashrc" to shell, ".bash_profile" to shell, ".zshrc" to shell, ".profile" to shell, ".bash_aliases" to shell,
    ".bash_logout" to shell, ".zprofile" to shell, ".bash_history" to shell, ".zsh_history" to shell,
    ".editorconfig" to config, ".npmrc" to config, ".vimrc" to badge("VIM", 0xFF019733), ".tmux.conf" to badge("TMX", 0xFF1BB91F),
    "license" to badge("©", 0xFFD0BF41, DARK_LABEL), "licence" to badge("©", 0xFFD0BF41, DARK_LABEL),
    "license.md" to badge("©", 0xFFD0BF41, DARK_LABEL), "license.txt" to badge("©", 0xFFD0BF41, DARK_LABEL),
    "readme" to badge("i", 0xFF42A5F5), "readme.md" to badge("i", 0xFF42A5F5), "readme.txt" to badge("i", 0xFF42A5F5),
    "changelog" to badge("CHG", 0xFF8BC34A, DARK_LABEL), "changelog.md" to badge("CHG", 0xFF8BC34A, DARK_LABEL),
    "authorized_keys" to key, "known_hosts" to key, "id_rsa" to key, "id_ed25519" to key, "id_ecdsa" to key,
    "build.gradle" to badge("G", 0xFF02303A), "build.gradle.kts" to badge("G", 0xFF02303A),
    "settings.gradle" to badge("G", 0xFF02303A), "settings.gradle.kts" to badge("G", 0xFF02303A),
    "gradlew" to badge("G", 0xFF02303A)
)

private val byExtension = mapOf(
    "js" to js, "mjs" to js, "cjs" to js, "jsx" to badge("JSX", 0xFF61DAFB, DARK_LABEL),
    "ts" to ts, "mts" to ts, "cts" to ts, "tsx" to badge("TSX", 0xFF3178C6),
    "py" to badge("PY", 0xFF3776AB, 0xFFFFD43B), "pyi" to badge("PY", 0xFF3776AB, 0xFFFFD43B),
    "ipynb" to badge("NB", 0xFFF37626),
    "kt" to badge("KT", 0xFF7F52FF), "kts" to badge("KTS", 0xFF7F52FF),
    "java" to badge("J", 0xFFE76F00), "class" to badge("J", 0xFFB07219), "jar" to badge("JAR", 0xFFB07219),
    "go" to badge("GO", 0xFF00ADD8), "rs" to badge("RS", 0xFFCE422B),
    "c" to badge("C", 0xFF5C6BC0), "h" to badge("H", 0xFFA074C4),
    "cc" to badge("C++", 0xFF00599C), "cpp" to badge("C++", 0xFF00599C), "cxx" to badge("C++", 0xFF00599C),
    "hpp" to badge("H++", 0xFFA074C4), "hh" to badge("H++", 0xFFA074C4), "m" to badge("OC", 0xFF438EFF),
    "cs" to badge("C#", 0xFF68217A), "fs" to badge("F#", 0xFF378BBA),
    "swift" to badge("SW", 0xFFF05138), "dart" to badge("DT", 0xFF0175C2),
    "rb" to badge("RB", 0xFFCC342D), "php" to badge("PHP", 0xFF777BB4), "pl" to badge("PL", 0xFF39457E),
    "lua" to badge("LUA", 0xFF000080), "r" to badge("R", 0xFF276DC3), "scala" to badge("SC", 0xFFDC322F),
    "ex" to badge("EX", 0xFF6E4A7E), "exs" to badge("EX", 0xFF6E4A7E), "erl" to badge("ERL", 0xFFA90533),
    "hs" to badge("HS", 0xFF5E5086), "clj" to badge("CLJ", 0xFF5881D8), "zig" to badge("ZIG", 0xFFF7A41D, DARK_LABEL),
    "nim" to badge("NIM", 0xFFFFC200, DARK_LABEL), "jl" to badge("JL", 0xFF9558B2), "groovy" to badge("GR", 0xFF4298B8),
    "sh" to shell, "bash" to shell, "zsh" to shell, "fish" to shell, "ksh" to shell,
    "ps1" to badge("PS", 0xFF012456), "psm1" to badge("PS", 0xFF012456), "bat" to badge("BAT", 0xFFC1F12E, DARK_LABEL),
    "cmd" to badge("BAT", 0xFFC1F12E, DARK_LABEL),
    "sql" to badge("SQL", 0xFFE38C00), "db" to badge("DB", 0xFF8D6E63), "sqlite" to badge("DB", 0xFF8D6E63),
    "sqlite3" to badge("DB", 0xFF8D6E63),
    "html" to badge("<>", 0xFFE34F26), "htm" to badge("<>", 0xFFE34F26), "xhtml" to badge("<>", 0xFFE34F26),
    "css" to badge("CSS", 0xFF1572B6), "scss" to badge("S", 0xFFCC6699), "sass" to badge("S", 0xFFCC6699),
    "less" to badge("LS", 0xFF1D365D), "vue" to badge("V", 0xFF41B883), "svelte" to badge("S", 0xFFFF3E00),
    "json" to json, "jsonc" to json, "json5" to json, "geojson" to json, "webmanifest" to json,
    "yaml" to yaml, "yml" to yaml, "toml" to badge("T", 0xFF9C4221), "xml" to badge("XML", 0xFFF1662A),
    "plist" to badge("XML", 0xFFF1662A), "svg" to badge("SVG", 0xFFFFB13B, DARK_LABEL),
    "ini" to config, "conf" to config, "cfg" to config, "properties" to config, "service" to config,
    "env" to badge("ENV", 0xFFECD53F, DARK_LABEL), "lock" to badge("LCK", 0xFF8D6E63),
    "md" to markdown, "markdown" to markdown, "mdx" to markdown, "rst" to badge("RST", 0xFF6D8086),
    "tex" to badge("TEX", 0xFF3D6117), "csv" to badge("CSV", 0xFF217346), "tsv" to badge("TSV", 0xFF217346),
    "gradle" to badge("G", 0xFF02303A), "tf" to badge("TF", 0xFF7B42BC), "tfvars" to badge("TF", 0xFF7B42BC),
    "proto" to badge("PB", 0xFF4285F4), "graphql" to badge("GQL", 0xFFE10098), "gql" to badge("GQL", 0xFFE10098),
    "nix" to badge("NIX", 0xFF5277C3), "vim" to badge("VIM", 0xFF019733), "dockerfile" to docker,
    "diff" to badge("±", 0xFF41B883), "patch" to badge("±", 0xFF41B883),
    "pem" to key, "key" to key, "crt" to key, "cer" to key, "pub" to key, "p12" to key, "gpg" to key, "asc" to key,
    "ttf" to font, "otf" to font, "woff" to font, "woff2" to font,
    "wasm" to badge("WA", 0xFF654FF0), "so" to badge("BIN", 0xFF607D8B), "o" to badge("BIN", 0xFF607D8B),
    "bin" to badge("BIN", 0xFF607D8B), "log" to FileIconSpec.Glyph(RemoteFileKind.TEXT, 0xFFAFB42B)
)

private val folderColors = mapOf(
    "src" to 0xFF4CAF50, "source" to 0xFF4CAF50, "lib" to 0xFF8BC34A, "libs" to 0xFF8BC34A, "app" to 0xFF4CAF50,
    "test" to 0xFF00BFA5, "tests" to 0xFF00BFA5, "__tests__" to 0xFF00BFA5, "spec" to 0xFF00BFA5, "androidtest" to 0xFF00BFA5,
    "node_modules" to 0xFF8BC34A, "vendor" to 0xFF8BC34A, ".venv" to 0xFFFFCA28, "venv" to 0xFFFFCA28,
    ".git" to 0xFFF4511E, ".github" to 0xFF546E7A, ".vscode" to 0xFF2196F3, ".idea" to 0xFF7E57C2,
    "docs" to 0xFF2196F3, "doc" to 0xFF2196F3, "dist" to 0xFFE57373, "build" to 0xFFE57373, "out" to 0xFFE57373,
    "target" to 0xFFE57373, "bin" to 0xFFE57373, "scripts" to 0xFF26A69A, "script" to 0xFF26A69A,
    "assets" to 0xFFFFCA28, "images" to 0xFF26A69A, "img" to 0xFF26A69A, "public" to 0xFF03A9F4, "static" to 0xFF03A9F4,
    "res" to 0xFFFFCA28, "resources" to 0xFFFFCA28, "config" to 0xFF00ACC1, ".config" to 0xFF00ACC1, "etc" to 0xFF00ACC1,
    ".ssh" to 0xFFC49A00, "downloads" to 0xFF42A5F5, "desktop" to 0xFF42A5F5, "documents" to 0xFF42A5F5,
    "home" to 0xFFFF8A65, "root" to 0xFFFF8A65, "tmp" to 0xFF78909C, "var" to 0xFF78909C, "log" to 0xFFAFB42B,
    "logs" to 0xFFAFB42B, "data" to 0xFF5C6BC0, "db" to 0xFF5C6BC0, "migrations" to 0xFF5C6BC0,
    "components" to 0xFFC0CA33, "views" to 0xFFFF7043, "pages" to 0xFFFF7043, "api" to 0xFFFFB300, "server" to 0xFFFFB300,
    "kotlin" to 0xFF7F52FF, "java" to 0xFFE76F00, "python" to 0xFF3776AB, "go" to 0xFF00ADD8
)

private const val DEFAULT_FOLDER = 0xFF90A4AE

internal fun fileIconSpec(name: String, directory: Boolean = false, permissions: String = ""): FileIconSpec {
    val lower = name.lowercase(Locale.ROOT)
    if (directory) return FileIconSpec.Folder(folderColors[lower] ?: DEFAULT_FOLDER)
    byName[lower]?.let { return it }
    if (lower.startsWith(".env")) return byExtension.getValue("env")
    if (lower.startsWith("dockerfile") || lower.endsWith(".dockerfile")) return docker
    if (lower.endsWith(".d.ts")) return ts
    val extension = lower.substringAfterLast('.', "")
    if (extension.isNotEmpty()) byExtension[extension]?.let { return it }
    // A link's own mode is always rwx; it says nothing about the target.
    val kind = remoteFileKind(name, false, if (permissions.startsWith('l')) "" else permissions)
    return FileIconSpec.Glyph(kind, glyphColor(kind))
}

private fun glyphColor(kind: RemoteFileKind): Long = when (kind) {
    RemoteFileKind.IMAGE -> 0xFF26A69A
    RemoteFileKind.AUDIO -> 0xFFEC407A
    RemoteFileKind.VIDEO -> 0xFFFF7043
    RemoteFileKind.ARCHIVE -> 0xFFAF8F5A
    RemoteFileKind.PDF -> 0xFFE53935
    RemoteFileKind.DOCUMENT -> 0xFF2B7CD3
    RemoteFileKind.SPREADSHEET -> 0xFF21A366
    RemoteFileKind.PRESENTATION -> 0xFFD24726
    RemoteFileKind.EXECUTABLE -> 0xFF4CAF50
    RemoteFileKind.CODE -> 0xFF7986CB
    RemoteFileKind.CONFIG -> 0xFF6D8086
    RemoteFileKind.TEXT -> 0xFF90A4AE
    RemoteFileKind.DIRECTORY -> DEFAULT_FOLDER
    RemoteFileKind.FILE -> 0xFF90A4AE
}
