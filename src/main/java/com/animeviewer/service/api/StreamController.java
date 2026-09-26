package com.animeviewer.service.api;

import com.animeviewer.service.model.Dtos.MediaFileDto;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.stream.RangeSupport;
import com.animeviewer.service.stream.RemuxStreamer;
import com.animeviewer.service.stream.TranscodeStreamer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/** S4 流式播放：
 *  - mp4/m4v/webm/mkv（H.264/VP9/AAC 等浏览器原生可解码）→ HTTP Range 随机访问（206 分段）
 *  - 其余容器（avi/ts/wmv/flv…）→ ffmpeg 转封装 fMP4 流（?t= 秒指定起点，seek 重拉）
 *  v0.28 P1 第四层 实时转码：浏览器不可解码编码（HEVC-10bit/mpeg4/mpeg2/vc1…，扫描时
 *  vcodec 已落库零探测成本）→ libx264 实时转码管道（X-AV-Transcode 头标注）；?transcode=on
 *  启用（前端设置页开关），?preset= 三档质量，?audio=N 音轨切换（P2，可解编码走转封装
 *  copy+音频转 aac，不可解编码随转码管道）。
 *  <video> 标签无法携带自定义请求头，Token 经查询参数传递（本服务为局域网个人服务，风险可接受）。 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /** 浏览器原生可播且容器可随机访问的扩展名。
     *  v0.23 SB0a 探测定案：mkv 直发（video/webm MIME + FileChannel Range）实测 h264+aac /
     *  h264+flac / HEVC-10bit 三态全部通过——duration 精确、全程 seekable、中段/结尾/回跳 seek 正常，
     *  与既有 mp4 直发行为一致（真实库样例 GTO 62s 同样通过）。直发失败由前端自动降级转封装重试。 */
    private static final Set<String> DIRECT_EXTS = Set.of("mp4", "m4v", "webm", "mkv");

    private static final Set<String> CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "video/ogg", "video/x-msvideo", "video/x-ms-wmv", "video/mp2t", "video/quicktime");

    /** 浏览器可解码的视频编码（v0.28 P1）：扫描落库 vcodec 命中 → 既有直发/转封装路径零回归；
     *  未命中（hevc/mpeg4/mpeg2video/vc1/未知 exotic 编码…）→ 转码兜底——浏览器视频解码面
     *  基本只有这四种，白名单外转码才能扩大可播面。null/空白按可解处理（保守回退旧路径）。 */
    static final Set<String> PLAYABLE_VCODECS = Set.of("h264", "vp8", "vp9", "av1");

    /** 播放路由（v0.28 P1 决策纯函数结果） */
    enum Route { DIRECT, REMUX, TRANSCODE }

    private final MediaRepository repo;
    private final RemuxStreamer remuxer;
    private final TranscodeStreamer transcoder;

    public StreamController(MediaRepository repo, RemuxStreamer remuxer, TranscodeStreamer transcoder) {
        this.repo = repo;
        this.remuxer = remuxer;
        this.transcoder = transcoder;
    }

    public static boolean isDirectExt(String ext) {
        return ext != null && DIRECT_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** v0.28 P1 路由决策（包级静态纯函数，供单测）：
     *  - 音轨参数出现 → 强制管道（DIRECT 无法换轨）：可解编码 REMUX（copy + 音频 aac），否则 TRANSCODE
     *  - transcode 关闭 → 旧路径（按 ext direct/remux，零回归）
     *  - transcode 开启 → vcodec 不可解 → TRANSCODE；可解/未知 → 旧路径 */
    static Route decideRoute(String ext, String vcodec, boolean transcodeOn, Integer audioIdx) {
        boolean playable = vcodec == null || vcodec.isBlank() || PLAYABLE_VCODECS.contains(vcodec.toLowerCase(Locale.ROOT));
        if (audioIdx != null) return playable ? Route.REMUX : Route.TRANSCODE;
        if (transcodeOn && !playable) return Route.TRANSCODE;
        return isDirectExt(ext) ? Route.DIRECT : Route.REMUX;
    }

    @GetMapping("/api/stream/{fileId}")
    public void stream(@PathVariable long fileId,
                       @RequestParam(required = false) Double t,
                       @RequestParam(required = false) String transcode,
                       @RequestParam(required = false) String preset,
                       @RequestParam(required = false) Integer audio,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        MediaFileDto file = repo.findFile(fileId).orElse(null);
        if (file == null) {
            response.sendError(404, "文件不存在");
            return;
        }
        Path path = Path.of(file.path());
        if (!Files.isRegularFile(path)) {
            response.sendError(410, "文件已不在磁盘上，请重新扫描");
            return;
        }

        boolean transcodeOn = "on".equalsIgnoreCase(transcode);
        Integer audioIdx = audio != null && audio >= 0 ? audio : null;
        Route route = decideRoute(file.ext(), file.vcodec(), transcodeOn, audioIdx);
        switch (route) {
            case DIRECT -> serveRange(path, file, request, response);
            case REMUX -> serveRemux(path, file, t, audioIdx, response);
            case TRANSCODE -> serveTranscode(path, file, t, audioIdx, preset, response);
        }
    }

    /* ── 直连 Range 流 ── */

    private void serveRange(Path path, MediaFileDto file, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long size = Files.size(path);
        RangeSupport.Result result = RangeSupport.parse(request.getHeader("Range"), size);

        response.setHeader("Accept-Ranges", "bytes");

        if (result instanceof RangeSupport.Unsatisfiable) {
            // 直接置状态不走 sendError：错误分发会继承预设的 video/mp4 Content-Type 导致 500
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        response.setContentType(contentTypeOf(file.ext()));
        if (result instanceof RangeSupport.Range r) {
            long length = r.end() - r.start() + 1;
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + r.start() + "-" + r.end() + "/" + size);
            response.setContentLengthLong(length);
            if ("HEAD".equalsIgnoreCase(request.getMethod())) return;
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                ch.position(r.start());
                copyUpTo(ch, response.getOutputStream(), length);
            }
            return;
        }
        // 200 全量
        response.setStatus(200);
        response.setContentLengthLong(size);
        if ("HEAD".equalsIgnoreCase(request.getMethod())) return;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            copyUpTo(ch, response.getOutputStream(), size);
        }
    }

    private static void copyUpTo(FileChannel ch, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int n = ch.read(java.nio.ByteBuffer.wrap(buf, 0, want));
            if (n < 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    /* ── 转封装流（fMP4）── */

    private void serveRemux(Path path, MediaFileDto file, Double t, Integer audioIdx, HttpServletResponse response) throws IOException {
        if (!remuxer.tryAcquire()) {
            response.sendError(503, "转封装并发已达上限（" + remuxer.maxConcurrent() + "），请稍后重试");
            return;
        }
        double seek = t != null && t > 0 ? t : 0;
        try {
            response.setStatus(200);
            response.setContentType("video/mp4");
            response.setHeader("X-AV-Remux", "1");
            response.setHeader("X-AV-Duration", file.durationSec() == null ? "" : String.valueOf(file.durationSec()));
            // 无 Content-Length：chunked 流式输出
            boolean ok = remuxer.pump(path.toAbsolutePath().toString(), seek, audioIdx, response.getOutputStream());
            if (!ok) {
                log.warn("转封装未产出数据: {}（-ss={}）", file.name(), seek);
            }
        } finally {
            remuxer.release();
        }
    }

    /* ── v0.28 P1 实时转码流（fMP4）── */

    private void serveTranscode(Path path, MediaFileDto file, Double t, Integer audioIdx,
                                String preset, HttpServletResponse response) throws IOException {
        // 即时拒绝：转码会话分钟级长驻，排队等待无意义（503 文案引导用户稍后再试/关其他标签页）
        if (!transcoder.tryAcquire()) {
            response.sendError(503, "已有转码任务进行中（并发上限 " + transcoder.maxConcurrent() + "），请关闭其他播放页后重试");
            return;
        }
        double seek = t != null && t > 0 ? t : 0;
        String p = TranscodeStreamer.normalizePreset(preset);
        log.info("转码播放: {}（{}p{}，preset={}，-ss={}）", file.name(),
                file.height() == null ? "?" : file.height(),
                audioIdx == null ? "" : "，音轨 " + audioIdx, p, seek);
        try {
            response.setStatus(200);
            response.setContentType("video/mp4");
            response.setHeader("X-AV-Transcode", "1");
            response.setHeader("X-AV-Preset", p);
            response.setHeader("X-AV-Duration", file.durationSec() == null ? "" : String.valueOf(file.durationSec()));
            // 无 Content-Length：chunked 流式输出（转码无随机访问，seek 由 ?t= 重拉）
            boolean ok = transcoder.pump(path.toAbsolutePath().toString(), seek, audioIdx, p, response.getOutputStream());
            if (!ok) {
                log.warn("转码未产出数据: {}（-ss={}）", file.name(), seek);
            }
        } finally {
            transcoder.release();
        }
    }

    private static String contentTypeOf(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase(Locale.ROOT)) {
            case "mp4", "m4v" -> "video/mp4";
            case "webm", "mkv" -> "video/webm"; // SB0：mkv 以 webm MIME 直发（Matroska 容器浏览器按 webm 解析）
            case "ogv" -> "video/ogg";
            case "avi" -> "video/x-msvideo";
            case "wmv" -> "video/x-ms-wmv";
            case "ts", "m2ts" -> "video/mp2t";
            case "mov" -> "video/quicktime";
            default -> "video/mp4"; // flv 等统一走转封装 fMP4 输出
        };
    }
}
