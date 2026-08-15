package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingSearchVectorClientWiringTests {

    private static final String V2 = "marketplace-listings-v2-g20260726120000000";

    @Test
    void steadyStateVectorWorkerStartsWithBackfillCommandDisabled() {
        runner(true, true, false)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ListingSearchVectorApplyWorker.class);
                    assertThat(context.getBean(OpenSearchListingVectorBackfillClient.class)
                            .vectorWritesAvailable()).isTrue();
                    assertThat(context.getBean(OpenSearchListingVectorBackfillClient.class)
                            .enabled()).isFalse();

                    assertThatThrownBy(() -> context
                            .getBean(ListingVectorBackfillService.class)
                            .rebuildInactiveGeneration())
                            .isInstanceOf(ListingSearchUnavailableException.class)
                            .hasMessageContaining("disabled");

                    context.getBean(ListingSearchVectorApplyWorker.class).processPending();
                    verify(context.getBean(ListingSearchVectorApplyWorkRepository.class))
                            .claimBatch(anyString(), any(), any(), anyInt());
                    verify(context.getBean(OpenSearchListingVectorBackfillClient.class), never())
                            .upsertVectorTarget(anyString(), any());
                });
    }

    @Test
    void defaultOffDoesNotConstructSteadyStateVectorWorker() {
        runner(false, false, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ListingSearchVectorApplyWorker.class);
        });
    }

    @Test
    void workerFlagWithoutSyncGateFailsClosed() {
        runner(false, true, false).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingReusableVectorClientCapabilityFailsClosed() {
        runner(true, true, false, false)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void backfillCommandRemainsExplicitlyGated() {
        runner(true, true, true)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenSearchListingVectorBackfillClient.class)
                            .vectorWritesAvailable()).isTrue();
                    assertThat(context.getBean(OpenSearchListingVectorBackfillClient.class)
                            .enabled()).isTrue();
                });
    }

    private ApplicationContextRunner runner(
            boolean vectorSyncEnabled,
            boolean workerEnabled,
            boolean backfillEnabled) {
        return runner(vectorSyncEnabled, workerEnabled, backfillEnabled, true);
    }

    private ApplicationContextRunner runner(
            boolean vectorSyncEnabled,
            boolean workerEnabled,
            boolean backfillEnabled,
            boolean vectorWritesAvailable) {
        ListingVectorBackfillProperties backfill = new ListingVectorBackfillProperties(
                backfillEnabled,
                "marketplace-listings-v2",
                100,
                1000,
                5_000_000,
                true);
        ListingSearchVectorSyncProperties sync = new ListingSearchVectorSyncProperties(
                vectorSyncEnabled,
                workerEnabled,
                25,
                5000,
                60,
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                100);
        OpenSearchListingVectorBackfillClient vectorClient =
                mock(OpenSearchListingVectorBackfillClient.class);
        when(vectorClient.vectorWritesAvailable()).thenReturn(vectorWritesAvailable);
        when(vectorClient.enabled()).thenReturn(backfillEnabled && vectorWritesAvailable);
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(vectorWritesAvailable
                        ? new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2))
                        : new OpenSearchListingVectorBackfillClient.VectorTargets(List.of()));
        return new ApplicationContextRunner()
                .withPropertyValues("listing.search.vector-sync.worker-enabled="
                        + workerEnabled)
                .withUserConfiguration(VectorClientWiringConfig.class)
                .withBean(ListingVectorBackfillProperties.class, () -> backfill)
                .withBean(ListingSearchVectorSyncProperties.class, () -> sync)
                .withBean(
                        ListingVectorBackfillSnapshotReader.class,
                        () -> mock(ListingVectorBackfillSnapshotReader.class))
                .withBean(OpenSearchListingVectorBackfillClient.class, () -> vectorClient)
                .withBean(
                        ListingSearchVectorDocumentResolver.class,
                        () -> mock(ListingSearchVectorDocumentResolver.class))
                .withBean(
                        ListingSearchPromotionRepository.class,
                        () -> {
                            ListingSearchPromotionRepository repository =
                                    mock(ListingSearchPromotionRepository.class);
                            when(repository.findDualWriteRun()).thenReturn(Optional.empty());
                            return repository;
                        })
                .withBean(
                        ListingSearchVectorApplyWorkRepository.class,
                        () -> {
                            ListingSearchVectorApplyWorkRepository repository =
                                    mock(ListingSearchVectorApplyWorkRepository.class);
                            when(repository.claimBatch(anyString(), any(), any(), anyInt()))
                                    .thenReturn(List.of());
                            return repository;
                        })
                .withBean(
                        ListingSearchPromotionProperties.class,
                        () -> new ListingSearchPromotionProperties(false, false, 5, 1000))
                .withBean(
                        ListingSearchVectorSyncMetrics.class,
                        () -> mock(ListingSearchVectorSyncMetrics.class))
                .withBean(UlidGenerator.class, () -> {
                    UlidGenerator generator = mock(UlidGenerator.class);
                    when(generator.next()).thenReturn("01C00000000000000000000801");
                    return generator;
                })
                .withBean(PlatformTransactionManager.class, () ->
                        mock(PlatformTransactionManager.class));
    }

    @Configuration
    @Import({
            ListingVectorBackfillService.class,
            ListingSearchVectorApplyWorker.class
    })
    static class VectorClientWiringConfig {
    }
}
