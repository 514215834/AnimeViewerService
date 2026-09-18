package com.animeviewer.service.api;

import com.animeviewer.service.media.FfprobeService;
import com.animeviewer.service.model.Dtos.SubtitleTrackDto;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.stream.SubtitleStreamer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** v0.23 SB1 内封字幕：
 *  - GET /api/files/{id}/subtitles → 字幕轨枚举（ffprobe -select_streams s，无轨返回空列表）
 *  - GET /api/files/{id}/subtitle/{track} → 第 track 条字幕轨转 WebVTT 流式输出
 *  字幕从源文件直接提取，与播放路径无关——SB0 直发 / 转封装管道流两路播放均可加载。
 *  Token 走 ?token= 查询参数（与 stream 双通道一致，ArtPlayer subtitle fetch 无法带自定义头）。 */
@RestController
public class SubtitleController {

    private final MediaRepository repo;
    private final FfprobeService probe;
    private final SubtitleStreamer extractor;

    public SubtitleController(MediaRepository repo, FfprobeService probe, SubtitleStreamer extractor) {
        this.repo = repo;
        this.probe = probe;
        this.extractor = extractor;
    }

    @GetMapping("/api/files/{id}/subtitles")
    public ResponseEntity<Map<String, List<SubtitleTrackDto>>> subtitles(@PathVariable long id) {
        var file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        List<SubtitleTrackDto> tracks = probe.subtitles(file.path());
        return ResponseEntity.ok(Map.of("tracks", tracks));
    }

    @GetMapping("/api/files/{id}/subtitle/{track}")
    public void subtitle(@PathVariable long id,
                         @PathVariable int track,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        var file = repo.findFile(id).orElse(null);
        if (file == null) {
            response.sendError(404, "文件不存在");
            return;
        }
        Path path = Path.of(file.path());
        if (!Files.isRegularFile(path)) {
            response.sendError(410, "文件已不在磁盘上，请重新扫描");
            return;
        }
        if (track < 0 || track >= 32) {
            response.sendError(400, "字幕轨序号非法");
            return;
        }
        if (!extractor.tryAcquire()) {
            response.sendError(503, "字幕提取并发已达上限，请稍后重试");
            return;
        }
        try {
            response.setStatus(200);
            response.setContentType("text/vtt; charset=utf-8");
            response.setHeader("X-AV-Sub-Index", String.valueOf(track));
            // 无 Content-Length：chunked 流式输出（与转封装流一致）；
            // 无产出 = 轨不存在或无法转换（头已提交只能截断，前端按空字幕处理）
            extractor.pump(path.toAbsolutePath().toString(), track, response.getOutputStream());
        } finally {
            extractor.release();
        }
    }
}
