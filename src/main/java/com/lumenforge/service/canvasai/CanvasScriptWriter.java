package com.lumenforge.service.canvasai;

import com.lumenforge.service.story.CodexCli;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本引擎 · 本地 GPT 写剧本 —— 逐函数复刻 Python 版 {@code app/script_writer.py}。
 *
 * <h2>分工（照抄原版注释）</h2>
 * <p>剧本（这一层）—— 本地 GPT（Codex CLI）把一句话题材写成一份能拍的剧本；
 * 分镜（下一层）—— 模型把剧本拆成 JSON 分镜与提示词。
 *
 * <p><b>为什么把「写剧本」和「拆分镜」分成两层</b>：实测「拆分镜」强的模型
 * （守 JSON、地点不漏、不自己编故事）往往<b>写故事不行</b> —— 给一句题材它会写得很平；
 * 反过来会写故事的模型又容易不守 JSON 格式。各干各擅长的那一半，中间用「人话剧本」交接，
 * 比让一个模型一次干完更稳。
 *
 * <p>交接物就是一份普通剧本（剧名 / 人物 / 分场 / 台词），不是 JSON —— 因为下游的
 * {@link CanvasDirectorService#directStoryboard} 本来就能吃「一段剧本」，并且它认得出这是剧本
 * （{@link CanvasDirectorService#isScriptLike} 按「几件事」判），会走「忠实改编」那条路，不会自由发挥。
 *
 * <p><b>取舍</b>：Python 版走 {@code codex_cli.ask_codex}（子进程 + 事件流）。Java 版复用
 * 已迁移好的 {@link CodexCli#ask(String, int)}，那里面已经处理了 exe 探测、{@code --json} 事件流、
 * 独立工作目录与「没装 Codex」的中文提示。这里只管「提示词 + 剪围栏 + 太短就报错」。
 */
@Component
public class CanvasScriptWriter {

    /** 本地 GPT 写剧本的默认上限（与原版 {@code writeScriptWithGpt} 的 180000 一致）。 */
    public static final int DEFAULT_TIMEOUT_MS = 180000;

    /** 剧本短于这个字数就没法拆（拆出来大概只有一两镜）。 */
    private static final int MIN_SCRIPT_LENGTH = 40;

    private final CodexCli codex;

    public CanvasScriptWriter(CodexCli codex) {
        this.codex = codex;
    }

    /** 让 GPT 把一句话题材写成一份剧本（纯文字，不是 JSON）。 */
    public static String buildScriptPrompt(String idea, int shotCount, String style) {
        List<String> lines = new ArrayList<String>();
        lines.add("你是一个短剧编剧。请把用户给的题材写成一集可以直接拍的短剧剧本。");
        lines.add("");
        lines.add("输出格式（只要正文，不要解释、不要 markdown 代码块、不要任何前后缀）：");
        lines.add("剧名：xxx");
        lines.add("人物：");
        lines.add("· 角色A：性别，年龄段，发型发色，五官特征，服装，气质（40~70 字）");
        lines.add("· 角色B：同上");
        lines.add("1. 地点：画面里发生什么 —— 谁在做什么、什么动作、什么表情、周围有什么（60~100 字）");
        lines.add("   台词：这个镜头里说的话（没有就写「无」）");
        lines.add("2. 地点：……");
        lines.add("   ……");
        lines.add("");
        lines.add("一共写 " + shotCount + " 个镜头，按剧情顺序。");
        lines.add("");
        lines.add("要求：");
        lines.add("- 剧情要有起承转合，镜头之间连贯，前一个镜头的结尾要能接上后一个镜头的开头。");
        lines.add("- 每个镜头只写「一个能定格成一张图的瞬间」，不要在一个镜头里写一长串连续动作。");
        lines.add("- 每个镜头都要写清楚「在哪」，而且同一场戏的连续几个镜头用同一个地点 ——");
        lines.add("  后面出图要靠这个地点保持背景一致，地点一跳，画面就跳。");
        lines.add("- 人物描述要具体、可画：「单眼皮」「鼻梁左侧有一颗小痣」「灰色连帽卫衣」，");
        lines.add("  不要写「很漂亮」「有气质」这种画不出来的话。");
        lines.add("- 不要写真实人名、品牌名、地名。不要出现暴力、色情、政治内容。");
        lines.add("- 台词短，一句 15 字以内，像人说的话。");
        lines.add("");
        lines.add("全片统一风格：" + style + "。（风格只在这里说一次，不要在正文里反复描述画风。）");
        lines.add("");
        lines.add("用户给的题材是：「" + (idea == null ? "" : idea) + "」");

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
        }
        return out.toString();
    }

    /**
     * 用本地 GPT 写一版剧本。返回 {@code {script, engine}}。
     *
     * <p>失败就抛错，由上层决定是「退回直接拆题材」还是把错误抛给用户
     * （{@link CanvasDirectorService#directStoryboard} 选的是前者，并把原因写进 {@code script.note}）。
     */
    public Map<String, Object> writeScriptWithGpt(String idea, int shotCount, String style, int timeoutMs) {
        String content = idea == null ? "" : idea.trim();
        if (content.length() < 4) {
            throw new IllegalArgumentException("先写一句题材（至少 4 个字）");
        }

        String answer = codex.ask(buildScriptPrompt(content, shotCount, style),
                timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);

        String script = stripFence(answer);
        if (script.length() < MIN_SCRIPT_LENGTH) {
            throw new IllegalArgumentException("本地 GPT 写的剧本太短，换个说法再试");
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("script", script);
        out.put("engine", "codex");
        return out;
    }

    /** 模型偶尔会自己套一层 markdown 围栏，剪掉。 */
    private static String stripFence(String answer) {
        String script = answer == null ? "" : answer;
        String left = script.replaceFirst("^\\s+", "");
        if (left.startsWith("```")) {
            script = left;
            int nl = script.indexOf('\n');
            script = nl >= 0 ? script.substring(nl + 1) : script.substring(3);
        }
        String right = script.replaceFirst("\\s+$", "");
        if (right.endsWith("```")) {
            script = right.substring(0, right.length() - 3);
        }
        return script.trim();
    }
}
