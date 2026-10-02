package com.piggymetrics.account.domain;

import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.NotNull;

public class User {

	@NotNull
	@Size(min = 3, max = 20)
	private String username;

	@NotNull
	@Size(min = 6, max = 40)
	private String password;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
