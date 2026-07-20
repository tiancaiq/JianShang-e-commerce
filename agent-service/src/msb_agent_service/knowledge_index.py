from __future__ import annotations

import argparse
import asyncio
import json
import logging
import time
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Awaitable, Callable, Sequence, TypeVar

from opensearchpy import AsyncOpenSearch
from opensearchpy.exceptions import NotFoundError

from .config import KnowledgeIndexSettings, Settings
from .knowledge_index_client import (
    KnowledgeIndexError,
    KnowledgeIndexErrorCode,
    classify_open_search_error,
    create_open_search_client,
)
from .knowledge_index_documents import (
    KnowledgeChunkDocument,
    SourceType,
    fixed_metadata_filters,
)
from .knowledge_index_mapping import (
    KNOWLEDGE_INDEX_SCHEMA_VERSION,
    knowledge_index_definition,
    mapping_is_compatible,
    physical_index_name,
    settings_are_compatible,
    validate_physical_index_name,
)
from .knowledge_index_metrics import KnowledgeIndexMetrics

LOGGER = logging.getLogger(__name__)
T = TypeVar("T")


class KnowledgeReadinessStatus(StrEnum):
    DISABLED = "DISABLED"
    READY = "READY"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    UNAVAILABLE = "UNAVAILABLE"
    UNAUTHORIZED = "UNAUTHORIZED"
    INCOMPATIBLE = "INCOMPATIBLE"


@dataclass(frozen=True)
class KnowledgeIndexStatus:
    status: KnowledgeReadinessStatus
    read_index: str | None = None
    write_index: str | None = None
    document_count: int = 0
    cluster_version: str | None = None


@dataclass(frozen=True)
class BulkWriteResult:
    attempted: int
    succeeded: int
    failed_chunk_ids: tuple[str, ...]


class KnowledgeIndexAdmin:
    """Owns versioned index and alias transitions for the agent schema."""

    def __init__(
        self,
        client: AsyncOpenSearch,
        settings: KnowledgeIndexSettings,
        metrics: KnowledgeIndexMetrics | None = None,
    ) -> None:
        self.client = client
        self.settings = settings
        self.metrics = metrics or KnowledgeIndexMetrics()

    async def status(self) -> KnowledgeIndexStatus:
        """Validate cluster, alias, mapping, and embedding compatibility."""

        if not self.settings.enabled:
            self.metrics.ready.set(0)
            self.metrics.alias_valid.set(0)
            return KnowledgeIndexStatus(KnowledgeReadinessStatus.DISABLED)
        try:
            info = await self._call("cluster_info", self.client.info)
            version = str(info.get("version", {}).get("number", ""))
            if not version.startswith("2."):
                return self._incompatible(version)
            read_targets = await self._alias_targets(
                self.settings.read_alias, require_write=False
            )
            write_targets = await self._alias_targets(
                self.settings.write_alias, require_write=True
            )
            if not read_targets and not write_targets:
                self.metrics.ready.set(0)
                self.metrics.alias_valid.set(0)
                return KnowledgeIndexStatus(
                    KnowledgeReadinessStatus.NOT_CONFIGURED,
                    cluster_version=version,
                )
            if len(read_targets) != 1 or len(write_targets) != 1:
                raise KnowledgeIndexError(
                    KnowledgeIndexErrorCode.ALIAS_INVALID,
                    "Knowledge index aliases must each resolve to one index",
                )
            read_index = read_targets[0]
            write_index = write_targets[0]
            for index_name in {read_index, write_index}:
                await self._validate_mapping(index_name)
            count_response = await self._call(
                "count", lambda: self.client.count(index=self.settings.read_alias)
            )
            document_count = int(count_response.get("count", 0))
            if document_count:
                latest_response = await self._call(
                    "latest_indexed_at",
                    lambda: self.client.search(
                        index=self.settings.read_alias,
                        body={
                            "size": 0,
                            "aggs": {
                                "latest": {
                                    "max": {
                                        "field": "indexedAt",
                                        "format": "epoch_millis",
                                    }
                                }
                            },
                        },
                    ),
                )
                latest_millis = (
                    latest_response.get("aggregations", {})
                    .get("latest", {})
                    .get("value")
                )
                if latest_millis is not None:
                    self.metrics.observe_indexed_at(float(latest_millis) / 1_000)
            self.metrics.ready.set(1)
            self.metrics.alias_valid.set(1)
            self.metrics.documents.set(document_count)
            return KnowledgeIndexStatus(
                KnowledgeReadinessStatus.READY,
                read_index,
                write_index,
                document_count,
                version,
            )
        except KnowledgeIndexError as error:
            self.metrics.ready.set(0)
            if error.code == KnowledgeIndexErrorCode.ALIAS_INVALID:
                self.metrics.alias_valid.set(0)
                return KnowledgeIndexStatus(KnowledgeReadinessStatus.INCOMPATIBLE)
            if error.code == KnowledgeIndexErrorCode.UNAUTHORIZED:
                return KnowledgeIndexStatus(KnowledgeReadinessStatus.UNAUTHORIZED)
            if error.code == KnowledgeIndexErrorCode.INCOMPATIBLE:
                return KnowledgeIndexStatus(KnowledgeReadinessStatus.INCOMPATIBLE)
            return KnowledgeIndexStatus(KnowledgeReadinessStatus.UNAVAILABLE)
        except NotFoundError:
            self.metrics.ready.set(0)
            self.metrics.alias_valid.set(0)
            return KnowledgeIndexStatus(KnowledgeReadinessStatus.INCOMPATIBLE)

    async def bootstrap(self) -> KnowledgeIndexStatus:
        """Create the first generation and both aliases idempotently."""

        existing = await self.status()
        if existing.status == KnowledgeReadinessStatus.READY:
            return existing
        if existing.status not in {
            KnowledgeReadinessStatus.NOT_CONFIGURED,
        }:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.OPERATION_REJECTED,
                f"Cannot bootstrap knowledge index from {existing.status}",
            )
        index_name = physical_index_name(self.settings, 1)
        body = knowledge_index_definition(self.settings)
        body["aliases"] = {
            self.settings.read_alias: {},
            self.settings.write_alias: {"is_write_index": True},
        }
        await self._call(
            "bootstrap",
            lambda: self.client.indices.create(index=index_name, body=body),
        )
        result = await self.status()
        if result.status != KnowledgeReadinessStatus.READY:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.INCOMPATIBLE,
                "Created knowledge index did not pass validation",
            )
        return result

    async def create_generation(self) -> str:
        """Create the next mapping-compatible generation and move write alias."""

        current = await self.status()
        if current.status != KnowledgeReadinessStatus.READY:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.OPERATION_REJECTED,
                "Knowledge index must be ready before creating a generation",
            )
        generation = await self._next_generation()
        index_name = physical_index_name(self.settings, generation)
        await self._call(
            "create_generation",
            lambda: self.client.indices.create(
                index=index_name, body=knowledge_index_definition(self.settings)
            ),
        )
        await self._validate_mapping(index_name)
        actions = [
            {
                "remove": {
                    "index": current.write_index,
                    "alias": self.settings.write_alias,
                    "must_exist": True,
                }
            },
            {
                "add": {
                    "index": index_name,
                    "alias": self.settings.write_alias,
                    "is_write_index": True,
                }
            },
        ]
        await self._call(
            "move_write_alias",
            lambda: self.client.indices.update_aliases(body={"actions": actions}),
        )
        return index_name

    async def promote(self, index_name: str) -> KnowledgeIndexStatus:
        """Atomically promote the current write generation for reads."""

        return await self._move_read_alias(index_name, require_write_target=True)

    async def rollback(self, index_name: str) -> KnowledgeIndexStatus:
        """Atomically move reads to a compatible prior generation."""

        return await self._move_read_alias(index_name, require_write_target=False)

    async def _move_read_alias(
        self, index_name: str, *, require_write_target: bool
    ) -> KnowledgeIndexStatus:
        validate_physical_index_name(self.settings, index_name)
        await self._validate_mapping(index_name)
        current = await self.status()
        if current.status != KnowledgeReadinessStatus.READY:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.OPERATION_REJECTED,
                "Knowledge index aliases are not ready",
            )
        if require_write_target and current.write_index != index_name:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.OPERATION_REJECTED,
                "Only the current write generation can be promoted",
            )
        if current.read_index == index_name:
            return current
        actions = [
            {
                "remove": {
                    "index": current.read_index,
                    "alias": self.settings.read_alias,
                    "must_exist": True,
                }
            },
            {"add": {"index": index_name, "alias": self.settings.read_alias}},
        ]
        await self._call(
            "move_read_alias",
            lambda: self.client.indices.update_aliases(body={"actions": actions}),
        )
        result = await self.status()
        if result.status != KnowledgeReadinessStatus.READY:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.INCOMPATIBLE,
                "Knowledge index alias change did not pass validation",
            )
        return result

    async def _next_generation(self) -> int:
        for generation in range(1, 1_000_000):
            index_name = physical_index_name(self.settings, generation)
            exists = await self._call(
                "index_exists",
                lambda index_name=index_name: self.client.indices.exists(
                    index=index_name
                ),
            )
            if not exists:
                return generation
        raise KnowledgeIndexError(
            KnowledgeIndexErrorCode.OPERATION_REJECTED,
            "Knowledge index generation limit is exhausted",
        )

    async def _alias_targets(
        self, alias: str, *, require_write: bool
    ) -> list[str]:
        exists = await self._call(
            "alias_exists", lambda: self.client.indices.exists_alias(name=alias)
        )
        if not exists:
            return []
        response = await self._call(
            "alias_lookup", lambda: self.client.indices.get_alias(name=alias)
        )
        targets: list[str] = []
        for index_name, value in response.items():
            alias_value = value.get("aliases", {}).get(alias, {})
            if not require_write or alias_value.get("is_write_index") is True:
                targets.append(index_name)
        return sorted(targets)

    async def _validate_mapping(self, index_name: str) -> None:
        try:
            validate_physical_index_name(self.settings, index_name)
        except ValueError as error:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.INCOMPATIBLE,
                "Knowledge index name is incompatible",
            ) from error
        response = await self._call(
            "mapping_lookup",
            lambda: self.client.indices.get_mapping(index=index_name),
        )
        if not mapping_is_compatible(self.settings, index_name, response):
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.INCOMPATIBLE,
                "Knowledge index mapping or embedding identity is incompatible",
            )
        settings_response = await self._call(
            "settings_lookup",
            lambda: self.client.indices.get_settings(index=index_name),
        )
        if not settings_are_compatible(
            self.settings, index_name, settings_response
        ):
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.INCOMPATIBLE,
                "Knowledge index settings are incompatible",
            )

    def _incompatible(self, version: str | None) -> KnowledgeIndexStatus:
        self.metrics.ready.set(0)
        self.metrics.alias_valid.set(0)
        return KnowledgeIndexStatus(
            KnowledgeReadinessStatus.INCOMPATIBLE,
            cluster_version=version,
        )

    async def _call(
        self, operation: str, action: Callable[[], Awaitable[T]]
    ) -> T:
        started = time.monotonic()
        try:
            result = await action()
            self.metrics.operations.labels(operation, "success").inc()
            self.metrics.last_success.set_to_current_time()
            LOGGER.info(
                json.dumps(
                    {
                        "event": "knowledge_index_operation",
                        "operation": operation,
                        "status": "success",
                        "schemaVersion": KNOWLEDGE_INDEX_SCHEMA_VERSION,
                    }
                )
            )
            return result
        except KnowledgeIndexError:
            self.metrics.operations.labels(operation, "failed").inc()
            raise
        except NotFoundError:
            self.metrics.operations.labels(operation, "not_found").inc()
            raise
        except Exception as error:
            mapped = classify_open_search_error(error)
            self.metrics.operations.labels(operation, "failed").inc()
            LOGGER.warning(
                json.dumps(
                    {
                        "event": "knowledge_index_operation",
                        "operation": operation,
                        "status": "failed",
                        "errorCode": mapped.code,
                        "retryable": mapped.retryable,
                    }
                )
            )
            raise mapped from error
        finally:
            self.metrics.operation_duration.labels(operation).observe(
                time.monotonic() - started
            )


class KnowledgeIndexWriter:
    """Provides bounded exact writes to one generation or all live generations."""

    def __init__(
        self,
        client: AsyncOpenSearch,
        settings: KnowledgeIndexSettings,
        metrics: KnowledgeIndexMetrics | None = None,
        *,
        target_index: str | None = None,
        mirror_live_generations: bool = False,
    ) -> None:
        if target_index is not None:
            validate_physical_index_name(settings, target_index)
        if target_index is not None and mirror_live_generations:
            raise ValueError("exact target and live mirroring are mutually exclusive")
        self.client = client
        self.settings = settings
        self.metrics = metrics or KnowledgeIndexMetrics()
        self._target_index = target_index
        self._mirror_live_generations = mirror_live_generations

    async def upsert(
        self, documents: Sequence[KnowledgeChunkDocument], *, refresh: bool = False
    ) -> BulkWriteResult:
        """Index a bounded strict batch through the configured write alias."""

        if not documents:
            return BulkWriteResult(0, 0, ())
        if len(documents) > self.settings.bulk_max_documents:
            raise self._document_error("Knowledge document batch is too large")
        if self.settings.embedding_dimensions is None:
            raise self._document_error("Embedding dimensions are not configured")
        targets = await self._write_targets()
        operations: list[dict[str, object]] = []
        chunk_ids: set[str] = set()
        for document in documents:
            try:
                document.validate_embedding_dimensions(
                    self.settings.embedding_dimensions
                )
            except ValueError as error:
                raise self._document_error("Knowledge document is invalid") from error
            if document.chunk_id in chunk_ids:
                raise self._document_error(
                    "Knowledge document batch contains duplicate chunk IDs"
                )
            chunk_ids.add(document.chunk_id)
            for target in targets:
                operations.append(
                    {
                        "index": {
                            "_index": target,
                            "_id": document.chunk_id,
                        }
                    }
                )
                operations.append(document.open_search_source())
        serialized_size = len(
            json.dumps(operations, separators=(",", ":")).encode("utf-8")
        )
        if serialized_size > self.settings.bulk_max_bytes:
            raise self._document_error("Knowledge document batch exceeds byte limit")
        response = await self._call(
            "bulk_upsert",
            lambda: self.client.bulk(body=operations, refresh=refresh),
        )
        failures = self._bulk_failures(response)
        if failures:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.PARTIAL_FAILURE,
                f"Knowledge index batch partially failed for {len(failures)} documents",
                retryable=True,
                failed_ids=failures,
            )
        self.metrics.observe_indexed_at(
            max(document.indexed_at for document in documents)
        )
        return BulkWriteResult(len(documents), len(documents), ())

    async def invalidate_source(
        self,
        source_type: SourceType,
        source_id: str,
        source_version: str,
        invalidated_at: datetime,
        *,
        refresh: bool = False,
    ) -> int:
        """Monotonically invalidate one exact source version."""

        self._validate_exact_source(source_id, source_version)
        self._validate_timestamp(invalidated_at)
        body = {
            "query": {
                "bool": {
                    "filter": fixed_metadata_filters(
                        source_types=[source_type],
                        source_id=source_id,
                        source_version=source_version,
                        active_only=False,
                    )
                }
            },
            "script": {
                "lang": "painless",
                "source": (
                    "if (ctx._source.invalidatedAt == null) "
                    "{ ctx._source.invalidatedAt = params.invalidatedAt; }"
                ),
                "params": {"invalidatedAt": invalidated_at.astimezone(UTC).isoformat()},
            },
        }
        updated = 0
        for target in await self._write_targets():
            response = await self._call(
                "invalidate_source",
                lambda target=target: self.client.update_by_query(
                    index=target,
                    body=body,
                    conflicts="proceed",
                    refresh=refresh,
                ),
            )
            updated += int(response.get("updated", 0))
        return updated

    async def delete_chunks(
        self, chunk_ids: Sequence[str], *, refresh: bool = False
    ) -> BulkWriteResult:
        """Delete exact validated chunk IDs through the write alias."""

        if len(chunk_ids) > self.settings.bulk_max_documents:
            raise self._document_error("Knowledge delete batch is too large")
        targets = await self._write_targets()
        operations: list[dict[str, object]] = []
        for chunk_id in chunk_ids:
            if not chunk_id or len(chunk_id) > 160 or any(
                character.isspace() for character in chunk_id
            ):
                raise self._document_error("Knowledge chunk ID is invalid")
            for target in targets:
                operations.append(
                    {
                        "delete": {
                            "_index": target,
                            "_id": chunk_id,
                        }
                    }
                )
        if not operations:
            return BulkWriteResult(0, 0, ())
        response = await self._call(
            "bulk_delete",
            lambda: self.client.bulk(body=operations, refresh=refresh),
        )
        failures = self._bulk_failures(response, allow_not_found=True)
        if failures:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.PARTIAL_FAILURE,
                f"Knowledge delete batch partially failed for {len(failures)} documents",
                retryable=True,
                failed_ids=failures,
            )
        return BulkWriteResult(len(chunk_ids), len(chunk_ids), ())

    async def delete_source(
        self,
        source_type: SourceType,
        source_id: str,
        source_version: str,
        *,
        refresh: bool = False,
    ) -> int:
        """Delete chunks for one exact source version without raw query input."""

        self._validate_exact_source(source_id, source_version)
        body = {
            "query": {
                "bool": {
                    "filter": fixed_metadata_filters(
                        source_types=[source_type],
                        source_id=source_id,
                        source_version=source_version,
                        active_only=False,
                    )
                }
            }
        }
        deleted = 0
        for target in await self._write_targets():
            response = await self._call(
                "delete_source",
                lambda target=target: self.client.delete_by_query(
                    index=target,
                    body=body,
                    conflicts="proceed",
                    refresh=refresh,
                ),
            )
            deleted += int(response.get("deleted", 0))
        return deleted

    async def count(self) -> int:
        """Count documents in an exact rebuild target."""

        if self._target_index is None:
            raise self._document_error("Exact target is required for rebuild count")
        response = await self._call(
            "count_target",
            lambda: self.client.count(index=self._target_index),
        )
        return int(response.get("count", 0))

    async def source_count(self) -> int:
        """Count distinct sources in an exact rebuild target."""

        if self._target_index is None:
            raise self._document_error(
                "Exact target is required for rebuild source count"
            )
        response = await self._call(
            "count_target_sources",
            lambda: self.client.search(
                index=self._target_index,
                body={
                    "size": 0,
                    "aggs": {
                        "sources": {
                            "cardinality": {
                                "field": "sourceId",
                                "precision_threshold": 40000,
                            }
                        }
                    },
                },
            ),
        )
        return int(
            response.get("aggregations", {}).get("sources", {}).get("value", 0)
        )

    async def refresh(self) -> None:
        """Refresh an exact generation before validation."""

        if self._target_index is None:
            raise self._document_error("Exact target is required for refresh")
        await self._call(
            "refresh_target",
            lambda: self.client.indices.refresh(index=self._target_index),
        )

    async def _write_targets(self) -> tuple[str, ...]:
        if self._target_index is not None:
            return (self._target_index,)
        if not self._mirror_live_generations:
            return (self.settings.write_alias,)
        response = await self._call(
            "resolve_live_generations",
            lambda: self.client.indices.get_alias(
                name=f"{self.settings.read_alias},{self.settings.write_alias}"
            ),
        )
        read_targets: list[str] = []
        write_targets: list[str] = []
        for index_name, value in response.items():
            aliases = value.get("aliases", {})
            if self.settings.read_alias in aliases:
                read_targets.append(index_name)
            write_alias = aliases.get(self.settings.write_alias)
            if (
                isinstance(write_alias, dict)
                and write_alias.get("is_write_index") is True
            ):
                write_targets.append(index_name)
        if len(read_targets) != 1 or len(write_targets) != 1:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.ALIAS_INVALID,
                "Live knowledge aliases must each resolve to one generation",
            )
        targets = tuple(sorted(set(read_targets + write_targets)))
        for target in targets:
            validate_physical_index_name(self.settings, target)
        return targets

    async def _call(
        self, operation: str, action: Callable[[], Awaitable[T]]
    ) -> T:
        started = time.monotonic()
        try:
            result = await action()
            self.metrics.operations.labels(operation, "success").inc()
            self.metrics.last_success.set_to_current_time()
            LOGGER.info(
                json.dumps(
                    {
                        "event": "knowledge_index_write",
                        "operation": operation,
                        "status": "success",
                    }
                )
            )
            return result
        except KnowledgeIndexError:
            self.metrics.operations.labels(operation, "failed").inc()
            raise
        except Exception as error:
            mapped = classify_open_search_error(error)
            self.metrics.operations.labels(operation, "failed").inc()
            LOGGER.warning(
                json.dumps(
                    {
                        "event": "knowledge_index_write",
                        "operation": operation,
                        "status": "failed",
                        "errorCode": mapped.code,
                        "retryable": mapped.retryable,
                    }
                )
            )
            raise mapped from error
        finally:
            self.metrics.operation_duration.labels(operation).observe(
                time.monotonic() - started
            )

    def _bulk_failures(
        self, response: dict[str, Any], *, allow_not_found: bool = False
    ) -> tuple[str, ...]:
        failures: list[str] = []
        for item in response.get("items", []):
            operation = next(iter(item.values()), {})
            status = int(operation.get("status", 500))
            if 200 <= status < 300 or (allow_not_found and status == 404):
                continue
            failures.append(str(operation.get("_id", "unknown")))
        return tuple(failures)

    def _validate_exact_source(self, source_id: str, source_version: str) -> None:
        if (
            not source_id
            or not source_version
            or len(source_id) > 160
            or len(source_version) > 80
            or any(character.isspace() for character in source_id + source_version)
        ):
            raise self._document_error("Knowledge source identity is invalid")

    def _document_error(self, message: str) -> KnowledgeIndexError:
        return KnowledgeIndexError(
            KnowledgeIndexErrorCode.DOCUMENT_INVALID,
            message,
        )

    def _validate_timestamp(self, value: datetime) -> None:
        if value.tzinfo is None or value.utcoffset() is None:
            raise self._document_error(
                "Knowledge invalidation timestamp must include a UTC offset"
            )


async def close_open_search_client(client: AsyncOpenSearch) -> None:
    """Close the async transport during FastAPI or CLI shutdown."""

    await client.close()


async def _run_cli(arguments: argparse.Namespace) -> int:
    client: AsyncOpenSearch | None = None
    try:
        settings = Settings.from_env().knowledge
        if arguments.command == "status" and not settings.enabled:
            print(
                json.dumps(
                    asdict(
                        KnowledgeIndexStatus(KnowledgeReadinessStatus.DISABLED)
                    ),
                    default=str,
                    sort_keys=True,
                )
            )
            return 0
        client = create_open_search_client(settings)
        admin = KnowledgeIndexAdmin(client, settings)
        if arguments.command == "status":
            result: object = asdict(await admin.status())
        elif arguments.command == "bootstrap":
            result = asdict(await admin.bootstrap())
        elif arguments.command == "create-generation":
            result = {"index": await admin.create_generation(), "status": "CREATED"}
        elif arguments.command in {"promote", "rollback"}:
            raise KnowledgeIndexError(
                KnowledgeIndexErrorCode.OPERATION_REJECTED,
                "Use the guarded knowledge-ingestion rebuild command",
            )
        else:
            raise ValueError("Unsupported knowledge index command")
        print(json.dumps(result, default=str, sort_keys=True))
        return 0
    except (KnowledgeIndexError, ValueError) as error:
        code = getattr(
            error,
            "code",
            KnowledgeIndexErrorCode.CONFIGURATION_INVALID,
        )
        print(json.dumps({"status": "FAILED", "errorCode": code}, default=str))
        return 2
    finally:
        if client is not None:
            await close_open_search_client(client)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Manage the agent knowledge index")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("status")
    subparsers.add_parser("bootstrap")
    subparsers.add_parser("create-generation")
    for command in ("promote", "rollback"):
        command_parser = subparsers.add_parser(command)
        command_parser.add_argument("--index", required=True)
    return parser


def main() -> None:
    raise SystemExit(asyncio.run(_run_cli(_parser().parse_args())))


if __name__ == "__main__":
    main()
