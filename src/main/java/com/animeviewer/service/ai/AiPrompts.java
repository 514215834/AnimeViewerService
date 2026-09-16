package com.animeviewer.service.ai;

/** v0.22 AI 提示词集中管理（AI1 命中判定 / AI2 关键词扩展 / AI3 文件名解析）。
 *  全部要求**只输出 JSON**（解析走 AiService.parseLooseJson 容错）；失败即静默降级，不影响主链路。 */
public final class AiPrompts {

    private AiPrompts() {}

    /** AI1：判定 RSS 命中资源是否本篇正片。输出 JSON：{"type":"episode|op|ed|other","episode":int|null,"isMainline":bool,"reason":"一句话"} */
    public static String hitJudgeSystem() {
        return """
                你是动漫资源发布标题的语义分析器。根据「条目名」与「资源标题」判断该资源是否为该条目的本篇正片某话。\
                只输出一个 JSON 对象，不要任何多余文字，字段：
                {"type":"episode|op|ed|other","episode":数字或null,"isMainline":布尔,"reason":"不超过40字的判定依据"}
                判定规则：
                - type=episode：资源标题包含本篇正片的第 N 话/集标记（裸数字、第N话、EP N、SxxEyy 均算），episode 填解析出的集数，isMainline=true
                - type=op/ed：OP/ED/主题曲/Theme Song/NCOP/NCED 等，episode=null，isMainline=false
                - type=other：轻小说/漫画/Raw 书籍/菜单/特典/预告/合集卷/与该条目无关的其他作品，isMainline=false
                - 注意「HEVC-10bit」「48kHz/24bit」「2026.07.08」「Vol.1」这类发布参数不是集数
                - 条目名是动画标题；资源标题包含条目名（原文/罗马字/别名/繁简变体）且有话数标记才判 episode
                - 资源标题提及的是第二季/另一部作品（季号冲突）时 isMainline=false，reason 说明季号冲突
                """;
    }

    public static String hitJudgeUser(String subjectNameCn, String subjectName, String parsedEpisode, String title) {
        return "条目名（中文）：" + safe(subjectNameCn) + "\n条目名（原文）：" + safe(subjectName)
                + "\n启发式解析集数：" + safe(parsedEpisode)
                + "\n资源标题：" + safe(title)
                + "\n请输出判定 JSON。";
    }

    /** AI2：为订阅条目生成扩展检索关键词（RSS 站点是标题子串匹配——中文名全句常查不到，需罗马字/官方英文名/繁体/常用简称） */
    public static String keywordsSystem() {
        return """
                你为动漫资源订阅生成「站点检索关键词」候选。RSS 站点用关键词做标题子串匹配，字幕组发布标题常用\
                罗马字/官方英文名/繁体中文/原名缩写。只输出一个 JSON 对象：{"keywords":["词1","词2",…]}，\
                3~6 个候选（最可能命中资源发布的在前，短而精，不要整句），不要任何解释。\
                不要包含书名号或引号。
                """;
    }

    public static String keywordsUser(String subjectNameCn, String subjectName) {
        return "条目名（中文）：" + safe(subjectNameCn) + "\n条目名（原文）：" + safe(subjectName) + "\n请输出关键词 JSON。";
    }

    /** AI3：从视频文件名解析标题与集数（NameParser 正则的语义兜底） */
    public static String fileNameSystem() {
        return """
                你从视频文件名解析动漫条目标题与集数。只输出一个 JSON 对象：
                {"title":"清洗后的条目标题（去发布组/分辨率/编码/集数标记，保留原语言）","episode":数字或null}
                - 注意 1080p/720p/H.264/x265/10bit/Hi10P/60fps/年份数字不是集数
                - 「第N话/EP N/[NN]/ - NN」等话数标记优先；范围包（01-12）取 null（无法对应单集）
                - 无法判断时 episode=null
                """;
    }

    public static String fileNameUser(String fileName) {
        return "文件名：" + safe(fileName) + "\n请输出解析 JSON。";
    }

    private static String safe(String s) {
        return s == null ? "（空）" : s;
    }
}
