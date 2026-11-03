package com.lumenforge.service.canvasai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Texts;
import com.lumenforge.service.story.ScriptChatClient;
import com.lumenforge.service.story.StoryModelStore;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 节点画布 · AI 编导：一段文字 → 一份分镜结构。
 *
 * <p>逐函数复刻 Python 版 {@code app/canvas_director.py}。<b>只出文字结构，不建任务、不落库</b> ——
 * 铺节点、点生成都是前端的事。返回结构与 Python 逐字段一致：
 * {@code title / setting / style / places / characters / shots}，外加
 * {@code script}（这版剧本谁写的）、{@code loyalty}（与原文重合度）、{@code photos}（参考图用没用上）。
 *
 * <p>为什么不用「小剧场」那套 {@code StoryboardService}：那是落在 {@code db.storyboards} 里的数据模型，
 * 和画布是两种东西 —— 硬接会让同一份分镜在两处各存一份，改一边另一边不知道。
 *
 * <p><b>未移植的部分</b>：Python 版还有一条「本地豆包客户端」引擎（{@code providers/doubao_bridge}），
 * 它依赖 Electron 客户端桥，Java 版没有对应实现。配置成 {@code engine = doubao} 时这里直接报 400
 * 并提示切回 API 引擎；{@code director_info()} 仍然如实报告引擎状态，界面才显示得对。
 */
@Service
public class CanvasDirectorService {

    // ============================================================== 常量

    /** 画风候选：给「文生图 + 图生视频」用得上的几种，前端也可以自己填。 */
    public static final List<Map<String, Object>> DIRECTOR_STYLES = buildStyles();

    public static final String DEFAULT_DIRECTOR_STYLE = "电影感写实风格，35mm 胶片质感，自然光，浅景深，细节丰富";

    public static final int MIN_SHOTS = 2;
    public static final int MAX_SHOTS = 12;
    public static final int DEFAULT_SHOTS = 6;

    /** 整条编导链路的总时限（含补镜、重拆所有轮次）。前端 300 秒就断，这里给 270 秒留重试余量。 */
    public static final long DIRECTOR_BUDGET_MS = 270000L;

    /** 补镜、跑题重拆只在剩余预算够跑一轮时才做。 */
    public static final long TOPUP_MIN_MS = 75000L;
    public static final long RETRY_MIN_MS = 90000L;

    /** 造型设定最多几个：再多定妆照也是一张一张出，等得久。 */
    public static final int MAX_CHARACTERS = 4;

    /** 地点最多几个：一场戏通常就一两个地方，再多就说明剧本被拆散了。 */
    public static final int MAX_SCENES = 6;

    /** 重合率低于这个值就算「跑偏」，重试一次。 */
    public static final double LOYALTY_MIN = 0.05D;

    /**
     * 常见地点词。免费小模型写 scene 经常只写「谁在做什么」、地点一句不写，
     * 出图时模型就自己编一个房间，于是同一场戏一镜在厨房、下一镜在客厅。
     */
    public static final List<String> PLACE_WORDS = buildPlaceWords();

    /** 模型没给 motion 时的兜底运镜：不同镜头给不同的一句，别让整片视频的运镜一模一样。 */
    public static final List<String> CAMERA_MOVES = buildCameraMoves();

    public static final List<String> SHOT_SIZES = buildShotSizes();

    public static final List<String> SHOT_ANGLES = buildShotAngles();

    /** 多模态模型的识别口径：{@code glm-4v-flash}、{@code qwen-vl} 这类，界面上要提醒换纯文本模型。 */
    private static final Pattern VISION_MODEL = Pattern.compile(
            "(^|[-_.])(v|vl|4v)([-_.]|$)|vision|4v-", Pattern.CASE_INSENSITIVE);

    private static final Pattern BEAT_SPLIT = Pattern.compile("[；;→\\n]+|。+");

    private static final Pattern LIGHT_LEADING = Pattern.compile("^[，,。；;、]+");

    private static final Pattern LIGHT_EMPTY = Pattern.compile("^(无|略|暂无|不明确|自然光)$");

    private static final Pattern FENCE_HEAD = Pattern.compile("^```(?:json)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern FENCE_TAIL = Pattern.compile("```$");

    private final StoryModelStore storyModel;
    private final ScriptChatClient chat;
    private final CanvasScriptWriter scriptWriter;
    private final CanvasPhotoRefs photoRefs;
    private final ObjectMapper json;

    public CanvasDirectorService(StoryModelStore storyModel,
                                 ScriptChatClient chat,
                                 CanvasScriptWriter scriptWriter,
                                 CanvasPhotoRefs photoRefs,
                                 ObjectMapper json) {
        this.storyModel = storyModel;
        this.chat = chat;
        this.scriptWriter = scriptWriter;
        this.photoRefs = photoRefs;
        this.json = json;
    }

    // ============================================================== 引擎 / 状态

    /**
     * 编导引擎：{@code api} = 配置好的 OpenAI 兼容模型；{@code doubao} = 本地豆包客户端。
     *
     * <p>存在 {@code story_model.engine} 里，跟剧本模型配置放一起，界面上就是一个开关。
     */
    public String directorEngine() {
        Map<String, Object> model = storyModel.snapshot();
        String raw = Texts.trim(model.get("engine")).toLowerCase();
        return "doubao".equals(raw) ? "doubao" : "api";
    }

    /** 给界面用的静态信息：画风、镜头数范围、剧本模型有没有配好。 */
    public Map<String, Object> directorInfo() {
        Map<String, Object> model = storyModel.snapshot();
        String name = Texts.trim(model.get("model"));
        String engine = directorEngine();
        // 写剧本的那一层（本地 GPT）也跟着报出去：界面要能显示「剧本由谁写」
        String scriptEngine = storyModel.scriptEngine();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("styles", DIRECTOR_STYLES);
        out.put("defaultStyle", DEFAULT_DIRECTOR_STYLE);
        out.put("minShots", MIN_SHOTS);
        out.put("maxShots", MAX_SHOTS);
        out.put("defaultShots", DEFAULT_SHOTS);
        out.put("engine", engine);
        // 用豆包当引擎时不需要接口地址和 Key，只要有窗口就行
        out.put("hasModel", "doubao".equals(engine)
                || (!Texts.trim(model.get("baseUrl")).isEmpty()
                    && !name.isEmpty()
                    && Texts.asBool(model.get("hasKey"), false)));
        out.put("modelName", "doubao".equals(engine) ? "本地豆包" : name);
        out.put("modelIsVision", !"doubao".equals(engine) && VISION_MODEL.matcher(name).find());
        out.put("scriptEngine", scriptEngine);
        out.put("scriptEngineName",
                "codex".equals(scriptEngine) ? "本地 GPT（Codex）" : "不写（直接拆你给的内容）");
        return out;
    }

    // ============================================================== 剧本判定

    /** 数「这件事」有几件：按分号 / 箭头 / 换行 / 句号切开，长度 ≥2 的算一件。 */
    public static int countBeats(Object value) {
        String body = value == null ? "" : String.valueOf(value);
        String[] parts = BEAT_SPLIT.split(body, -1);
        int count = 0;
        for (String part : parts) {
            if (part != null && part.trim().length() >= 2) {
                count++;
            }
        }
        return count;
    }

    /**
     * 这段文字是不是「一整个剧本」而不是一句主题。
     *
     * <p>不能只看字数：用户常写 70 字的镜头清单（「起床伸懒腰；拉开窗帘；下楼看到她在做饭…」），
     * 字数不够 120 却被当成一句话题材，模型就自由发挥编个不相干的故事。按「几件事」判才准。
     */
    public static boolean isScriptLike(Object value) {
        String body = value == null ? "" : String.valueOf(value).trim();
        return body.length() >= 120 || countBeats(body) >= 3;
    }

    // ============================================================== 地点识别

    /** 从一段画面文字里认出地点（取最先出现、最具体的那个词）。 */
    public static String detectPlace(Object textValue) {
        String body = textValue == null ? "" : String.valueOf(textValue);
        String best = "";
        int bestAt = body.length() + 1;
        for (String word : PLACE_WORDS) {
            int at = body.indexOf(word);
            if (at < 0) {
                continue;
            }
            // 位置靠前的优先；同一位置取更长的（「厨房门口」比「厨房」具体）
            if (at < bestAt || (at == bestAt && word.length() > best.length())) {
                best = word;
                bestAt = at;
            }
        }
        return best;
    }

    // ============================================================== 提示词

    /** 把「一段剧本 / 一句题材」包成编导提示词。{@code null} 行表示「这一行不需要」，空串是故意的空行。 */
    public static String buildPrompt(String script,
                                     int shotCount,
                                     String style,
                                     boolean wantVideo,
                                     int photoCount,
                                     boolean photosAttached) {
        boolean fullScript = isScriptLike(script);
        List<String> lines = new ArrayList<String>();
        lines.add("你是一个短视频导演。把用户给的内容拆成分镜，供「文生图 / 图生视频」模型直接使用。");
        lines.add("");
        if (photoCount > 0) {
            if (photosAttached) {
                lines.add("用户还附了 " + photoCount + " 张人物参考图（见输入末尾）。"
                        + "**角色正好 " + photoCount + " 个，一张图对应一个角色，按图的顺序排**；"
                        + "每个角色的 desc 要照着图里那个人写：发型发色、五官、肤色、穿着、体型气质都要对得上，"
                        + "后面全片就靠这段描述和这张图保持长相一致。");
            } else {
                // 纯文本模型接不了图：图仍然会挂成定妆照（长相以用户的图为准），desc 只能按文字写
                lines.add("用户给了 " + photoCount + " 张人物参考图，但**这次没发给模型**（当前模型不读图）。"
                        + "**角色正好 " + photoCount + " 个，按顺序对应那几张图**；"
                        + "每个角色的 desc 按用户给的文字写，写得具体、可画就行 —— 长相最后以用户的参考图为准，"
                        + "不要编图里的细节，也不要写「很漂亮」这种画不出来的话。");
            }
        } else {
            lines.add(null);
        }

        lines.add("");
        lines.add(fullScript
                ? "用户给的是一段剧本，请**忠实改编**：人物、台词、剧情顺序都要保留，不要另编一个故事。"
                : "用户给的是一句题材或简短描述，请围绕它编一个有起承转合的小故事。");
        lines.add("");
        lines.add("只输出 JSON，不要任何解释、不要 markdown 代码块。JSON 格式：");
        lines.add("{\"title\":\"剧名\",\"setting\":\"全片统一设定\",\"places\":[\"地点名\"],"
                + "\"characters\":[{\"name\":\"角色名\",\"desc\":\"外貌与服装描述\"}],"
                + "\"shots\":[{\"place\":\"地点名\",\"scene\":\"画面描述\",\"shotSize\":\"景别\","
                + "\"angle\":\"机位\",\"light\":\"光线\",\"motion\":\"镜头运动\","
                + "\"endState\":\"这一镜结束时的画面\",\"dialogue\":\"台词\",\"duration\":10,"
                + "\"characters\":[\"角色名\"]}]}");
        lines.add("");
        lines.add("下面是一个**只用来示意长度和写法**的例子（内容不要照抄）：");
        lines.add("{\"title\":\"末班车\",\"setting\":\"深夜的空荡地铁站台，冷白荧光灯管，清冷偏青的色调，"
                + "电影感浅景深，35mm 胶片质感，细腻写实，低饱和，8k 高清\",\"places\":[\"地铁站台\"],"
                + "\"characters\":[{\"name\":\"阿岚\",\"desc\":\"二十七岁的女性，齐耳短发染成亚麻色，单眼皮，"
                + "鼻梁左侧有一颗小痣，穿灰色连帽卫衣和洗旧的牛仔裤，神情疲惫，肩膀微微含着\"}],");
        lines.add(" \"shots\":[{\"place\":\"地铁站台\",\"scene\":\"阿岚站在深夜的地铁站台黄线边，"
                + "双手抱着一只纸箱贴在胸前，低头看箱盖，眉头轻轻皱着，人占画面中部偏左，半身入画；"
                + "身后荧光灯管忽明忽暗，远处立柱上贴着褪色的广告，脚边放着一只旧帆布包\","
                + "\"shotSize\":\"中景\",\"angle\":\"平视\",\"light\":\"头顶冷白荧光，灯管闪动，边缘偏暗\","
                + "\"motion\":\"阿岚先把纸箱换到左手，抬手拨开额前的碎发，镜头从站台尽头缓慢推近到中景\","
                + "\"endState\":\"阿岚抬起头看向隧道尽头，纸箱仍抱在怀里，发丝被进站的风吹起\","
                + "\"dialogue\":\"\",\"duration\":10,\"characters\":[\"阿岚\"]}]}");
        lines.add("");
        lines.add("各字段要求：");
        lines.add("- title：剧名，12 字以内。");
        lines.add("- setting：全片统一的画面设定，**40~80 字，一段话**，全片每一镜都会共用它，"
                + "所以写的是「这个片子长什么样」而不是剧情。");
        lines.add("  按顺序写：空间类型与整体环境 → 时间与天气 → 主光源与光线质感 → 色调与饱和度 → 质感与画质。");
        lines.add("  例：「现代都市温馨居民宅，清晨，柔和自然侧光，暖调低饱和，电影感浅景深，细腻写实，"
                + "35mm 胶片质感，8k 高清」。");
        lines.add("  **不要写具体某个人、某个动作、某一镜的道具**（那是 scene 的事），也不要写画风名 —— 画风已经单独定了。");
        lines.add("- places：这个故事一共发生在几个地方，1~" + MAX_SCENES + " 个，每个写成 2~8 个字的**具体地点名**"
                + "（「厨房」「卧室」「小区花园」「公司茶水间」），不要写「室内」「室外」「某处」这种没信息量的。");
        lines.add("  · 一个地方可以连着好几镜，不用一镜换一个地方。");
        if (photoCount > 0) {
            lines.add("- characters：正好 " + photoCount + " 个（一张参考图一个，顺序一致）。");
        } else {
            lines.add("- characters：1~" + MAX_CHARACTERS + " 个角色，只列**真正出现在画面里**的人。");
        }
        lines.add("  · name：角色名，2~6 个字，用中文名，不要英文名或外国译名。");
        lines.add("  · desc：写清「性别、年龄段、发型发色、五官特征、服装、气质」，**50~80 字**。");
        lines.add("    这段描述会被拿去生成角色定妆照，也是全片人物长相一致的唯一依据，");
        lines.add("    所以必须具体、可画 —— 不要写「很漂亮」「有气质」这种画不出来的话，");
        lines.add("    要写「单眼皮」「鼻梁左侧有一颗小痣」「灰色连帽卫衣」这种画得出来的特征。");
        lines.add("- shots：正好 " + shotCount + " 个镜头，按剧情顺序。**每一镜必须是能定格成一张图的一瞬间**，不是一段连续动作。");
        // 小模型特别容易把「角色数」当成「镜头数」：给了 2 张人物图，它就只写 2 镜
        if (photoCount > 0) {
            lines.add("  · 注意：" + shotCount + " 是**镜头数**，跟上面「角色正好 " + photoCount + " 个」没关系 —— "
                    + "就算只有 " + photoCount + " 个角色，也要拍 " + shotCount + " 个不同机位/不同瞬间的镜头。");
        } else {
            lines.add(null);
        }
        lines.add("  · **输出前先数一遍 shots 数组的长度，必须正好 " + shotCount + " 个**：少了就接着往下补成新镜头，"
                + "不要提前收尾；用户列了几件事、几个动作，就一件事一镜，别把两三件事压进同一镜。");
        lines.add("  · place：这一镜发生在哪个地方，**必须从上头 places 里挑一个原样填**，不要自己新编。");
        lines.add("    同一场戏连着几镜就都填同一个地点 —— 这是防止「上一镜在厨房、下一镜莫名跑到客厅」的关键字段，**不要留空**。");
        lines.add("  · scene：这一镜定格的那幅画面，**60~100 字**，按「谁在做什么 → 在哪、周围有什么 → 人在画面里占多大」的顺序写：");
        lines.add("    1. 主体：谁、什么姿态、什么表情。有人物参考图的角色**不写长相**（年龄发型五官肤色一概不写），"
                + "只写姿态和表情；没有参考图的才写长相。");
        lines.add("       表情要具体：「嘴角压着一点笑」「眉头皱着、嘴唇抿紧」，不要只写「开心」「难过」。");
        lines.add("    2. 动作：一个**正在发生**的动作，用进行态（「正抬手推开窗户」「低头看着箱盖」）。");
        lines.add("       不要写「先…然后…」「站起来走向…」这种一段时间里发生的事 —— 图像模型只会画出其中一帧，而且经常画错那一帧。");
        lines.add("    3. 环境：地点、时间、天气，再加 **2~3 个具体道具或环境细节**（「灶台上冒着热气的不锈钢锅」"
                + "「脚边一只旧帆布包」「窗台上落了一层灰」）。");
        lines.add("       道具要能说明这个人在干什么、这个地方是谁的，不要写「一些家具」「各种摆设」这种空话。");
        lines.add("       **scene 开头或动作后面必须把这个地点写出来**（「…在厨房的灶台前切着菜」），不能只写人物动作 ——");
        lines.add("       只写动作的话出图时模型会自己编一个房间，同一场戏的两镜就会一个在厨房一个在客厅。");
        lines.add("    4. 占比：写清主体在画面里的大小和位置（「人占画面中部，半身入画」「人物偏右，背景大面积留白」）。");
        lines.add("    只写看得见的东西。不要写画风，不要用「镜头」「画面」开头，不要写心理活动（「她想起…」），不要写比喻。");
        lines.add("    不要照抄剧本原句：剧本写「她坐下」，要改写成「她正坐到窗边的木椅上，双手交叠放在膝头，肩膀松下来」。");
        lines.add("  · shotSize：这一镜的景别，只能填「特写 / 近景 / 中景 / 全景 / 远景」之一，**不要留空**。");
        lines.add("    交代环境用全景，对话和情绪用近景，道具细节用特写 —— 全片不要都是同一种景别。");
        lines.add("  · angle：机位角度，只能填「平视 / 低角度 / 高角度 / 俯拍 / 仰拍 / 过肩」之一。");
        lines.add("    情绪压迫用低角度，交代环境用高角度，对话用平视或过肩，全片不要一直平视。");
        lines.add("  · light：这一镜的光线，**10~20 字**，写清光源方向和质感（「窗外柔和侧光，皮肤有细腻高光」"
                + "「傍晚暖色逆光，轮廓发亮」）。");
        lines.add("    同一场戏里光线要一致，只在这个镜头换场或换时间时才变。");
        lines.add("  · endState：这一镜的动作做完之后，画面**停在哪** —— 20~45 字，同样写成一个能定格的画面。");
        lines.add("    它同时是两样东西：下一镜 scene 的起点，以及这一段视频的最后一帧。");
        lines.add("    所以 endState 必须和下一镜的 scene 是同一个瞬间：同一套服装、同一个朝向、同一种光线。");
        lines.add("    例：scene 写「她正抬手推开窗户」，endState 就写「窗户推开一半，她探身向外，侧脸被风吹起几缕碎发」。");
        lines.add("    **endState 里出现的动作，motion 必须已经走到**：endState 写「坐直，抬手拢了一下头发」，");
        lines.add("    motion 就得写到「…完全坐直，抬手拢了一下头发」；只写「坐起来、揉揉脖子」的话，");
        lines.add("    模型会在揉脖子那儿就收尾，尾帧那张「整理头发」的图接不上。");
        lines.add("  · motion：这一镜的**运镜方式 —— 只写机位怎么动**，10~20 字。");
        lines.add("    例：「缓慢推近，轻微下移」「固定机位，轻微手持感」「从左侧缓慢横移跟拍」。");
        lines.add("    **不要把人物的动作写进来**（人物的动作已经写在 scene 和 endState 里，重复一遍只会让模型做两遍）。");
        lines.add("    全片不要每一镜都用同一个运镜；交代环境用推拉或横移，情绪停留用固定机位。");
        lines.add("  · dialogue：这个镜头里的台词，15 字以内；没有台词就填空字符串。");
        lines.add("  · duration：这个镜头多长，只能填 5 或 10（秒）。**默认都填 10**（成片默认横屏 10 秒一段），");
        lines.add("    只有动作特别短、节奏要快的镜头才填 5。");
        lines.add("  · characters：这个镜头画面里出现的角色名，必须是上面 characters 里出现过的名字；");
        lines.add("    一个角色都不出现的空镜就填空数组。**这个字段决定了出图时挂谁的参考图，不要漏填**。");
        lines.add("");
        lines.add("硬性要求：");
        lines.add("- 镜头之间要连贯：上一镜和下一镜是同一场戏的推进，不要 12 个互不相关的画面。");
        lines.add("- **地点不能乱跳**：除了剧情真的换地方（并且要在 scene 里写明「走到…」「推开门进了…」），");
        lines.add("  连着几镜都在同一个地方。人物的家就是一个地方，别一镜卧室一镜客厅一镜又卧室。");
        lines.add("- **首尾相接**：成片是「图 1 → 视频 → 图 2 → 视频 → 图 3 …」串起来的，一张图既当上一段视频的尾帧、");
        lines.add("  又当下一段视频的首帧。所以第 N 镜的 endState 就是第 N+1 镜 scene 的起点，");
        lines.add("  相邻镜头之间服装、发型、妆容、光线、人物朝向都要能对上；");
        lines.add("  人物在画面里的位置也不要跳（上一镜人在左边，下一镜别毫无交代地跑到右边）。");
        lines.add("- 最后一镜的 endState 写这一场戏的收尾画面（不需要再接下一镜）。");
        lines.add("- 不要出现真实人名、品牌名、地名。");
        lines.add("- 不要暴力、色情、政治相关内容。");
        lines.add("- 全片统一画风：" + style + "。**不要把画风写进 scene**，前端会统一拼。");
        if (wantVideo) {
            lines.add("- 每段视频只跑「本镜 scene → 本镜 endState」这一小段：动作要短、要收得住，"
                    + "别写成一段几分钟的连续剧情。");
        } else {
            lines.add(null);
        }
        lines.add("");
        lines.add("用户给的内容：");
        lines.add("\"\"\"");
        lines.add(script == null ? "" : script.trim());
        lines.add("\"\"\"");
        return joinNonNull(lines);
    }

    // ============================================================== 字段清洗

    /**
     * 画面里已经写了景别就以它为准：scene 才是最终要画的那句话，跟它打架没意义
     * （模型常出现「scene 写中景、shotSize 填中近景」这种自相矛盾）。
     */
    public static String pickShotSize(Object raw, Object scene) {
        String sceneText = scene == null ? "" : String.valueOf(scene);
        for (String size : SHOT_SIZES) {
            if (sceneText.contains(size)) {
                return size;
            }
        }
        String value = CanvasAi.text(raw, 12);
        if (!value.isEmpty()) {
            for (String size : SHOT_SIZES) {
                if (value.contains(size)) {
                    return size;
                }
            }
        }
        // 两处都没写就留空，前端也就不会补 —— 瞎猜一档比不写更糟
        return "";
    }

    /** 光线：模型偶尔写一大段，砍短；「无 / 略」这种等于没给。 */
    public static String pickLight(Object raw) {
        String value = LIGHT_LEADING.matcher(CanvasAi.text(raw, 40)).replaceFirst("");
        if (value.isEmpty() || LIGHT_EMPTY.matcher(value).matches()) {
            return "";
        }
        return value;
    }

    /** 机位角度：只认这几档，认不出来就平视（默认档，写进提示词不会误导）。 */
    public static String pickAngle(Object raw) {
        String value = CanvasAi.text(raw, 12);
        for (String angle : SHOT_ANGLES) {
            if (value.contains(angle)) {
                return angle;
            }
        }
        return "平视";
    }

    /** 「这一镜结束时画面停在哪」。老分镜没有这个字段，就退回下一镜的 scene。 */
    public static String pickEndState(Object raw, Map<String, Object> nextShot) {
        String value = CanvasAi.text(raw, 200);
        if (!value.isEmpty()) {
            return value;
        }
        String next = CanvasAi.text(nextShot == null ? null : nextShot.get("scene"), 200);
        return CanvasAi.slice(next, 80);
    }

    /**
     * 把模型给的一堆镜头规整成能直接用的结构（免费对话模型经常少字段、给超范围的值）。
     *
     * <p>导出是为了能被冒烟直接验证字段清洗（不联网也能测）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizePlan(Object parsed,
                                                   int shotCount,
                                                   String style,
                                                   List<Map<String, Object>> photos) {
        Map<String, Object> source = parsed instanceof Map
                ? (Map<String, Object>) parsed : new LinkedHashMap<String, Object>();

        String title = CanvasAi.text(source.get("title"), 30);
        if (title.isEmpty()) {
            title = "未命名分镜";
        }
        // 全片统一设定：模型给的 setting 是「这个片子长什么样」的一段话，每一镜都共用
        String setting = CanvasAi.text(source.get("setting"), 300);

        List<Map<String, Object>> characters = new ArrayList<Map<String, Object>>();
        List<?> rawCharacters = CanvasAi.asList(source.get("characters"));
        for (int i = 0; i < rawCharacters.size() && i < MAX_CHARACTERS; i++) {
            Object item = rawCharacters.get(i);
            Map<String, Object> cast = item instanceof Map ? (Map<String, Object>) item : null;
            String name = CanvasAi.text(cast == null ? null : cast.get("name"), 20);
            String desc = CanvasAi.text(cast == null ? null : cast.get("desc"), 300);
            if (!name.isEmpty() && !desc.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("name", name);
                entry.put("desc", desc);
                characters.add(entry);
            }
        }
        while (characters.size() > MAX_CHARACTERS) {
            characters.remove(characters.size() - 1);
        }

        // 用户给了人物图：按顺序把图直接挂成这些角色的定妆照 ——
        // 这一步跳过「AI 出定妆照」，人物就是用户给的那个人，之后每个镜头都拿它当参考图
        for (int i = 0; i < photos.size(); i++) {
            if (i >= characters.size()) {
                break;
            }
            Map<String, Object> ref = refOf(photos.get(i));
            characters.get(i).put("refId", ref.get("id"));
            characters.get(i).put("refUrl", ref.get("localUrl") == null ? "" : ref.get("localUrl"));
            characters.get(i).put("fromPhoto", true);
        }

        // 模型列的角色比用户给的图少：多出来的图也得落到画布上，补出来的角色只用来挂图
        for (int i = characters.size(); i < photos.size(); i++) {
            String base = "角色" + (i + 1);
            String name = base;
            int n = 2;
            while (hasName(characters, name)) {
                name = base + "-" + n;
                n++;
            }
            Map<String, Object> ref = refOf(photos.get(i));
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", name);
            entry.put("desc", "按你给的参考图定妆");
            entry.put("refId", ref.get("id"));
            entry.put("refUrl", ref.get("localUrl") == null ? "" : ref.get("localUrl"));
            entry.put("fromPhoto", true);
            characters.add(entry);
        }

        Set<String> names = new LinkedHashSet<String>();
        for (Map<String, Object> cast : characters) {
            names.add(Texts.trim(cast.get("name")));
        }

        // 地点表：模型给的 places 只用来「约束镜头别乱填」，最终以每镜自己的 place 为准
        List<String> declared = new ArrayList<String>();
        for (Object place : CanvasAi.asList(source.get("places"))) {
            String text = CanvasAi.text(place, 20);
            if (!text.isEmpty()) {
                declared.add(text);
            }
        }
        while (declared.size() > MAX_SCENES) {
            declared.remove(declared.size() - 1);
        }

        List<Map<String, Object>> rawShots = new ArrayList<Map<String, Object>>();
        List<?> sourceShots = CanvasAi.asList(source.get("shots"));
        for (int index = 0; index < sourceShots.size(); index++) {
            Object item = sourceShots.get(index);
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> shot = (Map<String, Object>) item;
            String scene = CanvasAi.text(shot.get("scene"), 500);
            if (scene.isEmpty()) {
                continue;
            }
            // 视频模型基本只有 5 秒 / 10 秒两档；写 8、12 这种按就近归到两档上
            double rawDuration = CanvasAi.number(shot.get("duration"));
            int duration = (rawDuration > 0 && rawDuration < 8) ? 5 : 10;

            // 模型经常忘记填 characters：漏了就退回「镜头文字里提到谁就算谁」。
            // 注意是「并集」不是二选一：模型填了但不全的情况更多
            List<String> named = new ArrayList<String>();
            for (Object value : CanvasAi.asList(shot.get("characters"))) {
                String name = CanvasAi.text(value, 20);
                if (names.contains(name)) {
                    named.add(name);
                }
            }
            String dialogue = CanvasAi.text(shot.get("dialogue"), 100);
            String spoken = scene + dialogue;
            List<String> mentioned = new ArrayList<String>();
            for (Map<String, Object> cast : characters) {
                String name = Texts.trim(cast.get("name"));
                if (!name.isEmpty() && spoken.contains(name)) {
                    mentioned.add(name);
                }
            }
            // 模型填的排前面（它知道谁是主体），文字里提到的补在后面
            LinkedHashSet<String> inShot = new LinkedHashSet<String>();
            inShot.addAll(named);
            inShot.addAll(mentioned);
            List<String> inShotList = new ArrayList<String>();
            for (String name : inShot) {
                if (inShotList.size() >= MAX_CHARACTERS) {
                    break;
                }
                inShotList.add(name);
            }

            // 地点三路兜底：模型填的 place → 从 scene 文字里认 → 留给下面「继承上一镜」
            String givenPlace = CanvasAi.text(shot.get("place"), 20);

            Map<String, Object> cleaned = new LinkedHashMap<String, Object>();
            cleaned.put("index", index + 1);
            cleaned.put("scene", scene);
            cleaned.put("place", givenPlace.isEmpty() ? detectPlace(scene) : givenPlace);
            cleaned.put("shotSize", pickShotSize(shot.get("shotSize"), scene));
            cleaned.put("angle", pickAngle(shot.get("angle")));
            cleaned.put("light", pickLight(shot.get("light")));
            // motion 缺了就按镜头序号轮换一条兜底运镜，也不会整片都是同一句
            String motion = CanvasAi.text(shot.get("motion"), 200);
            cleaned.put("motion", motion.isEmpty() ? CAMERA_MOVES.get(index % CAMERA_MOVES.size()) : motion);
            cleaned.put("rawEndState", CanvasAi.text(shot.get("endState"), 200));
            cleaned.put("dialogue", dialogue);
            cleaned.put("duration", duration);
            cleaned.put("characters", inShotList);
            rawShots.add(cleaned);
        }
        while (rawShots.size() > shotCount) {
            rawShots.remove(rawShots.size() - 1);
        }

        // 地点补齐：自己认得出就用自己认出的，认不出就继承上一镜；第一镜认不出就用 places 的第一个
        String carried = "";
        for (Map<String, Object> shot : rawShots) {
            String place = Texts.trim(shot.get("place"));
            if (place.isEmpty()) {
                place = carried.isEmpty() ? (declared.isEmpty() ? "" : declared.get(0)) : carried;
                shot.put("place", place);
            }
            carried = place.isEmpty() ? carried : place;
        }

        // endState 要看着「下一镜」才填得准，所以等整列镜头都洗完再补
        List<Map<String, Object>> shots = new ArrayList<Map<String, Object>>(rawShots.size());
        for (int index = 0; index < rawShots.size(); index++) {
            Map<String, Object> shot = rawShots.get(index);
            Object rawEnd = shot.remove("rawEndState");
            Map<String, Object> next = index + 1 < rawShots.size() ? rawShots.get(index + 1) : null;
            shot.put("endState", pickEndState(rawEnd, next));
            shots.add(shot);
        }

        // 全片地点清单：用镜头里实际出现的顺序去重，给前端做「这一场在哪」的展示与批量改
        List<String> places = new ArrayList<String>();
        for (Map<String, Object> shot : shots) {
            String place = Texts.trim(shot.get("place"));
            if (!place.isEmpty() && !places.contains(place)) {
                places.add(place);
            }
        }

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("title", title);
        plan.put("setting", setting);
        plan.put("style", style);
        plan.put("places", places);
        plan.put("characters", characters);
        plan.put("shots", shots);
        return plan;
    }

    // ============================================================== 解析 / 补镜

    /** 从模型的回答里抠出「一串镜头」：可能是 {@code {"shots":[…]}}，也可能直接是 {@code […]}。 */
    public List<Map<String, Object>> parseShotsAnswer(Object answer) {
        Object got = chat.extractJson(answer == null ? "" : String.valueOf(answer));
        List<Map<String, Object>> asList = asShotList(got);
        if (!asList.isEmpty() || got instanceof List) {
            return asList;
        }
        if (got instanceof Map && ((Map<?, ?>) got).get("shots") instanceof List) {
            return asList;
        }
        String raw = FENCE_HEAD.matcher(Texts.trim(answer)).replaceFirst("");
        raw = FENCE_TAIL.matcher(raw).replaceFirst("").trim();
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            Object parsed = tryParse(raw.substring(start, end + 1));
            if (parsed instanceof List) {
                return asShotList(parsed);
            }
        }
        return new ArrayList<Map<String, Object>>();
    }

    /** 取镜头数组，并过滤掉没有 scene 的。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shotsOf(Object value) {
        List<?> items;
        if (value instanceof List) {
            items = (List<?>) value;
        } else if (value instanceof Map && ((Map<?, ?>) value).get("shots") instanceof List) {
            items = (List<?>) ((Map<?, ?>) value).get("shots");
        } else {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object item : items) {
            if (item instanceof Map && !CanvasAi.text(((Map<String, Object>) item).get("scene"), 500).isEmpty()) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    /**
     * 镜头数不够就接着补：已经写好的几镜留着，让它从最后一镜的收尾画面接着往下写缺的那几镜。
     * 最多补两轮 —— 补不出来就认了，不能让一份能用的分镜卡在这里。
     */
    @SuppressWarnings("unchecked")
    public Object topUpShots(Ask ask, Object parsed, int want, LongSupplier left) {
        Object current = parsed;
        for (int round = 0; round < 2; round++) {
            List<Map<String, Object>> shots = shotsOf(current);
            if (shots.size() >= want) {
                break;
            }
            // 剩余预算不够再跑一轮补镜了：与其硬塞到超时，不如把手上这几镜交出去
            if (left != null && left.getAsLong() < TOPUP_MIN_MS) {
                break;
            }
            int missing = want - shots.size();
            Map<String, Object> last = shots.get(shots.size() - 1);
            Map<String, Object> currentMap = current instanceof Map
                    ? (Map<String, Object>) current : new LinkedHashMap<String, Object>();
            List<String> names = new ArrayList<String>();
            for (Object item : CanvasAi.asList(currentMap.get("characters"))) {
                if (!(item instanceof Map)) {
                    continue;
                }
                String name = CanvasAi.text(((Map<String, Object>) item).get("name"), 20);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }

            // 补镜这一轮也要把地点带上：不然补出来的几镜又是一片没地点可依的画面
            List<String> places = new ArrayList<String>();
            for (Map<String, Object> shot : shots) {
                String place = placeOf(shot);
                if (!place.isEmpty() && !places.contains(place)) {
                    places.add(place);
                }
            }
            String lastPlace = placeOf(last);
            if (lastPlace.isEmpty() && !places.isEmpty()) {
                lastPlace = places.get(places.size() - 1);
            }
            // 全片设定要一起带进补镜这一轮：不然补出来的几镜会换一套光线和色调
            String setting = CanvasAi.text(currentMap.get("setting"), 300);

            List<String> parts = new ArrayList<String>();
            parts.add("**这一轮只补镜头，不要重写、不要重复已经有的那几镜。**");
            parts.add("上一份 JSON 里 shots 只有 " + shots.size() + " 个，还差 " + missing + " 个。已有的：");
            // 模型给的镜头没有 index 字段（那是清洗时补的），编号只能按数组顺序数
            List<String> existing = new ArrayList<String>();
            for (int i = 0; i < shots.size(); i++) {
                Map<String, Object> shot = shots.get(i);
                String place = placeOf(shot);
                existing.add("第 " + (i + 1) + " 镜（" + (place.isEmpty() ? "未注明地点" : place) + "）："
                        + CanvasAi.text(shot.get("scene"), 120));
            }
            parts.add(joinLines(existing));
            String lastEnd = CanvasAi.text(last.get("endState"), 200);
            if (lastEnd.isEmpty()) {
                lastEnd = CanvasAi.text(last.get("scene"), 120);
            }
            parts.add("第 " + shots.size() + " 镜收尾在原画面：" + lastEnd);
            parts.add("");
            parts.add(setting.isEmpty() ? "" : "全片统一设定（补的这几镜必须跟它一致）：" + setting);
            parts.add("");
            parts.add("接着往下写第 " + (shots.size() + 1) + " 到第 " + want + " 镜，只输出这个 JSON（不要解释、不要代码块）：");
            parts.add("{\"shots\":[{\"place\":\"…\",\"scene\":\"…\",\"shotSize\":\"…\",\"angle\":\"…\","
                    + "\"light\":\"…\",\"motion\":\"…\",\"endState\":\"…\",\"dialogue\":\"\","
                    + "\"duration\":10,\"characters\":[]}]}");
            parts.add("- shots 里**正好 " + missing + " 个元素**，字段要求跟上面那份总则完全一样"
                    + "（scene 60~100 字、能定格、不写长相、不写画风）。");
            if (!lastPlace.isEmpty()) {
                parts.add("- 地点：上一镜在「" + lastPlace + "」，这一镜**默认还在这里**，place 就填「" + lastPlace + "」；"
                        + "真的要走出去，必须先在 scene 里写明「推开…的门」「走到…」，再换 place。");
            } else {
                parts.add("- 地点：每镜都要填 place，同一场戏连着几镜填同一个地方。");
            }
            parts.add("- 必须接着第 " + shots.size() + " 镜往下讲：同一套服装、同一个朝向、同一种光线，别跳到别的场景。");
            if (!names.isEmpty()) {
                parts.add("- characters 只能填这些名字：" + joinNames(names)
                        + "；一个角色都没有的空镜填空数组。");
            } else {
                parts.add("- characters 填画面里出现的人名，空镜填空数组。");
            }
            String extra = joinLines(parts);

            List<Map<String, Object>> added = new ArrayList<Map<String, Object>>();
            try {
                added = shotsOf(parseShotsAnswer(ask.ask(extra, 0.55D)));
            } catch (RuntimeException err) {
                // 补镜这一路挂了不影响主流程，手上这几镜照常用
                break;
            }
            if (added.isEmpty()) {
                break;
            }
            // 小模型收到「接着往下写」经常干脆整份重写一遍：它一次给的够数，就说明这是一套完整的新分镜
            if (added.size() >= want) {
                List<Map<String, Object>> trimmed = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < added.size() && i < want; i++) {
                    trimmed.add(added.get(i));
                }
                Map<String, Object> next = new LinkedHashMap<String, Object>(currentMap);
                next.put("shots", trimmed);
                current = next;
                break;
            }
            // 没重写就往下接，但要把「一字不差抄了前面某镜」的剔掉
            Set<String> seen = new LinkedHashSet<String>();
            for (Map<String, Object> shot : shots) {
                seen.add(CanvasAi.text(shot.get("scene"), 12));
            }
            List<Map<String, Object>> fresh = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> shot : added) {
                String key = CanvasAi.text(shot.get("scene"), 12);
                if (!seen.contains(key)) {
                    fresh.add(shot);
                }
            }
            if (fresh.isEmpty()) {
                break;
            }
            List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>(shots);
            merged.addAll(fresh);
            while (merged.size() > want) {
                merged.remove(merged.size() - 1);
            }
            Map<String, Object> next = new LinkedHashMap<String, Object>(currentMap);
            next.put("shots", merged);
            current = next;
        }
        return current;
    }

    // ============================================================== 跑题检测

    /**
     * 用户内容里的字有多少真的出现在分镜里（按 2 字滑窗算重合率，0~1）。
     *
     * <p>免费小模型有个典型毛病：prompt 说「忠实改编」，它照样自己编一个完整故事 ——
     * 光看 JSON 格式完全合法，只有比内容才知道跑题了。这个分数就是那道闸门。
     */
    @SuppressWarnings("unchecked")
    public static double scriptCoverage(Object script, Map<String, Object> plan) {
        String clean = CanvasAi.punctStrip(script == null ? "" : String.valueOf(script));
        Set<String> grams = new LinkedHashSet<String>();
        for (int i = 0; i + 1 < clean.length(); i++) {
            grams.add(clean.substring(i, i + 2));
        }
        // 太短的内容（「一只猫钓鱼」）没有足够的字可比，别误伤
        if (grams.size() < 6) {
            return 1.0D;
        }

        StringBuilder hay = new StringBuilder();
        hay.append(Texts.trim(plan.get("title")));
        for (Object item : CanvasAi.asList(plan.get("characters"))) {
            if (item instanceof Map) {
                Map<String, Object> cast = (Map<String, Object>) item;
                hay.append(Texts.trim(cast.get("name"))).append(Texts.trim(cast.get("desc")));
            }
        }
        for (Object item : CanvasAi.asList(plan.get("shots"))) {
            if (item instanceof Map) {
                Map<String, Object> shot = (Map<String, Object>) item;
                hay.append(Texts.trim(shot.get("scene")));
                hay.append(Texts.trim(shot.get("motion")));
                hay.append(Texts.trim(shot.get("dialogue")));
                hay.append(Texts.trim(shot.get("endState")));
            }
        }
        String body = hay.toString();
        int hit = 0;
        for (String gram : grams) {
            if (body.contains(gram)) {
                hit++;
            }
        }
        return hit / (double) grams.size();
    }

    // ============================================================== 主链路

    /**
     * 剧本 → 分镜结构。
     *
     * <p>返回 {@code title / setting / style / places / characters / shots} +
     * {@code loyalty{score,retried,weak}} + {@code script{source,note,text}} + {@code photos{used,attached,error}}。
     * 只出文字，不创建任何任务。
     *
     * @param budgetMs 整条链路的总预算；{@code null} 或非法值用 {@link #DIRECTOR_BUDGET_MS}
     */
    public Map<String, Object> directStoryboard(Object script,
                                                Object shotCount,
                                                Object style,
                                                boolean wantVideo,
                                                Object photoIds,
                                                Object budgetMs) {
        // content 会在「本地 GPT 先写一版剧本」那一步被换掉，所以用数组包一层给闭包读
        String initial = script == null ? "" : String.valueOf(script).trim();
        if (initial.length() < 6) {
            throw new IllegalArgumentException("先贴一段剧本，或者写一句题材（至少 6 个字）");
        }
        final String[] content = new String[] {initial};

        long budget = DIRECTOR_BUDGET_MS;
        if (budgetMs != null) {
            double parsed = CanvasAi.number(budgetMs);
            budget = Math.max(60000L, CanvasAi.pyRound(parsed));
        }
        final long deadline = System.currentTimeMillis() + budget;
        LongSupplier left = new LongSupplier() {
            @Override
            public long getAsLong() {
                return deadline - System.currentTimeMillis();
            }
        };

        final int want = Math.max(MIN_SHOTS, Math.min(MAX_SHOTS,
                shotCount == null ? DEFAULT_SHOTS : (int) CanvasAi.pyRound(CanvasAi.numberOr(shotCount, DEFAULT_SHOTS))));

        String styleText = CanvasAi.slice(Texts.trim(style), 300);
        final String useStyle = styleText.isEmpty() ? DEFAULT_DIRECTOR_STYLE : styleText;

        // ── 第一层：要不要先让本地 GPT 写一版剧本 ──────────────────────────────
        // 只在用户给的**不是**剧本（一句题材 / 几句话）时才写：用户已经贴了整份剧本，
        // 再让 GPT 改写一遍就是画蛇添足，还会把他写的东西改掉。
        String scriptSource = "user";
        String scriptNote = "";
        boolean wantGptScript = "codex".equals(storyModel.scriptEngine()) && !isScriptLike(content[0]);
        if (wantGptScript) {
            // 写剧本要 15~40 秒，预算里必须先扣掉；剩下的才是拆分的
            int writeBudget = (int) Math.min(120000L, Math.max(30000L, left.getAsLong() - 60000L));
            try {
                Map<String, Object> written = scriptWriter.writeScriptWithGpt(content[0], want, useStyle, writeBudget);
                content[0] = Texts.str(written, "script");
                scriptSource = "gpt";
                scriptNote = "题材是本地 GPT 写成剧本的";
            } catch (RuntimeException err) {
                // 写剧本失败不能把整条链路拖死：退回「拿题材直接拆」，并把原因说清楚
                scriptNote = "本地 GPT 没能写剧本（" + messageOf(err) + "），这次直接按你给的题材拆";
            }
        }

        // 跟 build_prompt 里同一套口径：镜头清单（几件事）也算剧本，不能只看字数
        final boolean fullScript = isScriptLike(content[0]);
        final List<Map<String, Object>> photos = photoRefs.collect(photoIds, MAX_CHARACTERS);
        final String[] photoError = new String[] {""};
        // 图还发不发给模型。纯文本模型接不了图（直接 400），退回来之后就不再发了 ——
        // 但 photos 本身要留着：它们仍然会被挂成角色的定妆照
        final boolean[] photosAttached = new boolean[] {!photos.isEmpty()};

        final List<String> dataUris = new ArrayList<String>();
        for (Map<String, Object> photo : photos) {
            Object uri = photo.get("dataUri");
            if (uri != null) {
                dataUris.add(String.valueOf(uri));
            }
        }

        // 编导引擎：Java 版只有 API 这一条路，豆包那条依赖 Electron 客户端桥，没有复刻
        if ("doubao".equals(directorEngine())) {
            throw new IllegalArgumentException("本地豆包引擎依赖桌面客户端，Java 版请切换到 API 引擎");
        }

        Ask ask = new Ask() {
            @Override
            public String ask(String extra, Double temperature) {
                String base = buildPrompt(content[0], want, useStyle, wantVideo,
                        photos.size(), photosAttached[0]);
                String prompt = extra == null || extra.isEmpty() ? base : base + "\n\n" + extra;
                // 长剧本或有人物图时是要「照着写」，温度压低一点，少点自由发挥
                double heat = temperature != null ? temperature
                        : (fullScript || !photos.isEmpty() ? 0.6D : 0.85D);
                // 每次调用只能用「剩下多少预算」，不能每次重新拿满 240 秒
                int timeout = (int) Math.max(20000L, left.getAsLong() - 8000L);
                return chatScript(prompt, heat, timeout, photosAttached[0] ? dataUris : null);
            }
        };

        Object parsed;
        try {
            parsed = chat.extractJson(ask.ask(null, null));
        } catch (RuntimeException err) {
            if (photos.isEmpty()) {
                throw err;
            }
            // 纯文本模型接不了图片（直接 400）。图丢了也要把分镜拆出来，这次不带图再试一遍
            photoError[0] = messageOf(err);
            if (photoError[0].isEmpty()) {
                photoError[0] = "模型不接受图片";
            }
            photosAttached[0] = false;
            parsed = chat.extractJson(ask.ask(null, null));
        }
        if (parsed == null) {
            throw new IllegalArgumentException("模型没有按要求给出 JSON 分镜，换个模型再试（免费小模型经常不守格式）");
        }

        // 镜头数不够（小模型把角色数当镜头数是常事）→ 接着补，别让它就这么少着交差
        parsed = topUpShots(ask, parsed, want, left);

        Map<String, Object> plan = sanitizePlan(parsed, want, useStyle, photos);
        if (CanvasAi.asList(plan.get("shots")).isEmpty()) {
            throw new IllegalArgumentException("模型没有给出可用的镜头画面描述，换个模型再试");
        }

        double score = scriptCoverage(content[0], plan);
        boolean retried = false;

        // 跑题了就再来一次：把「必须照用户的内容」单独顶到 prompt 末尾，温度压到 0.3。
        // 只在剩余预算够跑「重拆 + 再补镜」时才做
        if (score < LOYALTY_MIN && left.getAsLong() >= RETRY_MIN_MS) {
            try {
                String retryAnswer = ask.ask(fullScript
                        ? "**上面这一版跑题了。重新拆一次：只写用户内容里真的有人、有事、有场景，不要另编故事。**"
                        : "**上面这一版跑题了。重新拆一次：只围绕用户写的那件事展开，不要扯到无关的人物和场景。**",
                        0.3D);
                Object retryParsed = chat.extractJson(retryAnswer);
                if (retryParsed != null) {
                    // 重拆的这一版也可能少给，一样补齐再比
                    retryParsed = topUpShots(ask, retryParsed, want, left);
                    Map<String, Object> retryPlan = sanitizePlan(retryParsed, want, useStyle, photos);
                    double retryScore = CanvasAi.asList(retryPlan.get("shots")).isEmpty()
                            ? 0.0D : scriptCoverage(content[0], retryPlan);
                    if (retryScore > score) {
                        plan = retryPlan;
                        score = retryScore;
                    }
                }
                retried = true;
            } catch (RuntimeException err) {
                // 重试用尽就用手上这版，别让用户白等
            }
        }

        int fromPhoto = 0;
        for (Object item : CanvasAi.asList(plan.get("characters"))) {
            if (item instanceof Map && isTruthy(((Map<?, ?>) item).get("fromPhoto"))) {
                fromPhoto++;
            }
        }

        Map<String, Object> scriptInfo = new LinkedHashMap<String, Object>();
        scriptInfo.put("source", scriptSource);
        scriptInfo.put("note", scriptNote);
        scriptInfo.put("text", "gpt".equals(scriptSource) ? content[0] : "");

        Map<String, Object> loyalty = new LinkedHashMap<String, Object>();
        loyalty.put("score", score);
        loyalty.put("retried", retried);
        loyalty.put("weak", score < LOYALTY_MIN);

        Map<String, Object> photoInfo = new LinkedHashMap<String, Object>();
        photoInfo.put("used", photosAttached[0] ? photos.size() : 0);
        photoInfo.put("attached", fromPhoto);
        photoInfo.put("error", photoError[0]);

        Map<String, Object> out = new LinkedHashMap<String, Object>(plan);
        out.put("script", scriptInfo);
        out.put("loyalty", loyalty);
        out.put("photos", photoInfo);
        return out;
    }

    // ============================================================== 模型调用

    /**
     * 纯文本 / 多模态对话：给「AI 编导」这类只要文字结构的功能用。
     *
     * <p>配置与报错口径全部复用「写剧本」那一套（{@link ScriptChatClient}）——
     * 两个入口都在调同一个模型，用户看到的错误提示就该一模一样。
     */
    public String chatScript(String prompt, double temperature, int timeoutMs, List<String> images) {
        Map<String, Object> cfg = storyModel.scriptModelConfig();
        if (Texts.str(cfg, "baseUrl").isEmpty() || Texts.str(cfg, "model").isEmpty()) {
            throw new IllegalArgumentException(
                    "还没有配置写剧本的模型（要支持 OpenAI 兼容的对话接口），先去「视频生成 → 分镜」里填一个");
        }
        if (Texts.str(cfg, "apiKey").isEmpty()) {
            throw new IllegalArgumentException("写剧本的模型还没有填 API Key");
        }

        // 有图就发多模态 content 数组；没图保持纯文本，兼容不支持视觉的模型
        List<String> usable = new ArrayList<String>();
        if (images != null) {
            for (String uri : images) {
                if (uri != null && uri.startsWith("data:image/")) {
                    usable.add(uri);
                }
            }
        }
        Object content = usable.isEmpty()
                ? (Object) (prompt == null ? "" : prompt)
                : (Object) ScriptChatClient.multimodal(prompt, usable);

        List<Object> messages = new ArrayList<Object>();
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", content);
        messages.add(message);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", Texts.str(cfg, "model"));
        body.put("messages", messages);
        body.put("temperature", temperature);
        return chat.postChat(cfg, body, timeoutMs);
    }

    // ============================================================== 工具

    /** 编导链路里「问模型一句」的回调；{@code temperature} 为 {@code null} 表示用默认温度。 */
    public interface Ask {

        String ask(String extra, Double temperature);
    }

    private static String placeOf(Map<String, Object> shot) {
        String place = CanvasAi.text(shot.get("place"), 20);
        return place.isEmpty() ? detectPlace(shot.get("scene")) : place;
    }

    private static Map<String, Object> refOf(Map<String, Object> photo) {
        Object ref = photo == null ? null : photo.get("ref");
        if (ref instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) ref;
            return cast;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static boolean hasName(List<Map<String, Object>> characters, String name) {
        for (Map<String, Object> cast : characters) {
            if (name.equals(Texts.trim(cast.get("name")))) {
                return true;
            }
        }
        return false;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asShotList(Object value) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private Object tryParse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(text, Object.class);
        } catch (Exception err) {
            return null;
        }
    }

    private static String joinNonNull(List<String> lines) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            if (!first) {
                out.append('\n');
            }
            out.append(line);
            first = false;
        }
        return out.toString();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(lines.get(i) == null ? "" : lines.get(i));
        }
        return out.toString();
    }

    private static String joinNames(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append('、');
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    private static String messageOf(Throwable err) {
        String message = err.getMessage();
        return message == null || message.trim().isEmpty() ? err.getClass().getSimpleName() : message;
    }

    // ============================================================== 常量初始化

    private static List<Map<String, Object>> buildStyles() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(6);
        out.add(style("cinema", "电影写实", "电影感写实风格，35mm 胶片质感，自然光，浅景深，细节丰富"));
        out.add(style("anime", "日式动画", "日式 2D 动画风格，干净线条，明亮色彩，赛璐璐上色"));
        out.add(style("illust", "扁平插画", "扁平插画风格，柔和配色，简洁构图，轻微噪点质感"));
        out.add(style("3d", "3D 渲染", "3D 渲染风格，柔和布光，卡通化比例，细腻材质"));
        out.add(style("guofeng", "国风水墨", "国风水墨插画风格，留白构图，宣纸质感，淡彩"));
        out.add(style("cyber", "赛博霓虹", "赛博朋克风格，霓虹光效，湿润反光，高对比冷色调"));
        return out;
    }

    private static Map<String, Object> style(String id, String label, String prompt) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("label", label);
        out.put("prompt", prompt);
        return out;
    }

    private static List<String> buildPlaceWords() {
        List<String> out = new ArrayList<String>();
        out.add("厨房");
        out.add("客厅");
        out.add("卧室");
        out.add("书房");
        out.add("餐厅");
        out.add("阳台");
        out.add("卫生间");
        out.add("浴室");
        out.add("玄关");
        out.add("走廊");
        out.add("楼梯");
        out.add("院子");
        out.add("庭院");
        out.add("花园");
        out.add("屋顶");
        out.add("天台");
        out.add("地下室");
        out.add("车库");
        out.add("电梯");
        out.add("办公室");
        out.add("会议室");
        out.add("教室");
        out.add("图书馆");
        out.add("医院");
        out.add("病房");
        out.add("咖啡店");
        out.add("咖啡厅");
        out.add("酒吧");
        out.add("书店");
        out.add("超市");
        out.add("商场");
        out.add("街道");
        out.add("街头");
        out.add("马路");
        out.add("巷子");
        out.add("小巷");
        out.add("广场");
        out.add("公园");
        out.add("河边");
        out.add("海边");
        out.add("湖边");
        out.add("山上");
        out.add("山顶");
        out.add("森林");
        out.add("树林");
        out.add("田野");
        out.add("草原");
        out.add("沙漠");
        out.add("雪地");
        out.add("地铁站");
        out.add("车站");
        out.add("机场");
        out.add("火车站");
        out.add("码头");
        out.add("船上");
        out.add("车内");
        out.add("车里");
        out.add("操场");
        out.add("球场");
        out.add("泳池");
        out.add("健身房");
        out.add("宫殿");
        out.add("大殿");
        out.add("闺房");
        out.add("客栈");
        out.add("酒楼");
        out.add("茶馆");
        out.add("庙");
        out.add("寺");
        out.add("牢房");
        out.add("营帐");
        out.add("床前");
        out.add("床边");
        out.add("窗前");
        out.add("窗边");
        out.add("门口");
        out.add("门前");
        out.add("桌边");
        out.add("灶台");
        out.add("灶前");
        return out;
    }

    private static List<String> buildCameraMoves() {
        List<String> out = new ArrayList<String>(4);
        out.add("镜头缓慢推近，人物自然呼吸，画面有细微动态");
        out.add("镜头轻微横移，人物动作连贯，背景有轻微流动感");
        out.add("镜头固定，人物表情和动作自然变化，发丝和衣角轻轻动");
        out.add("镜头缓慢拉远，环境细节依次展开，人物保持动作");
        return out;
    }

    private static List<String> buildShotSizes() {
        List<String> out = new ArrayList<String>(5);
        out.add("特写");
        out.add("近景");
        out.add("中景");
        out.add("全景");
        out.add("远景");
        return out;
    }

    private static List<String> buildShotAngles() {
        List<String> out = new ArrayList<String>(6);
        out.add("平视");
        out.add("低角度");
        out.add("高角度");
        out.add("俯拍");
        out.add("仰拍");
        out.add("过肩");
        return out;
    }
}
