package com.animeviewer.service.api;

import com.animeviewer.service.model.Dtos.DirectoryDto;
import com.animeviewer.service.model.Dtos.DirectoryRequest;
import com.animeviewer.service.model.Dtos.ScanRequest;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.store.MediaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** S2 目录配置 CRUD + 扫描触发 / 状态 */
@RestController
@RequestMapping("/api")
public class DirectoryController {

    private final MediaRepository repo;
    private final LibraryScanner scanner;

    public DirectoryController(MediaRepository repo, LibraryScanner scanner) {
        this.repo = repo;
        this.scanner = scanner;
    }

    @GetMapping("/directories")
    public Object list() {
        return repo.listDirectories();
    }

    @PostMapping("/directories")
    public Object add(@RequestBody DirectoryRequest req) {
        if (req == null || req.path() == null || req.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "path 不能为空"));
        }
        Path path = Path.of(req.path().trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            return ResponseEntity.badRequest().body(Map.of("message", "目录不存在: " + path));
        }
        if (repo.findDirectoryByPath(path.toString()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "目录已在列表中"));
        }
        repo.insertDirectory(path.toString());
        // 添加目录后立即触发增量扫描（异步）
        scanner.startAsync(false);
        return ResponseEntity.ok().body(Map.of("message", "已添加并开始扫描"));
    }

    @DeleteMapping("/directories/{id}")
    public Object delete(@PathVariable long id) {
        repo.deleteDirectory(id);
        return ResponseEntity.ok().body(Map.of("message", "已移除目录及其索引"));
    }

    @PostMapping("/scan")
    public Object scan(@RequestBody(required = false) ScanRequest req) {
        boolean full = req != null && Boolean.TRUE.equals(req.full());
        boolean started = scanner.startAsync(full);
        if (!started) {
            return ResponseEntity.status(409).body(Map.of("message", "扫描正在进行中"));
        }
        return ResponseEntity.accepted().body(Map.of("message", full ? "全量重扫已开始" : "增量扫描已开始"));
    }

    @GetMapping("/scan/status")
    public Object scanStatus() {
        return scanner.status();
    }
}
