package com.piggymetrics.auth.grant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts legacy token requests (grant_type=password or refresh_token) from a PUBLIC
 * client (e.g. "browser", registered with ClientAuthenticationMethod.NONE - parity with
 * the legacy in-memory client config which had no secret for the browser client).
 *
 * Spring Authorization Server's default client authentication chain only supports:
 * client_secret_basic/post, JWT assertion, mTLS, and PKCE public-client authentication
 * (which requires a code_verifier and only applies to authorization_code). A request
 * carrying only client_id therefore matched no converter and was rejected with
 * invalid_client. This converter restores the legacy behavior.
 */
public class PasswordClientAuthenticationConverter implements AuthenticationConverter {

	@Nullable
	@Override
	public Authentication convert(HttpServletRequest request) {

		String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
		if (!"password".equals(grantType) && !"refresh_token".equals(grantType)) {
			return null;
		}

		String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
		if (!StringUtils.hasText(clientId)) {
			return null;
		}
		if (request.getParameterValues(OAuth2ParameterNames.CLIENT_ID).length != 1) {
			throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
		}
		// Requests carrying a client_secret are handled by ClientSecretPostAuthenticationConverter.
		if (StringUtils.hasText(request.getParameter(OAuth2ParameterNames.CLIENT_SECRET))) {
			return null;
		}

		Map<String, Object> additionalParameters = new HashMap<>();
		request.getParameterMap().forEach((key, values) -> {
			if (!key.equals(OAuth2ParameterNames.CLIENT_ID) && values.length > 0) {
				additionalParameters.put(key, values[0]);
			}
		});

		return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE,
				null, additionalParameters);
	}

}
