package com.portfolio.paymentsystem.outbox.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.portfolio.paymentsystem.outbox.domain.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findByAggregateTypeAndAggregateIdAndEventType(
            String aggregateType, UUID aggregateId, String eventType);

    /**
     * Claims a batch of rows for this publisher tick: due {@code PENDING}
     * rows, plus any {@code PUBLISHING} row that has sat unclaimed longer
     * than the stale timeout (a previous publisher instance/thread that
     * died mid-send). {@code FOR UPDATE SKIP LOCKED} is what makes this
     * safe if two publisher instances ever poll at the same moment: each
     * one only ever sees rows the other hasn't already locked, so the same
     * row is never claimed twice concurrently.
     * <p>
     * {@code next_attempt_at} does double duty by design: for a
     * {@code PENDING} row it means "don't retry before this time"; for a
     * {@code PUBLISHING} row it means "was claimed at this time" (see
     * {@code OutboxEvent#markPublishing}). Reusing one column avoids a
     * schema column that would only ever be read for stale-reclaim.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'PUBLISHING' AND next_attempt_at <= :staleBefore)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockDueForPublishing(
            @Param("now") Instant now, @Param("staleBefore") Instant staleBefore, @Param("limit") int limit);
}
