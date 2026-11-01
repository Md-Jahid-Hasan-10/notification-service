package com.banking.notification.messaging;

import com.banking.notification.messaging.payload.OtpDeliveryFailedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OtpDeliveryFailureAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(OtpDeliveryFailureAnnouncer.class);

    private final DirectEventPublisher publisher;

    public OtpDeliveryFailureAnnouncer(DirectEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void announce(String transferId, String reason, String causationId) {
        try {
            publisher.publish(TopicNames.NOTIFICATION_EVENTS, transferId, "OtpDeliveryFailed",
                    transferId, causationId, new OtpDeliveryFailedPayload(transferId, reason));
        } catch (RuntimeException ex) {
            // The original message is still in the dead-letter topic, so it can be replayed.
            log.error("Could not announce OtpDeliveryFailed for transfer {}: {}", transferId, ex.getMessage());
        }
    }
}