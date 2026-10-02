package com.piggymetrics.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cases GW-01..GW-02 [RED]: route table equivalence with the legacy Zuul config.
 * Legacy (config/src/main/resources/shared/gateway.yml):
 *   /uaa/**            -> url http://auth-service:5000   stripPrefix=false
 *   /accounts/**       -> serviceId account-service      stripPrefix=false
 *   /statistics/**     -> serviceId statistics-service   stripPrefix=false
 *   /notifications/**  -> serviceId notification-service stripPrefix=false
 *   zuul.ignoredServices='*' (only declared routes exposed)
 *   zuul.host / ribbon timeouts = 20000ms
 */
@SpringBootTest(properties = {
		"spring.cloud.nacos.discovery.enabled=false",
		"spring.cloud.nacos.config.enabled=false",
		"spring.cloud.sentinel.enabled=false",
		"spring.cloud.sentinel.transport.dashboard=",
		"spring.config.import="
})
class GatewayRoutesTest {

	@Autowired
	private RouteDefinitionLocator routeDefinitionLocator;

	@Autowired
	private Environment environment;

	@Autowired
	private org.springframework.context.ConfigurableApplicationContext applicationContext;

	private Map<String, RouteDefinition> routesById() {
		List<RouteDefinition> defs = routeDefinitionLocator.getRouteDefinitions()
				.collectList().block(Duration.ofSeconds(10));
		assertNotNull(defs);
		return defs.stream().collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
	}

	private boolean hasPath(RouteDefinition def, String path) {
		return def.getPredicates().stream()
				.anyMatch(p -> "Path".equals(p.getName())
						&& p.getArgs().values().stream().anyMatch(path::equals));
	}

	@Test
	@DisplayName("GW-01 [RED] exactly 4 routes with legacy-equivalent ids, predicates and URIs")
	void routeTableEquivalent() {
		Map<String, RouteDefinition> routes = routesById();
		assertEquals(4, routes.size(),
				"must declare exactly the 4 legacy routes, got: " + routes.keySet());

		RouteDefinition auth = routes.get("auth-service");
		assertNotNull(auth, "auth-service route missing");
		assertEquals("http://auth-service:5000", auth.getUri().toString(),
				"legacy Zuul used a fixed url for auth-service");
		assertTrue(hasPath(auth, "/uaa/**"), "auth route must match /uaa/**");

		RouteDefinition account = routes.get("account-service");
		assertNotNull(account);
		assertEquals("lb://account-service", account.getUri().toString());
		assertTrue(hasPath(account, "/accounts/**"));

		RouteDefinition statistics = routes.get("statistics-service");
		assertNotNull(statistics);
		assertEquals("lb://statistics-service", statistics.getUri().toString());
		assertTrue(hasPath(statistics, "/statistics/**"));

		RouteDefinition notification = routes.get("notification-service");
		assertNotNull(notification);
		assertEquals("lb://notification-service", notification.getUri().toString());
		assertTrue(hasPath(notification, "/notifications/**"));
	}

	@Test
	@DisplayName("GW-01b [RED] no StripPrefix filters (equivalent to Zuul stripPrefix=false)")
	void noStripPrefix() {
		routesById().values().forEach(def ->
				assertTrue(def.getFilters().stream().noneMatch(f -> "StripPrefix".equals(f.getName())),
						def.getId() + " must not strip prefix (legacy stripPrefix=false)"));
	}

	@Test
	@DisplayName("GW-02 connect/response timeouts equivalent to legacy 20s")
	void timeoutProperties() {
		assertEquals("20000",
				environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.connect-timeout"),
				"connect timeout must stay 20000ms (legacy zuul.host/ribbon)");
		assertEquals("20s",
				environment.getProperty("spring.cloud.gateway.server.webflux.httpclient.response-timeout"),
				"response timeout must stay 20s (legacy zuul.host.socket-timeout)");
	}

	// ================= dp-spec FT-GW-003/004 =================

	@Test
	@DisplayName("FT-GW-003 [RED-equiv] undeclared path -> 404 (zuul.ignoredServices='*' equivalence: only declared routes exist)")
	void undeclaredPathNotFound() {
		org.springframework.test.web.reactive.server.WebTestClient client =
				org.springframework.test.web.reactive.server.WebTestClient
						.bindToApplicationContext(applicationContext).build();
		client.get().uri("/foo/bar")
				.exchange()
				.expectStatus().isNotFound();
	}

	@Test
	@DisplayName("FT-GW-004 [RED] no route removes request headers -> Authorization passes through (legacy sensitiveHeaders=empty equivalence)")
	void authorizationHeaderNotStripped() {
		// Real pass-through is proven live by verify-main-compose.py (every authenticated
		// call flows Bearer tokens through the gateway). This slice test LOCKS the config:
		// no RemoveRequestHeader / DedupeResponseHeader style filters on any route.
		routesById().values().forEach(def ->
				assertTrue(def.getFilters().stream()
								.noneMatch(f -> f.getName() != null
										&& f.getName().toLowerCase().contains("removerequestheader")),
						def.getId() + " must not strip request headers (legacy zuul sensitiveHeaders was empty)"));
	}
}
