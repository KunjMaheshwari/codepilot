package com.codepilot.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    @Test
    void allowsRequestsWithinAuthWindowAndBlocksWhenExceeded() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "authLimit", 2);
        ReflectionTestUtils.setField(filter, "webhookLimit", 5);

        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/me");
            req.setRemoteAddr("198.51.100.10");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }

        MockHttpServletRequest blockedReq = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        blockedReq.setRemoteAddr("198.51.100.10");
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blockedReq, blockedRes, chain);

        assertThat(blockedRes.getStatus()).isEqualTo(429);
        assertThat(blockedRes.getContentAsString()).contains("Too Many Requests");
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void separatesWebhookAndAuthBucketsAndIgnoresSpoofedForwardedHeader() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "authLimit", 1);
        ReflectionTestUtils.setField(filter, "webhookLimit", 2);

        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest firstAuth = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        firstAuth.setRemoteAddr("203.0.113.5");
        firstAuth.addHeader("X-Forwarded-For", "10.0.0.1");
        MockHttpServletResponse firstAuthRes = new MockHttpServletResponse();
        filter.doFilter(firstAuth, firstAuthRes, chain);
        assertThat(firstAuthRes.getStatus()).isEqualTo(200);

        // Spoofed X-Forwarded-For from same remoteAddr must still hit the same auth bucket
        MockHttpServletRequest spoofedAuth = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        spoofedAuth.setRemoteAddr("203.0.113.5");
        spoofedAuth.addHeader("X-Forwarded-For", "10.0.0.99");
        MockHttpServletResponse spoofedAuthRes = new MockHttpServletResponse();
        filter.doFilter(spoofedAuth, spoofedAuthRes, chain);
        assertThat(spoofedAuthRes.getStatus()).isEqualTo(429);

        // Webhook bucket for the same IP remains independent
        MockHttpServletRequest webhookReq = new MockHttpServletRequest("POST", "/api/v1/webhooks/github");
        webhookReq.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse webhookRes = new MockHttpServletResponse();
        filter.doFilter(webhookReq, webhookRes, chain);
        assertThat(webhookRes.getStatus()).isEqualTo(200);
    }

    @Test
    void bypassesUnthrottledPathsAndDisabledFilter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "authLimit", 1);

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest repoReq = new MockHttpServletRequest("GET", "/api/v1/repositories");
        repoReq.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse repoRes = new MockHttpServletResponse();
        filter.doFilter(repoReq, repoRes, chain);
        assertThat(repoRes.getStatus()).isEqualTo(200);

        ReflectionTestUtils.setField(filter, "enabled", false);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest authReq = new MockHttpServletRequest("GET", "/api/v1/auth/me");
            authReq.setRemoteAddr("203.0.113.7");
            MockHttpServletResponse authRes = new MockHttpServletResponse();
            filter.doFilter(authReq, authRes, chain);
            assertThat(authRes.getStatus()).isEqualTo(200);
        }
        verify(chain, times(4)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
