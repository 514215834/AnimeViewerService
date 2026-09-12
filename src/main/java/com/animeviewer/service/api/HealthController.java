package com.animeviewer.service.api;

import com.animeviewer.service.bootstrap.Startup;
import com.animeviewer.service.media.ExternalTool;
import com.animeviewer.service.model.Dtos.Health;
import com.animeviewer.service.model.Dtos.ScanStatus;
import com.animeviewer.service.model.Dtos.ServiceStatus;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.store.MediaRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查（无需 Token）与服务状态（需 Token） */
@RestController
public class HealthController {

    private final ExternalTool externalTool;
    private final LibraryScanner scanner;
    private final MediaRepository repo;

    public HealthController(ExternalTool externalTool, LibraryScanner scanner, MediaRepository repo) {
        this.externalTool = externalTool;
        this.scanner = scanner;
        this.repo = repo;
    }

    @GetMapping("/api/health")
    public Health health() {
        return new Health("AnimeViewerService", Startup.VERSION,
                externalTool.ffmpegAvailable(), externalTool.ffprobeAvailable());
    }

    @GetMapping("/api/status")
    public ServiceStatus status() {
        ScanStatus scan = scanner.status();
        return new ServiceStatus(
                Startup.VERSION,
                externalTool.ffmpegAvailable(),
                externalTool.ffprobeAvailable(),
                repo.listDirectories().size(),
                repo.countAll(),
                repo.countByState("bound"),
                repo.countByState("pending"),
                repo.countByState("unmatched"),
                scan);
    }
}
