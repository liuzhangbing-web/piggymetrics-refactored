package com.piggymetrics.account.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * Compensation task record (Plan-A: idempotent compensation + reconciliation self-healing).
 *
 * <p>Persisted in the account-service's OWN mongo database (collection "compensation-tasks").
 * A task is enqueued only on FAILURE paths (local account save failure after the auth user
 * was already created, or statistics update swallowed by the Sentinel fallback). The
 * reconciliation job retries PENDING tasks until DONE or max-attempts (then FAILED + alert log).
 *
 * <p>This does not change any success-path business semantics (red-line safe):
 * create() call order and saveChanges() behavior are untouched when nothing fails.
 */
@Document(collection = "compensation-tasks")
public class CompensationTask {

	public enum Type {
		/** auth user exists but local account save failed -> delete the orphan user. */
		DELETE_AUTH_USER,
		/** account saved but statistics update failed (fallback) -> recompute datapoint. */
		UPDATE_STATISTICS
	}

	public enum Status { PENDING, DONE, FAILED }

	@Id
	private String id;

	private Type type;

	private String accountName;

	private Status status;

	private int attempts;

	private Date createdAt;

	private Date lastAttemptAt;

	private String lastError;

	public CompensationTask() {
	}

	public CompensationTask(Type type, String accountName) {
		this.type = type;
		this.accountName = accountName;
		this.status = Status.PENDING;
		this.attempts = 0;
		this.createdAt = new Date();
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }
	public Type getType() { return type; }
	public void setType(Type type) { this.type = type; }
	public String getAccountName() { return accountName; }
	public void setAccountName(String accountName) { this.accountName = accountName; }
	public Status getStatus() { return status; }
	public void setStatus(Status status) { this.status = status; }
	public int getAttempts() { return attempts; }
	public void setAttempts(int attempts) { this.attempts = attempts; }
	public Date getCreatedAt() { return createdAt; }
	public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
	public Date getLastAttemptAt() { return lastAttemptAt; }
	public void setLastAttemptAt(Date lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
	public String getLastError() { return lastError; }
	public void setLastError(String lastError) { this.lastError = lastError; }
}
