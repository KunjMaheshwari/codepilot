package com.codepilot.service;

import com.codepilot.model.Review;
import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewJobService {

    private final ReviewJobRepository reviewJobRepository;

    @Transactional
    public void updateJobStatus(UUID jobId, String status) {
        reviewJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            reviewJobRepository.save(job);
        });
    }

    @Transactional
    public ReviewJob createJob(Review review) {
        ReviewJob job = new ReviewJob();
        job.setReview(review);
        job.setStatus("PENDING");
        return reviewJobRepository.save(job);
    }

    @Transactional
    public void updateStatus(ReviewJob job, String status) {
        job.setStatus(status);
        reviewJobRepository.save(job);
    }

    @Transactional
    public void markFailed(ReviewJob job, String reason) {
        job.setStatus("FAILED");
        job.setErrorMessage(reason);
        reviewJobRepository.save(job);
    }
}
