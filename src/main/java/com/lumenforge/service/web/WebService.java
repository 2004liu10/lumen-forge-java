package com.lumenforge.service.web;

import java.util.List;
import java.util.Map;

/**
 * 网页平台（Web Provider）与网页账号（多账户额度池）的业务入口。
 *
 * <p>两块东西是配套的：平台是一份「怎么在这个网页上生成」的声明式配置，
 * 账号是这份配置下的一批 cookie 型登录态 + 每日额度账本。与 RunningHub 的
 * API Key 账号池完全独立。
 */
public interface WebService {

    /** GET /api/web-providers：内置清单兜底 seed 之后，回全部平台（每项带 partition）。 */
    Map<String, Object> listProviders();

    /** POST /api/web-providers：新增或更新；返回 {@code {provider, created}}。 */
    Map<String, Object> upsertProvider(Map<String, Object> body);

    /** DELETE /api/web-providers/{provider_id}：按 id 或 slug 删；不存在回 false。 */
    boolean deleteProvider(String providerId);

    /** POST /api/web-providers/reset：只补库里没有的内置平台，返回补了几家。 */
    Map<String, Object> resetProviders();

    /** GET /api/web-accounts：全部账号快照（含算出来的剩余额度 / 刷新时刻 / 登录态）。 */
    Map<String, Object> listAccounts();

    /** POST /api/web-accounts：新建账号；平台不存在 / 名字为空都报 400。 */
    Map<String, Object> createAccount(Map<String, Object> body);

    /** PATCH /api/web-accounts/{account_id}：局部改；账号不存在抛 IllegalArgumentException。 */
    Map<String, Object> patchAccount(String accountId, Map<String, Object> body);

    /** DELETE /api/web-accounts/{account_id}：账号不存在抛 IllegalArgumentException。 */
    void deleteAccount(String accountId);

    /** POST /api/web-accounts/pick：挑一个「还有额度 + 登录态有效」的账号。 */
    Map<String, Object> pickAccount(Map<String, Object> body);

    /** POST /api/web-accounts/{account_id}/commit：扣一次额度。 */
    Map<String, Object> commitUsage(String accountId, Map<String, Object> body);

    /** POST /api/web-accounts/{account_id}/fail：记一次失败（不扣额度）。 */
    Map<String, Object> failUsage(String accountId, Map<String, Object> body);
}
