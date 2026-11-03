package com.lumenforge.provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片协议常量、默认地址、尺寸预设与解析工具。
 *
 * <p>与 Python 版 {@code app/providers/imagegen.py} 的常量区一一对应，前端「模型管理」
 * 与「生成」表单的下拉候选都从这里取，避免两边硬编码漂移。</p>
 */
public final class ImageProtocols {
    private ImageProtocols() {}

    // ---------------------------------------------------------------- 协议标识
    public static final String ARK = "ark";
    public static final String OPENAI = "openai";
    public static final String ZHIPU = "zhipu";
    public static final String JIMENG = "jimeng";
    public static final String WANXIANG = "wanxiang";
    public static final String HUNYUAN = "hunyuan";
    public static final String MINIMAX = "minimax";
    public static final String CLOUDFLARE = "cloudflare";

    /** 当前支持的 8 种协议，顺序即前端下拉顺序。 */
    public static final List<String> PROTOCOLS = Collections.unmodifiableList(Arrays.asList(
            ARK, OPENAI, ZHIPU, JIMENG, WANXIANG, HUNYUAN, MINIMAX, CLOUDFLARE));

    /** 单次任务最多几张参考图（超出直接报错，不静默截断）。 */
    public static final int MAX_REFS = 10;
    /** 单次任务最多生成几张图。 */
    public static final int MAX_COUNT = 4;

    public static final String PROTOCOL_HELP =
            "协议只支持 ark（火山方舟）、openai（兼容接口）、zhipu（智谱 CogView）、jimeng（即梦）、"
            + "wanxiang（通义万相）、hunyuan（腾讯混元生图）、minimax（MiniMax 海螺）或 cloudflare（Cloudflare Workers AI）";

    // ---------------------------------------------------------------- 默认地址
    public static final String ARK_BASE = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String ZHIPU_BASE = "https://open.bigmodel.cn/api/paas/v4";
    public static final String JIMENG_BASE = "https://visual.volcengineapi.com";
    public static final String WANXIANG_BASE = "https://dashscope.aliyuncs.com";
    public static final String HUNYUAN_BASE = "https://tokenhub.tencentmaas.com";
    public static final String MINIMAX_BASE = "https://api.minimax.cn";
    public static final String CLOUDFLARE_BASE = "https://api.cloudflare.com";
    public static final String OPENAI_BASE = "https://api.openai.com/v1";

    /** 协议 → 默认 Base URL；未知协议回落到 OpenAI 兼容地址。 */
    public static String defaultBase(String protocol) {
        if (ARK.equals(protocol)) return ARK_BASE;
        if (ZHIPU.equals(protocol)) return ZHIPU_BASE;
        if (JIMENG.equals(protocol)) return JIMENG_BASE;
        if (WANXIANG.equals(protocol)) return WANXIANG_BASE;
        if (HUNYUAN.equals(protocol)) return HUNYUAN_BASE;
        if (MINIMAX.equals(protocol)) return MINIMAX_BASE;
        if (CLOUDFLARE.equals(protocol)) return CLOUDFLARE_BASE;
        return OPENAI_BASE;
    }

    // ---------------------------------------------------------------- 即梦
    public static final String JIMENG_HOST = "visual.volcengineapi.com";
    public static final String JIMENG_REGION = "cn-north-1";
    public static final String JIMENG_SERVICE = "cv";
    public static final String JIMENG_REQ_KEY = "jimeng_t2i_v40";
    /** 带参考图时改用「图生图 3.0 智能参考」的 req_key。 */
    public static final String JIMENG_I2I_REQ_KEY = "jimeng_i2i_v30";
    public static final String JIMENG_API_VERSION = "2022-08-31";
    public static final String JIMENG_ACTION_SUBMIT = "CVSync2AsyncSubmitTask";
    public static final String JIMENG_ACTION_QUERY = "CVSync2AsyncGetResult";
    public static final long JIMENG_POLL_INTERVAL_MS = 3000L;
    public static final long JIMENG_POLL_TIMEOUT_MS = 8L * 60L * 1000L;

    public static final List<String> JIMENG_SIZE_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "1024x1024", "2048x2048", "4096x4096",
            "2304x1728", "1728x2304", "4694x3520", "3520x4694",
            "2496x1664", "1664x2496", "4992x3328", "3328x4992",
            "2560x1440", "1440x2560", "5404x3040", "3040x5404",
            "3024x1296", "6198x2656"));
    public static final List<String> JIMENG_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "jimeng_t2i_v40", "jimeng_i2i_v30"));

    // ---------------------------------------------------------------- 通义万相
    /** 命中这些前缀的万相模型走新版「多模态生成」同步接口。 */
    public static final List<String> WANXIANG_MM_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "wan2.5-i2i", "wan2.6", "wan2.7"));
    /** 命中这些前缀的万相模型是文生图模型（走 text2image 异步接口，不要参考图）。 */
    public static final List<String> WANXIANG_T2I_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "wan2.2-t2i", "wan2.1-t2i", "wanx2.1-t2i", "wanx2.2-t2i"));
    /** 万相图像编辑支持的功能名（对应 Python {@code WANXIANG_FUNCTIONS}），下发前端当选项。 */
    public static final List<String> WANXIANG_FUNCTIONS = Collections.unmodifiableList(Arrays.asList(
            "description_edit", "stylization_all", "stylization_local",
            "description_edit_with_mask", "remove_watermark", "expand",
            "super_resolution", "colorization", "doodle", "control_cartoon_feature"));
    public static final long WANXIANG_POLL_INTERVAL_MS = 3000L;
    public static final long WANXIANG_POLL_TIMEOUT_MS = 8L * 60L * 1000L;

    // ---------------------------------------------------------------- Cloudflare
    /** FLUX.2 参考图上限（官方支持 input_image_0..3 共 4 张）。 */
    public static final int CLOUDFLARE_MAX_REFS = 4;
    /** FLUX.2 硬性要求参考图小于 512×512。 */
    public static final int CLOUDFLARE_REF_MAX_EDGE = 512;

    // ---------------------------------------------------------------- 智谱尺寸枚举
    /** cogview 系尺寸枚举；填枚举外的值接口会 400 拒收，所以只在命中枚举时下发。 */
    public static final Set<String> ZHIPU_IMAGE_SIZES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "1024x1024", "768x1344", "864x1152", "1344x768", "1152x864", "1440x720", "720x1440")));

    // ---------------------------------------------------------------- 尺寸候选
    /** 常用尺寸候选：方舟支持 1K/2K/3K/4K 或「宽x高」，OpenAI 兼容接口用「宽x高」。 */
    public static final List<String> SIZE_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "1K", "2K", "3K", "4K", "1024x1024", "1536x1024", "1024x1536", "2048x2048"));

    public static final List<String> ZHIPU_SIZE_PRESETS = Collections.unmodifiableList(
            new ArrayList<String>(new java.util.TreeSet<String>(ZHIPU_IMAGE_SIZES)));

    public static final List<String> HUNYUAN_SIZE_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "1024:1024", "768:1344", "1344:768", "1280:720", "720:1280"));

    public static final List<String> MINIMAX_SIZE_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "1:1", "16:9", "4:3", "3:2", "2:3", "3:4", "9:16", "21:9"));

    public static final List<String> CLOUDFLARE_SIZE_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "1024x1024", "1024x768", "768x1024", "1152x768", "768x1152", "1280x720", "720x1280"));

    public static final List<String> ARK_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "doubao-seedream-5-0-pro-260628",
            "doubao-seedream-5-0-flash-260915",
            "doubao-seedream-5-0-260128",
            "doubao-seedream-4-5-251128",
            "doubao-seedream-4-0-250828"));

    public static final List<String> ZHIPU_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "cogview-3-flash", "cogview-4", "cogview-4-250304", "glm-image"));

    public static final List<String> WANXIANG_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "wan2.7-image-pro", "wan2.6-image", "wan2.5-i2i-preview", "wanx2.1-imageedit",
            "wan2.2-t2i-flash", "wan2.2-t2i-plus", "wanx2.1-t2i-turbo", "wanx2.1-t2i-plus"));

    public static final List<String> HUNYUAN_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "hy-image-v3", "hy-image-v3.5-preview"));

    public static final List<String> MINIMAX_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "image-01", "image-01-live"));

    public static final List<String> CLOUDFLARE_MODEL_PRESETS = Collections.unmodifiableList(Arrays.asList(
            "@cf/black-forest-labs/flux-2-klein-4b",
            "@cf/black-forest-labs/flux-2-klein-9b",
            "@cf/black-forest-labs/flux-2-dev"));

    /** 图片「比例 → 该比例下的尺寸」对照表（按协议）。数组第 1 个是该比例默认值。 */
    public static final Map<String, Map<String, List<String>>> IMAGE_RATIO_PRESETS = buildRatioPresets();

    private static Map<String, Map<String, List<String>>> buildRatioPresets() {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<String, Map<String, List<String>>>();
        out.put(ARK, ratios(new Object[][]{
                {"1:1", new String[]{"2048x2048", "4096x4096"}},
                {"4:3", new String[]{"2304x1728"}},
                {"3:4", new String[]{"1728x2304"}},
                {"3:2", new String[]{"2496x1664"}},
                {"2:3", new String[]{"1664x2496"}},
                {"16:9", new String[]{"2560x1440"}},
                {"9:16", new String[]{"1440x2560"}},
                {"21:9", new String[]{"3024x1296"}}}));
        out.put(OPENAI, ratios(new Object[][]{
                {"1:1", new String[]{"1024x1024", "2048x2048"}},
                {"3:2", new String[]{"1536x1024"}},
                {"2:3", new String[]{"1024x1536"}}}));
        out.put(ZHIPU, ratios(new Object[][]{
                {"1:1", new String[]{"1024x1024"}},
                {"4:3", new String[]{"1152x864"}},
                {"3:4", new String[]{"864x1152"}},
                {"16:9", new String[]{"1344x768"}},
                {"9:16", new String[]{"768x1344"}},
                {"2:1", new String[]{"1440x720"}},
                {"1:2", new String[]{"720x1440"}}}));
        out.put(JIMENG, ratios(new Object[][]{
                {"1:1", new String[]{"2048x2048", "4096x4096", "1024x1024"}},
                {"4:3", new String[]{"2304x1728", "4694x3520"}},
                {"3:4", new String[]{"1728x2304", "3520x4694"}},
                {"3:2", new String[]{"2496x1664", "4992x3328"}},
                {"2:3", new String[]{"1664x2496", "3328x4992"}},
                {"16:9", new String[]{"2560x1440", "5404x3040"}},
                {"9:16", new String[]{"1440x2560", "3040x5404"}},
                {"21:9", new String[]{"3024x1296", "6198x2656"}}}));
        out.put(WANXIANG, ratios(new Object[][]{
                {"1:1", new String[]{"1024x1024"}},
                {"16:9", new String[]{"1280x720"}},
                {"9:16", new String[]{"720x1280"}}}));
        out.put(HUNYUAN, ratios(new Object[][]{
                {"1:1", new String[]{"1024:1024"}},
                {"16:9", new String[]{"1344:768", "1280:720"}},
                {"9:16", new String[]{"768:1344", "720:1280"}}}));
        out.put(MINIMAX, ratios(new Object[][]{
                {"1:1", new String[]{"1:1"}},
                {"4:3", new String[]{"4:3"}},
                {"3:4", new String[]{"3:4"}},
                {"3:2", new String[]{"3:2"}},
                {"2:3", new String[]{"2:3"}},
                {"16:9", new String[]{"16:9"}},
                {"9:16", new String[]{"9:16"}},
                {"21:9", new String[]{"21:9"}}}));
        out.put(CLOUDFLARE, ratios(new Object[][]{
                {"1:1", new String[]{"1024x1024"}},
                {"4:3", new String[]{"1024x768"}},
                {"3:4", new String[]{"768x1024"}},
                {"3:2", new String[]{"1152x768"}},
                {"2:3", new String[]{"768x1152"}},
                {"16:9", new String[]{"1280x720"}},
                {"9:16", new String[]{"720x1280"}}}));
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> ratios(Object[][] rows) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        for (Object[] row : rows) {
            out.put((String) row[0], Collections.unmodifiableList(Arrays.asList((String[]) row[1])));
        }
        return Collections.unmodifiableMap(out);
    }

    // ---------------------------------------------------------------- 尺寸解析

    private static final Pattern WXH = Pattern.compile("^(\\d{2,5})\\s*[xX×]\\s*(\\d{2,5})$");
    private static final Pattern COLON = Pattern.compile("^(\\d{2,5})\\s*[:：]\\s*(\\d{2,5})$");
    private static final Pattern RATIO = Pattern.compile("^(\\d{1,2}):(\\d{1,2})$");
    private static final Pattern JIMENG_WXH = Pattern.compile("^(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})$");

    /** 「宽x高」→ [width, height]；格式不对返回 null。 */
    public static int[] parseWxh(String size) {
        Matcher m = WXH.matcher(trim(size));
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** 「宽:高」→ "宽:高" 规范化串；格式不对返回空串（混元生图用冒号分隔）。 */
    public static String normalizeColonSize(String size) {
        Matcher m = COLON.matcher(trim(size));
        return m.matches() ? Integer.parseInt(m.group(1)) + ":" + Integer.parseInt(m.group(2)) : "";
    }

    /** 「宽x高」给宽高、「a:b」给比例，两种写法都收；都不是则返回空 Map。 */
    public static Map<String, Object> parseSizeOrRatio(String size) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int[] wh = parseWxh(size);
        if (wh != null) {
            out.put("width", wh[0]);
            out.put("height", wh[1]);
            return out;
        }
        String ratio = normalizeColonSize(size);
        if (!ratio.isEmpty()) out.put("aspectRatio", ratio);
        return out;
    }

    /** 解析即梦「宽x高」；非法或不在候选里返回 null（不下发，走平台默认）。 */
    public static int[] parseJimengSize(String size) {
        Matcher m = JIMENG_WXH.matcher(trim(size));
        if (!m.matches()) return null;
        int w = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        return JIMENG_SIZE_PRESETS.contains(w + "x" + h) ? new int[]{w, h} : null;
    }

    /**
     * 把前端传来的 size 归一成 "9:16" 这种比例。
     *
     * <p>画布给豆包节点存的是比例标签，普通图片模块存的是具体尺寸 —— 两种都得认。</p>
     */
    public static String sizeToRatio(String size) {
        String raw = trim(size);
        Matcher byRatio = RATIO.matcher(raw);
        if (byRatio.matches()) return Integer.parseInt(byRatio.group(1)) + ":" + Integer.parseInt(byRatio.group(2));
        Matcher byPx = WXH.matcher(raw);
        if (!byPx.matches()) return "";
        int a = Integer.parseInt(byPx.group(1));
        int b = Integer.parseInt(byPx.group(2));
        int g = gcd(a, b);
        if (g <= 0) g = 1;
        a /= g;
        b /= g;
        // 约不到常见档位就不硬猜（例如 1023x1025），交给上游自己的默认画幅
        return (a <= 32 && b <= 32) ? a + ":" + b : "";
    }

    private static int gcd(int x, int y) { return y == 0 ? x : gcd(y, x % y); }

    /** 万相模型是否走新版多模态同步接口。 */
    public static boolean isWanxiangMultimodal(String model) {
        String id = trim(model);
        for (String prefix : WANXIANG_MM_PREFIXES) if (id.startsWith(prefix)) return true;
        return false;
    }

    /** 万相模型是否是文生图专用（不要参考图）。 */
    public static boolean isWanxiangTextToImage(String model) {
        String id = trim(model);
        for (String prefix : WANXIANG_T2I_PREFIXES) if (id.startsWith(prefix)) return true;
        return false;
    }

    /** 去掉尾部斜杠并拼接后缀；base 为空时回落到方舟地址。 */
    public static String joinUrl(String baseUrl, String suffix) {
        String base = trim(baseUrl);
        if (base.isEmpty()) base = ARK_BASE;
        return stripTrailingSlash(base) + suffix;
    }

    public static String stripTrailingSlash(String value) {
        String out = trim(value);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
