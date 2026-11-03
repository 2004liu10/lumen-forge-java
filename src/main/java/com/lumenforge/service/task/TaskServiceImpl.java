package com.lumenforge.service.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumenforge.entity.account.Account;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.mapper.account.AccountMapper;
import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.mapper.canvas.CanvasMapper;
import com.lumenforge.service.canvas.CanvasService;
import com.lumenforge.entity.task.GenerationTask;
import com.lumenforge.mapper.task.GenerationTaskMapper;
import com.lumenforge.provider.RunningHubClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生成任务编排模块：负责保存任务状态、分配账号、提交 RunningHub 并轮询结果。
 * 网络请求期间调度器不持有数据库事务；每个状态变化单独落库，降低长请求锁表风险。
 */
@Service
@Transactional
public class TaskServiceImpl implements TaskService {
    private static final Set<String> ACTIVE = new java.util.HashSet<String>(Arrays.asList("preparing", "remote_queued", "running"));
    private static final long QUOTA_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private final GenerationTaskMapper tasks;
    private final AccountMapper accounts;
    private final CanvasService canvases;
    private final CredentialCipher cipher;
    private final RunningHubClient runningHub;
    private final ObjectMapper objectMapper;
    private final org.springframework.web.client.RestTemplate restTemplate;
    private final AtomicBoolean ticking = new AtomicBoolean(false);
    private final String storageRoot;

    public TaskServiceImpl(GenerationTaskMapper tasks, AccountMapper accounts, CanvasService canvases,
                       CredentialCipher cipher, RunningHubClient runningHub, ObjectMapper objectMapper,
                       org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder,
                       @Value("${app.storage.root:./data}") String storageRoot) {
        this.tasks = tasks;
        this.accounts = accounts;
        this.canvases = canvases;
        this.cipher = cipher;
        this.runningHub = runningHub;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(java.time.Duration.ofSeconds(15))
                .setReadTimeout(java.time.Duration.ofMinutes(5))
                .build();
        this.storageRoot = storageRoot;
    }

    /** 建立不可变的画布参数快照，后续画布编辑不影响这条任务。 */
    public Map<String, Object> create(String canvasId, String pinnedAccountId) {
        Map<String, Object> canvas = canvases.view(canvases.require(canvasId));
        String appId = string(canvas.get("appId"));
        List<Map<String, Object>> fields = listOfMaps(canvas.get("fields"));
        if (appId.isEmpty()) throw new IllegalArgumentException("请先填写 webappId / workflowId 并读取参数");
        if (fields.isEmpty()) throw new IllegalArgumentException("没有可提交的参数，请先读取应用或工作流参数");
        boolean autoRetry = !Boolean.FALSE.equals(canvas.get("autoRetry"));
        int maxAttempts = autoRetry ? Math.max(1, integer(canvas.get("maxAttempts"), 2)) : 1;
        boolean explicitlyPinned = pinnedAccountId != null && !pinnedAccountId.trim().isEmpty();
        String pin = explicitlyPinned ? pinnedAccountId.trim() : string(canvas.get("accountId")).trim();
        if (!pin.isEmpty() && !accounts.existsById(pin)) {
            // 账号可能在另一个页面被删除、而浏览器仍提交旧选择；自动调度比让 FK 拒绝整个任务更稳妥。
            if (pin.equals(string(canvas.get("accountId")).trim())) {
                Map<String, Object> clearBinding = new LinkedHashMap<String, Object>();
                clearBinding.put("accountId", "");
                canvases.update(canvasId, clearBinding);
            }
            pin = "";
        }
        GenerationTask task = new GenerationTask(canvasId, string(canvas.get("name")),
                string(canvas.get("mode")), appId, write(canvas), pin, maxAttempts);
        addLog(task, "任务已加入队列");
        return view(tasks.save(task));
    }

    public Map<String, Object> createAll() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> created = new ArrayList<Map<String, Object>>();
        List<String> errors = new ArrayList<String>();
        for (Map<String, Object> canvas : canvases.list()) {
            try { created.add(create(string(canvas.get("id")), string(canvas.get("accountId")))); }
            catch (Exception exception) { errors.add(string(canvas.get("name")) + "：" + exception.getMessage()); }
        }
        result.put("created", created.size());
        result.put("errors", errors);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String canvasId, String accountId, int requestedLimit) {
        int limit = Math.min(400, Math.max(1, requestedLimit));
        List<GenerationTask> all = tasks.findAll();
        all.sort(Comparator.comparing(GenerationTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (GenerationTask task : all) {
            if (canvasId != null && !canvasId.isEmpty() && !canvasId.equals(task.getCanvasId())) continue;
            if (accountId != null && !accountId.isEmpty() && !accountId.equals(task.getAccountId())) continue;
            result.add(view(task));
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 取消排队任务时直接结束；远端任务则记录取消意图，交给调度器调用平台接口。 */
    public Map<String, Object> cancel(String id) {
        GenerationTask task = require(id);
        if ("queued".equals(task.getStatus())) {
            task.setStatus("canceled");
            task.setMessage("已取消");
            task.setFinishedAt(System.currentTimeMillis());
            addLog(task, "已取消（尚未派发）");
        } else if (ACTIVE.contains(task.getStatus())) {
            task.setCancelRequested(true);
            task.setMessage("取消中…");
            addLog(task, "已请求取消");
        }
        return view(tasks.save(task));
    }

    /** 人工重试会清空本次远端结果并重新排队，保留历史日志便于追踪。 */
    public Map<String, Object> retry(String id) {
        GenerationTask task = require(id);
        if (task.isImported()) throw new IllegalArgumentException("导入任务只支持重查平台结果，不能重新提交");
        if (ACTIVE.contains(task.getStatus()) || "queued".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务仍在进行中");
        }
        task.setStatus("queued");
        task.setMessage("排队中");
        task.setError(null);
        task.setRemoteTaskId(null);
        task.setAccountId(null);
        task.setAccountName(null);
        task.setCancelRequested(false);
        task.setOutputsJson("[]");
        task.setAttempts(0);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setPollErrors(0);
        task.setNextPollAt(null);
        addLog(task, "重新排队");
        return view(tasks.save(task));
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> refreshResult(String id) {
        GenerationTask task = require(id);
        if (task.getRemoteTaskId() == null || task.getRemoteTaskId().isEmpty()) {
            throw new IllegalArgumentException("这个任务没有远端任务号，无法重查");
        }
        Account account = accounts.findById(task.getAccountId()).orElse(null);
        if (account == null) throw new IllegalArgumentException("原账号已删除，无法用它的 Key 查询");
        try {
            String key = cipher.decrypt(account.getApiKeyCiphertext());
            Map<String, Object> result = runningHub.queryOutputs(baseUrl(account, read(task.getCanvasConfigJson())), key, task.getRemoteTaskId());
            applyManualRefresh(task, account, result);
            tasks.save(task);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("task", view(task));
            response.put("state", result.get("state"));
            response.put("error", result.get("error") == null ? "" : result.get("error"));
            return response;
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception.getMessage() == null ? "重查远端任务失败" : exception.getMessage());
        }
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> importRemoteTask(String remoteTaskId, String accountId, String canvasId) {
        String remoteId = remoteTaskId == null ? "" : remoteTaskId.trim();
        if (!remoteId.matches("\\d{6,}")) throw new IllegalArgumentException("请填写平台上的任务号（一串数字）");
        Account account = accounts.findById(accountId == null ? "" : accountId).orElse(null);
        if (account == null) throw new IllegalArgumentException("请选择一个账号，用它的 API Key 查询平台任务");
        for (GenerationTask existing : tasks.findAll()) {
            if (remoteId.equals(existing.getRemoteTaskId())) {
                Map<String, Object> refreshed = refreshResult(existing.getId());
                refreshed.put("reused", true);
                return refreshed;
            }
        }
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        String name = "（手动取回）";
        String taskCanvasId = null;
        if (canvasId != null && !canvasId.trim().isEmpty()) {
            Map<String, Object> canvas = canvases.view(canvases.require(canvasId));
            config.putAll(canvas);
            name = string(canvas.get("name"));
            taskCanvasId = canvasId;
        }
        GenerationTask task = new GenerationTask(taskCanvasId, name, "app", "", write(config), null, 1);
        task.setImported(true);
        task.setAttempts(1);
        task.setAccountId(account.getId());
        task.setAccountName(account.getName());
        task.setRemoteTaskId(remoteId);
        task.setStartedAt(System.currentTimeMillis());
        task.setStatus("running");
        task.setMessage("正在向平台取回…");
        addLog(task, "按任务号取回平台任务 " + remoteId + "（账号「" + account.getName() + "」）");
        tasks.save(task);
        Map<String, Object> result = refreshResult(task.getId());
        result.put("reused", false);
        return result;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> fetchMissingResults() {
        List<GenerationTask> candidates = new ArrayList<GenerationTask>();
        for (GenerationTask task : tasks.findAll()) {
            if (task.getRemoteTaskId() != null && !task.getRemoteTaskId().isEmpty() && !outputsOnDisk(task)) candidates.add(task);
        }
        int recovered = 0;
        int stillRunning = 0;
        int failed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<String>();
        for (GenerationTask task : candidates) {
            Account account = accounts.findById(task.getAccountId()).orElse(null);
            if (account == null) {
                skipped++;
                errors.add(task.getId() + "：原账号已删除");
                continue;
            }
            try {
                Map<String, Object> remote = runningHub.queryOutputs(baseUrl(account, read(task.getCanvasConfigJson())),
                        cipher.decrypt(account.getApiKeyCiphertext()), task.getRemoteTaskId());
                String state = string(remote.get("state"));
                if ("success".equals(state)) {
                    List<Map<String, Object>> outputs = downloadOutputs(task, listOfMaps(remote.get("outputs")));
                    task.setOutputsJson(write(outputs));
                    task.setStatus("success");
                    task.setMessage("已完成");
                    task.setError(null);
                    task.setFinishedAt(task.getFinishedAt() == null ? System.currentTimeMillis() : task.getFinishedAt());
                    addLog(task, "一键取回：重新下载 " + outputs.size() + " 个结果");
                    recovered++;
                } else if ("running".equals(state) || "queued".equals(state)) {
                    addLog(task, "一键取回：平台仍在" + ("running".equals(state) ? "生成" : "排队"));
                    stillRunning++;
                } else {
                    if (!"success".equals(task.getStatus())) fail(task, string(remote.get("error")));
                    failed++;
                }
            } catch (Exception exception) {
                errors.add(task.getId() + "：" + exception.getMessage());
                failed++;
            }
            tasks.save(task);
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("folder", "outputs");
        summary.put("total", candidates.size());
        summary.put("recovered", recovered);
        summary.put("stillRunning", stillRunning);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("errors", errors);
        return summary;
    }

    private void applyManualRefresh(GenerationTask task, Account account, Map<String, Object> remote) {
        String state = string(remote.get("state"));
        if ("success".equals(state)) {
            if (!outputsOnDisk(task)) task.setOutputsJson(write(downloadOutputs(task, listOfMaps(remote.get("outputs")))));
            task.setStatus("success");
            task.setMessage("已完成");
            task.setError(null);
            if (task.getFinishedAt() == null) task.setFinishedAt(System.currentTimeMillis());
            addLog(task, "手动重查：平台已完成并取回结果");
        } else if ("running".equals(state)) {
            task.setStatus("running");
            task.setMessage("平台仍在生成中");
            task.setNextPollAt(System.currentTimeMillis());
            addLog(task, "手动重查：平台仍在生成中");
        } else if ("queued".equals(state)) {
            task.setStatus("remote_queued");
            task.setMessage("平台排队中");
            task.setNextPollAt(System.currentTimeMillis());
            addLog(task, "手动重查：平台排队中");
        } else {
            if (!"success".equals(task.getStatus())) fail(task, string(remote.get("error")));
            addLog(task, "手动重查：平台返回失败 —— " + string(remote.get("error")));
        }
    }

    private boolean outputsOnDisk(GenerationTask task) {
        List<Map<String, Object>> outputs = readList(task.getOutputsJson());
        if (outputs.isEmpty()) return false;
        for (Map<String, Object> output : outputs) {
            String path = string(output.get("localPath"));
            if (path.isEmpty() || !new File(path).isFile()) return false;
        }
        return true;
    }

    public Map<String, Object> delete(String id) {
        GenerationTask task = require(id);
        if (ACTIVE.contains(task.getStatus()) || "queued".equals(task.getStatus())) {
            throw new IllegalArgumentException("进行中的任务不能删除，请先取消");
        }
        tasks.delete(task);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /** 定时调度入口：先接管旧任务，再轮询远端任务，最后只派发一个新任务。 */
    @Scheduled(fixedDelayString = "${app.scheduler.delay-ms:2500}")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            recoverInterruptedSubmissions();
            pollRemoteTasks();
            dispatchQueuedTask();
        } catch (Exception exception) {
            System.err.println("[task-scheduler] " + exception.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private void recoverInterruptedSubmissions() {
        for (GenerationTask task : tasks.findAll()) {
            if ("preparing".equals(task.getStatus()) && (task.getRemoteTaskId() == null || task.getRemoteTaskId().isEmpty())) {
                task.setStatus("queued");
                task.setAccountId(null);
                task.setAccountName(null);
                task.setMessage("程序重启后重新排队");
                addLog(task, "程序重启时任务尚未记录远端编号，已重新排队");
                tasks.save(task);
            }
        }
    }

    private void pollRemoteTasks() {
        for (GenerationTask task : tasks.findAll()) {
            if (!("remote_queued".equals(task.getStatus()) || "running".equals(task.getStatus()))) continue;
            if (task.getNextPollAt() != null && task.getNextPollAt() > System.currentTimeMillis()) continue;
            Account account = accounts.findById(task.getAccountId()).orElse(null);
            if (account == null) {
                fail(task, "原账号已不存在，无法查询远端任务");
                continue;
            }
            String key;
            try { key = cipher.decrypt(account.getApiKeyCiphertext()); }
            catch (Exception exception) { fail(task, exception.getMessage()); continue; }
            if (task.isCancelRequested()) {
                try {
                    runningHub.cancelTask(baseUrl(account, task), key, task.getRemoteTaskId());
                    task.setStatus("canceled");
                    task.setMessage("已取消");
                    task.setFinishedAt(System.currentTimeMillis());
                    addLog(task, "已向 RunningHub 请求取消");
                } catch (Exception exception) {
                    task.setNextPollAt(System.currentTimeMillis() + 10000L);
                    addLog(task, "远端取消失败，将重试：" + exception.getMessage());
                }
                tasks.save(task);
                continue;
            }
            try {
                Map<String, Object> result = runningHub.queryOutputs(baseUrl(account, task), key, task.getRemoteTaskId());
                String state = string(result.get("state"));
                if ("queued".equals(state)) {
                    task.setStatus("remote_queued");
                    task.setMessage("已提交，排队中");
                } else if ("running".equals(state)) {
                    task.setStatus("running");
                    task.setMessage("生成中");
                } else if ("success".equals(state)) {
                    List<Map<String, Object>> outputs = downloadOutputs(task, listOfMaps(result.get("outputs")));
                    task.setOutputsJson(write(outputs));
                    task.setStatus("success");
                    task.setMessage("已完成");
                    task.setFinishedAt(System.currentTimeMillis());
                    account.recordTaskOutcome(true);
                    accounts.save(account);
                    addLog(task, "完成，得到 " + outputs.size() + " 个结果");
                } else {
                    String error = string(result.get("error"));
                    account.recordTaskOutcome(false);
                    accounts.save(account);
                    retryOrFail(task, error.isEmpty() ? "RunningHub 任务失败" : error);
                }
                task.setPollErrors(0);
                task.setNextPollAt(System.currentTimeMillis() + 4000L);
            } catch (Exception exception) {
                int errors = task.getPollErrors() + 1;
                task.setPollErrors(errors);
                task.setNextPollAt(System.currentTimeMillis() + Math.min(60000L, errors * 5000L));
                addLog(task, "查询远端结果失败（" + errors + "/10）：" + exception.getMessage());
                if (errors >= 10) fail(task, "连续 10 次查询远端结果失败：" + exception.getMessage());
            }
            // 请求远端期间，用户可能同时点了取消；合并这个标志，避免旧对象覆盖新请求。
            GenerationTask latest = tasks.findById(task.getId()).orElse(task);
            if (latest.isCancelRequested()) task.setCancelRequested(true);
            tasks.save(task);
        }
    }

    private void dispatchQueuedTask() {
        List<GenerationTask> queued = new ArrayList<GenerationTask>();
        for (GenerationTask task : tasks.findAll()) if ("queued".equals(task.getStatus())) queued.add(task);
        queued.sort(Comparator.comparing(GenerationTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (GenerationTask task : queued) {
            Account account = chooseAccount(task);
            if (account == null) {
                task.setMessage("等待可用账号或账号并发空位");
                tasks.save(task);
                continue;
            }
            task.setStatus("preparing");
            task.setMessage("正在准备提交");
            task.setAccountId(account.getId());
            task.setAccountName(account.getName());
            task.setStartedAt(task.getStartedAt() == null ? System.currentTimeMillis() : task.getStartedAt());
            task.setAttempts(task.getAttempts() + 1);
            addLog(task, "账号「" + account.getName() + "」已接单（第 " + task.getAttempts() + " 次尝试）");
            tasks.save(task);
            try {
                String key = cipher.decrypt(account.getApiKeyCiphertext());
                Map<String, Object> config = read(task.getCanvasConfigJson());
                List<Map<String, Object>> nodeInfo = buildNodeInfoList(config, task, account, key);
                String remoteId = runningHub.createTask(baseUrl(account, config), key, task.getMode(), task.getAppId(),
                        string(config.get("instanceType")), nodeInfo);
                // HTTP 调用期间用户可能已发出取消请求；重新读取记录，保留该并发更新。
                GenerationTask latest = tasks.findById(task.getId()).orElse(task);
                latest.setRemoteTaskId(remoteId);
                latest.setStatus("remote_queued");
                latest.setMessage(latest.isCancelRequested() ? "取消中…" : "已提交，排队中");
                latest.setNextPollAt(System.currentTimeMillis());
                account.consumeQuota();
                accounts.save(account);
                addLog(latest, "远端任务已创建：" + remoteId);
                task = latest;
            } catch (Exception exception) {
                String error = exception.getMessage() == null ? "RunningHub 提交失败" : exception.getMessage();
                GenerationTask latest = tasks.findById(task.getId()).orElse(task);
                addLog(latest, "提交失败：" + error);
                retryOrFail(latest, error);
                task = latest;
            }
            tasks.save(task);
            return;
        }
    }

    /** 找到满足额度和并发限制的账号；任务绑定账号时只允许使用该账号。 */
    private Account chooseAccount(GenerationTask task) {
        List<Account> pool = accounts.findAll();
        List<Account> candidates = new ArrayList<Account>();
        for (Account account : pool) {
            if (!account.isEnabled() || account.getApiKeyCiphertext() == null || account.getApiKeyCiphertext().isEmpty()) continue;
            if (task.getPinnedAccountId() != null && !task.getPinnedAccountId().isEmpty()
                    && !task.getPinnedAccountId().equals(account.getId())) continue;
            if (isQuotaBlocked(account)) continue;
            if (activeCount(account.getId()) >= Math.max(1, account.getConcurrency())) continue;
            candidates.add(account);
        }
        candidates.sort(Comparator.comparingInt((Account account) -> activeCount(account.getId()))
                .thenComparing(account -> account.getTotalTasks() == null ? 0L : account.getTotalTasks())
                .thenComparing(Account::getCreatedAt));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean isQuotaBlocked(Account account) {
        if (account.getDailyLimit() <= 0) return false;
        Long start = account.getQuotaWindowStart();
        if (start == null || System.currentTimeMillis() - start >= QUOTA_WINDOW_MS) return false;
        if (account.getQuotaUsed() < account.getDailyLimit()) return false;
        return account.getRemainCoins() == null || account.getRemainCoins() <= 0;
    }

    private int activeCount(String accountId) {
        int count = 0;
        for (GenerationTask task : tasks.findAll()) if (accountId.equals(task.getAccountId()) && ACTIVE.contains(task.getStatus())) count++;
        return count;
    }

    /** 把画布字段与值组装成 RunningHub nodeInfoList，文件先上传再替换成本地文件名。 */
    private List<Map<String, Object>> buildNodeInfoList(Map<String, Object> config, GenerationTask task,
                                                         Account account, String apiKey) {
        List<Map<String, Object>> nodeInfo = new ArrayList<Map<String, Object>>();
        Map<String, Object> values = objectMap(config.get("values"));
        Map<String, Object> assets = objectMap(config.get("assets"));
        for (Map<String, Object> field : listOfMaps(config.get("fields"))) {
            String nodeId = string(field.get("nodeId"));
            String fieldName = string(field.get("fieldName"));
            String fieldType = string(field.get("fieldType")).toUpperCase();
            String key = nodeId + "::" + fieldName;
            Object value = values.get(key);
            if (Arrays.asList("IMAGE", "VIDEO", "AUDIO").contains(fieldType)) {
                Map<String, Object> asset = objectMap(assets.get(key));
                String localPath = string(asset.get("localPath"));
                if (!localPath.isEmpty()) {
                    String remoteName = runningHub.uploadFile(baseUrl(account, config), apiKey,
                            new File(localPath), string(asset.get("mimeType")));
                    value = remoteName;
                } else if (value == null || string(value).isEmpty()) continue;
            } else if (value == null || string(value).isEmpty()) continue;
            // 旧画布快照可能仍把 VHS 数值输入标记为 STRING；提交时再次按字段语义纠正，兼容已保存任务。
            if ("NUMBER".equals(fieldType) || isKnownNumericInput(field.get("nodeName"), fieldName)) {
                try { value = Double.valueOf(String.valueOf(value)); }
                catch (NumberFormatException exception) { throw new IllegalArgumentException("参数「" + fieldName + "」必须是数字"); }
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("nodeId", nodeId);
            item.put("fieldName", fieldName);
            item.put("fieldValue", value);
            if (field.get("description") != null && !string(field.get("description")).isEmpty()) item.put("description", field.get("description"));
            nodeInfo.add(item);
        }
        String extra = string(config.get("extraParams"));
        if (!extra.trim().isEmpty()) {
            try {
                Object parsed = objectMapper.readValue(extra, Object.class);
                if (parsed instanceof List) {
                    for (Object item : (List<?>) parsed) {
                        Map<String, Object> value = objectMap(item);
                        if (value.containsKey("nodeId") && value.containsKey("fieldName")) nodeInfo.add(value);
                    }
                }
            } catch (Exception exception) {
                addLog(task, "额外参数 JSON 解析失败，已忽略");
            }
        }
        if (nodeInfo.isEmpty()) throw new IllegalArgumentException("没有可提交的参数，请先填写画布参数或上传素材");
        return nodeInfo;
    }

    /** 识别工作流中常见的数字输入，尤其是 VHS_LoadVideo.force_rate。 */
    private boolean isKnownNumericInput(Object nodeNameValue, String fieldName) {
        String nodeName = string(nodeNameValue).toLowerCase();
        String name = fieldName == null ? "" : fieldName.toLowerCase();
        if (nodeName.contains("int") || nodeName.contains("float") || nodeName.contains("number")) return true;
        return Arrays.asList("force_rate", "custom_width", "custom_height", "frame_load_cap",
                "skip_first_frames", "select_every_nth", "width", "height", "fps", "steps", "cfg",
                "seed", "denoise", "scale", "batch_size", "frame_rate", "duration", "strength",
                "threshold", "ratio", "count", "size").contains(name);
    }

    /** 将远端结果流式写入磁盘，避免把大视频完整读入 JVM 内存。 */
    private List<Map<String, Object>> downloadOutputs(GenerationTask task, List<Map<String, Object>> remoteOutputs) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        java.nio.file.Path outputDir = java.nio.file.Paths.get(storageRoot, "outputs").toAbsolutePath().normalize();
        try { java.nio.file.Files.createDirectories(outputDir); }
        catch (Exception exception) { throw new IllegalStateException("无法创建输出目录：" + exception.getMessage()); }
        int index = 0;
        for (Map<String, Object> remote : remoteOutputs) {
            index++;
            String url = string(remote.get("fileUrl"));
            if (url.isEmpty()) continue;
            String ext = extension(string(remote.get("fileType")), url);
            String fileName = task.getId() + "_" + index + ext;
            java.nio.file.Path destination = outputDir.resolve(fileName).normalize();
            if (!destination.startsWith(outputDir)) throw new IllegalArgumentException("非法输出文件名");
            try {
                restTemplate.execute(url, org.springframework.http.HttpMethod.GET, null, response -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("下载结果失败：HTTP " + response.getRawStatusCode());
                    java.nio.file.Files.copy(response.getBody(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return null;
                });
                Map<String, Object> output = new LinkedHashMap<String, Object>(remote);
                output.put("localUrl", "/files/outputs/" + fileName);
                output.put("localPath", destination.toString());
                output.put("size", java.nio.file.Files.size(destination));
                saved.add(output);
            } catch (Exception exception) {
                Map<String, Object> output = new LinkedHashMap<String, Object>(remote);
                output.put("downloadError", exception.getMessage());
                saved.add(output);
            }
        }
        return saved;
    }

    private void retryOrFail(GenerationTask task, String error) {
        task.setError(error);
        if (task.getAttempts() < task.getMaxAttempts() && !task.isCancelRequested()) {
            task.setStatus("queued");
            task.setMessage("本次失败，准备重试：" + error);
            task.setAccountId(null);
            task.setAccountName(null);
            task.setRemoteTaskId(null);
            task.setNextPollAt(null);
            task.setPollErrors(0);
        } else fail(task, error);
    }

    private void fail(GenerationTask task, String error) {
        task.setStatus(task.isCancelRequested() ? "canceled" : "failed");
        task.setMessage(task.isCancelRequested() ? "已取消" : error);
        task.setError(error);
        task.setFinishedAt(System.currentTimeMillis());
        addLog(task, "失败：" + error);
    }

    private Map<String, Object> view(GenerationTask task) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", task.getId());
        result.put("canvasId", task.getCanvasId());
        result.put("canvasName", task.getCanvasName());
        result.put("mode", task.getMode());
        result.put("appId", task.getAppId());
        result.put("status", task.getStatus());
        result.put("message", task.getMessage());
        result.put("attempts", task.getAttempts());
        result.put("maxAttempts", task.getMaxAttempts());
        result.put("accountId", task.getAccountId());
        result.put("accountName", task.getAccountName());
        result.put("pinnedAccountId", task.getPinnedAccountId());
        result.put("remoteTaskId", task.getRemoteTaskId());
        result.put("outputs", readList(task.getOutputsJson()));
        result.put("error", task.getError());
        result.put("log", readList(task.getLogJson()));
        result.put("createdAt", task.getCreatedAt());
        result.put("startedAt", task.getStartedAt());
        result.put("finishedAt", task.getFinishedAt());
        result.put("cancelRequested", task.isCancelRequested());
        return result;
    }

    private void addLog(GenerationTask task, String message) {
        List<Map<String, Object>> log = readList(task.getLogJson());
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("t", System.currentTimeMillis());
        entry.put("text", message);
        log.add(entry);
        if (log.size() > 120) log = new ArrayList<Map<String, Object>>(log.subList(log.size() - 120, log.size()));
        task.setLogJson(write(log));
    }

    private GenerationTask require(String id) {
        return tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }

    private String baseUrl(Account account, GenerationTask task) {
        Map<String, Object> config = read(task.getCanvasConfigJson());
        return baseUrl(account, config);
    }

    private String baseUrl(Account account, Map<String, Object> config) {
        return "ai".equals(account.getSite()) ? "https://www.runninghub.ai" : string(config.get("baseUrl"));
    }

    private String extension(String fileType, String url) {
        String type = fileType.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (Arrays.asList("png", "jpg", "jpeg", "webp", "gif", "bmp", "mp4", "mov", "webm", "mkv", "avi", "m4v", "mp3", "wav", "m4a", "ogg").contains(type)) return "." + type;
        try {
            String path = java.net.URI.create(url).getPath();
            String ext = path.substring(path.lastIndexOf('/') + 1);
            int dot = ext.lastIndexOf('.');
            if (dot >= 0) return ext.substring(dot).replaceAll("[^.a-zA-Z0-9]", "");
        } catch (Exception ignored) { }
        return ".bin";
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException exception) { return fallback; }
    }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize task state", exception); }
    }
    private Map<String, Object> read(String json) {
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception exception) { return new LinkedHashMap<String, Object>(); }
    }
    private List<Map<String, Object>> readList(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception exception) { return new ArrayList<Map<String, Object>>(); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : new LinkedHashMap<String, Object>();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (value instanceof List) for (Object item : (List<?>) value) if (item instanceof Map) result.add((Map<String, Object>) item);
        return result;
    }
}




