package com.portfolio.paymentsystem.outbox.exception;

import java.util.UUID;

/**
 * Thrown when {@code (aggregateType, aggregateId, eventType)} already has an
 * outbox row. In the current M4 flow this is defense-in-depth, not a real
 * business case — every optimistic-lock retry in {@code TransferExecutionService}
 * builds a brand-new {@code Transfer} id, so the same transfer never gets a
 * second write attempt. A plain {@link RuntimeException} (not an
 * {@code ApiException}): if this ever fires, it means an invariant the
 * application relies on was violated, not something a client can act on.
 */
public class DuplicateOutboxEventException extends RuntimeException {

    public DuplicateOutboxEventException(String aggregateType, UUID aggregateId, String eventType) {
        super("An outbox event already exists for " + aggregateType + ":" + aggregateId + " eventType=" + eventType);
    }
}
