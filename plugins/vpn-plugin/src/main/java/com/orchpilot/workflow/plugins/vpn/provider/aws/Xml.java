package com.orchpilot.workflow.plugins.vpn.provider.aws;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough XML to read the AWS EC2 query API's replies, with the JDK's own parser.
 *
 * <h2>Why the JDK parser and not a library</h2>
 *
 * The {@code javax.xml} parser is in the platform, delegated to plugins parent-first, so it costs nothing to
 * bundle and is already hardened. The EC2 query protocol returns XML — {@code DescribeVpnConnections} puts the
 * tunnel state in {@code vgwTelemetry/status} — and reading a few named elements out of it needs a DOM walk,
 * not a mapping framework. The factory is configured to ignore DTDs and external entities, because the input
 * is a remote response and an XML parser that resolves external entities is an SSRF and file-read primitive.
 */
final class Xml {

    private Xml() {
    }

    /**
     * Parses an XML document, safely.
     *
     * @param text the document
     * @return the root element, as an opaque node the other methods here understand
     */
    static Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Refuse DTDs and external entities: this is a remote response, and entity resolution would turn
            // the parser into a way to read local files or reach internal URLs.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            return document.getDocumentElement();
        } catch (Exception ex) {
            throw new IllegalArgumentException("The provider's XML response could not be parsed: "
                    + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * @param node a node from {@link #parse} or {@link #all}
     * @param tag  an element name
     * @return the text of the first descendant with that name, or empty
     */
    static String text(Object node, String tag) {
        Element found = first(node, tag);
        return found == null ? "" : found.getTextContent().trim();
    }

    /**
     * @param node a node
     * @param tag  an element name
     * @return every descendant element with that name
     */
    static List<Object> all(Object node, String tag) {
        List<Object> results = new ArrayList<>();
        if (node instanceof Element element) {
            NodeList list = element.getElementsByTagName(tag);
            for (int index = 0; index < list.getLength(); index++) {
                results.add(list.item(index));
            }
        }
        return results;
    }

    private static Element first(Object node, String tag) {
        if (!(node instanceof Element element)) {
            return null;
        }
        NodeList list = element.getElementsByTagName(tag);
        for (int index = 0; index < list.getLength(); index++) {
            Node item = list.item(index);
            if (item instanceof Element found) {
                return found;
            }
        }
        return null;
    }
}
