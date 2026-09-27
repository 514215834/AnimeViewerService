package com.animeviewer.service.config;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** v1.0 补记四 后端出口网络设置：yml（av.bangumi.proxy-*）提供默认值，SQLite settings 表存 JSON 覆盖
 *  （key=network），前端设置页读写；字段缺失/类型不符/损坏 JSON 回退默认。
 *  proxyMode 线路模式：auto（默认，直连失败自动经代理重试）/ direct（仅直连）/ proxy（仅代理）。
 *  消费方（原直读 av.bangumi().proxy*）：BangumiMatcher（v0 搜索/匹配）、DownloadService（种子抓取）、
 *  HanimeService（在线解析）、ResourceService（RSS 检索）、RangeForwarder（在线流转发）——
 *  统一经 NetworkSettingsProvider.current() 取当前值，懒建代理客户端的消费方以 version() 变化重建。 */
public record NetworkSettings(String proxyMode, String proxyHost, Integer proxyPort) {

    public static final String STORE_KEY = "network";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static NetworkSettings defaults(ServiceProperties props) {
        ServiceProperties.Bangumi b = props.bangumi();
        return new NetworkSettings(
                normalizeMode(b.proxyMode()),
                b.proxyHost() == null ? "" : b.proxyHost(),
                b.proxyPort());
    }

    /** 存储覆盖（可部分字段）叠加到默认值 */
    public static NetworkSettings load(String storedJson, NetworkSettings defaults) {
        if (storedJson == null || storedJson.isBlank()) return defaults;
        try {
            JsonNode n = MAPPER.readTree(storedJson);
            JsonNode mode = n.get("proxyMode");
            JsonNode host = n.get("proxyHost");
            JsonNode port = n.get("proxyPort");
            // 注意：proxyPort 可空——三元两支须同为 Integer，否则 int 提升会把 null 拆箱 NPE（被 catch 吞成回退默认）
            return new NetworkSettings(
                    mode != null && mode.isTextual() && !mode.asText().isBlank()
                            ? normalizeMode(mode.asText()) : defaults.proxyMode(),
                    host != null && host.isTextual() ? host.asText() : defaults.proxyHost(),
                    port != null && port.isInt() ? Integer.valueOf(port.asInt()) : defaults.proxyPort());
        } catch (Exception e) {
            return defaults;
        }
    }

    private static String normalizeMode(String v) {
        return v == null || v.isBlank() ? "auto" : v.trim().toLowerCase();
    }

    public String toJson() {
        try {
            var node = MAPPER.createObjectNode();
            node.put("proxyMode", proxyMode);
            node.put("proxyHost", proxyHost == null ? "" : proxyHost);
            if (proxyPort != null) node.put("proxyPort", proxyPort);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 保存前归一：模式小写、地址 trim */
    public NetworkSettings normalized() {
        return new NetworkSettings(normalizeMode(proxyMode), proxyHost == null ? "" : proxyHost.trim(), proxyPort);
    }

    /** 基础校验（控制器层转 400）；返回错误消息，null = 通过 */
    public String validate() {
        String mode = normalizeMode(proxyMode);
        if (!"auto".equals(mode) && !"direct".equals(mode) && !"proxy".equals(mode)) return "线路模式需为 auto / direct / proxy";
        boolean hasHost = proxyHost != null && !proxyHost.isBlank();
        if (hasHost && (proxyPort == null || proxyPort < 1 || proxyPort > 65535)) return "代理端口需在 1~65535";
        if ("proxy".equals(mode) && !hasHost) return "仅代理（proxy）模式需填写代理地址";
        return null;
    }

    /** 代理地址是否完整可用（host 非空且端口有效） */
    public boolean hasProxy() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort != null && proxyPort >= 1 && proxyPort <= 65535;
    }
}
