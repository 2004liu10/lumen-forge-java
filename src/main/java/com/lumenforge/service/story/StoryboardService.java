package com.lumenforge.service.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.common.util.Texts;
import com.lumenforge.entity.image.ImageModel;
import com.lumenforge.entity.image.ImageReference;
import com.lumenforge.entity.image.ImageTask;
import com.lumenforge.entity.storage.StoredFile;
import com.lumenforge.mapper.image.ImageModelMapper;
import com.lumenforge.mapper.image.ImageReferenceMapper;
import com.lumenforge.mapper.image.ImageTaskMapper;
import com.lumenforge.mapper.story.StoryboardMapper;
import com.lumenforge.entity.story.Storyboard;
import com.lumenforge.provider.ImageProtocols;
import com.lumenforge.service.image.ImageGenerationService;
import com.lumenforge.service.storage.FileStorageService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 分镜剧本：让模型编剧本（文字），再按剧本给角色出定妆照、给镜头批量出图。
 *
 * <p>存储上只有一张 {@code storyboards} 表（id / title / status / script_json / 两个时间戳），
 * 所以角色与镜头全部序列化进 {@code script_json}；图片与视频任务只在角色/镜头上记任务 id，
 * <b>不存结果快照</b> —— 任务会被重试、会被裁剪，存快照就会和真实结果对不上。
 */
@Service
public class StoryboardService {

    /** 写剧本这一轮的时间预算（含重试）。长分镜 prompt 上游经常要跑一两分钟。 */
    private static final int SCRIPT_TIMEOUT_MS = 240000;

    private static final Pattern TRAILING_PUNCT = Pattern.compile("[。，、；,;]+$");

    /** 参考图扩展名到 MIME：比图片模块多认 tif/tiff。 */
    private static final Map<String, String> REF_MIME_TYPES = new LinkedHashMap<String, String>();

    static {
        REF_MIME_TYPES.put(".png", "image/png");
        REF_MIME_TYPES.put(".jpg", "image/jpeg");
        REF_MIME_TYPES.put(".jpeg", "image/jpeg");
        REF_MIME_TYPES.put(".webp", "image/webp");
        REF_MIME_TYPES.put(".bmp", "image/bmp");
        REF_MIME_TYPES.put(".tif", "image/tiff");
        REF_MIME_TYPES.put(".tiff", "image/tiff");
        REF_MIME_TYPES.put(".gif", "image/gif");
    }

    private final StoryboardMapper boards;
    private final StoryModelStore storyModel;
    private final ScriptChatClient chat;
    private final CodexCli codex;
    private final ImageGenerationService images;
    private final ImageModelMapper imageModels;
    private final ImageTaskMapper imageTasks;
    private final ImageReferenceMapper imageRefs;
    private final FileStorageService files;
    private final ObjectMapper json;

    public StoryboardService(StoryboardMapper boards,
                             StoryModelStore storyModel,
                             ScriptChatClient chat,
                             CodexCli codex,
                             ImageGenerationService images,
                             ImageModelMapper imageModels,
                             ImageTaskMapper imageTasks,
                             ImageReferenceMapper imageRefs,
                             FileStorageService files,
                             ObjectMapper json) {
        this.boards = boards;
        this.storyModel = storyModel;
        this.chat = chat;
        this.codex = codex;
        this.images = images;
        this.imageModels = imageModels;
        this.imageTasks = imageTasks;
        this.imageRefs = imageRefs;
        this.files = files;
        this.json = json;
    }

    // ============================================================== 剧本模型

    /** 分镜面板上的「剧本模型」配置快照（Key 打码下发）。 */
    public Map<String, Object> storyModelSnapshot() {
        return storyModel.snapshot();
    }

    /** 保存「剧本模型」配置；只改传了值的字段，掩码不会把真 Key 冲掉。 */
    public Map<String, Object> saveStoryModel(Map<String, Object> body) {
        return storyModel.save(body == null ? new LinkedHashMap<String, Object>() : body);
    }

    // ============================================================== 脚本引擎

    /**
     * 脚本引擎状态：界面上的「检测」按钮用它。
     *
     * <p>返回结构照抄 Python：{@code engine} 是配置里选的引擎（{@code "codex"} 或空），
     * {@code available} / {@code version} / {@code exe} / {@code reason} 是现场探测的结果。
     */
    public Map<String, Object> scriptEngineInfo() {
        String engine = storyModel.scriptEngine();
        Map<String, Object> probe = codex.probe();
        String probeExe = Texts.str(probe, "exe");
        String probeVersion = Texts.str(probe, "version");
        String probeReason = Texts.str(probe, "reason");

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("engine", engine);
        out.put("available", Texts.asBool(probe.get("ok"), false));
        out.put("exe", probeExe.isEmpty() ? codex.findCodexExe() : probeExe);
        out.put("version", probeVersion);
        out.put("reason", probeReason);
        return out;
    }

    // ============================================================== 列表 / 详情

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBoards() {
        List<Storyboard> rows = boards.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows.size());
        for (Storyboard row : rows) {
            out.add(snapshot(row));
        }
        return out;
    }

    /**
     * 一份剧本的详情：<b>先把已完成定妆照收进角色记录，再出快照</b>。
     *
     * <p>收图是全局动作（Python 的 {@code sync_storyboards()} 遍历所有剧本），照抄。
     */
    public Map<String, Object> board(String id) {
        Storyboard entity = requireBoard(id);
        syncStoryboards();
        // sync 可能改了别人（也可能改了自己），重新读一次再出快照
        Storyboard fresh = boards.findById(entity.getId()).orElse(entity);
        return snapshot(fresh);
    }

    public Map<String, Object> deleteBoard(String id) {
        Storyboard entity = requireBoard(id);
        // 只删剧本本身：已经生成出来的图片/视频留在各自模块里，不跟着删
        boards.delete(entity);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        return out;
    }

    // ============================================================== 写剧本

    /**
     * 让模型编一份剧本，返回一份已经落库的 storyboard 快照。
     *
     * <p>只出「文字」，不生成任何图片 —— 出图是用户确认剧本之后再点的事。
     * {@code photoIds} 是「人物参考图」的图库 id：给了就走多模态，把图一起发给视觉模型，
     * 让它照着图里的人写角色描述，并把图直接登记成角色的定妆照。
     */
    public Map<String, Object> createBoard(Map<String, Object> body) {
        Map<String, Object> patch = body == null ? new LinkedHashMap<String, Object>() : body;
        Map<String, Object> cfg = storyModel.scriptModelConfig();
        if (Texts.str(cfg, "baseUrl").isEmpty() || Texts.str(cfg, "model").isEmpty()) {
            throw new IllegalArgumentException(
                    "还没配置写剧本的模型，点分镜面板上的「剧本模型」填一个（支持 OpenAI 兼容的对话模型）");
        }
        if (Texts.str(cfg, "apiKey").isEmpty()) {
            throw new IllegalArgumentException("写剧本的模型还没有填 API Key");
        }

        // ---- 收人物图：只认图库里真实存在、且本地文件还在的 ----
        List<Map<String, Object>> photos = collectPhotoRefs(patch.get("photoIds"), StoryModelStore.MAX_CHARACTERS);

        int want = looseInt(patch.get("shotCount"));
        if (want == 0) {
            want = 6;
        }
        want = Math.max(StoryModelStore.MIN_SHOTS, Math.min(StoryModelStore.MAX_SHOTS, want));
        String styleText = Texts.trim(patch.get("style"));
        String useStyle = styleText.isEmpty() ? StoryModelStore.DEFAULT_STYLE : styleText;

        // 有人物图时按图片张数定角色数：一张图一个角色，最直观
        int characterCount = photos.isEmpty() ? 2 : photos.size();
        String promptText = buildScriptPrompt(Texts.trim(patch.get("idea")), want, useStyle,
                characterCount, !photos.isEmpty());

        // 有图就发多模态 content 数组；没图保持纯文本，兼容不支持视觉的模型
        Object content;
        if (!photos.isEmpty()) {
            List<String> dataUris = new ArrayList<String>(photos.size());
            for (Map<String, Object> photo : photos) {
                dataUris.add(Texts.str(photo, "dataUri"));
            }
            content = ScriptChatClient.multimodal(promptText, dataUris);
        } else {
            content = promptText;
        }

        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", content);
        List<Object> messages = new ArrayList<Object>();
        messages.add(message);

        Map<String, Object> requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", Texts.str(cfg, "model"));
        requestBody.put("messages", messages);
        // 编故事需要点发挥，比识图调高
        requestBody.put("temperature", 0.8);

        String answer = chat.postChat(cfg, requestBody, SCRIPT_TIMEOUT_MS);

        Object parsed = chat.extractJson(answer);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("模型没有按要求给出 JSON 剧本，换个模型再试（免费小模型经常不守格式）");
        }
        Map<String, Object> payload = objectMap(parsed);

        // ---- 规整角色 ----
        List<Map<String, Object>> characters = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(payload.get("characters"))) {
            Map<String, Object> source = objectMap(raw);
            String name = Texts.truncate(Texts.trim(source.get("name")), 20);
            String desc = Texts.truncate(Texts.trim(source.get("desc")), 300);
            if (name.isEmpty() || desc.isEmpty()) {
                continue;
            }
            Map<String, Object> character = new LinkedHashMap<String, Object>();
            character.put("name", name);
            character.put("desc", desc);
            character.put("refId", "");
            character.put("refUrl", "");
            character.put("refTaskId", "");
            character.put("fromPhoto", false);
            character.put("note", "");
            characters.add(character);
        }
        if (characters.size() > StoryModelStore.MAX_CHARACTERS) {
            characters = new ArrayList<Map<String, Object>>(characters.subList(0, StoryModelStore.MAX_CHARACTERS));
        }
        if (characters.isEmpty()) {
            throw new IllegalArgumentException("模型没有给出可用的角色设定（缺 name 或 desc），换个模型再试");
        }

        // 用户给了人物图：按顺序把图直接挂成角色的定妆照 —— 这一步跳过「AI 生成定妆照」，
        // 人物就是用户给的那个人，之后每个镜头出图都拿它当参考图。
        for (int index = 0; index < characters.size() && index < photos.size(); index++) {
            Map<String, Object> ref = objectMap(photos.get(index).get("ref"));
            Map<String, Object> character = characters.get(index);
            character.put("refId", Texts.str(ref, "id"));
            character.put("refUrl", Texts.str(ref, "localUrl"));
            // 界面上标一下「这是你给的人物图」
            character.put("fromPhoto", true);
        }

        // ---- 规整镜头 ----
        List<String> validNames = new ArrayList<String>();
        for (Map<String, Object> character : characters) {
            validNames.add(Texts.str(character, "name"));
        }
        List<Map<String, Object>> shots = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(payload.get("shots"))) {
            Map<String, Object> source = objectMap(raw);
            String scene = Texts.truncate(Texts.trim(source.get("scene")), 500);
            if (scene.isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<String>();
            for (Object nameRaw : listOf(source.get("characters"))) {
                String name = Texts.trim(nameRaw);
                // 只保留确实在角色表里的名字：模型有时会编出个没定义的角色
                if (!name.isEmpty() && validNames.contains(name)) {
                    names.add(name);
                }
            }
            Map<String, Object> shot = new LinkedHashMap<String, Object>();
            shot.put("index", shots.size() + 1);
            shot.put("scene", scene);
            shot.put("dialogue", Texts.truncate(Texts.trim(source.get("dialogue")), 100));
            shot.put("characters", names);
            shot.put("imageTaskId", "");
            shot.put("videoTaskId", "");
            shot.put("note", "");
            shots.add(shot);
        }
        if (shots.isEmpty()) {
            throw new IllegalArgumentException("模型没有给出可用的镜头画面描述，换个模型再试");
        }

        String title = Texts.truncate(Texts.trim(payload.get("title")), 30);
        if (title.isEmpty()) {
            title = "未命名小剧场";
        }

        Map<String, Object> board = new LinkedHashMap<String, Object>();
        board.put("title", title);
        board.put("style", useStyle);
        board.put("status", "draft");
        board.put("error", "");
        board.put("characters", characters);
        board.put("shots", shots);

        Storyboard saved = boards.save(new Storyboard(title, "draft", write(board)));
        return snapshot(saved);
    }

    // ============================================================== 出定妆照

    /**
     * 给某个角色出一张定妆照。
     *
     * <p>提示词 = 画风 + 角色外貌 + 一句「正面半身定妆照」的取景要求。
     * 这一步不挂参考图 —— 它就是全片人物长相的「源头」，后续所有镜头都参考它。
     */
    public Map<String, Object> castCharacter(String id, Map<String, Object> body) {
        Map<String, Object> patch = body == null ? new LinkedHashMap<String, Object>() : body;
        Storyboard entity = requireBoard(id);
        Map<String, Object> board = readBoard(entity);
        String characterName = Texts.trim(patch.get("name"));

        Map<String, Object> character = null;
        for (Object raw : listOf(board.get("characters"))) {
            Map<String, Object> candidate = objectMap(raw);
            if (characterName.equals(Texts.str(candidate, "name"))) {
                character = candidate;
                break;
            }
        }
        if (character == null) {
            throw new IllegalArgumentException("剧本里没有叫「" + characterName + "」的角色");
        }

        ImageModel model = pickImageModel(Texts.str(patch, "modelId"));
        if (model == null) {
            throw new IllegalArgumentException("还没有可用的图片模型，先去「图片生成 → 模型管理」加一个");
        }

        String prompt = joinSentence(
                Texts.str(board, "style"),
                "角色定妆照：" + Texts.str(character, "desc"),
                "正面半身特写，看着镜头，表情自然，背景干净简洁，人物占据画面主体，五官清晰",
                "这是同一个角色的标准形象参考图，请保持外貌特征稳定");

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("modelId", model.getId());
        request.put("prompt", prompt);
        request.put("size", firstNonEmpty(Texts.str(patch, "size"), modelSize(model)));
        request.put("count", 1);
        request.put("refIds", new ArrayList<String>());

        Map<String, Object> task = images.createTask(request);

        // 记下是哪条任务在出这张定妆照，前端据此显示进度
        character.put("refTaskId", Texts.str(task, "id"));
        board.put("status", "casting");
        writeBoard(entity, board);
        boards.save(entity);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task", task);
        out.put("board", snapshot(entity));
        return out;
    }

    // ============================================================== 批量出图

    /**
     * 为剧本里所有「还没出图」的镜头批量建图片任务。
     *
     * <p>每个镜头的参考图 = 剧本里各角色的形象图（用户给的人物图，或 AI 生成的定妆照）。
     * 一个角色都没有形象图就不让出图 —— 那样画出来的人会每张都不一样。
     */
    public Map<String, Object> drawShots(String id, Map<String, Object> body) {
        Map<String, Object> patch = body == null ? new LinkedHashMap<String, Object>() : body;
        Storyboard entity = requireBoard(id);
        Map<String, Object> board = readBoard(entity);

        ImageModel model = pickImageModel(Texts.str(patch, "modelId"));
        if (model == null) {
            throw new IllegalArgumentException("还没有可用的图片模型，先去「图片生成 → 模型管理」加一个");
        }

        List<Map<String, Object>> allCharacters = new ArrayList<Map<String, Object>>();
        List<String> refIds = new ArrayList<String>();
        for (Object raw : listOf(board.get("characters"))) {
            Map<String, Object> character = objectMap(raw);
            allCharacters.add(character);
            String refId = Texts.str(character, "refId");
            if (!refId.isEmpty()) {
                refIds.add(refId);
            }
        }
        if (refIds.isEmpty()) {
            throw new IllegalArgumentException("还没有任何角色形象图（用户给的人物图或定妆照），先补上再出分镜图");
        }

        int created = 0;
        for (Object raw : listOf(board.get("shots"))) {
            Map<String, Object> shot = objectMap(raw);
            // 已经出过图（含正在出的）就跳过：重复点「批量出图」不该重复扣费
            if (!Texts.str(shot, "imageTaskId").isEmpty()) {
                continue;
            }

            // 这个镜头里出现的角色，把各自的形象描述也写进提示词 ——
            // 参考图负责「长得像」，文字描述负责「别把服装/发色画跑偏」，两者一起上最稳。
            List<String> inShot = new ArrayList<String>();
            for (Object nameRaw : listOf(shot.get("characters"))) {
                String name = Texts.trim(nameRaw);
                for (Map<String, Object> character : allCharacters) {
                    if (name.equals(Texts.str(character, "name"))) {
                        inShot.add(Texts.str(character, "name") + "：" + Texts.str(character, "desc"));
                        break;
                    }
                }
            }

            String prompt = joinSentence(Texts.str(board, "style"), joinWith(inShot, "；"),
                    Texts.str(shot, "scene"));

            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("modelId", model.getId());
            request.put("prompt", prompt);
            request.put("size", firstNonEmpty(Texts.str(patch, "size"), modelSize(model)));
            request.put("count", 1);
            request.put("refIds", refIds);

            Map<String, Object> task = images.createTask(request);
            shot.put("imageTaskId", Texts.str(task, "id"));
            shot.put("note", "");
            created++;
        }

        if (created > 0) {
            board.put("status", "drawing");
            board.put("error", "");
            writeBoard(entity, board);
            boards.save(entity);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("created", created);
        out.put("board", snapshot(entity));
        return out;
    }

    // ============================================================== 收定妆照

    /**
     * 每次读详情时调一次：把已经出好的定妆照「收」进角色记录。
     *
     * <p>定妆照任务成功后，把结果图登记成参考图，之后每个镜头出图就拿这个 {@code refId} 当参考图。
     */
    /**
     * 对外暴露一次「收定妆照」：{@code GET /api/state} 在拼快照之前要先把所有剧本的定妆照收一遍，
     * 对应 Python {@code state.py} 里那句 {@code sync_storyboards()}。
     */
    public void syncAll() {
        syncStoryboards();
    }

    private void syncStoryboards() {
        for (Storyboard entity : boards.findAllByOrderByCreatedAtDesc()) {
            if (harvestCast(entity)) {
                boards.save(entity);
            }
        }
    }

    private boolean harvestCast(Storyboard entity) {
        Map<String, Object> board = readBoard(entity);
        boolean changed = false;
        for (Object raw : listOf(board.get("characters"))) {
            Map<String, Object> character = objectMap(raw);
            String refId = Texts.str(character, "refId");
            String refTaskId = Texts.str(character, "refTaskId");
            if (!refId.isEmpty() || refTaskId.isEmpty()) {
                continue;
            }
            ImageTask task = imageTasks.findById(refTaskId).orElse(null);
            if (task == null) {
                continue;
            }
            List<Map<String, Object>> taskImages = imagesOf(task);
            if ("success".equals(task.getStatus()) && !taskImages.isEmpty()) {
                Map<String, Object> image = taskImages.get(0);
                try {
                    Map<String, Object> ref = images.taskImageToRef(task.getId(), emptyMap());
                    character.put("refId", Texts.str(ref, "id"));
                    character.put("refUrl", Texts.str(ref, "localUrl"));
                    character.put("refImageId", Texts.str(image, "id"));
                    changed = true;
                } catch (RuntimeException err) {
                    character.put("refTaskId", "");
                    character.put("note", "定妆照登记失败：" + messageOf(err));
                    changed = true;
                }
            } else if ("failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())) {
                // 出图失败就清掉任务 id，界面上那个角色回到「未定妆」，可以重出
                character.put("refTaskId", "");
                character.put("note", firstNonEmpty(task.getErrorMessage(), "定妆照生成失败"));
                changed = true;
            }
        }
        if (changed) {
            writeBoard(entity, board);
        }
        return changed;
    }

    // ============================================================== 快照

    /**
     * 下发给前端的剧本快照。
     *
     * <p>每次都从 {@code image_tasks} 现查图片状态，而不是存一份快照在剧本里 ——
     * 任务会被重试、会被裁剪，存快照就会和真实结果对不上。
     */
    private Map<String, Object> snapshot(Storyboard entity) {
        Map<String, Object> board = readBoard(entity);

        List<Map<String, Object>> characters = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(board.get("characters"))) {
            Map<String, Object> source = objectMap(raw);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", Texts.str(source, "name"));
            item.put("desc", Texts.str(source, "desc"));
            item.put("refUrl", Texts.str(source, "refUrl"));
            item.put("refTaskId", Texts.str(source, "refTaskId"));
            item.put("cast", imageTaskBrief(Texts.str(source, "refTaskId")));
            item.put("note", Texts.str(source, "note"));
            // 形象图是用户自己给的人物图（true）还是 AI 生成的定妆照（false）
            item.put("fromPhoto", Texts.asBool(source.get("fromPhoto"), false));
            characters.add(item);
        }

        List<Map<String, Object>> shots = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(board.get("shots"))) {
            Map<String, Object> source = objectMap(raw);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", Texts.asInt(source.get("index"), 0));
            item.put("scene", Texts.str(source, "scene"));
            item.put("dialogue", Texts.str(source, "dialogue"));
            item.put("characters", listOf(source.get("characters")));
            item.put("note", Texts.str(source, "note"));
            item.put("image", imageTaskBrief(Texts.str(source, "imageTaskId")));
            item.put("videoTaskId", Texts.str(source, "videoTaskId"));
            shots.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", entity.getId());
        out.put("title", entity.getTitle());
        out.put("style", Texts.str(board, "style"));
        out.put("status", entity.getStatus());
        out.put("error", Texts.str(board, "error"));
        out.put("createdAt", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        out.put("characters", characters);
        out.put("shots", shots);
        return out;
    }

    /** 一条图片任务压成界面要用的几个字段（分镜卡片上只显示状态和缩略图）。 */
    private Map<String, Object> imageTaskBrief(String taskId) {
        if (taskId.isEmpty()) {
            return null;
        }
        ImageTask task = imageTasks.findById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        List<Map<String, Object>> images = imagesOf(task);
        Map<String, Object> image = images.isEmpty() ? new LinkedHashMap<String, Object>() : images.get(0);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", task.getId());
        out.put("status", task.getStatus());
        out.put("error", task.getErrorMessage() == null ? "" : task.getErrorMessage());
        out.put("modelName", task.getModelName() == null ? "" : task.getModelName());
        out.put("imageId", Texts.str(image, "id"));
        out.put("url", firstNonEmpty(Texts.str(image, "localUrl"), Texts.str(image, "remoteUrl")));
        out.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        return out;
    }

    private List<Map<String, Object>> imagesOf(ImageTask task) {
        Map<String, Object> result = readJsonObject(task.getResultJson());
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : listOf(result.get("images"))) {
            out.add(objectMap(raw));
        }
        return out;
    }

    // ============================================================== 读 / 写剧本

    private Storyboard requireBoard(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new StoryboardNotFoundException();
        }
        return boards.findById(id.trim()).orElseThrow(StoryboardNotFoundException::new);
    }

    /** 把 {@code script_json} 反序列化成内存里的剧本对象，顺带补上 id / createdAt。 */
    private Map<String, Object> readBoard(Storyboard entity) {
        Map<String, Object> board = readJsonObject(entity.getScriptJson());
        board.put("id", entity.getId());
        board.put("title", entity.getTitle());
        board.put("status", entity.getStatus());
        board.put("createdAt", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        if (!Texts.has(board, "characters")) {
            board.put("characters", new ArrayList<Object>());
        }
        if (!Texts.has(board, "shots")) {
            board.put("shots", new ArrayList<Object>());
        }
        return board;
    }

    /** 把内存里的剧本对象写回实体（{@code script_json} + title/status 两列冗余）。 */
    private void writeBoard(Storyboard entity, Map<String, Object> board) {
        entity.setTitle(Texts.str(board, "title", "未命名小剧场"));
        entity.setStatus(Texts.str(board, "status", "draft"));
        entity.setScriptJson(write(board));
        entity.touch();
    }

    // ============================================================== 提示词

    /** 让模型按固定结构出剧本的那段说明（与 Python 逐行一致）。 */
    private String buildScriptPrompt(String idea, int shotCount, String style,
                                     int characterCount, boolean hasPhotos) {
        String head = hasPhotos
                ? "你是一个短视频分镜编剧。用户已经给了人物参考图，请**照着图里的人**编一个适合做成短视频的小剧场分镜。"
                : "你是一个短视频分镜编剧。请根据用户给的题材，编一个适合做成短视频的小剧场分镜。";

        // 有参考图时 desc 必须「照着图写」：之后每个镜头都拿这张图当参考图，
        // 描述和真人对不上会让模型不知道该画成谁。
        String charRule;
        if (hasPhotos) {
            charRule = String.join("\n", new String[]{
                    "- characters：正好 " + characterCount + " 个角色，**按用户给的图片顺序一一对应**（第 1 张图 = 第 1 个角色）。",
                    "  · name：给这个角色起个名字。",
                    "  · desc：**仔细观察图里的人**，写清楚「性别、年龄段、发型发色、五官特征、服装、气质」，40~70 字。",
                    "    这段描述会作为该角色的固定形象说明，之后每个镜头都会带上它 —— 所以必须**如实描述图里看到的样子**，",
                    "    不要自己另编一个长相，也不要用「很漂亮」「有气质」这种没法画的话。"
            });
        } else {
            charRule = String.join("\n", new String[]{
                    "- characters：" + characterCount + " 个角色。desc 必须写清楚「性别、年龄段、发型发色、五官特征、服装、气质」，",
                    "每个角色一段 40~70 字的描述。这段描述会被拿去生成角色定妆照，也是全片人物长相一致的唯一依据 —— ",
                    "所以特征必须具体、可画（不要写「很漂亮」「有气质」这种没法画的话）。"
            });
        }

        List<String> lines = new ArrayList<String>();
        lines.add(head);
        lines.add("");
        lines.add("只输出 JSON，不要任何解释、不要 markdown 代码块。JSON 格式：");
        lines.add("{\"title\":\"剧名\",\"characters\":[{\"name\":\"角色名\",\"desc\":\"外貌与服装的详细描述\"}],"
                + "\"shots\":[{\"scene\":\"这个镜头的画面描述\",\"dialogue\":\"台词或空字符串\","
                + "\"characters\":[\"出现在这个镜头里的角色名\"]}]}");
        lines.add("");
        lines.add("各字段要求：");
        lines.add("- title：剧名，12 字以内。");
        lines.add(charRule);
        lines.add("- shots：正好 " + shotCount + " 个镜头，按剧情顺序排列。");
        lines.add("  · scene：这个镜头的画面描述，50~90 字。要写清楚「在哪、谁在做什么、什么景别（近景/中景/全身）、什么动作、什么表情」。");
        lines.add("    每个镜头都必须是独立的、可单独画出来的一幅画面。");
        lines.add("  · dialogue：这个镜头里角色说的话，15 字以内；没有台词就填空字符串。");
        lines.add("  · characters：这个镜头里出现的角色名，必须是上面 characters 里出现过的名字。");
        lines.add("");
        lines.add("硬性要求：");
        lines.add("- 剧情要有起承转合，镜头之间要有连贯性，不要 12 个互不相关的画面。");
        lines.add("- 不要写任何真实人名、品牌名、地名。");
        lines.add("- 不要出现暴力、色情、政治相关内容。");
        lines.add("- 全片统一风格：" + style + "。不要把风格写进每个 scene，scene 只写画面内容。");
        lines.add(idea.isEmpty()
                ? "\n用户没给题材，你自由发挥一个有趣的小剧场。"
                : "\n用户给的题材是：「" + idea + "」。请围绕它编。");
        return String.join("\n", lines);
    }

    /** 用「，」拼句，但要先去掉各段自己的句末标点，否则会出现「…清晰。，这是…」这种叠标点。 */
    private static String joinSentence(String... parts) {
        List<String> cleaned = new ArrayList<String>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String text = TRAILING_PUNCT.matcher(part.trim()).replaceAll("");
            if (!text.isEmpty()) {
                cleaned.add(text);
            }
        }
        return joinWith(cleaned, "，") + "。";
    }

    private static String joinWith(List<String> parts, String separator) {
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

    // ============================================================== 参考图

    /** 把「参考图库里的 id」收成能直接发给模型的图；返回 {@code [{ref, dataUri}]}。 */
    private List<Map<String, Object>> collectPhotoRefs(Object photoIds, int maxCount) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(photoIds instanceof List)) {
            return out;
        }
        List<?> ids = (List<?>) photoIds;
        int limit = Math.min(ids.size(), maxCount);
        for (int i = 0; i < limit; i++) {
            String refId = Texts.trim(ids.get(i));
            if (refId.isEmpty()) {
                continue;
            }
            ImageReference ref = imageRefs.findById(refId).orElse(null);
            if (ref == null) {
                continue;
            }
            String dataUri = refDataUri(ref);
            if (dataUri.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("ref", refView(ref));
            entry.put("dataUri", dataUri);
            out.add(entry);
        }
        return out;
    }

    /** 把一张参考图读成 data URI；文件不在了返回空串（不抛错，调用方跳过即可）。 */
    private String refDataUri(ImageReference ref) {
        try {
            if (ref.isLink()) {
                return ""; // 外网链接图没法直接塞进 messages，跳过
            }
            String fileId = ref.getFileId();
            if (fileId == null || fileId.trim().isEmpty()) {
                return "";
            }
            StoredFile stored = files.require(fileId);
            Path path = files.resolve(stored);
            if (!Files.isRegularFile(path)) {
                return "";
            }
            String ext = FileStorageService.extensionOf(path.getFileName().toString());
            String mime = stored.getMimeType();
            if (mime == null || mime.trim().isEmpty()) {
                mime = REF_MIME_TYPES.get(ext);
            }
            if (mime == null || mime.trim().isEmpty()) {
                mime = "image/png";
            }
            byte[] buffer = Files.readAllBytes(path);
            String encoded = Base64.getEncoder().encodeToString(buffer);
            return "data:" + mime + ";base64," + encoded;
        } catch (IOException | RuntimeException err) {
            return "";
        }
    }

    /** 参考图库记录压成前端用的视图（字段与图片模块一致）。 */
    private Map<String, Object> refView(ImageReference ref) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ref.getId());
        out.put("source", ref.getSource());
        out.put("originalName", ref.getOriginalName());
        out.put("createdAt", ref.getCreatedAt() == null ? null : ref.getCreatedAt().toString());
        if (ref.isLink()) {
            out.put("fileName", "");
            out.put("localPath", "");
            out.put("localUrl", ref.getRemoteUrl());
            out.put("mimeType", "");
            out.put("size", 0L);
            out.put("url", ref.getRemoteUrl());
            return out;
        }
        StoredFile stored = null;
        if (ref.getFileId() != null && !ref.getFileId().trim().isEmpty()) {
            try {
                stored = files.require(ref.getFileId());
            } catch (IllegalArgumentException ignored) {
                stored = null;
            }
        }
        if (stored == null) {
            out.put("fileName", "");
            out.put("localPath", "");
            out.put("localUrl", "");
            out.put("mimeType", "");
            out.put("size", 0L);
            out.put("url", "");
            return out;
        }
        Path path = files.resolve(stored);
        out.put("fileName", stored.getStorageName());
        out.put("localPath", path.toString());
        out.put("localUrl", files.localUrlFor(stored));
        out.put("mimeType", stored.getMimeType());
        out.put("size", stored.getSizeBytes());
        out.put("url", "");
        return out;
    }

    // ============================================================== 图片模型

    /**
     * 没指定模型时挑一个：优先能图生图的（分镜后面要靠它），其次第一个启用的。
     *
     * <p>「能图生图」的判定与图片模块 {@code supportsImageInput} 同一套口径：
     * 智谱恒不支持，配置里显式写了就按配置，方舟没写默认支持。
     */
    private ImageModel pickImageModel(String modelId) {
        if (!modelId.isEmpty()) {
            return imageModels.findById(modelId).orElse(null);
        }
        List<ImageModel> enabled = new ArrayList<ImageModel>();
        for (ImageModel model : imageModels.findByEnabledTrueOrderByCreatedAtDesc()) {
            if (model.getModelName() != null && !model.getModelName().trim().isEmpty()) {
                enabled.add(model);
            }
        }
        for (ImageModel model : enabled) {
            if (supportsImageInput(model)) {
                return model;
            }
        }
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    private boolean supportsImageInput(ImageModel model) {
        Map<String, Object> config = readJsonObject(model.getConfigJson());
        if (ImageProtocols.ZHIPU.equals(model.getProtocol())) {
            return false;
        }
        if (Texts.asBool(config.get("supportsImageInput"), false)) {
            return true;
        }
        return ImageProtocols.ARK.equals(model.getProtocol()) && !config.containsKey("supportsImageInput");
    }

    /** 模型的默认尺寸存在 {@code config_json.size} 里。 */
    private String modelSize(ImageModel model) {
        return Texts.str(readJsonObject(model.getConfigJson()), "size");
    }

    // ============================================================== 工具

    private Map<String, Object> readJsonObject(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = json.readValue(raw, Object.class);
            if (parsed instanceof Map) {
                return objectMap(parsed);
            }
        } catch (IOException ignored) {
            // 坏 JSON 当空对象处理：界面宁可少点信息，也别整页 500
        }
        return new LinkedHashMap<String, Object>();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException err) {
            throw new IllegalStateException("无法序列化 JSON：" + err.getMessage(), err);
        }
    }

    /**
     * 取 Map 视图。
     *
     * <p><b>这里故意返回原对象而不是复制</b>：剧本里的角色和镜头是直接改 Map 再整份写回的
     * （{@code character.put("refTaskId", ...)}），一旦复制，改动就写不回 {@code script_json} 了。
     * Jackson 反序列化出来的本来就是 {@code LinkedHashMap<String, Object>}，强转是安全的。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static List<Object> listOf(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<?>) value);
        }
        return new ArrayList<Object>();
    }

    /** Python {@code _as_int}：转不成数字或 NaN 都算 0（3.7 取 3）。 */
    private static int looseInt(Object value) {
        if (value == null) {
            return 0;
        }
        double num;
        try {
            num = Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException err) {
            return 0;
        }
        if (Double.isNaN(num)) {
            return 0;
        }
        return (int) num;
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? (second == null ? "" : second) : first;
    }

    private static Map<String, Object> emptyMap() {
        return new LinkedHashMap<String, Object>();
    }

    private static String messageOf(Throwable err) {
        String message = err.getMessage();
        return message == null || message.trim().isEmpty() ? err.getClass().getSimpleName() : message;
    }

    /** 保留给未来可能的纯文本落盘需求（当前 {@code script_json} 走 Jackson）。 */
    static String utf8(byte[] buffer) {
        return new String(buffer, StandardCharsets.UTF_8);
    }

    /** 仅供测试/调试：把剧本对象的 JSON 打出来。 */
    String debugJson(Storyboard entity) {
        return entity.getScriptJson();
    }

    /** 保留：部分老数据里 {@code shot.index} 可能缺失，按顺序补。 */
    static int indexOf(Map<String, Object> shot, int fallback) {
        int value = Texts.asInt(shot.get("index"), 0);
        return value > 0 ? value : fallback;
    }

    /** 保留：图片路径是否存在（给未来的「缺图重出」用）。 */
    static boolean exists(String path) {
        return path != null && !path.isEmpty() && Files.isRegularFile(Paths.get(path));
    }
}
