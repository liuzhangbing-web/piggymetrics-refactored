package com.piggymetrics.auth.controller;

import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.security.Principal;

@RestController
@RequestMapping("/users")
public class UserController {

	@Autowired
	private UserService userService;

	@RequestMapping(value = "/current", method = RequestMethod.GET)
	public Principal getUser(Principal principal) {
		return principal;
	}

	@PreAuthorize("hasAuthority('SCOPE_server')")
	@RequestMapping(method = RequestMethod.POST)
	public void createUser(@Valid @RequestBody User user) {
		userService.create(user);
	}

	/**
	 * Compensation endpoint (Plan-A): deletes an orphaned user created by a failed
	 * account opening. Service-to-service only (SCOPE_server, same guard as create).
	 * Idempotent: 200 with body "true"/"false" (deleted / nothing to delete).
	 */
	@PreAuthorize("hasAuthority('SCOPE_server')")
	@RequestMapping(value = "/{username}", method = RequestMethod.DELETE)
	public boolean deleteUser(@PathVariable String username) {
		return userService.deleteByUsername(username);
	}
}
