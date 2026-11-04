package com.codepilot.controller;

import com.codepilot.model.User;
import com.codepilot.service.GitHubIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final GitHubIntegrationService gitHubIntegrationService;

    @GetMapping("/github/callback")
    public ResponseEntity<Void> githubCallback(@AuthenticationPrincipal OAuth2User oauth2User) {
        log.info("Received OAuth callback for user: {}", oauth2User.getName());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("http://localhost:3000/dashboard"))
                .build();
    }

    @DeleteMapping("/github")
    public ResponseEntity<Void> disconnectGitHub(@AuthenticationPrincipal User user) {
        gitHubIntegrationService.disconnectGitHub(user);
        return ResponseEntity.noContent().build();
    }
}
