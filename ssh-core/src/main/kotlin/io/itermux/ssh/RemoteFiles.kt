package io.itermux.ssh

data class RemoteDirectory(val path: String, val entries: List<RemoteEntry>)

/**
 * One SFTP directory entry. Extra metadata fields default so existing callers
 * that only need [name]/[isDirectory] stay source-compatible.
 */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Unix epoch seconds from SftpATTRS.mTime; 0 when unknown. */
    val mtimeSeconds: Long = 0L,
    /** Formatted mode from SftpATTRS.getPermissionsString(), e.g. "-rw-r--r--". */
    val permissions: String = ""
)

/** Join an SFTP directory and child name into a single path. */
fun joinRemotePath(directory: String, name: String): String {
    if (name.isEmpty()) {
        val base = directory.trimEnd('/')
        return if (base.isEmpty()) "/" else "$base/"
    }
    val base = directory.trimEnd('/')
    return if (base.isEmpty()) "/$name" else "$base/$name"
}

/** Parent of an absolute (or home-relative) remote path; root stays "/". */
fun parentRemotePath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
    return parent.ifEmpty { "/" }
}

/** True when [name] is a single path segment safe to delete or rename (not `.`/`..`, no separators). */
fun isSafeRemoteEntryName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name &&
        name.none { it == '\u0000' || it == '\n' || it == '\r' }

/**
 * True when [path] is a non-root remote path safe to delete: no blank/control chars,
 * no `.`/`..` segments, and not the filesystem root.
 */
fun isSafeRemoteDeletePath(path: String): Boolean {
    if (path.isBlank() || path.length > 4096) return false
    if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return false
    return trimmed.split('/').none { it == "." || it == ".." }
}

fun formatRemoteSize(bytes: Long): String = when {
    bytes < 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private val remoteMtimeFormatter = ThreadLocal.withInitial {
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
}

fun formatRemoteMtime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    return remoteMtimeFormatter.get().format(java.time.Instant.ofEpochSecond(epochSeconds))
}

/** Directory-first, case-insensitive name order used by SFTP listings. */
fun remoteEntryListComparator(): Comparator<RemoteEntry> =
    compareByDescending<RemoteEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
