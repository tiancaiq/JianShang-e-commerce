package com.msb.ecom.auth_service.system;

import java.util.List;
import java.util.Objects;

/** Derives the shared read-only ADM-SYS signal counts used by system and analytics views. */
public final class SystemOperationsSummaryAssembler {
    private SystemOperationsSummaryAssembler() { }

    public static Signals assemble(List<SystemContracts.ServiceHealth> rawHealth,
            List<SystemContracts.SourceSnapshot> rawSources) {
        List<SystemContracts.ServiceHealth> health = safe(rawHealth);
        List<SystemContracts.SourceSnapshot> sources = safe(rawSources);
        List<SystemContracts.JobSummary> jobs = sources.stream()
                .flatMap(source -> safe(source.jobs()).stream()).toList();
        List<SystemContracts.OutboxSummary> outbox = sources.stream()
                .flatMap(source -> safe(source.outbox()).stream()).toList();

        long healthy = health.stream().filter(value -> "HEALTHY".equals(value.status())).count();
        long unavailable = health.stream()
                .filter(value -> "UNAVAILABLE".equals(value.status())).count();
        long degraded = health.size() - healthy - unavailable;
        long failedJobs = jobs.stream().filter(value ->
                List.of("FAILED", "DEAD_LETTER", "TERMINAL").contains(value.status())).count();
        long retryableJobs = jobs.stream().filter(SystemContracts.JobSummary::retryable).count();
        long failedOutbox = outbox.stream().filter(value ->
                List.of("FAILED", "DEAD_LETTER").contains(value.status())).count();
        long deadLetterEvents = outbox.stream()
                .filter(value -> "DEAD_LETTER".equals(value.status())).count();
        long reconciliationIssues = sources.stream()
                .flatMap(source -> safe(source.reconciliation()).stream()).count();
        long inventoryIssues = sources.stream()
                .flatMap(source -> safe(source.inventory()).stream()).count();
        long searchIndexFailures = sources.stream().map(SystemContracts.SourceSnapshot::search)
                .filter(Objects::nonNull)
                .mapToLong(SystemContracts.SearchStatus::failedOperations).sum();
        long featureWarnings = sources.stream().flatMap(source -> safe(source.features()).stream())
                .filter(value -> !"ENABLED".equals(value.status())).count();
        List<String> sourceWarnings = sources.stream().filter(source -> !source.available())
                .map(SystemContracts.SourceSnapshot::safeSummary).filter(Objects::nonNull).toList();
        return new Signals(healthy, degraded, unavailable, failedJobs, retryableJobs,
                failedOutbox, deadLetterEvents, reconciliationIssues, inventoryIssues,
                searchIndexFailures, featureWarnings, sourceWarnings);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record Signals(long healthyServices, long degradedServices, long unavailableServices,
            long failedJobs, long retryableJobs, long failedOutboxEvents, long deadLetterEvents,
            long reconciliationIssues, long inventoryIssues, long searchIndexFailures,
            long featureWarnings, List<String> sourceWarnings) {
        public Signals {
            sourceWarnings = List.copyOf(sourceWarnings);
        }

        public boolean hasUnavailableSource() {
            return !sourceWarnings.isEmpty();
        }
    }
}
