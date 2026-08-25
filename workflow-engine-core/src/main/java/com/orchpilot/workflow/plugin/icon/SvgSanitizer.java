package com.orchpilot.workflow.plugin.icon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips everything executable out of an SVG shipped by a plugin.
 *
 * <h2>How much this is actually load-bearing</h2>
 *
 * Two other things already stand between a hostile SVG and a user, and it is worth being honest about the
 * order:
 *
 * <ol>
 *   <li>Installing a plugin needs {@code PLUGIN_UPLOAD}, held only by {@code ADMIN}. Anyone who can upload an
 *       icon can already upload <em>Java that runs in the engine's JVM</em>, which is a far larger capability
 *       than script in a browser. This sanitiser is not what stops a malicious administrator.</li>
 *   <li>The console renders the icon through {@code <img src="data:...">}. An SVG loaded as an image runs in
 *       the browser's secure static mode: no scripting, no external fetches, regardless of content.</li>
 * </ol>
 *
 * <p>So this is the third layer, and it exists for the case the first two do not cover — a plugin author who
 * is not the operator, an icon that some future code path renders inline rather than as an image, or the bytes
 * being served from an endpoint that a browser would treat as a document. Sanitising at ingest means the
 * stored bytes are safe whatever later reads them, rather than the safety living in one caller's choice of tag.
 *
 * <h2>Allowlist, not denylist</h2>
 *
 * Unknown elements and attributes are dropped rather than kept. A denylist of {@code <script>} and
 * {@code on*} is the version of this that gets bypassed; the set of drawing primitives an icon needs is small
 * and closed, so an allowlist costs nothing here.
 */
public final class SvgSanitizer {

    private static final Logger log = LoggerFactory.getLogger(SvgSanitizer.class);

    /** Drawing primitives an icon legitimately needs. Everything else is removed. */
    private static final Set<String> ALLOWED_ELEMENTS = Set.of(
            "svg", "g", "defs", "title", "desc", "style", "symbol",
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan",
            "lineargradient", "radialgradient", "stop", "clippath", "mask", "pattern",
            "filter", "fegaussianblur", "feoffset", "feblend", "femerge", "femergenode",
            "fecolormatrix", "fecomposite", "feflood");

    /**
     * Presentation attributes. Deliberately excludes every {@code on*} handler and every URL-bearing
     * attribute except the few checked separately below.
     */
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "id", "class", "d", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry",
            "width", "height", "viewbox", "transform", "points", "offset", "version",
            "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
            "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset",
            "stroke-miterlimit", "opacity", "color", "display", "visibility",
            "gradientunits", "gradienttransform", "spreadmethod", "stop-color", "stop-opacity",
            "clip-path", "clip-rule", "mask", "filter", "patternunits",
            "font-family", "font-size", "font-weight", "font-style", "text-anchor", "dominant-baseline",
            "preserveaspectratio", "xmlns", "xmlns:xlink", "style",
            "stddeviation", "dx", "dy", "in", "in2", "mode", "result", "type", "values", "operator");

    /** Attributes that may carry a URL, and are kept only when it is a same-document fragment. */
    private static final Set<String> FRAGMENT_ONLY_ATTRIBUTES = Set.of(
            "clip-path", "mask", "filter", "fill", "stroke");

    /** Anything that could pull in or run code from a style declaration. */
    private static final Pattern DANGEROUS_CSS = Pattern.compile(
            "(?i)(@import|javascript:|expression\\s*\\(|behavior\\s*:|-moz-binding|url\\s*\\(\\s*[\"']?(?!#))");

    private SvgSanitizer() {
    }

    /** What a sanitising pass removed, so the operator can be told rather than left guessing. */
    public record Result(byte[] svg, List<String> removed) {

        public boolean isClean() {
            return removed.isEmpty();
        }
    }

    /**
     * @param raw the SVG bytes from the archive
     * @return the sanitised SVG and a list of what was stripped
     * @throws IllegalArgumentException when the bytes are not parseable SVG at all
     */
    public static Result sanitize(byte[] raw) {
        List<String> removed = new ArrayList<>();
        Document document = parse(raw);

        Element root = document.getDocumentElement();
        if (root == null || !"svg".equalsIgnoreCase(localName(root))) {
            throw new IllegalArgumentException("The file's root element is not <svg>.");
        }

        clean(root, removed);

        // A viewBox is what lets the icon scale to whatever size the palette or canvas asks for. Without one
        // an SVG with only width/height renders at its intrinsic size and is cropped in a 40px tile.
        if (!root.hasAttribute("viewBox") && root.hasAttribute("width") && root.hasAttribute("height")) {
            String width = numeric(root.getAttribute("width"));
            String height = numeric(root.getAttribute("height"));
            if (width != null && height != null) {
                root.setAttribute("viewBox", "0 0 " + width + " " + height);
                removed.add("added a viewBox derived from width/height so the icon scales");
            }
        }

        return new Result(serialize(document), List.copyOf(removed));
    }

    /** Recursively removes disallowed elements and attributes. */
    private static void clean(Element element, List<String> removed) {
        // Copied first: removing children while iterating a live NodeList skips siblings.
        List<Element> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }

        for (Element child : children) {
            String name = localName(child);
            if (!ALLOWED_ELEMENTS.contains(name)) {
                element.removeChild(child);
                removed.add("<" + name + ">");
                continue;
            }
            if ("style".equals(name)) {
                String css = child.getTextContent();
                if (css != null && DANGEROUS_CSS.matcher(css).find()) {
                    element.removeChild(child);
                    removed.add("<style> containing an external or executable reference");
                    continue;
                }
            }
            clean(child, removed);
        }

        cleanAttributes(element, removed);
    }

    private static void cleanAttributes(Element element, List<String> removed) {
        NamedNodeMap attributes = element.getAttributes();
        List<Attr> present = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            present.add((Attr) attributes.item(i));
        }

        for (Attr attribute : present) {
            String name = attribute.getName().toLowerCase(Locale.ROOT);
            String value = attribute.getValue() == null ? "" : attribute.getValue();

            if (!ALLOWED_ATTRIBUTES.contains(name)) {
                element.removeAttributeNode(attribute);
                // Handlers are the interesting case; the rest is noise not worth listing individually.
                if (name.startsWith("on") || name.contains("href")) {
                    removed.add(name);
                }
                continue;
            }
            if ("style".equals(name) && DANGEROUS_CSS.matcher(value).find()) {
                element.removeAttributeNode(attribute);
                removed.add("style attribute with an external or executable reference");
                continue;
            }
            // fill="url(http://evil/x)" would fetch on render in a context that permits it.
            if (FRAGMENT_ONLY_ATTRIBUTES.contains(name)
                    && value.toLowerCase(Locale.ROOT).contains("url(")
                    && !value.replace(" ", "").toLowerCase(Locale.ROOT).contains("url(#")) {
                element.removeAttributeNode(attribute);
                removed.add(name + " referencing something outside the document");
            }
        }
    }

    /** Parses with every external-entity door shut, so a plugin cannot read the engine's filesystem. */
    private static Document parse(byte[] raw) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(raw));
        } catch (Exception ex) {
            throw new IllegalArgumentException("The file is not valid SVG: " + ex.getMessage());
        }
    }

    private static byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("Could not serialise a sanitised SVG: {}", ex.getMessage());
            throw new IllegalArgumentException("The SVG could not be rewritten safely.");
        }
    }

    /** The element name without its namespace prefix, lower-cased. */
    private static String localName(Element element) {
        String name = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        int colon = name.indexOf(':');
        return (colon < 0 ? name : name.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    /** @return the leading number in a length like {@code 24px}, or null when there is none */
    private static String numeric(String length) {
        if (length == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : length.trim().toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.length() == 0 ? null : digits.toString();
    }
}
