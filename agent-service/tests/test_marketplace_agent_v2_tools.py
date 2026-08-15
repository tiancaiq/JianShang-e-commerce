from __future__ import annotations

import unittest
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from msb_agent_service.marketplace_discovery import (
    DiscoveryAvailabilityProbe,
    CheckedListing,
    DiscoveryFacetValue,
    DiscoverySearchCandidate,
    DiscoverySearchFacets,
    DiscoverySearchPage,
    DiscoverySearchSummary,
    PublicIndividualListing,
)
from msb_agent_service.marketplace_agent_v2.schemas import (
    CheckAvailabilityArguments,
    RequestConfirmationArguments,
    SearchListingsArguments,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry
from msb_agent_service.marketplace_listing_retrieval import MarketplaceRetrievalError


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
IMAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB4"
LISTINGS = (
    LISTING,
    "01ARZ3NDEKTSV4RRFFQ69G5FB2",
    "01ARZ3NDEKTSV4RRFFQ69G5FB3",
)
NOW = datetime(2026, 7, 30, 12, 0, tzinfo=UTC)


class _Product:
    def __init__(self, *, inventory: int = 0, fail: bool = False) -> None:
        self.inventory = inventory
        self.fail = fail
        self.searches = 0
        self.probes = 0

    async def search_individual(self, **_: Any) -> DiscoverySearchPage:
        self.searches += 1
        if self.fail:
            raise RuntimeError("private Product failure")
        return DiscoverySearchPage(data=(), page={"hasMore": False})

    async def probe_availability(self, **kwargs: Any) -> DiscoveryAvailabilityProbe:
        self.probes += 1
        return DiscoveryAvailabilityProbe(
            schemaVersion="MARKETPLACE_AVAILABILITY_PROBE_V1",
            mode="AVAILABILITY_PROBE",
            searchExecuted=True,
            category=kwargs["category"],
            totalActiveCategoryInventory=self.inventory,
            relatedCategoryMatches=0,
            retryable=False,
        )

    async def get_listing(self, **_: Any) -> CheckedListing:
        return CheckedListing(
            listing=PublicIndividualListing.model_validate({
                "id": LISTING, "sellerType": "INDIVIDUAL",
                "sellerDisplayName": "Seller", "sellerAvatarUrl": None,
                "storeId": None, "storeSlug": None, "storeName": None,
                "businessVerified": False,
                "categoryId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
                "categorySlug": "chairs", "categoryName": "Dining Chair",
                "title": "Oak dining chair", "description": "Public chair listing",
                "condition": "GOOD", "conditionNotes": None,
                "priceAmount": Decimal("75.00"), "currency": "USD",
                "negotiable": False, "quantity": 1,
                "publicCity": "Irvine", "publicRegion": "Orange County",
                "publishedAt": NOW, "transactionNotice": "Arrange directly.",
                "visitCount": 0, "likeCount": 0, "images": [{
                    "id": IMAGE, "displayOrder": 0, "altText": "Oak dining chair",
                    "originalFileName": "chair.jpg", "contentType": "image/jpeg",
                    "sizeBytes": 1024,
                    "uploadUrl": f"/api/v1/public/listing-media/{IMAGE}",
                    "url": f"/api/v1/public/listing-media/{IMAGE}",
                }],
            }),
            checkedAt=NOW,
            responseHash="a" * 64,
        )


class _FacetedProduct(_Product):
    def __init__(
        self,
        *,
        total: int,
        subtypes: tuple[tuple[str, int], ...],
        normalized_category: str = "chair",
    ) -> None:
        super().__init__(inventory=total)
        self.total = total
        self.subtypes = subtypes
        self.normalized_category = normalized_category

    async def search_individual(self, **_: Any) -> DiscoverySearchPage:
        self.searches += 1
        data = () if self.total == 0 else (DiscoverySearchCandidate(listingId=LISTING),)
        return DiscoverySearchPage(
            data=data,
            page={"hasMore": False},
            summary=DiscoverySearchSummary(
                normalizedCategory=self.normalized_category,
                totalMatches=self.total,
                relevantMatchCount=self.total,
                retrievalConfidence="HIGH" if self.total else "LOW",
                reason="CATEGORY_UNAVAILABLE" if self.total == 0 else "RESULTS_AVAILABLE",
                facets=DiscoverySearchFacets(
                    subtype=tuple(
                        DiscoveryFacetValue(value=value, count=count)
                        for value, count in self.subtypes
                    ),
                ),
            ),
        )


class _ClassifiedFailureProduct(_Product):
    async def search_individual(self, **_: Any) -> DiscoverySearchPage:
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_PRODUCT_TIMEOUT", retryable=True
        )


class _ThreeResultProduct(_Product):
    async def search_individual(self, **_: Any) -> DiscoverySearchPage:
        self.searches += 1
        return DiscoverySearchPage(
            data=tuple(
                DiscoverySearchCandidate(
                    listingId=item,
                    matchQuality="EXACT" if index == 0 else "RELATED",
                )
                for index, item in enumerate(LISTINGS)
            ),
            page={"hasMore": False},
            summary=DiscoverySearchSummary(
                normalizedCategory="chair",
                totalMatches=3,
                relevantMatchCount=3,
                exactMatchCount=1,
                relatedMatchCount=2,
                retrievalConfidence="HIGH",
                reason="RESULTS_AVAILABLE",
                facets=DiscoverySearchFacets(
                    subtype=(DiscoveryFacetValue(value="Chair", count=3),)
                ),
            ),
        )

    async def get_listing(self, **kwargs: Any) -> CheckedListing:
        checked = await super().get_listing(**kwargs)
        return CheckedListing(
            listing=checked.listing.model_copy(update={
                "id": kwargs["listing_id"],
                "title": f"Current chair {LISTINGS.index(kwargs['listing_id']) + 1}",
            }),
            checkedAt=checked.checked_at,
            responseHash=checked.response_hash,
        )


class _MixedCategoryProduct(_Product):
    def __init__(self) -> None:
        super().__init__(inventory=2)
        self.queries: list[str] = []

    async def search_individual(self, **kwargs: Any) -> DiscoverySearchPage:
        self.searches += 1
        self.queries.append(kwargs["request"].q)
        return DiscoverySearchPage(
            data=(
                DiscoverySearchCandidate(listingId=LISTINGS[0], matchQuality="EXACT"),
                DiscoverySearchCandidate(listingId=LISTINGS[1], matchQuality="EXACT"),
            ),
            page={"hasMore": False},
            summary=DiscoverySearchSummary(
                normalizedCategory="key organizer",
                totalMatches=2,
                relevantMatchCount=2,
                exactMatchCount=2,
                relatedMatchCount=0,
                retrievalConfidence="HIGH",
                reason="RESULTS_AVAILABLE",
                facets=DiscoverySearchFacets(),
            ),
        )

    async def get_listing(self, **kwargs: Any) -> CheckedListing:
        checked = await super().get_listing(**kwargs)
        is_general = kwargs["listing_id"] == LISTINGS[0]
        return CheckedListing(
            listing=checked.listing.model_copy(update={
                "id": kwargs["listing_id"],
                "category_name": "General" if is_general else "Electronics",
                "title": "Key organizer" if is_general else "Electronic organizer",
            }),
            checkedAt=checked.checked_at,
            responseHash=("b" if is_general else "c") * 64,
        )


class MarketplaceAgentV2ToolRegistryTest(unittest.IsolatedAsyncioTestCase):
    async def test_category_refinement_keeps_product_query_and_filters_revalidated_facts(self) -> None:
        product = _MixedCategoryProduct()

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(
                query="key organizer", categoryName="General"
            ),
            actor_user_id=ACTOR,
            correlation_id="v2-category-name-filter",
            activity=None,
        )

        self.assertEqual(["key organizer"], product.queries)
        self.assertEqual("RESULTS_AVAILABLE", result.reason)
        self.assertEqual(1, result.result_count)
        self.assertEqual(1, result.relevant_match_count)
        self.assertEqual("General", result.attachments[0].category_name)
        self.assertEqual(("CATEGORY",), result.filter_categories)

    async def test_category_refinement_empty_slice_is_filters_too_strict(self) -> None:
        product = _MixedCategoryProduct()

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(
                query="key organizer", categoryName="Furniture"
            ),
            actor_user_id=ACTOR,
            correlation_id="v2-category-name-empty-filter",
            activity=None,
        )

        self.assertEqual(["key organizer"], product.queries)
        self.assertEqual("FILTERS_TOO_STRICT", result.reason)
        self.assertEqual("NO_RESULTS", result.presentation_hint)
        self.assertEqual((), result.attachments)
        self.assertEqual(0, product.probes)

    async def test_broad_search_exposes_only_product_owned_diverse_facets(self) -> None:
        product = _FacetedProduct(
            total=25,
            subtypes=(("Dining Chair", 12), ("Gaming Chair", 8), ("Folding Chair", 5)),
        )

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-facets",
            activity=None,
        )

        self.assertEqual(1, product.searches)
        self.assertEqual(25, result.total_matches)
        self.assertEqual("RESULTS_WITH_REFINEMENT", result.presentation_hint)
        self.assertEqual(
            ("Dining Chair", "Gaming Chair", "Folding Chair"),
            tuple(item.value for item in result.facets.subtype),
        )
        self.assertNotIn("Office Chair", tuple(item.value for item in result.facets.subtype))
        self.assertEqual(1, len(result.attachments))
        self.assertEqual(
            f"/api/v1/public/listing-media/{IMAGE}",
            result.attachments[0].thumbnail_url,
        )
        self.assertEqual("HIGH", result.retrieval_confidence)
        self.assertEqual(25, result.relevant_match_count)

    async def test_search_preserves_product_exact_first_group_counts(self) -> None:
        result = await MarketplaceAgentV2ToolRegistry(_ThreeResultProduct()).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="key organizer"),
            actor_user_id=ACTOR,
            correlation_id="v2-exact-related",
            activity=None,
        )

        self.assertEqual(1, result.exact_match_count)
        self.assertEqual(2, result.related_match_count)
        self.assertEqual(3, len(result.attachments))
        self.assertEqual(
            ("EXACT", "RELATED", "RELATED"),
            tuple(item.match_quality for item in result.attachments),
        )

    async def test_small_search_presents_validated_results_without_clarification(self) -> None:
        product = _FacetedProduct(total=3, subtypes=(("Dining Chair", 3),))

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-small-results",
            activity=None,
        )

        self.assertEqual("DIRECT_RESULTS", result.presentation_hint)
        self.assertEqual(1, len(result.attachments))

    async def test_three_useful_results_are_all_revalidated_and_presented(self) -> None:
        product = _ThreeResultProduct()

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-three-results",
            activity=None,
        )

        self.assertEqual("DIRECT_RESULTS", result.presentation_hint)
        self.assertEqual(LISTINGS, tuple(item.listing_id for item in result.attachments))
        self.assertTrue(all(item.checked_at == NOW for item in result.attachments))

    async def test_laptop_without_optional_preferences_searches_and_returns_results(self) -> None:
        product = _FacetedProduct(
            total=4, subtypes=(("Laptop", 4),), normalized_category="laptop"
        )

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="laptop"),
            actor_user_id=ACTOR,
            correlation_id="v2-laptop-results-first",
            activity=None,
        )

        self.assertEqual(1, product.searches)
        self.assertEqual("DIRECT_RESULTS", result.presentation_hint)
        self.assertEqual("HIGH", result.retrieval_confidence)
        self.assertEqual(1, len(result.attachments))

    async def test_large_homogeneous_search_presents_results_without_clarification(self) -> None:
        product = _FacetedProduct(total=20, subtypes=(("Dining Chair", 18), ("Chair", 2)))

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-homogeneous-results",
            activity=None,
        )

        self.assertEqual("DIRECT_RESULTS", result.presentation_hint)
        self.assertEqual(1, len(result.attachments))

    async def test_faceted_zero_result_search_never_asks_for_inventory_probe(self) -> None:
        product = _FacetedProduct(total=0, subtypes=())

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-faceted-zero",
            activity=None,
        )

        self.assertEqual("CATEGORY_UNAVAILABLE", result.reason)
        self.assertEqual("NO_RESULTS", result.presentation_hint)
        self.assertEqual(0, product.probes)
        self.assertEqual((), result.attachments)

    async def test_unavailable_exact_subtype_does_not_generate_broad_preferences(self) -> None:
        product = _FacetedProduct(
            total=0,
            subtypes=(),
            normalized_category="office chair",
        )

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="office chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-office-zero",
            activity=None,
        )

        self.assertEqual("CATEGORY_UNAVAILABLE", result.reason)
        self.assertEqual("NO_RESULTS", result.presentation_hint)
        self.assertEqual("office chair", result.normalized_query)
        self.assertIsNotNone(result.facets)
        self.assertEqual((), result.facets.subtype)

    async def test_provider_schemas_satisfy_responses_strict_required_contract(self) -> None:
        schemas = MarketplaceAgentV2ToolRegistry(_Product()).provider_schemas()

        for tool in schemas:
            parameters = tool["parameters"]
            self.assertTrue(tool["strict"])
            self.assertFalse(parameters["additionalProperties"])
            self.assertEqual(list(parameters["properties"]), parameters["required"])
        search = next(item for item in schemas if item["name"] == "search_listings")
        self.assertEqual(5, search["parameters"]["properties"]["limit"]["default"])
        self.assertEqual(8, search["parameters"]["properties"]["limit"]["maximum"])

    async def test_confirmation_control_creates_pending_state_without_product_work(self) -> None:
        product = _Product(inventory=10)
        registry = MarketplaceAgentV2ToolRegistry(product)
        arguments = RequestConfirmationArguments(
            query="lamp", categoryId=None, condition=None, minimumPrice=None,
            maximumPrice=Decimal("25"), currency="USD", city=None, county=None,
            limit=5, type="CONFIRM_ACTION", action="RUN_REFINED_SEARCH",
        )

        result = await registry.execute(
            tool="request_confirmation", arguments=arguments,
            actor_user_id=ACTOR, correlation_id="v2-confirmation-control",
            activity=None,
        )

        self.assertEqual("INTERACTION_READY", result.reason)
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertEqual(0, product.searches)
        self.assertEqual(0, product.probes)

    async def test_small_low_confidence_search_presents_results_without_clarification(self) -> None:
        product = _FacetedProduct(total=60, subtypes=(("General", 3), ("Home & Garden", 2)))

        async def low_confidence(**_: Any) -> DiscoverySearchPage:
            product.searches += 1
            return DiscoverySearchPage(
                data=(DiscoverySearchCandidate(listingId=LISTING),),
                page={"hasMore": False},
                summary=DiscoverySearchSummary(
                    normalizedCategory="key organizer",
                    totalMatches=60,
                    relevantMatchCount=5,
                    retrievalConfidence="LOW",
                    reason="LOW_RELEVANCE",
                    facets=DiscoverySearchFacets(
                        subtype=(
                            DiscoveryFacetValue(value="General", count=3),
                            DiscoveryFacetValue(value="Home & Garden", count=2),
                        )
                    ),
                ),
            )

        product.search_individual = low_confidence  # type: ignore[method-assign]
        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="key organizer"),
            actor_user_id=ACTOR,
            correlation_id="v2-low-confidence",
            activity=None,
        )

        self.assertEqual("RESULTS_AVAILABLE", result.reason)
        self.assertEqual("DIRECT_RESULTS", result.presentation_hint)
        self.assertEqual(5, result.total_matches)
        self.assertIsNone(result.retrieval_confidence)
        self.assertIsNone(result.broad_inventory_count)
        self.assertEqual((LISTING,), tuple(item.listing_id for item in result.attachments))
        self.assertEqual(0, product.probes)

    async def test_irrelevant_candidate_count_is_not_exposed_as_category_inventory(self) -> None:
        product = _FacetedProduct(total=60, subtypes=())

        async def irrelevant_candidates(**_: Any) -> DiscoverySearchPage:
            product.searches += 1
            return DiscoverySearchPage(
                data=(),
                page={"hasMore": False},
                summary=DiscoverySearchSummary(
                    normalizedCategory="chair",
                    totalMatches=60,
                    relevantMatchCount=0,
                    retrievalConfidence="LOW",
                    reason="LOW_RELEVANCE",
                    facets=DiscoverySearchFacets(),
                ),
            )

        product.search_individual = irrelevant_candidates  # type: ignore[method-assign]
        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-irrelevant-candidates",
            activity=None,
        )

        self.assertEqual("LOW_RELEVANCE", result.reason)
        self.assertEqual("NO_RESULTS", result.presentation_hint)
        self.assertEqual(0, result.total_matches)
        self.assertEqual(0, result.relevant_match_count)
        self.assertIsNone(result.broad_inventory_count)
        self.assertEqual((), result.attachments)

    async def test_check_availability_uses_only_the_product_owned_probe(self) -> None:
        product = _Product(inventory=8)
        activities: list[tuple[str, str]] = []

        async def activity(tool: str, label: str) -> None:
            activities.append((tool, label))

        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="check_availability",
            arguments=CheckAvailabilityArguments(category="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-probe",
            activity=activity,
        )

        self.assertEqual("RESULTS_AVAILABLE", result.reason)
        self.assertEqual(8, result.broad_inventory_count)
        self.assertEqual(0, result.result_count)
        self.assertEqual(0, product.searches)
        self.assertEqual(1, product.probes)
        self.assertEqual(
            [("check_availability", "Checking current availability")], activities
        )
        self.assertEqual((), result.attachments)

    async def test_zero_inventory_is_explicit_and_never_inferred_from_empty_search(self) -> None:
        product = _Product(inventory=0)
        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-zero",
            activity=None,
        )

        self.assertEqual("CATEGORY_UNAVAILABLE", result.reason)
        self.assertEqual(0, result.broad_inventory_count)
        self.assertEqual(1, product.searches)
        self.assertEqual(1, product.probes)

    async def test_available_inventory_with_zero_exact_matches_is_filters_too_strict(self) -> None:
        product = _Product(inventory=8)
        result = await MarketplaceAgentV2ToolRegistry(product).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(
                query="chair", maximumPrice="1", currency="USD"
            ),
            actor_user_id=ACTOR,
            correlation_id="v2-filters",
            activity=None,
        )

        self.assertEqual("FILTERS_TOO_STRICT", result.reason)
        self.assertEqual(8, result.broad_inventory_count)
        self.assertEqual(0, result.exact_match_count)

    async def test_product_failure_is_technical_and_never_zero_inventory(self) -> None:
        result = await MarketplaceAgentV2ToolRegistry(_Product(fail=True)).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-failure",
            activity=None,
        )

        self.assertEqual("FAILED", result.status)
        self.assertEqual("SEARCH_UNAVAILABLE", result.reason)

    async def test_allowlisted_retrieval_failure_category_is_preserved(self) -> None:
        result = await MarketplaceAgentV2ToolRegistry(
            _ClassifiedFailureProduct()
        ).execute(
            tool="search_listings",
            arguments=SearchListingsArguments(query="chair"),
            actor_user_id=ACTOR,
            correlation_id="v2-classified-failure",
            activity=None,
        )

        self.assertEqual("FAILED", result.status)
        self.assertEqual("PRODUCT_SEARCH_UNAVAILABLE", result.reason)
        self.assertIsNone(result.broad_inventory_count)


if __name__ == "__main__":
    unittest.main()
