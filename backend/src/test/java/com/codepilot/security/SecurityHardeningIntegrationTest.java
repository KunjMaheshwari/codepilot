package com.codepilot.security;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.Repository;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.RepositoryRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectedGitHubAccountRepository accountRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private EncryptionService encryptionService;

    private User userA;
    private User userB;
    private Repository repoB;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        repositoryRepository.deleteAll();
        userRepository.deleteAll();

        userA = new User();
        userA.setUsername("sec-user-a-" + UUID.randomUUID());
        userA.setEmail("seca-" + UUID.randomUUID() + "@example.com");
        userA = userRepository.save(userA);

        ConnectedGitHubAccount accountA = new ConnectedGitHubAccount();
        accountA.setUser(userA);
        accountA.setGithubUserId("10001");
        accountA.setGithubUsername(userA.getUsername());
        accountA.setAccessToken(encryptionService.encrypt("gho_super_secret_token_value"));
        accountRepository.save(accountA);

        userB = new User();
        userB.setUsername("sec-user-b-" + UUID.randomUUID());
        userB.setEmail("secb-" + UUID.randomUUID() + "@example.com");
        userB = userRepository.save(userB);

        repoB = new Repository();
        repoB.setOwner(userB);
        repoB.setName("private-b");
        repoB.setFullName("org-b/private-b");
        repoB.setGithubRepoId("99902");
        repoB.setIsPrivate(true);
        repoB = repositoryRepository.save(repoB);
    }

    @Test
    void csrfEndpointIssuesXsrfCookie() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void mutatingRequestsRequireValidCsrfToken() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/github")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        })))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/auth/github")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        }))
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/auth/github")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        }))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void corsAllowsTrustedOriginAndRejectsUntrustedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/dashboard")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-XSRF-TOKEN,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/api/v1/dashboard")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void crossTenantRepositoryAccessIsDeniedAndMeDoesNotLeakTokens() throws Exception {
        MvcResult meResult = mockMvc.perform(get("/api/v1/auth/me")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(userA.getEmail()))
                .andReturn();

        assertThat(meResult.getResponse().getContentAsString())
                .doesNotContain("gho_super_secret_token_value")
                .doesNotContain("accessToken");

        mockMvc.perform(get("/api/v1/repositories/" + repoB.getId())
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        })))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutInvalidatesSessionAndClearsCookies() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(session)
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("id", 10001);
                            attrs.put("login", userA.getUsername());
                            attrs.put("email", userA.getEmail());
                        }))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void webhookRejectsInvalidSignatureWithoutCsrfAndSwaggerIsNotPublicByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", "sec-deliv-1")
                        .header("X-Hub-Signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void oauthAuthorizationHonorsForwardedHttpsHeadersBehindReverseProxy() throws Exception {
        assertThat(applicationContext.getEnvironment().getProperty("server.forward-headers-strategy"))
                .isEqualTo("framework");

        org.springframework.web.filter.ForwardedHeaderFilter filter =
                new org.springframework.web.filter.ForwardedHeaderFilter();
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/oauth2/authorization/github");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "api.codepilot.example");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(req.getScheme()).isEqualTo("https");
            assertThat(req.getServerName()).isEqualTo("api.codepilot.example");
            assertThat(req.getServerPort()).isEqualTo(443);
        });
    }
}

