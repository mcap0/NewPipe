/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Small helpers to write and read the XML documents used by UPnP.
 *
 * The DOM parser from the JDK is used (instead of Android's XmlPullParser) so that the parsing
 * code can be tested with plain JVM unit tests.
 */
object XmlUtils {

    /**
     * Escapes a string so that it can be put in XML text or in an attribute value.
     *
     * Characters which are not allowed in XML 1.0 at all (most control characters and unpaired
     * surrogates) are removed, since a single one of them makes the whole document invalid and
     * TVs reject it. Emojis and other characters outside the BMP are kept as they are: they are
     * valid XML and the document is sent as UTF-8.
     */
    fun escape(text: String): String {
        val result = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> result.append("&amp;")

                c == '<' -> result.append("&lt;")

                c == '>' -> result.append("&gt;")

                c == '"' -> result.append("&quot;")

                c == '\'' -> result.append("&apos;")

                Character.isHighSurrogate(c) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        result.append(c).append(text[i + 1])
                        i++
                    }
                    // an unpaired high surrogate is dropped
                }

                Character.isLowSurrogate(c) -> Unit

                // unpaired low surrogate, dropped
                isValidXmlChar(c) -> result.append(c)

                else -> Unit // invalid control character, dropped
            }
            i++
        }
        return result.toString()
    }

    private fun isValidXmlChar(c: Char): Boolean {
        return c == '\t' || c == '\n' || c == '\r' ||
            (c in ' '..'퟿') || (c in ''..'�')
    }

    /**
     * Parses an XML document in a namespace aware way, with external entities disabled (the
     * documents come from devices on the local network and must not be able to read files).
     */
    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false
        // some devices send a BOM or whitespace before the XML declaration
        val cleaned = xml.trimStart('', ' ', '\n', '\r', '\t')
        return factory.newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
    }

    private fun trySetFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // feature not supported by this parser implementation, nothing to do
        }
    }

    /** @return the direct child elements of [parent] with the given local name */
    fun children(parent: Node, localName: String): List<Element> {
        val result = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && localNameOf(node) == localName) {
                result.add(node)
            }
        }
        return result
    }

    /** @return the first direct child element of [parent] with the given local name */
    fun child(parent: Node, localName: String): Element? = children(parent, localName).firstOrNull()

    /** @return the trimmed text of the first direct child with the given local name */
    fun childText(parent: Node, localName: String): String? = child(parent, localName)?.textContent?.trim()

    /** @return the first element with the given local name anywhere below [parent] */
    fun findFirst(parent: Node, localName: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) {
                if (localNameOf(node) == localName) {
                    return node
                }
                findFirst(node, localName)?.let { return it }
            }
        }
        return null
    }

    private fun localNameOf(element: Element): String = element.localName ?: element.tagName.substringAfter(':')
}
