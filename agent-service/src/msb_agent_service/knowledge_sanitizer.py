from __future__ import annotations

import hashlib
import hmac
import json
import re
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from .knowledge_source_client import (
    CategoryGuidanceSource,
    ListingKnowledgeSource,
)

LISTING_SANITIZER_VERSION = "listing-sanitizer-v1"
CATEGORY_GUIDANCE_SANITIZER_VERSION = "category-guidance-sanitizer-v1"
_HORIZONTAL_WHITESPACE = re.compile(r"[^\S\n]+")
_EXCESS_BLANK_LINES = re.compile(r"\n{3,}")
_UNSAFE_HTML = re.compile(
    r"<\s*/?\s*(?:script|iframe|object|embed|style|link|meta)\b",
    re.IGNORECASE,
)


class KnowledgeContentError(ValueError):
    """Carries a stable content-contract failure without retaining source text."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class SanitizedListing:
    title: str
    description: str
    price_amount: str
    currency: str
    public_city: str
    public_region: str


@dataclass(frozen=True)
class SanitizedCategoryGuidance:
    category_slug: str
    category_name: str
    title: str
    body: str


def canonical_listing_source_hash(source: ListingKnowledgeSource) -> str:
    """Reproduce Product Service's ordered raw-source SHA-256 contract."""

    if source.lifecycle != "ACTIVE" or source.content is None:
        raise KnowledgeContentError("SOURCE_ACTIVE_CONTENT_REQUIRED")
    content = source.content
    values = (
        ("title", content.title),
        ("description", content.description),
        ("priceAmount", canonical_decimal(content.price.amount)),
        ("currency", content.price.currency.upper()),
        ("publicCity", content.public_location.city),
        ("publicRegion", content.public_location.region),
    )
    canonical = "{" + ",".join(
        f"{json.dumps(key)}:"
        f"{json.dumps(value, ensure_ascii=False, separators=(',', ':'))}"
        for key, value in values
    ) + "}"
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_listing_source_hash(source: ListingKnowledgeSource) -> str:
    """Fail closed when the exact source body does not match its owner hash."""

    computed = canonical_listing_source_hash(source)
    if source.content_hash is None or not hmac.compare_digest(
        computed, source.content_hash
    ):
        raise KnowledgeContentError("SOURCE_CONTENT_HASH_MISMATCH")
    return computed


def canonical_category_guidance_source_hash(
    source: CategoryGuidanceSource,
) -> str:
    """Reproduce Product Service's ordered category-guidance SHA-256."""

    if source.lifecycle != "ACTIVE" or source.content is None:
        raise KnowledgeContentError("SOURCE_ACTIVE_CONTENT_REQUIRED")
    content = source.content
    values = (
        ("categoryId", source.source_id),
        ("categorySlug", content.category_slug),
        ("categoryName", content.category_name),
        ("language", source.language),
        ("title", content.title),
        ("body", content.body),
    )
    canonical = "{" + ",".join(
        f"{json.dumps(key)}:"
        f"{json.dumps(value, ensure_ascii=False, separators=(',', ':'))}"
        for key, value in values
    ) + "}"
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_category_guidance_source_hash(
    source: CategoryGuidanceSource,
) -> str:
    """Fail closed when category guidance differs from its owner hash."""

    computed = canonical_category_guidance_source_hash(source)
    if source.content_hash is None or not hmac.compare_digest(
        computed, source.content_hash
    ):
        raise KnowledgeContentError("SOURCE_CONTENT_HASH_MISMATCH")
    return computed


def sanitize_category_guidance(
    source: CategoryGuidanceSource,
) -> SanitizedCategoryGuidance:
    """Normalize only the approved public category-guidance fields."""

    if source.lifecycle != "ACTIVE" or source.content is None:
        raise KnowledgeContentError("SOURCE_ACTIVE_CONTENT_REQUIRED")
    content = source.content
    if any(
        _UNSAFE_HTML.search(value)
        for value in (
            content.category_name,
            content.title,
            content.body,
        )
    ):
        raise KnowledgeContentError("SOURCE_UNSAFE_MARKUP")
    return SanitizedCategoryGuidance(
        category_slug=_sanitize_text(content.category_slug, 120, multiline=False),
        category_name=_sanitize_text(content.category_name, 180, multiline=False),
        title=_sanitize_text(content.title, 180, multiline=False),
        body=_sanitize_text(content.body, 12_000, multiline=True),
    )


def sanitize_listing(source: ListingKnowledgeSource) -> SanitizedListing:
    """Normalize only the approved public listing fields deterministically."""

    if source.lifecycle != "ACTIVE" or source.content is None:
        raise KnowledgeContentError("SOURCE_ACTIVE_CONTENT_REQUIRED")
    content = source.content
    return SanitizedListing(
        title=_sanitize_text(content.title, 240, multiline=False),
        description=_sanitize_text(
            content.description,
            20_000,
            multiline=True,
        ),
        price_amount=canonical_decimal(content.price.amount),
        currency=content.price.currency.upper(),
        public_city=_sanitize_text(
            content.public_location.city,
            160,
            multiline=False,
        ),
        public_region=_sanitize_text(
            content.public_location.region,
            160,
            multiline=False,
        ),
    )


def canonical_decimal(value: str) -> str:
    """Match BigDecimal.stripTrailingZeros().toPlainString() for nonnegative input."""

    try:
        parsed = Decimal(value)
    except InvalidOperation as exc:
        raise KnowledgeContentError("SOURCE_PRICE_INVALID") from exc
    if not parsed.is_finite() or parsed < 0:
        raise KnowledgeContentError("SOURCE_PRICE_INVALID")
    rendered = format(parsed, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return rendered or "0"


def _sanitize_text(value: str, maximum: int, *, multiline: bool) -> str:
    normalized = unicodedata.normalize("NFKC", value).replace("\r\n", "\n")
    normalized = normalized.replace("\r", "\n")
    safe_characters: list[str] = []
    for character in normalized:
        if character in {"\n", "\t"}:
            safe_characters.append(character)
            continue
        category = unicodedata.category(character)
        if category in {"Cc", "Cf", "Cs"}:
            continue
        safe_characters.append(character)
    normalized = "".join(safe_characters)
    if multiline:
        lines = [
            _HORIZONTAL_WHITESPACE.sub(" ", line).strip()
            for line in normalized.split("\n")
        ]
        normalized = _EXCESS_BLANK_LINES.sub(
            "\n\n",
            "\n".join(lines),
        ).strip()
    else:
        normalized = " ".join(normalized.split())
    if not normalized:
        raise KnowledgeContentError("SOURCE_TEXT_EMPTY_AFTER_SANITIZATION")
    if len(normalized) > maximum:
        raise KnowledgeContentError("SOURCE_TEXT_EXCEEDS_SANITIZED_LIMIT")
    return normalized
