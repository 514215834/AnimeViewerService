package com.animeviewer.service.stream;

import com.animeviewer.service.ServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** v0.28 P1 实时转码流：浏览器不可解码编码（HEVC-10bit / mpeg4 / mpeg2 / vc1…）→
 *  ffmpeg libx264 实时转码 h264 + aac fMP4 管道（-pix_fmt yuv420p 强制 8bit——10bit H.264
 *  同样不可解；-crf 23；音频统一 aac 192k，规避 flac/dts 在 fMP4 的兼容性赌博）。
 *  「直播式」流与 RemuxStreamer 同语义：无随机访问，seek 由前端 ?t= 重拉（-ss 输入定位）。
 *  探测定案（2026-09-22，1080p HEVC-10bit 40s 样片实测）：superfast 6.1x / fast 3.0x /
 *  medium 2.5x 实时，首块延迟 2.1~2.5s。
 *  并发 1（分钟级长驻 + CPU 重负载，超限即时拒绝 503——与 remux 短会话排队语义不同）；
 *  硬件加速（QSV/NVENC）本轮不做（环境差异大），登记已知边界。 */
@Component
public class TranscodeStreamer {

    private static final Logger log = LoggerFactory.getLogger(TranscodeStreamer.class);

    /** 前端质量档白名单（设置页三档；越界值回退 superfast） */
    public static final Set<String> PRESETS = Set.of("superfast", "fast", "medium");
    public static final String DEFAULT_PRESET = "superfast";

    private final ServiceProperties props;
    private final Semaphore permits;
    private final int maxConcurrent;

    public TranscodeStreamer(ServiceProperties props) {
        this.props = props;
        this.maxConcurrent = props.stream() == null || props.stream().transcodeMaxConcurrent() == null
                ? 1 : Math.max(1, props.stream().transcodeMaxConcurrent());
        this.permits = new Semaphore(maxConcurrent);
    }

    /** 即时获取（不等待）：转码会话长驻，等待无意义。false = 503「已有转码任务进行中」。 */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** preset 白名单归一（null/越界 → superfast） */
    public static String normalizePreset(String preset) {
        return preset != null && PRESETS.contains(preset.toLowerCase(Locale.ROOT))
                ? preset.toLowerCase(Locale.ROOT) : DEFAULT_PRESET;
    }

    /** 将转码流拷贝到响应输出流（阻塞直至完成 / 客户端断开）。返回是否成功产出字节。 */
    public boolean pump(String filePath, double seekSeconds, Integer audioIdx, String preset, OutputStream out) {
        var pb = new ProcessBuilder(buildCommand(filePath, seekSeconds, audioIdx, preset));
        pb.redirectErrorStream(false);
        Process proc = null;
        boolean wroteAny = false;
        try {
            proc = pb.start();
            // stderr 侧线读取（防止缓冲区塞满阻塞进程），仅保留尾部用于日志
            TailReader tail = new TailReader(proc.getErrorStream());
            Thread tailThread = new Thread(tail, "ffmpeg-transcode-stderr");
            tailThread.setDaemon(true);
            tailThread.start();

            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (n > 0) wroteAny = true;
                }
            }
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (proc.exitValue() != 0) {
                log.warn("转码退出码 {}: {} preset={} tail={}", proc.exitValue(), filePath, preset, tail.tail());
                return wroteAny;
            }
            return wroteAny;
        } catch (Exception e) {
            // 客户端中途断开（seek 重拉 / 暂停退出 / 关页）属正常路径，仅 debug
            log.debug("转码流中断: {} ({})", filePath, e.toString());
            return wroteAny;
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
    }

    /** 命令组装（包级可见，供单测断言参数形态）。audioIdx 非空 = 音轨切换（-map 0:a:N?，轨序
     *  为 codec_type=audio 出现顺序 0 基，与 ffprobe -select_streams a 枚举同口径）。 */
    String[] buildCommand(String filePath, double seekSeconds, Integer audioIdx, String preset) {
        String p = normalizePreset(preset);
        var cmd = new ArrayList<String>();
        cmd.add(props.ffmpegPath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        if (seekSeconds > 0) {
            cmd.add("-ss");
            cmd.add(String.valueOf(seekSeconds));
        }
        cmd.add("-i");
        cmd.add(filePath);
        cmd.add("-map");
        cmd.add("0:v:0");
        cmd.add("-map");
        cmd.add("0:a:" + (audioIdx == null || audioIdx < 0 ? "0?" : Math.min(audioIdx, 63) + "?"));
        cmd.add("-sn");
        cmd.add("-dn");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add(p);
        cmd.add("-crf");
        cmd.add("23");
        // 10bit 输入强制 8bit 输出：Hi10P H.264 浏览器同样不可解
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add("-movflags");
        cmd.add("+frag_keyframe+empty_moov");
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add("pipe:1");
        return cmd.toArray(String[]::new);
    }

    /** stderr 尾部缓冲（≤2KB），与 RemuxStreamer.TailReader 同实现 */
    private static final class TailReader implements Runnable {
        private final InputStream in;
        private final StringBuilder tail = new StringBuilder();

        TailReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    tail.append(new String(buf, 0, n));
                    if (tail.length() > 2048) tail.delete(0, tail.length() - 2048);
                }
            } catch (Exception ignored) {
            }
        }

        String tail() {
            return tail.toString().strip();
        }
    }
}
