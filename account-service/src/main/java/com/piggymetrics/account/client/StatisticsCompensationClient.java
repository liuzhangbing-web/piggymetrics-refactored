package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Compensation-only statistics client used by the reconciliation job.
 *
 * <p>Deliberately has NO Sentinel fallback: the job MUST observe real success/failure so it
 * can keep a task PENDING and retry when statistics-service is still unavailable. (The
 * primary {@link StatisticsServiceClient} keeps its fallback to preserve legacy caller
 * availability semantics on the live request path.) The endpoint and semantics are identical
 * to {@code StatisticsServiceClient.updateStatistics}; only the degradation policy differs.
 */
@FeignClient(name = "statistics-service", contextId = "statisticsCompensationClient")
public interface StatisticsCompensationClient {

	@RequestMapping(method = RequestMethod.PUT, value = "/statistics/{accountName}",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	void updateStatistics(@PathVariable("accountName") String accountName, Account account);

}
