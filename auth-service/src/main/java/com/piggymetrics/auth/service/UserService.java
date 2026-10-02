package com.piggymetrics.auth.service;

import com.piggymetrics.auth.domain.User;

public interface UserService {

	void create(User user);

	/**
	 * Compensation-only deletion (Plan-A self-healing for the orphaned-user gap).
	 * Idempotent: deleting a non-existent user is a no-op returning false.
	 * Exposed ONLY under SCOPE_server (service-to-service); never used by UI flows.
	 *
	 * @return true if a user document was actually removed
	 */
	boolean deleteByUsername(String username);

}
