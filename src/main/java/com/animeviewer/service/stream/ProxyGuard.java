package com.animeviewer.service.stream;

import java.util.List;
import java.util.Locale;

/** v0.15 O2 流代理准入校验（纯函数，供单测）：
 *  仅 http/https；域名白名单精确匹配 + 可选 "*.example.com" 通配子域；白名单为空 = 代理禁用。 */
public final class ProxyGuard {

    private ProxyGuard() {}

    public static boolean schemeAllowed(String scheme) {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    /** host 已小写；白名单条目 trim/小写化后比较，空条目忽略，空名单 = 禁用 */
    public static boolean hostAllowed(String host, List<String> whitelist) {
        if (host == null || host.isBlank() || whitelist == null || whitelist.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String raw : whitelist) {
            if (raw == null) continue;
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (entry.equals(h)) return true;
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(1); // ".example.com"
                if (h.endsWith(suffix) && h.length() > suffix.length()) return true;
            }
        }
        return false;
    }
}
