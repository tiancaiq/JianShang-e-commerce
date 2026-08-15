package com.msb.ecom.product_service.search.hybrid;

import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ListingHybridSearchServiceTests {

    private static final byte[] BODY = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final String GENERATION =
            "marketplace-listings-v2-g20260723101112123";

    private final ListingHybridSearchRequestParser parser =
            mock(ListingHybridSearchRequestParser.class);
    private final OpenSearchListingHybridSearchClient client =
            mock(OpenSearchListingHybridSearchClient.class);
    private final ListingHybridSearchRepository repository =
            mock(ListingHybridSearchRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListingHybridSearchMetrics metrics =
            new ListingHybridSearchMetrics(registry);

    @Test
    void disabledAndAuthenticationGatesPrecedeParsingAndAllDependencies() {
        ListingHybridSearchService disabled = service(false, false);

        assertThatThrownBy(() -> disabled.search("agent-token", BODY))
                .isInstanceOf(ListingHybridSearchFeatureDisabledException.class);
        verifyNoInteractions(parser, client, repository);

        ListingHybridSearchService enabled = service(true, false);
        assertThatThrownBy(() -> enabled.search("wrong-token", BODY))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(parser, client, repository);
    }

    @Test
    void fusesBranchesAndReturnsOnlyCurrentMysqlFactsThatStillMatchFilters() {
        ListingHybridSearchRequest request = request();
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request)).thenReturn(List.of(id(1), id(2), id(4)));
        when(client.searchVector(GENERATION, request)).thenReturn(List.of(id(2), id(3), id(4)));
        when(repository.findCurrentEligibleByIds(List.of(id(2), id(4), id(1), id(3))))
                .thenReturn(List.of(
                        listing(id(2), "Current authoritative title", true, "Irvine", "Orange County"),
                        listing(id(4), "Wrong current city", true, "Anaheim", "Orange County"),
                        listing(id(1), "Lexical current title", true, "Irvine", "Orange County")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.schemaVersion())
                .isEqualTo(ListingHybridSearchResponse.SCHEMA_VERSION);
        assertThat(response.meta().retrievalMode()).isEqualTo("HYBRID");
        assertThat(response.meta().degraded()).isFalse();
        assertThat(response.meta().checkedAt()).isEqualTo(NOW);
        assertThat(response.data()).extracting(ListingHybridSearchResponse.Result::listingId)
                .containsExactly(id(2), id(1));
        assertThat(response.data().getFirst().title()).isEqualTo("Current authoritative title");
        assertThat(response.data().getFirst().provenance().matchedBy())
                .containsExactly("LEXICAL", "VECTOR");
        assertThat(response.data().getFirst().provenance().finalRank()).isEqualTo(1);
        assertThat(response.data().get(1).provenance().finalRank()).isEqualTo(2);
        assertThat(response.data().getFirst().transactionNotice())
                .contains("does not verify or protect");
        assertThat(response.discovery()).isNull();
    }

    @Test
    void facetResponseUsesOnlyRelevantRevalidatedChairInventory() {
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "chair", new float[1536],
                new ListingHybridSearchRequest.Filters(
                        null, null, null, null, null, null, null),
                5, ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3), id(4), id(5)));
        when(client.searchVector(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3), id(4), id(5)));
        when(repository.findCurrentEligibleByIds(List.of(id(1), id(2), id(3), id(4), id(5))))
                .thenReturn(List.of(
                        listing(id(1), "Oak dining chair", true, "Irvine", "Orange County"),
                        listing(id(2), "RGB gaming chair", true, "Anaheim", "Orange County"),
                        listing(id(3), "Portable folding chair", true, "Irvine", "Orange County"),
                        listing(id(4), "Replacement chair wheels", true, "Irvine", "Orange County"),
                        listing(id(5), "Office seating ergonomic", true, "Tustin", "Orange County")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.schemaVersion())
                .isEqualTo(ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);
        assertThat(response.discovery().totalMatches()).isEqualTo(5);
        assertThat(response.discovery().relevantMatchCount()).isEqualTo(4);
        assertThat(response.discovery().retrievalConfidence()).isEqualTo("HIGH");
        assertThat(response.data()).extracting(ListingHybridSearchResponse.Result::listingId)
                .doesNotContain(id(4));
        assertThat(response.discovery().facets().subtype())
                .extracting(ListingHybridSearchResponse.FacetValue::value)
                .containsExactly("Dining Chair", "Folding Chair", "Gaming Chair", "Office Chair")
                .doesNotContain("Chair Accessories");
    }

    @Test
    void exactChairSubtypeDoesNotCountOtherChairInventoryAsAnExactMatch() {
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "office chair", new float[1536],
                new ListingHybridSearchRequest.Filters(
                        null, null, null, null, null, null, null),
                5, ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3), id(4)));
        when(client.searchVector(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3), id(4)));
        when(repository.findCurrentEligibleByIds(List.of(id(1), id(2), id(3), id(4))))
                .thenReturn(List.of(
                        listing(id(1), "Oak dining chair", true, "Irvine", "Orange County"),
                        listing(id(2), "RGB gaming chair", true, "Anaheim", "Orange County"),
                        listing(id(3), "Portable folding chair", true, "Irvine", "Orange County"),
                        listing(id(4), "Replacement chair wheels", true, "Irvine", "Orange County")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.discovery().totalMatches()).isEqualTo(4);
        assertThat(response.discovery().relevantMatchCount()).isZero();
        assertThat(response.discovery().retrievalConfidence()).isEqualTo("LOW");
        assertThat(response.discovery().reason()).isEqualTo("LOW_RELEVANCE");
        assertThat(response.data()).isEmpty();
        assertThat(response.discovery().facets().subtype()).isEmpty();
    }

    @Test
    void exactMultiConceptMatchesRankAheadOfPartialMatches() {
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "key organizer", new float[1536],
                new ListingHybridSearchRequest.Filters(
                        null, null, null, null, null, null, null),
                5, ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION);
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3)));
        when(client.searchVector(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3)));
        when(repository.findCurrentEligibleByIds(List.of(id(1), id(2), id(3))))
                .thenReturn(List.of(
                        listing(id(1), "Drawer organizer", true, "Irvine", "Orange County"),
                        listing(id(2), "Wall key organizer", true, "Irvine", "Orange County"),
                        listing(id(3), "Desk organizer", true, "Irvine", "Orange County")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.data()).extracting(ListingHybridSearchResponse.Result::listingId)
                .containsExactly(id(2), id(1), id(3));
        assertThat(response.discovery().exactMatchCount()).isEqualTo(1);
        assertThat(response.discovery().relatedMatchCount()).isEqualTo(2);
    }

    @Test
    void v4RejectsMissingCoreConceptsAndRanksStrongThenRelatedWithoutFillingLimit() {
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "key bag", new float[1536],
                new ListingHybridSearchRequest.Filters(
                        null, null, null, null, null, null, null),
                5, ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request))
                .thenReturn(List.of(id(1), id(2), id(3), id(4)));
        when(client.searchVector(GENERATION, request))
                .thenReturn(List.of(id(3), id(2), id(4), id(1)));
        when(repository.findCurrentEligibleByIds(
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(
                        listing(id(1), "Leather key pouch", "Compact case for house keys"),
                        listing(id(3), "Canvas tote bag", "General carry bag"),
                        listing(id(2), "Pocket organizer", "Small holder for keys"),
                        listing(id(4), "Desk organizer", "Drawer and stationery storage")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.schemaVersion())
                .isEqualTo(ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);
        assertThat(response.data()).extracting(ListingHybridSearchResponse.Result::listingId)
                .containsExactly(id(1), id(2));
        assertThat(response.data()).extracting(result -> result.conceptMatch().relevance())
                .containsExactly("HIGH", "MEDIUM");
        assertThat(response.discovery().exactMatchCount()).isEqualTo(1);
        assertThat(response.discovery().relatedMatchCount()).isEqualTo(1);
        assertThat(response.discovery().rejectedCandidateCount()).isEqualTo(2);
        assertThat(response.discovery().rerankContext().coreConcepts())
                .containsExactly("key storage", "pouch or case");
        assertThat(response.data()).allSatisfy(result -> {
            assertThat(result.conceptMatch().completeConceptMatch()).isTrue();
            assertThat(result.conceptMatch().matchedConcepts()).hasSize(2);
        });
    }

    @Test
    void v4DoesNotReturnOrganizerThatMissesKeyConcept() {
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "key organizer", new float[1536],
                new ListingHybridSearchRequest.Filters(
                        null, null, null, null, null, null, null),
                5, ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request)).thenReturn(List.of(id(1), id(2)));
        when(client.searchVector(GENERATION, request)).thenReturn(List.of(id(2), id(1)));
        when(repository.findCurrentEligibleByIds(List.of(id(1), id(2))))
                .thenReturn(List.of(
                        listing(id(1), "Wall key organizer", "Hooks for keys"),
                        listing(id(2), "Desk organizer", "Pen and drawer storage")));

        ListingHybridSearchResponse response = service(true, false)
                .search("agent-token", BODY);

        assertThat(response.data()).extracting(ListingHybridSearchResponse.Result::listingId)
                .containsExactly(id(1));
        assertThat(response.discovery().rejectedCandidateCount()).isEqualTo(1);
    }

    @Test
    void singleBranchFailureIs503UnlessExplicitFallbackIsEnabled() {
        ListingHybridSearchRequest request = request();
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request)).thenReturn(List.of(id(1)));
        doThrow(new ListingHybridSearchUnavailableException())
                .when(client).searchVector(GENERATION, request);

        assertThatThrownBy(() -> service(true, false).search("agent-token", BODY))
                .isInstanceOf(ListingHybridSearchUnavailableException.class);
        verify(repository, never()).findCurrentEligibleByIds(org.mockito.ArgumentMatchers.anyList());

        when(repository.findCurrentEligibleByIds(List.of(id(1))))
                .thenReturn(List.of(listing(
                        id(1), "Lexical current title", true, "Irvine", "Orange County")));
        ListingHybridSearchResponse response =
                service(true, true).search("agent-token", BODY);

        assertThat(response.meta().retrievalMode()).isEqualTo("LEXICAL_ONLY");
        assertThat(response.meta().degraded()).isTrue();
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().provenance().mode()).isEqualTo("LEXICAL_ONLY");
    }

    @Test
    void recordsSafeFailureStageWhenMysqlRevalidationFails(CapturedOutput output) {
        ListingHybridSearchRequest request = secretBearingRequest();
        when(parser.parse(BODY)).thenReturn(parsed(request));
        when(client.validateReadAlias()).thenReturn(GENERATION);
        when(client.searchLexical(GENERATION, request)).thenReturn(List.of());
        when(client.searchVector(GENERATION, request)).thenReturn(List.of(id(1)));
        when(repository.findCurrentEligibleByIds(List.of(id(1))))
                .thenThrow(new DataAccessResourceFailureException(
                        "sql failed for SELECT token=sk-test query='secret chair' vector=[9.9]",
                        new IllegalStateException("nested listing id " + id(1))));

        assertThatThrownBy(() -> service(true, false).search("agent-token", BODY))
                .isInstanceOf(ListingHybridSearchUnavailableException.class);

        assertThat(registry.find(ListingHybridSearchMetrics.METRIC_NAME)
                .tags("operation", "failure_stage", "result", "mysql_revalidation")
                .counter())
                .isNotNull();
        assertThat(output)
                .contains("eventCode=PRODUCT_HYBRID_FAILURE_STAGE")
                .contains("stage=mysql_revalidation")
                .contains("kind=internal_unavailable")
                .doesNotContain("sql failed")
                .doesNotContain("SELECT")
                .doesNotContain("sk-test")
                .doesNotContain("secret chair")
                .doesNotContain("vector")
                .doesNotContain("nested")
                .doesNotContain(id(1))
                .doesNotContain("agent-token");
    }

    @Test
    void recordsOnlySafeOpenSearchFailureKindForBranchFailures(CapturedOutput output) {
        ListingHybridSearchRequest request = secretBearingRequest();
        for (ListingHybridSearchFailureKind kind : ListingHybridSearchFailureKind.values()) {
            reset(parser, client, repository);
            when(parser.parse(BODY)).thenReturn(parsed(request));
            when(client.validateReadAlias()).thenReturn(GENERATION);
            when(client.searchLexical(GENERATION, request)).thenReturn(List.of(id(1)));
            doThrow(new ListingHybridSearchUnavailableException(kind))
                    .when(client).searchVector(GENERATION, request);

            assertThatThrownBy(() -> service(true, false).search("agent-token", BODY))
                    .isInstanceOf(ListingHybridSearchUnavailableException.class);
        }

        assertThat(output)
                .contains("eventCode=PRODUCT_HYBRID_FAILURE_STAGE")
                .contains("stage=vector_branch");
        for (ListingHybridSearchFailureKind kind : ListingHybridSearchFailureKind.values()) {
            assertThat(output).contains("kind=" + kind.logValue());
        }
        assertThat(output)
                .doesNotContain("secret chair")
                .doesNotContain("sk-test")
                .doesNotContain("vector=[")
                .doesNotContain("SELECT")
                .doesNotContain(id(1))
                .doesNotContain("agent-token")
                .doesNotContain("Exception")
                .doesNotContain("RuntimeException");
    }

    @Test
    void identityMismatchStopsBeforeAliasBranchesAndMysql() {
        ListingHybridSearchRequest request = request();
        when(parser.parse(BODY)).thenReturn(new ListingHybridSearchRequestParser.ParsedRequest(
                request, "other", "model", 1536));

        assertThatThrownBy(() -> service(true, false).search("agent-token", BODY))
                .isInstanceOf(ListingHybridSearchIdentityMismatchException.class);

        verifyNoInteractions(client, repository);
    }

    private ListingHybridSearchService service(boolean enabled, boolean fallback) {
        return new ListingHybridSearchService(
                new ListingHybridSearchProperties(enabled, fallback),
                new ListingSearchProperties(
                        "opensearch",
                        new ListingSearchProperties.OpenSearchProperties(
                                "http://localhost:9201",
                                "marketplace-listings",
                                "marketplace-listings-write",
                                "marketplace-listings-v1",
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(3),
                                false)),
                parser,
                client,
                repository,
                metrics,
                "agent-token",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingHybridSearchRequest request() {
        return new ListingHybridSearchRequest(
                "desk chair",
                new float[1536],
                new ListingHybridSearchRequest.Filters(
                        "01D00000000000000000000999",
                        "GOOD",
                        BigDecimal.TEN,
                        new BigDecimal("100.00"),
                        "USD",
                        "Irvine",
                        "Orange County"),
                10);
    }

    private ListingHybridSearchRequest secretBearingRequest() {
        return new ListingHybridSearchRequest(
                "secret chair",
                new float[1536],
                new ListingHybridSearchRequest.Filters(
                        "01D00000000000000000000999",
                        "GOOD",
                        BigDecimal.TEN,
                        new BigDecimal("100.00"),
                        "USD",
                        "Irvine",
                        "Orange County"),
                10);
    }

    private ListingHybridSearchRequestParser.ParsedRequest parsed(
            ListingHybridSearchRequest request) {
        return new ListingHybridSearchRequestParser.ParsedRequest(
                request,
                "openai",
                "text-embedding-3-small",
                1536);
    }

    private ListingHybridSearchListing listing(
            String id,
            String title,
            boolean available,
            String city,
            String region) {
        return new ListingHybridSearchListing(
                id,
                7,
                "01D00000000000000000000999",
                "office",
                "Office",
                title,
                "GOOD",
                new BigDecimal("55.00"),
                "USD",
                city,
                region,
                available,
                "/api/v1/public/listing-media/01D00000000000000000000888",
                NOW.minusSeconds(3600));
    }

    private ListingHybridSearchListing listing(String id, String title, String description) {
        ListingHybridSearchListing base = listing(
                id, title, true, "Irvine", "Orange County");
        return new ListingHybridSearchListing(
                base.listingId(), base.listingVersion(), base.categoryId(),
                base.categorySlug(), base.categoryName(), base.title(), base.condition(),
                base.priceAmount(), base.currency(), base.publicCity(), base.publicRegion(),
                base.available(), base.primaryImageUrl(), base.publishedAt(), description);
    }

    private String id(int suffix) {
        return String.format("01D00000000000000000000%03d", suffix);
    }
}
