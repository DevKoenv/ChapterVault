package dev.koenv.chaptervault.infrastructure.notifications

import java.io.IOException
import java.util.Base64

private const val MAX_DISCORD_TITLE = 256
private const val MAX_DISCORD_DESCRIPTION = 4096

/**
 * A value that is safe in an HTTP header. Line breaks and other control characters are replaced by spaces (so a title
 * cannot add headers), and anything outside printable ASCII is sent as an RFC 2047 encoded word, which ntfy decodes.
 * Without that the HTTP client refuses to send a series called, say, 進撃の巨人 at all.
 */
internal fun headerValue(text: String): String {
    val cleaned = text.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }.joinToString("")
    if (cleaned.all { it.code in 0x20..0x7E }) return cleaned
    return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(cleaned.toByteArray(Charsets.UTF_8)) + "?="
}

/** Cuts [text] to at most [max] characters, ending in an ellipsis when something was removed. */
internal fun truncate(
    text: String,
    max: Int,
): String = if (text.length <= max) text else text.take(max - 1) + "\u2026"

internal fun discordTitle(seriesTitle: String): String = truncate("New chapters: $seriesTitle", MAX_DISCORD_TITLE)

/** One bullet per chapter, cut off with a count once Discord's description limit would be exceeded. */
internal fun discordDescription(chapterTitles: List<String>): String {
    val lines = mutableListOf<String>()
    var length = 0
    for ((i, title) in chapterTitles.withIndex()) {
        val line = truncate("- $title", MAX_DISCORD_TITLE)
        val remaining = chapterTitles.size - i
        val more = "\u2026and $remaining more"
        // keep room for the "and N more" line in case this is not the last one that fits
        val needed = length + line.length + 1 + (if (remaining > 1) more.length + 1 else 0)
        if (needed > MAX_DISCORD_DESCRIPTION) {
            lines += more
            return lines.joinToString("\n")
        }
        lines += line
        length += line.length + 1
    }
    return lines.joinToString("\n")
}

/** The status without the target address: webhook URLs such as Discord's carry their secret in the path. */
internal fun deliveryFailure(status: Int): IOException = IOException("HTTP $status from the notification target")
