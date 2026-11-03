package com.lumenforge.service.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 分镜 / 剧本模型的配置存储。
 *
 * <p>Python 版把这份配置放在单例表 {@code store.story_model} 里，但 Java 版的
 * {@code V1__initial_schema.sql} 里没有 settings 一类的小表（Flyway 校验和也不能动），
 * 所以沿用 {@code storage-paths.json} 的做法：落在数据根目录下的
 * {@code story-model.json}，API Key 用 {@link CredentialCipher} 加密后存
 * {@code apiKeyCiphertext}，下发时只给掩码。
 *
 * <p>配置分两层，与 Python 版一致：
 * {@code engine} 决定「谁来拆分镜」（api / doubao），{@code scriptEngine} 决定
 * 「谁先写一版剧本」（空 / codex）。
 */
@Component
public class StoryModelStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoryModelStore.class);

    /** 状态文件名，与 {@code storage-paths.json} 放在同一个数据根目录下。 */
    private static final String STATE_FILE = "story-model.json";

    private static final Pattern BASE_URL_RE = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_SLASH_RE = Pattern.compile("/+$");

    /** 一次最多出多少个镜头：再多整条链路（串行）跑太久，也容易超模型上下文。 */
    public static final int MAX_SHOTS = 12;
    /** 最少几个镜头。 */
    public static final int MIN_SHOTS = 3;
    /** 最多几个角色：定妆照也是一张一张串行出的。 */
    public static final int MAX_CHARACTERS = 4;

    /** 内置画风候选里的第一项，也是没指定画风时的默认值。 */
    public static final String DEFAULT_STYLE = "真人 cos 风格，写实摄影质感，自然光，皮肤质感真实，浅景深";

    private final StoragePaths paths;
    private final CredentialCipher cipher;
    private final ObjectMapper json;

    public StoryModelStore(StoragePaths paths, CredentialCipher cipher, ObjectMapper json) {
        this.paths = paths;
        this.cipher = cipher;
        this.json = json;
    }

    // ============================================================== 对外接口

    /** 下发到界面的配置快照：API Key 只给掩码。 */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> own = readState();
        String ciphertext = text(own, "apiKeyCiphertext");
        String apiKey = decrypt(ciphertext);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("baseUrl", text(own, "baseUrl"));
        out.put("model", text(own, "model"));
        out.put("apiKey", maskKey(apiKey));
        // Python 的 hasKey 判的是「有没有明文 Key」，不是「有没有存过密文」
        out.put("hasKey", !apiKey.isEmpty());
        out.put("engine", "doubao".equals(text(own, "engine")) ? "doubao" : "api");
        out.put("scriptEngine", "codex".equals(text(own, "scriptEngine")) ? "codex" : "");
        out.put("styles", styles());
        out.put("defaultStyle", DEFAULT_STYLE);
        out.put("maxShots", MAX_SHOTS);
        out.put("minShots", MIN_SHOTS);
        out.put("maxCharacters", MAX_CHARACTERS);
        return out;
    }

    /**
     * 保存配置；API Key 传空 = 保持原样（界面回显的是掩码，没重填就不该冲掉真 Key）。
     */
    public synchronized Map<String, Object> save(Map<String, Object> patch) {
        Map<String, Object> body = patch == null ? new LinkedHashMap<String, Object>() : patch;
        Map<String, Object> cur = readState();
        Map<String, Object> next = new LinkedHashMap<String, Object>();
        next.put("baseUrl", text(cur, "baseUrl"));
        next.put("model", text(cur, "model"));
        next.put("apiKeyCiphertext", text(cur, "apiKeyCiphertext"));
        next.put("engine", "doubao".equals(text(cur, "engine")) ? "doubao" : "api");
        next.put("scriptEngine", "codex".equals(text(cur, "scriptEngine")) ? "codex" : "");

        if (body.containsKey("baseUrl")) {
            String baseUrl = asText(body.get("baseUrl")).trim();
            if (!baseUrl.isEmpty() && !BASE_URL_RE.matcher(baseUrl).matches()) {
                throw new IllegalArgumentException("接口地址要以 http:// 或 https:// 开头");
            }
            next.put("baseUrl", TRAILING_SLASH_RE.matcher(baseUrl).replaceAll(""));
        }
        if (body.containsKey("model")) {
            next.put("model", asText(body.get("model")).trim());
        }
        Object apiKey = body.get("apiKey");
        if (apiKey instanceof String && ((String) apiKey).trim().length() > 0) {
            next.put("apiKeyCiphertext", cipher.encrypt(((String) apiKey).trim()));
        }
        if (body.containsKey("engine")) {
            next.put("engine", "doubao".equals(asText(body.get("engine")).trim()) ? "doubao" : "api");
        }
        if (body.containsKey("scriptEngine")) {
            next.put("scriptEngine", "codex".equals(asText(body.get("scriptEngine")).trim()) ? "codex" : "");
        }
        writeState(next);
        return snapshot();
    }

    /** 写剧本用的模型配置（明文 Key，只在服务端内部流转）。 */
    public synchronized Map<String, Object> scriptModelConfig() {
        Map<String, Object> own = readState();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("baseUrl", TRAILING_SLASH_RE.matcher(text(own, "baseUrl").trim()).replaceAll(""));
        out.put("apiKey", decrypt(text(own, "apiKeyCiphertext")).trim());
        out.put("model", text(own, "model").trim());
        return out;
    }

    /** 剧本引擎：空 = 不额外写剧本（用户给什么就拆什么）；codex = 本地 GPT 先写一版剧本。 */
    public synchronized String scriptEngine() {
        String raw = text(readState(), "scriptEngine").trim();
        return "codex".equalsIgnoreCase(raw) ? "codex" : "";
    }

    // ============================================================== 内置画风

    /** 内置画风候选；界面上也可以自己填。 */
    public static List<Map<String, Object>> styles() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(3);
        out.add(style("cos", "真人 cos（写实摄影）",
                "真人 cos 风格，写实摄影质感，自然光，皮肤质感真实，浅景深"));
        out.add(style("cos-studio", "真人 cos（棚拍）",
                "真人 cos 棚拍风格，影棚布光，干净背景，高清商业摄影质感"));
        out.add(style("cos-film", "真人 cos（电影感）",
                "真人 cos 电影感风格，胶片颗粒，戏剧性布光，电影级构图"));
        return out;
    }

    private static Map<String, Object> style(String id, String label, String prompt) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("label", label);
        out.put("prompt", prompt);
        return out;
    }

    // ============================================================== 落盘

    private Path stateFile() {
        return paths.root().resolve(STATE_FILE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readState() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                return new LinkedHashMap<String, Object>();
            }
            Object parsed = json.readValue(text, Object.class);
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        } catch (Exception err) {
            LOGGER.warn("分镜配置读取失败：{}", err.getMessage());
        }
        return new LinkedHashMap<String, Object>();
    }

    private void writeState(Map<String, Object> state) {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, json.writeValueAsString(state).getBytes(StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new IllegalStateException("无法保存分镜配置：" + err.getMessage(), err);
        }
    }

    // ============================================================== 工具

    private String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return "";
        }
        try {
            return cipher.decrypt(ciphertext);
        } catch (RuntimeException err) {
            LOGGER.warn("分镜配置的 API Key 解密失败：{}", err.getMessage());
            return "";
        }
    }

    /**
     * 掩码口径照抄 Python 的 {@code mask_key}：长度不超过 8 一律 {@code ****}，
     * 否则首尾各留 4 位（图片模块用的是圆点，两套各有出处，别混）。
     */
    private static String maskKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
