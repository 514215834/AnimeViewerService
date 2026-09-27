package com.animeviewer.service.config;

import com.animeviewer.service.ServiceProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.0 补记四 后端出口网络设置纯函数：默认值 / KV JSON 往返 / 校验 / 归一（对齐 AiSettingsTest 惯例）。 */
class NetworkSettingsTest {

    private static final ServiceProperties NULL_PROPS = new ServiceProperties(null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void defaultsFallBackWhenPropsNull() {
        // yml 缺省（ServiceProperties 构造器兜底 127.0.0.1:7897 auto）
        NetworkSettings d = NetworkSettings.defaults(NULL_PROPS);
        assertEquals("auto", d.proxyMode());
        assertEquals("127.0.0.1", d.proxyHost());
        assertEquals(7897, d.proxyPort());
        assertTrue(d.hasProxy());
        assertNull(d.validate());
    }

    @Test
    void jsonRoundTripPreservesFields() {
        NetworkSettings s = new NetworkSettings("proxy", "127.0.0.1", 7897);
        assertEquals(s, NetworkSettings.load(s.toJson(), NetworkSettings.defaults(NULL_PROPS)));
    }

    @Test
    void corruptOrEmptyJsonFallsBackToDefaults() {
        NetworkSettings defaults = new NetworkSettings("auto", "", null);
        assertEquals(defaults, NetworkSettings.load("{broken", defaults));
        assertEquals(defaults, NetworkSettings.load(null, defaults));
        assertEquals(defaults, NetworkSettings.load("", defaults));
    }

    @Test
    void partialJsonMergesWithDefaults() {
        NetworkSettings back = NetworkSettings.load("{\"proxyMode\":\"PROXY\",\"proxyHost\":\" 10.0.0.1 \"}",
                new NetworkSettings("auto", "", null));
        // 模式归一小写、地址不在此 trim（normalized() 负责），端口缺省补默认
        assertEquals("proxy", back.proxyMode());
        assertEquals(" 10.0.0.1 ", back.proxyHost());
        assertNull(back.proxyPort());
    }

    @Test
    void normalizedTrimsAndLowercases() {
        NetworkSettings n = new NetworkSettings("  PROXY ", " 127.0.0.1 ", 7897).normalized();
        assertEquals("proxy", n.proxyMode());
        assertEquals("127.0.0.1", n.proxyHost());
    }

    @Test
    void validateRejectsBadInput() {
        assertEquals("线路模式需为 auto / direct / proxy",
                new NetworkSettings("fallback", "127.0.0.1", 7897).validate());
        assertEquals("代理端口需在 1~65535",
                new NetworkSettings("auto", "127.0.0.1", 0).validate());
        assertEquals("代理端口需在 1~65535",
                new NetworkSettings("auto", "127.0.0.1", 70000).validate());
        assertEquals("仅代理（proxy）模式需填写代理地址",
                new NetworkSettings("proxy", "", 7897).validate());
        assertNull(new NetworkSettings("direct", "", null).validate());
        // 填了地址但端口缺失：按端口非法引导补填（UI 端口有默认值 7897，此形态多为手误）
        assertEquals("代理端口需在 1~65535",
                new NetworkSettings("auto", "127.0.0.1", null).validate());
    }

    @Test
    void hasProxyRequiresHostAndPort() {
        assertFalse(new NetworkSettings("auto", "", 7897).hasProxy());
        assertFalse(new NetworkSettings("auto", "127.0.0.1", null).hasProxy());
        assertFalse(new NetworkSettings("auto", "127.0.0.1", 0).hasProxy());
        assertTrue(new NetworkSettings("auto", "127.0.0.1", 7897).hasProxy());
    }
}
