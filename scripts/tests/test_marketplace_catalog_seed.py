import datetime as dt
import importlib.util
import json
import sys
import unittest
import statistics
from collections import Counter
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "marketplace_catalog_seed.py"
SPEC = importlib.util.spec_from_file_location("marketplace_catalog_seed", SCRIPT)
seed = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = seed
SPEC.loader.exec_module(seed)


def categories():
    roots = [
        ("root-electronics", "electronics", None),
        ("root-home", "home-garden", None),
        ("root-clothing", "clothing", None),
        ("root-media", "books-media", None),
        ("root-general", "general", None),
    ]
    leaves = [
        ("cat-auto", "automotive", "root-general"),
        ("cat-sports", "sports-fitness", "root-home"),
        ("cat-pets", "pet-supplies", "root-home"),
        ("cat-toys", "toys-games", "root-home"),
        ("cat-music", "musical-instruments", "root-home"),
        ("cat-office", "office-supplies", "root-home"),
        ("cat-beauty", "beauty-personal-care", "root-clothing"),
        ("cat-baby", "baby-kids", "root-clothing"),
    ]
    return [
        {"id": identifier, "slug": slug, "parentId": parent, "status": "ACTIVE"}
        for identifier, slug, parent in roots + leaves
    ]


def config(total=20, individuals=8):
    return seed.Config(
        namespace="unit-seed", total=total, individual_listings=individuals,
        business_listings=total - individuals, individual_sellers=5,
        business_sellers=5, candidates=total, max_age_days=548,
        random_seed=20260928, batch_size=10, use_source_images=False,
        auth_url="http://auth", product_url="http://product", inventory_url="http://inventory",
        service_token="token", agent_token="agent", source_jsonl=None,
        domains=("Electronics",),
    )


class MarketplaceCatalogSeedTests(unittest.TestCase):
    def test_text_limits_match_java_utf16_length(self):
        value = seed.normalize_text("a" * 179 + "😀" + "tail", 180)
        self.assertLessEqual(len(value.encode("utf-16-le")) // 2, 180)
        self.assertEqual("a" * 179, value)

    def test_metadata_filter_maps_to_consolidated_shopper_category(self):
        by_slug, segments = seed.category_index(categories())
        self.assertEqual("electronics", segments["electronics"])
        self.assertEqual("home", segments["home-garden"])
        product, reason = seed.quality_product({
            "parent_asin": "B000KEYBOARD",
            "title": "USB-C Wireless Mechanical Gaming Keyboard",
            "features": ["Bluetooth", "Hot-swappable switches"],
            "description": [],
            "price": "$89.99",
            "store": "Example Brand",
            "categories": ["Electronics", "Computer Accessories"],
        }, "Electronics", by_slug, segments, 7)
        self.assertEqual("accepted", reason)
        self.assertEqual("electronics", product.category_slug)
        self.assertEqual("electronics", product.category_segment)
        self.assertEqual(Decimal("89.99"), product.reference_price)
        self.assertIn("Features:", product.description)

    def test_quality_filter_rejects_unusable_and_digital_rows(self):
        by_slug, segments = seed.category_index(categories())
        missing, reason = seed.quality_product(
            {"parent_asin": "B1", "title": "Unknown", "price": 10},
            "Electronics", by_slug, segments, 7)
        self.assertIsNone(missing)
        self.assertEqual("missing_identity_or_title", reason)
        digital, reason = seed.quality_product({
            "parent_asin": "B000DIGITAL", "title": "Kindle ebook download",
            "features": ["Novel"], "price": 4,
        }, "Books", by_slug, segments, 7)
        self.assertIsNone(digital)
        self.assertEqual("digital_or_subscription_item", reason)

    def test_digital_manual_evidence_is_rejected_outside_books(self):
        by_slug, segments = seed.category_index(categories())
        product, reason = seed.quality_product({
            "parent_asin": "B000ONLINE1",
            "title": "Motorcycle Repair Manual",
            "features": ["Online service manual", "Screen Reader Supported"],
            "description": ["Downloadable reference for common repairs."],
            "details": {"File Size": "21404 KB"},
        }, "Automotive", by_slug, segments, 7)
        self.assertIsNone(product)
        self.assertEqual("digital_or_subscription_item", reason)

    def test_missing_price_is_deterministically_category_aware(self):
        by_slug, segments = seed.category_index(categories())
        raw = {
            "parent_asin": "B000DESK001", "title": "Oak writing desk",
            "description": ["Solid wood writing desk with storage drawer."],
        }
        first, _ = seed.quality_product(raw, "Home_and_Kitchen", by_slug, segments, 42)
        second, _ = seed.quality_product(raw, "Home_and_Kitchen", by_slug, segments, 42)
        self.assertTrue(first.price_generated)
        self.assertEqual(first.reference_price, second.reference_price)
        self.assertGreaterEqual(first.reference_price, Decimal("8"))
        self.assertLessEqual(first.reference_price, Decimal("1600"))

    def test_stable_identity_and_date_generation_are_reproducible(self):
        self.assertEqual(seed.stable_id("same"), seed.stable_id("same"))
        self.assertNotEqual(seed.stable_id("same"), seed.stable_id("other"))
        first = seed.weighted_dates(10_000, 548, 123)
        self.assertEqual(10_000, len(first))
        for created, approved, published, updated in first:
            self.assertLessEqual(created, approved)
            self.assertLessEqual(approved, published)
            self.assertLessEqual(published, updated)
            self.assertLessEqual(updated, dt.datetime.now(dt.timezone.utc))

    def test_engagement_is_deterministic_valid_and_long_tailed(self):
        published = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=180)
        first = seed.engagement_counts("listing-42", published, 99, "BUSINESS")
        self.assertEqual(first, seed.engagement_counts("listing-42", published, 99, "BUSINESS"))
        values = [seed.engagement_counts(f"listing-{index}", published, 99, "INDIVIDUAL")
                  for index in range(10_000)]
        self.assertTrue(all(0 <= likes <= views for views, likes in values))
        self.assertTrue(any(views == 0 for views, _ in values))
        view_counts = [views for views, _ in values]
        self.assertGreater(max(view_counts), statistics.median(view_counts) * 100)

    def test_individual_distribution_is_skewed_and_bounded(self):
        owners = [{"userId": f"u{i}"} for i in range(1200)]
        slots = seed.individual_owner_slots(owners, 4000, 9)
        counts = Counter(owner["userId"] for owner in slots)
        self.assertEqual(4000, sum(counts.values()))
        self.assertEqual(1200, len(counts))
        self.assertGreater(sum(value == 1 for value in counts.values()), 200)
        self.assertLessEqual(max(counts.values()), 6)
        self.assertNotEqual(1, len(set(counts.values())))

    def test_listing_generation_preserves_owner_type_price_stock_and_store_cohesion(self):
        cfg = config()
        products = []
        for index in range(cfg.total):
            electronics = index % 2 == 0
            products.append(seed.Product(
                identity=f"ASIN{index:06d}", source_domain="Electronics",
                title="Wireless Keyboard" if electronics else "Oak Desk",
                description="Useful product description.", features=("Supported fact",),
                details={}, brand="Brand", reference_price=Decimal("100"),
                category_id="root-electronics" if electronics else "root-home",
                category_slug="electronics" if electronics else "home-garden",
                category_segment="electronics" if electronics else "home",
                price_generated=False,
            ))
        identities = {
            "individualSellers": [
                {"userId": f"I{i:025d}", "publicCity": "Irvine", "publicRegion": "CA"}
                for i in range(cfg.individual_sellers)
            ],
            "businesses": [
                {"businessId": f"B{i:025d}", "storeId": f"S{i:025d}",
                 "segment": segment, "publicCity": "Irvine", "publicRegion": "CA"}
                for i, segment in enumerate(("electronics", "home", "clothing", "books-media", "general"))
            ],
        }
        rows = seed.build_listing_rows(cfg, products, identities)
        self.assertEqual(cfg.total, len(rows))
        individual = rows[: cfg.individual_listings]
        business = rows[cfg.individual_listings :]
        self.assertTrue(all(row["businessId"] is None and row["sku"] is None for row in individual))
        self.assertTrue(all(1 <= row["quantity"] <= 5 for row in individual))
        self.assertTrue(all(row["individualSellerUserId"] is None and row["sku"] for row in business))
        self.assertTrue(all(row["quantity"] >= 2 and Decimal(row["priceAmount"]) > 0 for row in business))
        self.assertTrue(all(0 <= row["likeCount"] <= row["visitCount"] for row in rows))
        segment_by_business = {entry["businessId"]: entry["segment"] for entry in identities["businesses"]}
        expected_segment = {"root-electronics": "electronics", "root-home": "home"}
        self.assertTrue(all(segment_by_business[row["businessId"]] == expected_segment[row["categoryId"]] for row in business))

    def test_local_jsonl_reads_metadata_only_shape(self):
        path = Path(__file__).with_suffix(".jsonl")
        try:
            path.write_text(json.dumps({
                "source_domain": "Electronics", "parent_asin": "B0001",
                "title": "Keyboard", "features": ["Wireless"], "price": 20,
            }) + "\n", encoding="utf-8")
            rows = list(seed.stream_jsonl(str(path), 10))
            self.assertEqual("Electronics", rows[0][0])
            self.assertNotIn("source_domain", rows[0][1])
        finally:
            path.unlink(missing_ok=True)

    def test_inventory_command_pages_product_candidates_and_preserves_existing_stock(self):
        cfg = config(total=20, individuals=8)
        first = [
            {"listingId": f"L{index:025d}", "businessId": f"B{index:025d}", "suggestedOnHand": 10}
            for index in range(10)
        ]
        second = [
            {"listingId": f"L{index:025d}", "businessId": f"B{index:025d}", "suggestedOnHand": 10}
            for index in range(10, 12)
        ]

        def response(method, url, token, params=None, json_body=None):
            if method == "GET":
                if params.get("cursor"):
                    return {"data": second, "page": {"nextCursor": None, "hasMore": False}}
                return {"data": first, "page": {"nextCursor": first[-1]["listingId"], "hasMore": True}}
            self.assertEqual("http://inventory/api/v1/internal/demo-fixtures/large-catalog/inventory", url)
            count = len(json_body["listings"])
            return {"received": count, "initialized": count - 1, "preserved": 1}

        with patch.object(seed, "request_json", side_effect=response):
            result = seed.seed_inventory(cfg)

        self.assertEqual(12, result["received"])
        self.assertEqual(10, result["initialized"])
        self.assertEqual(2, result["preserved"])


if __name__ == "__main__":
    unittest.main()
