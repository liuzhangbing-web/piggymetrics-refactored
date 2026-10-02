package com.piggymetrics.auth.grant;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.util.CollectionUtils;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Authentication provider for the legacy OAuth2 "password" grant, implemented as a
 * Spring Authorization Server extension (the grant was removed from the OAuth 2.1 core
 * specification but must be preserved for backward compatibility of the "browser" client).
 */
public class OAuth2ResourceOwnerPasswordAuthenticationProvider implements AuthenticationProvider {

	private static final OAuth2TokenType ACCESS_TOKEN_TYPE = OAuth2TokenType.ACCESS_TOKEN;

	private final OAuth2AuthorizationService authorizationService;

	private final AuthenticationManager authenticationManager;

	private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

	public OAuth2ResourceOwnerPasswordAuthenticationProvider(OAuth2AuthorizationService authorizationService,
			AuthenticationManager authenticationManager, OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator) {
		this.authorizationService = authorizationService;
		this.authenticationManager = authenticationManager;
		this.tokenGenerator = tokenGenerator;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {

		OAuth2ResourceOwnerPasswordAuthenticationToken passwordAuthentication =
				(OAuth2ResourceOwnerPasswordAuthenticationToken) authentication;

		OAuth2ClientAuthenticationToken clientPrincipal =
				getAuthenticatedClientElseThrowInvalidClient(passwordAuthentication);
		RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();

		if (registeredClient == null || !registeredClient.getAuthorizationGrantTypes()
				.contains(OAuth2ResourceOwnerPasswordAuthenticationToken.PASSWORD_GRANT_TYPE)) {
			throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
		}

		Set<String> authorizedScopes;
		if (!CollectionUtils.isEmpty(passwordAuthentication.getScopes())) {
			for (String scope : passwordAuthentication.getScopes()) {
				if (!registeredClient.getScopes().contains(scope)) {
					throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
				}
			}
			authorizedScopes = new LinkedHashSet<>(passwordAuthentication.getScopes());
		}
		else {
			authorizedScopes = Collections.emptySet();
		}

		UsernamePasswordAuthenticationToken usernamePassword = new UsernamePasswordAuthenticationToken(
				passwordAuthentication.getUsername(), passwordAuthentication.getPassword());

		Authentication principal;
		try {
			principal = this.authenticationManager.authenticate(usernamePassword);
		}
		catch (AuthenticationException ex) {
			throw new OAuth2AuthenticationException(
					new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, ex.getMessage(), null), ex);
		}

		DefaultOAuth2TokenContext.Builder tokenContextBuilder = DefaultOAuth2TokenContext.builder()
				.registeredClient(registeredClient)
				.principal(principal)
				.authorizationServerContext(AuthorizationServerContextHolder.getContext())
				.authorizedScopes(authorizedScopes)
				.authorizationGrantType(OAuth2ResourceOwnerPasswordAuthenticationToken.PASSWORD_GRANT_TYPE)
				.authorizationGrant(passwordAuthentication);

		OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization.withRegisteredClient(registeredClient)
				.principalName(principal.getName())
				.authorizationGrantType(OAuth2ResourceOwnerPasswordAuthenticationToken.PASSWORD_GRANT_TYPE)
				.authorizedScopes(authorizedScopes)
				.attribute(Principal.class.getName(), principal);

		// ----- access token -----
		OAuth2TokenContext tokenContext = tokenContextBuilder.tokenType(ACCESS_TOKEN_TYPE).build();
		OAuth2Token generatedAccessToken = this.tokenGenerator.generate(tokenContext);
		if (generatedAccessToken == null) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
					"The token generator failed to generate the access token.", null));
		}
		OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
				generatedAccessToken.getTokenValue(), generatedAccessToken.getIssuedAt(),
				generatedAccessToken.getExpiresAt(), tokenContext.getAuthorizedScopes());
		if (generatedAccessToken instanceof ClaimAccessor claimAccessor) {
			authorizationBuilder.token(accessToken, metadata ->
					metadata.put("metadata.token.claims", claimAccessor.getClaims()));
		}
		else {
			authorizationBuilder.accessToken(accessToken);
		}

		// ----- refresh token -----
		// LEGACY PARITY: the pre-migration authorization server issued refresh tokens for the
		// public "browser" client (authorizedGrantTypes included refresh_token). SAS's built-in
		// providers withhold refresh tokens from NONE-method clients; this custom grant restores
		// the legacy behavior exactly (only when the client is registered for REFRESH_TOKEN).
		OAuth2RefreshToken refreshToken = null;
		if (registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			tokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.REFRESH_TOKEN).build();
			OAuth2Token generatedRefreshToken = this.tokenGenerator.generate(tokenContext);
			if (!(generatedRefreshToken instanceof OAuth2RefreshToken generated)) {
				throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
						"The token generator failed to generate the refresh token.", null));
			}
			refreshToken = generated;
			authorizationBuilder.refreshToken(refreshToken);
		}

		OAuth2Authorization authorization = authorizationBuilder.build();
		this.authorizationService.save(authorization);

		return new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken, refreshToken);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ResourceOwnerPasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static OAuth2ClientAuthenticationToken getAuthenticatedClientElseThrowInvalidClient(
			Authentication authentication) {
		OAuth2ClientAuthenticationToken clientPrincipal = null;
		if (OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication.getPrincipal().getClass())) {
			clientPrincipal = (OAuth2ClientAuthenticationToken) authentication.getPrincipal();
		}
		if (clientPrincipal != null && clientPrincipal.isAuthenticated()) {
			return clientPrincipal;
		}
		throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
	}

}
