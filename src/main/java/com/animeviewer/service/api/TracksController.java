package com.animeviewer.service.api;

import com.animeviewer.service.media.FfprobeService;
import com.animeviewer.service.model.Dtos.AudioTrackDto;
import com.animeviewer.service.model.Dtos.ChapterDto;
import com.animeviewer.service.store.MediaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** v0.28 P2 多音轨与章节（只读枚举，与 SB1 字幕轨端点同构）：
 *  - GET /api/files/{id}/audios → 音轨枚举（ffprobe -select_streams a，index 与 -map 0:a:N 同口径）
 *  - GET /api/files/{id}/chapters → 章节枚举（ffprobe -show_chapters，start/end 文件绝对秒）
 *  与播放路径无关——直发 / 转封装 / 转码三路播放均可消费。 */
@RestController
public class TracksController {

    private final MediaRepository repo;
    private final FfprobeService probe;

    public TracksController(MediaRepository repo, FfprobeService probe) {
        this.repo = repo;
        this.probe = probe;
    }

    @GetMapping("/api/files/{id}/audios")
    public ResponseEntity<Map<String, List<AudioTrackDto>>> audios(@PathVariable long id) {
        var file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        if (!Files.isRegularFile(Path.of(file.path()))) return ResponseEntity.ok(Map.of("tracks", List.of()));
        return ResponseEntity.ok(Map.of("tracks", probe.audios(file.path())));
    }

    @GetMapping("/api/files/{id}/chapters")
    public ResponseEntity<Map<String, List<ChapterDto>>> chapters(@PathVariable long id) {
        var file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        if (!Files.isRegularFile(Path.of(file.path()))) return ResponseEntity.ok(Map.of("chapters", List.of()));
        return ResponseEntity.ok(Map.of("chapters", probe.chapters(file.path())));
    }
}
