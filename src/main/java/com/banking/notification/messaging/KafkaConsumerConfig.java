package com.banking.notification.messaging;

import com.banking.notification.messaging.payload.OtpIssuedPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 EventParser parser,
                                                 OtpDeliveryFailureAnnouncer announcer) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        record.topic() + TopicNames.DLT_SUFFIX, record.partition()));

        // After the last retry: park the message on "<topic>.DLT", and when it was an OTP
        // that could not be delivered, tell the other services.
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            deadLetters.accept(record, exception);
            announceOtpFailure(record, parser, announcer);
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class, EventCryptoException.class);
        return handler;
    }

    private void announceOtpFailure(ConsumerRecord<?, ?> record, EventParser parser,
                                    OtpDeliveryFailureAnnouncer announcer) {
        if (!TopicNames.OTP_EVENTS.equals(record.topic()) || !(record.value() instanceof String value)) {
            return;
        }
        try {
            EventEnvelope envelope = parser.parse(value);
            if ("OtpIssued".equals(envelope.eventType())) {
                OtpIssuedPayload payload = parser.payload(envelope, OtpIssuedPayload.class);
                announcer.announce(payload.transferId(), "The OTP email could not be delivered",
                        envelope.eventId());
            }
        } catch (RuntimeException ignored) {
            // The message is unreadable, so there is nothing to announce. It stays in the DLT.
        }
    }
}