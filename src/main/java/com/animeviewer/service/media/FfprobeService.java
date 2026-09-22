package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
import com.animeviewer.service.model.Dtos.AudioTrackDto;
import com.animeviewer.service.model.Dtos.ChapterDto;
import com.animeviewer.service.model.Dtos.SubtitleTrackDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** ffprobe 封装：取时长 / 容器 / 编码 / 分辨率；v0.23 SB1 增加字幕轨枚举。失败不抛出（返回 null 字段并记录 error），不阻塞扫描。 */
@Component
public class FfprobeService {

    private static final Logger log = LoggerFactory.getLogger(FfprobeService.class);

    private final ServiceProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public FfprobeService(ServiceProperties props) {
        this.props = props;
    }

    public record Probe(Double durationSec, String container, String vcodec, String acodec,
                        Integer width, Integer height, String error) {}

    public Probe probe(String path) {
        int timeout = Math.max(5, props.scan().probeTimeoutSeconds());
        try {
            var pb = new ProcessBuilder(
                    props.ffprobePath(), "-v", "error",
                    "-print_format", "json",
                    "-show_format", "-show_streams",
                    path);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            String stdout;
            try (var in = proc.getInputStream()) {
                stdout = new String(in.readAllBytes());
            }
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new Probe(null, null, null, null, null, null, "ffprobe 超时");
            }
            if (proc.exitValue() != 0) {
                String err = new String(proc.getErrorStream().readAllBytes());
                return new Probe(null, null, null, null, null, null, trimErr(err));
            }
            return parseJson(stdout);
        } catch (Exception e) {
            log.warn("ffprobe 执行失败: {} ({})", path, e.toString());
            return new Probe(null, null, null, null, null, null, "ffprobe 执行失败: " + e.getMessage());
        }
    }

    private Probe parseJson(String stdout) {
        try {
            JsonNode root = mapper.readTree(stdout);
            JsonNode format = root.path("format");
            Double duration = format.path("duration").isMissingNode() ? null : format.path("duration").asDouble();
            String container = format.path("format_name").asText(null);
            String vcodec = null;
            String acodec = null;
            Integer width = null;
            Integer height = null;
            for (JsonNode s : root.path("streams")) {
                String type = s.path("codec_type").asText("");
                if ("video".equals(type) && vcodec == null
                        && s.path("disposition").path("attached_pic").asInt(0) != 1) {
                    vcodec = s.path("codec_name").asText(null);
                    width = s.path("width").isInt() ? s.path("width").asInt() : null;
                    height = s.path("height").isInt() ? s.path("height").asInt() : null;
                } else if ("audio".equals(type) && acodec == null) {
                    acodec = s.path("codec_name").asText(null);
                }
            }
            return new Probe(duration, container, vcodec, acodec, width, height, null);
        } catch (Exception e) {
            return new Probe(null, null, null, null, null, null, "ffprobe 输出解析失败: " + e.getMessage());
        }
    }

    private static String trimErr(String err) {
        if (err == null || err.isBlank()) return "ffprobe 失败";
        String one = err.strip().replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(one.length() - 200) : one;
    }

    /* ── v0.23 SB1 字幕轨枚举 ── */

    /** 枚举内封字幕轨（-select_streams s）：失败返回空列表并记录 debug（无字幕轨是常态，不算错误）。 */
    public List<SubtitleTrackDto> subtitles(String path) {
        try {
            var pb = new ProcessBuilder(
                    props.ffprobePath(), "-v", "error",
                    "-print_format", "json",
                    "-select_streams", "s",
                    "-show_streams",
                    path);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            String stdout;
            try (var in = proc.getInputStream()) {
                stdout = new String(in.readAllBytes());
            }
            boolean finished = proc.waitFor(Math.max(5, props.scan().probeTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return List.of();
            }
            if (proc.exitValue() != 0) {
                log.debug("字幕轨枚举失败: {} tail={}", path, trimErr(new String(proc.getErrorStream().readAllBytes())));
                return List.of();
            }
            return parseSubtitleTracks(stdout);
        } catch (Exception e) {
            log.warn("字幕轨枚举失败: {} ({})", path, e.toString());
            return List.of();
        }
    }

    /** 解析 -select_streams s 输出（包级可见纯函数，供单测）：
     *  只收 codec_type=subtitle 的流；index 为字幕轨序号（按出现顺序 0 基），language/title 可缺省（如实为 null）。 */
    List<SubtitleTrackDto> parseSubtitleTracks(String stdout) {
        var out = new ArrayList<SubtitleTrackDto>();
        try {
            JsonNode root = mapper.readTree(stdout);
            for (JsonNode s : root.path("streams")) {
                if (!"subtitle".equals(s.path("codec_type").asText(""))) continue;
                JsonNode tags = s.path("tags");
                String lang = tags.path("language").isMissingNode() || tags.path("language").isNull()
                        ? null : tags.path("language").asText();
                String title = tags.path("title").isMissingNode() || tags.path("title").isNull()
                        ? null : tags.path("title").asText();
                out.add(new SubtitleTrackDto(out.size(), s.path("codec_name").asText(null), lang, title));
            }
        } catch (Exception e) {
            log.debug("字幕轨解析失败: {}", e.toString());
            return List.of();
        }
        return out;
    }

    /* ── v0.28 P2 多音轨与章节 ── */

    /** 枚举音轨（-select_streams a）：失败返回空列表并记录 debug（单音轨/无音轨是常态）。 */
    public List<AudioTrackDto> audios(String path) {
        String stdout = selectStreamsJson(path, "a");
        if (stdout == null) return List.of();
        return parseAudioTracks(stdout);
    }

    /** 解析 -select_streams a 输出（包级可见纯函数，供单测）：
     *  只收 codec_type=audio 的流；index 为音轨序号（按出现顺序 0 基——与 -map 0:a:N 同口径），
     *  channels/language/title 可缺省（如实为 null）。 */
    List<AudioTrackDto> parseAudioTracks(String stdout) {
        var out = new ArrayList<AudioTrackDto>();
        try {
            JsonNode root = mapper.readTree(stdout);
            for (JsonNode s : root.path("streams")) {
                if (!"audio".equals(s.path("codec_type").asText(""))) continue;
                JsonNode tags = s.path("tags");
                String lang = tags.path("language").isMissingNode() || tags.path("language").isNull()
                        ? null : tags.path("language").asText();
                String title = tags.path("title").isMissingNode() || tags.path("title").isNull()
                        ? null : tags.path("title").asText();
                Integer channels = s.path("channels").isInt() ? s.path("channels").asInt() : null;
                out.add(new AudioTrackDto(out.size(), s.path("codec_name").asText(null), channels, lang, title));
            }
        } catch (Exception e) {
            log.debug("音轨解析失败: {}", e.toString());
            return List.of();
        }
        return out;
    }

    /** 枚举章节（-show_chapters）：失败返回空列表并记录 debug（无章节是常态）。 */
    public List<ChapterDto> chapters(String path) {
        try {
            var pb = new ProcessBuilder(
                    props.ffprobePath(), "-v", "error",
                    "-print_format", "json",
                    "-show_chapters",
                    path);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            String stdout;
            try (var in = proc.getInputStream()) {
                stdout = new String(in.readAllBytes());
            }
            boolean finished = proc.waitFor(Math.max(5, props.scan().probeTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return List.of();
            }
            if (proc.exitValue() != 0) {
                log.debug("章节枚举失败: {} tail={}", path, trimErr(new String(proc.getErrorStream().readAllBytes())));
                return List.of();
            }
            return parseChapters(stdout);
        } catch (Exception e) {
            log.warn("章节枚举失败: {} ({})", path, e.toString());
            return List.of();
        }
    }

    /** 解析 -show_chapters 输出（包级可见纯函数，供单测）：
     *  start_time/end_time 为秒（字符串形态）；title 可缺省；start > end 的脏数据跳过。 */
    List<ChapterDto> parseChapters(String stdout) {
        var out = new ArrayList<ChapterDto>();
        try {
            JsonNode root = mapper.readTree(stdout);
            for (JsonNode c : root.path("chapters")) {
                double start = parseSeconds(c.path("start_time").asText(null));
                double end = parseSeconds(c.path("end_time").asText(null));
                if (start < 0 || end <= start) continue;
                JsonNode tags = c.path("tags");
                String title = tags.path("title").isMissingNode() || tags.path("title").isNull()
                        ? null : tags.path("title").asText();
                out.add(new ChapterDto(start, end, title));
            }
        } catch (Exception e) {
            log.debug("章节解析失败: {}", e.toString());
            return List.of();
        }
        return out;
    }

    private static double parseSeconds(String v) {
        if (v == null || v.isBlank()) return -1;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** -select_streams 枚举公共执行体（audios 复用）；失败返回 null */
    private String selectStreamsJson(String path, String streamType) {
        try {
            var pb = new ProcessBuilder(
                    props.ffprobePath(), "-v", "error",
                    "-print_format", "json",
                    "-select_streams", streamType,
                    "-show_streams",
                    path);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            String stdout;
            try (var in = proc.getInputStream()) {
                stdout = new String(in.readAllBytes());
            }
            boolean finished = proc.waitFor(Math.max(5, props.scan().probeTimeoutSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) {
                log.debug("音轨枚举失败: {} tail={}", path, trimErr(new String(proc.getErrorStream().readAllBytes())));
                return null;
            }
            return stdout;
        } catch (Exception e) {
            log.warn("音轨枚举失败: {} ({})", path, e.toString());
            return null;
        }
    }
}
