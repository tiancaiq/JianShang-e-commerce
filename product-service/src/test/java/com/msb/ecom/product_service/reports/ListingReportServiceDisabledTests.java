package com.msb.ecom.product_service.reports;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ListingReportServiceDisabledTests {

    @Mock ListingReportRepository repository;
    @Mock ListingReportCanonicalizer canonicalizer;
    @Mock ListingReportAbuseHasher abuseHasher;
    @Mock ListingReportMetrics metrics;
    @Mock ListingReportTimeSource timeSource;
    @Mock AuthServiceClient authServiceClient;
    @Mock CurrentActorProvider currentActorProvider;
    @Mock UlidGenerator ulidGenerator;

    @Test
    void defaultOffStopsBeforeAuthProductPersistenceOrAgentWork() {
        ListingReportService service = new ListingReportService(
                new ListingReportProperties(
                        false,
                        false,
                        false,
                        "disabled-only-listing-report-hmac-key",
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

        assertThatThrownBy(service::requireEnabled)
                .isInstanceOf(ListingReportFeatureDisabledException.class);

        verifyNoInteractions(
                repository,
                canonicalizer,
                abuseHasher,
                timeSource,
                authServiceClient,
                currentActorProvider,
                ulidGenerator);
    }
}
