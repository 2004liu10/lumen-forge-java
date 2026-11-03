package com.lumenforge.controller.web;

import com.lumenforge.service.web.WebService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页平台与网页账号 HTTP 模块。
 *
 * <p>对应 Python 版 {@code app/api/web.py}，11 条路由逐字符对齐。这里管的是
 * 「用浏览器登录网页版 AI（豆包 / 千问 / 元宝…）来生成」的那套账号，
 * 跟 RunningHub 的 API Key 账号池完全独立：那边按并发派单，这边按每日额度轮换。
 *
 * <p>「平台不存在」「账号不存在」在 Python 版是 404，而全局异常处理器只把
 * IllegalArgumentException 翻成 400，所以这几个路由自己在控制器里产出 404。
 */
@RestController
@RequestMapping("/api")
public class WebController {

    private final WebService service;

    public WebController(WebService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ 平台

    @GetMapping("/web-providers")
    public Map<String, Object> listProviders() {
        return service.listProviders();
    }

    @PostMapping("/web-providers")
    public Map<String, Object> upsertProvider(@RequestBody(required = false) Map<String, Object> body) {
        return service.upsertProvider(body);
    }

    @DeleteMapping("/web-providers/{provider_id}")
    public ResponseEntity<Map<String, Object>> deleteProvider(@PathVariable("provider_id") String providerId) {
        if (!service.deleteProvider(providerId)) {
            return notFound("平台不存在");
        }
        return ResponseEntity.ok(ok());
    }

    @PostMapping("/web-providers/reset")
    public Map<String, Object> resetProviders() {
        return service.resetProviders();
    }

    // ------------------------------------------------------------ 账号

    @GetMapping("/web-accounts")
    public Map<String, Object> listAccounts() {
        return service.listAccounts();
    }

    @PostMapping("/web-accounts")
    public Map<String, Object> createAccount(@RequestBody(required = false) Map<String, Object> body) {
        return service.createAccount(body);
    }

    @PatchMapping("/web-accounts/{account_id}")
    public ResponseEntity<Map<String, Object>> patchAccount(@PathVariable("account_id") String accountId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.patchAccount(accountId, body));
        } catch (IllegalArgumentException exception) {
            return notFound(exception.getMessage());
        }
    }

    @DeleteMapping("/web-accounts/{account_id}")
    public ResponseEntity<Map<String, Object>> deleteAccount(@PathVariable("account_id") String accountId) {
        try {
            service.deleteAccount(accountId);
        } catch (IllegalArgumentException exception) {
            return notFound(exception.getMessage());
        }
        return ResponseEntity.ok(ok());
    }

    @PostMapping("/web-accounts/pick")
    public Map<String, Object> pickAccount(@RequestBody(required = false) Map<String, Object> body) {
        return service.pickAccount(body);
    }

    @PostMapping("/web-accounts/{account_id}/commit")
    public ResponseEntity<Map<String, Object>> commitUsage(@PathVariable("account_id") String accountId,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.commitUsage(accountId, body));
        } catch (IllegalArgumentException exception) {
            return notFound(exception.getMessage());
        }
    }

    @PostMapping("/web-accounts/{account_id}/fail")
    public ResponseEntity<Map<String, Object>> failUsage(@PathVariable("account_id") String accountId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.failUsage(accountId, body));
        } catch (IllegalArgumentException exception) {
            return notFound(exception.getMessage());
        }
    }

    // ------------------------------------------------------------ 响应小工具

    /** Python 版 {@code ok()}：{@code {"ok": true}}。 */
    private Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", Boolean.TRUE);
        return body;
    }

    /** Python 版 {@code not_found(message)}：404 + {@code {"error": message}}。 */
    private ResponseEntity<Map<String, Object>> notFound(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
