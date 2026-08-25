package com.orchpilot.pluginserver.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cookie that keeps this registry's session separate from the workflow platform's.
 *
 * <h2>What these tests are defending</h2>
 *
 * Cookies are scoped by host and path and <b>not by port</b>, so a registry console on {@code localhost:4300}
 * and a workflow console on {@code localhost:4200} share one cookie jar, and both services put their refresh
 * cookie on {@code /api/auth}. The only thing separating the two sessions is the cookie name. If these tests
 * ever fail, signing in or out of one application will silently end the other's session — which is the bug
 * they exist to prevent, and which is invisible until somebody complains about being logged out.
 */
class RefreshCookiesTest {

    /** What the workflow platform issues. This registry must never read, write or clear it. */
    private static final String PLATFORM_COOKIE = "workflow_refresh_token";

    private AuthProperties properties;
    private RefreshCookies cookies;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        cookies = new RefreshCookies(properties);
    }

    @Test
    @DisplayName("the cookie name differs from the workflow platform's")
    void nameIsDistinct() {
        assertFalse(properties.getCookie().getName().equals(PLATFORM_COOKIE),
                "sharing a cookie name with the workflow platform makes each sign-in end the other session");
        assertEquals("plugin_registry_refresh", properties.getCookie().getName());
    }

    @Test
    @DisplayName("an issued cookie is HttpOnly, SameSite=Strict and scoped to the auth endpoints")
    void issuesASafeCookie() {
        String header = cookies.issue("a-refresh-token");

        assertTrue(header.startsWith("plugin_registry_refresh=a-refresh-token"));
        assertTrue(header.contains("HttpOnly"), "a token readable by script is a token an injection can take");
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Path=/api/auth"));
    }

    @Test
    @DisplayName("the platform's cookie on the same request is ignored")
    void ignoresThePlatformCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Exactly what a browser sends to localhost:4300 once somebody has signed in to both applications.
        request.setCookies(
                new Cookie(PLATFORM_COOKIE, "the-platform-session"),
                new Cookie("plugin_registry_refresh", "our-session"));

        assertEquals("our-session", cookies.read(request).orElseThrow());
    }

    @Test
    @DisplayName("a request carrying only the platform's cookie reads as no session here")
    void platformCookieAloneIsNoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(PLATFORM_COOKIE, "the-platform-session"));

        assertTrue(cookies.read(request).isEmpty(),
                "a workflow session must not be mistaken for a registry session");
    }

    @Test
    @DisplayName("clearing names only this registry's cookie")
    void clearsOnlyItsOwn() {
        String header = cookies.clear();

        assertTrue(header.startsWith("plugin_registry_refresh="));
        assertFalse(header.contains(PLATFORM_COOKIE),
                "signing out here must leave a workflow session alone");
        // Every attribute has to match the cookie that was set, or the browser treats it as a different
        // cookie and leaves the original in place: a sign-out that appears to work and does not.
        assertTrue(header.contains("Path=/api/auth"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("no cookies at all reads as no session, rather than failing")
    void noCookies() {
        assertTrue(cookies.read(new MockHttpServletRequest()).isEmpty());
    }

    @Test
    @DisplayName("an empty value is not a session")
    void emptyValueIsNotASession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("plugin_registry_refresh", ""));

        assertTrue(cookies.read(request).isEmpty());
    }
}
