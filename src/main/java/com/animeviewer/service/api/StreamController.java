package com.animeviewer.service.api;

import com.animeviewer.service.model.Dtos.MediaFileDto;
import com.animeviewer.service.store.MediaRepository;
import com.animeviewer.service.stream.RangeSupport;
import com.animeviewer.service.stream.RemuxStreamer;
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
 *  - mp4/m4v/webm（H.264/VP9/AAC 等浏览器原生可解码）→ HTTP Range 随机访问（206 分段）
 *  - 其余容器（mkv/avi/ts/wmv/flv…）→ ffmpeg 转封装 fMP4 流（?t= 秒指定起点，seek 重拉）
 *  <video> 标签无法携带自定义请求头，Token 经查询参数传递（本服务为局域网个人服务，风险可接受）。 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    /** 浏览器原生可播且容器可随机访问的扩展名 */
    private static final Set<String> DIRECT_EXTS = Set.of("mp4", "m4v", "webm");

    private static final Set<String> CONTENT_TYPES = Set.of(
            "video/mp4", "video/webm", "video/ogg", "video/x-msvideo", "video/x-ms-wmv", "video/mp2t", "video/quicktime");

    private final MediaRepository repo;
    private final RemuxStreamer remuxer;

    public StreamController(MediaRepository repo, RemuxStreamer remuxer) {
        this.repo = repo;
        this.remuxer = remuxer;
    }

    public static boolean isDirectExt(String ext) {
        return ext != null && DIRECT_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    @GetMapping("/api/stream/{fileId}")
    public void stream(@PathVariable long fileId,
                       @RequestParam(required = false) Double t,
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

        if (isDirectExt(file.ext())) {
            serveRange(path, file, request, response);
        } else {
            serveRemux(path, file, t, response);
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

    private void serveRemux(Path path, MediaFileDto file, Double t, HttpServletResponse response) throws IOException {
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
            boolean ok = remuxer.pump(path.toAbsolutePath().toString(), seek, response.getOutputStream());
            if (!ok) {
                log.warn("转封装未产出数据: {}（-ss={}）", file.name(), seek);
            }
        } finally {
            remuxer.release();
        }
    }

    private static String contentTypeOf(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase(Locale.ROOT)) {
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "ogv" -> "video/ogg";
            case "avi" -> "video/x-msvideo";
            case "wmv" -> "video/x-ms-wmv";
            case "ts", "m2ts" -> "video/mp2t";
            case "mov" -> "video/quicktime";
            default -> "video/mp4"; // mkv/flv 等统一走转封装 fMP4 输出
        };
    }
}
