package io.cardioops.api.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证浏览器不能绕过 DevicePlatform Admin，直接伪造客服身份调用 CardioOps。 */
class CustomerServiceApiSecurityFilterTest {

    @Test
    void failsClosedWhenServiceTokenIsNotConfigured() throws Exception {
        var properties = new CustomerServiceApiProperties();
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();

        new CustomerServiceApiSecurityFilter(properties).doFilter(
                request(null), response, (req, resp) -> passed.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(passed).isFalse();
    }

    @Test
    void rejectsInvalidServiceCredential() throws Exception {
        var properties = properties("trusted-token");
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();

        new CustomerServiceApiSecurityFilter(properties).doFilter(
                request("Bearer wrong-token"), response, (req, resp) -> passed.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(passed).isFalse();
    }

    @Test
    void allowsTrustedAdminBffCredential() throws Exception {
        var properties = properties("trusted-token");
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean();

        new CustomerServiceApiSecurityFilter(properties).doFilter(
                request("Bearer trusted-token"), response, (req, resp) -> passed.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(passed).isTrue();
    }

    @Test
    void approvalEndpointRequiresSeparateApprovalCredential() throws Exception {
        var properties = properties("ordinary-token");
        properties.setApprovalServiceToken("approval-token");
        var rejected = new MockHttpServletResponse();
        var accepted = new MockHttpServletResponse();
        var passed = new AtomicBoolean();
        var filter = new CustomerServiceApiSecurityFilter(properties);

        filter.doFilter(approvalRequest("Bearer ordinary-token"), rejected,
                (req, resp) -> passed.set(true));
        filter.doFilter(approvalRequest("Bearer approval-token"), accepted,
                (req, resp) -> passed.set(true));

        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(passed).isTrue();
    }

    private MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/internal/v1/customer-service/cases");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private MockHttpServletRequest approvalRequest(String authorization) {
        var request = new MockHttpServletRequest("POST",
                "/internal/v1/customer-service/cases/incident-1/closure-proposals/proposal-1/decision");
        request.addHeader("Authorization", authorization);
        return request;
    }

    private CustomerServiceApiProperties properties(String token) {
        var properties = new CustomerServiceApiProperties();
        properties.setServiceToken(token);
        return properties;
    }
}
