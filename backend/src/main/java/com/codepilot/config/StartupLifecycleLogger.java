package com.codepilot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits structured startup and graceful-shutdown lifecycle logs without exposing secrets.
 */
@Slf4j
@Component
public class StartupLifecycleLogger {

    @Value("${codepilot.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${codepilot.review.async.enabled:true}")
    private boolean asyncReviewEnabled;

    @Value("${codepilot.review.publish-comments-enabled:false}")
    private boolean publishCommentsEnabled;

    @Value("${codepilot.security.cookie-secure:false}")
    private boolean cookieSecure;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info(
                "CodePilot backend is ready (frontendUrl={}, asyncReviewEnabled={}, publishCommentsEnabled={}, cookieSecure={})",
                frontendUrl,
                asyncReviewEnabled,
                publishCommentsEnabled,
                cookieSecure);
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        log.info("CodePilot backend is shutting down gracefully");
    }
}

