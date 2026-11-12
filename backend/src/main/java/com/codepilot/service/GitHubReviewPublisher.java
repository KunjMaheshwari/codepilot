package com.codepilot.service;

import com.codepilot.ai.DiffParser;
import com.codepilot.exception.GitHubApiException;
import com.codepilot.model.ConnectedGitHubAccount;
import com.codepilot.model.GitHubCommentPublication;
import com.codepilot.model.PullRequest;
import com.codepilot.model.Repository;
import com.codepilot.model.Review;
import com.codepilot.model.ReviewFinding;
import com.codepilot.model.User;
import com.codepilot.repository.ConnectedGitHubAccountRepository;
import com.codepilot.repository.GitHubCommentPublicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubReviewPublisher {

    private static final String MARKER_PREFIX = "<!-- codepilot:idempotency:";
    private static final String MARKER_SUFFIX = " -->";

    private final GitHubApiClient gitHubApiClient;
    private final ConnectedGitHubAccountRepository connectedGitHubAccountRepository;
    private final GitHubCommentPublicationRepository publicationRepository;
    private final EncryptionService encryptionService;

    @Value("${codepilot.review.publish-comments-enabled:false}")
    private boolean publishCommentsEnabled;

    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong duplicatePreventedCount = new AtomicLong();
    private final AtomicLong summaryFallbackCount = new AtomicLong();

    public long getPublishedCount() {
        return publishedCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public long getDuplicatePreventedCount() {
        return duplicatePreventedCount.get();
    }

    public long getSummaryFallbackCount() {
        return summaryFallbackCount.get();
    }

    public void publishReviewFindings(
            Review review,
            PullRequest pullRequest,
            List<ReviewFinding> findings,
            List<DiffParser.ParsedFile> parsedFiles) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        if (!publishCommentsEnabled) {
            log.info("github_comment_publishing_disabled reviewId={} findingsCount={}",
                    review != null ? review.getId() : null, findings.size());
            return;
        }
        if (review == null || pullRequest == null || pullRequest.getRepository() == null
                || pullRequest.getRepository().getOwner() == null) {
            throw new IllegalArgumentException("Review publication requires valid review, pull request, repository, and owner");
        }

        Repository repository = pullRequest.getRepository();
        User owner = repository.getOwner();
        if (review.getPullRequest() != null && !review.getPullRequest().getId().equals(pullRequest.getId())) {
            throw new IllegalStateException("Cross-tenant or mismatched pull request publication rejected");
        }
        if (repository.getFullName() == null || !repository.getFullName().contains("/")) {
            throw new IllegalStateException("Repository has an invalid GitHub full name");
        }
        String[] repoParts = repository.getFullName().split("/", 2);
        if (repoParts.length != 2 || repoParts[0].isBlank() || repoParts[1].isBlank()) {
            throw new IllegalStateException("Repository has an invalid GitHub full name");
        }
        String repoOwner = repoParts[0];
        String repoName = repoParts[1];

        ConnectedGitHubAccount account = connectedGitHubAccountRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new IllegalStateException("Repository owner does not have a connected GitHub account"));
        String accessToken = encryptionService.decrypt(account.getAccessToken());

        String headSha = firstNonBlank(pullRequest.getHeadSha(), review.getHeadSha());
        Map<String, Set<Integer>> addedLinesByPath = indexAddedLines(parsedFiles);
        List<ReviewFinding> fallbackFindings = new ArrayList<>();

        for (ReviewFinding finding : findings) {
            if (!isPublishableFinding(finding)) {
                log.warn("github_comment_skipped_unvalidated reviewId={} findingId={}",
                        review.getId(), finding != null ? finding.getId() : null);
                continue;
            }
            if (finding.getReview() != null && review.getId() != null
                    && !review.getId().equals(finding.getReview().getId())) {
                throw new IllegalStateException("Finding does not belong to target review");
            }

            boolean canAnchorInline = headSha != null
                    && finding.getLineNumber() != null
                    && finding.getLineNumber() > 0
                    && addedLinesByPath.getOrDefault(finding.getFilePath(), Set.of())
                            .contains(finding.getLineNumber());

            if (!canAnchorInline) {
                fallbackFindings.add(finding);
                continue;
            }

            String idempotencyKey = inlineKey(repository, pullRequest, headSha, finding);
            GitHubCommentPublication publication = publicationRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseGet(() -> createPublicationRecord(
                            review, pullRequest, repository, finding, idempotencyKey, "INLINE", headSha));

            if ("PUBLISHED".equals(publication.getStatus())) {
                duplicatePreventedCount.incrementAndGet();
                log.info("github_comment_duplicate_prevented reviewId={} idempotencyKey={} type=INLINE",
                        review.getId(), idempotencyKey);
                continue;
            }
            if ("AMBIGUOUS_TIMEOUT".equals(publication.getStatus())) {
                Optional<Long> existingId = reconcileInlineComment(
                        accessToken, repoOwner, repoName, pullRequest.getPrNumber(), idempotencyKey);
                if (existingId == null) {
                    // Reconciliation GET failed; do not risk duplicate POST
                    continue;
                }
                if (existingId.isPresent()) {
                    markPublished(publication, existingId.get());
                    duplicatePreventedCount.incrementAndGet();
                    log.info("github_comment_reconciled_existing reviewId={} idempotencyKey={} commentId={}",
                            review.getId(), idempotencyKey, existingId.get());
                    continue;
                }
            }

            publication.setAttemptCount(publication.getAttemptCount() + 1);
            String body = formatInlineComment(finding, idempotencyKey);
            try {
                Long commentId = gitHubApiClient.createPullRequestReviewComment(
                        accessToken,
                        repoOwner,
                        repoName,
                        pullRequest.getPrNumber(),
                        body,
                        headSha,
                        finding.getFilePath(),
                        finding.getLineNumber());
                markPublished(publication, commentId);
                publishedCount.incrementAndGet();
                log.info("github_comment_published reviewId={} prNumber={} type=INLINE commentId={}",
                        review.getId(), pullRequest.getPrNumber(), commentId);
            } catch (GitHubApiException exception) {
                if (exception.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) {
                    publication.setStatus("FALLBACK_TO_SUMMARY");
                    publication.setLastError(truncate(exception.getMessage()));
                    publicationRepository.save(publication);
                    fallbackFindings.add(finding);
                    summaryFallbackCount.incrementAndGet();
                    log.warn("github_comment_inline_fallback reviewId={} prNumber={} reason=unprocessable_diff_position",
                            review.getId(), pullRequest.getPrNumber());
                } else if (exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT) {
                    publication.setStatus("AMBIGUOUS_TIMEOUT");
                    publication.setLastError(truncate(exception.getMessage()));
                    publicationRepository.save(publication);
                    failureCount.incrementAndGet();
                    log.warn("github_comment_publish_ambiguous_timeout reviewId={} prNumber={} idempotencyKey={}",
                            review.getId(), pullRequest.getPrNumber(), idempotencyKey);
                } else {
                    publication.setStatus("FAILED");
                    publication.setLastError(truncate(exception.getMessage()));
                    publicationRepository.save(publication);
                    failureCount.incrementAndGet();
                    log.warn("github_comment_publish_failed reviewId={} prNumber={} status={}",
                            review.getId(), pullRequest.getPrNumber(), exception.getStatus().value());
                    if (isFatalRepoOrAuthError(exception.getStatus())) {
                        return;
                    }
                }
            }
        }

        if (!fallbackFindings.isEmpty()) {
            publishSummaryFallback(
                    review, pullRequest, repository, accessToken, repoOwner, repoName, headSha, fallbackFindings);
        }
    }

    private void publishSummaryFallback(
            Review review,
            PullRequest pullRequest,
            Repository repository,
            String accessToken,
            String repoOwner,
            String repoName,
            String headSha,
            List<ReviewFinding> fallbackFindings) {
        String summaryKey = summaryKey(repository, pullRequest, headSha);
        GitHubCommentPublication publication = publicationRepository
                .findByIdempotencyKey(summaryKey)
                .orElseGet(() -> createPublicationRecord(
                        review, pullRequest, repository, null, summaryKey, "SUMMARY", headSha));

        if ("PUBLISHED".equals(publication.getStatus())) {
            duplicatePreventedCount.incrementAndGet();
            log.info("github_comment_duplicate_prevented reviewId={} idempotencyKey={} type=SUMMARY",
                    review.getId(), summaryKey);
            return;
        }
        if ("AMBIGUOUS_TIMEOUT".equals(publication.getStatus())) {
            Optional<Long> existingId = reconcileSummaryComment(
                    accessToken, repoOwner, repoName, pullRequest.getPrNumber(), summaryKey);
            if (existingId == null) {
                return;
            }
            if (existingId.isPresent()) {
                markPublished(publication, existingId.get());
                duplicatePreventedCount.incrementAndGet();
                log.info("github_comment_reconciled_existing reviewId={} idempotencyKey={} type=SUMMARY commentId={}",
                        review.getId(), summaryKey, existingId.get());
                return;
            }
        }

        publication.setAttemptCount(publication.getAttemptCount() + 1);
        String body = formatSummaryComment(fallbackFindings, summaryKey);
        try {
            Long commentId = gitHubApiClient.createIssueComment(
                    accessToken, repoOwner, repoName, pullRequest.getPrNumber(), body);
            markPublished(publication, commentId);
            publishedCount.incrementAndGet();
            log.info("github_comment_published reviewId={} prNumber={} type=SUMMARY commentId={}",
                    review.getId(), pullRequest.getPrNumber(), commentId);
        } catch (GitHubApiException exception) {
            if (exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT) {
                publication.setStatus("AMBIGUOUS_TIMEOUT");
                publication.setLastError(truncate(exception.getMessage()));
                publicationRepository.save(publication);
                failureCount.incrementAndGet();
                log.warn("github_comment_publish_ambiguous_timeout reviewId={} prNumber={} type=SUMMARY",
                        review.getId(), pullRequest.getPrNumber());
            } else {
                publication.setStatus("FAILED");
                publication.setLastError(truncate(exception.getMessage()));
                publicationRepository.save(publication);
                failureCount.incrementAndGet();
                log.warn("github_comment_publish_failed reviewId={} prNumber={} type=SUMMARY status={}",
                        review.getId(), pullRequest.getPrNumber(), exception.getStatus().value());
            }
        }
    }

    private Optional<Long> reconcileInlineComment(
            String accessToken, String owner, String repo, int prNumber, String idempotencyKey) {
        try {
            List<Map<String, Object>> comments = gitHubApiClient.listPullRequestReviewComments(
                    accessToken, owner, repo, prNumber, 1, 100);
            return findMatchingCommentId(comments, idempotencyKey);
        } catch (GitHubApiException exception) {
            log.warn("github_comment_reconcile_failed prNumber={} type=INLINE status={}",
                    prNumber, exception.getStatus().value());
            return null;
        }
    }

    private Optional<Long> reconcileSummaryComment(
            String accessToken, String owner, String repo, int prNumber, String idempotencyKey) {
        try {
            List<Map<String, Object>> comments = gitHubApiClient.listIssueComments(
                    accessToken, owner, repo, prNumber, 1, 100);
            return findMatchingCommentId(comments, idempotencyKey);
        } catch (GitHubApiException exception) {
            log.warn("github_comment_reconcile_failed prNumber={} type=SUMMARY status={}",
                    prNumber, exception.getStatus().value());
            return null;
        }
    }

    private Optional<Long> findMatchingCommentId(List<Map<String, Object>> comments, String idempotencyKey) {
        if (comments == null || comments.isEmpty()) {
            return Optional.empty();
        }
        String marker = marker(idempotencyKey);
        for (Map<String, Object> comment : comments) {
            Object body = comment.get("body");
            if (body instanceof String text && text.contains(marker)) {
                Object id = comment.get("id");
                return id instanceof Number number ? Optional.of(number.longValue()) : Optional.of(0L);
            }
        }
        return Optional.empty();
    }

    private GitHubCommentPublication createPublicationRecord(
            Review review,
            PullRequest pullRequest,
            Repository repository,
            ReviewFinding finding,
            String idempotencyKey,
            String type,
            String headSha) {
        GitHubCommentPublication publication = new GitHubCommentPublication();
        publication.setReview(review);
        publication.setPullRequest(pullRequest);
        publication.setRepository(repository);
        publication.setFinding(finding);
        publication.setIdempotencyKey(idempotencyKey);
        publication.setPublicationType(type);
        publication.setStatus("PENDING");
        publication.setHeadSha(headSha);
        publication.setFilePath(finding != null ? finding.getFilePath() : null);
        publication.setLineNumber(finding != null ? finding.getLineNumber() : null);
        return publicationRepository.save(publication);
    }

    private void markPublished(GitHubCommentPublication publication, Long commentId) {
        publication.setStatus("PUBLISHED");
        publication.setGithubCommentId(commentId);
        publication.setLastError(null);
        publication.setPublishedAt(ZonedDateTime.now());
        publicationRepository.save(publication);
    }

    private Map<String, Set<Integer>> indexAddedLines(List<DiffParser.ParsedFile> parsedFiles) {
        if (parsedFiles == null || parsedFiles.isEmpty()) {
            return Map.of();
        }
        return parsedFiles.stream()
                .filter(file -> file.getFilePath() != null && file.getAddedLines() != null)
                .collect(Collectors.toMap(
                        DiffParser.ParsedFile::getFilePath,
                        file -> Set.copyOf(file.getAddedLines()),
                        (left, right) -> left));
    }

    private boolean isPublishableFinding(ReviewFinding finding) {
        return finding != null
                && isNonBlank(finding.getFilePath())
                && isNonBlank(finding.getSeverity())
                && isNonBlank(finding.getTitle())
                && isNonBlank(finding.getDescription())
                && finding.getLineNumber() != null
                && finding.getLineNumber() > 0;
    }

    private boolean isFatalRepoOrAuthError(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED
                || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.NOT_FOUND;
    }

    private String formatInlineComment(ReviewFinding finding, String idempotencyKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(finding.getSeverity()).append("]** ")
                .append(finding.getTitle()).append("\n\n");
        sb.append(finding.getDescription()).append("\n");
        if (isNonBlank(finding.getImpact())) {
            sb.append("\n**Impact:** ").append(finding.getImpact()).append("\n");
        }
        if (isNonBlank(finding.getSuggestedFix())) {
            sb.append("\n**Suggested fix:**\n```suggestion\n")
                    .append(finding.getSuggestedFix()).append("\n```\n");
        }
        sb.append("\n").append(marker(idempotencyKey));
        return sb.toString();
    }

    private String formatSummaryComment(List<ReviewFinding> findings, String idempotencyKey) {
        StringBuilder sb = new StringBuilder("### CodePilot AI Review Findings\n\n");
        for (ReviewFinding finding : findings) {
            sb.append("- **[").append(finding.getSeverity()).append("] ")
                    .append(finding.getTitle()).append("** (`")
                    .append(finding.getFilePath()).append(":").append(finding.getLineNumber()).append("`)\n")
                    .append("  ").append(finding.getDescription()).append("\n");
            if (isNonBlank(finding.getImpact())) {
                sb.append("  - **Impact:** ").append(finding.getImpact()).append("\n");
            }
            if (isNonBlank(finding.getSuggestedFix())) {
                sb.append("  - **Suggested fix:** `").append(finding.getSuggestedFix().replace("\n", " ")).append("`\n");
            }
        }
        sb.append("\n").append(marker(idempotencyKey));
        return sb.toString();
    }

    private String inlineKey(Repository repo, PullRequest pr, String headSha, ReviewFinding finding) {
        String normalizedTitle = finding.getTitle().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String raw = String.join(":",
                String.valueOf(repo.getId()),
                String.valueOf(pr.getPrNumber()),
                headSha == null ? "no-sha" : headSha,
                finding.getFilePath(),
                String.valueOf(finding.getLineNumber()),
                finding.getSeverity(),
                normalizedTitle);
        return sha256(raw);
    }

    private String summaryKey(Repository repo, PullRequest pr, String headSha) {
        String raw = String.join(":",
                String.valueOf(repo.getId()),
                String.valueOf(pr.getPrNumber()),
                headSha == null ? "no-sha" : headSha,
                "SUMMARY");
        return sha256(raw);
    }

    private String marker(String idempotencyKey) {
        return MARKER_PREFIX + idempotencyKey + MARKER_SUFFIX;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (isNonBlank(primary)) {
            return primary;
        }
        return isNonBlank(fallback) ? fallback : null;
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}

