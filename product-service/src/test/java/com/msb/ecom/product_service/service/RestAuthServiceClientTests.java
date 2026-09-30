package com.msb.ecom.product_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RestAuthServiceClientTests {

    @Test
    void publicStoreVisibilityIdsAreSplitAtTheAuthContractLimit() {
        Set<String> ids = IntStream.range(0, 120)
                .mapToObj(index -> String.format("%026d", index))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        var batches = RestAuthServiceClient.boundedIdBatches(ids);

        assertThat(batches).hasSize(3);
        assertThat(batches).extracting(Set::size).containsExactly(50, 50, 20);
        assertThat(batches.stream().flatMap(Set::stream).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void emptyVisibilityFilterStillProducesOneUnfilteredRequest() {
        assertThat(RestAuthServiceClient.boundedIdBatches(Set.of()))
                .containsExactly(Set.of());
    }

    @Test
    void publicStoreVisibilityUriUsesRepeatedScalarParameters() {
        String firstBusinessId = "01KABCDEF0123456789ABCDEFA";
        String secondBusinessId = "01KABCDEF0123456789ABCDEFB";

        var uri = RestAuthServiceClient.publicBusinessStoreSearchUri(
                UriComponentsBuilder.fromUriString("http://auth-service:8080"),
                "camera lens",
                new LinkedHashSet<>(Set.of(secondBusinessId, firstBusinessId)),
                Set.of());
        var queryParameters = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        assertThat(queryParameters.get("businessIds"))
                .containsExactly(firstBusinessId, secondBusinessId);
        assertThat(queryParameters).doesNotContainKey("storeIds");
        assertThat(queryParameters.getFirst("q")).isEqualTo("camera%20lens");
    }
}
