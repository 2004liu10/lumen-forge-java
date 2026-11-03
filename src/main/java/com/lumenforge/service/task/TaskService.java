package com.lumenforge.service.task;

import java.util.List;
import java.util.Map;

/** 任务业务能力接口：集中声明任务编排用例，避免控制器依赖具体实现。 */
public interface TaskService {
    Map<String, Object> create(String canvasId, String pinnedAccountId);
    Map<String, Object> createAll();
    List<Map<String, Object>> list(String canvasId, String accountId, int requestedLimit);
    Map<String, Object> cancel(String id);
    Map<String, Object> retry(String id);
    Map<String, Object> refreshResult(String id);
    Map<String, Object> importRemoteTask(String remoteTaskId, String accountId, String canvasId);
    Map<String, Object> fetchMissingResults();
    Map<String, Object> delete(String id);
}



