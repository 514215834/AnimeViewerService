package com.animeviewer.service.stream;

/** S4 HTTP Range 请求头解析（纯函数，供单测）。仅支持单区间（浏览器媒体播放的实际形态）。
 *  结果三态：FULL（无 Range 或不可解析 → 200 全量）/ RANGE（206）/ UNSATISFIABLE（416）。 */
public final class RangeSupport {

    private RangeSupport() {}

    public sealed interface Result permits Full, Range, Unsatisfiable {}

    public record Full() implements Result {}

    /** 闭区间 [start, end]，end 已钳制到 size-1 */
    public record Range(long start, long end) implements Result {}

    public record Unsatisfiable() implements Result {}

    public static Result parse(String rangeHeader, long size) {
        if (rangeHeader == null || rangeHeader.isBlank() || size <= 0) return new Full();
        String h = rangeHeader.trim().toLowerCase();
        if (!h.startsWith("bytes=")) return new Full();
        String spec = h.substring("bytes=".length()).trim();
        if (spec.contains(",")) spec = spec.substring(0, spec.indexOf(',')).trim(); // 多区间只取第一段
        int dash = spec.indexOf('-');
        if (dash < 0) return new Full();
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        try {
            if (startStr.isEmpty()) {
                // 后缀区间 bytes=-N：最后 N 字节
                if (endStr.isEmpty()) return new Full();
                long n = Long.parseLong(endStr);
                if (n <= 0) return new Unsatisfiable();
                long start = Math.max(0, size - n);
                return new Range(start, size - 1);
            }
            long start = Long.parseLong(startStr);
            if (start >= size) return new Unsatisfiable();
            long end = endStr.isEmpty() ? size - 1 : Long.parseLong(endStr);
            if (end < start) return new Full();
            if (end >= size) end = size - 1;
            return new Range(start, end);
        } catch (NumberFormatException e) {
            return new Full();
        }
    }
}
