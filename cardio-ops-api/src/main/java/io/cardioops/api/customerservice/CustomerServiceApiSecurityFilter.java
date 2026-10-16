package io.cardioops.api.customerservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 只保护案件内部路径。共享服务凭证对上才放行。浏览器不能自己写 X-Operator-Id 就当成某位员工。
 * 关单批准使用独立审批凭证。员工权限仍由 Device Platform Admin 校验。
 *
 *
 * 员工身份由 BFF 完成认证，本服务仅验证受信任的 BFF 服务身份，避免维护重复身份体系。
 */
public final class CustomerServiceApiSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceApiSecurityFilter.class);
    private static final String PATH_PREFIX = "/internal/v1/customer-service/cases";
    private static final String APPROVAL_PATH_PART = "/closure-proposals/";
    private static final String APPROVAL_PATH_SUFFIX = "/decision";
    private final CustomerServiceApiProperties properties;

    public CustomerServiceApiSecurityFilter(CustomerServiceApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean approvalRequest = request.getRequestURI().contains(APPROVAL_PATH_PART)
                && request.getRequestURI().endsWith(APPROVAL_PATH_SUFFIX);
        String configured = approvalRequest
                ? properties.getApprovalServiceToken() : properties.getServiceToken();
        if (configured == null || configured.isBlank()) {
            log.error("customer service API rejected because service token is not configured path={}",
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    approvalRequest ? "approval API service token is not configured"
                            : "customer service API service token is not configured");
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String provided = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!constantTimeEquals(configured, provided)) {
            log.warn("customer service API rejected invalid service credential path={}",
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid service credential");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
