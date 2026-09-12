package com.animeviewer.service.media;

import com.animeviewer.service.ServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** ffprobe 封装：取时长 / 容器 / 编码 / 分辨率。失败不抛出（返回 null 字段并记录 error），不阻塞扫描。 */
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
}
