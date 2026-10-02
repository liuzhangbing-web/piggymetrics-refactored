package com.piggymetrics.account.service;

import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsServiceClient;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.CompensationTask;
import com.piggymetrics.account.domain.Currency;
import com.piggymetrics.account.domain.Saving;
import com.piggymetrics.account.domain.User;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.repository.CompensationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.util.Date;

@Service
public class AccountServiceImpl implements AccountService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Autowired
	private StatisticsServiceClient statisticsClient;

	@Autowired
	private AuthServiceClient authClient;

	@Autowired
	private AccountRepository repository;

	/**
	 * Plan-A compensation store. Optional wiring (required=false) keeps existing unit
	 * tests (@InjectMocks without this mock) working with legacy behavior.
	 */
	@Autowired(required = false)
	private CompensationTaskRepository compensationTaskRepository;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Account findByName(String accountName) {
		Assert.hasLength(accountName, "accountName must have length");
		return repository.findByName(accountName);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Plan-A compensation (self-healing, failure path ONLY - success-path call order
	 * findByName -> authClient.createUser -> repository.save is unchanged, locked by ACC-03):
	 * if the local account save fails AFTER the auth user was created, immediately try to
	 * delete the orphaned user; if that compensation call also fails, enqueue a
	 * DELETE_AUTH_USER task for the reconciliation job. The original exception is always
	 * rethrown so the caller sees the failure (legacy error semantics preserved, ACC-05).
	 */
	@Override
	public Account create(User user) {

		Account existing = repository.findByName(user.getUsername());
		Assert.isNull(existing, "account already exists: " + user.getUsername());

		authClient.createUser(user);

		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(0));
		saving.setCurrency(Currency.getDefault());
		saving.setInterest(new BigDecimal(0));
		saving.setDeposit(false);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setName(user.getUsername());
		account.setLastSeen(new Date());
		account.setSaving(saving);

		try {
			repository.save(account);
		} catch (RuntimeException saveFailure) {
			compensateOrphanedUser(user.getUsername());
			throw saveFailure;
		}

		log.info("new account has been created: " + account.getName());

		return account;
	}

	/**
	 * Failure-path compensation: remove the auth user created moments ago (orphaned-user gap).
	 * Best-effort and null-safe; on compensation failure an async DELETE_AUTH_USER task is
	 * enqueued so the reconciliation job retries. Never masks the original exception.
	 */
	private void compensateOrphanedUser(String username) {
		try {
			authClient.deleteUser(username);
			log.warn("COMPENSATION: orphaned auth user {} deleted after local account save failure", username);
			return;
		} catch (Exception compensationFailure) {
			log.error("COMPENSATION: immediate delete of orphaned user {} failed, enqueuing async task: {}",
					username, compensationFailure.toString());
		}
		if (compensationTaskRepository == null) {
			return; // repository not wired (unit-test construction) -> nothing more possible
		}
		try {
			boolean alreadyPending = !compensationTaskRepository
					.findByTypeAndAccountNameAndStatus(CompensationTask.Type.DELETE_AUTH_USER,
							username, CompensationTask.Status.PENDING).isEmpty();
			if (!alreadyPending) {
				compensationTaskRepository.save(
						new CompensationTask(CompensationTask.Type.DELETE_AUTH_USER, username));
			}
		} catch (Exception enqueueFailure) {
			log.error("COMPENSATION: failed to enqueue DELETE_AUTH_USER task for {}: {}",
					username, enqueueFailure.toString());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void saveChanges(String name, Account update) {

		Account account = repository.findByName(name);
		Assert.notNull(account, "can't find account with name " + name);

		account.setIncomes(update.getIncomes());
		account.setExpenses(update.getExpenses());
		account.setSaving(update.getSaving());
		account.setNote(update.getNote());
		account.setLastSeen(new Date());
		repository.save(account);

		log.debug("account {} changes has been saved", name);

		try {
			statisticsClient.updateStatistics(name, account);
		} catch (RuntimeException statisticsFailure) {
			// Plan-A: raw Feign failure path (when Sentinel fallback is NOT active).
			// With the fallback active, the fallback itself enqueues the task.
			// Enqueue + rethrow: legacy exception semantics preserved (ACC-08).
			enqueueStatisticsCompensation(name, statisticsFailure);
			throw statisticsFailure;
		}
	}

	private void enqueueStatisticsCompensation(String name, RuntimeException cause) {
		if (compensationTaskRepository == null) {
			return;
		}
		try {
			boolean alreadyPending = !compensationTaskRepository
					.findByTypeAndAccountNameAndStatus(CompensationTask.Type.UPDATE_STATISTICS,
							name, CompensationTask.Status.PENDING).isEmpty();
			if (!alreadyPending) {
				compensationTaskRepository.save(
						new CompensationTask(CompensationTask.Type.UPDATE_STATISTICS, name));
				log.warn("COMPENSATION task enqueued (raw failure path): recompute statistics for {} ({})",
						name, cause.toString());
			}
		} catch (Exception enqueueFailure) {
			log.error("COMPENSATION: failed to enqueue UPDATE_STATISTICS task for {}: {}",
					name, enqueueFailure.toString());
		}
	}
}
