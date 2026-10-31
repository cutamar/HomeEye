package app.homeeye.onvif

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Small helpers for reading SOAP responses by local tag name, ignoring namespace prefixes. */
internal object Xml {
    fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Camera responses never need a DTD; refusing them blocks entity tricks.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

/** All descendant elements with this local name. */
internal fun Element.all(localName: String): List<Element> {
    val nodes = getElementsByTagNameNS("*", localName)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

internal fun Element.first(localName: String): Element? = all(localName).firstOrNull()

/** Direct child elements with this local name. */
internal fun Element.children(localName: String): List<Element> {
    val out = mutableListOf<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == localName) out += n
        n = n.nextSibling
    }
    return out
}

internal fun Element.text(localName: String): String? = first(localName)?.textContent?.trim()
