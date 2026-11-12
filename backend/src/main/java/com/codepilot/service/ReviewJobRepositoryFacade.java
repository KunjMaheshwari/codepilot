package com.codepilot.service;

import com.codepilot.model.ReviewJob;
import com.codepilot.repository.ReviewJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewJobRepositoryFacade {

    private final ReviewJobRepository repository;

    public ReviewJob find(UUID id) {
        return repository.findWithReviewById(id).orElse(null);
    }
}
