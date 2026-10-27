package org.example.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.example.config.WeaverSsoConfig;
import org.example.dto.UserInfo;
import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.example.service.GlobalStoreService;
import org.example.service.HrSyncStoreService;
import org.example.service.SsoLoginDeniedException;
import org.example.service.SsoProviderService;
import org.example.service.UserMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 泛微 Ecology SSO 提供者服务实现
 *
 * 核心机制（主动校验模式，对齐 Ecology 统一认证中心 CAS 协议）：
 * 1. getAuthUrl 拼接 CAS 登录地址：{server}/sso/login?appid=xxx&service=回调地址；
 * 2. CAS 认证成功后回调本服务并携带 ticket 参数；
 * 3. 本服务主动调用 CAS 服务端校验接口（默认 /sso/validate），用 ticket + service 换取账号，
 *    兼容 JSON（code/data）、CAS 2.0 XML（cas:user + cas:attributes）、纯文本（yes\n账号）三种返回；
 * 4. 按用户映射表把 CAS 账号换成 FastGPT 用户（未映射/已停用则拒绝登录），
 *    生成一次性 code 供 FastGPT 换取用户信息；
 * 5. 校验失败最多重新跳 CAS 登录 maxRetry 次，避免 CAS 与应用之间无限重定向。
 */
@Service("weaverSsoProvider")
public class WeaverSsoProviderServiceImpl implements SsoProviderService {

    private static final Logger log = LoggerFactory.getLogger(WeaverSsoProviderServiceImpl.class);
    private static final String SESSION_REDIRECT_URI_KEY = "fastgpt_redirect_uri";
    private static final String SESSION_STATE_KEY = "fastgpt_state";
    private static final String SESSION_RETRY_KEY = "cas_retry_count";

    /** code 在 GlobalStore 中的过期时间（分钟） */
    private static final int CODE_EXPIRE_MINUTES = 5;

    @Autowired
    private WeaverSsoConfig config;

    @Autowired
    private GlobalStoreService globalStoreService;

    @Autowired
    private HrSyncStoreService hrSyncStoreService;

    @Autowired
    private UserMappingService userMappingService;

    // ========================= OAuth 流程 =========================

    @Override
    public String getAuthUrl(HttpServletRequest request, String redirectUri, String state) {
        HttpSession session = request.getSession(true);
        debug("[1/5 getAuthURL] input redirectUri={}, state={}, session={}", redirectUri, state, session.getId());
        // 将 redirectUri 和 state 暂存
        if (state != null && !state.isEmpty()) {
            globalStoreService.setTmpValue("oauth_state:" + state, redirectUri, CODE_EXPIRE_MINUTES);
        }
        session.setAttribute(SESSION_REDIRECT_URI_KEY, redirectUri);
        session.setAttribute(SESSION_STATE_KEY, state);
        // 新一轮登录，清理失败计数
        session.removeAttribute(SESSION_RETRY_KEY);

        if (!config.isServiceIncludeRedirect()) {
            // service 固定时 redirect_uri 只能靠浏览器 Session 恢复；
            // getAuthURL 由 FastGPT 后端调用，这里的 Session 到不了浏览器，所以先让浏览器经 /login/oauth/authorize 中转
            String key = UUID.randomUUID().toString().replace("-", "");
            globalStoreService.setTmpValue("authorize:" + key, new String[]{redirectUri, state}, CODE_EXPIRE_MINUTES);
            String authorizeUrl = config.getServerName() + "/login/oauth/authorize?key=" + key;
            debug("[2/5 getAuthURL] fixed service, output authorizeURL={}", authorizeUrl);
            log.info("泛微SSO getAuthUrl: authorizeUrl={}, state={}", authorizeUrl, state);
            return authorizeUrl;
        }

        String serviceUrl = buildServiceUrl(redirectUri, state);
        String casLoginUrl = buildCasLoginUrl(serviceUrl);

        debug("[2/5 getAuthURL] CAS login base={}, service={}", config.getCasLoginUrl(), serviceUrl);
        debug("[3/5 getAuthURL] output authURL={}", casLoginUrl);
        log.info("泛微SSO getAuthUrl: casLoginUrl={}, state={}", casLoginUrl, state);
        return casLoginUrl;
    }

    @Override
    public String startLogin(HttpServletRequest request, String key) {
        String[] pending = globalStoreService.getTmpValue("authorize:" + key);
        if (pending == null) {
            throw new RuntimeException("无效或已过期的登录请求");
        }
        globalStoreService.setTmpValue("authorize:" + key, null, 0);
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_REDIRECT_URI_KEY, pending[0]);
        session.setAttribute(SESSION_STATE_KEY, pending[1]);
        session.removeAttribute(SESSION_RETRY_KEY);
        String casLoginUrl = buildCasLoginUrl(buildServiceUrl(pending[0], pending[1]));
        debug("[3/5 authorize] session={}, output casLoginUrl={}", session.getId(), casLoginUrl);
        return casLoginUrl;
    }

    @Override
    public String handleCallback(HttpServletRequest request) {
        debug("[1/7 callback] input uri={}, ticket={}, redirect_uri={}, state={}", request.getRequestURI(),
                mask(request.getParameter("ticket")), request.getParameter("redirect_uri"),
                request.getParameter("state"));

        HttpSession session = request.getSession(true);
        String redirectUri = resolveRedirectUri(request, session);
        String state = resolveState(request, session);

        // 构建校验用的 service（必须与 getAuthUrl 中构建的 service 一致，否则 CAS 校验会失败）
        String serviceUrl = buildServiceUrl(redirectUri, state);
        debug("[2/7 callback] validate service={}", serviceUrl);

        String ticket = request.getParameter("ticket");
        JSONObject userData;
        try {
            if (ticket == null || ticket.isEmpty()) {
                throw new CasValidateException("CAS回调未携带ticket参数，无法完成票据校验");
            }
            // 调用 CAS 校验接口，用 ticket 换取用户信息
            userData = validateTicket(ticket, serviceUrl);
        } catch (CasValidateException e) {
            return redirectAfterFailure(session, serviceUrl, e);
        }
        debug("[3/7 callback] CAS validate success, userData={}", userData.toJSONString());

        // 从 userData 中提取 CAS 账号信息
        WeaverSsoConfig.AttributeMapping mapping = config.getAttribute();
        String casAccount = extractUsername(userData, mapping.getUsername());
        String memberName = getFieldString(userData, mapping.getMemberName());
        String email = getFieldString(userData, mapping.getEmail());
        String mobile = getFieldString(userData, mapping.getMobile());
        session.removeAttribute(SESSION_RETRY_KEY);

        // 通过映射表把 CAS 账号换成 FastGPT 用户，未映射或已停用则拒绝登录
        UserMappingService.UserMapping userMapping = userMappingService.findByCasAccount(casAccount);
        if (userMapping == null) {
            log.warn("泛微SSO CAS账号未映射FastGPT用户，拒绝登录: casAccount={}", casAccount);
            throw new SsoLoginDeniedException("统一认证账号 [" + casAccount + "] 未开通 FastGPT 账号，请联系管理员");
        }
        if (!userMapping.isEnabled()) {
            log.warn("泛微SSO 映射的FastGPT用户已停用，拒绝登录: casAccount={}, username={}", casAccount,
                    userMapping.getUsername());
            throw new SsoLoginDeniedException("FastGPT 账号 [" + userMapping.getUsername() + "] 已停用，请联系管理员");
        }
        String username = userMapping.getUsername();
        if (userMapping.getMemberName() != null) {
            memberName = userMapping.getMemberName();
        }
        if (userMapping.getContact() != null) {
            email = userMapping.getContact();
        }

        debug("[4/7 callback] casAccount={}, mapped username={}, userDataKeys={}", casAccount, username,
                userData.keySet());
        log.info("泛微SSO handleCallback: casAccount={}, username={}, memberName={}", casAccount, username, memberName);

        // 用户信息存入 Session（对齐 casLoginUser 约定）
        session.setAttribute(WeaverSsoConfig.SESSION_CAS_USER_KEY, userData);

        // 生成一次性 code，关联用户信息
        String code = "weaver_" + UUID.randomUUID().toString().replace("-", "");
        CasUserInfo userInfo = new CasUserInfo(username, memberName, email, mobile);
        globalStoreService.setTmpValue("code:" + code, userInfo, CODE_EXPIRE_MINUTES);
        debug("[5/7 callback] temporary code stored code={}, expires={}min", mask(code), CODE_EXPIRE_MINUTES);

        if (redirectUri == null || redirectUri.isEmpty()) {
            throw new RuntimeException("无法获取回调地址redirect_uri");
        }
        debug("[6/7 callback] redirectUri={}", redirectUri);
        session.removeAttribute(SESSION_REDIRECT_URI_KEY);
        session.removeAttribute(SESSION_STATE_KEY);

        // 拼接回传 FastGPT 前端的 URL
        String separator = redirectUri.contains("?") ? "&" : "?";
        String callbackUrl = redirectUri + separator + "code="
                + urlEncode(code);

        if (state != null && !state.isEmpty()) {
            callbackUrl += "&state=" + urlEncode(state);
        }

        log.info("泛微SSO callback redirect: {}", callbackUrl.replace(code, mask(code)));
        debug("[7/7 callback] output location={}", callbackUrl.replace(code, mask(code)));
        return callbackUrl;
    }

    @Override
    public String getLogoutUrl(HttpServletRequest request, String redirectUri) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // CAS 登出必须调用 /sso/logout，否则 CAS 会话残留，下次访问会被自动免密登录
        String target = redirectUri != null && !redirectUri.isEmpty() ? redirectUri : config.getLogoutRedirectUrl();
        String logoutUrl = config.getLogoutUrl();
        if (target != null && !target.isEmpty()) {
            logoutUrl += (logoutUrl.contains("?") ? "&" : "?") + "service=" + urlEncode(target);
        }
        log.info("泛微SSO logout redirect: {}", logoutUrl);
        return logoutUrl;
    }

    /**
     * CAS 校验失败后的处理：
     * 1. 前 maxRetry 次重新跳 CAS 登录，让 CAS 重新签发 ticket；
     * 2. 连续失败超过 maxRetry 次抛出异常，避免「CAS -> 应用 -> CAS」无限重定向。
     */
    private String redirectAfterFailure(HttpSession session, String serviceUrl, CasValidateException e) {
        Integer retryCount = (Integer) session.getAttribute(SESSION_RETRY_KEY);
        retryCount = retryCount == null ? 1 : retryCount + 1;
        if (retryCount > config.getMaxRetry()) {
            session.removeAttribute(SESSION_RETRY_KEY);
            log.error("CAS登录连续{}次失败，停止重试: {}", retryCount, e.getMessage());
            throw e;
        }
        session.setAttribute(SESSION_RETRY_KEY, retryCount);
        log.warn("CAS登录失败，第{}次重试，重新跳转CAS登录: {}", retryCount, e.getMessage());
        return buildCasLoginUrl(serviceUrl);
    }

    /**
     * 调用 CAS 服务端校验接口，用 ticket 换取用户信息
     * 兼容三种返回格式：
     * - JSON：{"code": 0, "msg": "成功", "data": { 用户信息 }}
     * - CAS 2.0 XML：&lt;cas:serviceResponse&gt;&lt;cas:authenticationSuccess&gt;&lt;cas:user&gt;xxx&lt;/cas:user&gt;...
     * - 纯文本：yes\nxxx / yes,xxx（失败为 no / false / error）
     */
    private JSONObject validateTicket(String ticket, String serviceUrl) {
        String validateUrl = config.getValidateUrl();
        Map<String, Object> params = new HashMap<>();
        params.put("ticket", ticket);
        params.put("service", serviceUrl);
        // 如果CAS需要额外参数：appId、timestamp、sign等，通过 weaver.sso.extra-params 配置补充
        if (config.getExtraParams() != null && !config.getExtraParams().isEmpty()) {
            params.putAll(config.getExtraParams());
        }

        boolean useGet = "GET".equalsIgnoreCase(config.getValidateMethod());
        String[] resp = callValidate(validateUrl, params, useGet);
        int status = Integer.parseInt(resp[0]);
        // 部分 CAS（如标准 serviceValidate）只接受 GET，POST 会返回 405/501
        if (!useGet && (status == 405 || status == 501)) {
            log.warn("CAS校验接口不支持POST(status={})，改用GET重试", status);
            resp = callValidate(validateUrl, params, true);
            status = Integer.parseInt(resp[0]);
        }
        String respBody = resp[1];
        log.info("CAS校验返回: status={}, body={}", status, abbreviate(respBody));
        if (status < 200 || status >= 300) {
            throw new CasValidateException("CAS校验接口返回HTTP " + status);
        }

        String text = respBody == null ? "" : respBody.trim();
        JSONObject userData;
        if (text.startsWith("{")) {
            userData = parseJsonResponse(text);
        } else if (text.startsWith("<")) {
            userData = parseXmlResponse(text);
        } else {
            userData = parsePlainTextResponse(text);
        }
        debug("CAS validate userData={}", userData.toJSONString());
        return userData;
    }

    /**
     * 发起校验请求，返回 [httpStatus, body]
     */
    private String[] callValidate(String validateUrl, Map<String, Object> params, boolean useGet) {
        HttpRequest httpRequest = useGet
                ? HttpRequest.get(buildUrlWithParams(validateUrl, params))
                : HttpRequest.post(validateUrl).form(params);
        try (HttpResponse httpResp = httpRequest.timeout(config.getValidateTimeout()).execute()) {
            return new String[]{String.valueOf(httpResp.getStatus()), httpResp.body()};
        } catch (Exception e) {
            log.error("调用CAS校验接口异常: url={}, method={}", validateUrl, useGet ? "GET" : "POST", e);
            throw new CasValidateException("调用CAS校验接口异常: " + e.getMessage());
        }
    }

    /**
     * 把参数拼到 URL 上，用于 GET 校验请求（如 CAS 2.0 serviceValidate）
     */
    private String buildUrlWithParams(String url, Map<String, Object> params) {
        StringBuilder builder = new StringBuilder(url);
        char separator = url.contains("?") ? '&' : '?';
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            builder.append(separator).append(urlEncode(entry.getKey())).append('=')
                    .append(urlEncode(String.valueOf(entry.getValue())));
            separator = '&';
        }
        return builder.toString();
    }

    /**
     * 约定：code=successCode 成功，data 返回用户信息
     */
    private JSONObject parseJsonResponse(String text) {
        JSONObject result;
        try {
            result = JSON.parseObject(text);
        } catch (Exception e) {
            throw new CasValidateException("CAS校验返回JSON解析失败: " + abbreviate(text));
        }
        if (result == null) {
            throw new CasValidateException("CAS校验返回为空");
        }
        Integer code = result.getInteger("code");
        if (code == null || code != config.getSuccessCode()) {
            String msg = result.getString("msg");
            log.error("CAS票据校验失败: code={}, msg={}", code, msg);
            throw new CasValidateException("CAS票据校验失败: " + (msg != null ? msg : "code=" + code));
        }
        JSONObject userData = result.getJSONObject("data");
        if (userData == null) {
            throw new CasValidateException("CAS校验成功但未返回用户数据(data)");
        }
        return userData;
    }

    /**
     * CAS 2.0 XML：cas:user 作为登录名，cas:attributes 下的子节点作为用户属性
     */
    private JSONObject parseXmlResponse(String text) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // 防 XXE
            setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            throw new CasValidateException("CAS校验返回XML解析失败: " + abbreviate(text));
        }

        Node failure = firstNode(document, "authenticationFailure");
        if (failure != null) {
            Node failureCode = failure.getAttributes() == null ? null : failure.getAttributes().getNamedItem("code");
            throw new CasValidateException("CAS票据校验失败: "
                    + (failureCode != null ? failureCode.getNodeValue() + " " : "")
                    + failure.getTextContent().trim());
        }
        Node user = firstNode(document, "user");
        if (user == null || user.getTextContent().trim().isEmpty()) {
            throw new CasValidateException("CAS校验XML中未返回cas:user: " + abbreviate(text));
        }

        JSONObject userData = new JSONObject();
        Node attributes = firstNode(document, "attributes");
        if (attributes != null) {
            NodeList children = attributes.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    userData.put(stripPrefix(child.getNodeName()), child.getTextContent().trim());
                }
            }
        }
        String casUser = user.getTextContent().trim();
        userData.put("user", casUser);
        fillUsernameIfAbsent(userData, casUser);
        return userData;
    }

    /**
     * 现场 /sso/validate 纯文本返回：
     * - 成功：yes\nxitongceshi.cd / yes,xitongceshi.cd / yes xitongceshi.cd
     * - 失败：no / no,xxx / false / error / fail
     */
    private JSONObject parsePlainTextResponse(String text) {
        if (text.isEmpty()) {
            throw new CasValidateException("CAS校验返回为空");
        }
        String lower = text.toLowerCase();
        if (!lower.startsWith("yes")) {
            throw new CasValidateException("CAS票据校验失败: " + abbreviate(text));
        }
        // yes 后面可能是逗号、竖线、换行、制表符、空格，账号只取第一段
        String[] parts = text.split("[,|\\r\\n\\t ]+");
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            throw new CasValidateException("CAS文本校验成功但未返回账号: " + abbreviate(text));
        }
        String account = parts[1].trim();
        JSONObject userData = new JSONObject();
        userData.put("user", account);
        fillUsernameIfAbsent(userData, account);
        return userData;
    }

    /**
     * XML/纯文本中 cas:user 就是登录名，补到配置的登录名字段上，保证后续提取一致
     */
    private void fillUsernameIfAbsent(JSONObject userData, String casUser) {
        String key = config.getAttribute().getUsername();
        if (key != null && !key.isEmpty() && getFieldString(userData, key) == null) {
            userData.put(key, casUser);
        }
    }

    private static Node firstNode(Document document, String localName) {
        NodeList nodes = document.getElementsByTagName("cas:" + localName);
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName(localName);
        }
        return nodes.getLength() > 0 ? nodes.item(0) : null;
    }

    private static String stripPrefix(String nodeName) {
        int idx = nodeName.indexOf(':');
        return idx >= 0 ? nodeName.substring(idx + 1) : nodeName;
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // 某些 JDK/解析器不支持该特性，忽略即可
        }
    }

    /**
     * 构建 CAS 登录地址: {server}{loginPath}?appid=xxx&service=回调地址
     */
    private String buildCasLoginUrl(String serviceUrl) {
        return config.getCasLoginUrl() + "&service=" + urlEncode(serviceUrl);
    }

    /**
     * 构建校验用的 service URL
     * getAuthUrl 与 handleCallback 必须使用同一规则，否则 CAS 校验会因 service 不一致失败。
     * serviceIncludeRedirect=false 时 service 固定为注册的回调地址，redirect_uri/state 依赖 Session 恢复。
     */
    private String buildServiceUrl(String redirectUri, String state) {
        String serviceUrl = config.getServiceUrl();
        if (!config.isServiceIncludeRedirect()) {
            return serviceUrl;
        }
        String separator = serviceUrl.contains("?") ? "&" : "?";
        if (redirectUri != null && !redirectUri.isEmpty()) {
            serviceUrl = serviceUrl + separator + "redirect_uri=" + urlEncode(redirectUri);
            separator = "&";
        }
        if (state != null && !state.isEmpty()) {
            serviceUrl = serviceUrl + separator + "state=" + urlEncode(state);
        }
        return serviceUrl;
    }

    /**
     * 恢复原始 redirectUri：请求参数 > state 暂存 > Session
     */
    private String resolveRedirectUri(HttpServletRequest request, HttpSession session) {
        String redirectUri = request.getParameter("redirect_uri");
        if (redirectUri == null || redirectUri.isEmpty()) {
            String state = resolveState(request, session);
            if (state != null && !state.isEmpty()) {
                redirectUri = globalStoreService.getTmpValue("oauth_state:" + state);
            }
        }
        if ((redirectUri == null || redirectUri.isEmpty()) && session.getAttribute(SESSION_REDIRECT_URI_KEY) != null) {
            redirectUri = String.valueOf(session.getAttribute(SESSION_REDIRECT_URI_KEY));
        }
        return redirectUri;
    }

    private String resolveState(HttpServletRequest request, HttpSession session) {
        String state = request.getParameter("state");
        if ((state == null || state.isEmpty()) && session.getAttribute(SESSION_STATE_KEY) != null) {
            state = String.valueOf(session.getAttribute(SESSION_STATE_KEY));
        }
        return state;
    }

    /**
     * 从 userData 中提取登录名
     * 优先使用配置的字段名（默认 loginid），未命中时尝试常见字段名
     */
    private String extractUsername(JSONObject userData, String configuredKey) {
        String value = getFieldString(userData, configuredKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // 常见登录名字段兜底
        String[] fallbackKeys = {"loginid", "loginId", "loginName", "username", "userName", "account", "user",
                "userid", "workcode"};
        for (String key : fallbackKeys) {
            value = getFieldString(userData, key);
            if (value != null && !value.isEmpty()) {
                log.info("登录名未命中配置字段[{}]，使用兜底字段[{}]", configuredKey, key);
                return value;
            }
        }
        throw new CasValidateException("CAS返回的userData中未找到登录名字段，userData=" + userData.toJSONString());
    }

    /**
     * 从 JSONObject 中安全获取字符串值
     */
    private String getFieldString(JSONObject userData, String key) {
        if (userData == null || key == null) {
            return null;
        }
        Object value = userData.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * CAS 票据校验失败异常（区分于网络等系统异常）
     */
    private static class CasValidateException extends RuntimeException {
        CasValidateException(String message) {
            super(message);
        }
    }

    @Override
    public UserInfo getUserInfo(String code) {
        debug("[1/3 getUserInfo] input code={}", mask(code));
        // 根据 code 取出暂存的用户信息
        CasUserInfo casUserInfo = globalStoreService.getTmpValue("code:" + code);
        if (casUserInfo == null) {
            throw new RuntimeException("无效或已过期的code: " + mask(code));
        }

        // 删除已使用的 code（一次性消费）
        globalStoreService.setTmpValue("code:" + code, null, 0);
        debug("[2/3 getUserInfo] code consumed, username={}", casUserInfo.username);

        // 从 HR 同步数据中补充信息
        String username = casUserInfo.username;
        UserListItem hrUser = hrSyncStoreService.getUserByUsername(username);

        UserInfo userInfo = new UserInfo();
        userInfo.setUsername(username);
        // 优先使用 CAS 返回的姓名，纯文本/XML 无姓名时使用 HR 同步数据
        if (casUserInfo.memberName != null && !casUserInfo.memberName.isEmpty()) {
            userInfo.setMemberName(casUserInfo.memberName);
        } else if (hrUser != null) {
            userInfo.setMemberName(hrUser.getMemberName());
        }

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

        log.info("泛微SSO getUserInfo: username={}, memberName={}", username, userInfo.getMemberName());
        debug("[3/3 getUserInfo] output username={}, memberName={}, contactPresent={}", username,
                userInfo.getMemberName(), userInfo.getContact() != null);
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

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("当前JVM不支持UTF-8编码", e);
        }
    }

    private void debug(String message, Object... args) {
        if (config.isDebug()) {
            log.info("[SSO-DEBUG] " + message, args);
        }
    }

    private String mask(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 10) {
            return "***";
        }
        return value.substring(0, 5) + "..." + value.substring(value.length() - 4);
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
