package com.msb.ecom.product_service.search.hybrid;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ListingHybridSearchMetrics {

    static final String METRIC_NAME =
            "product.listing.search.hybrid.operations";

    private final MeterRegistry meterRegistry;

    public ListingHybridSearchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // Records only fixed operation/result labels; query, vector, filters and IDs never become tags.
    public void record(String operation, String result) {
        meterRegistry.counter(
                METRIC_NAME,
                "operation", operation,
                "result", result).increment();
    }

    public void recordCount(String kind, int count) {
        record("count_" + kind, bucket(count));
    }

    // Records a fixed latency bucket without introducing query- or request-specific labels.
    public void recordLatency(long elapsedNanos) {
        long elapsedMillis = Math.max(0L, elapsedNanos / 1_000_000L);
        String bucket = elapsedMillis < 50L
                ? "under_50_ms"
                : elapsedMillis < 200L
                ? "50_to_199_ms"
                : elapsedMillis < 1_000L
                ? "200_to_999_ms"
                : elapsedMillis < 3_000L
                ? "one_to_three_s"
                : "over_three_s";
        record("latency", bucket);
    }

    private String bucket(int count) {
        if (count == 0) {
            return "zero";
        }
        if (count <= 5) {
            return "one_to_five";
        }
        if (count <= 20) {
            return "six_to_twenty";
        }
        if (count <= 80) {
            return "twenty_one_to_eighty";
        }
        return "over_eighty";
    }
}
