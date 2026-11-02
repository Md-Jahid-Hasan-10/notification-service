# notification-service

> Owns outbound e-mail for the banking platform: it renders a fixed set of templates, sends them over SMTP, records every attempt in `notification_logs`, and announces a failed OTP delivery back to the saga. Application port **8087**, management/metrics port **18087**.

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

The notification service owns **outbound e-mail**. It renders a small set of fixed templates, sends the mail through SMTP (`JavaMailSender` / `SimpleMailMessage`), and records every attempt in the `notification_logs` table. E-mail is the only channel: `NotificationChannel` is an interface with exactly one implementation, `EmailChannel`. There is no SMS channel and no push channel.

It can be called three ways. Synchronously, over its internal REST API — a free-form e-mail (`POST /internal/notifications/email`) or a templated e-mail (`POST /internal/notifications/templated`) — which is how the OTP service (`otp-service`, port 8086) delivers a one-time password. And by consuming Kafka events: OTP codes from `otp.events`, transfer outcomes from `transfer.events`, and e-mail verification requests from `user.events`.

It is the only service that decrypts the OTP code (AES-GCM with the shared `EVENT_CRYPTO_KEY`) and the e-mail verification token. It is also the service that tells the saga that a delivery failed: it publishes `OtpDeliveryFailed` on `notification.events`, and the fund-transfer service (`fund-transfer-service`, port 8082) reacts to it by failing the transfer with the error code `OTP_DELIVERY_FAILED`.

It does **not** own the transfer, the OTP or the verification token. It does not decide what a transfer is worth or whether it succeeded — it only renders a message from facts other services published. It never retries a send itself: the Kafka consumers are retried by the container's error handler, and the REST endpoint fails fast with a 502.

## Architecture At A Glance

```
REST path
  otp-service --POST /internal/notifications/templated--> notification-service
  fund-transfer-service / others --POST /internal/notifications/email--> notification-service
                                                         |
                                                  SMTP (smtp.gmail.com:587)
                                                         |
                                              notification_logs (PostgreSQL)

Kafka path
  otp.events        --> OtpEventsConsumer        --> OTP mail (decrypts the code)
  transfer.events   --> TransferEventsConsumer   --> transfer outcome mail
  user.events       --> UserEventsConsumer       --> e-mail verification mail
                                                         |
                                         notification.events (OtpDeliveryFailed)
                                                         v
                                          fund-transfer-service (saga)
```

| Depends on | Depended on by |
|---|---|
| PostgreSQL `notification_db` (`localhost:5433`) — `notification_logs` and `processed_events` | `otp-service` (port 8086) — calls `POST /internal/notifications/templated` for the OTP mail |
| SMTP server `smtp.gmail.com:587` | `fund-transfer-service` (port 8082) — consumes `notification.events` (`OtpDeliveryFailed`) |
| Kafka (`localhost:9092`) — consumes `user.events`, `otp.events`, `transfer.events`; publishes `notification.events` | `user-service` (port 8083) — the verification mail is triggered by `EmailVerificationRequested` on `user.events` |
| Eureka (`localhost:8761`) | The api-gateway (port 8080) blocks `/internal/**`, so these endpoints are not reachable from a browser. |

Redis is **not** a dependency of this service.

## HTTP API

Base path: `/internal/notifications`. Both endpoints are `POST` and accept `application/json`. Neither requires authentication — the api-gateway blocks `/internal/**` instead.

### `POST /internal/notifications/email`

Sends a free-form e-mail. Returns **200** on success.

Request body — `EmailRequest`:

| Field | Type | Required | Validation message |
|---|---|---|---|
| `to` | `String` | yes (`@NotBlank`) | `"Recipient is required"` |
| `to` | `String` | (`@Email`) | `"Recipient email format is invalid"` |
| `subject` | `String` | yes (`@NotBlank`) | `"Subject is required"` |
| `message` | `String` | yes (`@NotBlank`) | `"Message is required"` |

Response body — `EmailResponse`:

| Field | Type | Meaning |
|---|---|---|
| `status` | `String` | Always `"SENT"`. |

```json
{"status":"SENT"}
```

The notification is written to `notification_logs` with `type = GENERAL` and `status = SENT`.

### `POST /internal/notifications/templated`

Sends an e-mail rendered from a fixed template. Returns **200** on success.

Request body — `TemplatedEmailRequest`:

| Field | Type | Required | Validation message |
|---|---|---|---|
| `to` | `String` | yes (`@NotBlank`) | `"Recipient is required"` |
| `to` | `String` | (`@Email`) | `"Recipient email format is invalid"` |
| `type` | `NotificationType` | yes (`@NotNull`) | `"Type is required"` |
| `params` | `Map<String,String>` | optional (null is treated as an empty map) | – |

`NotificationType` — the exact enum values are `GENERAL`, `OTP`, `TRANSFER_SUCCESS`, `TRANSFER_FAILED`.

Response body — `EmailResponse`:

| Field | Type | Meaning |
|---|---|---|
| `status` | `String` | Always `"SENT"`. |

```json
{"status":"SENT"}
```

Status codes (from `GlobalExceptionHandler`):

| Situation | Status | `message` |
|---|---|---|
| Success | **200** | body `{"status":"SENT"}` |
| Bean validation failure | **400** | `"<field>: <message>; ..."` — the individual messages joined with `"; "` |
| Malformed JSON | **400** | `"Malformed request body"` |
| Missing template param | **400** | `"Missing template param: <key>"` |
| `type = GENERAL` on the templated endpoint | **400** | `"GENERAL has no template, use /internal/notifications/email"` |
| SMTP failure (`MailException`) | **502** | `"Failed to send email"` |
| Unknown path | **404** | `"Not found"` |
| Anything else | **500** | `"Something went wrong"` (stack trace logged as `"Unhandled error in the notification service"`) |

### The templates

Templates live in `NotificationTemplates`. The subject and the body are fixed strings; only the listed params are substituted.

| `type` | Subject | Body | Required params | Optional params (default) |
|---|---|---|---|---|
| `OTP` | `"Your Banking OTP"` | `"Your one-time password is <otp>.\nIt expires in 5 minutes. Do not share it with anyone."` | `otp` | – |
| `TRANSFER_SUCCESS` | `"Transfer Successful"` | `"Your transfer was completed.\nAmount: <amount>\nFrom account: <fromAccount>\nTo account: <toAccount>\nFee: <fee>\nReference: <reference>"` | `amount`, `fromAccount`, `toAccount`, `reference` | `fee` (default `"0"`) |
| `TRANSFER_FAILED` | `"Transfer Failed"` | `"Your transfer could not be completed.\nAmount: <amount>\nFrom account: <fromAccount>\nTo account: <toAccount>\nReason: <reason>"` | `amount`, `fromAccount`, `toAccount` | `reason` (default `"Unknown"`) |
| `GENERAL` | – | **no template** | – | – |

A required param that is missing, null or blank produces `"Missing template param: <key>"` and HTTP 400. `GENERAL` on this endpoint produces `"GENERAL has no template, use /internal/notifications/email"` and HTTP 400.

### Error Response Shape

Every non-2xx response uses the same body — `ErrorResponse`:

| Field | Type | Notes |
|---|---|---|
| `timestamp` | `LocalDateTime` | Server-local time of the failure. Serialised as `2026-11-02T10:15:30.123456`. |
| `status` | `int` | The HTTP status code, repeated in the body. |
| `error` | `String` | The HTTP reason phrase, e.g. `"Bad Request"`, `"Bad Gateway"`. |
| `message` | `String` | The exact human-readable explanation (see the tables above). |

Example:

```json
{
  "timestamp": "2026-11-02T10:15:30.123456",
  "status": 400,
  "error": "Bad Request",
  "message": "Missing template param: otp"
}
```

## Database

* URL: `jdbc:postgresql://localhost:5433/notification_db` → database name **`notification_db`**
* Username: `${DB_USERNAME:banking_admin}`
* Password: `${DB_PASSWORD:postgre2188}`
* `spring.jpa.hibernate.ddl-auto: update`, `spring.jpa.open-in-view: false`

The database `notification_db` is created by `postgres/init/01-create-databases.sql`.

### Table `notification_logs`

Entity: `NotificationLog`, `@Entity @Table(name = "notification_logs")`. Repository: `NotificationLogRepository extends JpaRepository<NotificationLog, Long>`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `Long` (bigserial) | no | `@Id`, `@GeneratedValue(strategy = IDENTITY)` |
| `recipient` | `String` | no | `length = 150` |
| `subject` | `String` | no | `length = 200` |
| `type` | `String` | no | `length = 30`, enum `NotificationType` stored as `EnumType.STRING` (`GENERAL`, `OTP`, `TRANSFER_SUCCESS`, `TRANSFER_FAILED`) |
| `status` | `String` | no | `length = 20`, enum `NotificationStatus` stored as `EnumType.STRING` (`SENT`, `FAILED`) |
| `error_message` | `String` | yes | `length = 500`; the SMTP exception message truncated to 500 characters |
| `created_at` | `LocalDateTime` | no | `updatable = false`; set in `@PrePersist` to `LocalDateTime.now()` |

One row is written for **every** attempt, successful or not. A success writes `status = SENT` with a null `error_message`; an SMTP failure writes `status = FAILED` with the exception message.

### Table `processed_events`

Entity: `ProcessedEvent`, `@Entity @Table(name = "processed_events")`. This is the idempotency ledger: it stops a Kafka message from producing the same mail twice.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `String` | no | `@Id`, `length = 160`; the value is `"<handler>:<eventId>"`, e.g. `"otp-events:6b0a2f0e-…"` |
| `processed_at` | `Instant` | no | When the claim was taken |

Repository: `ProcessedEventRepository extends JpaRepository<ProcessedEvent, String>`.

| Method | SQL | Purpose |
|---|---|---|
| `insertIfAbsent(id)` | `insert into processed_events (id, processed_at) values (:id, now()) on conflict (id) do nothing` | Claims the event. Only the first caller gets a row back; everyone else gets 0. |
| `deleteClaim(id)` | `delete from processed_events where id = :id` | Releases the claim so a failed action can be retried. |
| `deleteOlderThan(cutoff)` | JPQL `delete from ProcessedEvent p where p.processedAt < :cutoff` | Housekeeping, run by `ProcessedEventCleaner`. |

**Schema repair.** `postgres/init/02-notification-processed-events.sql` and `postgres/init/04-repair-schema.sql` rename a legacy `event_id` column back to `id` and widen it to `varchar(160)`. This matters: a wrong column name broke `existsById()` and `insertIfAbsent()`, every mail went to the dead-letter topic, and nothing was sent. The scripts are idempotent and run against `notification_db`.

### The cleanup job

`ProcessedEventCleaner` runs `@Scheduled(cron = "0 30 3 * * *")` — every day at 03:30 — and deletes rows older than **14 days**. Topics keep messages for 7 days, so an id older than 14 days can no longer be redelivered.

## Kafka Topics

Topics are declared in `TopicNames`: `USER_EVENTS = "user.events"`, `OTP_EVENTS = "otp.events"`, `TRANSFER_EVENTS = "transfer.events"`, `NOTIFICATION_EVENTS = "notification.events"`, `DLT_SUFFIX = ".DLT"`.

Every message on the bus uses the same envelope — `EventEnvelope`:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | `String` | Fresh UUID for the message. |
| `eventType` | `String` | e.g. `"OtpIssued"`, `"TransferCompleted"`. |
| `version` | `int` | Always `1`. |
| `correlationId` | `String` | Usually the `transferId`. |
| `causationId` | `String` | The `eventId` of the message that caused this one. |
| `occurredAt` | `Instant` | When the message was built. |
| `producer` | `String` | `app.messaging.producer-name`, i.e. `"notification-service"`. |
| `payload` | `JsonNode` | The type-specific payload. |

### Consumed

| Topic | Listener | Handler key | Event types handled |
|---|---|---|---|
| `otp.events` | `OtpEventsConsumer` | `otp-events` | `OtpIssued` → send the OTP mail. Everything else (including `OtpIssueRejected`) is ignored. |
| `transfer.events` | `TransferEventsConsumer` | `transfer-events` | `TransferCompleted`, `TransferFailed`, `TransferReversed` → mail. `ReconciliationRequired` → WARN log only, ignored. |
| `user.events` | `UserEventsConsumer` | `user-events` | `EmailVerificationRequested` → verification mail. `UserRegistered` → ignored. Unknown types → ignored. |

Payloads:

**`OtpIssued`** — `OtpIssuedPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the code belongs to. Also the AAD for the decryption. |
| `otpId` | `String` | The UUID of the stored code. |
| `email` | `String` | The recipient. |
| `encryptedCode` | `String` | The AES-GCM encrypted code; decrypted with `crypto.decrypt(encryptedCode, transferId.trim())`. |
| `expiresAt` | `Instant` | When the code stops being valid. |

**Transfer outcome** — `TransferOutcomeEvent`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer reference. |
| `type` | `String` | `DEPOSIT` or `WITHDRAWAL` select the cash-mail path; anything else is treated as a transfer. |
| `userId` | `Long` | The user. |
| `email` | `String` | The recipient. |
| `fromAccountNumber` | `String` | Source account. |
| `toAccountNumber` | `String` | Target account. |
| `amount` | `BigDecimal` | The amount. |
| `fee` | `BigDecimal` | The fee; rendered as `"0.00"` when it is null. |
| `reason` | `String` | The failure reason. |

**`EmailVerificationRequested`** — `EmailVerificationPayload`:

| Field | Type | Meaning |
|---|---|---|
| `userId` | `Long` | The user; also the AAD for the token decryption. |
| `email` | `String` | The recipient. |
| `fullName` | `String` | Used in the greeting. |
| `encryptedToken` | `String` | The AES-GCM encrypted verification token. |
| `expiresAt` | `Instant` | Expiry printed in the mail. |

### Published

Topic **`notification.events`**, event type **`OtpDeliveryFailed`** — payload `OtpDeliveryFailedPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the OTP belonged to. Also the Kafka key. |
| `reason` | `String` | A human-readable explanation (see below). |

Published by `OtpDeliveryFailureAnnouncer`. `correlationId` is the `transferId`; `causationId` is the `eventId` of the failing `OtpIssued` message.

| Reason | Announced from | When |
|---|---|---|
| `"The OTP email could not be delivered"` | `KafkaConsumerConfig.announceOtpFailure(...)` | All retries for the `OtpIssued` message failed. |
| `"The OTP expired before it could be delivered"` | `OtpEventsConsumer.deliver(...)` | `payload.expiresAt()` is before `Instant.now()`. **The mail is not sent in this case.** |

The fund-transfer saga consumes this event and fails the transfer with the error code **`OTP_DELIVERY_FAILED`** and the message `"The one-time password could not be delivered. Please start again"` (`fund-transfer-service`, `TransferSagaEngine.onOtpDeliveryFailed`). That literal error code lives in the fund-transfer service, not here — this service only publishes the human-readable `reason`.

### Idempotency, retries, dead letters and metrics

`IdempotentProcessor` runs the action once per `(handler, eventId)`. The key `"<handler>:<eventId>"` is claimed **before** the action runs (`insertIfAbsent` wins the race); if the action throws, the claim is released so the retry can run again. Metric: `events.consumed` counter with the tags `type` (the eventType) and `result` ∈ {`processed`, `duplicate`, `failed`, `ignored`}.

`KafkaConsumerConfig` uses `ExponentialBackOffWithMaxRetries(4)` (initial 1 s, multiplier 2.0, max 10 s) and parks a failed record on `<topic>.DLT` on the same partition. `InvalidEventException` and `EventCryptoException` are **not retryable** and go straight to the DLT. The OTP failure announcement is published **before** the DLT parking, so the other services learn about the failure even if parking itself fails.

Kafka client tuning: consumer `group-id: notification-service`, `auto-offset-reset: earliest`, `enable-auto-commit: false`, `ack-mode: record`, `concurrency: 3`; producer `acks: all`, `enable.idempotence: true`, `linger.ms: 0`, `max.block.ms: 15000`, `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000`.

Every topic has **3 partitions**. `notification.events` is kept for 7 days with a 14-day DLT retention.

## Configuration

### Server and Spring properties

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8087` | Application port. Overridden by `SERVER_PORT`. |
| `management.server.port` | `18087` | Actuator / Prometheus port. |
| `spring.application.name` | `notification-service` | Name registered with Eureka. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/notification_db` | JDBC URL. `run-all.sh` overrides it with `jdbc:postgresql://127.0.0.1:5433/notification_db`. |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | Database user. |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | Database password. |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate maintains the schema. |
| `spring.jpa.open-in-view` | `false` | No session left open during view rendering. |
| `spring.mail.host` | `smtp.gmail.com` | SMTP host. |
| `spring.mail.port` | `587` | SMTP port. |
| `spring.mail.username` | `${MAIL_USERNAME}` | SMTP user; also the `From:` address of every mail. |
| `spring.mail.password` | `${MAIL_PASSWORD}` | SMTP password. |
| `spring.mail.properties.mail.smtp.auth` | `true` | SMTP authentication. |
| `spring.mail.properties.mail.smtp.starttls.enable` | `true` | STARTTLS. |
| `spring.mail.properties.mail.smtp.connectiontimeout` | `5000` | Connect timeout (ms). |
| `spring.mail.properties.mail.smtp.timeout` | `5000` | Read timeout (ms). |
| `spring.mail.properties.mail.smtp.writetimeout` | `5000` | Write timeout (ms). |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | Broker list. |
| `spring.kafka.consumer.group-id` | `notification-service` | Consumer group. |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Where a new group starts. |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Offsets are committed by the listener. |
| `spring.kafka.listener.ack-mode` | `record` | Ack per record. |
| `spring.kafka.listener.concurrency` | `3` | Listener threads. |
| `spring.kafka.producer.acks` | `all` | Full durability. |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Idempotent producer. |
| `spring.kafka.producer.properties.linger.ms` | `0` | Send immediately. |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | Max block on send. |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | Request timeout. |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | Delivery timeout. |
| `spring.config.import` | `optional:file:.env[.properties]` | Reads a `.env` file from the working directory if present. |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` | Discovery server. **No environment variable here** — unlike otp-service and charge-fee-service, this service does not use `${EUREKA_URL:...}`. |
| `eureka.instance.prefer-ip-address` | `true` | Register by IP. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Exposed actuator endpoints. |
| `management.endpoint.health.show-details` | `never` | Health details are not published. |
| `management.health.mail.enabled` | `false` | The mail health indicator is disabled — it opened a real SMTP connection on every probe, which took about three seconds and made the health check look like a timeout. |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | Latency histograms. |

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.messaging.producer-name` | `notification-service` | The `producer` field of the published envelope. |
| `app.messaging.crypto-key` | `${EVENT_CRYPTO_KEY:pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=}` | Base64 AES-256 key; must decode to 32 bytes. Used to decrypt `encryptedCode` and `encryptedToken`. |
| `app.notification.verification-url` | `${VERIFY_URL:http://localhost:3000/verify.html?token=}` | Prefix of the verification link; the URL-encoded token is appended. |

Binding class: `MessagingProperties` (`@ConfigurationProperties(prefix = "app.messaging")`, record `producerName`, `cryptoKey`).

### Environment variables

| Name | Default | Used for |
|---|---|---|
| `DB_USERNAME` | `banking_admin` | `spring.datasource.username` |
| `DB_PASSWORD` | `postgre2188` | `spring.datasource.password` |
| `MAIL_USERNAME` | **no default — required** | `spring.mail.username`; also the `From:` address of every mail |
| `MAIL_PASSWORD` | **no default — required** | `spring.mail.password` |
| `EVENT_CRYPTO_KEY` | `pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=` | `app.messaging.crypto-key` |
| `VERIFY_URL` | `http://localhost:3000/verify.html?token=` | `app.notification.verification-url` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `spring.kafka.bootstrap-servers` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/notification_db` | `run-all.sh` sets it to `jdbc:postgresql://127.0.0.1:5433/notification_db` |
| `SERVER_PORT` | `8087` (set by `run-all.sh`) | Overrides `server.port`. |

#### Environment variables you must set

* `MAIL_USERNAME` and `MAIL_PASSWORD` — **no default**. The service will not start without them. `MAIL_USERNAME` is also the `From:` address, so it must be a real, permitted sender (a Gmail account with an app password for `smtp.gmail.com:587`).
* `EVENT_CRYPTO_KEY` — **must be identical** to the value in `otp-service`. If the two differ, the OTP code cannot be decrypted, no OTP mail is sent and the message is parked in `otp.events.DLT` with an `EventCryptoException`. Compare the `"Event crypto key fingerprint: {}"` line in the two logs.

The key must be Base64 for exactly 32 bytes:

```bash
openssl rand -base64 32
```

## Behaviour Rules

| Rule | Value | Notes |
|---|---|---|
| Channels | exactly one — **e-mail** | `NotificationChannel` is the interface; `EmailChannel` is the only implementation (`SimpleMailMessage`, `From` = `spring.mail.username`). There is no SMS or push channel. |
| Every attempt is logged | yes | Success → a `notification_logs` row with `status = SENT`. SMTP failure → a row with `status = FAILED` and `error_message` = the exception message truncated to **500** characters, then `NotificationFailedException("Failed to send email")` → HTTP **502**. |
| The decrypted code is never logged | enforced | `OtpEmailService` decrypts the code and hands it straight to the template; the comment says *"The code is only decrypted here, and is never logged."* |
| An expired OTP is never mailed | enforced | `OtpEventsConsumer` checks `payload.expiresAt()` and announces `"The OTP expired before it could be delivered"` instead of sending a mail. |
| Cash movements do not use a template | DEPOSIT / WITHDRAWAL | `TransferEmailService.sendCash(...)` builds a free-text mail with subject `"Deposit successful"` / `"Deposit failed"` / `"Withdrawal successful"` / `"Withdrawal failed"`. |
| A reversal is reported as a failure | `TransferReversed` | Mapped to `TRANSFER_FAILED` with the reason suffixed by `" Your money was returned."`. |
| Idempotency | one mail per `(handler, eventId)` | The claim is taken before the action and released if the action fails. |
| Duplicate events are counted, not failed | `duplicate` | `events.consumed{result="duplicate"}` is incremented and the message is skipped. |

### Failure modes

| Failure | Where it is handled | Result |
|---|---|---|
| `NotificationFailedException` | `GlobalExceptionHandler` | HTTP **502** `"Failed to send email"` |
| `InvalidTemplateRequestException` | `GlobalExceptionHandler` | HTTP **400** |
| `InvalidEventException` (missing payload fields) | Kafka error handler | Not retryable → straight to the DLT |
| `EventCryptoException` (wrong `EVENT_CRYPTO_KEY`) | Kafka error handler | Not retryable → straight to the DLT. This is the failure mode the fingerprint logger exists for. |
| `OtpDeliveryFailed` on `notification.events` | `fund-transfer-service` | The saga fails the transfer with the error code `OTP_DELIVERY_FAILED` |

The exact validation messages for the payloads are:

| Event | Missing field | Message |
|---|---|---|
| `OtpIssued` | `transferId`, `email` or `encryptedCode` | `"OtpIssued is missing required fields"` |
| Transfer outcome | `transferId`, `email`, `fromAccountNumber`, `toAccountNumber` or `amount` | `"<eventType> is missing required fields"` |
| `EmailVerificationRequested` | `userId`, `email` or `encryptedToken` | `"EmailVerificationRequested is missing required fields"` |

The envelope-level errors are `"Empty message"`, `"Message is not valid JSON"`, `"Message has no eventId or eventType"`, `"Message has no payload"` and `"Payload does not match <eventType>"`.

## Running It

### Prerequisites

* Java 17
* Maven 3.8+ (or use the bundled `./mvnw`)
* PostgreSQL on `127.0.0.1:5433` with the database `notification_db` and the user `banking_admin`
* Kafka on `localhost:9092`
* Eureka on `localhost:8761`
* SMTP access to `smtp.gmail.com:587` — a Gmail account with an app password
* Redis is **not** required

### Build

```bash
cd notification-service
mvn -q clean package -DskipTests
# or
./mvnw -q clean package -DskipTests
```

Test: `mvn test` (only `NotificationServiceApplicationTests#contextLoads` exists).
Jar: `notification-service/target/notification-service-0.0.1-SNAPSHOT.jar`.

### Run

```bash
cd notification-service
export DB_USERNAME=banking_admin
export DB_PASSWORD='postgre2188'
export MAIL_USERNAME='your-account@gmail.com'
export MAIL_PASSWORD='your-app-password'
export EVENT_CRYPTO_KEY='pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk='
export VERIFY_URL='http://localhost:3000/verify.html?token='
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export SERVER_PORT=8087

java -jar target/notification-service-0.0.1-SNAPSHOT.jar
```

### Whole stack

From the repository root:

```bash
./run-all.sh          # start everything
./run-all.sh --build  # rebuild the jars first
./stop-all.sh         # stop everything
```

`run-all.sh` starts the service on **8087** with `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5433/notification_db`.

### Check that it is healthy

```bash
curl -s http://localhost:18087/actuator/health
```

```json
{"status":"UP"}
```

Note that the mail health indicator is disabled, so `UP` does **not** prove that SMTP works. Send a test mail with the curl example below.

### Stop it

```bash
# foreground
Ctrl+C

# as a background process
kill <pid>
# or, for the whole stack
./stop-all.sh
```

## API Quick Reference (curl examples)

The `/internal/**` endpoints are blocked by the api-gateway, so call them directly on port 8087. No `Authorization` header is required — the endpoints do not authenticate.

Send a free-form e-mail:

```bash
curl -i -X POST http://localhost:8087/internal/notifications/email \
  -H 'Content-Type: application/json' \
  -d '{
        "to": "customer@example.com",
        "subject": "Account statement",
        "message": "Your statement for November is ready."
      }'
```

```http
HTTP/1.1 200
{"status":"SENT"}
```

Send a templated OTP e-mail (this is exactly what `otp-service` does):

```bash
curl -i -X POST http://localhost:8087/internal/notifications/templated \
  -H 'Content-Type: application/json' \
  -d '{
        "to": "customer@example.com",
        "type": "OTP",
        "params": {"otp": "123456"}
      }'
```

```http
HTTP/1.1 200
{"status":"SENT"}
```

Send a templated transfer-success e-mail:

```bash
curl -i -X POST http://localhost:8087/internal/notifications/templated \
  -H 'Content-Type: application/json' \
  -d '{
        "to": "customer@example.com",
        "type": "TRANSFER_SUCCESS",
        "params": {
          "amount": "250.00",
          "fromAccount": "ACC-1001",
          "toAccount": "ACC-2002",
          "fee": "10.00",
          "reference": "TXN-3A422C5733554406"
        }
      }'
```

A missing template param returns HTTP 400:

```bash
curl -i -X POST http://localhost:8087/internal/notifications/templated \
  -H 'Content-Type: application/json' \
  -d '{"to":"customer@example.com","type":"OTP"}'
```

```http
HTTP/1.1 400
{"timestamp":"2026-11-02T10:15:30.123456","status":400,"error":"Bad Request","message":"Missing template param: otp"}
```

An unknown path returns HTTP 404:

```bash
curl -i http://localhost:8087/internal/notifications/nope
```

## Configuration Reference (quick table)

| Knob | Default | Change it to |
|---|---|---|
| `MAIL_USERNAME` / `MAIL_PASSWORD` | none — required | A Gmail account and its app password. `MAIL_USERNAME` is also the `From:` address. |
| `app.messaging.crypto-key` | a built-in default | Base64 of 32 bytes; **must match otp-service**. |
| `app.notification.verification-url` | `http://localhost:3000/verify.html?token=` | The prefix of the verification link; the token is appended. |
| `app.messaging.producer-name` | `notification-service` | The `producer` field of published envelopes. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/notification_db` | Your PostgreSQL instance. |
| `spring.mail.host` / `spring.mail.port` | `smtp.gmail.com` / `587` | Your SMTP relay. |
| `server.port` | `8087` | The application port. |
| `management.server.port` | `18087` | The actuator/metrics port. |
| `management.health.mail.enabled` | `false` | Leave it off; the indicator opens a real SMTP connection on every probe. |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| The service does not start and complains about a mail property | `MAIL_USERNAME` or `MAIL_PASSWORD` is not set — they have no default. | Export both. |
| HTTP 502 with `"Failed to send email"` | The SMTP server rejected the message. | Check the log line `"Failed to send <type> email to <recipient>: <message>"`, the SMTP credentials, and whether the Gmail account needs an app password. The failure is also recorded in `notification_logs` with `status = FAILED`. |
| HTTP 400 with `"Missing template param: otp"` | The `params` map did not contain `otp`. | Add the required param for the type; see the template table. |
| HTTP 400 with `"GENERAL has no template, use /internal/notifications/email"` | `GENERAL` was sent to the templated endpoint. | Use `POST /internal/notifications/email` for a free-form message. |
| HTTP 400 with `"Recipient email format is invalid"` | The address does not pass `@Email`. | Check the address. |
| The OTP mail never arrives and the message is in `otp.events.DLT` | The crypto keys of this service and `otp-service` differ, so the code cannot be decrypted. | Compare the `"Event crypto key fingerprint: {}"` line in both logs; set the same `EVENT_CRYPTO_KEY` in both. |
| Every message ends up in the DLT and no mail is sent | The `processed_events` table has the legacy column `event_id` instead of `id`, so `existsById()` and `insertIfAbsent()` fail. | Run `postgres/init/02-notification-processed-events.sql` (or `04-repair-schema.sql`) against `notification_db`; it renames the column back to `id` and widens it to `varchar(160)`. |
| No mail at all, but the service is healthy | The mail health indicator is disabled, so `UP` does not mean SMTP works. | Send a test mail with the curl example and check the SMTP credentials. |
| A duplicate event arrives and no second mail is sent | The `(handler, eventId)` claim is already in `processed_events`. | Expected behaviour. Check `events.consumed{result="duplicate"}`. |
| The mail arrives twice for one event | The claim was released after a failure and the retry re-ran the action. | Check `notification_logs` for two rows and the log for `"Could not release the processed-event claim"`. |
| The verification link points at the wrong page | `VERIFY_URL` still has its default value. | Set `VERIFY_URL` to the address of your verification page; the URL-encoded token is appended to it. |
| `"Could not release the processed-event claim …"` in the log | The claim could not be deleted, so that event will not be processed again. | Investigate the database connection; the message can be replayed by hand from the DLT. |
| `404` with `"Not found"` | The path does not exist. | The only endpoints are `/internal/notifications/email` and `/internal/notifications/templated`. |
| `500` with `"Something went wrong"` | An unexpected error; the stack trace is logged as `"Unhandled error in the notification service"`. | Read the notification-service log for the stack trace. |

## Notes and Known Limitations

* **E-mail is the only channel.** `NotificationChannel` has exactly one implementation. There is no SMS and no push notification.
* **`OTP_DELIVERY_FAILED` does not live in this service.** This service publishes the human-readable `reason` strings `"The OTP email could not be delivered"` and `"The OTP expired before it could be delivered"` on `notification.events`. The literal error code `OTP_DELIVERY_FAILED` and the message `"The one-time password could not be delivered. Please start again"` live in `fund-transfer-service` (`TransferSagaEngine.onOtpDeliveryFailed`).
* **The mail health indicator is switched off** (`management.health.mail.enabled: false`). It opened a real SMTP connection to Gmail on every probe, took about three seconds and made the health check look like a timeout. The mail server is checked when a mail is actually sent.
* **The `From:` address is `spring.mail.username`.** Every mail goes out from the SMTP account; there is no separate display-name or no-reply configuration.
* **The mail body is plain text** (`SimpleMailMessage`). There is no HTML template and no attachment support.
* **The `notification_logs` table grows without bound.** Nothing prunes it; only `processed_events` has a cleanup job.
* **The `processed_events` cleanup job runs at 03:30 every day** and removes rows older than 14 days. If the service was down over midnight, the job is missed and the rows stay until the next run.
* **The schema is managed by Hibernate** (`ddl-auto: update`), and the two init scripts repair the `processed_events` column. The `04-repair-schema.sql` script also has to run against an existing installation, because Docker only executes the init scripts when the data directory is empty.
* **The Eureka URL is not configurable by environment variable here.** The service hard-codes `http://localhost:8761/eureka`, while `otp-service` and `charge-fee-service` use `${EUREKA_URL:...}`.
* **`app.messaging.crypto-key` must match otp-service.** This only works because the same value happens to be set in both services.
* **`spring.mail.host` and `spring.mail.port` are not environment-driven.** They are fixed in `application.yaml` at `smtp.gmail.com` and `587`.
* **The decrypted OTP code is never logged**, but it does travel over the network in the REST call from `otp-service` to `/internal/notifications/templated` as the `params.otp` value. That hop is plain HTTP.
* **A failed send does not roll back the claim** unless the claim could be released. The original message stays in the DLT and can be replayed by hand.
