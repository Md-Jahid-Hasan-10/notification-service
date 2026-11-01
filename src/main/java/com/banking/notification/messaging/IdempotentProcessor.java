package com.banking.notification.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotentProcessor {

    private final ProcessedEventRepository repository;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;

    public IdempotentProcessor(ProcessedEventRepository repository,
                               TransactionTemplate transaction,
                               MeterRegistry meters) {
        this.repository = repository;
        this.transaction = transaction;
        this.meters = meters;
    }

    /** Runs the action once per (handler, eventId). Repeats are skipped. */
    public void processOnce(EventEnvelope envelope, String handler, Runnable action) {
        String id = handler + ":" + envelope.eventId();

        if (repository.existsById(id)) {
            count(envelope, "duplicate");
            return;
        }

        // If this throws, nothing is recorded, so the retry runs the action again.
        action.run();

        transaction.executeWithoutResult(status -> repository.insertIfAbsent(id));
        count(envelope, "processed");
    }

    /** For events this service does not act on. They are counted and then dropped. */
    public void ignore(EventEnvelope envelope) {
        count(envelope, "ignored");
    }

    private void count(EventEnvelope envelope, String result) {
        meters.counter("events.consumed", "type", envelope.eventType(), "result", result).increment();
    }
}