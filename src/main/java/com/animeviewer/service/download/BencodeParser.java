package com.animeviewer.service.download;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** v0.24 SE2 bencode 纯函数（对齐 RssResourceParser/MagnetParser 同款模式，JUnit 护航）：
 *  从 .torrent 种子内容定位顶层 dict 的 "info" 值段原始字节并 sha1——即 BTIH（40 位 hex 小写），
 *  补齐种子任务跨轮 infoHash 去重（订阅 existsInfohash / 入队 409）与 qBt 直开任务键。
 *  真值对拍夹具：nyaa #2160516 种子（src/test/resources/v024-spyfamily-2160516.torrent），
 *  sha1(info) 与站点 RSS 标注 nyaa:infoHash 一致（2026-09-19 实测）。
 *  只做最小解构（定位 info 段，不建树）；任何形态不合法返回 null，不抛异常（入队链路容错优先）。 */
public final class BencodeParser {

    private BencodeParser() {}

    /** 种子内容 → BTIH（40hex 小写）；缺 info 键 / bencode 非法 / 非顶层 dict 返回 null */
    public static String infoHashHex(byte[] torrent) {
        int[] seg = locateInfo(torrent);
        if (seg == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(torrent, seg[0], seg[1] - seg[0]);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 顶层 dict 中 "info" 键对应值段的 [start, end)；找不到/非法返回 null */
    static int[] locateInfo(byte[] b) {
        if (b == null || b.length < 2 || b[0] != 'd') return null;
        try {
            int i = 1;
            while (i < b.length && b[i] != 'e') {
                int colon = indexOfByte(b, i, ':');
                if (colon < 0) return null;
                int len = Integer.parseInt(new String(b, i, colon - i, StandardCharsets.US_ASCII));
                if (len < 0 || colon + 1 + len > b.length) return null;
                String key = new String(b, colon + 1, len, StandardCharsets.UTF_8);
                int valueStart = colon + 1 + len;
                int valueEnd = skipValue(b, valueStart);
                if ("info".equals(key)) return new int[]{valueStart, valueEnd};
                i = valueEnd;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 跳过一个 bencode 值，返回结束下标（exclusive）；结构非法抛异常 */
    private static int skipValue(byte[] b, int i) {
        if (i >= b.length) throw new IllegalArgumentException("截断的 bencode");
        char c = (char) (b[i] & 0xff);
        switch (c) {
            case 'i': {
                int e = indexOfByte(b, i, 'e');
                if (e < 0) throw new IllegalArgumentException("截断的整数");
                return e + 1;
            }
            case 'd': case 'l': {
                int i2 = i + 1;
                if (c == 'd') {
                    while (i2 < b.length && b[i2] != 'e') {
                        i2 = skipValue(b, i2); // key（必为字符串）
                        i2 = skipValue(b, i2); // value
                    }
                } else {
                    while (i2 < b.length && b[i2] != 'e') i2 = skipValue(b, i2);
                }
                if (i2 >= b.length) throw new IllegalArgumentException("截断的容器");
                return i2 + 1;
            }
            default: {
                if (c < '0' || c > '9') throw new IllegalArgumentException("非法 bencode 标记: " + c);
                int colon = indexOfByte(b, i, ':');
                if (colon < 0) throw new IllegalArgumentException("截断的字符串");
                long len = Long.parseLong(new String(b, i, colon - i, StandardCharsets.US_ASCII));
                if (len < 0 || colon + 1 + len > b.length) throw new IllegalArgumentException("字符串长度越界");
                return (int) (colon + 1 + len);
            }
        }
    }

    /** 自 from 起首个指定字节下标（bencode 无转义，逐字节安全）；找不到返回 -1 */
    private static int indexOfByte(byte[] b, int from, char target) {
        for (int i = from; i < b.length; i++) {
            if ((char) (b[i] & 0xff) == target) return i;
        }
        return -1;
    }
}
