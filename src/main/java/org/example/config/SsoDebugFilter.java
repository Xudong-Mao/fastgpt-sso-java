package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;

/** Traces the complete CAS callback filter chain when SSO_DEBUG=true. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.sso.debug", havingValue = "true")
public class SsoDebugFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SsoDebugFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.equals("/login/oauth/getAuthURL")
                && !uri.equals("/login/oauth/callback")
                && !uri.equals("/login/oauth/getUserInfo");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        logState("ENTER", request, response);
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error("[SSO-DEBUG] FILTER-ERROR method={}, uri={}, error={}",
                    request.getMethod(), request.getRequestURI(), e.getMessage(), e);
            throw e;
        } finally {
            logState("EXIT", request, response);
            log.info("[SSO-DEBUG] COMPLETE status={}, elapsedMs={}, location={}", response.getStatus(),
                    System.currentTimeMillis() - startedAt, response.getHeader("Location"));
        }
    }

    private void logState(String phase, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Object casUser = session == null ? null
                : session.getAttribute(WeaverSsoConfig.SESSION_CAS_USER_KEY);
        log.info("[SSO-DEBUG] {} method={}, uri={}, query={}, status={}, session={}, sessionAttrs={}, "
                        + "casLoginUser={}, remoteUser={}",
                phase, request.getMethod(), request.getRequestURI(), sanitizeQuery(request.getQueryString()),
                response.getStatus(), session == null ? "none" : session.getId(),
                session == null ? Collections.emptyList() : Collections.list(session.getAttributeNames()),
                casUser != null, request.getRemoteUser());
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return null;
        }
        return query.replaceAll("(?i)(ticket|code|token)=([^&]+)", "$1=***");
    }
}
