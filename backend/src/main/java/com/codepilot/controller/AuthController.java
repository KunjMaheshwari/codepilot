package com.codepilot.controller;

import com.codepilot.dto.UserDto;
import com.codepilot.service.GitHubIntegrationService;
import com.codepilot.service.RepositoryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final GitHubIntegrationService gitHubIntegrationService;
    private final RepositoryService repositoryService;

    @Value("${codepilot.frontend.url:http://localhost:3000}")
    private String frontendUrl = "http://localhost:3000";

    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        if (token != null) {
            return ResponseEntity.noContent()
                    .header("X-XSRF-TOKEN", token.getToken())
                    .build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserDto me() {
        return new UserDto(repositoryService.getAuthenticatedUser());
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Void> githubCallback(@AuthenticationPrincipal OAuth2User oauth2User) {
        if (oauth2User != null) {
            log.info("Received OAuth callback for user: {}", oauth2User.getName());
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendUrl + "/dashboard"))
                .build();
    }

    @DeleteMapping("/github")
    public ResponseEntity<Void> disconnectGitHub() {
        gitHubIntegrationService.disconnectGitHub(repositoryService.getAuthenticatedUser());
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpSession session = attrs.getRequest().getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
