#!/usr/bin/env python3
"""Stream Amazon Reviews 2023 item metadata into the local JianShang marketplace.

The script never loads review records. It is an orchestrator only: Auth Service
owns seeded identities, Product Service owns seeded listings and search work,
and the optional inventory command asks Inventory Service to initialize stock.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import math
import os
import random
import re
import statistics
import sys
import time
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Iterable, Iterator

import requests


DATASET = "McAuley-Lab/Amazon-Reviews-2023"
DEFAULT_DOMAINS = (
    "Electronics",
    "Cell_Phones_and_Accessories",
    "Home_and_Kitchen",
    "Office_Products",
    "Sports_and_Outdoors",
    "Toys_and_Games",
    "Pet_Supplies",
    "Musical_Instruments",
    "Clothing_Shoes_and_Jewelry",
    "All_Beauty",
    "Books",
    "Video_Games",
    "Automotive",
    "Tools_and_Home_Improvement",
    "Arts_Crafts_and_Sewing",
    "Appliances",
)
BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
PROMOTIONAL = re.compile(
    r"\b(?:amazon(?:'s)?\s+choice|sold\s+by\s+amazon|buy\s+now\s+on\s+amazon|prime\s+eligible)\b",
    re.IGNORECASE,
)
SPACE = re.compile(r"\s+")
BAD_TITLE = re.compile(r"^(?:n/?a|unknown|untitled|null|none|product|test)(?:\s+product)?$", re.IGNORECASE)
DIGITAL_ONLY = re.compile(
    r"\b(?:kindle|e-?book|audible|digital\s+download|downloadable|subscription|"
    r"online\s+(?:service\s+|repair\s+)?manual|screen\s+reader\s+supported)\b",
    re.IGNORECASE,
)


@dataclasses.dataclass(frozen=True)
class Config:
    namespace: str
    total: int
    individual_listings: int
    business_listings: int
    individual_sellers: int
    business_sellers: int
    candidates: int
    max_age_days: int
    random_seed: int
    batch_size: int
    use_source_images: bool
    auth_url: str
    product_url: str
    inventory_url: str
    service_token: str
    agent_token: str
    source_jsonl: str | None
    domains: tuple[str, ...]


@dataclasses.dataclass(frozen=True)
class Product:
    identity: str
    source_domain: str
    title: str
    description: str
    features: tuple[str, ...]
    details: dict[str, str]
    brand: str | None
    reference_price: Decimal
    category_id: str
    category_slug: str
    category_segment: str
    price_generated: bool


class SeedError(RuntimeError):
    pass


def stable_id(value: str) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).digest()
    buffer = 0
    bits = 0
    result: list[str] = []
    for byte in digest:
        buffer = (buffer << 8) | byte
        bits += 8
        while bits >= 5 and len(result) < 26:
            bits -= 5
            result.append(BASE32[(buffer >> bits) & 31])
        if len(result) == 26:
            break
    return "".join(result)


def normalize_text(value: Any, limit: int) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        value = " ".join(str(item) for item in value if item)
    value = PROMOTIONAL.sub("", str(value))
    value = SPACE.sub(" ", value).strip(" \t\r\n-|,")
    # Java/MySQL limits are expressed in UTF-16 code units; supplementary
    # characters (for example emoji in source titles) consume two units.
    encoded = value.encode("utf-16-le")[: limit * 2]
    if len(encoded) % 2:
        encoded = encoded[:-1]
    while encoded:
        try:
            value = encoded.decode("utf-16-le")
            break
        except UnicodeDecodeError:
            encoded = encoded[:-2]
    else:
        value = ""
    return value.strip()


def string_list(value: Any, item_limit: int = 240, max_items: int = 12) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return ()
    result: list[str] = []
    for item in value:
        text = normalize_text(item, item_limit)
        if text and text.lower() not in {entry.lower() for entry in result}:
            result.append(text)
        if len(result) >= max_items:
            break
    return tuple(result)


def normalized_details(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, str] = {}
    for key, raw in value.items():
        name = normalize_text(key, 80)
        content = normalize_text(raw, 240)
        if name and content and len(result) < 12:
            result[name] = content
    return result


def parse_price(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        if isinstance(value, str):
            value = re.sub(r"[^0-9.]", "", value.split("-")[0])
        parsed = Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    except (InvalidOperation, ValueError):
        return None
    return parsed if Decimal("0.50") <= parsed <= Decimal("100000") else None


CATEGORY_RULES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("electronics", (r"\bkeyboard\b", r"keycap", r"\bmonitor\b", r"computer display",
                     r"\blaptop\b", r"\bnotebook computer\b", r"chromebook",
                     r"desktop (?:pc|computer)", r"gaming pc", r"workstation computer",
                     r"headphone", r"earbud", r"earphone", r"headset", r"\bspeaker",
                     r"soundbar", r"\bcamera\b", r"camera lens", r"tripod", r"\biphone\b",
                     r"smartphone", r"cell phone", r"phone case", r"phone charger",
                     r"\btelevision\b", r"\btv\b", r"projector", r"home theater",
                     r"game console", r"gaming controller", r"nintendo switch", r"playstation",
                     r"xbox", r"usb[- ]?c", r"usb hub", r"computer mouse", r"laptop stand",
                     r"webcam", r"computer cable")),
    ("home-garden", (r"\bdesk\b", r"bedside table", r"side table", r"dining table",
                     r"coffee table", r"\bchair\b", r"stool", r"bookshelf", r"bookcase",
                     r"dresser", r"cabinet", r"shelving", r"cookware", r"kitchen",
                     r"tea set", r"dinnerware", r"utensil", r"coffee maker", r"\blamp\b",
                     r"bedding", r"pillow", r"curtain", r"home decor", r"storage bin",
                     r"power tool", r"hand tool", r"drill", r"wrench", r"screwdriver",
                     r"hardware", r"garden", r"patio", r"lawn", r"outdoor furniture",
                     r"appliance", r"refrigerator", r"washing machine", r"vacuum",
                     r"air fryer", r"microwave")),
    ("sports-fitness", (r"running", r"fitness", r"exercise", r"camping", r"hiking", r"sports?\b", r"water bottle")),
    ("pet-supplies", (r"\bdog\b", r"\bcat\b", r"pet (?:toy|food|supply|bed|collar)")),
    ("toys-games", (r"\btoy\b", r"board game", r"puzzle", r"doll", r"building blocks")),
    ("musical-instruments", (r"guitar", r"piano", r"keyboard instrument", r"drum", r"microphone", r"musical instrument")),
    ("office-supplies", (r"office chair mat", r"stationery", r"printer paper", r"filing",
                         r"office suppl", r"craft", r"sewing", r"paint brush", r"yarn", r"canvas")),
    ("clothing", (r"\bdress\b", r"gown", r"women'?s (?:shirt|blouse|skirt|pants|trousers|top)",
                  r"men'?s (?:shirt|jacket|coat|pants|trousers|suit)", r"\bshoes?\b",
                  r"sneaker", r"boots?\b", r"sandals?\b", r"handbag", r"backpack",
                  r"wallet", r"jewelry", r"necklace", r"watch\b")),
    ("beauty-personal-care", (r"beauty", r"skin care", r"makeup", r"shampoo", r"grooming")),
    ("baby-kids", (r"\bbaby\b", r"toddler", r"children'?s clothing")),
    ("books-media", (r"\bbook\b", r"paperback", r"hardcover", r"novel\b", r"\bdvd\b",
                     r"blu[- ]?ray", r"movie\b", r"television series", r"vinyl record",
                     r"\bcd\b", r"music album", r"video game", r"pc game", r"console game")),
    ("automotive", (r"automotive", r"car part", r"vehicle", r"motorcycle", r"tire\b")),
)

DOMAIN_DEFAULTS = {
    "Electronics": "electronics",
    "Cell_Phones_and_Accessories": "electronics",
    "Home_and_Kitchen": "home-garden",
    "Office_Products": "office-supplies",
    "Sports_and_Outdoors": "sports-fitness",
    "Toys_and_Games": "toys-games",
    "Pet_Supplies": "pet-supplies",
    "Musical_Instruments": "musical-instruments",
    "Clothing_Shoes_and_Jewelry": "clothing",
    "Amazon_Fashion": "clothing",
    "All_Beauty": "beauty-personal-care",
    "Beauty_and_Personal_Care": "beauty-personal-care",
    "Books": "books-media",
    "Movies_and_TV": "books-media",
    "CDs_and_Vinyl": "books-media",
    "Video_Games": "books-media",
    "Automotive": "automotive",
    "Tools_and_Home_Improvement": "home-garden",
    "Arts_Crafts_and_Sewing": "office-supplies",
    "Patio_Lawn_and_Garden": "home-garden",
    "Appliances": "home-garden",
    "Baby_Products": "baby-kids",
}

CATEGORY_SEGMENTS = {
    "electronics": "electronics", "home-garden": "home", "clothing": "clothing",
    "books-media": "books-media", "automotive": "automotive", "sports-fitness": "outdoors",
    "pet-supplies": "pets", "toys-games": "kids-toys", "baby-kids": "kids-toys",
    "musical-instruments": "music", "office-supplies": "office-crafts",
    "arts-crafts-sewing": "office-crafts", "beauty-personal-care": "beauty",
}

PRICE_RANGES = {
    "electronics": (8, 2200), "home-garden": (8, 1600),
    "clothing": (8, 600), "books-media": (4, 180),
    "phones-accessories": (12, 950), "laptops": (250, 1800), "desktop-computers": (350, 2200),
    "keyboards": (18, 180), "monitors": (90, 900), "computer-accessories": (8, 160),
    "headphones": (15, 450), "speakers": (20, 650), "cameras": (80, 1800),
    "gaming-electronics": (25, 700), "tv-home-theater": (80, 1800),
    "desks-tables": (35, 900), "chairs": (30, 700), "storage-furniture": (25, 800),
    "kitchen-dining": (8, 350), "home-decor": (8, 320), "tools-home-improvement": (8, 650),
    "patio-garden": (10, 800), "sports-fitness": (8, 700), "pet-supplies": (4, 220),
    "toys-games": (5, 250), "musical-instruments": (15, 1800), "office-supplies": (3, 300),
    "appliances": (25, 1600), "dresses": (15, 350), "womens-tops-bottoms": (8, 220),
    "mens-clothing": (10, 300), "shoes": (15, 350), "bags-accessories": (8, 600),
    "beauty-personal-care": (4, 180), "baby-kids": (5, 350), "books": (4, 120),
    "movies-tv": (4, 100), "music": (5, 180), "video-games": (8, 120),
    "automotive": (8, 1200), "arts-crafts-sewing": (3, 300),
}


def category_index(categories: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    by_slug = {entry["slug"]: entry for entry in categories if entry.get("status") == "ACTIVE"}
    by_id = {entry["id"]: entry for entry in categories}
    segment_by_slug: dict[str, str] = {}
    for slug, entry in by_slug.items():
        cursor = entry
        seen: set[str] = set()
        while cursor.get("parentId") and cursor["id"] not in seen:
            seen.add(cursor["id"])
            cursor = by_id.get(cursor["parentId"], cursor)
        root_slug = cursor.get("slug", "general")
        segment = {
            "electronics": "electronics",
            "home-garden": "home",
            "clothing": "clothing",
            "books-media": "books-media",
        }.get(root_slug, "general")
        segment_by_slug[slug] = CATEGORY_SEGMENTS.get(slug, segment)
    return by_slug, segment_by_slug


def map_category(raw: dict[str, Any], domain: str, by_slug: dict[str, dict[str, Any]]) -> str | None:
    categories = raw.get("categories") or []
    parts = [raw.get("main_category"), raw.get("title"), raw.get("description"), raw.get("features")]
    parts.extend(categories if isinstance(categories, list) else [categories])
    text = normalize_text(parts, 12_000).lower()
    for slug, expressions in CATEGORY_RULES:
        if slug in by_slug and any(re.search(expression, text, re.IGNORECASE) for expression in expressions):
            return slug
    fallback = DOMAIN_DEFAULTS.get(domain)
    return fallback if fallback in by_slug else None


def contextual_price(slug: str, identity: str, seed: int) -> Decimal | None:
    bounds = PRICE_RANGES.get(slug)
    if not bounds:
        return None
    local = random.Random(int(hashlib.sha256(f"{seed}|{identity}|price".encode()).hexdigest()[:16], 16))
    low, high = bounds
    value = math.exp(local.uniform(math.log(low), math.log(high)))
    return Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def quality_product(
    raw: dict[str, Any], domain: str, by_slug: dict[str, dict[str, Any]],
    segment_by_slug: dict[str, str], seed: int,
) -> tuple[Product | None, str]:
    title = normalize_text(raw.get("title"), 180)
    identity = normalize_text(raw.get("parent_asin"), 80)
    if len(title) < 4 or BAD_TITLE.fullmatch(title) or len(identity) < 5:
        return None, "missing_identity_or_title"
    digital_evidence = normalize_text(
        [title, raw.get("main_category"), raw.get("description"), raw.get("features"), raw.get("details")],
        20_000,
    )
    if DIGITAL_ONLY.search(digital_evidence):
        return None, "digital_or_subscription_item"
    features = string_list(raw.get("features"))
    descriptions = string_list(raw.get("description"), item_limit=800, max_items=6)
    if not features and not descriptions:
        return None, "missing_product_content"
    slug = map_category(raw, domain, by_slug)
    if not slug:
        return None, "category_mapping_failure"
    description = normalize_text(" ".join(descriptions), 5_000)
    if not description:
        description = "Features: " + "; ".join(features)
    details = normalized_details(raw.get("details"))
    brand = normalize_text(raw.get("store"), 160) or None
    price = parse_price(raw.get("price"))
    generated = price is None
    if price is None:
        price = contextual_price(slug, identity, seed)
    if price is None:
        return None, "missing_price"
    return Product(
        identity=identity,
        source_domain=domain,
        title=title,
        description=description,
        features=features,
        details=details,
        brand=brand,
        reference_price=price,
        category_id=by_slug[slug]["id"],
        category_slug=slug,
        category_segment=segment_by_slug[slug],
        price_generated=generated,
    ), "accepted"


def stream_huggingface(config: Config) -> Iterator[tuple[str, dict[str, Any]]]:
    """Stream the repository's raw item-metadata JSONL files, never review rows."""
    quota = math.ceil(config.candidates / len(config.domains))
    inspected = 0
    for domain in config.domains:
        url = (
            f"https://huggingface.co/datasets/{DATASET}/resolve/main/"
            f"raw/meta_categories/meta_{domain}.jsonl?download=true"
        )
        with requests.get(url, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            for row_index, line in enumerate(response.iter_lines(decode_unicode=True)):
                if row_index >= quota or inspected >= config.candidates:
                    break
                if not line:
                    continue
                inspected += 1
                yield domain, json.loads(line)
        if inspected >= config.candidates:
            break


def stream_jsonl(path: str, candidate_limit: int) -> Iterator[tuple[str, dict[str, Any]]]:
    with Path(path).open("r", encoding="utf-8") as source:
        for index, line in enumerate(source):
            if index >= candidate_limit:
                break
            raw = json.loads(line)
            domain = raw.pop("source_domain", None) or raw.pop("_source_domain", None)
            if not domain:
                raise SeedError("Local JSONL rows require a source_domain field.")
            yield str(domain), raw


def fetch_candidates(config: Config, categories: list[dict[str, Any]]) -> tuple[list[Product], dict[str, Any]]:
    by_slug, segments = category_index(categories)
    stream = stream_jsonl(config.source_jsonl, config.candidates) if config.source_jsonl else stream_huggingface(config)
    products: list[Product] = []
    reasons: Counter[str] = Counter()
    seen: set[str] = set()
    examined = 0
    for domain, raw in stream:
        examined += 1
        product, reason = quality_product(raw, domain, by_slug, segments, config.random_seed)
        if product and product.identity in seen:
            product = None
            reason = "duplicate_parent_asin"
        reasons[reason] += 1
        if product:
            seen.add(product.identity)
            products.append(product)
        if examined % 5_000 == 0:
            print(f"Candidates inspected: {examined:,}; valid: {len(products):,}", flush=True)
    if examined < min(config.candidates, config.total):
        raise SeedError(f"Only {examined} source candidates were available; expected at least {config.total}.")
    if len(products) < config.total:
        raise SeedError(
            f"Only {len(products)} of {examined} candidates passed filtering; "
            f"increase SEED_CANDIDATE_PRODUCTS above {config.candidates}."
        )
    rng = random.Random(config.random_seed)
    rng.shuffle(products)
    selected = products[: config.total]
    report = {
        "sourceDataset": DATASET,
        "metadataOnly": True,
        "domains": list(config.domains),
        "candidatesExamined": examined,
        "acceptedBeforeTargetLimit": len(products),
        "selected": len(selected),
        "rejected": examined - len(products),
        "rejectionReasons": dict(sorted((key, value) for key, value in reasons.items() if key != "accepted")),
        "categoryMappingFailures": reasons["category_mapping_failure"],
        "contextualPricesGenerated": sum(product.price_generated for product in selected),
    }
    return selected, report


def request_json(method: str, url: str, token: str, *, json_body: Any = None, params: dict[str, Any] | None = None) -> Any:
    response = requests.request(
        method,
        url,
        headers={"X-Internal-Service-Token": token},
        json=json_body,
        params=params,
        timeout=180,
    )
    if not response.ok:
        raise SeedError(f"{method} {url} failed ({response.status_code}): {response.text[:1000]}")
    return response.json() if response.content else None


def public_json(url: str, params: dict[str, Any] | None = None, *, agent_token: str | None = None) -> Any:
    headers = {"X-Agent-Internal-Service-Token": agent_token} if agent_token else None
    response = requests.get(url, params=params, headers=headers, timeout=30)
    if not response.ok:
        raise SeedError(f"GET {url} failed ({response.status_code}): {response.text[:1000]}")
    return response.json()


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def weighted_dates(total: int, max_age_days: int, seed: int) -> list[tuple[dt.datetime, dt.datetime, dt.datetime, dt.datetime]]:
    if max_age_days < 548:
        raise SeedError("SEED_MAX_AGE_DAYS must be at least 548 for the required 18-month distribution.")
    rng = random.Random(seed ^ 0xD471E)
    anchor = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    bands = ((0, 7, .08), (7, 30, .17), (30, 90, .20), (90, 180, .20), (180, 365, .22), (365, 548, .13))
    raw_counts = [total * weight for _, _, weight in bands]
    counts = [math.floor(value) for value in raw_counts]
    for index in sorted(range(len(counts)), key=lambda i: raw_counts[i] - counts[i], reverse=True)[: total - sum(counts)]:
        counts[index] += 1
    values: list[tuple[dt.datetime, dt.datetime, dt.datetime, dt.datetime]] = []
    for (minimum, maximum, _), count in zip(bands, counts, strict=True):
        for _ in range(count):
            age_seconds = rng.randrange(minimum * 86_400 + 60, maximum * 86_400)
            published = anchor - dt.timedelta(seconds=age_seconds)
            created = published - dt.timedelta(seconds=rng.randrange(600, 172_800))
            approved = created + (published - created) * rng.random()
            if rng.random() < 0.58:
                updated = published
            else:
                available = max(1, int((anchor - published).total_seconds()))
                updated = published + dt.timedelta(seconds=rng.randrange(available))
            values.append((created, approved, published, min(updated, anchor)))
    rng.shuffle(values)
    return values


def individual_owner_slots(owners: list[dict[str, Any]], total: int, seed: int) -> list[dict[str, Any]]:
    if total < len(owners):
        owners = owners[:total]
    rng = random.Random(seed ^ 0x1AD1)
    slots = list(owners)
    capacities: list[tuple[dict[str, Any], int]] = []
    shuffled = list(owners)
    rng.shuffle(shuffled)
    for index, owner in enumerate(shuffled):
        ratio = index / max(1, len(shuffled))
        capacity = 1 if ratio < .25 else (3 if ratio < .70 else 6)
        capacities.append((owner, capacity))
    candidates = [owner for owner, capacity in capacities for _ in range(max(0, capacity - 1))]
    rng.shuffle(candidates)
    while len(slots) < total and candidates:
        slots.append(candidates.pop())
    while len(slots) < total:
        slots.append(rng.choice(owners))
    rng.shuffle(slots)
    return slots[:total]


INDIVIDUAL_TEMPLATES = (
    "Owned for a while and ready for a new home. {condition}. {facts}",
    "Selling because it is no longer being used. {condition}. {facts}",
    "Kept in a clean home and handled carefully. {condition}. {facts}",
    "This has been useful, but I am clearing some space. {condition}. {facts}",
    "Personal item in the condition shown. {condition}. {facts}",
)
CONDITION_TEXT = {
    "NEW": "Unused and still in new condition",
    "LIKE_NEW": "Lightly used with very little visible wear",
    "GOOD": "Used with ordinary cosmetic wear and working normally",
    "FAIR": "Shows noticeable wear but remains usable",
}
CONDITION_FACTORS = {
    "NEW": (.85, 1.02), "LIKE_NEW": (.70, .95), "GOOD": (.50, .80), "FAIR": (.30, .65)
}


def detail_facts(product: Product, limit: int = 3) -> str:
    facts = list(product.features[:limit])
    if product.brand:
        facts.append(f"Brand: {product.brand}")
    if not facts:
        facts.append(product.description[:240])
    return "; ".join(facts)[:700].rstrip("; ") + "."


def business_description(product: Product) -> str:
    pieces = [product.description]
    if product.features and not product.description.casefold().startswith("features:"):
        pieces.append("Features: " + "; ".join(product.features[:8]) + ".")
    if product.brand:
        pieces.append("Brand: " + product.brand + ".")
    if product.details:
        specs = "; ".join(f"{key}: {value}" for key, value in list(product.details.items())[:8])
        pieces.append("Specifications: " + specs + ".")
    return normalize_text(" ".join(pieces), 8_000)


def engagement_counts(identity: str, published_at: dt.datetime, seed: int,
                      seller_type: str) -> tuple[int, int]:
    """Generate a deterministic, unbounded long-tail market engagement signal."""
    rng = random.Random(int(hashlib.sha256(
        f"{seed}|{identity}|engagement".encode()).hexdigest()[:16], 16))
    if rng.random() < .055:
        return 0, 0
    age_days = max(0.0, (dt.datetime.now(dt.timezone.utc) - published_at).total_seconds() / 86_400)
    exposure = (age_days + 1.0) ** .62 * rng.lognormvariate(2.15, 1.20)
    if seller_type == "BUSINESS":
        exposure *= 1.25
    viral_roll = rng.random()
    if viral_roll < .004:
        exposure *= rng.uniform(150, 3_000)
    elif viral_roll < .035:
        exposure *= rng.uniform(8, 60)
    views = min(int(round(exposure)), 9_000_000_000_000_000_000)
    if views == 0:
        return 0, 0
    like_rate = min(.32, max(.001, rng.betavariate(1.35, 22.0)))
    likes = min(views, int(round(views * like_rate * rng.uniform(.75, 1.25))))
    return views, likes


def build_listing_rows(config: Config, products: list[Product], identities: dict[str, Any]) -> list[dict[str, Any]]:
    rng = random.Random(config.random_seed)
    dates = weighted_dates(config.total, config.max_age_days, config.random_seed)
    individual_products = products[: config.individual_listings]
    business_products = products[config.individual_listings : config.total]
    owner_slots = individual_owner_slots(identities["individualSellers"], config.individual_listings, config.random_seed)
    rows: list[dict[str, Any]] = []
    conditions = ("NEW", "LIKE_NEW", "GOOD", "GOOD", "GOOD", "FAIR")
    for index, (product, owner) in enumerate(zip(individual_products, owner_slots, strict=True)):
        condition = rng.choice(conditions)
        factor_low, factor_high = CONDITION_FACTORS[condition]
        price = (product.reference_price * Decimal(str(rng.uniform(factor_low, factor_high)))).quantize(Decimal("0.01"))
        created, approved, published, updated = dates[index]
        source_identity = f"individual:{product.identity}"
        views, likes = engagement_counts(source_identity, published, config.random_seed, "INDIVIDUAL")
        rows.append({
            "listingId": stable_id(f"{config.namespace}|{source_identity}"),
            "sourceIdentity": source_identity,
            "sourceDomain": product.source_domain,
            "sellerType": "INDIVIDUAL",
            "individualSellerUserId": owner["userId"],
            "businessId": None,
            "storeId": None,
            "categoryId": product.category_id,
            "title": product.title,
            "description": INDIVIDUAL_TEMPLATES[index % len(INDIVIDUAL_TEMPLATES)].format(
                condition=CONDITION_TEXT[condition], facts=detail_facts(product)),
            "condition": condition,
            "conditionNotes": CONDITION_TEXT[condition] + ".",
            "priceAmount": str(max(price, Decimal("0.50"))),
            "currency": "USD",
            "negotiable": rng.random() < .72,
            "sku": None,
            "quantity": 1 if rng.random() < .92 else rng.randint(2, 5),
            "publicCity": owner["publicCity"],
            "publicRegion": owner["publicRegion"],
            "visitCount": views, "likeCount": likes,
            "createdAt": iso(created), "approvedAt": iso(approved),
            "publishedAt": iso(published), "updatedAt": iso(updated),
        })
    stores_by_segment: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for business in identities["businesses"]:
        stores_by_segment[business["segment"]].append(business)
    store_offsets: Counter[str] = Counter()
    for offset, product in enumerate(business_products, start=config.individual_listings):
        stores = stores_by_segment.get(product.category_segment) or identities["businesses"]
        store = stores[store_offsets[product.category_segment] % len(stores)]
        store_offsets[product.category_segment] += 1
        created, approved, published, updated = dates[offset]
        price = (product.reference_price * Decimal(str(rng.uniform(.94, 1.08)))).quantize(Decimal("0.01"))
        expensive = price >= Decimal("500")
        quantity = rng.randint(2, 20) if expensive else rng.randint(10, 500)
        if price < Decimal("35") and rng.random() < .25:
            quantity = rng.randint(100, 1000)
        source_identity = f"business:{product.identity}"
        views, likes = engagement_counts(source_identity, published, config.random_seed, "BUSINESS")
        rows.append({
            "listingId": stable_id(f"{config.namespace}|{source_identity}"),
            "sourceIdentity": source_identity,
            "sourceDomain": product.source_domain,
            "sellerType": "BUSINESS",
            "individualSellerUserId": None,
            "businessId": store["businessId"],
            "storeId": store["storeId"],
            "categoryId": product.category_id,
            "title": product.title,
            "description": business_description(product),
            "condition": "OPEN_BOX" if rng.random() < .04 else "NEW",
            "conditionNotes": None,
            "priceAmount": str(max(price, Decimal("0.50"))),
            "currency": "USD",
            "negotiable": False,
            "sku": f"SEED-{offset + 1:06d}",
            "quantity": quantity,
            "publicCity": store["publicCity"],
            "publicRegion": store["publicRegion"],
            "visitCount": views, "likeCount": likes,
            "createdAt": iso(created), "approvedAt": iso(approved),
            "publishedAt": iso(published), "updatedAt": iso(updated),
        })
    return rows


def config_from_args(args: argparse.Namespace) -> Config:
    getenv = os.environ.get
    total = int(getenv("SEED_TOTAL_LISTINGS", "10000"))
    individual = int(getenv("SEED_INDIVIDUAL_LISTINGS", "4000"))
    business = int(getenv("SEED_BUSINESS_LISTINGS", str(total - individual)))
    if total != individual + business:
        raise SeedError("SEED_TOTAL_LISTINGS must equal individual plus business listings.")
    namespace = getenv("SEED_NAMESPACE", "amazon-reviews-2023-v1")
    domains = tuple(filter(None, (item.strip() for item in getenv("SEED_SOURCE_DOMAINS", ",".join(DEFAULT_DOMAINS)).split(","))))
    config = Config(
        namespace=namespace,
        total=total,
        individual_listings=individual,
        business_listings=business,
        individual_sellers=int(getenv("SEED_INDIVIDUAL_SELLERS", "1200")),
        business_sellers=int(getenv("SEED_BUSINESS_SELLERS", "175")),
        candidates=int(getenv("SEED_CANDIDATE_PRODUCTS", "25000")),
        max_age_days=int(getenv("SEED_MAX_AGE_DAYS", "548")),
        random_seed=int(getenv("SEED_RANDOM_SEED", "20260928")),
        batch_size=int(getenv("SEED_BATCH_SIZE", "500")),
        use_source_images=getenv("SEED_USE_SOURCE_IMAGES", "false").lower() == "true",
        auth_url=getenv("SEED_AUTH_URL", "http://localhost:8085").rstrip("/"),
        product_url=getenv("SEED_PRODUCT_URL", "http://localhost:8091").rstrip("/"),
        inventory_url=getenv("SEED_INVENTORY_URL", "http://localhost:8082").rstrip("/"),
        service_token=getenv("COMMERCE_INTERNAL_SERVICE_TOKEN", "local-commerce-service-token"),
        agent_token=getenv("AGENT_INTERNAL_SERVICE_TOKEN", "local-agent-service-token"),
        source_jsonl=getattr(args, "source_jsonl", None),
        domains=domains,
    )
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", config.namespace):
        raise SeedError("SEED_NAMESPACE must be 1-32 lowercase letters, digits, or hyphens.")
    if config.total < 1 or config.individual_sellers < 1 or config.business_sellers < 1:
        raise SeedError("Listing and seller counts must be positive.")
    if config.candidates < config.total or not 1 <= config.batch_size <= 1000:
        raise SeedError("Candidate count must cover the target and batch size must be 1-1000.")
    return config


def seed(config: Config) -> dict[str, Any]:
    print("Loading JianShang categories...", flush=True)
    categories = public_json(config.product_url + "/api/v1/categories")
    products, source_report = fetch_candidates(config, categories)
    if config.use_source_images:
        print("SEED_USE_SOURCE_IMAGES=true requested; external images remain unpersisted because the Product media model requires owned storage objects.")
    print(f"Creating individual sellers: {config.individual_sellers:,}", flush=True)
    print(f"Creating businesses: {config.business_sellers:,}", flush=True)
    identities = request_json(
        "POST", config.auth_url + "/api/v1/internal/demo-fixtures/large-catalog/identities",
        config.service_token,
        json_body={
            "namespace": config.namespace,
            "randomSeed": config.random_seed,
            "individualSellerCount": config.individual_sellers,
            "businessSellerCount": config.business_sellers,
            "maxListingAgeDays": config.max_age_days,
        },
    )
    rows = build_listing_rows(config, products, identities)
    completed = 0
    for start in range(0, len(rows), config.batch_size):
        batch = rows[start : start + config.batch_size]
        request_json(
            "POST", config.product_url + "/api/v1/internal/demo-fixtures/large-catalog/listings",
            config.service_token,
            json_body={"namespace": config.namespace, "reviewerUserId": identities["reviewerUserId"], "listings": batch},
        )
        completed += len(batch)
        kind = "individual" if completed <= config.individual_listings else "business"
        print(f"Creating {kind} listings: {completed:,} / {config.total:,}", flush=True)
    wait_for_search_projection(config)
    report = verify(config)
    report["source"] = source_report
    report["configuration"] = dataclasses.asdict(config) | {"service_token": "<redacted>", "agent_token": "<redacted>"}
    path = Path("build/reports/large-catalog-seed.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True, default=str), encoding="utf-8")
    print(f"Complete. Report: {path.resolve()}")
    return report


SEARCH_QUERIES = (
    "wireless keyboard", "gaming monitor", "used iPhone", "pink dress", "office chair",
    "running shoes", "USB-C charger", "dog toy", "guitar", "desk", "wireless headphones",
)


def wait_for_search_projection(config: Config, timeout_seconds: int = 300) -> None:
    """Wait for the derived OpenSearch projection before exercising retrieval."""
    deadline = time.monotonic() + timeout_seconds
    last_pending: int | None = None
    while True:
        stats = request_json(
            "GET", config.product_url + "/api/v1/internal/demo-fixtures/large-catalog/stats",
            config.service_token, params={"namespace": config.namespace})
        pending = int(stats["pendingSearchProjectionWork"])
        if pending == 0:
            return
        if pending != last_pending:
            print(f"Waiting for search projection: {pending:,} listing(s) pending", flush=True)
            last_pending = pending
        if time.monotonic() >= deadline:
            raise SeedError(f"Search projection did not drain within {timeout_seconds} seconds ({pending} pending).")
        time.sleep(2)


def verify(config: Config) -> dict[str, Any]:
    product_stats = request_json(
        "GET", config.product_url + "/api/v1/internal/demo-fixtures/large-catalog/stats",
        config.service_token, params={"namespace": config.namespace})
    auth_stats = request_json(
        "GET", config.auth_url + "/api/v1/internal/demo-fixtures/large-catalog/stats",
        config.service_token, params={"namespace": config.namespace})
    search_results: dict[str, int] = {}
    agent_results: dict[str, int] = {}
    for query in SEARCH_QUERIES:
        marketplace = public_json(config.product_url + "/api/v1/public/marketplace/listings/search", {"q": query, "limit": 20})
        stores = public_json(config.product_url + "/api/v1/public/stores/listings/search", {"q": query, "limit": 20})
        search_results[query] = len(marketplace.get("data", [])) + len(stores.get("data", []))
        agent = public_json(
            config.product_url + "/api/v1/internal/agent/marketplace/listings/search",
            {"q": query, "limit": 20}, agent_token=config.agent_token)
        agent_results[query] = len(agent.get("data", []))
    integrity_ok = all(product_stats[key] == 0 for key in (
        "futureDatedListings", "invalidTimestampOrder", "invalidPrices", "invalidInventory",
        "invalidEngagement", "duplicateSeedIdentities"))
    return {
        "generatedAt": iso(dt.datetime.now(dt.timezone.utc)),
        "product": product_stats,
        "auth": auth_stats,
        "publicSearchResultCounts": search_results,
        "agentRetrievalCandidateCounts": agent_results,
        "integrityPassed": integrity_ok,
    }


def reset(config: Config) -> dict[str, Any]:
    product = request_json(
        "DELETE", config.product_url + "/api/v1/internal/demo-fixtures/large-catalog",
        config.service_token, params={"namespace": config.namespace})
    auth = request_json(
        "DELETE", config.auth_url + "/api/v1/internal/demo-fixtures/large-catalog",
        config.service_token, params={"namespace": config.namespace})
    result = {"product": product, "auth": auth}
    print(json.dumps(result, indent=2))
    return result


def seed_inventory(config: Config) -> dict[str, Any]:
    """Initialize missing stock for Product-registered business seed listings."""
    cursor: str | None = None
    initialized = 0
    preserved = 0
    received = 0
    while True:
        params: dict[str, Any] = {"namespace": config.namespace, "limit": config.batch_size}
        if cursor:
            params["cursor"] = cursor
        page = request_json(
            "GET",
            config.product_url + "/api/v1/internal/demo-fixtures/large-catalog/inventory-candidates",
            config.service_token,
            params=params,
        )
        candidates = page.get("data", [])
        if candidates:
            result = request_json(
                "POST",
                config.inventory_url + "/api/v1/internal/demo-fixtures/large-catalog/inventory",
                config.service_token,
                json_body={"namespace": config.namespace, "listings": candidates},
            )
            received += int(result["received"])
            initialized += int(result["initialized"])
            preserved += int(result["preserved"])
            print(
                f"Initializing business inventory: {received:,} / {config.business_listings:,} "
                f"({initialized:,} new, {preserved:,} preserved)",
                flush=True,
            )
        metadata = page.get("page", {})
        if not metadata.get("hasMore"):
            break
        cursor = metadata.get("nextCursor")
        if not cursor:
            raise SeedError("Product inventory candidate page omitted its next cursor.")
    if received != config.business_listings:
        raise SeedError(
            f"Expected {config.business_listings} business inventory candidates but received {received}.")
    result = {
        "namespace": config.namespace,
        "received": received,
        "initialized": initialized,
        "preserved": preserved,
    }
    print(json.dumps(result, indent=2))
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subparsers = result.add_subparsers(dest="command", required=True)
    for command in ("seed", "reseed"):
        child = subparsers.add_parser(command)
        child.add_argument("--source-jsonl", help="Use a local metadata-only JSONL fixture instead of Hugging Face streaming.")
    subparsers.add_parser("verify")
    subparsers.add_parser("inventory")
    subparsers.add_parser("reset")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        config = config_from_args(args)
        if args.command in {"seed", "reseed"}:
            print(json.dumps(seed(config), indent=2, default=str))
        elif args.command == "verify":
            print(json.dumps(verify(config), indent=2, default=str))
        elif args.command == "inventory":
            seed_inventory(config)
        else:
            reset(config)
        return 0
    except (SeedError, requests.RequestException) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
