package com.piggymetrics.statistics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.domain.Account;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.Item;
import com.piggymetrics.statistics.domain.Saving;
import com.piggymetrics.statistics.domain.TimePeriod;
import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import com.piggymetrics.statistics.domain.timeseries.ItemMetric;
import com.piggymetrics.statistics.domain.timeseries.StatisticMetric;
import com.piggymetrics.statistics.repository.DataPointRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RED-LINE money tests (STA-01..06) driven by golden/statistics-golden.json.
 * Golden values were derived analytically by exactly emulating the legacy BigDecimal
 * algorithm (see the _meta block in the JSON). The conversion algorithm itself is
 * NOT to be modified - these tests lock scale=4/HALF_UP behavior (H3).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatisticsServiceImplTest {

	private static JsonNode GOLDEN;

	@Mock
	private DataPointRepository repository;

	@Mock
	private ExchangeRatesService ratesService;

	@InjectMocks
	private StatisticsServiceImpl service;

	@BeforeAll
	static void loadGolden() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		GOLDEN = mapper.readTree(StatisticsServiceImplTest.class
				.getResourceAsStream("/golden/statistics-golden.json"));
	}

	/**
	 * Stub ratesService exactly like the golden model. Uses doAnswer().when() (not
	 * when().thenAnswer()) because this method is called repeatedly across golden cases
	 * on the SAME mock; the when() form would invoke the previously-registered answer
	 * with null matcher arguments and NPE.
	 */
	private void stubRates(JsonNode ratesNode) {
		Map<String, BigDecimal> rates = new HashMap<>();
		ratesNode.fields().forEachRemaining(e -> rates.put(e.getKey(), new BigDecimal(e.getValue().asText())));
		doAnswer(inv -> {
			Map<Currency, BigDecimal> m = new EnumMap<>(Currency.class);
			rates.forEach((k, v) -> m.put(Currency.valueOf(k), v));
			return m;
		}).when(ratesService).getCurrentRates();
		doAnswer(inv -> {
			Currency from = inv.getArgument(0);
			Currency to = inv.getArgument(1);
			BigDecimal amount = inv.getArgument(2);
			BigDecimal ratio = rates.get(to.name()).divide(rates.get(from.name()), 4,
					java.math.RoundingMode.HALF_UP);
			return amount.multiply(ratio);
		}).when(ratesService).convert(any(), any(), any());
	}

	private List<Item> items(JsonNode arr) {
		List<Item> list = new ArrayList<>();
		for (JsonNode n : arr) {
			Item item = new Item();
			item.setTitle("t" + list.size());
			item.setAmount(new BigDecimal(n.get("amount").asText()));
			item.setCurrency(Currency.valueOf(n.get("currency").asText()));
			item.setPeriod(TimePeriod.valueOf(n.get("period").asText()));
			list.add(item);
		}
		return list;
	}

	@Test
	@DisplayName("STA-02 [RED] golden dataset: all metrics match legacy-derived values exactly")
	void goldenDataset() {
		JsonNode cases = GOLDEN.get("statisticsCases");
		assertTrue(cases.size() >= 10, "expected >=10 golden cases");

		for (JsonNode c : cases) {
			String id = c.get("id").asText();
			stubRates(c.get("rates"));

			Account account = new Account();
			account.setIncomes(items(c.get("incomes")));
			account.setExpenses(items(c.get("expenses")));
			Saving saving = new Saving();
			saving.setAmount(new BigDecimal(c.get("saving").get("amount").asText()));
			saving.setCurrency(Currency.valueOf(c.get("saving").get("currency").asText()));
			saving.setInterest(BigDecimal.ZERO);
			saving.setDeposit(false);
			saving.setCapitalization(false);
			account.setSaving(saving);

			when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
			DataPoint saved = service.save("golden-" + id, account);

			Map<StatisticMetric, BigDecimal> stats = saved.getStatistics();
			JsonNode exp = c.get("expected");
			assertEquals(0, new BigDecimal(exp.get("INCOMES_AMOUNT").asText())
							.compareTo(stats.get(StatisticMetric.INCOMES_AMOUNT)),
					id + " INCOMES_AMOUNT mismatch: expected " + exp.get("INCOMES_AMOUNT").asText()
								+ " got " + stats.get(StatisticMetric.INCOMES_AMOUNT));
			assertEquals(0, new BigDecimal(exp.get("EXPENSES_AMOUNT").asText())
							.compareTo(stats.get(StatisticMetric.EXPENSES_AMOUNT)),
					id + " EXPENSES_AMOUNT mismatch");
			assertEquals(0, new BigDecimal(exp.get("SAVING_AMOUNT").asText())
							.compareTo(stats.get(StatisticMetric.SAVING_AMOUNT)),
					id + " SAVING_AMOUNT mismatch");

			// per-item metric values
			assertMetrics(id + ".incomes", exp.get("incomeMetrics"), saved.getIncomes());
			assertMetrics(id + ".expenses", exp.get("expenseMetrics"), saved.getExpenses());
		}
	}

	private void assertMetrics(String label, JsonNode expectedAmounts, Set<ItemMetric> actual) {
		List<BigDecimal> actualAmounts = new ArrayList<>();
		actual.forEach(m -> actualAmounts.add(m.getAmount()));
		assertEquals(expectedAmounts.size(), actualAmounts.size(), label + " count");
		for (int i = 0; i < expectedAmounts.size(); i++) {
			BigDecimal exp = new BigDecimal(expectedAmounts.get(i).asText());
			assertTrue(actualAmounts.stream().anyMatch(a -> a.compareTo(exp) == 0),
					label + " missing expected metric value " + exp + " in " + actualAmounts);
		}
	}

	@Test
	@DisplayName("STA-01 [RED] save: DataPointId = (accountName, start-of-today)")
	void save_dataPointId() {
		JsonNode c = GOLDEN.get("statisticsCases").get(0);
		stubRates(c.get("rates"));
		Account account = new Account();
		account.setIncomes(items(c.get("incomes")));
		account.setExpenses(items(c.get("expenses")));
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(c.get("saving").get("amount").asText()));
		saving.setCurrency(Currency.valueOf(c.get("saving").get("currency").asText()));
		account.setSaving(saving);
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		ArgumentCaptor<DataPoint> captor = ArgumentCaptor.forClass(DataPoint.class);
		service.save("acct-1", account);
		verify(repository).save(captor.capture());

		Date expectedDate = Date.from(LocalDate.now().atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
		DataPointId id = captor.getValue().getId();
		assertEquals("acct-1", id.getAccount());
		assertEquals(expectedDate, id.getDate());
	}

	@Test
	@DisplayName("STA-03 [RED] empty incomes/expenses -> ZERO sums, no NPE")
	void emptyLists() {
		JsonNode c = GOLDEN.get("statisticsCases").get(2); // G03 empty case
		stubRates(c.get("rates"));
		Account account = new Account();
		account.setIncomes(Collections.emptyList());
		account.setExpenses(Collections.emptyList());
		Saving saving = new Saving();
		saving.setAmount(BigDecimal.ZERO);
		saving.setCurrency(Currency.USD);
		account.setSaving(saving);
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		DataPoint dp = assertDoesNotThrow(() -> service.save("acct-empty", account));
		assertEquals(0, BigDecimal.ZERO.compareTo(dp.getStatistics().get(StatisticMetric.INCOMES_AMOUNT)));
		assertEquals(0, BigDecimal.ZERO.compareTo(dp.getStatistics().get(StatisticMetric.EXPENSES_AMOUNT)));
	}

	@Test
	@DisplayName("STA-04 findByAccountName blank -> IllegalArgumentException")
	void findByAccountName_blank() {
		assertThrows(IllegalArgumentException.class, () -> service.findByAccountName(""));
		assertThrows(IllegalArgumentException.class, () -> service.findByAccountName(null));
	}

	@Test
	@DisplayName("STA-05 rates snapshot written into DataPoint.rates")
	void ratesSnapshot() {
		JsonNode c = GOLDEN.get("statisticsCases").get(0);
		stubRates(c.get("rates"));
		Map<Currency, BigDecimal> rates = ratesService.getCurrentRates();
		Account account = new Account();
		account.setIncomes(items(c.get("incomes")));
		account.setExpenses(items(c.get("expenses")));
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(c.get("saving").get("amount").asText()));
		saving.setCurrency(Currency.valueOf(c.get("saving").get("currency").asText()));
		account.setSaving(saving);
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		DataPoint dp = service.save("acct-r", account);
		assertEquals(rates, dp.getRates());
	}

	@Test
	@DisplayName("STA-06 ItemMetric legacy equals/hashCode semantics (LOCKED, includes known quirk)")
	void itemMetricEquality() {
		ItemMetric a = new ItemMetric("Salary", new BigDecimal("100"));
		ItemMetric b = new ItemMetric("Salary", new BigDecimal("999")); // same title, diff amount
		ItemMetric c = new ItemMetric("salary", new BigDecimal("100")); // same title, diff case

		// equals is by title only, case-INSENSITIVE, amount-irrelevant (legacy contract)
		assertEquals(a, b, "equals ignores amount");
		assertTrue(a.equals(c), "equals is case-insensitive on title");

		// KNOWN LEGACY QUIRK (locked, NOT fixed - RED-LINE H3 domain semantics):
		// hashCode() = title.hashCode() is case-SENSITIVE, so a and c are equals() but have
		// different hashCode(), violating the equals/hashCode contract. Consequence: HashSet
		// dedup of same-title-different-case metrics is unreliable. This test documents the
		// existing behavior so any future change is caught; fixing it is a separate H-item.
		assertNotEquals(a.hashCode(), c.hashCode(),
				"legacy hashCode is case-sensitive (documented quirk, do not rely on Set dedup across case)");

		// identical title+case DO collapse in a Set (the reliable dedup path)
		Set<ItemMetric> set = new HashSet<>(List.of(a, b));
		assertEquals(1, set.size(), "identical-case same-title metrics collapse in Set");
	}
}
