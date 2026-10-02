package com.piggymetrics.account.service;

import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsCompensationClient;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.CompensationTask;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.repository.CompensationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Plan-A reconciliation / self-healing job.
 *
 * <p>Periodically drains PENDING compensation tasks:
 * <ul>
 *   <li>DELETE_AUTH_USER  -> authClient.deleteUser(name)   (removes orphaned auth user)</li>
 *   <li>UPDATE_STATISTICS -> recompute datapoint from the account (source of truth) via
 *                            statisticsClient.updateStatistics(name, latestAccount)</li>
 * </ul>
 *
 * <p>Both operations are idempotent: deleting a missing user is a no-op; recomputing the
 * datapoint from the current account always yields the same result for the same input.
 * On success the task is marked DONE; on failure attempts++ and the task stays PENDING
 * until maxAttempts, then FAILED (logged at ERROR as an alert signal for monitoring).
 *
 * <p>This job introduces NO change to any success-path business logic; it only heals
 * the two known cross-service consistency gaps (orphaned user / stale statistics).
 */
@Component
public class CompensationReconciliationJob {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Autowired
	private CompensationTaskRepository taskRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AuthServiceClient authClient;

	/**
	 * No-fallback client: the job must see real failures to retry (the primary client's
	 * Sentinel fallback would otherwise swallow a still-down statistics-service and the
	 * task would be wrongly marked DONE).
	 */
	@Autowired
	private StatisticsCompensationClient statisticsCompensationClient;

	@Value("${compensation.max-attempts:5}")
	private int maxAttempts;

	/**
	 * fixedDelayString so the interval is Nacos/config-driven (default 30s).
	 * initialDelay avoids racing service startup.
	 */
	@Scheduled(fixedDelayString = "${compensation.reconcile-interval-ms:30000}",
			initialDelayString = "${compensation.reconcile-initial-delay-ms:20000}")
	public void reconcile() {
		List<CompensationTask> pending;
		try {
			pending = taskRepository.findByStatus(CompensationTask.Status.PENDING);
		} catch (Exception e) {
			log.error("COMPENSATION reconcile: failed to load PENDING tasks: {}", e.toString());
			return;
		}
		if (pending.isEmpty()) {
			return;
		}
		log.info("COMPENSATION reconcile: {} pending task(s)", pending.size());
		for (CompensationTask task : pending) {
			process(task);
		}
	}

	private void process(CompensationTask task) {
		try {
			switch (task.getType()) {
				case DELETE_AUTH_USER -> handleDeleteAuthUser(task);
				case UPDATE_STATISTICS -> handleUpdateStatistics(task);
				default -> {
					log.error("COMPENSATION: unknown task type {} for {}", task.getType(), task.getAccountName());
					markFailed(task, "unknown type");
				}
			}
		} catch (Exception e) {
			handleAttemptFailure(task, e);
		}
	}

	private void handleDeleteAuthUser(CompensationTask task) {
		authClient.deleteUser(task.getAccountName()); // idempotent server-side
		markDone(task);
	}

	private void handleUpdateStatistics(CompensationTask task) {
		Account account = accountRepository.findByName(task.getAccountName());
		if (account == null) {
			// account no longer exists -> nothing to recompute; close the task as done
			log.warn("COMPENSATION: account {} not found for statistics recompute, closing task",
					task.getAccountName());
			markDone(task);
			return;
		}
		statisticsCompensationClient.updateStatistics(task.getAccountName(), account);
		markDone(task);
	}

	private void markDone(CompensationTask task) {
		task.setStatus(CompensationTask.Status.DONE);
		task.setLastAttemptAt(new Date());
		task.setAttempts(task.getAttempts() + 1);
		task.setLastError(null);
		taskRepository.save(task);
		log.warn("COMPENSATION: task {} ({}) for {} DONE", task.getId(), task.getType(), task.getAccountName());
	}

	private void handleAttemptFailure(CompensationTask task, Exception e) {
		task.setAttempts(task.getAttempts() + 1);
		task.setLastAttemptAt(new Date());
		task.setLastError(e.toString());
		if (task.getAttempts() >= maxAttempts) {
			markFailed(task, e.toString());
		} else {
			taskRepository.save(task); // stays PENDING for the next cycle
			log.error("COMPENSATION: task {} ({}) for {} attempt {}/{} failed: {}",
					task.getId(), task.getType(), task.getAccountName(), task.getAttempts(), maxAttempts, e.toString());
		}
	}

	private void markFailed(CompensationTask task, String reason) {
		task.setStatus(CompensationTask.Status.FAILED);
		task.setLastAttemptAt(new Date());
		task.setLastError(reason);
		taskRepository.save(task);
		// ERROR-level alert signal: monitoring (SkyWalking/Sentinel) should page on this.
		log.error("COMPENSATION ALERT: task {} ({}) for {} exhausted {} attempts -> FAILED. Manual intervention required. lastError={}",
				task.getId(), task.getType(), task.getAccountName(), maxAttempts, reason);
	}
}
