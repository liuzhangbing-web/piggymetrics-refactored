package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.User;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient(name = "auth-service")
public interface AuthServiceClient {

	@RequestMapping(method = RequestMethod.POST, value = "/uaa/users", consumes = MediaType.APPLICATION_JSON_VALUE)
	void createUser(User user);

	/**
	 * Compensation call (Plan-A): remove an orphaned auth user when the local account
	 * save failed after createUser succeeded. Idempotent on the server side.
	 */
	@RequestMapping(method = RequestMethod.DELETE, value = "/uaa/users/{username}")
	boolean deleteUser(@PathVariable("username") String username);

}
