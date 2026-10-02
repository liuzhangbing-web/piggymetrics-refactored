package com.piggymetrics.statistics.service;

import com.piggymetrics.statistics.client.ExchangeRatesClient;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

@Service
public class ExchangeRatesServiceImpl implements ExchangeRatesService {

	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesServiceImpl.class);

	/**
	 * CONCURRENCY FIX (risk R4, approved as M6): the daily rates cache is now guarded
	 * (volatile + double-checked locking) to prevent duplicate remote fetches and unsafe
	 * publication under concurrent access. The caching strategy (per-day invalidation),
	 * conversion algorithm, scale and rounding mode are UNCHANGED (zero business change).
	 */
	private volatile ExchangeRatesContainer container;

	@Autowired
	private ExchangeRatesClient client;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<Currency, BigDecimal> getCurrentRates() {

		ExchangeRatesContainer current = container;
		if (current == null || !current.getDate().equals(LocalDate.now())) {
			synchronized (this) {
				current = container;
				if (current == null || !current.getDate().equals(LocalDate.now())) {
					current = client.getRates(Currency.getBase());
					container = current;
					log.info("exchange rates has been updated: {}", current);
				}
			}
		}

		return Map.of(
				Currency.EUR, current.getRates().get(Currency.EUR.name()),
				Currency.RUB, current.getRates().get(Currency.RUB.name()),
				Currency.USD, BigDecimal.ONE
		);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BigDecimal convert(Currency from, Currency to, BigDecimal amount) {

		Assert.notNull(amount, "amount must not be null");

		Map<Currency, BigDecimal> rates = getCurrentRates();
		BigDecimal ratio = rates.get(to).divide(rates.get(from), 4, RoundingMode.HALF_UP);

		return amount.multiply(ratio);
	}
}
