package com.animeviewer.service;

import com.animeviewer.service.stream.ProxyGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.15 O2 代理准入用例：scheme 限定、白名单精确/通配匹配、空名单 = 禁用。 */
class ProxyGuardTest {

    @Test
    void onlyHttpAndHttpsAllowed() {
        assertTrue(ProxyGuard.schemeAllowed("http"));
        assertTrue(ProxyGuard.schemeAllowed("https"));
        assertFalse(ProxyGuard.schemeAllowed("ftp"));
        assertFalse(ProxyGuard.schemeAllowed("file"));
        assertFalse(ProxyGuard.schemeAllowed(null));
        assertFalse(ProxyGuard.schemeAllowed(""));
    }

    @Test
    void emptyWhitelistDisablesProxy() {
        assertFalse(ProxyGuard.hostAllowed("cdn.example.com", List.of()));
        assertFalse(ProxyGuard.hostAllowed("cdn.example.com", null));
        assertFalse(ProxyGuard.hostAllowed("cdn.example.com", List.of("", " ")));
    }

    @Test
    void exactMatchCaseInsensitive() {
        assertTrue(ProxyGuard.hostAllowed("CDN.Example.com", List.of("cdn.example.com", "other.net")));
        assertFalse(ProxyGuard.hostAllowed("evil-cdn.example.com", List.of("cdn.example.com")));
        assertFalse(ProxyGuard.hostAllowed("cdn.example.com.evil.io", List.of("cdn.example.com")));
        assertFalse(ProxyGuard.hostAllowed(null, List.of("cdn.example.com")));
    }

    @Test
    void wildcardMatchesSubdomainsOnly() {
        List<String> wl = List.of("*.example.com");
        assertTrue(ProxyGuard.hostAllowed("a.example.com", wl));
        assertTrue(ProxyGuard.hostAllowed("deep.b.example.com", wl));
        assertFalse(ProxyGuard.hostAllowed("example.com", wl));
        assertFalse(ProxyGuard.hostAllowed("example.com.evil.io", wl));
    }
}
