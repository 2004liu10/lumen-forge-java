package com.lumenforge.security;

/** 缺少本地凭据加密主密钥时抛出，便于 API 给出可操作的配置提示。 */
public class CredentialKeyNotConfiguredException extends IllegalStateException {
    public CredentialKeyNotConfiguredException() {
        super("APP_CREDENTIAL_KEY is not configured");
    }
}
