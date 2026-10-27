package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 泛微 SSO 配置
 * 适配 weaversso.jar 参数命名
 */
@Configuration
@ConfigurationProperties(prefix = "weaver.sso")
public class WeaverSsoConfig {

    /** 是否启用泛微SSO */
    private boolean enabled = false;

    /** 是否输出完整的 SSO 调试链路日志 */
    private boolean debug = false;

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

    /** CAS ticket 校验接口路径（主动校验模式），默认 /sso/validate
     *  兼容返回 JSON / CAS 2.0 XML / 纯文本（yes\n账号） */
    private String validatePath = "/sso/validate";

    /** CAS ticket 校验请求方式，默认 POST；服务端返回 405/501 时自动改用 GET 重试 */
    private String validateMethod = "POST";

    /** CAS ticket 校验接口超时时间（毫秒），默认 5000 */
    private int validateTimeout = 5000;

    /** CAS 校验接口返回的成功码，默认 0（仅 JSON 格式使用：code=0 成功，data 返回用户信息） */
    private int successCode = 0;

    /** CAS 校验失败后重新跳 CAS 登录的最大次数，超过后返回错误页，避免无限重定向 */
    private int maxRetry = 2;

    /** service 回调地址是否携带 redirect_uri/state 参数
     *  泛微要求 service 与注册地址完全一致时设为 false，此时依赖 Session 恢复 redirect_uri */
    private boolean serviceIncludeRedirect = true;

    /** CAS 登出路径，默认 /sso/logout */
    private String logoutPath = "/sso/logout";

    /** CAS 登出后回跳地址（如 FastGPT 登录页），为空时停留在 CAS 登出页 */
    private String logoutRedirectUrl;

    /** CAS 账号 → FastGPT 用户 映射表文件（CSV），未映射的账号拒绝登录 */
    private String userMappingFile;

    /** CAS 校验接口额外参数（如 appId、timestamp、sign 等） */
    private Map<String, String> extraParams = new LinkedHashMap<>();

    /** Session 中存储 CAS 用户信息的 key */
    public static final String SESSION_CAS_USER_KEY = "casLoginUser";

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

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
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
     * 获取 CAS ticket 校验接口完整地址
     * 主动校验模式：POST ticket + service 到此地址换取用户信息
     * 如 http://11.41.154.72:8080/sso/validate
     */
    public String getValidateUrl() {
        return serverUrl + validatePath;
    }

    /**
     * 获取 CAS 登出地址，如 http://11.41.154.72:8080/sso/logout
     */
    public String getLogoutUrl() {
        return serverUrl + logoutPath;
    }

    public String getValidateMethod() {
        return validateMethod;
    }

    public void setValidateMethod(String validateMethod) {
        this.validateMethod = validateMethod;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public boolean isServiceIncludeRedirect() {
        return serviceIncludeRedirect;
    }

    public void setServiceIncludeRedirect(boolean serviceIncludeRedirect) {
        this.serviceIncludeRedirect = serviceIncludeRedirect;
    }

    public String getLogoutPath() {
        return logoutPath;
    }

    public void setLogoutPath(String logoutPath) {
        this.logoutPath = logoutPath;
    }

    public String getLogoutRedirectUrl() {
        return logoutRedirectUrl;
    }

    public void setLogoutRedirectUrl(String logoutRedirectUrl) {
        this.logoutRedirectUrl = logoutRedirectUrl;
    }

    public String getUserMappingFile() {
        return userMappingFile;
    }

    public void setUserMappingFile(String userMappingFile) {
        this.userMappingFile = userMappingFile;
    }

    public String getValidatePath() {
        return validatePath;
    }

    public void setValidatePath(String validatePath) {
        this.validatePath = validatePath;
    }

    public int getValidateTimeout() {
        return validateTimeout;
    }

    public void setValidateTimeout(int validateTimeout) {
        this.validateTimeout = validateTimeout;
    }

    public int getSuccessCode() {
        return successCode;
    }

    public void setSuccessCode(int successCode) {
        this.successCode = successCode;
    }

    public Map<String, String> getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(Map<String, String> extraParams) {
        this.extraParams = extraParams;
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
