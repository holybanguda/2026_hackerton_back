package com.hackerton.recommendation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationScheduler {
    private final RecommendationRepository recommendationRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void deleteExpiredRecommendations() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(48);
        recommendationRepository.deleteByCreatedAtBefore(threshold);
        log.info("48시간이 지난 추천 히스토리 자동 삭제 완료 (기준 시간: {})", threshold);
    }
}
