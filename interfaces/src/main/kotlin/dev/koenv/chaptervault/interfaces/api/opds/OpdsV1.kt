package dev.koenv.chaptervault.interfaces.api.opds

import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.serialization.XML

class OpdsV1 {
    private val xmlFormat =
        XML {
            repairNamespaces = true
            xmlDeclMode = XmlDeclMode.Charset
            indentString = ""
        }

    fun serialize(feed: OpdsFeed): String = xmlFormat.encodeToString(OpdsFeedXml.serializer(), OpdsFeedXml.from(feed))

    /** [template] must be absolute: the OpenSearch 1.1 URL template grammar requires a scheme and a host. */
    fun serializeSearchDescription(template: String): String =
        xmlFormat.encodeToString(
            OpenSearchDescriptionXml.serializer(),
            OpenSearchDescriptionXml(url = OpenSearchUrlXml(type = FeedBuilder.TYPE_ACQUISITION, template = template)),
        )
}
