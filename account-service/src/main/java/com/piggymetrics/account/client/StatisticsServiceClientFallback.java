package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.CompensationTask;
import com.piggymetrics.account.repository.CompensationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author cdov
 *
 * <p>Plan-A enhancement (idempotent compensation + reconciliation self-healing):
 * when the statistics update is degraded/swallowed here, enqueue a compensation task so
 * the reconciliation job can recompute the datapoint from the account document (the
 * source of truth). Enqueueing is best-effort and null-safe: if the repository is not
 * wired (e.g. direct construction in unit tests) the legacy log-only behavior is
 * preserved byte-for-byte, and enqueue failures never mask the original degradation.
 */
@Component
public class StatisticsServiceClientFallback implements StatisticsServiceClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsServiceClientFallback.class);

    @Autowired(required = false)
    private CompensationTaskRepository compensationTaskRepository;

    @Override
    public void updateStatistics(String accountName, Account account) {
        LOGGER.error("Error during update statistics for account: {}", accountName);
        enqueueCompensation(accountName);
    }

    /**
     * Best-effort enqueue of a recompute task. Never throws.
     * Idempotent: skips when a PENDING task for this account already exists
     * (the recompute always reads the latest account state, so one task suffices).
     */
    private void enqueueCompensation(String accountName) {
        if (compensationTaskRepository == null) {
            return; // repository not wired -> legacy log-only behavior
        }
        try {
            boolean alreadyPending = !compensationTaskRepository
                    .findByTypeAndAccountNameAndStatus(CompensationTask.Type.UPDATE_STATISTICS,
                            accountName, CompensationTask.Status.PENDING).isEmpty();
            if (alreadyPending) {
                return;
            }
            compensationTaskRepository.save(
                    new CompensationTask(CompensationTask.Type.UPDATE_STATISTICS, accountName));
            LOGGER.warn("COMPENSATION task enqueued: recompute statistics for account {}", accountName);
        } catch (Exception e) {
            LOGGER.error("Failed to enqueue compensation task for account {}: {}", accountName, e.toString());
        }
    }
}
