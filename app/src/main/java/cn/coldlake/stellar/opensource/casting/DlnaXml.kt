package cn.coldlake.stellar.opensource.casting

import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import javax.xml.parsers.DocumentBuilderFactory

/** Android's DOM factory does not support the Xerces DTD feature switches. */
internal object DlnaXml {
    fun parse(bytes: ByteArray): Element {
        require(bytes.size <= 1024 * 1024)
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val stack = ArrayDeque<Element>()
        var count = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> error("DLNA XML must not contain a DTD")
                XmlPullParser.START_TAG -> {
                    require(++count <= 4096 && stack.size < 64)
                    val name = parser.prefix?.takeIf(String::isNotEmpty)?.let { "$it:${parser.name}" } ?: parser.name
                    val element = doc.createElementNS(parser.namespace.takeIf(String::isNotEmpty), name)
                    for (n in 0 until parser.attributeCount) {
                        val prefix = parser.getAttributePrefix(n)?.takeIf(String::isNotEmpty)
                        val attribute = (prefix?.let { "$it:" } ?: "") + parser.getAttributeName(n)
                        element.setAttributeNS(parser.getAttributeNamespace(n).takeIf(String::isNotEmpty), attribute, parser.getAttributeValue(n))
                    }
                    if (stack.isEmpty()) doc.appendChild(element) else stack.last().appendChild(element)
                    stack.addLast(element)
                }
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val text = parser.text ?: error("Unresolved XML entity")
                    if (stack.isNotEmpty()) stack.last().appendChild(doc.createTextNode(text))
                }
            }
        }
        require(stack.isEmpty())
        return requireNotNull(doc.documentElement)
    }
}
