package com.piggymetrics.auth.grant;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Custom grant token for the legacy OAuth2 "password" flow (browser client),
 * kept for backward compatibility after migrating to Spring Authorization Server.
 */
public class OAuth2ResourceOwnerPasswordAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

	public static final AuthorizationGrantType PASSWORD_GRANT_TYPE = new AuthorizationGrantType("password");

	private final Authentication clientPrincipal;

	private final Set<String> scopes;

	private final String username;

	private final String password;

	public OAuth2ResourceOwnerPasswordAuthenticationToken(Authentication clientPrincipal, Set<String> scopes,
			String username, String password, Map<String, Object> additionalParameters) {
		super(PASSWORD_GRANT_TYPE, clientPrincipal, additionalParameters);
		this.clientPrincipal = clientPrincipal;
		this.scopes = Collections.unmodifiableSet(scopes != null ? new HashSet<>(scopes) : Collections.emptySet());
		this.username = username;
		this.password = password;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public Set<String> getScopes() {
		return scopes;
	}

	@Override
	public Authentication getPrincipal() {
		return clientPrincipal;
	}

}
