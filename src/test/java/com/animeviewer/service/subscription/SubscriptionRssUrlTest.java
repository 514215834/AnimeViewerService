package com.animeviewer.service.subscription;

import com.animeviewer.service.download.DownloadException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v0.25 RSS 固定直链订阅：直链 URL 净化用例（trim/空白清除/前缀校验，update 接口同口径）。 */
class SubscriptionRssUrlTest {

    @Test
    void sanitizeTrimsAndAcceptsHttpS() {
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=3061",
                SubscriptionService.sanitizeRssUrl("  https://mikanani.me/RSS/Bangumi?bangumiId=3061  "));
        assertEquals("http://127.0.0.1:8991/mikan.xml", SubscriptionService.sanitizeRssUrl("http://127.0.0.1:8991/mikan.xml"));
    }

    @Test
    void blankMeansClear() {
        assertNull(SubscriptionService.sanitizeRssUrl(null));
        assertNull(SubscriptionService.sanitizeRssUrl(""));
        assertNull(SubscriptionService.sanitizeRssUrl("   "));
    }

    @Test
    void invalidPrefixRejected() {
        assertThrows(DownloadException.class, () -> SubscriptionService.sanitizeRssUrl("mikanani.me/RSS/Bangumi"));
        assertThrows(DownloadException.class, () -> SubscriptionService.sanitizeRssUrl("ftp://x/y"));
        assertThrows(DownloadException.class, () -> SubscriptionService.sanitizeRssUrl("magnet:?xt=urn:btih:" + "a".repeat(40)));
    }
}
