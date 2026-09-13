package com.animeviewer.service.download;

import com.animeviewer.service.model.Dtos.DownloadFileDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.18 QB1 引擎抽象层的 aria2 实现：包装既有 Aria2Engine/Aria2Client，行为零变化
 * （v0.16/v0.17 全部下载行为经此透传）。任务键 = aria2 gid。
 */
@Component
public class Aria2Adapter implements DownloadEngine {

    private final Aria2Engine engine;

    public Aria2Adapter(Aria2Engine engine) {
        this.engine = engine;
    }

    @Override
    public String type() {
        return "aria2";
    }

    @Override
    public String enqueue(String uri, String infoHash, String downloadDir) throws DownloadException {
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) {
            throw new DownloadException(503, "下载引擎不可用：" + info.error());
        }
        Aria2Client client = engine.client();
        try {
            return client.addUri(List.of(uri), Map.of("dir", info.downloadDir()));
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "引擎拒绝任务: " + e.getMessage());
        }
    }

    /** 添加 .torrent 种子内容（v0.16 DN2：服务端抓内容走 addTorrent，文件清单立即可知） */
    public String enqueueTorrent(byte[] torrentBytes, String infoHash, String downloadDir) throws DownloadException {
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) {
            throw new DownloadException(503, "下载引擎不可用：" + info.error());
        }
        Aria2Client client = engine.client();
        try {
            return client.addTorrent(java.util.Base64.getEncoder().encodeToString(torrentBytes),
                    List.of(), Map.of("dir", info.downloadDir()));
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "引擎拒绝任务: " + e.getMessage());
        }
    }

    @Override
    public EngineInfo ensureRunning() {
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        return new DownloadEngine.EngineInfo(info.available(), info.mode(), info.version(),
                info.downloadDir(), info.error());
    }

    @Override
    public EngineInfo restart() {
        Aria2Engine.EngineInfo info = engine.restart();
        return new DownloadEngine.EngineInfo(info.available(), info.mode(), info.version(),
                info.downloadDir(), info.error());
    }

    @Override
    public TaskSnapshot status(String taskKey) throws DownloadException {
        Aria2Client client = requireClient();
        Map<String, Object> st = client.tellStatus(taskKey, List.of(
                "status", "totalLength", "completedLength", "downloadSpeed", "uploadSpeed",
                "connections", "numSeeds", "numSeeders", "bittorrent", "errorMessage", "files", "infoHash"));
        String engineStatus = Aria2Client.asString(st, "status");
        List<DownloadFileDto> files = filesOf(st);
        String btName = btName(st);
        String errorMessage = "error".equals(engineStatus) ? Aria2Client.asString(st, "errorMessage") : null;
        return new TaskSnapshot(taskKey, engineStatus,
                Aria2Client.asLong(st, "totalLength"), Aria2Client.asLong(st, "completedLength"),
                Aria2Client.asLong(st, "downloadSpeed"), Aria2Client.asLong(st, "uploadSpeed"),
                (int) Aria2Client.asLong(st, "connections"),
                (int) Math.max(Aria2Client.asLong(st, "numSeeds"), Aria2Client.asLong(st, "numSeeders")),
                files, Aria2Client.asString(st, "infoHash"), btName, errorMessage);
    }

    /** 非终止任务键（aria2：active + waiting 列表的 gid；paused 由 DB 状态跟踪） */
    @Override
    public List<String> activeTaskKeys() throws DownloadException {
        Aria2Client client = requireClient();
        List<String> keys = List.of("gid");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : client.tellList("aria2.tellActive", keys)) {
            String gid = Aria2Client.asString(m, "gid");
            if (gid != null) out.add(gid);
        }
        for (Map<String, Object> m : client.tellPaged("aria2.tellWaiting", 0, 50, keys)) {
            String gid = Aria2Client.asString(m, "gid");
            if (gid != null) out.add(gid);
        }
        return out;
    }

    @Override
    public void pause(String taskKey) throws DownloadException {
        Aria2Client client = requireClient();
        try {
            client.forcePause(taskKey);
        } catch (Aria2Client.GidNotFoundException e) {
            throw new DownloadException(409, "引擎中无此任务（可能已结束），等待状态同步");
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "暂停失败: " + e.getMessage());
        }
    }

    @Override
    public void resume(String taskKey) throws DownloadException {
        Aria2Client client = requireClient();
        try {
            client.unpause(taskKey);
        } catch (Aria2Client.GidNotFoundException e) {
            throw new DownloadException(409, "引擎中无此任务（可能已结束），等待状态同步");
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "恢复失败: " + e.getMessage());
        }
    }

    /** 文件勾选（v0.16 原型结论：active 任务先暂停→changeOption→恢复） */
    @Override
    public void applySelection(String taskKey, List<Integer> indexes) throws DownloadException {
        Aria2Client client = requireClient();
        String csv = indexes.stream().filter(x -> x != null && x >= 1)
                .map(String::valueOf).distinct()
                .reduce((a, b) -> a + "," + b).orElse("");
        if (csv.isEmpty()) throw new DownloadException(400, "至少选择一个文件");
        try {
            // 先读当前状态判断是否运行中（暂停态 changeOption 可写）
            Map<String, Object> st = client.tellStatus(taskKey, List.of("status"));
            boolean wasRunning = "active".equals(Aria2Client.asString(st, "status"));
            if (wasRunning) client.forcePause(taskKey);
            client.changeOption(taskKey, Map.of("select-file", csv, "bt-remove-unselected-file", "true"));
            if (wasRunning) client.unpause(taskKey);
        } catch (Aria2Client.GidNotFoundException e) {
            throw new DownloadException(409, "引擎中无此任务（可能已结束），等待状态同步");
        } catch (Aria2Client.Aria2Exception e) {
            throw new DownloadException(502, "应用文件选择失败: " + e.getMessage());
        }
    }

    @Override
    public void remove(String taskKey, boolean deleteFiles) throws DownloadException {
        // 引擎侧移除；文件删除由 DownloadService 服务端执行（安全护栏 + .aria2 清理）
        Aria2Client client = engine.client();
        if (client != null) {
            try {
                client.forceRemove(taskKey);
                client.purgeDownloadResult();
            } catch (Exception ignored) {
                // 引擎侧可能已无此任务
            }
        }
    }

    /** tracker 注入在入队时已合并进磁力 uri（MagnetParser.mergeTrackers）——空实现 */
    @Override
    public void addTrackers(String taskKey, List<String> trackers) {
    }

    private Aria2Client requireClient() throws DownloadException {
        Aria2Engine.EngineInfo info = engine.ensureRunning();
        if (!info.available()) throw new DownloadException(503, "下载引擎不可用：" + info.error());
        return engine.client();
    }

    private static String btName(Map<String, Object> st) {
        Object bt = st.get("bittorrent");
        if (bt instanceof Map<?, ?> m) {
            Object info = m.get("info");
            if (info instanceof Map<?, ?> im) {
                Object name = im.get("name");
                if (name != null) return String.valueOf(name);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawFiles(Map<String, Object> st) {
        Object files = st.get("files");
        return files instanceof List ? (List<Map<String, Object>>) files : List.of();
    }

    private static List<DownloadFileDto> filesOf(Map<String, Object> st) {
        return rawFiles(st).stream()
                .map(f -> new DownloadFileDto(
                        (int) Aria2Client.asLong(f, "index"),
                        Aria2Client.asString(f, "path"),
                        fileName(Aria2Client.asString(f, "path")),
                        Aria2Client.asLong(f, "length"),
                        Aria2Client.asLong(f, "completedLength"),
                        Aria2Client.asLong(f, "selected") == 1))
                .toList();
    }

    private static String fileName(String path) {
        if (path == null) return "";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }
}
