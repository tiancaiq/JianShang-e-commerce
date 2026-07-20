from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

import tiktoken

from .knowledge_sanitizer import (
    CATEGORY_GUIDANCE_SANITIZER_VERSION,
    LISTING_SANITIZER_VERSION,
    KnowledgeContentError,
    SanitizedCategoryGuidance,
    SanitizedListing,
)

LISTING_CHUNKER_VERSION = "listing-chunker-v1"
CATEGORY_GUIDANCE_CHUNKER_VERSION = "category-guidance-chunker-v1"
_PREFERRED_BOUNDARIES = ("\n", ". ", "! ", "? ", "; ", ", ", " ")


@dataclass(frozen=True)
class ListingChunk:
    chunk_id: str
    ordinal: int
    section_label: str
    text: str
    token_count: int


class ListingKnowledgeChunker:
    """Creates deterministic token-bounded listing summary/description chunks."""

    def __init__(
        self,
        *,
        model: str,
        maximum_tokens: int,
        overlap_tokens: int,
        maximum_chunks: int,
        encoding: Any | None = None,
    ) -> None:
        if encoding is not None:
            self._encoding = encoding
        else:
            try:
                self._encoding = tiktoken.encoding_for_model(model)
            except KeyError:
                self._encoding = tiktoken.get_encoding("cl100k_base")
        self._maximum_tokens = maximum_tokens
        self._overlap_tokens = overlap_tokens
        self._maximum_chunks = maximum_chunks

    def chunk(
        self,
        listing: SanitizedListing,
        *,
        source_id: str,
        source_version: int,
        language: str,
        content_hash: str,
    ) -> tuple[ListingChunk, ...]:
        """Build one summary plus bounded description windows."""

        summary = (
            "Listing summary\n"
            f"Title: {listing.title}\n"
            f"Location: {listing.public_city}, {listing.public_region}\n"
            f"Price: {listing.price_amount} {listing.currency}"
        )
        chunks: list[tuple[str, str]] = [("Listing summary", summary)]
        prefix = f"Title: {listing.title}\nDescription: "
        available = self._maximum_tokens - self.token_count(prefix)
        if available <= 0:
            raise KnowledgeContentError("CHUNK_TITLE_EXCEEDS_TOKEN_LIMIT")
        for segment in self._description_segments(
            listing.description,
            available,
        ):
            chunks.append(
                (f"Description {len(chunks)}", f"{prefix}{segment}")
            )
            if len(chunks) > self._maximum_chunks:
                raise KnowledgeContentError("CHUNK_COUNT_LIMIT_EXCEEDED")

        result: list[ListingChunk] = []
        for ordinal, (label, text) in enumerate(chunks):
            token_count = self.token_count(text)
            if (
                not text
                or len(text) > 8_000
                or token_count > self._maximum_tokens
            ):
                raise KnowledgeContentError("CHUNK_OUTPUT_LIMIT_EXCEEDED")
            result.append(
                ListingChunk(
                    chunk_id=deterministic_chunk_id(
                        source_type="LISTING",
                        source_id=source_id,
                        source_version=source_version,
                        language=language,
                        ordinal=ordinal,
                        content_hash=content_hash,
                    ),
                    ordinal=ordinal,
                    section_label=label,
                    text=text,
                    token_count=token_count,
                )
            )
        return tuple(result)

    def token_count(self, text: str) -> int:
        return len(self._encoding.encode(text))

    def _description_segments(
        self,
        description: str,
        available_tokens: int,
    ) -> tuple[str, ...]:
        segments: list[str] = []
        start = 0
        text_length = len(description)
        while start < text_length:
            end = self._maximum_end(description, start, available_tokens)
            if end <= start:
                raise KnowledgeContentError("CHUNK_PROGRESS_FAILED")
            if end < text_length:
                preferred = self._preferred_end(description, start, end)
                if preferred > start:
                    end = preferred
            segment = description[start:end].strip()
            if not segment:
                start = end
                continue
            segments.append(segment)
            if len(segments) >= self._maximum_chunks:
                if description[end:].strip():
                    raise KnowledgeContentError("CHUNK_COUNT_LIMIT_EXCEEDED")
                break
            if end >= text_length:
                break
            overlap_start = self._overlap_start(description, start, end)
            start = max(start + 1, overlap_start)
            while start < end and description[start].isspace():
                start += 1
        return tuple(segments)

    def _maximum_end(self, text: str, start: int, token_limit: int) -> int:
        low = start + 1
        high = min(len(text), start + 8_000)
        accepted = start
        while low <= high:
            middle = (low + high) // 2
            if self.token_count(text[start:middle]) <= token_limit:
                accepted = middle
                low = middle + 1
            else:
                high = middle - 1
        return accepted

    def _preferred_end(self, text: str, start: int, maximum_end: int) -> int:
        minimum = start + max(1, (maximum_end - start) // 2)
        window = text[minimum:maximum_end]
        best = -1
        boundary_length = 0
        for boundary in _PREFERRED_BOUNDARIES:
            position = window.rfind(boundary)
            if position > best:
                best = position
                boundary_length = len(boundary)
        return (
            maximum_end
            if best < 0
            else minimum + best + boundary_length
        )

    def _overlap_start(self, text: str, start: int, end: int) -> int:
        if self._overlap_tokens == 0:
            return end
        low = start
        high = end
        accepted = end
        while low <= high:
            middle = (low + high) // 2
            if self.token_count(text[middle:end]) <= self._overlap_tokens:
                accepted = middle
                high = middle - 1
            else:
                low = middle + 1
        boundary = text.find(" ", accepted, end)
        return accepted if boundary < 0 else boundary + 1


class CategoryGuidanceChunker(ListingKnowledgeChunker):
    """Creates deterministic metadata and body chunks for category guidance."""

    def chunk(
        self,
        guidance: SanitizedCategoryGuidance,
        *,
        source_id: str,
        source_version: int,
        language: str,
        content_hash: str,
    ) -> tuple[ListingChunk, ...]:
        metadata = (
            "Category guidance\n"
            f"Category: {guidance.category_name}\n"
            f"Category slug: {guidance.category_slug}\n"
            f"Title: {guidance.title}"
        )
        prefix = (
            f"Category: {guidance.category_name}\n"
            f"Title: {guidance.title}\n"
            "Guidance: "
        )
        available = self._maximum_tokens - self.token_count(prefix)
        if available <= 0:
            raise KnowledgeContentError("CHUNK_TITLE_EXCEEDS_TOKEN_LIMIT")
        chunks: list[tuple[str, str]] = [("Category guidance", metadata)]
        for segment in self._description_segments(guidance.body, available):
            chunks.append(
                (f"Guidance {len(chunks)}", f"{prefix}{segment}")
            )
            if len(chunks) > self._maximum_chunks:
                raise KnowledgeContentError("CHUNK_COUNT_LIMIT_EXCEEDED")
        result: list[ListingChunk] = []
        for ordinal, (label, text) in enumerate(chunks):
            token_count = self.token_count(text)
            if (
                not text
                or len(text) > 8_000
                or token_count > self._maximum_tokens
            ):
                raise KnowledgeContentError("CHUNK_OUTPUT_LIMIT_EXCEEDED")
            result.append(
                ListingChunk(
                    chunk_id=deterministic_chunk_id(
                        source_type="CATEGORY_GUIDANCE",
                        source_id=source_id,
                        source_version=source_version,
                        language=language,
                        ordinal=ordinal,
                        content_hash=content_hash,
                        sanitizer_version=CATEGORY_GUIDANCE_SANITIZER_VERSION,
                        chunker_version=CATEGORY_GUIDANCE_CHUNKER_VERSION,
                    ),
                    ordinal=ordinal,
                    section_label=label,
                    text=text,
                    token_count=token_count,
                )
            )
        return tuple(result)


def deterministic_chunk_id(
    *,
    source_type: str,
    source_id: str,
    source_version: int,
    language: str,
    ordinal: int,
    content_hash: str,
    sanitizer_version: str = LISTING_SANITIZER_VERSION,
    chunker_version: str = LISTING_CHUNKER_VERSION,
) -> str:
    """Hash the immutable source/chunker identity into an exact document ID."""

    identity = "\0".join(
        (
            source_type,
            source_id,
            str(source_version),
            language,
            sanitizer_version,
            chunker_version,
            str(ordinal),
            content_hash,
        )
    )
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()
