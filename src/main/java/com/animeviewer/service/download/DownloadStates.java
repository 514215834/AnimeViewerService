package com.animeviewer.service.download;

import java.util.Set;

/** v0.16 任务状态机纯函数（JUnit 覆盖）：
 *  queued（aria2 waiting，排队）→ metadata（active 但元数据未解析，仅磁力）→ downloading（active 且文件已知）
 *  → completed / paused（用户）/ error。元数据 gid 的 complete 不映射为 completed——
 *  原型实测：aria2 元数据下载完成后会为负载生成新 gid，由 watcher 按 infoHash 收养，状态停留 metadata。 */
public final class DownloadStates {

    private DownloadStates() {}

    public static final Set<String> NON_TERMINAL = Set.of("queued", "metadata", "downloading", "paused");

    /** @param filesKnown 文件清单已知（负载任务）；false = 仍在元数据阶段或纯排队 */
    public static String mapTaskStatus(String engineStatus, boolean filesKnown) {
        return switch (engineStatus == null ? "" : engineStatus) {
            case "active" -> filesKnown ? "downloading" : "metadata";
            case "waiting" -> "queued";
            case "paused" -> "paused";
            case "error" -> "error";
            case "complete" -> filesKnown ? "completed" : "metadata";
            default -> "queued";
        };
    }

    /** aria2 files[0].path 以 [METADATA] 开头 = 元数据占位，不算文件已知 */
    public static boolean filesKnown(java.util.List<String> firstFilePath) {
        if (firstFilePath == null || firstFilePath.isEmpty()) return false;
        String p = firstFilePath.get(0);
        return p != null && !p.startsWith("[METADATA]");
    }
}
