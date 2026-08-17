package com.hackerton.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<RecommendationEntity, Long> {

    List<RecommendationEntity> findAllByOrderByCreatedAtDesc();

    void deleteByCreatedAtBefore(LocalDateTime threshold);

}
