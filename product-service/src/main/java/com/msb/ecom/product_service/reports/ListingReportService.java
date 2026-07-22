package com.msb.ecom.product_service.reports;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
class ListingReportService {

    private final ListingReportProperties properties;
    private final ListingReportRepository repository;
    private final ListingReportCanonicalizer canonicalizer;
    private final ListingReportAbuseHasher abuseHasher;
    private final ListingReportMetrics metrics;
    private final ListingReportTimeSource timeSource;
    private final AuthServiceClient authServiceClient;
    private final CurrentActorProvider currentActorProvider;
    private final UlidGenerator ulidGenerator;

    void requireEnabled() {
        if (!properties.intakeEnabled()) {
            metrics.intake("disabled", null);
            throw new ListingReportFeatureDisabledException();
        }
    }

    @Transactional
    // Creates one immutable allegation, subject snapshot, report case link, history record, and outbox event atomically.
    CreateResult create(
            ListingReportCreateRequest rawRequest,
            String rawIdempotencyKey,
            String correlationId) {
        requireEnabled();
        ListingReportCanonicalizer.NormalizedRequest request = canonicalizer.normalize(rawRequest);
        String idempotencyKey = normalizedIdempotencyKey(rawIdempotencyKey);
        Instant now = timeSource.now();
        CurrentActor actor = currentActorProvider.currentActor();
        AuthServiceClient.CurrentUser currentUser =
                authServiceClient.requireCurrentUserForReport(actor.accessToken());
        String reporterUserId = currentUser.id();

        boolean newIdempotency = repository.reserveIdempotency(
                reporterUserId,
                idempotencyKey,
                request.requestHash(),
                now.plus(properties.idempotencyRetention()),
                now);
        ListingReportRepository.IdempotencyReservation idempotency = repository
                .lockIdempotency(reporterUserId, idempotencyKey)
                .orElseThrow(ListingReportUnavailableException::new);
        if (!request.requestHash().equals(idempotency.requestHash())) {
            metrics.intake("idempotency_conflict", request.reason().name());
            throw new ListingReportIdempotencyConflictException();
        }
        if (idempotency.reportId() != null) {
            ListingReportResponse replay = ownedReport(idempotency.reportId(), reporterUserId);
            metrics.intake("replay", request.reason().name());
            return new CreateResult(replay, false);
        }
        if (!newIdempotency) {
            throw new ListingReportUnavailableException();
        }

        boolean newDuplicateWindow = repository.reserveDuplicateWindow(
                reporterUserId,
                request.listingId(),
                request.reason().name(),
                now.plus(properties.duplicateWindow()),
                now);
        ListingReportRepository.DuplicateWindow duplicateWindow = repository
                .lockDuplicateWindow(reporterUserId, request.listingId(), request.reason().name())
                .orElseThrow(ListingReportUnavailableException::new);
        if (duplicateWindow.reportId() != null) {
            repository.completeIdempotency(reporterUserId, idempotencyKey, duplicateWindow.reportId(), now);
            ListingReportResponse duplicate = ownedReport(duplicateWindow.reportId(), reporterUserId);
            metrics.intake("semantic_duplicate", request.reason().name());
            return new CreateResult(duplicate, false);
        }
        if (!newDuplicateWindow) {
            throw new ListingReportUnavailableException();
        }

        ListingReportRepository.PublicSubject subject = repository.lockPublicSubject(request.listingId())
                .orElseThrow(ListingReportNotFoundException::new);
        requireNonOwner(actor.accessToken(), reporterUserId, subject);

        List<ListingReportCanonicalizer.HashedMedia> media = repository.findPublicMedia(subject.listingId()).stream()
                .map(item -> new ListingReportCanonicalizer.HashedMedia(
                        item,
                        canonicalizer.mediaSnapshotHash(item)))
                .toList();
        requireSelectedMedia(request.listingMediaIds(), media);
        incrementAcceptedRate(reporterUserId, now);

        String reportId = ulidGenerator.next();
        String snapshotId = ulidGenerator.next();
        String caseId = repository.ensureOpenReportCase(
                ulidGenerator.next(),
                subject,
                reporterUserId,
                request.reason(),
                now);
        String snapshotHash = canonicalizer.subjectHash(subject, media);
        repository.insertSnapshot(
                snapshotId,
                subject,
                properties.policyVersion(),
                repository.publicSnapshotJson(subject, media),
                snapshotHash,
                media,
                now);
        repository.insertReport(
                reportId,
                reporterUserId,
                request,
                properties.policyVersion(),
                snapshotId,
                caseId,
                now.plus(properties.contentRetention()),
                now.plus(properties.metadataRetention()),
                now);
        repository.insertEvidence(
                reportId,
                request.statement(),
                request.listingMediaIds(),
                media,
                canonicalizer,
                ulidGenerator::next,
                now);
        repository.insertHistory(
                ulidGenerator.next(),
                reportId,
                request.reason().name(),
                properties.policyVersion(),
                now);
        repository.insertReceivedOutbox(
                ulidGenerator.next(),
                properties.eventTopic(),
                reportId,
                caseId,
                subject,
                snapshotId,
                request.reason(),
                properties.policyVersion(),
                safeCorrelationId(correlationId),
                repository.receivedEventJson(
                        reportId,
                        caseId,
                        subject,
                        snapshotId,
                        request.reason(),
                        properties.policyVersion()),
                now);
        repository.completeDuplicateWindow(
                reporterUserId,
                request.listingId(),
                request.reason().name(),
                reportId,
                now);
        repository.completeIdempotency(reporterUserId, idempotencyKey, reportId, now);

        ListingReportResponse response = ownedReport(reportId, reporterUserId);
        metrics.intake("created", request.reason().name());
        log.info(
                "Listing report received reportRef={} listingRef={} reason={} route={} correlationId={}",
                shortHash("report-log", reportId),
                shortHash("listing-log", subject.listingId()),
                request.reason().name(),
                request.reason().routingQueue(),
                safeCorrelationId(correlationId));
        return new CreateResult(response, true);
    }

    @Transactional(readOnly = true)
    // Returns only the authenticated reporter's sanitized report status and hides every other actor's record.
    ListingReportResponse get(String reportId) {
        requireEnabled();
        String normalizedReportId = normalizedUlid(reportId);
        CurrentActor actor = currentActorProvider.currentActor();
        String reporterUserId = authServiceClient.requireCurrentUserForReport(actor.accessToken()).id();
        ListingReportResponse response = ownedReport(normalizedReportId, reporterUserId);
        metrics.read("found");
        return response;
    }

    private ListingReportResponse ownedReport(String reportId, String reporterUserId) {
        return repository.findOwnedReport(reportId, reporterUserId)
                .orElseThrow(ListingReportNotFoundException::new);
    }

    private void requireNonOwner(
            String accessToken,
            String reporterUserId,
            ListingReportRepository.PublicSubject subject) {
        if ("INDIVIDUAL".equals(subject.sellerType())) {
            if (reporterUserId.equals(subject.individualSellerUserId())) {
                throw new ListingReportNotFoundException();
            }
            return;
        }
        AuthServiceClient.BusinessListingPermissionDecision decision =
                authServiceClient.checkBusinessListingPermission(accessToken, subject.businessId());
        if (decision == null) {
            throw new AuthServiceClient.DependencyUnavailableException();
        }
        if (decision == AuthServiceClient.BusinessListingPermissionDecision.EDITABLE) {
            throw new ListingReportNotFoundException();
        }
    }

    private void requireSelectedMedia(
            List<String> selectedMediaIds,
            List<ListingReportCanonicalizer.HashedMedia> media) {
        Set<String> publicMediaIds = new HashSet<>();
        for (ListingReportCanonicalizer.HashedMedia item : media) {
            publicMediaIds.add(item.media().listingImageId());
        }
        if (!publicMediaIds.containsAll(selectedMediaIds)) {
            throw new ListingReportNotFoundException();
        }
    }

    private void incrementAcceptedRate(String reporterUserId, Instant now) {
        String abuseKey = abuseHasher.hash("report-rate", reporterUserId);
        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant hourEnd = hourStart.plus(1, ChronoUnit.HOURS);
        try {
            repository.incrementRateBucket(
                    abuseKey,
                    "HOUR",
                    hourStart,
                    hourEnd,
                    hourEnd.plus(30, ChronoUnit.DAYS),
                    properties.hourlyLimit(),
                    now);
        } catch (ListingReportRateLimitException exception) {
            metrics.rateLimit("hour");
            throw exception;
        }

        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
        try {
            repository.incrementRateBucket(
                    abuseKey,
                    "DAY",
                    dayStart,
                    dayEnd,
                    dayEnd.plus(30, ChronoUnit.DAYS),
                    properties.dailyLimit(),
                    now);
        } catch (ListingReportRateLimitException exception) {
            metrics.rateLimit("day");
            throw exception;
        }
    }

    private String normalizedIdempotencyKey(String value) {
        if (value == null || value.length() < 8 || value.length() > 128) {
            throw new ListingReportInvalidRequestException();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new ListingReportInvalidRequestException();
            }
        }
        return value;
    }

    private String normalizedUlid(String value) {
        if (value == null || !value.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
            throw new ListingReportNotFoundException();
        }
        return value;
    }

    private String safeCorrelationId(String value) {
        if (value == null || value.isBlank() || value.length() > 80
                || !value.matches("[A-Za-z0-9._:-]+")) {
            return "missing";
        }
        return value;
    }

    private String shortHash(String domain, String value) {
        return abuseHasher.hash(domain, value).substring(0, 16);
    }

    record CreateResult(ListingReportResponse response, boolean created) {
    }
}
