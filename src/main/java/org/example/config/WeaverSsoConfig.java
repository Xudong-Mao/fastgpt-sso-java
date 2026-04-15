package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 泛微 SSO 配置
 */
@Configuration
@ConfigurationProperties(prefix = "weaver.sso")
public class WeaverSsoConfig {

    /** 是否启用泛微SSO */
    private boolean enabled = false;

    /** CAS 服务端地址，如 http://192.168.1.100:8080 */
    private String serverUrl;

    /** CAS 登录路径，默认 /login/login.jsp */
    private String loginPath = "/login/login.jsp";

    /** CAS Ticket 校验路径，默认 /sso/serviceValidate */
    private String validatePath = "/sso/serviceValidate";

    /** 应用标识（在泛微统一认证中心注册时分配） */
    private String appId;

    /** 应用服务地址（本服务的外网可达地址），如 http://your-sso-service:8080 */
    private String serverName;

    /** 回调接口路径，默认 /login/oauth/callback */
    private String callbackPath = "/login/oauth/callback";

    /** HR同步接口认证Token */
    private String hrSyncToken;

    /** CAS 返回属性与 FastGPT 字段的映射 */
    private AttributeMapping attribute = new AttributeMapping();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getLoginPath() {
        return loginPath;
    }

    public void setLoginPath(String loginPath) {
        this.loginPath = loginPath;
    }

    public String getValidatePath() {
        return validatePath;
    }

    public void setValidatePath(String validatePath) {
        this.validatePath = validatePath;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getCallbackPath() {
        return callbackPath;
    }

    public void setCallbackPath(String callbackPath) {
        this.callbackPath = callbackPath;
    }

    public String getHrSyncToken() {
        return hrSyncToken;
    }

    public void setHrSyncToken(String hrSyncToken) {
        this.hrSyncToken = hrSyncToken;
    }

    public AttributeMapping getAttribute() {
        return attribute;
    }

    public void setAttribute(AttributeMapping attribute) {
        this.attribute = attribute;
    }

    /**
     * 获取完整的 CAS 登录地址
     */
    public String getCasLoginUrl() {
        return serverUrl + loginPath + "?appid=" + appId;
    }

    /**
     * 获取完整的 Ticket 校验地址
     */
    public String getCasValidateUrl() {
        return serverUrl + validatePath;
    }

    /**
     * 获取完整的回调地址
     */
    public String getServiceUrl() {
        return serverName + callbackPath;
    }

    /**
     * CAS 返回属性映射
     */
    public static class AttributeMapping {
        /** 用户名字段，默认 loginid */
        private String username = "loginid";
        /** 姓名字段，默认 lastname */
        private String memberName = "lastname";
        /** 邮箱字段，默认 email */
        private String email = "email";
        /** 手机号字段，默认 mobile */
        private String mobile = "mobile";

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getMemberName() { return memberName; }
        public void setMemberName(String memberName) { this.memberName = memberName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getMobile() { return mobile; }
        public void setMobile(String mobile) { this.mobile = mobile; }
    }
}
