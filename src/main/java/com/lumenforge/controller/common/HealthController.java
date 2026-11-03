package com.lumenforge.controller.common;

import com.lumenforge.common.api.ApiResponse;
import com.lumenforge.security.CredentialCipher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final CredentialCipher credentialCipher;

    public HealthController(CredentialCipher credentialCipher) {
        this.credentialCipher = credentialCipher;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> status = new HashMap<String, String>();
        status.put("status", "UP");
        status.put("service", "lumen-forge-java");
        status.put("credentialEncryptionConfigured", String.valueOf(credentialCipher.isConfigured()));
        return ApiResponse.ok(status);
    }
}




