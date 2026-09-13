package com.animeviewer.service.download;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** v0.16 DN4 下载设置：yml（av.aria2.*）提供默认值，SQLite settings 表存 JSON 覆盖（key=download），
 *  前端设置页读写；字段缺失/类型不符时回退默认（损坏 JSON 整体回退）。
 *  v0.18 用户定案（不用 WebUI 对接）：engineType（aria2-managed/aria2-external/qbittorrent）+
 *  qbPath（qBittorrent 可执行文件路径，直开模式唯一字段）；engineType 缺省 aria2-managed 兼容旧库。 */
public record DownloadSettings(
        String engineType,
        String enginePath, String engineUrl, String engineSecret, int rpcPort,
        String qbPath,
        String downloadDir, int maxConcurrent, String uploadLimit,
        List<String> trackers, boolean autoScan, int seedTimeMinutes, boolean checkCertificate) {

    public static final String STORE_KEY = "download";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 引擎类型常量 */
    public static final String TYPE_ARIA2_MANAGED = "aria2-managed";
    public static final String TYPE_ARIA2_EXTERNAL = "aria2-external";
    public static final String TYPE_QBITTORRENT = "qbittorrent";

    public boolean aria2Managed() { return !TYPE_ARIA2_EXTERNAL.equals(engineType) && !TYPE_QBITTORRENT.equals(engineType); }
    public boolean aria2External() { return TYPE_ARIA2_EXTERNAL.equals(engineType); }
    public boolean qbittorrent() { return TYPE_QBITTORRENT.equals(engineType); }

    public static DownloadSettings defaults(ServiceProperties props) {
        ServiceProperties.Aria2 a = props.aria2();
        return new DownloadSettings(
                TYPE_ARIA2_MANAGED,
                a.path(), a.externalUrl(), a.externalSecret(),
                a.rpcPort() == null ? 16800 : a.rpcPort(),
                "",
                a.downloadDir(),
                a.maxConcurrent() == null ? 2 : a.maxConcurrent(),
                a.uploadLimit() == null ? "" : a.uploadLimit(),
                a.trackers() == null ? List.of() : a.trackers(),
                a.autoScan(),
                a.seedTimeMinutes() == null ? 0 : a.seedTimeMinutes(),
                a.checkCertificate());
    }

    /** 存储覆盖（可部分字段）叠加到默认值 */
    public static DownloadSettings load(String storedJson, DownloadSettings defaults) {
        if (storedJson == null || storedJson.isBlank()) return defaults;
        try {
            JsonNode n = MAPPER.readTree(storedJson);
            return new DownloadSettings(
                    engineTypeOf(n, defaults.engineType()),
                    text(n, "enginePath", defaults.enginePath()),
                    text(n, "engineUrl", defaults.engineUrl()),
                    text(n, "engineSecret", defaults.engineSecret()),
                    intOf(n, "rpcPort", defaults.rpcPort()),
                    text(n, "qbPath", defaults.qbPath()),
                    text(n, "downloadDir", defaults.downloadDir()),
                    intOf(n, "maxConcurrent", defaults.maxConcurrent()),
                    text(n, "uploadLimit", defaults.uploadLimit()),
                    trackers(n, defaults.trackers()),
                    boolOf(n, "autoScan", defaults.autoScan()),
                    intOf(n, "seedTimeMinutes", defaults.seedTimeMinutes()),
                    boolOf(n, "checkCertificate", defaults.checkCertificate()));
        } catch (Exception e) {
            return defaults;
        }
    }

    /** engineType 归一：旧库无此字段回退默认；兼容旧值 aria2/aria2-managed 写法 */
    private static String engineTypeOf(JsonNode n, String def) {
        String v = text(n, "engineType", def);
        return switch (v) {
            case "aria2", "aria2-managed", "" -> TYPE_ARIA2_MANAGED;
            case "aria2-external" -> TYPE_ARIA2_EXTERNAL;
            case "qbittorrent" -> TYPE_QBITTORRENT;
            default -> def;
        };
    }

    public String toJson() {
        try {
            var node = MAPPER.createObjectNode();
            node.put("engineType", engineType);
            node.put("enginePath", enginePath);
            node.put("engineUrl", engineUrl);
            node.put("engineSecret", engineSecret);
            node.put("rpcPort", rpcPort);
            node.put("qbPath", qbPath);
            node.put("downloadDir", downloadDir);
            node.put("maxConcurrent", maxConcurrent);
            node.put("uploadLimit", uploadLimit);
            node.set("trackers", MAPPER.valueToTree(trackers));
            node.put("autoScan", autoScan);
            node.put("seedTimeMinutes", seedTimeMinutes);
            node.put("checkCertificate", checkCertificate);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 基础校验（控制器层转 400）；返回错误消息，null = 通过 */
    public String validate() {
        if (rpcPort < 1 || rpcPort > 65535) return "RPC 端口需在 1~65535";
        if (maxConcurrent < 1 || maxConcurrent > 10) return "同时活动任务数需在 1~10";
        if (downloadDir == null || downloadDir.isBlank()) return "下载目录不能为空";
        if (seedTimeMinutes < 0 || seedTimeMinutes > 100000) return "做种分钟数需 ≥ 0";
        if (qbittorrent() && (qbPath == null || qbPath.isBlank())) return "qBittorrent 直开模式需填写 qBittorrent 可执行文件路径";
        return null;
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : def;
    }

    private static int intOf(JsonNode n, String field, int def) {
        JsonNode v = n.get(field);
        return v != null && v.isInt() ? v.asInt() : def;
    }

    private static boolean boolOf(JsonNode n, String field, boolean def) {
        JsonNode v = n.get(field);
        return v != null && v.isBoolean() ? v.asBoolean() : def;
    }

    private static List<String> trackers(JsonNode n, List<String> def) {
        JsonNode v = n.get("trackers");
        if (v == null || !v.isArray()) return def;
        List<String> out = new ArrayList<>();
        v.forEach(x -> { if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText().trim()); });
        return List.copyOf(out);
    }
}
