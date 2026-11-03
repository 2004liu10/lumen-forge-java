package com.lumenforge.controller.common;

import com.lumenforge.provider.ImageProtocols;
import com.lumenforge.provider.VideoGenerationClient;
import com.lumenforge.service.account.AccountService;
import com.lumenforge.service.canvas.CanvasService;
import com.lumenforge.service.image.ImageGenerationService;
import com.lumenforge.service.material.MaterialConstants;
import com.lumenforge.service.material.MaterialSourceService;
import com.lumenforge.service.ncanvas.NodeAssetService;
import com.lumenforge.service.ncanvas.NodeCanvasService;
import com.lumenforge.service.publish.PublishService;
import com.lumenforge.service.story.StoryboardService;
import com.lumenforge.service.task.TaskService;
import com.lumenforge.service.topaz.TopazService;
import com.lumenforge.service.topic.TopicService;
import com.lumenforge.service.video.VideoGenerationService;
import com.lumenforge.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量状态接口。
 *
 * <p>对应 Python 的 {@code GET /api/state}（{@code app/api/state.py}）。前端启动时只拉这一个接口，
 * 就能把账号、画布、任务、图片/视频模型、素材源、剧本、话题、发布记录、Topaz、无限画布全部填满，
 * 省掉十几次并发请求。</p>
 *
 * <p>键名、顺序、嵌套结构逐字对齐 Python 的 {@code state_snapshot()}；其中 {@code topics} /
 * {@code topicStats} / {@code publishLog} 是**裸数组**（不是 {@code {topics, stats}} 包装对象），
 * 所以这里要从各 service 的包装结果里取出来再放。</p>
 */
@RestController
@RequestMapping("/api")
public class StateController {

    private static final Logger log = LoggerFactory.getLogger(StateController.class);

    /** Python {@code runninghub.DEFAULT_BASE}。 */
    private static final String DEFAULT_BASE_URL = "https://www.runninghub.cn";

    /** Python {@code _recent()} 的条数上限。 */
    private static final int RECENT = 40;

    /** Python {@code store.tasks[:120]}。 */
    private static final int TASK_ROWS = 120;

    private final AccountService accounts;
    private final CanvasService canvases;
    private final TaskService tasks;
    private final ImageGenerationService images;
    private final VideoGenerationService videos;
    private final MaterialSourceService materials;
    private final StoryboardService storyboards;
    private final TopicService topics;
    private final PublishService publish;
    private final TopazService topaz;
    private final NodeCanvasService nodeCanvases;
    private final NodeAssetService nodeAssets;
    private final StoragePaths paths;

    public StateController(AccountService accounts,
                           CanvasService canvases,
                           TaskService tasks,
                           ImageGenerationService images,
                           VideoGenerationService videos,
                           MaterialSourceService materials,
                           StoryboardService storyboards,
                           TopicService topics,
                           PublishService publish,
                           TopazService topaz,
                           NodeCanvasService nodeCanvases,
                           NodeAssetService nodeAssets,
                           StoragePaths paths) {
        this.accounts = accounts;
        this.canvases = canvases;
        this.tasks = tasks;
        this.images = images;
        this.videos = videos;
        this.materials = materials;
        this.storyboards = storyboards;
        this.topics = topics;
        this.publish = publish;
        this.topaz = topaz;
        this.nodeCanvases = nodeCanvases;
        this.nodeAssets = nodeAssets;
        this.paths = paths;
    }

    /**
     * 全量状态。19 组键，顺序与 Python 一致。
     *
     * <p>Python 在返回前还会做两件「顺手修一下」的活：{@code sync_storyboards()}（把出好的定妆照收进角色）
     * 和 {@code repair_moved_local_paths()}（存储目录搬家后修正历史记录的本地路径）。两件都被 try/except
     * 包着、失败只记日志不影响返回，这里照抄同样的容错。</p>
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        try {
            storyboards.syncAll();
        } catch (RuntimeException err) {
            log.error("[state] 同步剧本失败：{}", err.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("accounts", accounts.list());
        out.put("canvases", canvases.list());
        out.put("tasks", tasks.list(null, null, TASK_ROWS));

        out.put("imageModels", images.listModels());
        out.put("imageTasks", images.listTasks(RECENT));

        out.put("videoModels", videos.listModels());
        out.put("videoTasks", videos.listTasks(RECENT));
        out.put("videoInputs", videos.listInputs());

        out.put("materialSources", materials.listSnapshots());
        out.put("materialLinePool", materials.linePool());
        out.put("imageSearchSources", MaterialConstants.IMAGE_SEARCH_SOURCES);
        out.put("imageRankModes", MaterialConstants.IMAGE_RANK_MODES);

        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("baseUrl", DEFAULT_BASE_URL);
        out.put("defaults", defaults);

        out.put("storage", paths.snapshot());
        out.put("dataDir", paths.root().toString());
        out.put("imageDefaults", imageDefaults());

        Map<String, Object> videoDefaults = new LinkedHashMap<String, Object>();
        videoDefaults.put("providers", VideoGenerationClient.presets());
        out.put("videoDefaults", videoDefaults);

        out.put("storyboards", storyboards.listBoards());
        out.put("storyDefaults", storyboards.storyModelSnapshot());

        // Python 这三个是裸数组：topic_list() / topic_stats() / list_publish_records()。
        // Java 侧 service 为了给各自的接口复用，包了一层 {topics,stats} / {records}，这里取出来。
        Map<String, Object> topicBundle = topics.list("");
        out.put("topics", topicBundle.get("topics"));
        out.put("topicStats", topicBundle.get("stats"));

        Map<String, Object> publishBundle = publish.listRecords();
        out.put("publishLog", publishBundle.get("records"));

        out.put("topaz", topaz.snapshot());
        out.put("nodeCanvases", nodeCanvases.snapshots());
        out.put("nodeAssets", nodeAssets.snapshots());
        return out;
    }

    /** 图片协议的下拉预设：地址、内置模型目录、尺寸档、比例档与参考图上限。 */
    private Map<String, Object> imageDefaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("arkBaseUrl", ImageProtocols.ARK_BASE);
        out.put("arkModels", ImageProtocols.ARK_MODEL_PRESETS);
        out.put("sizePresets", ImageProtocols.SIZE_PRESETS);
        out.put("ratioPresets", ImageProtocols.IMAGE_RATIO_PRESETS);
        out.put("maxRefs", ImageProtocols.MAX_REFS);
        out.put("zhipuBaseUrl", ImageProtocols.ZHIPU_BASE);
        out.put("zhipuModels", ImageProtocols.ZHIPU_MODEL_PRESETS);
        out.put("zhipuSizePresets", ImageProtocols.ZHIPU_SIZE_PRESETS);
        out.put("jimengBaseUrl", ImageProtocols.JIMENG_BASE);
        out.put("jimengModels", ImageProtocols.JIMENG_MODEL_PRESETS);
        out.put("jimengSizePresets", ImageProtocols.JIMENG_SIZE_PRESETS);
        out.put("wanxiangBaseUrl", ImageProtocols.WANXIANG_BASE);
        out.put("wanxiangModels", ImageProtocols.WANXIANG_MODEL_PRESETS);
        out.put("wanxiangFunctions", ImageProtocols.WANXIANG_FUNCTIONS);
        out.put("hunyuanBaseUrl", ImageProtocols.HUNYUAN_BASE);
        out.put("hunyuanModels", ImageProtocols.HUNYUAN_MODEL_PRESETS);
        out.put("hunyuanSizePresets", ImageProtocols.HUNYUAN_SIZE_PRESETS);
        out.put("minimaxBaseUrl", ImageProtocols.MINIMAX_BASE);
        out.put("minimaxModels", ImageProtocols.MINIMAX_MODEL_PRESETS);
        out.put("minimaxSizePresets", ImageProtocols.MINIMAX_SIZE_PRESETS);
        out.put("cloudflareBaseUrl", ImageProtocols.CLOUDFLARE_BASE);
        out.put("cloudflareModels", ImageProtocols.CLOUDFLARE_MODEL_PRESETS);
        out.put("cloudflareSizePresets", ImageProtocols.CLOUDFLARE_SIZE_PRESETS);
        return out;
    }
}
