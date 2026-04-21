package org.example.service.impl;

import com.weaver.sso.client.authentication.AttributePrincipal;
import com.weaver.sso.client.util.AbstractCasFilter;
import com.weaver.sso.client.validation.Assertion;
import org.example.config.WeaverSsoConfig;
import org.example.dto.UserInfo;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.example.service.GlobalStoreService;
import org.example.service.HrSyncStoreService;
import org.example.service.SsoProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 泛微 Ecology SSO 提供者服务实现
 * 基于 weaversso.jar 对接泛微统一认证中心
 *
 * 核心机制：
 * weaversso.jar 的 Filter 会自动完成 CAS 认证和 Ticket 校验，
 * 将 Assertion 存入 HttpSession。
 * 本服务只需从 Session 中获取 Assertion 即可拿到用户信息。
 */
@Service("weaverSsoProvider")
public class WeaverSsoProviderServiceImpl implements SsoProviderService {

    private static final Logger log = LoggerFactory.getLogger(WeaverSsoProviderServiceImpl.class);
    private static final String SESSION_REDIRECT_URI_KEY = "fastgpt_redirect_uri";

    /** code 在 GlobalStore 中的过期时间（分钟） */
    private static final int CODE_EXPIRE_MINUTES = 5;

    @Autowired
    private WeaverSsoConfig config;

    @Autowired
    private GlobalStoreService globalStoreService;

    @Autowired
    private HrSyncStoreService hrSyncStoreService;

    // ========================= OAuth 流程 =========================

    @Override
    public String getAuthUrl(HttpServletRequest request, String redirectUri, String state) {
        // 将 redirectUri 和 state 暂存
        if (state != null && !state.isEmpty()) {
            globalStoreService.setTmpValue("oauth_state:" + state, redirectUri, CODE_EXPIRE_MINUTES);
        }
        request.getSession(true).setAttribute(SESSION_REDIRECT_URI_KEY, redirectUri);

        // 构建 CAS 登录地址: CAS_SERVER/login/login.jsp?appid=xxx&service=回调地址
        String serviceUrl = config.getServiceUrl();
        String casLoginUrl = config.getCasLoginUrl() + "&service="
                + URLEncoder.encode(serviceUrl, java.nio.charset.StandardCharsets.UTF_8);

        log.info("泛微SSO getAuthUrl: casLoginUrl={}, state={}", casLoginUrl, state);
        return casLoginUrl;
    }

    @Override
    public String handleCallback(HttpServletRequest request) {
        /**
         * weaversso.jar 的 Filter 链已经自动完成了：
         * 1. AuthenticationFilter: 检查用户是否已认证，未认证则重定向到 CAS
         * 2. SSO20ProxyReceivingTicketValidationFilter: 校验 CAS 回调的 ticket
         * 3. 将 Assertion 存入 HttpSession
         *
         * 所以到这里，如果请求能进来，说明 CAS 认证已成功，
         * 我们直接从 Session 中获取 Assertion 即可。
         */

        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new RuntimeException("CAS认证回调无有效Session，请确认weaversso.jar Filter已正确配置");
        }

        // 从 Session 获取 Assertion（weaversso.jar 存入的）
        Assertion assertion = (Assertion) session.getAttribute(AbstractCasFilter.CONST_CAS_ASSERTION);
        if (assertion == null) {
            throw new RuntimeException("Session中未找到CAS Assertion，Ticket校验可能失败");
        }

        // 获取用户信息
        AttributePrincipal principal = assertion.getPrincipal();
        String username = principal.getName();
        Map<String, Object> attributes = principal.getAttributes();

        log.info("泛微SSO handleCallback: username={}, attributes={}", username, attributes);

        // 从 CAS 属性中提取信息
        WeaverSsoConfig.AttributeMapping mapping = config.getAttribute();
        String memberName = getAttributeValue(attributes, mapping.getMemberName());
        String email = getAttributeValue(attributes, mapping.getEmail());
        String mobile = getAttributeValue(attributes, mapping.getMobile());

        // 生成临时 code，关联用户信息
        String code = "weaver_" + System.currentTimeMillis() + "_" + username.hashCode();
        CasUserInfo userInfo = new CasUserInfo(username, memberName, email, mobile);
        globalStoreService.setTmpValue("code:" + code, userInfo, CODE_EXPIRE_MINUTES);

        // 恢复原始 redirectUri
        String state = request.getParameter("state");
        String redirectUri = null;
        if (state != null && !state.isEmpty()) {
            redirectUri = globalStoreService.getTmpValue("oauth_state:" + state);
        }

        // 如果 state 关联不到 redirectUri，尝试从请求中获取
        if (redirectUri == null || redirectUri.isEmpty()) {
            redirectUri = request.getParameter("redirect_uri");
        }
        if ((redirectUri == null || redirectUri.isEmpty()) && session.getAttribute(SESSION_REDIRECT_URI_KEY) != null) {
            redirectUri = String.valueOf(session.getAttribute(SESSION_REDIRECT_URI_KEY));
        }

        if (redirectUri == null || redirectUri.isEmpty()) {
            throw new RuntimeException("无法获取回调地址redirect_uri");
        }
        session.removeAttribute(SESSION_REDIRECT_URI_KEY);

        // 拼接回传 FastGPT 前端的 URL
        String separator = redirectUri.contains("?") ? "&" : "?";
        String callbackUrl = redirectUri + separator + "code="
                + URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);

        if (state != null && !state.isEmpty()) {
            callbackUrl += "&state=" + URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8);
        }

        log.info("泛微SSO callback redirect: {}", callbackUrl);
        return callbackUrl;
    }

    @Override
    public UserInfo getUserInfo(String code) {
        // 根据 code 取出暂存的用户信息
        CasUserInfo casUserInfo = globalStoreService.getTmpValue("code:" + code);
        if (casUserInfo == null) {
            throw new RuntimeException("无效或已过期的code: " + code);
        }

        // 删除已使用的 code（一次性消费）
        globalStoreService.setTmpValue("code:" + code, null, 0);

        // 从 HR 同步数据中补充信息
        String username = casUserInfo.username;
        UserListItem hrUser = hrSyncStoreService.getUserByUsername(username);

        UserInfo userInfo = new UserInfo();
        userInfo.setUsername(username);
        userInfo.setMemberName(casUserInfo.memberName);

        // 优先使用 CAS 返回属性，其次使用 HR 同步数据
        if (casUserInfo.email != null && !casUserInfo.email.isEmpty()) {
            userInfo.setContact(casUserInfo.email);
        } else if (hrUser != null && hrUser.getContact() != null) {
            userInfo.setContact(hrUser.getContact());
        }

        // 头像：CAS 不返回，HR 同步数据可能有
        if (hrUser != null) {
            userInfo.setAvatar(hrUser.getAvatar());
        }

        log.info("泛微SSO getUserInfo: username={}, memberName={}", username, casUserInfo.memberName);
        return userInfo;
    }

    // ========================= 用户/组织列表 =========================

    @Override
    public List<UserListItem> getUserList() {
        return hrSyncStoreService.getUserList();
    }

    @Override
    public List<OrgListItem> getOrgList() {
        return hrSyncStoreService.getOrgList();
    }

    // ========================= SAML（泛微使用 CAS，不支持 SAML） =========================

    @Override
    public String getSamlMetadata() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><EntityDescriptor>" +
                "<!-- 泛微SSO基于CAS协议，不提供SAML元数据 -->" +
                "</EntityDescriptor>";
    }

    @Override
    public String handleSamlAssert(String samlResponse, String relayState) {
        throw new RuntimeException("泛微SSO基于CAS协议，不支持SAML断言");
    }

    // ========================= 工具方法 =========================

    /**
     * 从 CAS 属性 Map 中安全获取字符串值
     */
    private String getAttributeValue(Map<String, Object> attributes, String key) {
        if (attributes == null || key == null) {
            return null;
        }
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    // ========================= 内部类 =========================

    /**
     * CAS 用户信息暂存
     */
    private static class CasUserInfo {
        final String username;
        final String memberName;
        final String email;
        final String mobile;

        CasUserInfo(String username, String memberName, String email, String mobile) {
            this.username = username;
            this.memberName = memberName;
            this.email = email;
            this.mobile = mobile;
        }
    }
}
