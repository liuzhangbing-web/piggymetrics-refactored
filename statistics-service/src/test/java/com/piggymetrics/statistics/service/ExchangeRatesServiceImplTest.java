package com.piggymetrics.statistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.client.ExchangeRatesClient;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cases EXR-01..06. EXR-01 is RED-LINE (money conversion); EXR-05 verifies the M6
 * concurrency fix (volatile + double-checked locking) without touching the algorithm.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeRatesServiceImplTest {

	private static JsonNode GOLDEN;

	@Mock
	private ExchangeRatesClient client;

	@InjectMocks
	private ExchangeRatesServiceImpl service;

	@BeforeEach
	void loadGolden() throws Exception {
		if (GOLDEN == null) {
			GOLDEN = new ObjectMapper().readTree(ExchangeRatesServiceImplTest.class
					.getResourceAsStream("/golden/statistics-golden.json"));
		}
	}

	private ExchangeRatesContainer container(String eur, String rub, LocalDate date) {
		ExchangeRatesContainer c = new ExchangeRatesContainer();
		c.setBase(Currency.getBase());
		c.setDate(date);
		Map<String, BigDecimal> rates = new HashMap<>();
		rates.put(Currency.EUR.name(), new BigDecimal(eur));
		rates.put(Currency.RUB.name(), new BigDecimal(rub));
		c.setRates(rates);
		return c;
	}

	@Test
	@DisplayName("EXR-01 [RED] convert values match golden (ratio divide scale=4 HALF_UP, then multiply)")
	void convert_golden() {
		when(client.getRates(any())).thenReturn(container("0.9", "75.0", LocalDate.now()));
		for (JsonNode c : GOLDEN.get("convertCases")) {
			Currency from = Currency.valueOf(c.get("from").asText());
			Currency to = Currency.valueOf(c.get("to").asText());
			BigDecimal amount = new BigDecimal(c.get("amount").asText());
			BigDecimal actual = service.convert(from, to, amount);
			BigDecimal expected = new BigDecimal(c.get("expected").asText());
			assertEquals(0, expected.compareTo(actual),
					"convert(" + from + "->" + to + ", " + amount + ") expected " + expected + " got " + actual);
		}
	}

	@Test
	@DisplayName("EXR-02 [RED] convert with null amount -> IllegalArgumentException")
	void convert_null() {
		when(client.getRates(any())).thenReturn(container("0.9", "75.0", LocalDate.now()));
		assertThrows(IllegalArgumentException.class, () -> service.convert(Currency.EUR, Currency.USD, null));
	}

	@Test
	@DisplayName("EXR-03 same-day cache hit -> client called once")
	void cacheHit() {
		when(client.getRates(any())).thenReturn(container("0.9", "75.0", LocalDate.now()));
		service.getCurrentRates();
		service.getCurrentRates();
		verify(client, times(1)).getRates(any());
	}

	@Test
	@DisplayName("EXR-04 stale (yesterday) cache -> refetch")
	void cacheExpired() {
		when(client.getRates(any()))
				.thenReturn(container("0.9", "75.0", LocalDate.now().minusDays(1)))
				.thenReturn(container("0.95", "76.0", LocalDate.now()));
		service.getCurrentRates();
		service.getCurrentRates();
		verify(client, times(2)).getRates(any());
	}

	@Test
	@DisplayName("EXR-05 M6 concurrency: 16 threads cold cache -> exactly ONE remote fetch, identical rates")
	void concurrentColdCache() throws Exception {
		AtomicInteger fetches = new AtomicInteger();
		when(client.getRates(any())).thenAnswer(inv -> {
			fetches.incrementAndGet();
			Thread.sleep(50); // widen the race window
			return container("0.9", "75.0", LocalDate.now());
		});
		ReflectionTestUtils.setField(service, "container", null);

		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Map<Currency, BigDecimal>>> futures = new java.util.ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				return service.getCurrentRates();
			}));
		}
		start.countDown();
		Map<Currency, BigDecimal> first = futures.get(0).get(20, TimeUnit.SECONDS);
		for (Future<Map<Currency, BigDecimal>> f : futures) {
			assertEquals(first, f.get(20, TimeUnit.SECONDS), "all threads must observe identical rates");
		}
		pool.shutdownNow();
		assertEquals(1, fetches.get(), "double-checked locking must collapse concurrent fetches to one");
	}

	@Test
	@DisplayName("EXR-06 fallback with empty rates -> NPE surfaces (legacy status quo, D16 - NOT masked)")
	void fallbackEmptyRates() {
		ExchangeRatesContainer empty = new ExchangeRatesContainer();
		empty.setBase(Currency.getBase());
		empty.setDate(LocalDate.now());
		empty.setRates(Collections.emptyMap());
		when(client.getRates(any())).thenReturn(empty);
		// Documents the known risk: empty rates cause NPE on Map.of(...) - same as legacy.
		assertThrows(NullPointerException.class, () -> service.getCurrentRates());
	}
}
