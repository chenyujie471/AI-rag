package com.example.airag.auth.dto;

import com.example.airag.quota.QuotaInfo;

/**
 * 登录成功的返回体。
 *
 * @param redirectUrl 登录后跳转地址:配置了 app.auth.redirect-url 则为该值,否则为 /faq.html
 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserProfile user, QuotaInfo quota,
                            String redirectUrl) {
}
