package com.lumenforge.service.account;

import java.util.List;
import java.util.Map;

/**
 * 账号业务能力接口：隔离 HTTP 控制层与业务实现，便于后续替换实现或添加事务装饰。
 */
public interface AccountService {
    List<Map<String, Object>> list();
    Map<String, Object> create(Map<String, Object> body);
    Map<String, Object> update(String id, Map<String, Object> body);
    Map<String, Object> credentials(String id);
    Map<String, Object> delete(String id);
    Map<String, Object> test(String id);
    Map<String, Object> refreshBalance(String id);
    Map<String, Object> refreshAllBalances();
}



