package com.gudesk.host.session;

import com.gudesk.common.proto.GuDeskProto.AuthorizeRequest;
import com.gudesk.common.proto.GuDeskProto.AuthorizeResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 授权确认抽象：密码验证通过后，由实现方决定是否允许主控端控制本机。
 *
 * <p>实现方在 {@link AuthorizeRequest} 中获得主控端来源（viewer_id）与
 * 长期身份公钥指纹（viewer_fingerprint，未提供身份密钥时为空串），
 * 以 {@link AuthorizeResponse} 异步返回结果：
 * <ul>
 *   <li>{@code accepted=false}：拒绝（reason 由 HostSession 统一映射，如"用户拒绝授权"）；</li>
 *   <li>{@code accepted=true}：接受，{@code always_trust=true} 时被控端将指纹写入
 *       {@link TrustStore}，下次连接免确认。</li>
 * </ul>
 *
 * <p>实现要求：不得阻塞调用线程（HostSession 在 IO 事件循环上调用）；
 * 未在约定时间内完成的 future 由 HostSession 以超时拒绝兜底（默认 30s）。
 */
public interface Authorizer {

    /**
     * 请求授权确认。
     *
     * @param request 主控端信息（来源地址 + 身份指纹）
     * @return 授权结果 future（异常完成视为拒绝）
     */
    CompletableFuture<AuthorizeResponse> requestAuthorization(AuthorizeRequest request);
}
