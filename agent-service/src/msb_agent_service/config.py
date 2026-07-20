from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlparse


@dataclass(frozen=True)
class KnowledgeIngestionSettings:
    """Holds validated durable listing-ingestion configuration."""

    enabled: bool = False
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_database: str = "agent"
    mysql_username: str = "agent"
    mysql_password: str | None = field(default=None, repr=False)
    mysql_pool_min_size: int = 1
    mysql_pool_max_size: int = 5
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic: str = "listing-knowledge-v1"
    kafka_group_id: str = "msb-agent-listing-knowledge-v1"
    kafka_client_id: str = "msb-agent-service"
    category_guidance_intake_enabled: bool = False
    category_guidance_kafka_topic: str = "category-guidance-v1"
    category_guidance_kafka_group_id: str = "msb-agent-category-guidance-v1"
    category_guidance_processing_enabled: bool = False
    category_guidance_retrieval_enabled: bool = False
    kafka_security_protocol: str = "PLAINTEXT"
    kafka_username: str | None = field(default=None, repr=False)
    kafka_password: str | None = field(default=None, repr=False)
    product_service_url: str | None = None
    product_service_token: str | None = field(default=None, repr=False)
    source_timeout_seconds: float = 5.0
    consumer_poll_timeout_ms: int = 1000
    consumer_batch_size: int = 50
    job_claim_batch_size: int = 10
    job_claim_seconds: int = 120
    job_max_attempts: int = 8
    job_retry_base_seconds: float = 5.0
    job_retry_max_seconds: float = 900.0
    processor_enabled: bool = False
    worker_poll_seconds: float = 1.0
    chunk_max_tokens: int = 800
    chunk_overlap_tokens: int = 80
    chunk_max_count: int = 16
    embedding_max_inputs: int = 16
    embedding_max_input_tokens: int = 8_000
    embedding_max_total_tokens: int = 32_000

    def validate(self) -> None:
        """Reject incomplete or unsafe enabled ingestion configuration."""

        _validate_host("AGENT_MYSQL_HOST", self.mysql_host)
        _validate_integer("AGENT_MYSQL_PORT", self.mysql_port, 1, 65535)
        _validate_identifier(
            "AGENT_MYSQL_DATABASE", self.mysql_database, maximum_length=64
        )
        _validate_identifier(
            "AGENT_MYSQL_USERNAME", self.mysql_username, maximum_length=128
        )
        _validate_integer(
            "AGENT_MYSQL_POOL_MIN_SIZE", self.mysql_pool_min_size, 1, 32
        )
        _validate_integer(
            "AGENT_MYSQL_POOL_MAX_SIZE", self.mysql_pool_max_size, 1, 64
        )
        if self.mysql_pool_min_size > self.mysql_pool_max_size:
            raise ValueError(
                "AGENT_MYSQL_POOL_MIN_SIZE must not exceed "
                "AGENT_MYSQL_POOL_MAX_SIZE"
            )
        _validate_bounded_text(
            "AGENT_KAFKA_BOOTSTRAP_SERVERS",
            self.kafka_bootstrap_servers,
            maximum_length=500,
        )
        _validate_identifier(
            "AGENT_KAFKA_TOPIC",
            self.kafka_topic,
            maximum_length=249,
            allowed_extra="._-",
        )
        _validate_identifier(
            "AGENT_KAFKA_GROUP_ID",
            self.kafka_group_id,
            maximum_length=255,
            allowed_extra="._-",
        )
        _validate_identifier(
            "AGENT_KAFKA_CLIENT_ID",
            self.kafka_client_id,
            maximum_length=255,
            allowed_extra="._-",
        )
        _validate_identifier(
            "AGENT_CATEGORY_GUIDANCE_KAFKA_TOPIC",
            self.category_guidance_kafka_topic,
            maximum_length=249,
            allowed_extra="._-",
        )
        _validate_identifier(
            "AGENT_CATEGORY_GUIDANCE_KAFKA_GROUP_ID",
            self.category_guidance_kafka_group_id,
            maximum_length=255,
            allowed_extra="._-",
        )
        if self.category_guidance_intake_enabled and not self.enabled:
            raise ValueError(
                "AGENT_KNOWLEDGE_INGESTION_ENABLED must be true when "
                "category guidance intake is enabled"
            )
        if self.category_guidance_processing_enabled and not self.processor_enabled:
            raise ValueError(
                "AGENT_KNOWLEDGE_PROCESSOR_ENABLED must be true when "
                "category guidance processing is enabled"
            )
        if self.kafka_security_protocol not in {
            "PLAINTEXT",
            "SSL",
            "SASL_PLAINTEXT",
            "SASL_SSL",
        }:
            raise ValueError(
                "AGENT_KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL, "
                "SASL_PLAINTEXT, or SASL_SSL"
            )
        sasl_enabled = self.kafka_security_protocol.startswith("SASL_")
        if sasl_enabled and (not self.kafka_username or not self.kafka_password):
            raise ValueError(
                "AGENT_KAFKA_USERNAME and AGENT_KAFKA_PASSWORD are required "
                "for SASL"
            )
        if bool(self.kafka_username) != bool(self.kafka_password):
            raise ValueError(
                "AGENT_KAFKA_USERNAME and AGENT_KAFKA_PASSWORD must be "
                "configured together"
            )
        _validate_number(
            "AGENT_KNOWLEDGE_SOURCE_TIMEOUT_SECONDS",
            self.source_timeout_seconds,
            0.1,
            120.0,
        )
        _validate_integer(
            "AGENT_KAFKA_CONSUMER_POLL_TIMEOUT_MS",
            self.consumer_poll_timeout_ms,
            100,
            30_000,
        )
        _validate_integer(
            "AGENT_KAFKA_CONSUMER_BATCH_SIZE",
            self.consumer_batch_size,
            1,
            500,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_JOB_CLAIM_BATCH_SIZE",
            self.job_claim_batch_size,
            1,
            100,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_JOB_CLAIM_SECONDS",
            self.job_claim_seconds,
            10,
            3600,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_JOB_MAX_ATTEMPTS",
            self.job_max_attempts,
            1,
            100,
        )
        _validate_number(
            "AGENT_KNOWLEDGE_JOB_RETRY_BASE_SECONDS",
            self.job_retry_base_seconds,
            0.1,
            3600.0,
        )
        _validate_number(
            "AGENT_KNOWLEDGE_JOB_RETRY_MAX_SECONDS",
            self.job_retry_max_seconds,
            self.job_retry_base_seconds,
            86_400.0,
        )
        _validate_number(
            "AGENT_KNOWLEDGE_WORKER_POLL_SECONDS",
            self.worker_poll_seconds,
            0.1,
            60.0,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_CHUNK_MAX_TOKENS",
            self.chunk_max_tokens,
            64,
            8_000,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_CHUNK_OVERLAP_TOKENS",
            self.chunk_overlap_tokens,
            0,
            2_000,
        )
        if self.chunk_overlap_tokens >= self.chunk_max_tokens:
            raise ValueError(
                "AGENT_KNOWLEDGE_CHUNK_OVERLAP_TOKENS must be less than "
                "AGENT_KNOWLEDGE_CHUNK_MAX_TOKENS"
            )
        _validate_integer(
            "AGENT_KNOWLEDGE_CHUNK_MAX_COUNT",
            self.chunk_max_count,
            2,
            100,
        )
        _validate_integer(
            "AGENT_EMBEDDING_MAX_INPUTS",
            self.embedding_max_inputs,
            1,
            100,
        )
        _validate_integer(
            "AGENT_EMBEDDING_MAX_INPUT_TOKENS",
            self.embedding_max_input_tokens,
            64,
            8_192,
        )
        _validate_integer(
            "AGENT_EMBEDDING_MAX_TOTAL_TOKENS",
            self.embedding_max_total_tokens,
            self.embedding_max_input_tokens,
            1_000_000,
        )
        if self.chunk_max_count > self.embedding_max_inputs:
            raise ValueError(
                "AGENT_KNOWLEDGE_CHUNK_MAX_COUNT must not exceed "
                "AGENT_EMBEDDING_MAX_INPUTS"
            )
        if self.chunk_max_tokens > self.embedding_max_input_tokens:
            raise ValueError(
                "AGENT_KNOWLEDGE_CHUNK_MAX_TOKENS must not exceed "
                "AGENT_EMBEDDING_MAX_INPUT_TOKENS"
            )
        if not self.enabled:
            return
        if not self.mysql_password:
            raise ValueError(
                "AGENT_MYSQL_PASSWORD is required when knowledge ingestion is enabled"
            )
        if self.product_service_url is None:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_URL is required when knowledge ingestion "
                "is enabled"
            )
        parsed = urlparse(self.product_service_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_URL must be an absolute http or https URL"
            )
        if parsed.username or parsed.password:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_URL must not contain credentials"
            )
        if parsed.query or parsed.fragment:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_URL must not contain query or fragment data"
            )
        if not self.product_service_token:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_TOKEN is required when knowledge ingestion "
                "is enabled"
            )


@dataclass(frozen=True)
class AgentPersistenceSettings:
    """Holds independently gated Agent Service conversation persistence settings."""

    enabled: bool = False
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_database: str = "agent"
    mysql_username: str = "agent"
    mysql_password: str | None = field(default=None, repr=False)
    mysql_pool_min_size: int = 1
    mysql_pool_max_size: int = 5
    question_max_characters: int = 8_000
    assistant_max_characters: int = 12_000
    maximum_failed_retries: int = 1
    message_retention_days: int = 90
    audit_retention_days: int = 365

    def validate(self) -> None:
        """Reject unsafe persistence bounds and incomplete enabled credentials."""

        _validate_host("AGENT_MYSQL_HOST", self.mysql_host)
        _validate_integer("AGENT_MYSQL_PORT", self.mysql_port, 1, 65535)
        _validate_identifier(
            "AGENT_MYSQL_DATABASE", self.mysql_database, maximum_length=64
        )
        _validate_identifier(
            "AGENT_MYSQL_USERNAME", self.mysql_username, maximum_length=128
        )
        _validate_integer(
            "AGENT_MYSQL_POOL_MIN_SIZE", self.mysql_pool_min_size, 1, 32
        )
        _validate_integer(
            "AGENT_MYSQL_POOL_MAX_SIZE", self.mysql_pool_max_size, 1, 64
        )
        if self.mysql_pool_min_size > self.mysql_pool_max_size:
            raise ValueError(
                "AGENT_MYSQL_POOL_MIN_SIZE must not exceed "
                "AGENT_MYSQL_POOL_MAX_SIZE"
            )
        _validate_integer(
            "AGENT_QUESTION_MAX_CHARACTERS",
            self.question_max_characters,
            1,
            8_000,
        )
        _validate_integer(
            "AGENT_ASSISTANT_MAX_CHARACTERS",
            self.assistant_max_characters,
            self.question_max_characters,
            12_000,
        )
        _validate_integer(
            "AGENT_MAXIMUM_FAILED_RETRIES",
            self.maximum_failed_retries,
            0,
            1,
        )
        _validate_integer(
            "AGENT_MESSAGE_RETENTION_DAYS",
            self.message_retention_days,
            1,
            90,
        )
        _validate_integer(
            "AGENT_AUDIT_RETENTION_DAYS",
            self.audit_retention_days,
            self.message_retention_days,
            365,
        )
        if self.enabled and not self.mysql_password:
            raise ValueError(
                "AGENT_MYSQL_PASSWORD is required when agent persistence is enabled"
            )


@dataclass(frozen=True)
class AgentApiSettings:
    """Holds the disabled-by-default authenticated customer-service API boundary."""

    enabled: bool = False
    auth_service_url: str | None = None
    product_service_url: str | None = None
    product_service_token: str | None = field(default=None, repr=False)
    dependency_timeout_seconds: float = 5.0
    message_page_default_limit: int = 50
    message_page_max_limit: int = 100

    def validate(self, *, persistence_enabled: bool) -> None:
        """Reject an enabled API that cannot authenticate or persist safely."""

        _validate_number(
            "AGENT_API_DEPENDENCY_TIMEOUT_SECONDS",
            self.dependency_timeout_seconds,
            0.1,
            30.0,
        )
        _validate_integer(
            "AGENT_MESSAGE_PAGE_DEFAULT_LIMIT",
            self.message_page_default_limit,
            1,
            100,
        )
        _validate_integer(
            "AGENT_MESSAGE_PAGE_MAX_LIMIT",
            self.message_page_max_limit,
            self.message_page_default_limit,
            100,
        )
        if not self.enabled:
            return
        if not persistence_enabled:
            raise ValueError(
                "AGENT_PERSISTENCE_ENABLED must be true when "
                "AGENT_CUSTOMER_SERVICE_API_ENABLED is true"
            )
        _validate_service_url("AUTH_SERVICE_URL", self.auth_service_url)
        _validate_service_url(
            "AGENT_PRODUCT_SERVICE_URL",
            self.product_service_url,
        )
        if not self.product_service_token:
            raise ValueError(
                "AGENT_PRODUCT_SERVICE_TOKEN is required when the customer-service "
                "API is enabled"
            )


@dataclass(frozen=True)
class ListingProposalApiSettings:
    """Holds the independently default-off AI-LIST-02A API and generation gates."""

    enabled: bool = False
    auth_service_url: str | None = None
    dependency_timeout_seconds: float = 5.0
    kill_switch_enabled: bool = False
    orchestration_enabled: bool = False
    media_tool_enabled: bool = False
    product_service_url: str | None = None
    product_service_token: str | None = field(default=None, repr=False)
    multimodal_provider_enabled: bool = False
    multimodal_provider_configured: bool = False

    def validate(self, *, persistence_enabled: bool) -> None:
        """Reject enabled API or generation gates without their required boundary."""

        _validate_number(
            "AGENT_LISTING_PROPOSAL_DEPENDENCY_TIMEOUT_SECONDS",
            self.dependency_timeout_seconds,
            0.1,
            30.0,
        )
        if self.enabled:
            if not persistence_enabled:
                raise ValueError(
                    "AGENT_PERSISTENCE_ENABLED must be true when "
                    "AGENT_LISTING_PROPOSAL_API_ENABLED is true"
                )
            _validate_service_url("AUTH_SERVICE_URL", self.auth_service_url)
        if self.media_tool_enabled:
            _validate_service_url(
                "AGENT_PRODUCT_SERVICE_URL",
                self.product_service_url,
            )
            if not self.product_service_token:
                raise ValueError(
                    "AGENT_PRODUCT_SERVICE_TOKEN is required when the listing "
                    "proposal media tool is enabled"
                )
        if (
            self.multimodal_provider_enabled
            and not self.multimodal_provider_configured
        ):
            raise ValueError(
                "AGENT_LISTING_PROPOSAL_PROVIDER_CONFIGURED must be true when "
                "the multimodal provider gate is enabled"
            )


@dataclass(frozen=True)
class KnowledgeIndexSettings:
    """Holds validated OpenSearch knowledge-index configuration."""

    enabled: bool = False
    url: str | None = None
    username: str | None = field(default=None, repr=False)
    password: str | None = field(default=None, repr=False)
    verify_certs: bool = True
    request_timeout_seconds: float = 3.0
    max_retries: int = 2
    index_prefix: str = "msb-agent-knowledge"
    read_alias: str = "msb-agent-knowledge-read"
    write_alias: str = "msb-agent-knowledge-write"
    embedding_provider: str | None = None
    embedding_model: str | None = None
    embedding_dimensions: int | None = None
    shards: int = 1
    replicas: int = 0
    bulk_max_documents: int = 500
    bulk_max_bytes: int = 5_000_000

    def validate(self) -> None:
        """Reject unsafe or incomplete enabled configuration before I/O."""

        _validate_index_name("AGENT_KNOWLEDGE_INDEX_PREFIX", self.index_prefix)
        _validate_index_name("AGENT_KNOWLEDGE_READ_ALIAS", self.read_alias)
        _validate_index_name("AGENT_KNOWLEDGE_WRITE_ALIAS", self.write_alias)
        _validate_number(
            "AGENT_OPENSEARCH_REQUEST_TIMEOUT_SECONDS",
            self.request_timeout_seconds,
            0.1,
            120.0,
        )
        _validate_integer("AGENT_OPENSEARCH_MAX_RETRIES", self.max_retries, 0, 5)
        _validate_integer("AGENT_KNOWLEDGE_SHARDS", self.shards, 1, 32)
        _validate_integer("AGENT_KNOWLEDGE_REPLICAS", self.replicas, 0, 16)
        _validate_integer(
            "AGENT_KNOWLEDGE_BULK_MAX_DOCUMENTS",
            self.bulk_max_documents,
            1,
            2_000,
        )
        _validate_integer(
            "AGENT_KNOWLEDGE_BULK_MAX_BYTES",
            self.bulk_max_bytes,
            1_024,
            50_000_000,
        )
        if len({self.index_prefix, self.read_alias, self.write_alias}) != 3:
            raise ValueError("knowledge index prefix and aliases must be distinct")
        if "msb-public-listings" in {
            self.index_prefix,
            self.read_alias,
            self.write_alias,
        }:
            raise ValueError(
                "knowledge index names must not use the Product Service index"
            )
        if not self.enabled:
            return
        if self.url is None:
            raise ValueError("AGENT_OPENSEARCH_URL is required when knowledge is enabled")
        parsed = urlparse(self.url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("AGENT_OPENSEARCH_URL must be an absolute http or https URL")
        if parsed.username or parsed.password:
            raise ValueError("AGENT_OPENSEARCH_URL must not contain credentials")
        if parsed.query or parsed.fragment:
            raise ValueError("AGENT_OPENSEARCH_URL must not contain query or fragment data")
        if bool(self.username) != bool(self.password):
            raise ValueError("OpenSearch username and password must be configured together")
        if not self.embedding_provider:
            raise ValueError(
                "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER is required when knowledge is enabled"
            )
        _validate_metadata_identity(
            "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER",
            self.embedding_provider,
        )
        if not self.embedding_model:
            raise ValueError(
                "AGENT_KNOWLEDGE_EMBEDDING_MODEL is required when knowledge is enabled"
            )
        _validate_metadata_identity(
            "AGENT_KNOWLEDGE_EMBEDDING_MODEL",
            self.embedding_model,
        )
        if self.embedding_dimensions is None:
            raise ValueError(
                "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS is required when knowledge is enabled"
            )
        _validate_integer(
            "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS",
            self.embedding_dimensions,
            1,
            16_000,
        )


@dataclass(frozen=True)
class Settings:
    """Holds validated runtime configuration without exposing secret values."""

    openai_api_key: str | None = field(default=None, repr=False)
    openai_model: str = "gpt-5-mini"
    openai_timeout_seconds: float = 30.0
    openai_max_retries: int = 2
    port: int = 8086
    knowledge: KnowledgeIndexSettings = field(default_factory=KnowledgeIndexSettings)
    knowledge_ingestion: KnowledgeIngestionSettings = field(
        default_factory=KnowledgeIngestionSettings
    )
    agent_persistence: AgentPersistenceSettings = field(
        default_factory=AgentPersistenceSettings
    )
    agent_api: AgentApiSettings = field(default_factory=AgentApiSettings)
    listing_proposal_api: ListingProposalApiSettings = field(
        default_factory=ListingProposalApiSettings
    )

    @classmethod
    def from_env(cls) -> "Settings":
        """Build configuration from process environment variables only."""

        api_key = os.getenv("OPENAI_API_KEY", "").strip() or None
        model = os.getenv("OPENAI_MODEL", "gpt-5-mini").strip()
        if not model:
            raise ValueError("OPENAI_MODEL must not be blank")

        timeout = _positive_float("OPENAI_TIMEOUT_SECONDS", 30.0)
        max_retries = _bounded_int("OPENAI_MAX_RETRIES", 2, minimum=0, maximum=10)
        port = _bounded_int("PORT", 8086, minimum=1, maximum=65535)
        knowledge = KnowledgeIndexSettings(
            enabled=_boolean("AGENT_KNOWLEDGE_ENABLED", False),
            url=_optional_text("AGENT_OPENSEARCH_URL"),
            username=_optional_text("AGENT_OPENSEARCH_USERNAME"),
            password=_optional_text("AGENT_OPENSEARCH_PASSWORD"),
            verify_certs=_boolean("AGENT_OPENSEARCH_VERIFY_CERTS", True),
            request_timeout_seconds=_bounded_float(
                "AGENT_OPENSEARCH_REQUEST_TIMEOUT_SECONDS", 3.0, 0.1, 120.0
            ),
            max_retries=_bounded_int(
                "AGENT_OPENSEARCH_MAX_RETRIES", 2, minimum=0, maximum=5
            ),
            index_prefix=os.getenv(
                "AGENT_KNOWLEDGE_INDEX_PREFIX", "msb-agent-knowledge"
            ).strip(),
            read_alias=os.getenv(
                "AGENT_KNOWLEDGE_READ_ALIAS", "msb-agent-knowledge-read"
            ).strip(),
            write_alias=os.getenv(
                "AGENT_KNOWLEDGE_WRITE_ALIAS", "msb-agent-knowledge-write"
            ).strip(),
            embedding_provider=_optional_text(
                "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER"
            ),
            embedding_model=_optional_text("AGENT_KNOWLEDGE_EMBEDDING_MODEL"),
            embedding_dimensions=_optional_bounded_int(
                "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS", 1, 16_000
            ),
            shards=_bounded_int("AGENT_KNOWLEDGE_SHARDS", 1, 1, 32),
            replicas=_bounded_int("AGENT_KNOWLEDGE_REPLICAS", 0, 0, 16),
            bulk_max_documents=_bounded_int(
                "AGENT_KNOWLEDGE_BULK_MAX_DOCUMENTS", 500, 1, 2_000
            ),
            bulk_max_bytes=_bounded_int(
                "AGENT_KNOWLEDGE_BULK_MAX_BYTES",
                5_000_000,
                1_024,
                50_000_000,
            ),
        )
        knowledge.validate()
        knowledge_ingestion = KnowledgeIngestionSettings(
            enabled=_boolean("AGENT_KNOWLEDGE_INGESTION_ENABLED", False),
            mysql_host=os.getenv("AGENT_MYSQL_HOST", "localhost").strip(),
            mysql_port=_bounded_int("AGENT_MYSQL_PORT", 3306, 1, 65535),
            mysql_database=os.getenv("AGENT_MYSQL_DATABASE", "agent").strip(),
            mysql_username=os.getenv("AGENT_MYSQL_USERNAME", "agent").strip(),
            mysql_password=_optional_text("AGENT_MYSQL_PASSWORD"),
            mysql_pool_min_size=_bounded_int(
                "AGENT_MYSQL_POOL_MIN_SIZE", 1, 1, 32
            ),
            mysql_pool_max_size=_bounded_int(
                "AGENT_MYSQL_POOL_MAX_SIZE", 5, 1, 64
            ),
            kafka_bootstrap_servers=os.getenv(
                "AGENT_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"
            ).strip(),
            kafka_topic=os.getenv(
                "AGENT_KAFKA_TOPIC", "listing-knowledge-v1"
            ).strip(),
            kafka_group_id=os.getenv(
                "AGENT_KAFKA_GROUP_ID", "msb-agent-listing-knowledge-v1"
            ).strip(),
            kafka_client_id=os.getenv(
                "AGENT_KAFKA_CLIENT_ID", "msb-agent-service"
            ).strip(),
            category_guidance_intake_enabled=_boolean(
                "AGENT_CATEGORY_GUIDANCE_INTAKE_ENABLED", False
            ),
            category_guidance_kafka_topic=os.getenv(
                "AGENT_CATEGORY_GUIDANCE_KAFKA_TOPIC",
                "category-guidance-v1",
            ).strip(),
            category_guidance_kafka_group_id=os.getenv(
                "AGENT_CATEGORY_GUIDANCE_KAFKA_GROUP_ID",
                "msb-agent-category-guidance-v1",
            ).strip(),
            category_guidance_processing_enabled=_boolean(
                "AGENT_CATEGORY_GUIDANCE_PROCESSING_ENABLED", False
            ),
            category_guidance_retrieval_enabled=_boolean(
                "AGENT_CATEGORY_GUIDANCE_RETRIEVAL_ENABLED", False
            ),
            kafka_security_protocol=os.getenv(
                "AGENT_KAFKA_SECURITY_PROTOCOL", "PLAINTEXT"
            )
            .strip()
            .upper(),
            kafka_username=_optional_text("AGENT_KAFKA_USERNAME"),
            kafka_password=_optional_text("AGENT_KAFKA_PASSWORD"),
            product_service_url=_optional_text("AGENT_PRODUCT_SERVICE_URL"),
            product_service_token=_optional_text("AGENT_PRODUCT_SERVICE_TOKEN"),
            source_timeout_seconds=_bounded_float(
                "AGENT_KNOWLEDGE_SOURCE_TIMEOUT_SECONDS", 5.0, 0.1, 120.0
            ),
            consumer_poll_timeout_ms=_bounded_int(
                "AGENT_KAFKA_CONSUMER_POLL_TIMEOUT_MS", 1000, 100, 30_000
            ),
            consumer_batch_size=_bounded_int(
                "AGENT_KAFKA_CONSUMER_BATCH_SIZE", 50, 1, 500
            ),
            job_claim_batch_size=_bounded_int(
                "AGENT_KNOWLEDGE_JOB_CLAIM_BATCH_SIZE", 10, 1, 100
            ),
            job_claim_seconds=_bounded_int(
                "AGENT_KNOWLEDGE_JOB_CLAIM_SECONDS", 120, 10, 3600
            ),
            job_max_attempts=_bounded_int(
                "AGENT_KNOWLEDGE_JOB_MAX_ATTEMPTS", 8, 1, 100
            ),
            job_retry_base_seconds=_bounded_float(
                "AGENT_KNOWLEDGE_JOB_RETRY_BASE_SECONDS",
                5.0,
                0.1,
                3600.0,
            ),
            job_retry_max_seconds=_bounded_float(
                "AGENT_KNOWLEDGE_JOB_RETRY_MAX_SECONDS",
                900.0,
                0.1,
                86_400.0,
            ),
            processor_enabled=_boolean(
                "AGENT_KNOWLEDGE_PROCESSOR_ENABLED", False
            ),
            worker_poll_seconds=_bounded_float(
                "AGENT_KNOWLEDGE_WORKER_POLL_SECONDS",
                1.0,
                0.1,
                60.0,
            ),
            chunk_max_tokens=_bounded_int(
                "AGENT_KNOWLEDGE_CHUNK_MAX_TOKENS",
                800,
                64,
                8_000,
            ),
            chunk_overlap_tokens=_bounded_int(
                "AGENT_KNOWLEDGE_CHUNK_OVERLAP_TOKENS",
                80,
                0,
                2_000,
            ),
            chunk_max_count=_bounded_int(
                "AGENT_KNOWLEDGE_CHUNK_MAX_COUNT",
                16,
                2,
                100,
            ),
            embedding_max_inputs=_bounded_int(
                "AGENT_EMBEDDING_MAX_INPUTS",
                16,
                1,
                100,
            ),
            embedding_max_input_tokens=_bounded_int(
                "AGENT_EMBEDDING_MAX_INPUT_TOKENS",
                8_000,
                64,
                8_192,
            ),
            embedding_max_total_tokens=_bounded_int(
                "AGENT_EMBEDDING_MAX_TOTAL_TOKENS",
                32_000,
                64,
                1_000_000,
            ),
        )
        knowledge_ingestion.validate()
        agent_persistence = AgentPersistenceSettings(
            enabled=_boolean("AGENT_PERSISTENCE_ENABLED", False),
            mysql_host=os.getenv("AGENT_MYSQL_HOST", "localhost").strip(),
            mysql_port=_bounded_int("AGENT_MYSQL_PORT", 3306, 1, 65535),
            mysql_database=os.getenv("AGENT_MYSQL_DATABASE", "agent").strip(),
            mysql_username=os.getenv("AGENT_MYSQL_USERNAME", "agent").strip(),
            mysql_password=_optional_text("AGENT_MYSQL_PASSWORD"),
            mysql_pool_min_size=_bounded_int(
                "AGENT_MYSQL_POOL_MIN_SIZE", 1, 1, 32
            ),
            mysql_pool_max_size=_bounded_int(
                "AGENT_MYSQL_POOL_MAX_SIZE", 5, 1, 64
            ),
            question_max_characters=_bounded_int(
                "AGENT_QUESTION_MAX_CHARACTERS", 8_000, 1, 8_000
            ),
            assistant_max_characters=_bounded_int(
                "AGENT_ASSISTANT_MAX_CHARACTERS", 12_000, 1, 12_000
            ),
            maximum_failed_retries=_bounded_int(
                "AGENT_MAXIMUM_FAILED_RETRIES", 1, 0, 1
            ),
            message_retention_days=_bounded_int(
                "AGENT_MESSAGE_RETENTION_DAYS", 90, 1, 90
            ),
            audit_retention_days=_bounded_int(
                "AGENT_AUDIT_RETENTION_DAYS", 365, 1, 365
            ),
        )
        agent_persistence.validate()
        agent_api = AgentApiSettings(
            enabled=_boolean("AGENT_CUSTOMER_SERVICE_API_ENABLED", False),
            auth_service_url=_optional_text("AUTH_SERVICE_URL"),
            product_service_url=_optional_text("AGENT_PRODUCT_SERVICE_URL"),
            product_service_token=_optional_text("AGENT_PRODUCT_SERVICE_TOKEN"),
            dependency_timeout_seconds=_bounded_float(
                "AGENT_API_DEPENDENCY_TIMEOUT_SECONDS",
                5.0,
                0.1,
                30.0,
            ),
            message_page_default_limit=_bounded_int(
                "AGENT_MESSAGE_PAGE_DEFAULT_LIMIT",
                50,
                1,
                100,
            ),
            message_page_max_limit=_bounded_int(
                "AGENT_MESSAGE_PAGE_MAX_LIMIT",
                100,
                1,
                100,
            ),
        )
        agent_api.validate(persistence_enabled=agent_persistence.enabled)
        listing_proposal_api = ListingProposalApiSettings(
            enabled=_boolean("AGENT_LISTING_PROPOSAL_API_ENABLED", False),
            auth_service_url=_optional_text("AUTH_SERVICE_URL"),
            dependency_timeout_seconds=_bounded_float(
                "AGENT_LISTING_PROPOSAL_DEPENDENCY_TIMEOUT_SECONDS",
                5.0,
                0.1,
                30.0,
            ),
            kill_switch_enabled=_boolean(
                "AGENT_LISTING_PROPOSAL_KILL_SWITCH_ENABLED",
                False,
            ),
            orchestration_enabled=_boolean(
                "AGENT_LISTING_PROPOSAL_ORCHESTRATION_ENABLED",
                False,
            ),
            media_tool_enabled=_boolean(
                "AGENT_LISTING_MEDIA_TOOL_ENABLED",
                False,
            ),
            product_service_url=_optional_text("AGENT_PRODUCT_SERVICE_URL"),
            product_service_token=_optional_text("AGENT_PRODUCT_SERVICE_TOKEN"),
            multimodal_provider_enabled=_boolean(
                "AGENT_LISTING_PROPOSAL_PROVIDER_ENABLED",
                False,
            ),
            multimodal_provider_configured=_boolean(
                "AGENT_LISTING_PROPOSAL_PROVIDER_CONFIGURED",
                False,
            ),
        )
        listing_proposal_api.validate(
            persistence_enabled=agent_persistence.enabled
        )
        if knowledge_ingestion.processor_enabled:
            if not knowledge_ingestion.enabled:
                raise ValueError(
                    "AGENT_KNOWLEDGE_INGESTION_ENABLED must be true when "
                    "AGENT_KNOWLEDGE_PROCESSOR_ENABLED is true"
                )
            if not knowledge.enabled:
                raise ValueError(
                    "AGENT_KNOWLEDGE_ENABLED must be true when "
                    "AGENT_KNOWLEDGE_PROCESSOR_ENABLED is true"
                )
            if not api_key:
                raise ValueError(
                    "OPENAI_API_KEY is required when the knowledge processor "
                    "is enabled"
                )
            if (
                knowledge.embedding_provider != "openai"
                or knowledge.embedding_model != "text-embedding-3-small"
                or knowledge.embedding_dimensions != 1536
            ):
                raise ValueError(
                    "AI-RAG-02C requires openai/text-embedding-3-small/1536 "
                    "embedding identity"
                )
        return cls(
            openai_api_key=api_key,
            openai_model=model,
            openai_timeout_seconds=timeout,
            openai_max_retries=max_retries,
            port=port,
            knowledge=knowledge,
            knowledge_ingestion=knowledge_ingestion,
            agent_persistence=agent_persistence,
            agent_api=agent_api,
            listing_proposal_api=listing_proposal_api,
        )

    @property
    def openai_configured(self) -> bool:
        return self.openai_api_key is not None


def _positive_float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    try:
        value = default if raw_value is None else float(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if value <= 0:
        raise ValueError(f"{name} must be greater than zero")
    return value


def _bounded_int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw_value = os.getenv(name)
    try:
        value = default if raw_value is None else int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _optional_bounded_int(name: str, minimum: int, maximum: int) -> int | None:
    raw_value = os.getenv(name)
    if raw_value is None or not raw_value.strip():
        return None
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _bounded_float(
    name: str, default: float, minimum: float, maximum: float
) -> float:
    raw_value = os.getenv(name)
    try:
        value = default if raw_value is None else float(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _boolean(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    normalized = raw_value.strip().lower()
    if normalized in {"true", "1", "yes", "on"}:
        return True
    if normalized in {"false", "0", "no", "off"}:
        return False
    raise ValueError(f"{name} must be true or false")


def _optional_text(name: str) -> str | None:
    value = os.getenv(name, "").strip()
    return value or None


def _validate_index_name(name: str, value: str) -> None:
    if not value:
        raise ValueError(f"{name} must not be blank")
    if len(value) > 120:
        raise ValueError(f"{name} must not exceed 120 characters")
    if not value[0].isalnum() or any(
        character not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for character in value
    ):
        raise ValueError(
            f"{name} must contain only lowercase letters, digits, hyphens, and underscores"
        )


def _validate_integer(name: str, value: int, minimum: int, maximum: int) -> None:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")


def _validate_number(
    name: str, value: float, minimum: float, maximum: float
) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number")
    if not minimum <= float(value) <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")


def _validate_metadata_identity(name: str, value: str) -> None:
    if len(value) > 160:
        raise ValueError(f"{name} must not exceed 160 characters")
    if value != value.strip() or any(ord(character) < 32 for character in value):
        raise ValueError(f"{name} contains unsafe characters")


def _validate_host(name: str, value: str) -> None:
    _validate_bounded_text(name, value, maximum_length=253)
    if any(character in value for character in "/?#@"):
        raise ValueError(f"{name} must be a host name or IP address")


def _validate_bounded_text(name: str, value: str, maximum_length: int) -> None:
    if not value or value != value.strip():
        raise ValueError(f"{name} must not be blank or padded")
    if len(value) > maximum_length:
        raise ValueError(f"{name} must not exceed {maximum_length} characters")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ValueError(f"{name} contains unsafe characters")


def _validate_identifier(
    name: str,
    value: str,
    *,
    maximum_length: int,
    allowed_extra: str = "_-",
) -> None:
    _validate_bounded_text(name, value, maximum_length)
    if any(not character.isalnum() and character not in allowed_extra for character in value):
        raise ValueError(f"{name} contains unsupported characters")


def _validate_service_url(name: str, value: str | None) -> None:
    if value is None:
        raise ValueError(f"{name} is required")
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(f"{name} must be an absolute http or https URL")
    if parsed.username or parsed.password:
        raise ValueError(f"{name} must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ValueError(f"{name} must not contain query or fragment data")
