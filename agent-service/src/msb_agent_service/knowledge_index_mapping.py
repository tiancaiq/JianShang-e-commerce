from __future__ import annotations

import re
from typing import Any

from .config import KnowledgeIndexSettings

KNOWLEDGE_INDEX_SCHEMA_VERSION = 1
DISTANCE_SPACE = "cosinesimil"
_GENERATION_PATTERN = re.compile(r"^(?P<prefix>[a-z0-9_-]+)-v(?P<schema>\d{4})-(?P<generation>\d{6})$")


def physical_index_name(settings: KnowledgeIndexSettings, generation: int) -> str:
    """Build the immutable physical index name for one schema generation."""

    if not 1 <= generation <= 999_999:
        raise ValueError("knowledge index generation must be between 1 and 999999")
    return (
        f"{settings.index_prefix}-v{KNOWLEDGE_INDEX_SCHEMA_VERSION:04d}-"
        f"{generation:06d}"
    )


def validate_physical_index_name(
    settings: KnowledgeIndexSettings, index_name: str
) -> int:
    """Accept only an exact physical index under the configured prefix/schema."""

    match = _GENERATION_PATTERN.fullmatch(index_name)
    if (
        match is None
        or match.group("prefix") != settings.index_prefix
        or int(match.group("schema")) != KNOWLEDGE_INDEX_SCHEMA_VERSION
    ):
        raise ValueError("physical index name does not match the configured prefix/schema")
    return int(match.group("generation"))


def knowledge_index_definition(settings: KnowledgeIndexSettings) -> dict[str, Any]:
    """Generate deterministic strict mappings for public knowledge chunks."""

    if settings.embedding_dimensions is None:
        raise ValueError("embedding dimensions are required to build the mapping")
    return {
        "settings": {
            "index": {
                "knn": True,
                "number_of_shards": settings.shards,
                "number_of_replicas": settings.replicas,
                "mapping.total_fields.limit": 64,
            }
        },
        "mappings": {
            "dynamic": "strict",
            "_meta": expected_mapping_metadata(settings),
            "properties": {
                "chunkId": {"type": "keyword"},
                "sourceType": {"type": "keyword"},
                "sourceId": {"type": "keyword"},
                "sourceVersion": {"type": "keyword"},
                "contentHash": {"type": "keyword"},
                "listingId": {"type": "keyword"},
                "visibility": {"type": "keyword"},
                "language": {"type": "keyword"},
                "effectiveFrom": {"type": "date"},
                "effectiveTo": {"type": "date"},
                "indexedAt": {"type": "date"},
                "invalidatedAt": {"type": "date"},
                "ordinal": {"type": "integer"},
                "sectionLabel": {"type": "keyword", "ignore_above": 200},
                "text": {"type": "text"},
                "embedding": {
                    "type": "knn_vector",
                    "dimension": settings.embedding_dimensions,
                    "method": {
                        "name": "hnsw",
                        "space_type": DISTANCE_SPACE,
                        "engine": "lucene",
                        "parameters": {
                            "ef_construction": 100,
                            "m": 16,
                        },
                    },
                },
            },
        },
    }


def expected_mapping_metadata(settings: KnowledgeIndexSettings) -> dict[str, Any]:
    """Describe the compatibility identity stored in the mapping metadata."""

    return {
        "schemaVersion": KNOWLEDGE_INDEX_SCHEMA_VERSION,
        "embeddingProvider": settings.embedding_provider,
        "embeddingModel": settings.embedding_model,
        "embeddingDimensions": settings.embedding_dimensions,
        "distanceSpace": DISTANCE_SPACE,
        "createdBy": "msb-agent-service",
    }


def mapping_is_compatible(
    settings: KnowledgeIndexSettings, index_name: str, response: dict[str, Any]
) -> bool:
    """Check schema and embedding identity without trusting mutable aliases."""

    index_mapping = response.get(index_name, {}).get("mappings", {})
    if index_mapping.get("dynamic") != "strict":
        return False
    if index_mapping.get("_meta") != expected_mapping_metadata(settings):
        return False
    properties = index_mapping.get("properties", {})
    expected_properties = knowledge_index_definition(settings)["mappings"]["properties"]
    return properties == expected_properties


def settings_are_compatible(
    settings: KnowledgeIndexSettings, index_name: str, response: dict[str, Any]
) -> bool:
    """Validate immutable index settings returned as OpenSearch strings."""

    actual = response.get(index_name, {}).get("settings", {}).get("index", {})
    return (
        str(actual.get("knn", "")).lower() == "true"
        and int(actual.get("number_of_shards", -1)) == settings.shards
        and int(actual.get("number_of_replicas", -1)) == settings.replicas
    )
