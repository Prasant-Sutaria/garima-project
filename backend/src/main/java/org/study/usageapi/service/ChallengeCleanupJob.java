package org.study.usageapi.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.study.usageapi.persistence.ChallengeRepository;

@Component
public class ChallengeCleanupJob {

    private final ChallengeRepository challenges;

    public ChallengeCleanupJob(ChallengeRepository challenges) {
        this.challenges = challenges;
    }

    @Scheduled(cron = "0 17 * * * *", zone = "UTC")
    public void deleteExpiredChallenges() {
        challenges.deleteExpired();
    }
}
