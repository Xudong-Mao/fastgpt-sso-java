package org.example.service;

/**
 * SSO 认证成功但不允许登录 FastGPT（账号未映射、已停用等），message 直接展示给用户
 */
public class SsoLoginDeniedException extends RuntimeException {

    public SsoLoginDeniedException(String message) {
        super(message);
    }
}
