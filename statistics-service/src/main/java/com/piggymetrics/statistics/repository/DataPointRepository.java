package com.piggymetrics.statistics.repository;

import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataPointRepository extends CrudRepository<DataPoint, DataPointId> {

	/**
	 * MIGRATION NOTE (bug B3, found by real-Mongo CVT-03 test): Spring Data MongoDB 4.x
	 * treats DataPointId as a simple type (a custom converter is registered for it), so the
	 * derived query "findByIdAccount" can no longer resolve the id.account property path.
	 * The explicit @Query below produces the IDENTICAL MongoDB filter {_id.account: ?0} as
	 * the legacy derived query - zero semantic/business change, wire-compatible.
	 */
	@Query("{'_id.account': ?0}")
	List<DataPoint> findByAccountName(String account);

}
