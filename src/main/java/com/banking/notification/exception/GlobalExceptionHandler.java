
package com.banking.notification.exception;

import com.banking.notification.observability.CurrentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final CurrentTrace currentTrace;

    public GlobalExceptionHandler(CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
    }

    @ExceptionHandler(InvalidTemplateRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTemplate(
            InvalidTemplateRequestException ex) {

        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(NotificationFailedException.class)
    public ResponseEntity<ErrorResponse> handleSendFailed(
            NotificationFailedException ex) {

        log.error("Could not send the email: {}", ex.getMessage(), ex);

        return build(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    // Permanent email delivery failure
    @ExceptionHandler(PermanentDeliveryException.class)
    public ResponseEntity<ErrorResponse> handlePermanentDelivery(
            PermanentDeliveryException ex) {

        return build(
                HttpStatus.BAD_GATEWAY,
                "EMAIL_PERMANENT_FAILURE",
                ex.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex) {

        return build(
                HttpStatus.BAD_REQUEST,
                "Malformed request body"
        );
    }

    /**
     * A request for a path that does not exist is a client mistake,
     * not a server failure.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex) {

        return build(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {

        log.error(
                "Unhandled error in the notification service",
                ex
        );

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong"
        );
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message) {

        return build(status, status.name(), message);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String code,
            String message) {

        return ResponseEntity.status(status)
                .body(body(status, code, message));
    }

    private ErrorResponse body(
            HttpStatus status,
            String code,
            String message) {

        return new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                currentTrace.traceId()
        );
    }
}
