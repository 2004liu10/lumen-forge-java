package com.lumenforge.service.account;

import com.lumenforge.entity.canvas.Canvas;
import com.lumenforge.mapper.canvas.CanvasMapper;
import com.lumenforge.entity.account.Account;
import com.lumenforge.security.CredentialCipher;
import com.lumenforge.mapper.account.AccountMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 账号业务模块：维护账号生命周期，并适配 RunningHub 密钥校验与余额查询。 */
@Service
@Transactional
public class AccountServiceImpl implements AccountService {
    private static final long QUOTA_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private final AccountMapper accounts;
    private final CanvasMapper canvases;
    private final CredentialCipher cipher;
    private final RestTemplate restTemplate;

    public AccountServiceImpl(AccountMapper accounts, CanvasMapper canvases,
                          CredentialCipher cipher, RestTemplateBuilder restTemplateBuilder) {
        this.accounts = accounts;
        this.canvases = canvases;
        this.cipher = cipher;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Account account : accounts.findAll()) result.add(snapshot(account));
        return result;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String name = string(body.get("name")).trim();
        String apiKey = string(body.get("apiKey")).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请填写账号名称");
        if (apiKey.isEmpty()) throw new IllegalArgumentException("请填写 API Key");
        Account account = new Account(name, cipher.encrypt(apiKey), site(body.get("site")),
                Math.max(1, integer(body.get("concurrency"), 1)), string(body.get("remark")),
                nonNegativeInteger(body.get("dailyLimit"), 1),
                cipher.encrypt(string(body.get("loginName")).trim()), cipher.encrypt(string(body.get("loginPass"))));
        return snapshot(accounts.save(account));
    }

    public Map<String, Object> update(String id, Map<String, Object> body) {
        Account account = require(id);
        if (body.containsKey("name")) {
            String name = string(body.get("name")).trim();
            if (!name.isEmpty()) account.setName(name);
        }
        if (body.containsKey("apiKey")) {
            String apiKey = string(body.get("apiKey")).trim();
            if (!apiKey.isEmpty()) {
                account.setApiKeyCiphertext(cipher.encrypt(apiKey));
                account.clearBalance();
            }
        }
        if (body.containsKey("site")) {
            String nextSite = site(body.get("site"));
            if (!nextSite.equals(account.getSite())) {
                account.setSite(nextSite);
                account.clearBalance();
            }
        }
        if (body.containsKey("concurrency")) account.setConcurrency(Math.max(1, integer(body.get("concurrency"), 1)));
        if (body.containsKey("enabled")) account.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        if (body.containsKey("dailyLimit")) account.setDailyLimit(nonNegativeInteger(body.get("dailyLimit"), account.getDailyLimit()));
        if (body.containsKey("remark")) account.setRemark(string(body.get("remark")));
        if (body.containsKey("loginName")) account.setLoginNameCiphertext(cipher.encrypt(string(body.get("loginName")).trim()));
        if (body.containsKey("loginPass")) account.setLoginPassCiphertext(cipher.encrypt(string(body.get("loginPass"))));
        if (body.containsKey("loginAt")) account.setLoginAt(nullableLong(body.get("loginAt")));
        if (body.containsKey("loginExpiresAt")) account.setLoginExpiresAt(nullableLong(body.get("loginExpiresAt")));
        if (body.containsKey("claimAt")) account.setClaimAt(nullableLong(body.get("claimAt")));
        if (body.containsKey("claimGain")) account.setClaimGain(nullableLong(body.get("claimGain")));
        if (body.containsKey("claimNote")) account.setClaimNote(string(body.get("claimNote")));
        return snapshot(accounts.save(account));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> credentials(String id) {
        Account account = require(id);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("apiKey", cipher.decrypt(account.getApiKeyCiphertext()));
        result.put("loginName", cipher.decrypt(account.getLoginNameCiphertext()));
        result.put("loginPass", cipher.decrypt(account.getLoginPassCiphertext()));
        return result;
    }

    @Override
    public Map<String, Object> delete(String id) {
        Account account = require(id);
        for (Canvas canvas : canvases.findByAccountId(id)) canvas.setAccountId(null);
        accounts.delete(account);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    public Map<String, Object> test(String id) {
        Account account = require(id);
        String message;
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("apiKey", cipher.decrypt(account.getApiKeyCiphertext()));
            payload.put("taskId", "0");
            Map response = post(account, "/task/openapi/outputs", payload);
            Object rawCode = response.get("code");
            String rawMessage = string(response.get("msg"));
            boolean invalid = String.valueOf(rawCode).equals("1002") || rawMessage.matches("(?is).*(api\\s*key|apikey|key\\s*无效|invalid).*");
            message = invalid ? "密钥无效：" + rawMessage : "密钥可用（平台返回：" + (rawMessage.isEmpty() ? "无" : rawMessage) + "）";
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", !invalid);
            result.put("level", invalid ? "error" : "ok");
            result.put("message", message);
            return result;
        } catch (Exception exception) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", false);
            result.put("level", "error");
            result.put("message", "连接失败：" + exception.getMessage());
            return result;
        }
    }

    public Map<String, Object> refreshBalance(String id) {
        Account account = require(id);
        if (account.getApiKeyCiphertext() == null || account.getApiKeyCiphertext().isEmpty()) {
            throw new IllegalArgumentException("这个账号还没有填 API Key");
        }
        Map<String, Object> balance = fetchBalance(account);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("balance", balance);
        result.put("account", snapshot(account));
        return result;
    }

    public Map<String, Object> refreshAllBalances() {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (Account account : accounts.findAll()) {
            if (account.getApiKeyCiphertext() == null || account.getApiKeyCiphertext().isEmpty()) continue;
            Map<String, Object> balance = fetchBalance(account);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", account.getId());
            item.put("name", account.getName());
            item.put("remainCoins", balance.get("remainCoins"));
            item.put("error", balance.get("error"));
            results.add(item);
        }
        return Collections.<String, Object>singletonMap("results", results);
    }

    private Map<String, Object> fetchBalance(Account account) {
        Map<String, Object> data;
        String error = "";
        try {
            String key = cipher.decrypt(account.getApiKeyCiphertext());
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("apiKey", key);
            payload.put("apikey", key);
            Map response = post(account, "/uc/openapi/accountStatus", payload);
            if (!"0".equals(String.valueOf(response.get("code")))) {
                throw new IllegalStateException(string(response.get("msg")));
            }
            Object raw = response.get("data");
            data = raw instanceof Map ? (Map<String, Object>) raw : Collections.<String, Object>emptyMap();
        } catch (Exception exception) {
            data = Collections.emptyMap();
            error = exception.getMessage() == null ? "RunningHub request failed" : exception.getMessage();
        }
        Double coins = number(data.get("remainCoins"));
        Double money = number(data.get("remainMoney"));
        Integer tasks = integerOrNull(data.get("currentTaskCounts"));
        account.updateBalance(coins, money, string(data.get("currency")), string(data.get("apiType")), tasks, error);
        accounts.save(account);
        Map<String, Object> balance = new LinkedHashMap<String, Object>();
        balance.put("remainCoins", coins);
        balance.put("remainMoney", money);
        balance.put("currency", string(data.get("currency")));
        balance.put("apiType", string(data.get("apiType")));
        balance.put("currentTaskCounts", tasks);
        balance.put("updatedAt", account.getBalanceUpdatedAt());
        balance.put("error", error);
        return balance;
    }

    private Map post(Account account, String path, Map<String, Object> payload) {
        String base = "ai".equals(account.getSite()) ? "https://www.runninghub.ai" : "https://www.runninghub.cn";
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cipher.decrypt(account.getApiKeyCiphertext()));
        return restTemplate.postForObject(base + path, new HttpEntity<Map<String, Object>>(payload, headers), Map.class);
    }

    private Account require(String id) {
        return accounts.findById(id).orElseThrow(() -> new IllegalArgumentException("账号不存在"));
    }

    private Map<String, Object> snapshot(Account account) {
        long now = System.currentTimeMillis();
        Long resetAt = null;
        int quotaUsed = account.getQuotaUsed();
        Long windowStart = account.getQuotaWindowStart();
        if (windowStart != null && now - windowStart >= QUOTA_WINDOW_MS) {
            quotaUsed = 0;
            windowStart = null;
            account.setQuotaUsed(0);
            account.setQuotaWindowStart(null);
        }
        Integer limit = account.getDailyLimit() == 0 ? null : account.getDailyLimit();
        Integer left = limit == null ? null : Math.max(0, limit - quotaUsed);
        if (left != null && left == 0 && windowStart != null) resetAt = windowStart + QUOTA_WINDOW_MS;

        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("total", account.getTotalTasks() == null ? 0L : account.getTotalTasks());
        stats.put("success", account.getSuccessfulTasks() == null ? 0L : account.getSuccessfulTasks());
        stats.put("failed", account.getFailedTasks() == null ? 0L : account.getFailedTasks());
        Map<String, Object> balance = null;
        if (account.getBalanceUpdatedAt() != null) {
            balance = new LinkedHashMap<String, Object>();
            balance.put("remainCoins", account.getRemainCoins());
            balance.put("remainMoney", account.getRemainMoney());
            balance.put("currency", account.getCurrency());
            balance.put("apiType", account.getApiType());
            balance.put("currentTaskCounts", account.getCurrentTaskCounts());
            balance.put("updatedAt", account.getBalanceUpdatedAt());
            balance.put("error", account.getBalanceError() == null ? "" : account.getBalanceError());
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", account.getId());
        result.put("name", account.getName());
        result.put("apiKey", mask(cipher.decrypt(account.getApiKeyCiphertext())));
        result.put("hasKey", account.getApiKeyCiphertext() != null && !account.getApiKeyCiphertext().isEmpty());
        result.put("site", account.getSite());
        result.put("concurrency", account.getConcurrency());
        result.put("enabled", account.isEnabled());
        result.put("remark", account.getRemark());
        result.put("dailyLimit", account.getDailyLimit());
        result.put("quotaUsed", quotaUsed);
        result.put("quotaWindowStart", windowStart);
        result.put("stats", stats);
        result.put("loginAt", account.getLoginAt());
        result.put("loginExpiresAt", account.getLoginExpiresAt());
        result.put("claimAt", account.getClaimAt());
        result.put("claimGain", account.getClaimGain());
        result.put("claimNote", account.getClaimNote());
        result.put("createdAt", account.getCreatedAt() == null ? null : account.getCreatedAt().toEpochMilli());
        result.put("balance", balance);
        result.put("running", 0);
        result.put("capacity", Math.max(1, account.getConcurrency()));
        result.put("quotaLimit", limit);
        result.put("quotaLeft", left);
        result.put("quotaResetAt", resetAt);
        return result;
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String site(Object value) { return "ai".equals(String.valueOf(value)) ? "ai" : "cn"; }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException exception) { return fallback; }
    }
    private int nonNegativeInteger(Object value, int fallback) { return Math.max(0, integer(value, fallback)); }
    private Long nullableLong(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try { return (long) Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }
    private Double number(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try { return Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }
    private Integer integerOrNull(Object value) {
        Double parsed = number(value);
        return parsed == null ? null : parsed.intValue();
    }
}




