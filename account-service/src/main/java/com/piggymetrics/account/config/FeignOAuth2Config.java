package com.piggymetrics.account.config;

import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;

import feign.RequestInterceptor;

/**
 * Feign OAuth2 propagation, replacing the retired OAuth2FeignRequestInterceptor
 * (spring-cloud-security). Uses Spring Security OAuth2 Client with the
 * client_credentials grant to obtain a service token from auth-service and attach it
 * as a Bearer header on every outbound Feign call.
 *
 * The client registration (clientId/secret/tokenUri/scope=server) is defined in the
 * Nacos-managed application config under spring.security.oauth2.client.registration.*.
 */
@Configuration
@EnableFeignClients
public class FeignOAuth2Config {

	@Bean
	public OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository repository) {
		return new InMemoryOAuth2AuthorizedClientService(repository);
	}

	@Bean
	public OAuth2AuthorizedClientManager authorizedClientManager(
			ClientRegistrationRepository repository, OAuth2AuthorizedClientService authorizedClientService) {
		var provider = OAuth2AuthorizedClientProviderBuilder.builder()
				.clientCredentials()
				.build();
		var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository, authorizedClientService);
		manager.setAuthorizedClientProvider(provider);
		return manager;
	}

	/**
	 * Interceptor that fetches a client_credentials token for the "account-service" registration
	 * and sets the Authorization header. The registrationId is read from config so each
	 * service supplies its own clientId (account-service / notification-service).
	 */
	@Bean
	public RequestInterceptor oauth2FeignRequestInterceptor(
			OAuth2AuthorizedClientManager manager,
			org.springframework.core.env.Environment env) {
		String registrationId = env.getProperty("piggymetrics.feign.client-registration-id", "account-service");
		return template -> {
			org.springframework.security.oauth2.client.OAuth2AuthorizeRequest request =
					org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
							.withClientRegistrationId(registrationId)
							.principal(registrationId)
							.build();
			OAuth2AuthorizedClient client = manager.authorize(request);
			if (client != null && client.getAccessToken() != null) {
				template.header("Authorization",
						client.getAccessToken().getTokenType().getValue() + " "
								+ client.getAccessToken().getTokenValue());
			}
		};
	}

}
