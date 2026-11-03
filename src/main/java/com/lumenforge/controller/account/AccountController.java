package com.lumenforge.controller.account;

import com.lumenforge.service.account.AccountService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list() { return service.list(); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(body);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return service.update(id, body);
    }

    @GetMapping("/{id}/credentials")
    public Map<String, Object> credentials(@PathVariable String id) {
        return service.credentials(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) { return service.delete(id); }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) { return service.test(id); }

    @PostMapping("/refresh-balance")
    public Map<String, Object> refreshAllBalances() { return service.refreshAllBalances(); }

    @PostMapping("/{id}/balance")
    public Map<String, Object> refreshBalance(@PathVariable String id) { return service.refreshBalance(id); }
}




