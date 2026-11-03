package com.lumenforge.service.canvasai;

import com.lumenforge.common.util.Texts;
import com.lumenforge.service.story.ScriptChatClient;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节点画布 · AI 聊天（对话式编导）—— 逐函数复刻 Python 版 {@code app/canvas_chat.py}。
 *
 * <h2>跟 {@link CanvasDirectorService} 的分工</h2>
 * <ul>
 *   <li>{@code CanvasDirectorService} 是「一段文字 → 一份分镜」，纯函数，不谈上下文；</li>
 *   <li>这里是「聊天」，负责判断用户这一句话想干嘛，再决定要不要叫上面那个。</li>
 * </ul>
 *
 * <p><b>为什么拆两步</b>：分镜 prompt 里塞了大量字段规范（scene 要 50~90 字、motion 怎么写、
 * characters 不能漏），这些跟「聊天」混在一条 prompt 里，小模型两件事都做不好 ——
 * 要么 reply 变成说明文，要么镜头字段缺一半。所以先让模型只做「判意图 + 回一句话」，
 * 真要铺分镜再走原来那条已经调好的链路。代价是多一次调用，换来的是分镜质量不打折。
 *
 * <p>状态全在画布上：这里不落库、不建任务，只返回「回什么话 + 要做什么」。
 */
@Service
public class CanvasChatService {

    /** 只带最近几轮：小模型的上下文窗口小，塞多了反而忘掉当前画布状态。 */
    public static final int MAX_TURNS = 8;

    /**
     * 整个 {@code POST /api/canvas/chat} 请求的总预算。
     *
     * <p>前端 {@code public/nodecanvas.js} 的 CHAT_TIMEOUT_MS 是 300 秒，到点就 abort，
     * 用户看到的是「signal timed out」。服务端必须在这之前把话说出来 —— 所以这里给 280 秒，
     * 并把它切成几段分给「分类调用」和「拆分镜」：一次聊天里最多会有 1 次分类调用（~90 秒）
     * + 1 条完整编导链路（~225 秒），串起来早就爆了。分类调用先跑，剩下的全给编导。
     */
    public static final long CHAT_BUDGET_MS = 280000L;

    /**
     * 分类调用最多用掉这么多，剩下的必须留给编导（不然分镜还没开始就被掐）。
     *
     * <p>分类的 prompt 很短（只回一个小 JSON），但实测 glm-4.5-flash 带 thinking 时也要 30~90 秒；
     * 给 90 秒，偶发 5xx 还有 {@link ScriptChatClient#postChat} 的三次重试兜着。
     * 留 180 秒给编导，才够拆 6 镜。另外：用户直接贴剧本时不会走这条
     * （{@link CanvasDirectorService#isScriptLike} 直接进编导）。
     */
    public static final long CLASSIFY_MAX_MS = 90000L;

    public static final Set<String> INTENTS = buildIntents();

    /**
     * 「开始生成」这句话不需要模型来判断 —— 说跑就跑。
     *
     * <p>之前完全交给模型，glm-4v-flash 会看着历史里那条「第 2 镜改成雨夜」接着往下改，
     * 把「开始生成」当成又一轮修改。这类指令明确、结果唯一，本地判掉更快也更稳。
     *
     * <p>正则故意收得很紧（整句就是这几个词，或者后面只跟语气词 / 数量），
     * 否则「生成的图不对」这种句子也会被当成「开始生成」。
     */
    private static final Pattern RUN_RE = Pattern.compile(
            "^(一键生成|开始生成|生成|开始|开跑|跑起来|出图|出视频|开工|执行)"
            + "(吧|啊|一下|呗|呢|全部|所有|全部分镜|所有镜头|分镜|镜头|图片|视频|[！!。.~～ ])*$");

    /** edits 翻译成人话时用的字段中文名。 */
    private static final Map<String, String> FIELD_LABEL = buildFieldLabels();

    /** 模型常把字段名写进内容（「画面：…」「scene: …」），拼到提示词里会变成噪音。 */
    private static final Pattern FIELD_NAME_RE = Pattern.compile(
            "^(画面|场景|内容|scene|prompt)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);

    /** 「…中景｜运镜：镜头固定｜时长：10 秒｜出场：阿岚｜还没出图」这种元信息尾巴。 */
    private static final Pattern TRAILER_RE = Pattern.compile(
            "[，,；;]\\s*(运镜|时长|出场|台词|角色|镜头运动)\\s*[:：][\\s\\S]*$");

    /** 「第 3 镜」「镜头 3」「3 号镜」都认。 */
    private static final Pattern MENTIONED_SHOTS_RE = Pattern.compile(
            "第\\s*(\\d{1,2})\\s*镜|镜头\\s*(\\d{1,2})|(\\d{1,2})\\s*号镜");

    private static final Pattern PLAN_DIRECTIVE_START = Pattern.compile("^(把|将|就|照|按|拿|用)");

    private static final Pattern PLAN_DIRECTIVE_VERB = Pattern.compile("拆|分|做|拍|铺");

    private static final Pattern NEGATIVE_EDIT = Pattern.compile("改|换|去掉|重写|删");

    private static final Pattern ECHO_LEADING = Pattern.compile("^[，,。.、；;：:\\s]+");

    private static final Pattern GUESS_RUN_START = Pattern.compile("^(生成|开始|开跑|跑起来|出图|出视频|一键)");

    private static final Pattern GUESS_RUN_SEARCH = Pattern.compile("开始(生成|出图|跑)");

    private static final Pattern GUESS_REVISE = Pattern.compile("改成|换个|换一|去掉|加上|快一点|慢一点|调一下|重写");

    private final CanvasDirectorService director;
    private final ScriptChatClient chat;

    public CanvasChatService(CanvasDirectorService director, ScriptChatClient chat) {
        this.director = director;
        this.chat = chat;
    }

    // ============================================================== 本地判定

    /**
     * 用户点了名的那几镜。
     *
     * <p>导出为 public 是因为它同时被 {@link #chatWithCanvas} 和
     * {@link #isPlanDirective} 用，冒烟测试也要直接验它。
     */
    public static Set<Integer> mentionedShots(Object said) {
        Set<Integer> found = new LinkedHashSet<Integer>();
        Matcher matcher = MENTIONED_SHOTS_RE.matcher(said == null ? "" : String.valueOf(said));
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1)
                    : (matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            if (value == null || value.isEmpty()) {
                continue;
            }
            int num;
            try {
                num = Integer.parseInt(value);
            } catch (NumberFormatException err) {
                continue;
            }
            if (num >= 1) {
                found.add(num);
            }
        }
        return found;
    }

    /**
     * 「把它拆成分镜」这种指代句：句子里没有剧本，原文在前一轮。
     *
     * <p>这种话不用问模型 —— 实测 glm-4v-flash 会把 prompt 里的占位符「要拆分镜的原文」原样抄进
     * script，还会把 JSON 输出成 markdown 代码块导致解析失败。本地判掉更快也更准。
     * 点名了「第 N 镜」的一律不算（那是改分镜，不是重新铺）。
     */
    public static boolean isPlanDirective(Object said) {
        String body = said == null ? "" : String.valueOf(said).trim();
        if (body.isEmpty() || body.length() > 20 || !mentionedShots(body).isEmpty()) {
            return false;
        }
        if (body.contains("分镜")) {
            return !NEGATIVE_EDIT.matcher(body).find();
        }
        return PLAN_DIRECTIVE_START.matcher(body).find()
                && PLAN_DIRECTIVE_VERB.matcher(body).find()
                && !NEGATIVE_EDIT.matcher(body).find();
    }

    /** 前几轮里最长的那段用户消息（= 大概率就是剧本原文）。 */
    public static String findEarlierScript(List<Map<String, Object>> history, Map<String, Object> lastUser) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> message = history.get(i);
            if (!"user".equals(Texts.trim(message.get("role")))) {
                continue;
            }
            if (lastUser != null && message == lastUser) {
                continue;
            }
            String content = Texts.trim(message.get("content"));
            if (content.length() >= 20) {
                return content;
            }
        }
        return "";
    }

    /**
     * 「要拆分镜的原文」绝不能直接信模型给的。
     *
     * <p>实测 glm-4v-flash 会自由发挥：用户说「女神起床伸懒腰、闺蜜做饭、搂腰脸红」，
     * 它在 script 字段里编了一个「雨夜奇遇」的故事，拿去一拆，画布上铺出来的就完全是另一个世界。
     *
     * <p>所以 script 只认「和哪条用户消息对得上」的原文：
     * <ul>
     *   <li>模型抄的是历史里某条用户消息（≥50% 字对重合）→ 用那条<b>用户的原话</b>，不用模型的改写；</li>
     *   <li>对不上（= 编的）→ 用用户最后说的一句话。</li>
     * </ul>
     * 任何情况下都不把模型写的文字当剧本。
     */
    public static String pickScript(Object modelScript,
                                    List<Map<String, Object>> history,
                                    Map<String, Object> lastUser) {
        String said = lastUser == null ? "" : Texts.trim(lastUser.get("content"));
        String got = Texts.trim(modelScript);

        // 用户这句只是「把它拆成分镜」这种指代 —— 真正的原文在前面几轮里，直接捞那段
        if (isPlanDirective(said)) {
            String older = findEarlierScript(history, lastUser);
            if (!older.isEmpty()) {
                return older;
            }
        }
        if (got.isEmpty()) {
            return said;
        }

        String bestMessage = said;
        double bestScore = containment(got, said);
        // 从新到旧找：同样对得上时，优先离得近的那条
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> message = history.get(i);
            if (!"user".equals(Texts.trim(message.get("role")))) {
                continue;
            }
            if (lastUser != null && message == lastUser) {
                continue;
            }
            Object content = message.get("content");
            if (content == null || Texts.trim(content).isEmpty()) {
                continue;
            }
            double score = containment(got, content);
            if (score > bestScore) {
                bestMessage = String.valueOf(content);
                bestScore = score;
            }
        }
        // 对得上 → 用那条用户原话；对不上（模型自己编的）→ 用用户最后这句
        return bestScore >= 0.5D ? bestMessage : said;
    }

    /**
     * 中文按 2 字滑窗比「包含度」：needle 的字对里有多少出现在 hay 里（0~1）。
     *
     * <p>判「模型抄的是哪条用户消息」靠它 —— 直接比字符串相等没用，模型总会顺手改几个字。
     */
    public static double containment(Object needle, Object hay) {
        String a = CanvasAi.punctStrip(needle == null ? "" : String.valueOf(needle));
        String b = CanvasAi.punctStrip(hay == null ? "" : String.valueOf(hay));
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0D;
        }
        Set<String> grams = new LinkedHashSet<String>();
        for (int i = 0; i + 1 < a.length(); i++) {
            grams.add(a.substring(i, i + 2));
        }
        if (grams.isEmpty()) {
            return 0.0D;
        }
        int hit = 0;
        for (String gram : grams) {
            if (b.contains(gram)) {
                hit++;
            }
        }
        return hit / (double) grams.size();
    }

    // ============================================================== 文本清洗

    /**
     * 模型爱把用户原话当成回复的开头：「第 2 镜改成雨夜，铺好了，3 个镜头。」
     * 聊天气泡里用户刚说过的话再重复一遍很蠢，直接剥掉。
     */
    public static String trimEcho(Object reply, Object said) {
        String saidText = said == null ? "" : String.valueOf(said).trim();
        String replyText = reply == null ? "" : String.valueOf(reply).trim();
        if (saidText.length() < 4 || !replyText.startsWith(saidText)) {
            return replyText;
        }
        String rest = ECHO_LEADING.matcher(replyText.substring(saidText.length())).replaceFirst("");
        return rest.isEmpty() ? "好。" : rest;
    }

    /** 模型常把「画面：」这种字段名前缀一起抄进内容里。 */
    public static String stripFieldName(Object value) {
        return FIELD_NAME_RE.matcher(value == null ? "" : String.valueOf(value)).replaceFirst("");
    }

    /**
     * 更糟的一种：模型直接抄 {@code describe_scene} 里那一行摘要，
     * 「…中景｜运镜：镜头固定｜时长：10 秒｜出场：阿岚｜还没出图」整串塞进画面描述。
     * 没有竖线的时候它会改写成「…近景，运镜：镜头固定，时长：10 秒」——
     * 竖线之后、或者带冒号的元信息字段之后，都不是画面本身，截断掉。
     */
    public static String stripTrailer(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        int bar = text.indexOf('｜');
        String line = bar >= 0 ? text.substring(0, bar) : text;
        return TRAILER_RE.matcher(line).replaceAll("").trim();
    }

    // ============================================================== 画布现状

    /** 把画布现状说给模型听：它只能看见这段文字，看不见节点。 */
    public static String describeScene(Object scene) {
        if (!(scene instanceof Map)) {
            return "画布上还没有分镜。";
        }
        Map<?, ?> sceneMap = (Map<?, ?>) scene;
        if (!(sceneMap.get("shots") instanceof List)) {
            return "画布上还没有分镜。";
        }
        List<?> shots = (List<?>) sceneMap.get("shots");
        if (shots.isEmpty()) {
            return "画布上还没有分镜。";
        }

        List<String> lines = new ArrayList<String>();
        String planTitle = Texts.trim(sceneMap.get("planTitle"));
        lines.add("画布上已经有一份分镜" + (planTitle.isEmpty() ? "" : "《" + planTitle + "》")
                + "，共 " + shots.size() + " 个镜头。");
        // 成片结构要讲清楚：模型看不见节点，不说明白它会以为「一个镜头配一段视频」
        lines.add("出片结构：图 → 视频 → 图 → 视频 … 串起来的，所以 " + shots.size() + " 个镜头有 "
                + Math.max(0, shots.size() - 1) + " 段视频；"
                + "第 N 段视频从「第 N 镜的图」走到「第 N+1 镜的图」（前一张是首帧、后一张是尾帧），"
                + "第一镜前面没有镜头，不单独出视频。");
        if (!Texts.trim(sceneMap.get("ratio")).isEmpty()) {
            lines.add("画幅：" + Texts.trim(sceneMap.get("ratio")) + "。");
        }
        if (!Texts.trim(sceneMap.get("style")).isEmpty()) {
            lines.add("画风：" + Texts.trim(sceneMap.get("style")) + "。");
        }

        List<String> castLines = new ArrayList<String>();
        Object casts = sceneMap.get("characters");
        if (casts instanceof List) {
            for (Object item : (List<?>) casts) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> cast = (Map<?, ?>) item;
                String desc = Texts.trim(cast.get("desc"));
                castLines.add(Texts.trim(cast.get("name")) + (desc.isEmpty() ? "" : "（" + desc + "）"));
            }
        }
        if (!castLines.isEmpty()) {
            lines.add("角色：" + join(castLines, "；") + "。");
        }
        lines.add("");

        for (Object item : shots) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> shot = (Map<?, ?>) item;
            List<String> parts = new ArrayList<String>();
            parts.add("第 " + Texts.trim(shot.get("index")) + " 镜");
            if (!Texts.trim(shot.get("scene")).isEmpty()) {
                parts.add("画面：" + Texts.trim(shot.get("scene")));
            }
            if (!Texts.trim(shot.get("endState")).isEmpty()) {
                parts.add("收尾：" + Texts.trim(shot.get("endState")));
            }
            if (!Texts.trim(shot.get("motion")).isEmpty()) {
                parts.add("运镜：" + Texts.trim(shot.get("motion")));
            }
            if (!Texts.trim(shot.get("dialogue")).isEmpty()) {
                parts.add("台词：" + Texts.trim(shot.get("dialogue")));
            }
            if (!Texts.trim(shot.get("duration")).isEmpty()) {
                parts.add("时长：" + Texts.trim(shot.get("duration")) + " 秒");
            }
            Object inShot = shot.get("characters");
            if (inShot instanceof List && !((List<?>) inShot).isEmpty()) {
                parts.add("出场：" + inShot);
            }
            parts.add(isTruthy(shot.get("hasImage")) ? "已有图" : "还没出图");
            lines.add("- " + join(parts, "｜"));
        }
        return join(lines, "\n");
    }

    /** 判意图那一条 prompt。 */
    public static String buildPrompt(List<Map<String, Object>> messages, Object scene, Object prefs) {
        int want = clampShotCount(shotCountRaw(prefs));
        List<String> lines = new ArrayList<String>();
        lines.add("你是一个嵌在视频创作画布右侧的 AI 编导。用户直接跟你说话，你判断他这一句想干什么，然后回一句话。");
        lines.add("");
        lines.add("可能的意思只有这四种，必须选一个：");
        lines.add("- plan：用户给了剧本、故事梗概、或者一句题材，要你拆成分镜铺到画布上。");
        lines.add("- revise：画布上已经有分镜，用户要改其中某些镜头（改画面、改运镜、改台词、改长短）。");
        lines.add("- run：用户让你开始出图 / 出视频。");
        lines.add("- chat：其它提问、闲聊、或者只是问你能干什么。");
        lines.add("");
        lines.add("当前画布：");
        lines.add(describeScene(scene));
        lines.add("");
        lines.add("只输出 JSON，不要解释，不要 markdown 代码块：");
        lines.add("{\"reply\":\"回给用户的话\",\"intent\":\"plan\","
                + "\"script\":\"从用户消息里原样摘的剧本（没有就填空串）\",\"shotCount\":6,\"edits\":[]}");
        lines.add("");
        lines.add("字段要求：");
        lines.add("- reply：中文，**最多 3 句、60 字以内**，像同事说话，直接给结论。");
        lines.add("  · 不要复述用户的话，不要写「好的，我将…」这种客套，"
                + "**不要罗列分镜内容**（分镜已经铺到画布上了，用户自己看得见）。");
        lines.add("  · 只说一句「铺好了，X 个镜头」这种结论，需要提醒的补半句。");
        lines.add("- intent：只能是 plan / revise / run / chat 之一。");
        lines.add("- script：intent=plan 时必填，把用户给的那段内容**原文照抄**下来"
                + "（不要改写、不要摘要），最长 8000 字。其它意图留空串。");
        lines.add("- shotCount：intent=plan 时填镜头数，只能填 2~12 的整数。");
        lines.add("  · 用户明说了「拆成 4 个镜头」就填 4；**没说就填 " + want
                + "（右边参数面板当前的值，不是示例里的数字）**，不要照抄示例。");
        lines.add("- edits：intent=revise 时必填，数组，每项一个要改的镜头：");
        lines.add("  {\"shotIndex\":3,\"scene\":\"改后的画面描述\",\"motion\":\"改后的运镜\","
                + "\"endState\":\"改后的收尾画面\",\"dialogue\":\"改后的台词\",\"duration\":5}");
        lines.add("  · shotIndex 是第几镜（从 1 开始，看上面画布里的编号）。");
        lines.add("  · **只写真正要改的字段，没提到的一律不要出现**（不要填 null，也不要填原值）。");
        lines.add("  · 不要把没被点名的镜头写进 edits。用户说「第 3 镜」就只改第 3 镜。");
        lines.add("  · 画面描述写具体（在哪、谁、做什么、光线、景别），50~90 字；"
                + "运镜写「什么在动、怎么动」，15~40 字。");
        lines.add("  · duration 只能是 5 或 10。");
        lines.add("");
        lines.add("判断规则：");
        lines.add("- **只看最后一条用户消息**。前面几轮里出现过的修改早就执行完了，不要再重复一遍。");
        lines.add("- 用户贴了一段剧情 / 故事 / 场景描述（哪怕只有一句话），或者明说「拆成分镜」「按这个拍」→ plan。");
        lines.add("- 画布已经有分镜，用户说的是「改成…」「换个…」「快一点」「加一镜」→ revise。");
        lines.add("- 用户说「生成」「开始」「跑起来」「出图」→ run。");
        lines.add("- 拿不准就选 chat，并问一句他想干什么。");
        lines.add("");
        lines.add("用户说的话（由旧到新）：");
        for (Map<String, Object> message : messages) {
            String speaker = "user".equals(Texts.trim(message.get("role"))) ? "用户" : "你";
            lines.add(speaker + "：" + CanvasAi.plainText(message.get("content"), 4000));
        }
        return join(lines, "\n");
    }

    // ============================================================== 意图兜底

    /** 模型没给 intent（或给了个没见过的值）时，按关键词猜一下，别让整条链路瘫掉。 */
    public static String guessIntent(Object answer, Object lastUser) {
        String said = lastUser == null ? "" : String.valueOf(lastUser).trim();
        String both = String.valueOf(answer == null ? "" : answer) + "\n" + said;
        // 「生成的图不对」也以「生成」开头，所以只在短句上认这条
        if (said.length() <= 12
                && (GUESS_RUN_START.matcher(said).find() || GUESS_RUN_SEARCH.matcher(both).find())) {
            return "run";
        }
        if (GUESS_REVISE.matcher(said).find()) {
            return "revise";
        }
        if (said.length() >= 30) {
            return "plan";
        }
        return "chat";
    }

    /**
     * edits 翻译成人话：「改好了：第 2 镜的画面、运镜；第 3 镜的台词。」—— 比让模型复述可靠。
     */
    public static String describeEdits(List<Map<String, Object>> edits) {
        List<String> parts = new ArrayList<String>();
        boolean sceneChanged = false;
        for (Map<String, Object> edit : edits) {
            List<String> fields = new ArrayList<String>();
            for (String key : edit.keySet()) {
                if ("shotIndex".equals(key)) {
                    continue;
                }
                fields.add(FIELD_LABEL.containsKey(key) ? FIELD_LABEL.get(key) : key);
                if ("scene".equals(key)) {
                    sceneChanged = true;
                }
            }
            parts.add("第 " + Texts.trim(edit.get("shotIndex")) + " 镜的" + join(fields, "、"));
        }
        // 只有画面真改过，旧图才作废；只改台词、时长的时候说这句会让人以为图没了
        String tail = sceneChanged ? "画面改过的镜头会清掉旧图，点「一键生成」重出。" : "";
        return "改好了：" + join(parts, "；") + "。" + tail;
    }

    /** 把模型给的 edits 收干净：只要认得出的字段，shotIndex 必须是正整数。 */
    public static List<Map<String, Object>> sanitizeEdits(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> source = (Map<?, ?>) item;
            Object rawIndex = source.get("shotIndex");
            double indexValue = CanvasAi.number(rawIndex);
            if (rawIndex == null || Texts.trim(rawIndex).isEmpty()) {
                continue;
            }
            long shotIndex = CanvasAi.pyRound(indexValue);
            if (shotIndex < 1) {
                continue;
            }
            Map<String, Object> edit = new LinkedHashMap<String, Object>();
            edit.put("shotIndex", (int) shotIndex);
            String scene = CanvasAi.plainText(stripTrailer(stripFieldName(source.get("scene"))), 500);
            String motion = CanvasAi.plainText(stripTrailer(stripFieldName(source.get("motion"))), 200);
            // 收尾画面：这一镜动作做完时画面停在哪（它同时是下一段视频的尾帧）
            String endState = CanvasAi.plainText(stripTrailer(stripFieldName(source.get("endState"))), 200);
            String dialogue = CanvasAi.plainText(source.get("dialogue"), 100);
            if (!scene.isEmpty()) {
                edit.put("scene", scene);
            }
            if (!motion.isEmpty()) {
                edit.put("motion", motion);
            }
            if (!endState.isEmpty()) {
                edit.put("endState", endState);
            }
            // 台词允许被清空（用户说「这镜不要台词」），所以只要给了字段就收下
            if (source.containsKey("dialogue") && source.get("dialogue") != null) {
                edit.put("dialogue", dialogue);
            }
            if (source.get("duration") != null) {
                double duration = CanvasAi.number(source.get("duration"));
                if (duration != 0.0D) {
                    edit.put("duration", duration >= 8.0D ? 10 : 5);
                }
            }
            if (edit.size() > 1) {
                out.add(edit);
            }
        }
        return out.size() > 24 ? new ArrayList<Map<String, Object>>(out.subList(0, 24)) : out;
    }

    /** 铺完分镜的那句话自己写：小模型只会复述，而且铺完用户要的是「几个镜头、角色是谁」。 */
    public static String planReply(Map<String, Object> plan, int shotCount) {
        List<?> characters = CanvasAi.asList(plan.get("characters"));
        List<?> shots = CanvasAi.asList(plan.get("shots"));

        List<String> names = new ArrayList<String>();
        for (Object item : characters) {
            if (item instanceof Map) {
                names.add(Texts.trim(((Map<?, ?>) item).get("name")));
            }
        }
        String cast = names.isEmpty()
                ? "" : "，" + join(names, "、") + " " + characters.size() + " 个角色";
        String short_ = shots.size() < shotCount
                ? "（你要 " + shotCount + " 个，模型只给了 " + shots.size() + " 个）" : "";

        // 和原文几乎对不上：宁可当场说出来，也别让人以为这就是自己要的剧本
        String drift = "";
        Object loyalty = plan.get("loyalty");
        if (loyalty instanceof Map && isTruthy(((Map<?, ?>) loyalty).get("weak"))) {
            drift = "\n⚠️ 不过这份分镜和你给的内容差得有点远（写成了《" + Texts.trim(plan.get("title"))
                    + "》）。把剧情写得更具体些、或换成纯文本模型（如 glm-4-flash）再拆一次更稳。";
        }

        // 挂上参考图的角色不用再出定妆照，说一声，免得用户以为少生成了几张；
        // 反过来，图挂上了但模型读不了图（纯文本模型），也要说 —— 长相以用户的图为准，
        // 只是 desc 是按文字写的，别让人以为模型看过图。
        int fromPhoto = 0;
        for (Object item : characters) {
            if (item instanceof Map && isTruthy(((Map<?, ?>) item).get("fromPhoto"))) {
                fromPhoto++;
            }
        }
        String photoError = "";
        Object photos = plan.get("photos");
        if (photos instanceof Map) {
            photoError = Texts.trim(((Map<?, ?>) photos).get("error"));
        }
        String photosInfo;
        if (fromPhoto > 0) {
            if (!photoError.isEmpty()) {
                photosInfo = "你给的 " + fromPhoto + " 张图已经直接挂成定妆照了（没让 AI 再生成）。"
                        + "不过这次模型读不了图：" + photoError + "。角色长相以你的图为准，文字描述是按剧情写的。";
            } else {
                photosInfo = "你给的 " + fromPhoto + " 张图已经直接挂成定妆照，不用再生成。";
            }
        } else if (!photoError.isEmpty()) {
            photosInfo = "\n⚠️ 人物参考图这次没用上（" + photoError + "），角色长相是按文字写的 —— "
                    + "想照图定妆就换个支持读图的模型。";
        } else {
            photosInfo = "";
        }

        // 剧本是谁写的要交代一句：用户只写了一句题材，画布上却出来一整份剧本，
        // 不说清楚会以为模型又自己编故事了（这正是以前最招人烦的行为）
        String written = "";
        Object script = plan.get("script");
        if (script instanceof Map) {
            String source = Texts.trim(((Map<?, ?>) script).get("source"));
            String note = Texts.trim(((Map<?, ?>) script).get("note"));
            if ("gpt".equals(source)) {
                written = "\n这份剧本是本地 GPT 按你的题材写的（你只给了题材，没给整份剧本）。";
            } else if (!note.isEmpty()) {
                written = "\n（" + note + "）";
            }
        }

        return "《" + Texts.trim(plan.get("title")) + "》拆好了：" + shots.size() + " 个镜头"
                + cast + "，铺在画布上了" + short_ + "。" + written + photosInfo + drift;
    }

    // ============================================================== 主入口

    /**
     * 聊一句。
     *
     * <p>返回 {@code {reply, intent, plan?, edits?}}：
     * {@code plan} = 已拆好的分镜结构（前端直接铺节点），
     * {@code edits} = 要改的镜头（前端自己往节点上套）。
     */
    public Map<String, Object> chatWithCanvas(Object messages, Object scene, Object prefs, Object photoIds) {
        final long deadline = System.currentTimeMillis() + CHAT_BUDGET_MS;

        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        if (messages instanceof List) {
            for (Object item : (List<?>) messages) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> message = (Map<?, ?>) item;
                String role = Texts.trim(message.get("role"));
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                if (Texts.trim(message.get("content")).isEmpty()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("role", role);
                entry.put("content", CanvasAi.plainText(message.get("content"), 4000));
                history.add(entry);
            }
        }
        // 只留最近 MAX_TURNS 轮
        if (history.size() > MAX_TURNS) {
            history = new ArrayList<Map<String, Object>>(history.subList(history.size() - MAX_TURNS, history.size()));
        }

        Map<String, Object> lastUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(Texts.trim(history.get(i).get("role")))) {
                lastUser = history.get(i);
                break;
            }
        }
        if (lastUser == null) {
            throw new IllegalArgumentException("说点什么吧");
        }
        String said = Texts.trim(lastUser.get("content"));

        List<?> shots = new ArrayList<Object>();
        if (scene instanceof Map && ((Map<?, ?>) scene).get("shots") instanceof List) {
            shots = (List<?>) ((Map<?, ?>) scene).get("shots");
        }

        // 明确说要跑：不占用模型，直接回。
        if (RUN_RE.matcher(said).find()) {
            return runReply(shots);
        }

        // 「把它拆成分镜」这种指代句：原文在前一轮，本地直接铺。
        // 交给模型只会多两次失败：它会把 prompt 里的占位符「要拆分镜的原文」抄进 script，
        // 还会把 JSON 包进 markdown 代码块导致解析失败（实测 C 场景整段返回了原文）。
        if (isPlanDirective(said)) {
            String script = findEarlierScript(history, lastUser);
            if (script.isEmpty()) {
                return replyOf("把要拆的剧本发我，或者再说清楚一点（我现在还不知道拆什么）。", "chat");
            }
            return planPath(script, shotCountOf(prefs), prefs, photoIds, deadline);
        }

        // 用户直接把一段剧本贴进来：不用先问模型「这是什么意图」。
        //
        // 以前这类输入要走一次分类调用（实测 30~90 秒，偶尔 5xx 还要重试两次），
        // 把 280 秒的总预算吃掉一大半，后面真正拆镜时预算不够就被掐成
        // 「模型一直没有响应（超过 60 秒）」。剧本这种输入本地就能认出来：
        // 长、有事、多个分句 —— 直接走编导，把整段预算都留给它。
        if (CanvasDirectorService.isScriptLike(said) && mentionedShots(said).isEmpty()) {
            return planPath(said, shotCountOf(prefs), prefs, photoIds, deadline);
        }

        // ── 分类：只判意图 + 回一句话 ────────────────────────────────────────
        String prompt = buildPrompt(history, scene, prefs);
        // 分类调用不许吃掉全部预算：后面可能还要拆一遍分镜。
        // 至少留 180 秒给编导链路，所以这里取 min(90 秒, 剩余-180 秒)。
        // 90 秒而不是 60：实测 glm-4.5-flash 带 thinking 时这条也要 30~90 秒，
        // 卡 60 秒会在网络正常的情况下直接误报「超过 60 秒」。
        long remaining = deadline - System.currentTimeMillis();
        int timeout = (int) Math.max(30000L, Math.min(CLASSIFY_MAX_MS, remaining - 180000L));

        // 豆包引擎依赖 Electron 客户端桥，Java 版没有复刻：只实现 api 这条路径
        if ("doubao".equals(director.directorEngine())) {
            throw new IllegalArgumentException("本地豆包引擎依赖桌面客户端，Java 版请切换到 API 引擎");
        }
        String answer = director.chatScript(prompt, 0.5D, timeout, null);
        Object parsed = chat.extractJson(answer);

        // 模型没守格式：直接把原文当回复，别把错误甩给用户 —— 聊天窗口里看到一句人话总比看到报错好
        if (parsed == null || !(parsed instanceof Map)) {
            String text = CanvasAi.plainText(answer, 2000);
            return replyOf(text.isEmpty() ? "我没太看懂，能再说一遍吗？" : text, "chat");
        }
        Map<?, ?> parsedMap = (Map<?, ?>) parsed;

        String intent = Texts.trim(parsedMap.get("intent")).toLowerCase();
        if (!INTENTS.contains(intent)) {
            intent = guessIntent(answer, said);
        }

        Set<Integer> named = mentionedShots(said);
        // 画布上已经有分镜，用户又点名了「第 N 镜」—— 这就是改分镜，不可能是重新铺一份。
        // 小模型看到「雨夜」这种画面词就往 plan 上靠，不拦的话会在旁边再铺一整套分镜。
        if (!shots.isEmpty() && !named.isEmpty() && said.length() < 400 && "plan".equals(intent)) {
            intent = "revise";
        }

        String reply = trimEcho(CanvasAi.plainText(parsedMap.get("reply"), 2000), said);
        if (reply.isEmpty()) {
            reply = "好。";
        }

        if ("plan".equals(intent)) {
            // 剧本用**用户的原话**，模型给的那份只当「它抄的是哪一条」的线索（见 pickScript）
            String script = pickScript(parsedMap.get("script"), history, lastUser);
            int shotCount = parsedShotCount(parsedMap.get("shotCount"), prefs);
            return planPath(script, shotCount, prefs, photoIds, deadline);
        }

        if ("revise".equals(intent)) {
            List<Map<String, Object>> edits = sanitizeEdits(parsedMap.get("edits"));
            // 小模型会把没点名的镜头一起改（历史里提过的也照改一遍）。
            // 用户点名了第几镜，就只认那几镜 —— 这一步比 prompt 里的叮嘱管用。
            if (!named.isEmpty()) {
                List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> edit : edits) {
                    if (named.contains((int) CanvasAi.pyRound(CanvasAi.number(edit.get("shotIndex"))))) {
                        kept.add(edit);
                    }
                }
                if (!kept.isEmpty()) {
                    edits = kept;
                }
            }
            // 说了要改却一条都没给：退回聊天语气，别让用户以为改好了
            if (edits.isEmpty()) {
                return replyOf("我听出来你想改，但没拿到具体改法 —— 说下第几镜、改成什么？", "chat");
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("reply", describeEdits(edits));
            out.put("intent", intent);
            out.put("edits", edits);
            return out;
        }

        return replyOf(reply, intent);
    }

    // ============================================================== 分支

    /** 「开跑」：不占用模型，直接看画布上还剩几镜没出图。 */
    private static Map<String, Object> runReply(List<?> shots) {
        if (shots.isEmpty()) {
            return replyOf("画布上还没有分镜，先把剧本发我，我拆好再开跑。", "chat");
        }
        int todo = 0;
        for (Object item : shots) {
            if (item instanceof Map && !isTruthy(((Map<?, ?>) item).get("hasImage"))) {
                todo++;
            }
        }
        if (todo == 0) {
            return replyOf("这些镜头都已经出好图了。想重画哪一镜，点开那个节点用面板里的重新生成。", "chat");
        }
        return replyOf("开跑，一共 " + todo + " 个还没出图的镜头。"
                + "按「定妆照 → 镜头图 → 镜头视频」一波一波来，出完一批再下一批。", "run");
    }

    /** 走一遍编导链路，回一句铺完了的话。 */
    private Map<String, Object> planPath(String script,
                                         int shotCount,
                                         Object prefs,
                                         Object photoIds,
                                         long deadline) {
        Map<?, ?> prefsMap = prefs instanceof Map ? (Map<?, ?>) prefs : null;
        Object style = prefsMap == null ? null : prefsMap.get("style");
        Object wantVideo = prefsMap == null ? null : prefsMap.get("wantVideo");
        Map<String, Object> plan = director.directStoryboard(
                script,
                shotCount,
                style,
                // Python 是 prefs.get("wantVideo", True) is not False —— 只认字面 false，
                // 没给 / 给 null / 给字符串都当作要视频
                !(wantVideo instanceof Boolean && !((Boolean) wantVideo)),
                photoIds,
                deadline - System.currentTimeMillis());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("reply", planReply(plan, shotCount));
        out.put("intent", "plan");
        out.put("plan", plan);
        return out;
    }

    private static Map<String, Object> replyOf(String reply, String intent) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("reply", reply);
        out.put("intent", intent);
        return out;
    }

    // ============================================================== 工具

    /** {@code max(2, min(12, round(float(raw or 6)))}。 */
    private static int shotCountOf(Object prefs) {
        return clampShotCount(shotCountRaw(prefs));
    }

    private static double shotCountRaw(Object prefs) {
        Object raw = prefs instanceof Map ? ((Map<?, ?>) prefs).get("shotCount") : null;
        return CanvasAi.numberOr(raw, 6.0D);
    }

    private static int clampShotCount(double raw) {
        return (int) Math.max(2.0D, Math.min(12.0D, CanvasAi.pyRound(raw)));
    }

    /** intent=plan 时优先信模型给的 shotCount；它没给或给了非数字就退回参数面板的值。 */
    private static int parsedShotCount(Object raw, Object prefs) {
        if (raw == null || Texts.trim(raw).isEmpty()) {
            return shotCountOf(prefs);
        }
        double value = CanvasAi.number(raw);
        if (value == 0.0D && !"0".equals(Texts.trim(raw)) && !(raw instanceof Number)) {
            // 不是数字（比如「六个」）：退回参数面板的值
            return shotCountOf(prefs);
        }
        return clampShotCount(value);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return !String.valueOf(value).trim().isEmpty();
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.toString();
    }

    private static Set<String> buildIntents() {
        Set<String> out = new LinkedHashSet<String>();
        out.add("plan");
        out.add("revise");
        out.add("run");
        out.add("chat");
        return out;
    }

    private static Map<String, String> buildFieldLabels() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("scene", "画面");
        out.put("motion", "运镜");
        out.put("dialogue", "台词");
        out.put("duration", "时长");
        return out;
    }
}
