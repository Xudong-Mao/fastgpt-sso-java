package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 泛微 SSO 配置
 * 适配 weaversso.jar 参数命名
 */
@Configuration
@ConfigurationProperties(prefix = "weaver.sso")
public class WeaverSsoConfig {

    /** 是否启用泛微SSO */
    private boolean enabled = false;

    /** CAS 服务端地址，如 http://192.168.1.100:8080
     *  对应 weaversso.jar 的 ssoServerLoginUrl 前缀和 ssoServerUrlPrefix 前缀 */
    private String serverUrl;

    /** CAS 登录路径，默认 /login/login.jsp */
    private String loginPath = "/login/login.jsp";

    /** 应用标识（在泛微统一认证中心注册时分配）
     *  对应 weaversso.jar 的 appid 参数 */
    private String appId;

    /** 应用服务地址（本服务的外网可达地址），如 http://your-sso-service:8080
     *  对应 weaversso.jar 的 serverName 参数 */
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
     * 对应 weaversso.jar AuthenticationFilter 的 ssoServerLoginUrl 参数
     * 格式: http://OA地址/login/login.jsp?appid=应用标识
     */
    public String getCasLoginUrl() {
        return serverUrl + loginPath + "?appid=" + appId;
    }

    /**
     * 获取 CAS 服务端 SSO 前缀地址
     * 对应 weaversso.jar SSO20ProxyReceivingTicketValidationFilter 的 ssoServerUrlPrefix 参数
     * 格式: http://OA地址/sso
     */
    public String getCasServerUrlPrefix() {
        return serverUrl + "/sso";
    }

    /**
     * 获取完整的回调地址（service URL）
     * CAS 认证成功后回调到此地址
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
