package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.UserInfo;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.example.service.SsoProviderService;
import org.example.util.ErrorUtils;
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(ErrorUtils.getErrText(e)));
        }
    }

    /**
     * 处理回调
     */
    @GetMapping("/login/oauth/callback")
    public ResponseEntity<Void> handleCallback(HttpServletRequest request) {
        try {
            String redirectUrl = getSsoProvider().handleCallback(request);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取用户信息
     */
    @GetMapping("/login/oauth/getUserInfo")
    public ResponseEntity<ApiResponse<UserInfo>> getUserInfo(@RequestParam("code") String code) {
        try {
            UserInfo userInfo = getSsoProvider().getUserInfo(code);
            ApiResponse<UserInfo> response = ApiResponse.success("", userInfo);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(ErrorUtils.getErrText(e)));
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
}
