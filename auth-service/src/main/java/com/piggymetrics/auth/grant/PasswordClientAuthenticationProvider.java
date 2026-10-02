package com.piggymetrics.auth.grant;

import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Authenticates PUBLIC clients (ClientAuthenticationMethod.NONE) for the legacy password
 * grant by client_id lookup only - parity with the legacy authorization server where the
 * "browser" client had no secret.
 *
 * SECURITY NOTE: only clients explicitly registered with the NONE method can use this path;
 * confidential clients (with a secret) are never authenticated here. This preserves the
 * legacy authorization semantics without relaxing them.
 */
public class PasswordClientAuthenticationProvider implements AuthenticationProvider {

	private final RegisteredClientRepository registeredClientRepository;

	public PasswordClientAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
		this.registeredClientRepository = registeredClientRepository;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {

		OAuth2ClientAuthenticationToken clientAuthentication = (OAuth2ClientAuthenticationToken) authentication;

		if (!ClientAuthenticationMethod.NONE.equals(clientAuthentication.getClientAuthenticationMethod())) {
			return null;
		}

		String clientId = clientAuthentication.getPrincipal().toString();
		RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
		if (registeredClient == null) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT,
					"Client authentication failed: " + clientId, null));
		}

		if (!registeredClient.getClientAuthenticationMethods()
				.contains(ClientAuthenticationMethod.NONE)) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT,
					"Client authentication failed: " + clientId, null));
		}

		return new OAuth2ClientAuthenticationToken(registeredClient, ClientAuthenticationMethod.NONE, null);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
