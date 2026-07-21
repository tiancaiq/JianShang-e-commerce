package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.config.NotificationReadProperties;
import com.msb.ecom.notification_service.dto.NotificationPageResponse;
import com.msb.ecom.notification_service.model.NotificationReadException;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class NotificationReadService {

    private static final Logger log = LoggerFactory.getLogger(NotificationReadService.class);
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern BEARER =
            Pattern.compile("Bearer [A-Za-z0-9\\-._~+/]+=*");
    private static final int MAX_AUTHORIZATION_LENGTH = 4103;

    private final NotificationReadProperties properties;
    private final NotificationActorIdentityClient identity;
    private final NotificationRepository repository;
    private final NotificationPresentationMapper presentation;
    private final NotificationReadMetrics metrics;
    private final Clock clock;

    @Autowired
    public NotificationReadService(
            NotificationReadProperties properties,
            NotificationActorIdentityClient identity,
            NotificationRepository repository,
            NotificationPresentationMapper presentation,
            NotificationReadMetrics metrics) {
        this(properties, identity, repository, presentation, metrics, Clock.systemUTC());
    }

    NotificationReadService(
            NotificationReadProperties properties,
            NotificationActorIdentityClient identity,
            NotificationRepository repository,
            NotificationPresentationMapper presentation,
            NotificationReadMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.identity = identity;
        this.repository = repository;
        this.presentation = presentation;
        this.metrics = metrics;
        this.clock = clock;
    }

    // Lists a deterministic page only after the feature, bearer, query, and Auth gates pass.
    public NotificationPageResponse list(
            String authorization,
            String correlationId,
            String cursorValue,
            String limitValue) {
        requireEnabled();
        String boundedAuthorization = requireBearer(authorization);
        NotificationCursorCodec.Cursor cursor = NotificationCursorCodec.decode(cursorValue);
        int limit = NotificationCursorCodec.limit(limitValue);
        String recipientUserId = resolveRecipient(boundedAuthorization, correlationId);
        try {
            List<NotificationReadRow> loaded = repository.findRecipientPage(
                    recipientUserId,
                    cursor == null ? null : cursor.createdAt(),
                    cursor == null ? null : cursor.notificationId(),
                    limit + 1);
            boolean hasMore = loaded.size() > limit;
            List<NotificationReadRow> visible = hasMore ? loaded.subList(0, limit) : loaded;
            List<NotificationPageResponse.NotificationItem> items =
                    visible.stream().map(presentation::map).toList();
            String nextCursor = hasMore
                    ? NotificationCursorCodec.encode(visible.get(visible.size() - 1))
                    : null;
            success("list", correlationId, recipientUserId);
            return new NotificationPageResponse(
                    items,
                    new NotificationPageResponse.PageMetadata(nextCursor, hasMore));
        } catch (NotificationReadException exception) {
            metrics.outcome("list", "invalid_projection");
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable("list", correlationId);
        }
    }

    // Marks one recipient-owned notification read while preserving the first timestamp on replay.
    public void markRead(
            String authorization,
            String correlationId,
            String notificationId,
            long contentLength,
            String transferEncoding) {
        requireEnabled();
        String boundedAuthorization = requireBearer(authorization);
        requireId(notificationId);
        requireNoBody(contentLength, transferEncoding);
        String recipientUserId = resolveRecipient(boundedAuthorization, correlationId);
        try {
            if (!repository.markOwnedRead(recipientUserId, notificationId, clock.instant())) {
                metrics.outcome("read_one", "not_found");
                throw new NotificationReadException(
                        HttpStatus.NOT_FOUND,
                        "NOTIFICATION_NOT_FOUND",
                        "Notification was not found.");
            }
            success("read_one", correlationId, recipientUserId);
        } catch (NotificationReadException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable("read_one", correlationId);
        }
    }

    // Marks only the resolved recipient's unread rows and never exposes an affected-row count.
    public void markAllRead(
            String authorization,
            String correlationId,
            long contentLength,
            String transferEncoding) {
        requireEnabled();
        String boundedAuthorization = requireBearer(authorization);
        requireNoBody(contentLength, transferEncoding);
        String recipientUserId = resolveRecipient(boundedAuthorization, correlationId);
        try {
            repository.markAllOwnedRead(recipientUserId, clock.instant());
            success("read_all", correlationId, recipientUserId);
        } catch (DataAccessException exception) {
            throw unavailable("read_all", correlationId);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new NotificationReadException(
                    HttpStatus.NOT_FOUND,
                    "NOTIFICATION_READ_API_NOT_AVAILABLE",
                    "Notification reads are not available.");
        }
    }

    private String requireBearer(String authorization) {
        if (authorization == null
                || authorization.length() > MAX_AUTHORIZATION_LENGTH
                || !BEARER.matcher(authorization).matches()) {
            metrics.outcome("identity", "authentication_required");
            throw new NotificationReadException(
                    HttpStatus.UNAUTHORIZED,
                    "NOTIFICATION_AUTHENTICATION_REQUIRED",
                    "Authentication is required.");
        }
        return authorization;
    }

    private void requireId(String notificationId) {
        if (notificationId == null || !ULID.matcher(notificationId).matches()) {
            throw new NotificationReadException(
                    HttpStatus.BAD_REQUEST,
                    "NOTIFICATION_ID_INVALID",
                    "Notification ID is invalid.");
        }
    }

    private void requireNoBody(long contentLength, String transferEncoding) {
        if (contentLength > 0 || (transferEncoding != null && !transferEncoding.isBlank())) {
            throw new NotificationReadException(
                    HttpStatus.BAD_REQUEST,
                    "NOTIFICATION_BODY_NOT_ALLOWED",
                    "This notification command does not accept a request body.");
        }
    }

    private String resolveRecipient(String authorization, String correlationId) {
        try {
            return identity.requireActiveUserId(authorization, correlationId);
        } catch (NotificationActorIdentityClient.AuthenticationRequiredException exception) {
            metrics.outcome("identity", "authentication_required");
            throw new NotificationReadException(
                    HttpStatus.UNAUTHORIZED,
                    "NOTIFICATION_AUTHENTICATION_REQUIRED",
                    "Authentication is required.");
        } catch (NotificationActorIdentityClient.AccessDeniedException exception) {
            metrics.outcome("identity", "access_denied");
            throw new NotificationReadException(
                    HttpStatus.FORBIDDEN,
                    "NOTIFICATION_ACCESS_DENIED",
                    "Notification access is not allowed.");
        } catch (NotificationActorIdentityClient.DependencyUnavailableException exception) {
            metrics.outcome("identity", "dependency_unavailable");
            throw new NotificationReadException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "NOTIFICATION_IDENTITY_UNAVAILABLE",
                    "Notification identity could not be resolved.");
        }
    }

    private NotificationReadException unavailable(String operation, String correlationId) {
        metrics.outcome(operation, "unavailable");
        log.warn(
                "Notification read failed operation={} outcome=unavailable correlationId={}",
                operation,
                correlationId);
        return new NotificationReadException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NOTIFICATION_READ_UNAVAILABLE",
                "Notifications are temporarily unavailable.");
    }

    private void success(String operation, String correlationId, String recipientUserId) {
        metrics.outcome(operation, "success");
        log.info(
                "Notification read completed operation={} outcome=success correlationId={} actorRef={}",
                operation,
                correlationId,
                reference(recipientUserId));
    }

    private String reference(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception exception) {
            return "unavailable";
        }
    }
}
