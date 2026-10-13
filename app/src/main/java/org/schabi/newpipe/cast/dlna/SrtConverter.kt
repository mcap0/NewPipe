/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.util.Locale
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Converts subtitles to SubRip (SRT), the only subtitle format most TVs accept from DLNA.
 */
object SrtConverter {
    private class Cue(val startMillis: Long, val endMillis: Long, val text: String)

    fun isSupported(format: String?): Boolean = format != null && format.lowercase(Locale.US) in FORMATS

    /**
     * @param format the subtitle format: `ttml`, `vtt` or `srt` (the file suffix)
     * @return the subtitles as SRT, or null if the format is not supported
     */
    fun convert(text: String, format: String): String? = when (format.lowercase(Locale.US)) {
        "srt" -> text
        "vtt" -> fromVtt(text)
        "ttml", "xml", "dfxp" -> fromTtml(text)
        else -> null
    }

    fun fromTtml(xml: String): String {
        val document = XmlUtils.parse(xml)
        val root = document.documentElement
        val tickRate = root.attributeNamed("tickRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val frameRate = root.attributeNamed("frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
        val cues = ArrayList<Cue>()
        collectParagraphs(root) { p ->
            val start = parseTtmlTime(p.getAttribute("begin"), tickRate, frameRate) ?: return@collectParagraphs
            val end = parseTtmlTime(p.getAttribute("end"), tickRate, frameRate)
                ?: parseTtmlTime(p.getAttribute("dur"), tickRate, frameRate)?.let { start + it }
                ?: return@collectParagraphs
            val text = textOf(p).lines().joinToString("\n") { it.trim() }.trim()
            if (text.isNotEmpty() && end > start) {
                cues.add(Cue(start, end, text))
            }
        }
        return write(cues)
    }

    fun fromVtt(vtt: String): String = write(parseCues(vtt))

    /**
     * @return [srt] with the times moved [offsetMillis] earlier, without the cues which ended
     * before; for a video sent to the TV from the middle
     */
    fun shift(srt: String, offsetMillis: Long): String = write(
        parseCues(srt).mapNotNull { cue ->
            if (cue.endMillis <= offsetMillis) {
                null
            } else {
                Cue((cue.startMillis - offsetMillis).coerceAtLeast(0), cue.endMillis - offsetMillis, cue.text)
            }
        }
    )

    /** Parses WebVTT or SRT cues. */
    private fun parseCues(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) {
                continue
            }
            val timing = lines[timingIndex].split("-->")
            val start = parseClock(timing[0].trim()) ?: continue
            val end = parseClock(timing[1].trim().substringBefore(' ')) ?: continue
            val cueText = lines.drop(timingIndex + 1)
                .joinToString("\n") { it.replace(TAG, "").trim() }
                .let { decodeEntities(it) }
                .trim()
            if (cueText.isNotEmpty() && end > start) {
                cues.add(Cue(start, end, cueText))
            }
        }
        return cues
    }

    /** `HH:MM:SS.mmm`, `MM:SS.mmm` or `HH:MM:SS,mmm` */
    private fun parseClock(text: String): Long? {
        val parts = text.replace(',', '.').split(':')
        if (parts.size !in 2..3) {
            return null
        }
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000)
    }

    /**
     * TTML clock time (`HH:MM:SS.mmm`, `HH:MM:SS:frames`) or offset time (`1.5s`, `1500ms`,
     * `2h`, `3m`, `250t`, `12f`).
     */
    private fun parseTtmlTime(text: String?, tickRate: Double, frameRate: Double): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in value) {
            val parts = value.split(':')
            if (parts.size == 4) {
                val clock = parseClock(parts.take(3).joinToString(":")) ?: return null
                val frames = parts[3].toDoubleOrNull() ?: return null
                return clock + Math.round(frames * 1000 / frameRate)
            }
            return parseClock(value)
        }
        val unit = value.takeLastWhile { it.isLetter() }
        val number = value.dropLast(unit.length).toDoubleOrNull() ?: return null
        val millis = when (unit) {
            "h" -> number * 3_600_000
            "m" -> number * 60_000
            "s", "" -> number * 1000
            "ms" -> number
            "t" -> number * 1000 / tickRate
            "f" -> number * 1000 / frameRate
            else -> return null
        }
        return Math.round(millis)
    }

    private fun collectParagraphs(node: Node, onParagraph: (Element) -> Unit) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localNameOf(child) == "p") {
                onParagraph(child)
            } else {
                collectParagraphs(child, onParagraph)
            }
        }
    }

    private fun textOf(node: Node): String {
        val text = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when {
                child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE ->
                    text.append(child.nodeValue.replace(WHITESPACE, " "))

                child is Element && localNameOf(child) == "br" -> text.append('\n')

                child is Element -> text.append(textOf(child))
            }
        }
        return text.toString()
    }

    private fun Element.attributeNamed(localName: String): String? {
        val attributes = attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (name == localName) {
                return attribute.nodeValue
            }
        }
        return null
    }

    private fun localNameOf(element: Element): String = element.localName ?: element.tagName.substringAfter(':')

    private fun decodeEntities(text: String) = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun write(cues: List<Cue>): String {
        val srt = StringBuilder()
        cues.sortedBy { it.startMillis }.forEachIndexed { index, cue ->
            srt.append(index + 1).append('\n')
                .append(formatTime(cue.startMillis)).append(" --> ").append(formatTime(cue.endMillis)).append('\n')
                .append(cue.text).append("\n\n")
        }
        return srt.toString()
    }

    fun formatTime(millis: Long): String {
        val m = millis.coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d,%03d",
            m / 3_600_000,
            (m / 60_000) % 60,
            (m / 1000) % 60,
            m % 1000
        )
    }

    private val FORMATS = setOf("srt", "vtt", "ttml", "xml", "dfxp")
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
}
