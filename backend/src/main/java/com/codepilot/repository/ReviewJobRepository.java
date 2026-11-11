package com.codepilot.repository;

import com.codepilot.model.ReviewJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReviewJobRepository extends JpaRepository<ReviewJob, UUID> {
}
