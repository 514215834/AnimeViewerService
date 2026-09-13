package com.animeviewer.service.download;

import com.animeviewer.service.model.Dtos.DownloadFileDto;

import java.util.List;
import java.util.Map;

/**
 * v0.18 引擎抽象层：DownloadService/DownloadController 仅消费本接口。
 * 方法面 = v0.16/v0.17 实际消费的调用（addUri/addTorrent/status/list/pause/resume/
 * applySelection/remove/version），任务标识统一用「任务键」（aria2=gid，直开=infohash/临时种子路径）。
 * 实现方：Aria2Adapter（包装既有 Aria2Engine/Aria2Client，行为零变化）、
 * ExternalAppAdapter（qBittorrent 外部应用直开，无 RPC）。
 */
public interface DownloadEngine {

    /** 引擎状态（与前端 SvcDownloadEngine 对应） */
    record EngineInfo(boolean available, String mode, String version, String downloadDir, String error) {}

    /** 任务快照（watcher 同步用；taskId 为 DB 主键，taskKey 为引擎侧任务键） */
    record TaskSnapshot(
            String taskKey,
            String engineStatus,
            long totalLength,
            long completedLength,
            long downloadSpeed,
            long uploadSpeed,
            int connections,
            int seeds,
            List<DownloadFileDto> files,
            String infoHash,
            String btName,
            String errorMessage) {}

    /** 引擎侧任务键（入队返回；aria2=gid，直开=infohash/临时种子文件路径） */
    String enqueue(String uri, String infoHash, String downloadDir) throws DownloadException;

    /** 幂等连接/就绪检查；不可用时返回 available=false + 真实原因 */
    EngineInfo ensureRunning();

    /** 重启引擎（托管模式销毁重拉；外部模式重连；直开=重新校验路径配置） */
    EngineInfo restart();

    TaskSnapshot status(String taskKey) throws DownloadException;

    /** 非终止任务键列表（watcher 用；直开模式无 RPC，返回空） */
    List<String> activeTaskKeys() throws DownloadException;

    void pause(String taskKey) throws DownloadException;

    void resume(String taskKey) throws DownloadException;

    /** 文件勾选（aria2=select-file；直开模式不支持，抛业务提示） */
    void applySelection(String taskKey, List<Integer> indexes) throws DownloadException;

    /** 删除任务；deleteFiles 由实现方决定引擎侧是否删文件（aria2 由服务端删，直开=qBt 自管不动文件） */
    void remove(String taskKey, boolean deleteFiles) throws DownloadException;

    /** 注入公共 tracker（aria2=入队时已合并进磁力 uri，空实现；直开同理） */
    default void addTrackers(String taskKey, List<String> trackers) throws DownloadException {}

    /** 引擎类型标识（aria2 / qbittorrent；DownloadService 按此分派特殊逻辑） */
    String type();
}
