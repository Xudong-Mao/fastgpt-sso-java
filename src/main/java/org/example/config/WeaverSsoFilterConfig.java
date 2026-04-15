package org.example.config;

import com.weaver.sso.client.authentication.AuthenticationFilter;
import com.weaver.sso.client.session.SingleSignOutFilter;
import com.weaver.sso.client.session.SingleSignOutHttpSessionListener;
import com.weaver.sso.client.util.AssertionThreadLocalFilter;
import com.weaver.sso.client.util.HttpServletRequestWrapperFilter;
import com.weaver.sso.client.validation.SSO20ProxyReceivingTicketValidationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 泛微 weaversso.jar Filter/Listener 注册配置
 *
 * 根据《ecology统一认证中心V5》文档，配置以下组件：
 * 1. SingleSignOutHttpSessionListener - 单点登出监听器（可选）
 * 2. SingleSignOutFilter - 单点登出过滤器
 * 3. AuthenticationFilter - 认证过滤器（必须）
 * 4. SSO20ProxyReceivingTicketValidationFilter - Ticket校验过滤器（必须）
 * 5. HttpServletRequestWrapperFilter - 请求包裹过滤器（可选，支持 getRemoteUser()）
 * 6. AssertionThreadLocalFilter - Assertion线程本地过滤器（可选）
 *
 * 仅在 app.sso.provider=weaver 时生效
 */
@Configuration
@Profile("weaver")
public class WeaverSsoFilterConfig {

    /**
     * 单点登出监听器
     */
    @Bean
    public ServletListenerRegistrationBean<SingleSignOutHttpSessionListener> singleSignOutListener() {
        ServletListenerRegistrationBean<SingleSignOutHttpSessionListener> listener =
                new ServletListenerRegistrationBean<>();
        listener.setListener(new SingleSignOutHttpSessionListener());
        listener.setOrder(1);
        return listener;
    }

    /**
     * 单点登出过滤器
     * 必须在所有其他 Filter 之前执行
     */
    @Bean
    public FilterRegistrationBean<SingleSignOutFilter> singleSignOutFilter() {
        FilterRegistrationBean<SingleSignOutFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SingleSignOutFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }

    /**
     * CAS 认证过滤器
     * 未登录用户自动重定向到 CAS 登录页
     * 参数：
     *   ssoServerLoginUrl - CAS 登录地址，格式: http://OA地址/login/login.jsp?appid=应用标识
     *   serverName - 本应用服务地址
     *   excludePath - 排除路径，多个用 | 分隔
     */
    @Bean
    public FilterRegistrationBean<AuthenticationFilter> authenticationFilter(WeaverSsoConfig config) {
        FilterRegistrationBean<AuthenticationFilter> registration = new FilterRegistrationBean<>();
        AuthenticationFilter filter = new AuthenticationFilter();
        registration.setFilter(filter);
        registration.addUrlPatterns("/login/oauth/callback");
        registration.addInitParameter("ssoServerLoginUrl", config.getCasLoginUrl());
        registration.addInitParameter("serverName", config.getServerName());
        // 排除不需要CAS认证的路径
        registration.addInitParameter("excludePath",
                "/test|/user/list|/org/list|/sso/getdata/*|/login/oauth/getAuthURL|/login/oauth/getUserInfo|/login/saml/*");
        registration.setOrder(3);
        return registration;
    }

    /**
     * CAS Ticket 校验过滤器
     * 负责校验 CAS 回调中携带的 ticket
     * 参数：
     *   ssoServerUrlPrefix - CAS 服务端 SSO 前缀，格式: http://OA地址/sso
     *   serverName - 本应用服务地址
     */
    @Bean
    public FilterRegistrationBean<SSO20ProxyReceivingTicketValidationFilter> sso20ValidationFilter(WeaverSsoConfig config) {
        FilterRegistrationBean<SSO20ProxyReceivingTicketValidationFilter> registration = new FilterRegistrationBean<>();
        SSO20ProxyReceivingTicketValidationFilter filter = new SSO20ProxyReceivingTicketValidationFilter();
        registration.setFilter(filter);
        registration.addUrlPatterns("/login/oauth/callback");
        // ssoServerUrlPrefix: CAS服务端地址/sso，如 http://192.168.1.100:8080/sso
        registration.addInitParameter("ssoServerUrlPrefix", config.getServerUrl() + "/sso");
        registration.addInitParameter("serverName", config.getServerName());
        registration.setOrder(4);
        return registration;
    }

    /**
     * HttpServletRequest 包裹过滤器
     * 支持通过 request.getRemoteUser() 获取 SSO 登录用户名
     */
    @Bean
    public FilterRegistrationBean<HttpServletRequestWrapperFilter> httpServletRequestWrapperFilter() {
        FilterRegistrationBean<HttpServletRequestWrapperFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HttpServletRequestWrapperFilter());
        registration.addUrlPatterns("/login/oauth/callback");
        registration.setOrder(5);
        return registration;
    }

    /**
     * Assertion 线程本地过滤器
     * 将 Assertion 存入 ThreadLocal，便于代码中通过 AssertionHolder 获取
     */
    @Bean
    public FilterRegistrationBean<AssertionThreadLocalFilter> assertionThreadLocalFilter() {
        FilterRegistrationBean<AssertionThreadLocalFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AssertionThreadLocalFilter());
        registration.addUrlPatterns("/login/oauth/callback");
        registration.setOrder(6);
        return registration;
    }
}
