package com.codepilot.repository;

import com.codepilot.model.ReviewSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewSettingsRepository extends JpaRepository<ReviewSettings, UUID> {
    Optional<ReviewSettings> findByRepositoryId(UUID repositoryId);
}
