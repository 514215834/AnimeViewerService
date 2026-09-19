package com.animeviewer.service.api;

import com.animeviewer.service.ai.AiPrompts;
import com.animeviewer.service.ai.AiService;
import com.animeviewer.service.media.BangumiMatcher;
import com.animeviewer.service.media.LibraryScanner;
import com.animeviewer.service.media.NameParser;
import com.animeviewer.service.model.Dtos.BangumiEpisodeDto;
import com.animeviewer.service.model.Dtos.BangumiSubjectDto;
import com.animeviewer.service.model.Dtos.MatchRequest;
import com.animeviewer.service.model.Dtos.MatchOutcome;
import com.animeviewer.service.model.Dtos.MediaFileDto;
import com.animeviewer.service.model.Dtos.Page;
import com.animeviewer.service.model.Dtos.SubjectFileDto;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.store.MediaRepository.MediaFileRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 文件索引与匹配状态机：列表 / 详情 / 人工确认绑定 / 改绑 / 解绑 / 重匹配 / 条目维度查询。 */
@RestController
@RequestMapping("/api")
public class FilesController {

    private final MediaRepository repo;
    private final BangumiMatcher matcher;
    private final LibraryScanner scanner;
    private final AiService ai;

    public FilesController(MediaRepository repo, BangumiMatcher matcher, LibraryScanner scanner, AiService ai) {
        this.repo = repo;
        this.matcher = matcher;
        this.scanner = scanner;
        this.ai = ai;
    }

    @GetMapping("/files")
    public Page<MediaFileDto> list(@RequestParam(required = false) Long dirId,
                                   @RequestParam(required = false) String state,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset) {
        limit = Math.clamp(limit, 1, 200);
        var items = repo.listFiles(dirId, state, q, limit, offset);
        long total = repo.countFiles(dirId, state, q);
        return new Page<>(items, total, limit, offset);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<MediaFileDto> detail(@PathVariable long id) {
        return repo.findFile(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** 人工确认 / 改绑：subjectId + episodeSort 直接落库为 bound */
    @PostMapping("/files/{id}/match")
    public ResponseEntity<?> match(@PathVariable long id, @RequestBody MatchRequest req) {
        if (req == null || req.subjectId() <= 0 || req.sort() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "subjectId 与 sort 必须为正整数"));
        }
        MediaFileDto file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        BangumiSubjectDto subject = matcher.subject(req.subjectId());
        repo.updateMatch(id, "bound", req.subjectId(),
                subject == null ? null : subject.name(),
                subject == null ? null : subject.nameCn(),
                req.sort(), false);
        return ResponseEntity.ok(Map.of("message", "已绑定"));
    }

    /** 解绑：回到未识别态（保留解析结果，等待重新匹配或人工绑定） */
    @PostMapping("/files/{id}/unbind")
    public ResponseEntity<?> unbind(@PathVariable long id) {
        MediaFileDto file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        boolean hasTitle = file.parsedTitle() != null && !file.parsedTitle().isBlank();
        repo.updateMatch(id, hasTitle ? "pending" : "unmatched", null, null, null, null, false);
        return ResponseEntity.ok(Map.of("message", "已解绑"));
    }

    /** 单文件重匹配（立即执行并返回结果） */
    @PostMapping("/files/{id}/rematch")
    public ResponseEntity<?> rematch(@PathVariable long id) {
        MediaFileDto file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        String title = file.parsedTitle();
        if (title == null || title.isBlank()) {
            NameParser.ParsedName reparsed = NameParser.parse(file.name());
            title = reparsed.title();
        }
        MatchOutcome outcome = matcher.match(title, file.parsedEpisode());
        long rowId = id;
        Integer ep = file.parsedEpisode();
        scanner.applyMatch(rowId, outcome, ep);
        return ResponseEntity.ok(outcome);
    }

    /** v0.22 AI3：文件名语义解析兜底（NameParser 正则失败的乱名/标题党文件）。
     *  LLM 判定标题与集数 → 回写 parsed_title/parsed_episode；随后用解析标题走 BangumiMatcher
     *  **预填关联条目**（v0.22 实测优化：此前「确认」还要人工弹窗搜索——现在待确认行直接带
     *  「疑似《X》」，点「确认」一键绑定）。仍置 pending，不自动绑定（人工把关不变式）；
     *  已绑定文件走「解绑」后再解析。 */
    @PostMapping("/files/{id}/ai-analyze")
    public ResponseEntity<?> aiAnalyze(@PathVariable long id) {
        MediaFileDto file = repo.findFile(id).orElse(null);
        if (file == null) return ResponseEntity.notFound().build();
        if ("bound".equals(file.matchState())) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件已绑定，请先解绑再 AI 解析"));
        }
        if (!ai.settings().ready()) {
            return ResponseEntity.badRequest().body(Map.of("message", "AI 未启用或未配置（设置页「AI 分析」填写接口地址与模型）"));
        }
        var node = ai.askJson(AiPrompts.fileNameSystem(), AiPrompts.fileNameUser(file.name()));
        if (node == null || !node.isObject()) {
            return ResponseEntity.status(502).body(Map.of("message", "AI 解析失败（服务不可用或返回格式异常）"));
        }
        String title = node.path("title").asText("").trim();
        Integer ep = node.path("episode").isInt() && node.path("episode").asInt() > 0
                && node.path("episode").asInt() <= 999 ? node.path("episode").asInt() : null;
        if (title.isBlank() && ep == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "AI 未能从文件名解析出有效信息"));
        }
        String newTitle = title.isBlank() ? file.parsedTitle() : title;
        Integer newEp = ep != null ? ep : file.parsedEpisode();
        repo.updateParsed(id, newTitle, newEp, "pending");
        // 解析出的标题立刻匹配 Bangumi 条目并预填（pending + 条目 = 「疑似《X》」，确认一键绑定）
        Long subjectId = null;
        String subjectLabel = null;
        if (newTitle != null && !newTitle.isBlank()) {
            MatchOutcome outcome = matcher.match(newTitle, newEp);
            if (outcome.subjectId() != null) {
                subjectId = outcome.subjectId();
                subjectLabel = outcome.subjectNameCn() == null || outcome.subjectNameCn().isBlank()
                        ? outcome.subjectName() : outcome.subjectNameCn();
                repo.updateMatch(id, "pending", outcome.subjectId(), outcome.subjectName(),
                        outcome.subjectNameCn(), newEp, false);
            }
        }
        return ResponseEntity.ok(Map.of(
                "title", newTitle == null ? "" : newTitle,
                "episode", newEp == null ? 0 : newEp,
                "subjectId", subjectId == null ? 0 : subjectId,
                "subjectName", subjectLabel == null ? "" : subjectLabel,
                "message", subjectId == null
                        ? "AI 解析完成，未找到相近条目（可人工绑定）"
                        : "AI 解析完成：已关联《" + subjectLabel + "》，点「确认」完成绑定"));
    }

    /** 条目维度：某 Bangumi 条目已绑定的文件（详情页剧集 Tab 渲染播放按钮） */
    @GetMapping("/subjects/{subjectId}/files")
    public List<SubjectFileDto> bySubject(@PathVariable long subjectId) {
        return repo.filesBySubject(subjectId).stream()
                .map(f -> new SubjectFileDto(f.id(), f.episodeSort() == null ? 0 : f.episodeSort(),
                        f.name(), f.durationSec(), f.ext(), StreamController.isDirectExt(f.ext())))
                .toList();
    }

    /* ── Bangumi 查询（人工改绑选择器用；服务端直连无 CORS，自定义合规 UA） ── */

    @GetMapping("/bangumi/search")
    public Map<String, List<BangumiSubjectDto>> search(@RequestParam String kw) {
        return Map.of("list", matcher.search(kw));
    }

    @GetMapping("/bangumi/subjects/{subjectId}/episodes")
    public Map<String, List<BangumiEpisodeDto>> episodes(@PathVariable long subjectId) {
        return Map.of("list", matcher.episodes(subjectId));
    }
}
