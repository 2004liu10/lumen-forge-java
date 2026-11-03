package com.lumenforge.service.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.web.WebAccount;
import com.lumenforge.entity.web.WebProvider;
import com.lumenforge.mapper.web.WebAccountMapper;
import com.lumenforge.mapper.web.WebProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 网页平台与网页账号的业务实现。
 *
 * <p>照搬 Python 版 {@code app/web_providers.py} + {@code app/web_accounts.py}：
 * 平台配置是一份白名单化的 JSON（{@code config_json} 承载页面地址、hosts、选择器候选数组、
 * 额度规则），账号是「cookie 型登录态 + 每日额度账本」，按自然日 0 点滚动。
 *
 * <p>有两处与 Python 版的结构差异，都是因为 DDL 是权威：
 * <ol>
 *   <li>账号的 {@code stats}（total / success / failed 三个累计计数）在 DDL 里没有对应列，
 *       因此不持久化，快照里统一回 0 —— Python 版拿它只做「挑号时用得少的优先」这一个权重，
 *       少这一项不会改变调度结果的方向。</li>
 *   <li>{@code quotaDay} 在 DDL 里是 {@code DATE} 而不是字符串，读写时按
 *       {@code yyyy-MM-dd} 转换，对外仍回字符串（与 Python 版一致）。</li>
 * </ol>
 */
@Service
public class WebServiceImpl implements WebService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebServiceImpl.class);

    /** 平台标识的合法形状：1~32 位，字母数字开头，其余可含短横线与下划线。 */
    private static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9\\-_]{0,31}$", Pattern.CASE_INSENSITIVE);

    /** 平台配置里 hosts 最多存这么多条。 */
    private static final int MAX_HOSTS = 20;

    /** 选择器候选数组每格最多存这么多条。 */
    private static final int MAX_SELECTORS = 10;

    /** 账号备注最长 1000 字（与 DDL 的 VARCHAR(1000) 对齐）。 */
    private static final int MAX_REMARK = 1000;

    /** 失败原因最长 200 字（Python 版 {@code str(error)[:200]}）。 */
    private static final int MAX_ERROR = 200;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper;
    private final WebProviderMapper providerMapper;
    private final WebAccountMapper accountMapper;

    public WebServiceImpl(ObjectMapper objectMapper,
                          WebProviderMapper providerMapper,
                          WebAccountMapper accountMapper) {
        this.objectMapper = objectMapper;
        this.providerMapper = providerMapper;
        this.accountMapper = accountMapper;
    }

    /**
     * 首次启动把 8 家内置平台灌进库。Python 版在 {@code main.py} 启动时调
     * {@code ensure_providers()}，这里用 {@code @PostConstruct} 等价实现；
     * 库里已经有内容就完全不动 —— 用户改过的那份以库为准。
     */
    @PostConstruct
    public void ensureProviders() {
        try {
            if (providerMapper.count() > 0) {
                return;
            }
            List<Map<String, Object>> builtin = builtinCopy();
            for (Map<String, Object> payload : builtin) {
                providerMapper.save(toEntity(null, payload));
            }
            LOGGER.info("网页平台表为空，已灌入 {} 家内置平台", builtin.size());
        } catch (RuntimeException err) {
            // 数据库还没迁移好 / 只读环境下不应该让整个应用起不来，记一笔即可。
            LOGGER.warn("初始化内置网页平台失败：{}", err.getMessage());
        }
    }

    // ============================================================ 平台

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listProviders() {
        List<Map<String, Object>> providers = new ArrayList<Map<String, Object>>();
        for (WebProvider provider : providerMapper.findAllByOrderByCreatedAtAsc()) {
            providers.add(withPartition(toProviderMap(provider)));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("providers", providers);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> upsertProvider(Map<String, Object> body) {
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;
        String wantedId = Texts.trim(raw.get("id"));
        WebProvider base = wantedId.isEmpty() ? null : providerMapper.findById(wantedId).orElse(null);

        Map<String, Object> payload = sanitize(raw, base == null ? null : toProviderMap(base));

        String slug = Texts.trim(payload.get("slug"));
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("请填写平台标识（slug）");
        }
        if (!SLUG_RE.matcher(slug).matches()) {
            throw new IllegalArgumentException("平台标识只能用字母、数字、短横线和下划线（如 doubao-2）");
        }
        if (Texts.trim(payload.get("name")).isEmpty()) {
            throw new IllegalArgumentException("请填写平台名称");
        }

        if (base != null) {
            // 改 slug 时不能和别人撞
            List<WebProvider> others = providerMapper.findAll();
            for (WebProvider other : others) {
                if (!other.getId().equals(base.getId()) && slug.equals(other.getSlug())) {
                    throw new IllegalArgumentException("已经有平台用了标识「" + slug + "」");
                }
            }
            applyPayload(base, payload);
            base.setUpdatedAt(LocalDateTime.now());
            providerMapper.save(base);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("provider", withPartition(toProviderMap(base)));
            result.put("created", Boolean.FALSE);
            return result;
        }

        for (WebProvider other : providerMapper.findAll()) {
            if (slug.equals(other.getSlug())) {
                throw new IllegalArgumentException("已经有平台用了标识「" + slug + "」");
            }
        }
        WebProvider created = toEntity(null, payload);
        providerMapper.save(created);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", withPartition(toProviderMap(created)));
        result.put("created", Boolean.TRUE);
        return result;
    }

    @Override
    @Transactional
    public boolean deleteProvider(String providerId) {
        String key = Texts.trim(providerId);
        if (key.isEmpty()) {
            return false;
        }
        WebProvider target = providerMapper.findById(key).orElse(null);
        if (target == null) {
            target = providerMapper.findBySlug(key).orElse(null);
        }
        if (target == null) {
            return false;
        }
        // web_accounts.provider_slug 上的外键是 ON DELETE CASCADE，账号会跟着走。
        providerMapper.delete(target);
        return true;
    }

    @Override
    @Transactional
    public Map<String, Object> resetProviders() {
        List<WebProvider> rows = providerMapper.findAllByOrderByCreatedAtAsc();
        List<String> have = new ArrayList<String>();
        for (WebProvider provider : rows) {
            have.add(provider.getSlug());
        }
        int added = 0;
        for (Map<String, Object> payload : builtinCopy()) {
            String slug = Texts.trim(payload.get("slug"));
            if (have.contains(slug)) {
                continue;
            }
            providerMapper.save(toEntity(null, payload));
            have.add(slug);
            added++;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("added", added);
        result.put("providers", listProviders().get("providers"));
        return result;
    }

    // ============================================================ 账号

    @Override
    @Transactional
    public Map<String, Object> listAccounts() {
        List<Map<String, Object>> accounts = new ArrayList<Map<String, Object>>();
        for (WebAccount account : accountMapper.findAllByOrderByCreatedAtAsc()) {
            accounts.add(snapshot(account));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accounts", accounts);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> createAccount(Map<String, Object> body) {
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;
        String slug = Texts.trim(raw.get("provider"));
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("请先选一个平台");
        }
        if (findProvider(slug) == null) {
            throw new IllegalArgumentException("没有这个平台：" + slug);
        }
        String name = Texts.trim(raw.get("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请给这个账号起个名字（比如「豆包-小号2」）");
        }
        WebAccount account = new WebAccount(slug, name);
        accountMapper.save(account);
        return snapshot(account);
    }

    @Override
    @Transactional
    public Map<String, Object> patchAccount(String accountId, Map<String, Object> body) {
        WebAccount account = requireAccount(accountId);
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;

        if (raw.get("name") != null && !Texts.trim(raw.get("name")).isEmpty()) {
            account.setName(Texts.trim(raw.get("name")));
        }
        if (Texts.has(raw, "enabled")) {
            account.setEnabled(pythonBool(raw.get("enabled")));
        }
        if (Texts.has(raw, "remark")) {
            account.setRemark(Texts.truncate(Texts.trim(raw.get("remark")), MAX_REMARK));
        }
        for (String key : Arrays.asList("loginAt", "loginExpiresAt")) {
            if (!Texts.has(raw, key)) {
                continue;
            }
            long value = asIntLoose(raw.get(key), 0);
            if ("loginAt".equals(key)) {
                account.setLoginAt(value);
            } else {
                account.setLoginExpiresAt(value);
            }
        }
        if (Texts.has(raw, "quotaUsed")) {
            int used = (int) asIntLoose(raw.get("quotaUsed"), 0);
            account.setQuotaUsed(Math.max(0, used));
        }
        if (Texts.has(raw, "quotaDay")) {
            account.setQuotaDay(parseDay(Texts.trim(raw.get("quotaDay"))));
        }
        if (Texts.has(raw, "lastError")) {
            account.setLastError(Texts.truncate(Texts.trim(raw.get("lastError")), MAX_ERROR));
        }
        accountMapper.save(account);
        return snapshot(account);
    }

    @Override
    @Transactional
    public void deleteAccount(String accountId) {
        WebAccount account = requireAccount(accountId);
        accountMapper.delete(account);
    }

    @Override
    @Transactional
    public Map<String, Object> pickAccount(Map<String, Object> body) {
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;
        String slug = Texts.trim(raw.get("provider"));
        Map<String, Object> quota = quotaOf(slug);

        List<WebAccount> all = accountMapper.findAllByOrderByCreatedAtAsc();
        WebAccount picked = pick(all, slug, quota);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (picked != null) {
            accountMapper.save(picked);
            result.put("account", snapshot(picked));
            result.put("reason", "");
            return result;
        }
        result.put("account", null);
        result.put("reason", pickReason(all, slug, quota));
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> commitUsage(String accountId, Map<String, Object> body) {
        WebAccount account = requireAccount(accountId);
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;
        Map<String, Object> quota = quotaOf(account.getProviderSlug());

        long times = asIntLoose(raw.get("times"), 1);
        if (times == 0L) {
            times = 1L;
        }
        rollover(account);
        int cost = costOf(quota);
        account.setQuotaUsed(account.getQuotaUsed() + (int) (times * cost));
        account.setLastError("");
        accountMapper.save(account);
        return snapshot(account);
    }

    @Override
    @Transactional
    public Map<String, Object> failUsage(String accountId, Map<String, Object> body) {
        WebAccount account = requireAccount(accountId);
        Map<String, Object> raw = body == null ? new LinkedHashMap<String, Object>() : body;
        // 失败不扣额度：失败不是用户用掉的。
        account.setLastError(Texts.truncate(pythonStr(raw.get("error")), MAX_ERROR));
        accountMapper.save(account);
        return snapshot(account);
    }

    // ============================================================ 内置平台清单

    /** 8 家内置平台的原始声明，字段与 Python 版 {@code BUILTIN_PROVIDERS} 逐项一致。 */
    private List<Map<String, Object>> builtinProviders() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();

        Map<String, Object> doubao = provider("doubao", "豆包", "https://www.doubao.com/chat/",
                Arrays.asList("doubao.com"), "https://www.doubao.com/chat/",
                "网页版豆包。登录后可绑多个账号，每天各有免费生成额度");
        adapter(doubao,
                Arrays.asList("div[contenteditable=\"true\"]", "textarea[placeholder]", "textarea",
                        "div[role=\"textbox\"]"),
                Arrays.asList("button[type=\"submit\"]", "button[aria-label*=\"发送\"]", "button"),
                "发送|生成|submit|send",
                Arrays.asList("img[src*=\"doubao\"]", "video[src*=\"doubao\"]", "img[src^=\"http\"]"));
        quota(doubao, "次", 0, 1, "免费额度以豆包页面实际显示为准，可在平台管理里调整");
        list.add(doubao);

        Map<String, Object> qwen = provider("qwen", "千问 · 通义万相", "https://tongyi.aliyun.com/wanxiang/",
                Arrays.asList("tongyi.aliyun.com", "qianwen.com"), "https://tongyi.aliyun.com/wanxiang/",
                "阿里通义万相。网页自带风控签名，不用逆向，靠页面自己算");
        adapter(qwen,
                Arrays.asList("textarea", "div[contenteditable=\"true\"]", "input[type=\"text\"]"),
                Arrays.asList("button[type=\"submit\"]", "button"),
                "生成|立即生成|generate",
                Arrays.asList("img[src*=\"aliyun\"]", "video[src*=\"aliyun\"]", "img[src^=\"http\"]"));
        quota(qwen, "次", 0, 1, "");
        list.add(qwen);

        Map<String, Object> yuanbao = provider("yuanbao", "元宝 · 混元", "https://yuanbao.tencent.com/chat",
                Arrays.asList("yuanbao.tencent.com"), "https://yuanbao.tencent.com/chat",
                "腾讯元宝。固定次数免费额度");
        adapter(yuanbao,
                Arrays.asList("div[contenteditable=\"true\"]", "textarea", "input[type=\"text\"]"),
                Arrays.asList("button[type=\"submit\"]", "button"),
                "发送|生成|submit|send",
                Arrays.asList("img[src*=\"yuanbao\"]", "video[src*=\"yuanbao\"]", "img[src^=\"http\"]"));
        quota(yuanbao, "次", 0, 1, "");
        list.add(yuanbao);

        Map<String, Object> chatglm = provider("chatglm", "智谱清言",
                "https://chatglm.cn/main/alltoolsdetail", Arrays.asList("chatglm.cn"),
                "https://chatglm.cn/", "智谱清言网页版");
        adapter(chatglm,
                Arrays.asList("textarea", "div[contenteditable=\"true\"]"),
                Arrays.asList("button[type=\"submit\"]", "button"),
                "生成|发送|submit|send",
                Arrays.asList("img[src*=\"chatglm\"]", "img[src^=\"http\"]"));
        quota(chatglm, "次", 0, 1, "");
        list.add(chatglm);

        Map<String, Object> ideogram = provider("ideogram", "Ideogram", "https://ideogram.ai/",
                Arrays.asList("ideogram.ai"), null, "英文界面，可用模块里的「整页翻译」");
        adapter(ideogram,
                Arrays.asList("textarea", "input[type=\"text\"]", "div[contenteditable=\"true\"]"),
                Arrays.asList("button[type=\"submit\"]", "button"),
                "generate|生成",
                Arrays.asList("img[src*=\"ideogram\"]", "img[src*=\"cloudfront\"]", "img[src^=\"http\"]"));
        quota(ideogram, "次", 0, 1, "");
        list.add(ideogram);

        Map<String, Object> leonardo = provider("leonardo", "Leonardo", "https://leonardo.ai/",
                Arrays.asList("leonardo.ai"), null, "英文界面，可用模块里的「整页翻译」");
        adapter(leonardo,
                Arrays.asList("textarea[placeholder]", "textarea", "div[contenteditable=\"true\"]"),
                Arrays.asList("button[type=\"submit\"]", "form button", "button"),
                "generate|generate image|生成|create",
                Arrays.asList("img[src*=\"cdn.leonardo\"]", "img[src*=\"leonardo\"]", "img[src^=\"http\"]"));
        quota(leonardo, "次", 0, 1, "");
        list.add(leonardo);

        Map<String, Object> copilot = provider("copilot", "Bing 图像 · Copilot",
                "https://copilot.microsoft.com/",
                Arrays.asList("copilot.microsoft.com", "bing.com", "bingusercontent.com", "microsoft.com"),
                null, "");
        adapter(copilot,
                Arrays.asList("textarea#userInput", "textarea", "div[contenteditable=\"true\"]"),
                Arrays.asList("button[aria-label*=\"Submit\"]", "button[title*=\"Send\"]",
                        "button[type=\"submit\"]", "button"),
                "send|发送|提交|生成",
                Arrays.asList("img[src*=\"bingusercontent\"]", "img[src*=\"th.bing\"]",
                        "img[src*=\"copilot\"]", "img[src^=\"http\"]"));
        quota(copilot, "次", 0, 1, "");
        list.add(copilot);

        Map<String, Object> shenlang = provider("shenlang-canvas", "沈浪超级Ai画布（独家版）",
                "https://www.yuque.com/vip99180/rg39r3/anfv8pm0ghwzq87k",
                Arrays.asList("yuque.com", "www.yuque.com"), null,
                "官方页：打开后下载「沈浪超级Ai画布 Setup」Windows 版；这是独立应用，不是在线生图平台");
        adapter(shenlang, Collections.<String>emptyList(), Collections.<String>emptyList(), "",
                Collections.<String>emptyList());
        quota(shenlang, "次", 0, 1, "");
        list.add(shenlang);

        return list;
    }

    /** 内置清单的一份「洗过」的拷贝（seed 用，避免把常量改脏）。 */
    private List<Map<String, Object>> builtinCopy() {
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> raw : builtinProviders()) {
            copy.add(sanitize(raw, raw));
        }
        return copy;
    }

    private Map<String, Object> provider(String slug, String name, String url, List<String> hosts,
                                         String loginUrl, String hint) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("slug", slug);
        out.put("name", name);
        out.put("url", url);
        out.put("hosts", new ArrayList<Object>(hosts));
        if (loginUrl != null) {
            out.put("loginUrl", loginUrl);
        }
        out.put("hint", hint);
        return out;
    }

    private void adapter(Map<String, Object> target, List<String> input, List<String> submit,
                         String submitText, List<String> result) {
        Map<String, Object> adapter = new LinkedHashMap<String, Object>();
        adapter.put("input", new ArrayList<Object>(input));
        adapter.put("submit", new ArrayList<Object>(submit));
        adapter.put("submitText", submitText);
        adapter.put("result", new ArrayList<Object>(result));
        target.put("adapter", adapter);
    }

    private void quota(Map<String, Object> target, String unit, int daily, int cost, String note) {
        Map<String, Object> quota = new LinkedHashMap<String, Object>();
        quota.put("unit", unit);
        quota.put("daily", daily);
        quota.put("cost", cost);
        quota.put("note", note);
        target.put("quota", quota);
    }

    // ============================================================ 平台配置的清洗与读写

    /**
     * 白名单化：前端传什么都只认这些字段，避免把乱七八糟的东西写进库。
     * 逐行对照 Python 版 {@code web_providers.sanitize()}。
     */
    private Map<String, Object> sanitize(Map<String, Object> raw, Map<String, Object> base) {
        Map<String, Object> input = raw == null ? new LinkedHashMap<String, Object>() : raw;
        Map<String, Object> out = base == null
                ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(base);

        out.put("slug", firstTruthy(input.get("slug"), out.get("slug")).trim());
        out.put("name", firstTruthy(input.get("name"), out.get("name")).trim());
        out.put("url", firstTruthy(input.get("url"), out.get("url")).trim());
        out.put("loginUrl", firstTruthy(input.get("loginUrl"), out.get("loginUrl")).trim());
        // hint 不 strip：它是一句给人看的话，首尾空白无害
        out.put("hint", firstTruthy(input.get("hint"), out.get("hint")));

        if (input.get("enabled") != null) {
            out.put("enabled", pythonBool(input.get("enabled")));
        } else {
            Object previous = out.get("enabled");
            out.put("enabled", !Boolean.FALSE.equals(previous));
        }

        Object rawHosts = input.get("hosts");
        if (rawHosts instanceof List) {
            out.put("hosts", cleanList(rawHosts, MAX_HOSTS));
        } else {
            out.put("hosts", copyList(out.get("hosts")));
        }

        Map<String, Object> adapter = asMap(input.get("adapter"));
        Map<String, Object> previousAdapter = asMap(out.get("adapter"));
        String submitText = stringOr(containsKey(adapter, "submitText")
                ? adapter.get("submitText") : previousAdapter.get("submitText")).trim();
        // 正则源码：用户可能手滑写个不合法的，存之前先试编译一次，不合法就当没有
        if (!submitText.isEmpty() && !compiles(submitText)) {
            submitText = "";
        }
        Map<String, Object> nextAdapter = new LinkedHashMap<String, Object>();
        nextAdapter.put("input", cleanList(adapter.get("input"), MAX_SELECTORS));
        nextAdapter.put("submit", cleanList(adapter.get("submit"), MAX_SELECTORS));
        nextAdapter.put("submitText", submitText);
        nextAdapter.put("result", cleanList(adapter.get("result"), MAX_SELECTORS));
        out.put("adapter", nextAdapter);

        Map<String, Object> quota = asMap(input.get("quota"));
        Map<String, Object> previousQuota = asMap(out.get("quota"));
        Object dailyRaw = containsKey(quota, "daily") ? quota.get("daily") : previousQuota.get("daily");
        Object costRaw = containsKey(quota, "cost") ? quota.get("cost") : previousQuota.get("cost");
        int daily;
        try {
            daily = Math.max(0, (int) number(dailyRaw == null ? 0D : dailyRaw));
        } catch (NumberFormatException ignored) {
            daily = 0;
        }
        int cost;
        try {
            cost = Math.max(1, (int) number(costRaw == null ? 1D : costRaw));
        } catch (NumberFormatException ignored) {
            cost = 1;
        }
        Map<String, Object> nextQuota = new LinkedHashMap<String, Object>();
        nextQuota.put("unit", firstTruthy(quota.get("unit"),
                firstTruthy(previousQuota.get("unit"), "次")));
        nextQuota.put("daily", daily);
        nextQuota.put("cost", cost);
        nextQuota.put("note", stringOr(containsKey(quota, "note")
                ? quota.get("note") : previousQuota.get("note")));
        out.put("quota", nextQuota);
        return out;
    }

    /** 把洗过的 payload 落到实体上（slug / name / enabled / quota_per_day + 整份 config_json）。 */
    private void applyPayload(WebProvider entity, Map<String, Object> payload) {
        entity.setSlug(Texts.trim(payload.get("slug")));
        entity.setName(Texts.trim(payload.get("name")));
        entity.setEnabled(!Boolean.FALSE.equals(payload.get("enabled")));
        entity.setQuotaPerDay(dailyOf(asMap(payload.get("quota"))));
        entity.setConfigJson(writeConfig(payload));
    }

    private WebProvider toEntity(WebProvider previous, Map<String, Object> payload) {
        String slug = Texts.trim(payload.get("slug"));
        String name = Texts.trim(payload.get("name"));
        boolean enabled = !Boolean.FALSE.equals(payload.get("enabled"));
        Integer quotaPerDay = dailyOf(asMap(payload.get("quota")));
        String configJson = writeConfig(payload);
        if (previous == null) {
            return new WebProvider(slug, name, enabled, quotaPerDay, configJson);
        }
        previous.setSlug(slug);
        previous.setName(name);
        previous.setEnabled(enabled);
        previous.setQuotaPerDay(quotaPerDay);
        previous.setConfigJson(configJson);
        return previous;
    }

    /**
     * 序列化平台配置。{@code id} 是列上的主键，不该重复写进 {@code config_json}
     * （sanitize 的 base 里带了它，这里剔除）。
     */
    private String writeConfig(Map<String, Object> payload) {
        Map<String, Object> clean = new LinkedHashMap<String, Object>(payload);
        clean.remove("id");
        return writeJson(clean);
    }

    /** 实体 → 与 Python 版同形的平台 dict（含 id，不含 created_at / updated_at）。 */
    private Map<String, Object> toProviderMap(WebProvider entity) {
        Map<String, Object> config = readConfig(entity.getConfigJson());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("slug", entity.getSlug());
        out.put("name", entity.getName());
        out.put("url", Texts.trim(config.get("url")));
        out.put("hosts", copyList(config.get("hosts")));
        out.put("loginUrl", Texts.trim(config.get("loginUrl")));
        out.put("hint", config.get("hint") == null ? "" : String.valueOf(config.get("hint")));
        out.put("enabled", entity.isEnabled());
        out.put("adapter", adapterMap(config.get("adapter")));
        out.put("quota", quotaMap(config.get("quota"), entity.getQuotaPerDay()));
        out.put("id", entity.getId());
        return out;
    }

    private Map<String, Object> adapterMap(Object raw) {
        Map<String, Object> source = asMap(raw);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("input", copyList(source.get("input")));
        out.put("submit", copyList(source.get("submit")));
        out.put("submitText", source.get("submitText") == null ? "" : String.valueOf(source.get("submitText")));
        out.put("result", copyList(source.get("result")));
        return out;
    }

    private Map<String, Object> quotaMap(Object raw, Integer quotaPerDay) {
        Map<String, Object> source = asMap(raw);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String unit = source.get("unit") == null ? "次" : String.valueOf(source.get("unit"));
        out.put("unit", unit.isEmpty() ? "次" : unit);
        Integer daily = dailyOf(source);
        // 两处都存了每日额度：列上没填就以内嵌的 quota.daily 为准，保持一致
        out.put("daily", daily == null ? (quotaPerDay == null ? 0 : quotaPerDay) : daily);
        Object cost = source.get("cost");
        int costValue = cost == null ? 1 : Math.max(1, (int) number(cost));
        out.put("cost", costValue);
        out.put("note", source.get("note") == null ? "" : String.valueOf(source.get("note")));
        return out;
    }

    private Integer dailyOf(Map<String, Object> quota) {
        Object raw = quota == null ? null : quota.get("daily");
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, (int) number(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Map<String, Object> withPartition(Map<String, Object> provider) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(provider);
        out.put("partition", partitionOf(Texts.trim(provider.get("slug"))));
        return out;
    }

    /** 分区名：一个平台（或平台下的一个账号）一个，登录态互不干扰。 */
    private String partitionOf(String slug) {
        return "persist:gen-" + (slug == null ? "" : slug);
    }

    private WebProvider findProvider(String slug) {
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        Optional<WebProvider> found = providerMapper.findBySlug(slug);
        return found.orElse(null);
    }

    /** 这家平台配了什么额度规则；没配就是 null（等于不限额）。 */
    private Map<String, Object> quotaOf(String slug) {
        WebProvider provider = findProvider(slug);
        if (provider == null) {
            return null;
        }
        Map<String, Object> quota = asMap(readConfig(provider.getConfigJson()).get("quota"));
        return quota.isEmpty() ? null : quota;
    }

    // ============================================================ 账号额度

    private WebAccount requireAccount(String accountId) {
        String key = Texts.trim(accountId);
        WebAccount account = key.isEmpty() ? null : accountMapper.findById(key).orElse(null);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        return account;
    }

    /** 把账本滚到今天：跨天了就把已用清零。 */
    private void rollover(WebAccount account) {
        LocalDate today = LocalDate.now();
        if (account.getQuotaDay() == null || !today.equals(account.getQuotaDay())) {
            account.setQuotaDay(today);
            account.setQuotaUsed(0);
        }
    }

    /** 这家平台的每日额度上限（0 / 未配 = 不限）。 */
    private int dailyLimit(Map<String, Object> quota) {
        Object raw = quota == null ? null : quota.get("daily");
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(0, (int) number(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 单次消耗。 */
    private int costOf(Map<String, Object> quota) {
        Object raw = quota == null ? null : quota.get("cost");
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, (int) number(raw));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** 账号今天还剩几次；{@code null} = 不限。 */
    private Integer quotaLeft(WebAccount account, Map<String, Object> quota) {
        int limit = dailyLimit(quota);
        if (limit == 0) {
            return null;
        }
        rollover(account);
        return Math.max(0, limit - account.getQuotaUsed());
    }

    /** 这个账号现在能不能用：启用 + 今天还有额度（额度不限的永远算有）。 */
    private boolean isUsable(WebAccount account, Map<String, Object> quota) {
        if (account == null || !account.isEnabled()) {
            return false;
        }
        Integer left = quotaLeft(account, quota);
        return left == null || left > 0;
    }

    /** 库里记的登录态还算数吗：从没登过、或已过期的都不算。 */
    private boolean loginFresh(WebAccount account) {
        Long loginAt = account.getLoginAt();
        if (loginAt == null || loginAt == 0L) {
            return false;
        }
        long expires = account.getLoginExpiresAt() == null ? 0L : account.getLoginExpiresAt();
        if (expires != 0L && expires <= System.currentTimeMillis()) {
            return false;
        }
        return true;
    }

    /**
     * 挑一个账号：谁今天剩得多就先用谁，同额度时用得最少的优先；
     * 登录态有效的优先，只有实在没别人了才用过期号。
     */
    private WebAccount pick(List<WebAccount> accounts, String provider, Map<String, Object> quota) {
        List<WebAccount> usable = new ArrayList<WebAccount>();
        for (WebAccount account : accounts) {
            if (account.getProviderSlug() != null && account.getProviderSlug().equals(provider)
                    && isUsable(account, quota)) {
                usable.add(account);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        List<WebAccount> fresh = new ArrayList<WebAccount>();
        for (WebAccount account : usable) {
            if (loginFresh(account)) {
                fresh.add(account);
            }
        }
        final Map<String, Object> rule = quota;
        List<WebAccount> pool = fresh.isEmpty() ? usable : fresh;
        Collections.sort(pool, new Comparator<WebAccount>() {
            @Override
            public int compare(WebAccount left, WebAccount right) {
                Integer leftCount = quotaLeft(left, rule);
                Integer rightCount = quotaLeft(right, rule);
                // 不限额度（null）的排后面：优先消耗有明确上限、会每天刷新的号
                int cmp = Boolean.compare(leftCount == null, rightCount == null);
                if (cmp != 0) {
                    return cmp;
                }
                int a = leftCount == null ? 0 : leftCount;
                int b = rightCount == null ? 0 : rightCount;
                return b < a ? -1 : (b > a ? 1 : 0);
            }
        });
        return pool.get(0);
    }

    /** 挑不到账号时给用户一句能动手改的话。 */
    private String pickReason(List<WebAccount> accounts, String provider, Map<String, Object> quota) {
        List<WebAccount> pool = new ArrayList<WebAccount>();
        for (WebAccount account : accounts) {
            if (account.getProviderSlug() != null && account.getProviderSlug().equals(provider)) {
                pool.add(account);
            }
        }
        if (pool.isEmpty()) {
            return "这个平台还没绑账号";
        }
        boolean anyEnabled = false;
        for (WebAccount account : pool) {
            if (account.isEnabled()) {
                anyEnabled = true;
                break;
            }
        }
        if (!anyEnabled) {
            return "这个平台的账号都被停用了";
        }
        // 剩下「启用了、但额度用光」的账号：额度是有限值才算用光
        int remaining = 0;
        boolean limited = false;
        for (WebAccount account : pool) {
            if (!account.isEnabled()) {
                continue;
            }
            Integer left = quotaLeft(account, quota);
            if (left == null) {
                continue;
            }
            limited = true;
            remaining += left;
        }
        if (limited && remaining > 0) {
            return "账号都不可用（登录态可能过期了）";
        }
        return "今天所有账号的额度都用完了，明早 0 点会重置";
    }

    /** 给前端的账号快照：补上算出来的剩余额度、刷新时刻、登录态是否可疑。 */
    private Map<String, Object> snapshot(WebAccount account) {
        Map<String, Object> quota = quotaOf(account.getProviderSlug());
        rollover(account);
        Integer left = quotaLeft(account, quota);
        int limit = dailyLimit(quota);
        long expires = account.getLoginExpiresAt() == null ? 0L : account.getLoginExpiresAt();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", account.getId());
        out.put("provider", account.getProviderSlug() == null ? "" : account.getProviderSlug());
        out.put("name", account.getName() == null ? "" : account.getName());
        out.put("enabled", account.isEnabled());
        if (account.getRemark() != null) {
            out.put("remark", account.getRemark());
        }
        out.put("loginAt", account.getLoginAt() == null ? 0L : account.getLoginAt());
        out.put("loginExpiresAt", expires);
        out.put("quotaDay", account.getQuotaDay() == null ? "" : account.getQuotaDay().format(DAY_FORMAT));
        out.put("quotaUsed", account.getQuotaUsed());
        // stats 没有对应的 DDL 列，不持久化，按 0 兜底
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("total", 0);
        stats.put("success", 0);
        stats.put("failed", 0);
        out.put("stats", stats);
        out.put("lastError", account.getLastError() == null ? "" : account.getLastError());
        out.put("createdAt", millis(account.getCreatedAt()));
        out.put("quotaLimit", limit == 0 ? null : (Object) limit);
        out.put("quotaLeft", left);
        out.put("quotaLeftUsed", account.getQuotaUsed());
        out.put("quotaResetAt", (limit != 0 && left != null && left <= 0) ? nextResetAt() : null);
        out.put("logged", Boolean.FALSE);
        out.put("loginStale", expires != 0L && expires <= System.currentTimeMillis());
        return out;
    }

    /** 本地时区的明天 0 点（毫秒）。 */
    private long nextResetAt() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        return tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private long millis(LocalDateTime moment) {
        return moment == null ? 0L : moment.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDate parseDay(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, DAY_FORMAT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ============================================================ 小工具

    /** Python 的 {@code bool()}：None / False / 0 / 空串 / 空容器为假，其余为真。 */
    private boolean pythonBool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0D;
        }
        if (value instanceof CharSequence) {
            return ((CharSequence) value).length() > 0;
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        return true;
    }

    /** Python 的 {@code str(value or "")}。 */
    private String pythonStr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Python 的 {@code _str(value or fallback, "")}：值假就用 fallback。 */
    private String firstTruthy(Object value, Object fallback) {
        if (pythonBool(value)) {
            return String.valueOf(value);
        }
        return pythonStr(fallback);
    }

    private String stringOr(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean containsKey(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key);
    }

    /** Python 的 {@code float()}：整数、浮点、数字字符串都认；NaN / Inf 视为不合法。 */
    private double number(Object value) {
        if (value == null) {
            return 0D;
        }
        double result;
        if (value instanceof Number) {
            result = ((Number) value).doubleValue();
        } else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return 0D;
            }
            result = Double.parseDouble(text);
        }
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new NumberFormatException("不是有限数字");
        }
        return result;
    }

    /**
     * Python 版 {@code as_int(value, fallback)} 的等价物：先按 {@code float()} 解析，
     * 解析不了就退回落值；数值本身是 0 时也退回落值（Python 写的是
     * {@code int(num) if num else fallback}，判断的是 num 而不是取整结果）。
     */
    private long asIntLoose(Object value, long fallback) {
        try {
            double num = number(value);
            return num == 0D ? fallback : (long) num;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean compiles(String regex) {
        try {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            return true;
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    /** 过滤空串、去空白、截断到 {@code limit} 条。 */
    private List<Object> cleanList(Object value, int limit) {
        List<Object> out = new ArrayList<Object>();
        if (!(value instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) value) {
            String text = Texts.trim(item);
            if (text.isEmpty()) {
                continue;
            }
            out.add(text);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<Object> copyList(Object value) {
        List<Object> out = new ArrayList<Object>();
        if (!(value instanceof List)) {
            return out;
        }
        for (Object item : (List<?>) value) {
            out.add(item == null ? "" : String.valueOf(item));
        }
        return out;
    }

    private Map<String, Object> asMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private Map<String, Object> readConfig(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = decodeJson(raw);
        // H2 的 JSON 列会把整段 JSON 文本当成「一个 JSON 字符串」存下来（外面多套一层引号），
        // MySQL 不会。这里兼容两层：解出来还是字符串就再解一次。
        if (parsed instanceof String) {
            parsed = decodeJson((String) parsed);
        }
        Map<String, Object> config = asMap(parsed);
        return config == null ? new LinkedHashMap<String, Object>() : config;
    }

    private Object decodeJson(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Object>() { });
        } catch (Exception ignored) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
