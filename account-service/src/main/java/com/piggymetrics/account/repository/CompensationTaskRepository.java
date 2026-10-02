package com.piggymetrics.account.repository;

import com.piggymetrics.account.domain.CompensationTask;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Store for compensation tasks (Plan-A self-healing). Lives in the account-service's
 * own MongoDB (collection "compensation-tasks") - no new infrastructure required.
 */
@Repository
public interface CompensationTaskRepository extends CrudRepository<CompensationTask, String> {

	List<CompensationTask> findByStatus(CompensationTask.Status status);

	/** Reconciliation idempotency guard: at most one open task per (type, account). */
	List<CompensationTask> findByTypeAndAccountNameAndStatus(CompensationTask.Type type,
			String accountName, CompensationTask.Status status);
}
