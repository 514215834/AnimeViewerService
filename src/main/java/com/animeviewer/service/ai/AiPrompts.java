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

    /** v0.30 A7：判定「解析出的标题」与「Bangumi 候选条目」是否同一作品（同一 IP 不同季算不同作品）。
     *  锚点收紧（§5R 验收实测：弱信号乱名仅凭子串词重叠会被过自信误判——子串重叠不算确信） */
    public static String matchScoreSystem() {
        return """
                你是动漫作品匹配判定器。判断「解析出的标题」与「候选条目」是否为同一部作品（同一 IP 的不同季\
                算不同作品，季号对不上要扣分）。只输出一个 JSON 对象，不要任何多余文字：
                {"score":0到100的整数,"reason":"不超过40字"}
                评分口径：
                - 95~100=完全同一作品（仅繁简/全半角/标点差异）
                - 85~94=同一作品但写法不同（罗马字↔中文译名/别名/常见简称——确信是同一部）
                - 50~84=疑似但不确定（部分单词子串重叠、季号存疑、缺少关键信息）
                - 0~49=不同作品、无法判断、或解析出的标题根本不是真实作品名（乱名/无意义词组）
                硬规则：
                - 仅凭个别单词重叠（如 HOME、LOVE 这类）不能构成 85+；除非整个标题就是该作品名
                - 标题信息量不足以确定是哪部作品时 ≤79
                """;
    }

    public static String matchScoreUser(String parsedTitle, String subjectNameCn, String subjectName, String year) {
        return "解析出的标题：" + safe(parsedTitle)
                + "\n候选条目（中文）：" + safe(subjectNameCn)
                + "\n候选条目（原文）：" + safe(subjectName)
                + "\n候选年份：" + safe(year)
                + "\n请输出判定 JSON。";
    }

    /** v0.30 A6：从用户粘贴的页面 URL/文本解析 RSS 订阅地址（未知站点语义兜底；规则引擎先行，此处仅兜底） */
    public static String rssResolveSystem() {
        return """
                你从用户粘贴的文本中解析出可直接订阅的 RSS/Atom 地址（喂给 RSS 阅读器的 feed URL，不是网页 URL）。
                只输出一个 JSON 对象，不要任何多余文字：
                {"rssUrl":"https://…（解析出的 feed 地址）"} 或 {"rssUrl":null,"reason":"不超过40字（为什么解析不出）"}
                规则：
                - 输入里已有 RSS/feed 形态地址（路径含 rss/feed 或以 .rss/.xml 结尾且非网页）→ 原样提取
                - 资源站的「搜索页/条目页」URL 可按常见形态推导 feed（如 keyword 搜索页 → rss 订阅搜索结果）
                - 推不出就 rssUrl=null 并给 reason；不要编造不存在的地址，不要编造站点域名
                """;
    }

    public static String rssResolveUser(String text, String sitesContext) {
        return "用户粘贴的文本：\n" + safe(text)
                + "\n已启用资源站（baseUrl + 搜索模板，{kw} 为关键词占位符）：\n" + sitesContext
                + "\n请输出解析 JSON。";
    }

    /** v0.30 补记一：从站点地址推导「key + 名称 + baseUrl + 关键词搜索模板」（规则映射先行，此处仅兜底未知站点） */
    public static String siteFillSystem() {
        return """
                你是 RSS 资源站接入配置生成器。根据用户粘贴的站点地址/文本，推导该站的「关键词搜索 RSS 模板」\
                ——在 baseUrl 后拼接模板即得到按关键词搜索结果的 RSS feed 地址（XML，不是网页）。\
                只输出一个 JSON 对象，不要任何多余文字：
                {"key":"1~24位小写字母数字连字符下划线","name":"站点显示名","baseUrl":"https://根地址","searchTemplate":"含 {kw} 占位符的搜索模板（相对 baseUrl，不带前导斜杠）"}
                或解析不出：{"key":null,"reason":"不超过40字"}
                规则：
                - searchTemplate 形如 rss.xml?keyword={kw}、topics/rss/rss.xml?keyword={kw}、?page=rss&q={kw}&c=0_0&f=0
                - 知道该站搜索 RSS 形态就给准确值；不知道就拒绝（key=null 并给 reason），不要编造地址与模板
                参考示例：
                - acgnx：baseUrl https://share.acgnx.se 模板 rss.xml?keyword={kw}
                - dmhy：baseUrl https://share.dmhy.org 模板 topics/rss/rss.xml?keyword={kw}
                - nyaa：baseUrl https://nyaa.si 模板 ?page=rss&q={kw}&c=0_0&f=0
                """;
    }

    public static String siteFillUser(String text) {
        return "用户粘贴的文本：\n" + safe(text) + "\n请输出配置 JSON。";
    }

    private static String safe(String s) {
        return s == null ? "（空）" : s;
    }
}
