package org.example.service;

import org.example.dto.UserInfo;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * SSO 提供者服务接口
 */
public interface SsoProviderService {
    
    /**
     * 获取认证 URL
     */
    String getAuthUrl(HttpServletRequest request, String redirectUri, String state);
    
    /**
     * 处理回调
     */
    String handleCallback(HttpServletRequest request);
    
    /**
     * 获取用户信息
     */
    UserInfo getUserInfo(String code);
    
    /**
     * 获取用户列表
     */
    List<UserListItem> getUserList();
    
    /**
     * 获取组织列表
     */
    List<OrgListItem> getOrgList();
    
    /**
     * 获取 SAML 元数据
     */
    String getSamlMetadata();
    
    /**
     * 处理 SAML 断言
     */
    String handleSamlAssert(String samlResponse, String relayState);

    /**
     * 浏览器发起登录（getAuthUrl 返回本服务中转地址时使用），返回要跳转的 SSO 登录地址，默认不支持
     */
    default String startLogin(HttpServletRequest request, String key) {
        throw new UnsupportedOperationException("当前SSO Provider不支持中转登录");
    }

    /**
     * 获取统一登出地址（清理 SSO 服务端会话），默认不支持
     */
    default String getLogoutUrl(HttpServletRequest request, String redirectUri) {
        throw new UnsupportedOperationException("当前SSO Provider不支持统一登出");
    }
}