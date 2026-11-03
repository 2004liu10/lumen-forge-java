-- Lumen Forge MySQL 8 schema. Core runtime entities are followed by planned module tables
-- so later feature migrations can add Java mappings without changing table ownership.

CREATE TABLE provider_accounts (
    id VARCHAR(255) NOT NULL COMMENT '账号主键',
    name VARCHAR(160) NOT NULL COMMENT '界面显示名称',
    api_key_ciphertext VARCHAR(4096) NOT NULL COMMENT 'AES-GCM 加密后的 RunningHub API Key',
    site VARCHAR(8) NOT NULL COMMENT '站点：cn 国内站，ai 国际站',
    concurrency INT NOT NULL COMMENT '该账号允许的最大并发任务数',
    enabled BIT(1) NOT NULL COMMENT '是否参与任务调度',
    remark VARCHAR(1000) NULL COMMENT '用户备注',
    daily_limit INT NOT NULL COMMENT '本地 24 小时额度上限，0 表示不限',
    quota_used INT NOT NULL COMMENT '当前额度窗口已提交任务数',
    quota_window_start BIGINT NULL COMMENT '额度窗口起始时间，Unix 毫秒',
    total_tasks BIGINT NOT NULL COMMENT '累计处理任务数',
    successful_tasks BIGINT NOT NULL COMMENT '累计成功数',
    failed_tasks BIGINT NOT NULL COMMENT '累计失败数',
    login_name_ciphertext VARCHAR(4096) NULL COMMENT '加密后的网页登录名',
    login_pass_ciphertext VARCHAR(4096) NULL COMMENT '加密后的网页登录密码',
    login_at BIGINT NULL COMMENT '最近登录时间，Unix 毫秒',
    login_expires_at BIGINT NULL COMMENT '网页登录态过期时间，Unix 毫秒',
    claim_at BIGINT NULL COMMENT '最近领取额度时间，Unix 毫秒',
    claim_gain BIGINT NULL COMMENT '最近一次领取的额度',
    claim_note VARCHAR(500) NULL COMMENT '最近一次领取结果说明',
    remain_coins DOUBLE NULL COMMENT '最近一次查询到的 RH 币余额',
    remain_money DOUBLE NULL COMMENT '最近一次查询到的钱包金额',
    currency VARCHAR(20) NULL COMMENT '余额货币单位',
    api_type VARCHAR(80) NULL COMMENT '平台 API 账号类型',
    current_task_counts INT NULL COMMENT '平台报告的运行任务数',
    balance_error VARCHAR(1000) NULL COMMENT '最近一次余额查询错误',
    balance_updated_at BIGINT NULL COMMENT '余额更新时间，Unix 毫秒',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间',
    PRIMARY KEY (id),
    KEY idx_provider_accounts_dispatch (enabled, site),
    KEY idx_provider_accounts_quota (enabled, quota_window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='RunningHub API 账号与调度额度';

CREATE TABLE canvases (
    id VARCHAR(255) NOT NULL COMMENT '画布主键',
    name VARCHAR(160) NOT NULL COMMENT '画布名称',
    mode VARCHAR(24) NOT NULL COMMENT 'app AI 应用或 workflow 工作流',
    base_url VARCHAR(500) NOT NULL COMMENT 'RunningHub API 地址',
    app_id VARCHAR(200) NULL COMMENT 'RunningHub webappId 或 workflowId',
    app_name VARCHAR(200) NULL COMMENT '平台应用名称',
    account_id VARCHAR(255) NULL COMMENT '优先使用的账号；空值由调度器分配',
    auto_retry BIT(1) NOT NULL COMMENT '失败后是否自动重试',
    max_attempts INT NOT NULL COMMENT '自动重试上限',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间',
    config_json LONGTEXT NOT NULL COMMENT '动态字段、表单值、素材和高级设置快照',
    PRIMARY KEY (id),
    KEY idx_canvases_account (account_id),
    CONSTRAINT fk_canvases_account FOREIGN KEY (account_id)
        REFERENCES provider_accounts (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='可复用的 AI 应用/工作流配置';

CREATE TABLE generation_tasks (
    id VARCHAR(255) NOT NULL COMMENT '本地任务主键',
    canvas_id VARCHAR(255) NULL COMMENT '来源画布；手动导入任务可为空',
    canvas_name VARCHAR(160) NOT NULL COMMENT '创建任务时的画布名称快照',
    mode VARCHAR(24) NOT NULL COMMENT '提交模式：app 或 workflow',
    app_id VARCHAR(200) NOT NULL COMMENT '提交时使用的应用/工作流 ID 快照',
    status VARCHAR(32) NOT NULL COMMENT 'queued/preparing/remote_queued/running/success/failed/canceled',
    message VARCHAR(1000) NULL COMMENT '前端展示的当前状态说明',
    attempts INT NOT NULL COMMENT '已尝试提交次数',
    max_attempts INT NOT NULL COMMENT '允许的提交次数上限',
    account_id VARCHAR(255) NULL COMMENT '实际执行任务的账号',
    account_name VARCHAR(160) NULL COMMENT '提交时账号名称快照',
    pinned_account_id VARCHAR(255) NULL COMMENT '用户指定账号；空值代表自动分配',
    remote_task_id VARCHAR(255) NULL COMMENT 'RunningHub 远端任务号',
    error_message VARCHAR(2000) NULL COMMENT '最近错误信息',
    canvas_config_json LONGTEXT NOT NULL COMMENT '不可变的提交配置快照，画布变更不影响排队任务',
    outputs_json LONGTEXT NOT NULL COMMENT '远端结果及本地文件元数据列表',
    log_json LONGTEXT NOT NULL COMMENT '按时间排列的任务事件日志',
    created_at BIGINT NULL COMMENT '创建时间，Unix 毫秒',
    started_at BIGINT NULL COMMENT '首次派单时间，Unix 毫秒',
    finished_at BIGINT NULL COMMENT '结束时间，Unix 毫秒',
    next_poll_at BIGINT NULL COMMENT '下一次远端轮询时间，Unix 毫秒',
    poll_errors INT NOT NULL COMMENT '连续查询失败次数',
    cancel_requested BIT(1) NOT NULL COMMENT '是否等待调度器向远端取消',
    imported BIT(1) NOT NULL COMMENT '是否由用户按远端任务号导入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_generation_tasks_remote (remote_task_id),
    KEY idx_generation_tasks_queue (status, created_at),
    KEY idx_generation_tasks_account_status (account_id, status),
    KEY idx_generation_tasks_canvas_created (canvas_id, created_at),
    CONSTRAINT fk_generation_tasks_canvas FOREIGN KEY (canvas_id)
        REFERENCES canvases (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_tasks_account FOREIGN KEY (account_id)
        REFERENCES provider_accounts (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_tasks_pinned_account FOREIGN KEY (pinned_account_id)
        REFERENCES provider_accounts (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='跨重启持久化的生成队列和远端任务状态';

CREATE TABLE stored_files (
    id VARCHAR(255) NOT NULL COMMENT '文件元数据主键',
    category VARCHAR(32) NOT NULL COMMENT 'uploads/outputs/materials/images/videos 等分类',
    original_name VARCHAR(500) NOT NULL COMMENT '用户上传或平台返回的原始文件名',
    storage_name VARCHAR(255) NOT NULL COMMENT '磁盘上的随机化文件名',
    relative_path VARCHAR(1000) NOT NULL COMMENT '相对于 storage root 的路径',
    mime_type VARCHAR(160) NULL COMMENT '文件 MIME 类型',
    size_bytes BIGINT NOT NULL COMMENT '文件字节数',
    sha256 CHAR(64) NULL COMMENT '可选内容摘要，用于去重和完整性检查',
    created_at DATETIME(6) NOT NULL COMMENT '文件登记时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_files_path (relative_path(191)),
    KEY idx_stored_files_category_created (category, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='本地媒体文件索引；文件二进制仍保存在受控磁盘目录';

CREATE TABLE web_providers (
    id VARCHAR(255) NOT NULL COMMENT '网页平台配置主键',
    slug VARCHAR(80) NOT NULL COMMENT '稳定平台标识',
    name VARCHAR(160) NOT NULL COMMENT '平台显示名称',
    enabled BIT(1) NOT NULL COMMENT '是否启用该平台',
    quota_per_day INT NULL COMMENT '单账号每日免费额度',
    config_json JSON NOT NULL COMMENT '登录地址、能力标记和网页自动化配置',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_web_providers_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='可配置的网页端 AI 服务平台';

CREATE TABLE web_accounts (
    id VARCHAR(255) NOT NULL COMMENT '网页平台账号主键',
    provider_slug VARCHAR(80) NOT NULL COMMENT '所属平台 slug',
    name VARCHAR(160) NOT NULL COMMENT '账号显示名称',
    enabled BIT(1) NOT NULL COMMENT '是否参加网页账号额度调度',
    remark VARCHAR(1000) NULL COMMENT '用户备注',
    login_at BIGINT NULL COMMENT '最近成功登录时间，Unix 毫秒',
    login_expires_at BIGINT NULL COMMENT '网页登录态过期时间，Unix 毫秒',
    quota_used INT NOT NULL COMMENT '当天已消耗次数',
    quota_day DATE NULL COMMENT '额度计数对应的自然日',
    last_error VARCHAR(500) NULL COMMENT '最近一次运行失败原因',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_web_accounts_provider_enabled (provider_slug, enabled),
    CONSTRAINT fk_web_accounts_provider FOREIGN KEY (provider_slug)
        REFERENCES web_providers (slug) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='豆包、千问等网页平台的多账号额度池';

CREATE TABLE image_models (
    id VARCHAR(255) NOT NULL COMMENT '图片模型主键',
    name VARCHAR(160) NOT NULL COMMENT '模型显示名称',
    protocol VARCHAR(40) NOT NULL COMMENT '协议适配器标识',
    base_url VARCHAR(1000) NOT NULL COMMENT '厂商 API 地址',
    api_key_ciphertext VARCHAR(4096) NULL COMMENT '加密 API Key',
    model_name VARCHAR(255) NOT NULL COMMENT '厂商模型 ID',
    enabled BIT(1) NOT NULL COMMENT '是否允许提交任务',
    config_json JSON NOT NULL COMMENT '尺寸、参考图支持、附加参数等模型特有设置',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_image_models_enabled (enabled, protocol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='多厂商图片生成模型配置';

CREATE TABLE image_tasks (
    id VARCHAR(255) NOT NULL COMMENT '图片任务主键',
    model_id VARCHAR(255) NULL COMMENT '来源模型；保留模型名称快照以支持模型删除',
    model_name VARCHAR(160) NOT NULL COMMENT '提交时模型名称',
    prompt TEXT NOT NULL COMMENT '生图提示词',
    size VARCHAR(80) NULL COMMENT '图片尺寸或比例',
    image_count INT NOT NULL COMMENT '请求生成张数',
    status VARCHAR(32) NOT NULL COMMENT 'queued/running/success/failed/canceled',
    result_json LONGTEXT NOT NULL COMMENT '图片结果、远端状态和本地文件引用',
    error_message TEXT NULL COMMENT '最近错误信息',
    attempts INT NOT NULL COMMENT '提交次数',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    finished_at DATETIME(6) NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    KEY idx_image_tasks_status_created (status, created_at),
    CONSTRAINT fk_image_tasks_model FOREIGN KEY (model_id)
        REFERENCES image_models (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='异步图片生成任务';

CREATE TABLE image_references (
    id VARCHAR(255) NOT NULL COMMENT '参考图主键',
    file_id VARCHAR(255) NULL COMMENT '本地图片文件；外链参考图可为空',
    remote_url VARCHAR(2000) NULL COMMENT '仅支持 URL 的协议使用的外链图片地址',
    original_name VARCHAR(500) NOT NULL COMMENT '原始文件名',
    source VARCHAR(32) NOT NULL COMMENT 'upload/generated/link',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_image_references_created (created_at),
    CONSTRAINT fk_image_references_file FOREIGN KEY (file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='可复用的图生图参考素材';

CREATE TABLE image_task_references (
    image_task_id VARCHAR(255) NOT NULL COMMENT '图片任务',
    reference_id VARCHAR(255) NOT NULL COMMENT '参考图',
    position_index INT NOT NULL COMMENT '参考图提交顺序',
    PRIMARY KEY (image_task_id, reference_id),
    CONSTRAINT fk_image_task_references_task FOREIGN KEY (image_task_id)
        REFERENCES image_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_image_task_references_ref FOREIGN KEY (reference_id)
        REFERENCES image_references (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='图片任务与参考图的多对多关联';

CREATE TABLE video_models (
    id VARCHAR(255) NOT NULL COMMENT '视频模型主键',
    name VARCHAR(160) NOT NULL COMMENT '模型显示名称',
    provider VARCHAR(40) NOT NULL COMMENT '厂商适配器标识',
    base_url VARCHAR(1000) NOT NULL COMMENT '厂商 API 地址',
    api_key_ciphertext VARCHAR(4096) NULL COMMENT '加密 API Key',
    model_name VARCHAR(255) NOT NULL COMMENT '厂商模型 ID',
    enabled BIT(1) NOT NULL COMMENT '是否可用于生成',
    config_json JSON NOT NULL COMMENT '时长、清晰度、比例与厂商扩展参数',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_video_models_enabled (enabled, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='多厂商视频生成模型配置';

CREATE TABLE video_inputs (
    id VARCHAR(255) NOT NULL COMMENT '视频输入素材主键',
    file_id VARCHAR(255) NOT NULL COMMENT '图片/视频输入文件',
    source VARCHAR(32) NOT NULL COMMENT 'upload/fromImage',
    source_task_id VARCHAR(255) NULL COMMENT '来源图片任务',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_video_inputs_source_task (source_task_id),
    CONSTRAINT fk_video_inputs_file FOREIGN KEY (file_id)
        REFERENCES stored_files (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='视频生成首帧、尾帧和上传素材';

CREATE TABLE video_tasks (
    id VARCHAR(255) NOT NULL COMMENT '视频任务主键',
    model_id VARCHAR(255) NULL COMMENT '所用视频模型',
    model_name VARCHAR(160) NOT NULL COMMENT '提交时模型名称',
    prompt TEXT NOT NULL COMMENT '视频生成提示词',
    params_json JSON NOT NULL COMMENT '时长、分辨率、比例等参数',
    input_id VARCHAR(255) NULL COMMENT '首帧或主输入素材',
    end_input_id VARCHAR(255) NULL COMMENT '尾帧素材',
    status VARCHAR(32) NOT NULL COMMENT 'queued/submitting/polling/downloading/success/failed/canceled',
    remote_task_id VARCHAR(255) NULL COMMENT '厂商远端任务号',
    result_json LONGTEXT NULL COMMENT '视频结果、本地路径和抽取尾帧',
    error_message TEXT NULL COMMENT '最近错误信息',
    attempts INT NOT NULL COMMENT '提交次数',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    finished_at DATETIME(6) NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_tasks_remote (remote_task_id),
    KEY idx_video_tasks_status_created (status, created_at),
    CONSTRAINT fk_video_tasks_model FOREIGN KEY (model_id)
        REFERENCES video_models (id) ON DELETE SET NULL,
    CONSTRAINT fk_video_tasks_input FOREIGN KEY (input_id)
        REFERENCES video_inputs (id) ON DELETE SET NULL,
    CONSTRAINT fk_video_tasks_end_input FOREIGN KEY (end_input_id)
        REFERENCES video_inputs (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='异步视频生成任务';

CREATE TABLE material_sources (
    id VARCHAR(255) NOT NULL COMMENT '素材来源主键',
    name VARCHAR(160) NOT NULL COMMENT '来源显示名称',
    source_type VARCHAR(40) NOT NULL COMMENT '来源类型/抓取适配器',
    enabled BIT(1) NOT NULL COMMENT '是否参与素材检索',
    config_json JSON NOT NULL COMMENT '来源地址、抓取参数与标签配置',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_material_sources_enabled (enabled, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='可配置的外部素材来源';

CREATE TABLE material_items (
    id VARCHAR(255) NOT NULL COMMENT '素材条目主键',
    source_id VARCHAR(255) NOT NULL COMMENT '所属素材来源',
    source_item_id VARCHAR(255) NOT NULL COMMENT '来源平台原始条目标识',
    title VARCHAR(500) NOT NULL COMMENT '素材标题',
    description TEXT NULL COMMENT '素材说明',
    media_url VARCHAR(2000) NULL COMMENT '来源媒体 URL',
    file_id VARCHAR(255) NULL COMMENT '已下载到本地的文件',
    tags_json JSON NOT NULL COMMENT '标签列表',
    metadata_json JSON NOT NULL COMMENT '平台原始信息与排序指标',
    fetched_at DATETIME(6) NOT NULL COMMENT '抓取时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_source_item (source_id, source_item_id),
    KEY idx_material_items_fetched (fetched_at),
    CONSTRAINT fk_material_items_source FOREIGN KEY (source_id)
        REFERENCES material_sources (id) ON DELETE CASCADE,
    CONSTRAINT fk_material_items_file FOREIGN KEY (file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='素材检索缓存与本地下载索引';

CREATE TABLE topic_history (
    id VARCHAR(255) NOT NULL COMMENT '选题主键',
    topic VARCHAR(1000) NOT NULL COMMENT '选题文本',
    category VARCHAR(160) NULL COMMENT '选题分类',
    used BIT(1) NOT NULL COMMENT '是否已使用',
    metadata_json JSON NULL COMMENT '来源、热度和生成提示等扩展信息',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    used_at DATETIME(6) NULL COMMENT '标记使用时间',
    PRIMARY KEY (id),
    KEY idx_topic_history_used_created (used, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='内容选题池及使用记录';

CREATE TABLE storyboards (
    id VARCHAR(255) NOT NULL COMMENT '分镜项目主键',
    title VARCHAR(255) NOT NULL COMMENT '分镜项目标题',
    status VARCHAR(32) NOT NULL COMMENT '草稿、生成中或完成状态',
    script_json LONGTEXT NOT NULL COMMENT '脚本模型输出与分镜级设置快照',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_storyboards_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 脚本和分镜项目';

CREATE TABLE storyboard_scenes (
    id VARCHAR(255) NOT NULL COMMENT '分镜场景主键',
    storyboard_id VARCHAR(255) NOT NULL COMMENT '所属分镜项目',
    scene_index INT NOT NULL COMMENT '场景顺序',
    title VARCHAR(255) NULL COMMENT '场景标题',
    prompt TEXT NULL COMMENT '画面生成提示词',
    narration TEXT NULL COMMENT '旁白文本',
    duration_seconds DECIMAL(8,2) NULL COMMENT '场景时长',
    config_json JSON NOT NULL COMMENT '角色、镜头和生成参数快照',
    image_file_id VARCHAR(255) NULL COMMENT '场景图文件',
    video_file_id VARCHAR(255) NULL COMMENT '场景视频文件',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_storyboard_scene_order (storyboard_id, scene_index),
    CONSTRAINT fk_storyboard_scenes_storyboard FOREIGN KEY (storyboard_id)
        REFERENCES storyboards (id) ON DELETE CASCADE,
    CONSTRAINT fk_storyboard_scenes_image FOREIGN KEY (image_file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL,
    CONSTRAINT fk_storyboard_scenes_video FOREIGN KEY (video_file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='分镜场景明细；独立行便于排序、重绘和生成状态管理';

CREATE TABLE publish_records (
    id VARCHAR(255) NOT NULL COMMENT '发布记录主键',
    platform VARCHAR(40) NOT NULL COMMENT '目标发布平台',
    title VARCHAR(500) NULL COMMENT '发布标题',
    description TEXT NULL COMMENT '发布文案',
    file_id VARCHAR(255) NULL COMMENT '发布视频或媒体文件',
    status VARCHAR(32) NOT NULL COMMENT 'draft/publishing/published/failed/deleted',
    remote_work_id VARCHAR(255) NULL COMMENT '平台作品 ID',
    remote_url VARCHAR(2000) NULL COMMENT '平台作品链接',
    published_at DATETIME(6) NULL COMMENT '发布时间',
    metadata_json JSON NOT NULL COMMENT '标签、账号和平台回执等字段',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_publish_records_status_created (status, created_at),
    KEY idx_publish_records_remote (platform, remote_work_id),
    CONSTRAINT fk_publish_records_file FOREIGN KEY (file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='发布草稿、作品和删除状态记录';

CREATE TABLE topaz_jobs (
    id VARCHAR(255) NOT NULL COMMENT '视频增强任务主键',
    source_file_id VARCHAR(255) NOT NULL COMMENT '待增强源视频',
    output_file_id VARCHAR(255) NULL COMMENT '增强后的结果文件',
    status VARCHAR(32) NOT NULL COMMENT 'queued/running/success/failed/canceled',
    preset VARCHAR(160) NULL COMMENT 'Topaz 模型或预设',
    progress DECIMAL(5,2) NULL COMMENT '增强进度百分比',
    config_json JSON NOT NULL COMMENT '增强参数快照',
    error_message TEXT NULL COMMENT '错误详情',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    finished_at DATETIME(6) NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    KEY idx_topaz_jobs_status_created (status, created_at),
    CONSTRAINT fk_topaz_jobs_source FOREIGN KEY (source_file_id)
        REFERENCES stored_files (id) ON DELETE RESTRICT,
    CONSTRAINT fk_topaz_jobs_output FOREIGN KEY (output_file_id)
        REFERENCES stored_files (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Topaz 本地视频增强任务';
