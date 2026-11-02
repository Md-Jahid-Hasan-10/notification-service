package com.banking.notification.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotentProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentProcessor.class);

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

    /**
     * Runs the action once per (handler, eventId). Repeats are skipped.
     *
     * The event id is claimed BEFORE the action runs. If the action then
     * fails, the claim is released again so the retry can try once more.
     *
     * The claim comes first on purpose: if it came after, a failure while
     * recording the claim would leave the email sent but not recorded, and
     * every Kafka retry would send the email a second time.
     */
    public void processOnce(EventEnvelope envelope, String handler, Runnable action) {
        String id = handler + ":" + envelope.eventId();

        if (repository.existsById(id)) {
            count(envelope, "duplicate");
            return;
        }

        // The insert only succeeds for the first attempt, so only one
        // consumer can own this event.
        Integer claimed = transaction.execute(status -> repository.insertIfAbsent(id));
        if (claimed == null || claimed == 0) {
            // Another consumer got there first.
            count(envelope, "duplicate");
            return;
        }

        try {
            action.run();
        } catch (RuntimeException | Error ex) {
            // The action failed, so the event was not handled. Release the
            // claim, otherwise the retry would be treated as a duplicate.
            release(id);
            count(envelope, "failed");
            throw ex;
        }

        count(envelope, "processed");
    }

    /** For events this service does not act on. They are counted and then dropped. */
    public void ignore(EventEnvelope envelope) {
        count(envelope, "ignored");
    }

    private void release(String id) {
        try {
            transaction.executeWithoutResult(status -> repository.deleteClaim(id));
        } catch (RuntimeException ex) {
            // The claim stays, so this event is not processed again.
            // The operator can see it in the logs and replay it by hand.
            log.error("Could not release the processed-event claim {}: {}", id, ex.getMessage());
        }
    }

    private void count(EventEnvelope envelope, String result) {
        meters.counter("events.consumed", "type", envelope.eventType(), "result", result).increment();
    }
}
