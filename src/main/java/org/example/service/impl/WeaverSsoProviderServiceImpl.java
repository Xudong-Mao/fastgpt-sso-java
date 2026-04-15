package org.example.service.impl;

import org.example.config.WeaverSsoConfig;
import org.example.dto.UserInfo;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.example.service.GlobalStoreService;
import org.example.service.HrSyncStoreService;
import org.example.service.SsoProviderService;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 泛微 Ecology SSO 提供者服务实现
 * 基于 CAS 协议对接泛微统一认证中心
 */
@Service("weaverSsoProvider")
public class WeaverSsoProviderServiceImpl implements SsoProviderService {

    private static final Logger log = LoggerFactory.getLogger(WeaverSsoProviderServiceImpl.class);

    /** code 在 GlobalStore 中的过期时间（分钟） */
    private static final int CODE_EXPIRE_MINUTES = 5;

    @Autowired
    private WeaverSsoConfig config;

    @Autowired
    private GlobalStoreService globalStoreService;

    @Autowired
    private HrSyncStoreService hrSyncStoreService;

    private final RestTemplate restTemplate = new RestTemplate();

    // ========================= OAuth 流程 =========================

    @Override
    public String getAuthUrl(HttpServletRequest request, String redirectUri, String state) {
        // 将 redirectUri 和 state 暂存，key 用 state
        if (state != null && !state.isEmpty()) {
            globalStoreService.setTmpValue("oauth_state:" + state, redirectUri, CODE_EXPIRE_MINUTES);
        }

        // 构建 CAS 登录地址: CAS_SERVER/login/login.jsp?appid=xxx&service=回调地址
        String serviceUrl = config.getServiceUrl();
        String casLoginUrl = config.getCasLoginUrl() + "&service=" + URLEncoder.encode(serviceUrl, java.nio.charset.StandardCharsets.UTF_8);

        log.info("泛微SSO getAuthUrl: casLoginUrl={}, state={}", casLoginUrl, state);
        return casLoginUrl;
    }

    @Override
    public String handleCallback(HttpServletRequest request) {
        // CAS 认证成功后回调，带 ticket 参数
        String ticket = request.getParameter("ticket");
        // 从 service 参数中恢复 state（泛微 CAS 回调时可能不传回原始 state）
        // 我们通过校验 ticket 来获取用户信息，然后生成 code 回传给 FastGPT
        String state = request.getParameter("state");

        log.info("泛微SSO handleCallback: ticket={}, state={}", ticket, state);

        if (ticket == null || ticket.isEmpty()) {
            throw new RuntimeException("CAS回调缺少ticket参数");
        }

        // 1. 用 ticket 向 CAS Server 校验，获取用户名和属性
        CasValidateResult validateResult = validateTicket(ticket);

        // 2. 生成临时 code，关联用户信息
        String code = "weaver_" + System.currentTimeMillis() + "_" + ticket.hashCode();
        globalStoreService.setTmpValue("code:" + code, validateResult, CODE_EXPIRE_MINUTES);

        // 3. 恢复原始 redirectUri
        String redirectUri = null;
        if (state != null && !state.isEmpty()) {
            redirectUri = globalStoreService.getTmpValue("oauth_state:" + state);
        }

        // 如果 state 关联不到 redirectUri，尝试从请求中获取
        if (redirectUri == null || redirectUri.isEmpty()) {
            redirectUri = request.getParameter("redirect_uri");
        }

        if (redirectUri == null || redirectUri.isEmpty()) {
            throw new RuntimeException("无法获取回调地址redirect_uri");
        }

        // 4. 拼接回传 FastGPT 前端的 URL
        String separator = redirectUri.contains("?") ? "&" : "?";
        String callbackUrl = redirectUri + separator + "code=" + URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);

        if (state != null && !state.isEmpty()) {
            callbackUrl += "&state=" + URLEncoder.encode(state, java.nio.charset.StandardCharsets.UTF_8);
        }

        log.info("泛微SSO callback redirect: {}", callbackUrl);
        return callbackUrl;
    }

    @Override
    public UserInfo getUserInfo(String code) {
        // 根据 code 取出暂存的用户校验结果
        CasValidateResult validateResult = globalStoreService.getTmpValue("code:" + code);
        if (validateResult == null) {
            throw new RuntimeException("无效或已过期的code: " + code);
        }

        // 删除已使用的 code
        globalStoreService.setTmpValue("code:" + code, null, 0);

        // 从 HR 同步数据中补充信息
        String username = validateResult.username;
        UserListItem hrUser = hrSyncStoreService.getUserByUsername(username);

        UserInfo userInfo = new UserInfo();
        userInfo.setUsername(username);
        userInfo.setMemberName(validateResult.memberName);

        // 优先使用 CAS 返回属性，其次使用 HR 同步数据
        if (validateResult.email != null && !validateResult.email.isEmpty()) {
            userInfo.setContact(validateResult.email);
        } else if (hrUser != null && hrUser.getContact() != null) {
            userInfo.setContact(hrUser.getContact());
        }

        // 头像：CAS 不返回，HR 同步数据可能有
        if (hrUser != null) {
            userInfo.setAvatar(hrUser.getAvatar());
        }

        log.info("泛微SSO getUserInfo: username={}, memberName={}", username, validateResult.memberName);
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

    // ========================= CAS Ticket 校验 =========================

    /**
     * 向 CAS Server 校验 Ticket
     * CAS 2.0 协议：GET /sso/serviceValidate?service=xxx&ticket=xxx
     * 返回 XML 格式：
     * <cas:serviceResponse>
     *   <cas:authenticationSuccess>
     *     <cas:user>loginid</cas:user>
     *     <cas:attributes>...</cas:attributes>
     *   </cas:authenticationSuccess>
     * </cas:serviceResponse>
     */
    private CasValidateResult validateTicket(String ticket) {
        String validateUrl = config.getCasValidateUrl()
                + "?service=" + URLEncoder.encode(config.getServiceUrl(), java.nio.charset.StandardCharsets.UTF_8)
                + "&ticket=" + URLEncoder.encode(ticket, java.nio.charset.StandardCharsets.UTF_8);

        log.info("泛微SSO validateTicket: url={}", validateUrl);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(validateUrl, String.class);
            String body = response.getBody();
            log.debug("泛微SSO validateTicket response: {}", body);

            if (body == null || body.isEmpty()) {
                throw new RuntimeException("CAS校验返回空响应");
            }

            return parseCasResponse(body);
        } catch (Exception e) {
            log.error("泛微SSO Ticket校验失败: {}", e.getMessage(), e);
            throw new RuntimeException("CAS Ticket校验失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 CAS 2.0 XML 响应
     */
    private CasValidateResult parseCasResponse(String xml) {
        try {
            Document doc = DocumentHelper.parseText(xml);
            Element root = doc.getRootElement();

            // 命名空间处理：cas: 前缀
            Element successElem = root.element("authenticationSuccess");
            if (successElem == null) {
                // 尝试带命名空间
                for (Element elem : (List<Element>) root.elements()) {
                    if (elem.getName().equals("authenticationSuccess")) {
                        successElem = elem;
                        break;
                    }
                }
            }

            if (successElem == null) {
                Element failureElem = root.element("authenticationFailure");
                String errMsg = failureElem != null ? failureElem.getTextTrim() : "未知错误";
                throw new RuntimeException("CAS认证失败: " + errMsg);
            }

            CasValidateResult result = new CasValidateResult();

            // 获取用户名
            Element userElem = successElem.element("user");
            if (userElem != null) {
                result.username = userElem.getTextTrim();
            }

            // 获取属性
            Element attrsElem = successElem.element("attributes");
            if (attrsElem != null) {
                Map<String, String> attrs = new java.util.HashMap<>();
                for (Element attr : (List<Element>) attrsElem.elements()) {
                    attrs.put(attr.getName(), attr.getTextTrim());
                }

                WeaverSsoConfig.AttributeMapping mapping = config.getAttribute();
                result.memberName = attrs.get(mapping.getMemberName());
                result.email = attrs.get(mapping.getEmail());
                result.mobile = attrs.get(mapping.getMobile());
                result.attributes = attrs;
            }

            if (result.username == null || result.username.isEmpty()) {
                throw new RuntimeException("CAS响应中缺少用户名");
            }

            log.info("泛微SSO Ticket校验成功: username={}", result.username);
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析CAS响应XML失败: " + e.getMessage(), e);
        }
    }

    // ========================= 内部类 =========================

    /**
     * CAS Ticket 校验结果
     */
    private static class CasValidateResult {
        String username;
        String memberName;
        String email;
        String mobile;
        Map<String, String> attributes;
    }
}
