package com.msb.ecom.product_service.reports;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingReportActorFailureTests {

    @Mock ListingReportRepository repository;
    @Mock ListingReportCanonicalizer canonicalizer;
    @Mock ListingReportAbuseHasher abuseHasher;
    @Mock ListingReportMetrics metrics;
    @Mock ListingReportTimeSource timeSource;
    @Mock AuthServiceClient authServiceClient;
    @Mock CurrentActorProvider currentActorProvider;
    @Mock UlidGenerator ulidGenerator;

    @ParameterizedTest
    @MethodSource("actorFailures")
    void actorAuthFailuresStopBeforeAnyPersistence(RuntimeException failure) {
        ListingReportService service = service();
        ListingReportCreateRequest request = new ListingReportCreateRequest();
        request.setListingId("01ARZ3NDEKTSV4RRFFQ69G5FAC");
        request.setReasonCode("DUPLICATE_OR_SPAM");
        request.setListingMediaIds(List.of());
        when(canonicalizer.normalize(request)).thenReturn(new ListingReportCanonicalizer.NormalizedRequest(
                "01ARZ3NDEKTSV4RRFFQ69G5FAC",
                ListingReportReason.DUPLICATE_OR_SPAM,
                null,
                List.of(),
                "a".repeat(64)));
        when(timeSource.now()).thenReturn(Instant.parse("2026-07-20T10:00:00Z"));
        when(currentActorProvider.currentActor()).thenReturn(new CurrentActor(
                "01ARZ3NDEKTSV4RRFFQ69G5FAD",
                "opaque-token",
                null,
                null,
                true));
        when(authServiceClient.requireCurrentUserForReport(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(request, "report-key-0001", "corr-report"))
                .isSameAs(failure);

        verifyNoInteractions(repository, abuseHasher, ulidGenerator);
    }

    private ListingReportService service() {
        return new ListingReportService(
                new ListingReportProperties(
                        true,
                        true,
                        false,
                        "x".repeat(40),
                        "REP-00-V1",
                        "listing-report-v1",
                        Duration.ofDays(90),
                        Duration.ofHours(24),
                        Duration.ofDays(180),
                        Duration.ofDays(730),
                        5,
                        20),
                repository,
                canonicalizer,
                abuseHasher,
                metrics,
                timeSource,
                authServiceClient,
                currentActorProvider,
                ulidGenerator);
    }

    private static Stream<RuntimeException> actorFailures() {
        return Stream.of(
                new AuthServiceClient.AuthenticationException(),
                new ListingAuthorizationException("Authenticated report access is required."),
                new AuthServiceClient.DependencyUnavailableException());
    }
}
