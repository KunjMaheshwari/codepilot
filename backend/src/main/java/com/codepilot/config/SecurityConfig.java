package com.codepilot.config;

import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.UserRepository;
import com.codepilot.service.GitHubIntegrationService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final ConnectedGitHubAccountRepository connectedGitHubAccountRepository;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final RateLimitFilter rateLimitFilter;

    @Value("${codepilot.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${codepilot.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Value("${codepilot.security.swagger-public:false}")
    private boolean swaggerPublic;

    @Value("${codepilot.security.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${codepilot.security.cookie-same-site:Lax}")
    private String cookieSameSite;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .secure(cookieSecure)
                .sameSite(cookieSameSite));

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(requestHandler)
                .ignoringRequestMatchers("/api/v1/webhooks/github")
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.migrateSession())
            )
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/oauth2/**", "/login/oauth2/code/**",
                        "/api/v1/webhooks/github", "/api/v1/auth/csrf").permitAll();
                if (swaggerPublic) {
                    auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(this.oauth2UserService())
                )
                .defaultSuccessUrl(frontendUrl + "/dashboard", true)
            )
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .logoutSuccessHandler((request, response, authentication) ->
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT))
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> trustedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank() && !"*".equals(origin))
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(trustedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Authorization", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

        return request -> {
            OAuth2User oAuth2User = delegate.loadUser(request);

            Map<String, Object> attributes = oAuth2User.getAttributes();
            String githubUserId = attributes.get("id").toString();
            String githubUsername = (String) attributes.get("login");
            String email = (String) attributes.get("email");
            if (email == null || email.isBlank()) {
                email = githubUsername + "@users.noreply.github.com";
            }

            String finalEmail = email;
            User user = connectedGitHubAccountRepository.findByGithubUserId(githubUserId)
                    .map(ConnectedGitHubAccount::getUser)
                    .orElseGet(() -> userRepository.findByEmail(finalEmail)
                            .orElseGet(() -> {
                                User newUser = new User();
                                newUser.setUsername(githubUsername);
                                newUser.setEmail(finalEmail);
                                return userRepository.save(newUser);
                            }));

            gitHubIntegrationService.linkAccount(user, githubUserId, githubUsername, request.getAccessToken().getTokenValue());

            List<GrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
            return new DefaultOAuth2User(authorities, attributes, "login");
        };
    }
}
