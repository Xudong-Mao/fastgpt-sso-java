package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.UserInfo;
import org.example.dto.UserInfoResponse;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.example.service.SsoLoginDeniedException;
import org.example.service.SsoProviderService;
import org.example.util.ErrorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.net.URL;
import java.util.List;

/**
 * SSO 控制器
 */
@RestController
public class SsoController {

    private static final Logger log = LoggerFactory.getLogger(SsoController.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Value("${app.sso.provider:test}")
    private String providerName;

    @Value("${app.redirect.enabled:false}")
    private boolean redirectEnabled;

    @Value("${app.hostname:}")
    private String hostname;

    /**
     * 根据 app.sso.provider 配置动态获取 SSO Provider
     * 配置值为 weaver 时，查找 weaverSsoProvider bean
     * 配置值为 test 时，查找 testSsoProvider bean
     */
    private SsoProviderService getSsoProvider() {
        String beanName = providerName + "SsoProvider";
        try {
            return applicationContext.getBean(beanName, SsoProviderService.class);
        } catch (Exception e) {
            throw new RuntimeException("未找到SSO Provider: " + beanName + "，请检查 app.sso.provider 配置");
        }
    }

    /**
     * 获取认证 URL
     */
    @GetMapping("/login/oauth/getAuthURL")
    public ResponseEntity<ApiResponse<String>> getAuthUrl(
            HttpServletRequest request,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "state", required = false) String state) {

        try {
            if (redirectEnabled && hostname != null && !hostname.isEmpty()) {
                URL hostnameUrl = new URL(hostname);
                if (!request.getServerName().equals(hostnameUrl.getHost())) {
                    String authURL = hostname + request.getRequestURI() + "?" + request.getQueryString();
                    ApiResponse<String> response = ApiResponse.success();
                    response.setAuthURL(authURL);
                    return ResponseEntity.ok(response);
                }
            }

            String authUrl = getSsoProvider().getAuthUrl(request, redirectUri, state);
            ApiResponse<String> response = ApiResponse.success();
            response.setAuthURL(authUrl);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("SSO getAuthURL failed: redirectUri={}, state={}", redirectUri, state, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(ErrorUtils.getErrText(e)));
        }
    }

    /**
     * 处理回调
     */
    @GetMapping("/login/oauth/callback")
    public ResponseEntity<String> handleCallback(HttpServletRequest request) {
        try {
            String redirectUrl = getSsoProvider().handleCallback(request);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        } catch (SsoLoginDeniedException e) {
            return errorPage(HttpStatus.FORBIDDEN, "无法登录 FastGPT", e.getMessage());
        } catch (Exception e) {
            log.error("SSO callback failed: uri={}, query={}", request.getRequestURI(),
                    sanitizeQuery(request.getQueryString()), e);
            return errorPage(HttpStatus.INTERNAL_SERVER_ERROR, "统一认证登录异常",
                    "统一认证登录失败，请稍后重试或联系管理员。");
        }
    }

    private ResponseEntity<String> errorPage(HttpStatus status, String title, String message) {
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + escapeHtml(title)
                + "</title></head><body style=\"font-family:sans-serif;text-align:center;padding-top:80px\">"
                + "<h2>" + escapeHtml(title) + "</h2><p>" + escapeHtml(message) + "</p></body></html>";
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 获取用户信息
     */
    @GetMapping("/login/oauth/getUserInfo")
    public ResponseEntity<UserInfoResponse> getUserInfo(@RequestParam("code") String code) {
        try {
            UserInfo userInfo = getSsoProvider().getUserInfo(code);
            return ResponseEntity.ok(UserInfoResponse.success(userInfo));
        } catch (Exception e) {
            log.error("SSO getUserInfo failed: code={}", mask(code), e);
            return ResponseEntity.ok(UserInfoResponse.error(ErrorUtils.getErrText(e)));
        }
    }

    /**
     * 获取用户列表
     */
    @GetMapping("/user/list")
    public ResponseEntity<ApiResponse<List<UserListItem>>> getUserList() {
        try {
            List<UserListItem> userList = getSsoProvider().getUserList();
            ApiResponse<List<UserListItem>> response = ApiResponse.success();
            response.setUserList(userList);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(ErrorUtils.getErrText(e)));
        }
    }

    /**
     * 获取组织列表
     */
    @GetMapping("/org/list")
    public ResponseEntity<ApiResponse<List<OrgListItem>>> getOrgList() {
        try {
            List<OrgListItem> orgList = getSsoProvider().getOrgList();
            ApiResponse<List<OrgListItem>> response = ApiResponse.success();
            response.setOrgList(orgList);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(ErrorUtils.getErrText(e)));
        }
    }

    /**
     * 浏览器登录中转：在浏览器 Session 中记录 redirect_uri/state 后跳转 SSO 登录页
     * getAuthURL 由 FastGPT 后端调用，Session Cookie 不会到达浏览器，因此需要这一跳
     */
    @GetMapping("/login/oauth/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request, @RequestParam("key") String key) {
        try {
            String loginUrl = getSsoProvider().startLogin(request, key);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", loginUrl)
                    .build();
        } catch (Exception e) {
            log.error("SSO authorize failed: key={}", mask(key), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /**
     * 统一登出：清理本服务 Session 后跳转 CAS /sso/logout，清理 CAS 单点会话
     * 仅清理 FastGPT 本地登录态时 CAS 会话仍在，下次访问会被自动免密登录
     */
    @GetMapping("/login/oauth/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri) {
        try {
            String logoutUrl = getSsoProvider().getLogoutUrl(request, redirectUri);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", logoutUrl)
                    .build();
        } catch (Exception e) {
            log.error("SSO logout failed: redirectUri={}", redirectUri, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 测试接口
     */
    @GetMapping("/test")
    public String test() {
        return "FastGPT-SSO-Service";
    }

    /**
     * 获取 SAML 元数据
     */
    @GetMapping(value = "/login/saml/metadata.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getSamlMetadata() {
        try {
            String metadata = getSsoProvider().getSamlMetadata();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(metadata);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("<?xml version=\"1.0\"?><error>" + ErrorUtils.getErrText(e) + "</error>");
        }
    }

    /**
     * 处理 SAML 断言
     */
    @PostMapping("/login/saml/assert")
    public ResponseEntity<Void> handleSamlAssert(
            @RequestParam("SAMLResponse") String samlResponse,
            @RequestParam(value = "RelayState", required = false) String relayState) {
        try {
            String redirectUrl = getSsoProvider().handleSamlAssert(samlResponse, relayState);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return null;
        }
        return query.replaceAll("(?i)(ticket|code|token)=([^&]+)", "$1=***");
    }

    private String mask(String value) {
        if (value == null || value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
