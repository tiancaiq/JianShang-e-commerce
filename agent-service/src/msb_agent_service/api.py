from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import json
import re
import time
import uuid
import logging
from typing import AsyncIterator, Awaitable, Callable

from fastapi import FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from opensearchpy import AsyncOpenSearch
from prometheus_client import CollectorRegistry, make_asgi_app

from .agent_persistence import (
    AgentPersistenceError,
    AgentPersistenceRepository,
)
from .agent_persistence_metrics import AgentPersistenceMetrics
from .config import AgentPersistenceSettings
from .config import Settings
from .customer_service_api import (
    ActorIdentityClient,
    AgentApiError,
    AgentApiErrorCode,
    AgentCustomerService,
    DeferredQuestionAnswerer,
    ListingEligibilityClient,
    QuestionAnswerer,
    decode_cursor,
    map_persistence_error,
)
from .customer_service_metrics import (
    CREATE_SESSION_VALIDATION_ROUTE,
    VALIDATION_ERROR_CATEGORIES,
    VALIDATION_ERROR_RESULT,
    VALIDATION_FIELD_CATEGORIES,
    CustomerServiceApiMetrics,
)
from .customer_service_runtime import (
    CustomerServiceRuntime,
    build_customer_service_runtime,
)
from .discovery_api import (
    CreateDiscoveryExclusionRequest,
    CreateDiscoveryExclusionResponse,
    CreateDiscoverySessionRequest,
    DiscoveryApiError,
    DiscoveryApiErrorCode,
    DiscoveryHistoryPage,
    DiscoveryProgressStage,
    DiscoverySessionResponse,
    MarketplaceDiscoveryService,
    SendDiscoveryMessageRequest,
    SendDiscoveryMessageResponse,
    StopDiscoveryResponse,
    RetryDiscoveryResponseRequest,
)
from .discovery_persistence import DiscoveryPersistenceRepository
from .discovery_embedding_runtime import (
    DiscoveryEmbeddingRuntime,
    DiscoveryEmbeddingRuntimeStatus,
)
from .knowledge_ingestion_runtime import (
    KnowledgeIngestionRuntime,
    KnowledgeIngestionStatus,
)
from .knowledge_index import (
    KnowledgeIndexAdmin,
    KnowledgeReadinessStatus,
    close_open_search_client,
)
from .knowledge_index_client import create_open_search_client
from .knowledge_index_metrics import KnowledgeIndexMetrics
from .listing_proposal_review import (
    CreateListingProposalRequest,
    ListingProposalApiError,
    ListingProposalApiErrorCode,
    ListingProposalGenerator,
    ListingProposalMetrics,
    ListingProposalRepository,
    ListingProposalRepositoryProtocol,
    ListingProposalResponse,
    ListingProposalReviewService,
    listing_proposal_retention_loop,
)
from .marketplace_discovery import MarketplaceDiscoveryOrchestrator
from .marketplace_discovery_runtime import (
    MarketplaceDiscoveryRuntime,
    build_marketplace_discovery_runtime,
)
from .marketplace_agent_v2.api import (
    MarketplaceAgentV2ApiError,
    MarketplaceAgentV2ApiErrorCode,
    stream_event as marketplace_v2_stream_event,
)
from .marketplace_agent_v2.persistence import MarketplaceAgentV2Persistence
from .marketplace_agent_v2.runtime import (
    MarketplaceAgentV2Runtime,
    build_marketplace_agent_v2_runtime,
)
from .marketplace_agent_v2.service import (
    CreateMarketplaceAgentV2SessionRequest,
    MarketplaceAgentV2HistoryPage,
    MarketplaceAgentV2Service,
    MarketplaceAgentV2SessionResponse,
    RetryMarketplaceAgentV2ResponseRequest,
    SendMarketplaceAgentV2MessageRequest,
    SendMarketplaceAgentV2MessageResponse,
    StopMarketplaceAgentV2Response,
)
from .schemas import (
    AgentMessagePageResponse,
    AgentSessionResponse,
    ApiErrorBody,
    ApiErrorDetail,
    ApiErrorEnvelope,
    CreateAgentSessionRequest,
    HealthResponse,
    ReadinessResponse,
    SendAgentMessageRequest,
    SendAgentMessageResponse,
    Ulid,
)

KnowledgeReadinessProbe = Callable[[], Awaitable[KnowledgeReadinessStatus]]
KnowledgeIngestionRuntimeFactory = Callable[
    [Settings, KnowledgeIndexMetrics],
    Awaitable[KnowledgeIngestionRuntime],
]
AgentPersistenceRepositoryFactory = Callable[
    [AgentPersistenceSettings, AgentPersistenceMetrics],
    Awaitable[AgentPersistenceRepository],
]
ListingProposalRepositoryFactory = Callable[
    [AgentPersistenceRepository, ListingProposalMetrics],
    Awaitable[ListingProposalRepositoryProtocol],
]
CustomerServiceRuntimeFactory = Callable[
    [
        Settings,
        AgentPersistenceRepository,
        AsyncOpenSearch,
        CollectorRegistry,
    ],
    CustomerServiceRuntime,
]
MarketplaceDiscoveryRuntimeFactory = Callable[
    [Settings],
    MarketplaceDiscoveryRuntime,
]
MarketplaceAgentV2RuntimeFactory = Callable[[Settings], MarketplaceAgentV2Runtime]
DiscoveryEmbeddingRuntimeFactory = Callable[
    [Settings, CollectorRegistry],
    Awaitable[DiscoveryEmbeddingRuntime],
]
LOGGER = logging.getLogger(__name__)
_CREATE_SESSION_PATH = "/api/v1/agent/sessions"
_MAX_DIAGNOSTIC_ERRORS = 8
_MAX_PUBLIC_VALIDATION_ERRORS = 20
_FIELD_CATEGORY_ORDER = {
    category: index for index, category in enumerate(VALIDATION_FIELD_CATEGORIES)
}
_ERROR_CATEGORY_ORDER = {
    category: index for index, category in enumerate(VALIDATION_ERROR_CATEGORIES)
}


def create_app(
    settings: Settings | None = None,
    knowledge_readiness_probe: KnowledgeReadinessProbe | None = None,
    ingestion_runtime_factory: KnowledgeIngestionRuntimeFactory | None = None,
    persistence_repository_factory: AgentPersistenceRepositoryFactory | None = None,
    identity_client: ActorIdentityClient | None = None,
    listing_client: ListingEligibilityClient | None = None,
    question_answerer: QuestionAnswerer | None = None,
    customer_service_runtime_factory: CustomerServiceRuntimeFactory | None = None,
    listing_proposal_repository_factory: (
        ListingProposalRepositoryFactory | None
    ) = None,
    listing_proposal_generator: ListingProposalGenerator | None = None,
    discovery_orchestrator: MarketplaceDiscoveryOrchestrator | None = None,
    discovery_service_override: MarketplaceDiscoveryService | None = None,
    discovery_runtime_factory: MarketplaceDiscoveryRuntimeFactory | None = None,
    discovery_embedding_runtime_factory: (
        DiscoveryEmbeddingRuntimeFactory | None
    ) = None,
    marketplace_v2_service_override: MarketplaceAgentV2Service | None = None,
    marketplace_v2_runtime_factory: MarketplaceAgentV2RuntimeFactory | None = None,
) -> FastAPI:
    """Create the service app without performing provider calls at startup."""

    runtime_settings = settings or Settings.from_env()
    runtime_settings.agent_api.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled
    )
    runtime_settings.listing_proposal_api.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled
    )
    runtime_settings.discovery_api.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled,
    )
    runtime_settings.marketplace_agent_v2.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled,
        provider_configured=runtime_settings.openai_configured,
    )
    runtime_settings.discovery_embedding.validate(
        mysql_password_configured=bool(
            runtime_settings.knowledge_ingestion.mysql_password
        ),
        product_source_configured=bool(
            runtime_settings.knowledge_ingestion.product_service_url
            and runtime_settings.knowledge_ingestion.product_service_token
        ),
        provider_configured=runtime_settings.openai_configured,
    )
    metrics = KnowledgeIndexMetrics()
    knowledge_client = None
    knowledge_admin = None
    if runtime_settings.knowledge.enabled and knowledge_readiness_probe is None:
        knowledge_client = create_open_search_client(runtime_settings.knowledge)
        knowledge_admin = KnowledgeIndexAdmin(
            knowledge_client,
            runtime_settings.knowledge,
            metrics,
        )
    ingestion_runtime: KnowledgeIngestionRuntime | None = None
    create_ingestion_runtime = (
        ingestion_runtime_factory or KnowledgeIngestionRuntime.create
    )
    persistence_repository: AgentPersistenceRepository | None = None
    create_persistence_repository = (
        persistence_repository_factory or AgentPersistenceRepository.create
    )
    persistence_metrics = AgentPersistenceMetrics(metrics.registry)
    api_metrics = CustomerServiceApiMetrics(metrics.registry)
    listing_proposal_metrics = ListingProposalMetrics(metrics.registry)
    customer_service: AgentCustomerService | None = None
    discovery_repository: DiscoveryPersistenceRepository | None = None
    discovery_service: MarketplaceDiscoveryService | None = None
    discovery_embedding_runtime: DiscoveryEmbeddingRuntime | None = None
    listing_proposal_repository: ListingProposalRepositoryProtocol | None = None
    listing_proposal_service: ListingProposalReviewService | None = None
    listing_proposal_retention_task: asyncio.Task[None] | None = None
    customer_service_runtime: CustomerServiceRuntime | None = None
    discovery_runtime: MarketplaceDiscoveryRuntime | None = None
    marketplace_v2_runtime: MarketplaceAgentV2Runtime | None = None
    marketplace_v2_service: MarketplaceAgentV2Service | None = None
    active_discovery_streams: dict[tuple[str, str, str], asyncio.Task[object]] = {}
    active_marketplace_v2_streams: dict[
        tuple[str, str, str], asyncio.Task[object]
    ] = {}
    stopped_discovery_streams: dict[tuple[str, str, str], float] = {}
    create_listing_proposal_repository = (
        listing_proposal_repository_factory
        or ListingProposalRepository.from_agent_repository
    )
    runtime_identity_client = identity_client
    runtime_listing_client = listing_client
    orchestration_available = False
    create_customer_service_runtime = (
        customer_service_runtime_factory or build_customer_service_runtime
    )
    create_discovery_runtime = discovery_runtime_factory or (
        lambda selected_settings: build_marketplace_discovery_runtime(
            selected_settings,
            registry=metrics.registry,
        )
    )
    create_discovery_embedding_runtime = (
        discovery_embedding_runtime_factory or DiscoveryEmbeddingRuntime.create
    )
    create_marketplace_v2_runtime = marketplace_v2_runtime_factory or (
        lambda selected_settings: build_marketplace_agent_v2_runtime(
            selected_settings,
            registry=metrics.registry,
        )
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            nonlocal ingestion_runtime
            nonlocal persistence_repository
            nonlocal customer_service
            nonlocal discovery_repository
            nonlocal discovery_service
            nonlocal discovery_embedding_runtime
            nonlocal runtime_identity_client
            nonlocal runtime_listing_client
            nonlocal listing_proposal_repository
            nonlocal listing_proposal_service
            nonlocal listing_proposal_retention_task
            nonlocal customer_service_runtime
            nonlocal discovery_runtime
            nonlocal marketplace_v2_runtime
            nonlocal marketplace_v2_service
            nonlocal orchestration_available
            if runtime_settings.knowledge_ingestion.enabled:
                ingestion_runtime = await create_ingestion_runtime(
                    runtime_settings,
                    metrics,
                )
                await ingestion_runtime.start()
            if runtime_settings.agent_persistence.enabled:
                persistence_repository = await create_persistence_repository(
                    runtime_settings.agent_persistence,
                    persistence_metrics,
                )
                await persistence_repository.validate_schema()
            if runtime_settings.agent_api.enabled:
                if persistence_repository is None:
                    raise RuntimeError(
                        "Customer-service API requires initialized persistence"
                    )
                runtime_identity_client = (
                    runtime_identity_client
                    or ActorIdentityClient(runtime_settings.agent_api)
                )
                runtime_listing_client = (
                    runtime_listing_client
                    or ListingEligibilityClient(runtime_settings.agent_api)
                )
                selected_answerer: QuestionAnswerer = DeferredQuestionAnswerer()
                if runtime_settings.agent_api.generation_enabled:
                    if question_answerer is not None:
                        selected_answerer = question_answerer
                    else:
                        if knowledge_client is None:
                            raise RuntimeError(
                                "Enabled customer-service generation requires "
                                "an initialized knowledge client"
                            )
                        customer_service_runtime = create_customer_service_runtime(
                            runtime_settings,
                            persistence_repository,
                            knowledge_client,
                            metrics.registry,
                        )
                        selected_answerer = customer_service_runtime.answerer
                    orchestration_available = True
                customer_service = AgentCustomerService(
                    persistence_repository,
                    runtime_listing_client,
                    selected_answerer,
                )
            if runtime_settings.discovery_api.enabled:
                if persistence_repository is None:
                    raise RuntimeError(
                        "Discovery API requires initialized persistence"
                    )
                runtime_identity_client = (
                    runtime_identity_client
                    or ActorIdentityClient(runtime_settings.discovery_api)
                )
                if discovery_service_override is not None:
                    discovery_service = discovery_service_override
                else:
                    selected_discovery_orchestrator = discovery_orchestrator
                    if (
                        runtime_settings.discovery_api.generation_enabled
                        and selected_discovery_orchestrator is None
                    ):
                        discovery_runtime = create_discovery_runtime(runtime_settings)
                        selected_discovery_orchestrator = (
                            discovery_runtime.orchestrator
                        )
                    discovery_repository = DiscoveryPersistenceRepository(
                        persistence_repository
                    )
                    await discovery_repository.validate_schema()
                    discovery_service = MarketplaceDiscoveryService(
                        discovery_repository,
                        selected_discovery_orchestrator,
                        (
                            discovery_runtime.provider
                            if discovery_runtime is not None
                            else None
                        ),
                    )
            if runtime_settings.marketplace_agent_v2.enabled:
                if persistence_repository is None:
                    raise RuntimeError("Marketplace Agent V2 requires initialized persistence")
                runtime_identity_client = (
                    runtime_identity_client
                    or ActorIdentityClient(runtime_settings.marketplace_agent_v2)
                )
                if marketplace_v2_service_override is not None:
                    marketplace_v2_service = marketplace_v2_service_override
                else:
                    marketplace_v2_repository = MarketplaceAgentV2Persistence(
                        persistence_repository
                    )
                    await marketplace_v2_repository.validate_schema()
                    marketplace_v2_runtime = create_marketplace_v2_runtime(runtime_settings)
                    marketplace_v2_service = MarketplaceAgentV2Service(
                        marketplace_v2_repository,
                        marketplace_v2_runtime.orchestrator,
                        provider_name="openai",
                        model_name=runtime_settings.openai_model,
                    )
            if runtime_settings.listing_proposal_api.enabled:
                if persistence_repository is None:
                    raise RuntimeError(
                        "Listing proposal API requires initialized persistence"
                    )
                runtime_identity_client = (
                    runtime_identity_client
                    or ActorIdentityClient(runtime_settings.listing_proposal_api)
                )
                listing_proposal_repository = (
                    await create_listing_proposal_repository(
                        persistence_repository,
                        listing_proposal_metrics,
                    )
                )
                await listing_proposal_repository.validate_schema()
                generation_enabled = (
                    not runtime_settings.listing_proposal_api.kill_switch_enabled
                    and runtime_settings.listing_proposal_api.orchestration_enabled
                    and runtime_settings.listing_proposal_api.media_tool_enabled
                    and runtime_settings.listing_proposal_api.multimodal_provider_enabled
                    and runtime_settings.listing_proposal_api.multimodal_provider_configured
                )
                if generation_enabled and listing_proposal_generator is None:
                    raise RuntimeError(
                        "Enabled listing proposal generation requires an injected "
                        "Agent-owned generator"
                    )
                listing_proposal_service = ListingProposalReviewService(
                    listing_proposal_repository,
                    runtime_settings.listing_proposal_api,
                    listing_proposal_metrics,
                    listing_proposal_generator,
                )
                listing_proposal_retention_task = asyncio.create_task(
                    listing_proposal_retention_loop(listing_proposal_repository)
                )
            if _discovery_embedding_runtime_enabled(runtime_settings):
                discovery_embedding_runtime = (
                    await create_discovery_embedding_runtime(
                        runtime_settings,
                        metrics.registry,
                    )
                )
                await discovery_embedding_runtime.start()
            yield
        finally:
            if listing_proposal_retention_task is not None:
                listing_proposal_retention_task.cancel()
                with suppress(asyncio.CancelledError):
                    await listing_proposal_retention_task
            if discovery_embedding_runtime is not None:
                await discovery_embedding_runtime.stop()
            if persistence_repository is not None:
                await persistence_repository.close()
            if ingestion_runtime is not None:
                await ingestion_runtime.stop()
            if knowledge_client is not None:
                await close_open_search_client(knowledge_client)
            if customer_service_runtime is not None:
                await customer_service_runtime.close()
            if discovery_runtime is not None:
                await discovery_runtime.close()
            if marketplace_v2_runtime is not None:
                await marketplace_v2_runtime.close()

    app = FastAPI(title="MSB Agent Service", version="0.9.0", lifespan=lifespan)
    app.mount("/metrics", make_asgi_app(registry=metrics.registry))

    @app.middleware("http")
    async def correlation(request: Request, call_next):
        supplied = request.headers.get("X-Correlation-Id")
        correlation_id = (
            supplied
            if supplied is not None
            and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", supplied)
            else str(uuid.uuid4())
        )
        request.state.correlation_id = correlation_id
        if (
            request.url.path.startswith("/api/v1/agent/listing-proposals")
            and not runtime_settings.listing_proposal_api.enabled
        ):
            return _error_response(
                request,
                404,
                ListingProposalApiErrorCode.FEATURE_DISABLED.value,
                "Listing proposal review is not available.",
            )
        if (
            request.method == "POST"
            and request.url.path.startswith("/api/v1/agent/listing-proposals")
        ):
            content_length = request.headers.get("content-length")
            try:
                declared_size = (
                    None if content_length is None else int(content_length)
                )
            except ValueError:
                declared_size = 8_193
            if declared_size is not None and declared_size > 8_192:
                return _error_response(
                    request,
                    413,
                    ListingProposalApiErrorCode.PAYLOAD_TOO_LARGE.value,
                    "The request body exceeds 8192 bytes.",
                )
            body = await request.body()
            if len(body) > 8_192:
                return _error_response(
                    request,
                    413,
                    ListingProposalApiErrorCode.PAYLOAD_TOO_LARGE.value,
                    "The request body exceeds 8192 bytes.",
                )
        response = await call_next(request)
        response.headers["X-Correlation-Id"] = correlation_id
        return response

    @app.exception_handler(AgentApiError)
    async def agent_api_error_handler(
        request: Request,
        error: AgentApiError,
    ) -> JSONResponse:
        return _error_response(
            request,
            error.status_code,
            error.code.value,
            error.public_message,
        )

    @app.exception_handler(ListingProposalApiError)
    async def listing_proposal_error_handler(
        request: Request,
        error: ListingProposalApiError,
    ) -> JSONResponse:
        return _error_response(
            request,
            error.status_code,
            error.code.value,
            error.public_message,
        )

    @app.exception_handler(DiscoveryApiError)
    async def discovery_api_error_handler(
        request: Request,
        error: DiscoveryApiError,
    ) -> JSONResponse:
        return _error_response(
            request,
            error.status_code,
            error.code.value,
            error.public_message,
        )

    @app.exception_handler(MarketplaceAgentV2ApiError)
    async def marketplace_v2_api_error_handler(
        request: Request,
        error: MarketplaceAgentV2ApiError,
    ) -> JSONResponse:
        return _error_response(
            request,
            error.status_code,
            error.code.value,
            error.public_message,
        )

    @app.exception_handler(AgentPersistenceError)
    async def persistence_error_handler(
        request: Request,
        error: AgentPersistenceError,
    ) -> JSONResponse:
        mapped = map_persistence_error(error)
        return _error_response(
            request,
            mapped.status_code,
            mapped.code.value,
            mapped.public_message,
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request,
        error: RequestValidationError,
    ) -> JSONResponse:
        validation_errors = error.errors()
        if (
            request.method == "POST"
            and request.url.path == _CREATE_SESSION_PATH
        ):
            diagnostics = _create_session_validation_diagnostics(
                validation_errors
            )
            for field_category, error_category in diagnostics:
                api_metrics.record_validation(
                    CREATE_SESSION_VALIDATION_ROUTE,
                    field_category,
                    error_category,
                    VALIDATION_ERROR_RESULT,
                )
            input_shape = _create_session_input_shape(error.body)
            LOGGER.info(
                "Agent create-session validation rejected "
                "operation=create_session result=%s correlationId=%s "
                "errorCount=%d fieldCategories=%s errorCategories=%s "
                "topLevelKeys=%s subjectKeys=%s subjectIdType=%s "
                "subjectIdLength=%s",
                VALIDATION_ERROR_RESULT,
                request.state.correlation_id,
                min(len(validation_errors), _MAX_PUBLIC_VALIDATION_ERRORS),
                ",".join(item[0] for item in diagnostics),
                ",".join(item[1] for item in diagnostics),
                input_shape["top_level_keys"],
                input_shape["subject_keys"],
                input_shape["subject_id_type"],
                input_shape["subject_id_length"],
            )
        details = [
            ApiErrorDetail(
                field=".".join(str(part) for part in item["loc"][1:]) or None,
                message=item["msg"],
            )
            for item in validation_errors[:_MAX_PUBLIC_VALIDATION_ERRORS]
        ]
        error_code = (
            ListingProposalApiErrorCode.INVALID_REQUEST.value
            if request.url.path.startswith("/api/v1/agent/listing-proposals")
            else "VALIDATION_ERROR"
        )
        return _error_response(
            request,
            400,
            error_code,
            "The request is invalid.",
            details,
        )

    @app.exception_handler(Exception)
    async def unexpected_error_handler(
        request: Request,
        error: Exception,
    ) -> JSONResponse:
        LOGGER.error(
            "Unexpected Agent API failure path=%s correlationId=%s errorType=%s",
            request.url.path,
            request.state.correlation_id,
            type(error).__name__,
        )
        return _error_response(
            request,
            500,
            "INTERNAL_ERROR",
            "An unexpected error occurred.",
        )

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="UP", service="agent-service")

    @app.get("/ready", response_model=ReadinessResponse)
    async def ready() -> ReadinessResponse | JSONResponse:
        knowledge_status = KnowledgeReadinessStatus.DISABLED
        if runtime_settings.knowledge.enabled:
            if knowledge_readiness_probe is not None:
                knowledge_status = await knowledge_readiness_probe()
            elif knowledge_admin is not None:
                knowledge_status = (await knowledge_admin.status()).status
        knowledge_ready = knowledge_status in {
            KnowledgeReadinessStatus.DISABLED,
            KnowledgeReadinessStatus.READY,
        }
        ingestion_status = KnowledgeIngestionStatus.DISABLED
        if runtime_settings.knowledge_ingestion.enabled:
            ingestion_status = (
                KnowledgeIngestionStatus.UNAVAILABLE
                if ingestion_runtime is None
                else ingestion_runtime.status()
            )
        ingestion_ready = ingestion_status in {
            KnowledgeIngestionStatus.DISABLED,
            KnowledgeIngestionStatus.READY,
        }
        category_status = KnowledgeIngestionStatus.DISABLED
        if (
            runtime_settings.knowledge_ingestion.enabled
            and runtime_settings.knowledge_ingestion.category_guidance_intake_enabled
        ):
            category_status = (
                KnowledgeIngestionStatus.UNAVAILABLE
                if ingestion_runtime is None
                else ingestion_runtime.category_intake_status()
            )
        category_ready = category_status in {
            KnowledgeIngestionStatus.DISABLED,
            KnowledgeIngestionStatus.READY,
        }
        persistence_status = (
            "DISABLED"
            if not runtime_settings.agent_persistence.enabled
            else "READY"
            if persistence_repository is not None
            else "UNAVAILABLE"
        )
        persistence_ready = persistence_status in {"DISABLED", "READY"}
        customer_service_status = (
            "DISABLED"
            if not runtime_settings.agent_api.enabled
            else "READY"
            if customer_service is not None and orchestration_available
            else "ORCHESTRATION_DEFERRED"
        )
        customer_service_ready = customer_service_status in {"DISABLED", "READY"}
        discovery_status = (
            "DISABLED"
            if not runtime_settings.discovery_api.enabled
            else "READY"
            if (
                discovery_service is not None
                and runtime_settings.discovery_api.generation_enabled
            )
            else "ORCHESTRATION_DEFERRED"
        )
        discovery_ready = discovery_status in {"DISABLED", "READY"}
        discovery_embedding_status = _discovery_embedding_status(
            runtime_settings,
            discovery_embedding_runtime,
        )
        discovery_embedding_ready = discovery_embedding_status in {
            DiscoveryEmbeddingRuntimeStatus.DISABLED,
            DiscoveryEmbeddingRuntimeStatus.READY,
            DiscoveryEmbeddingRuntimeStatus.DEFERRED,
        }
        if (
            runtime_settings.openai_configured
            and knowledge_ready
            and ingestion_ready
            and category_ready
            and persistence_ready
            and customer_service_ready
            and discovery_ready
            and discovery_embedding_ready
        ):
            return ReadinessResponse(
                status="READY",
                openai="CONFIGURED",
                knowledgeIndex=knowledge_status,
                knowledgeIngestion=ingestion_status,
                categoryGuidanceIntake=category_status,
                agentPersistence=persistence_status,
                customerServiceApi=customer_service_status,
                marketplaceDiscoveryApi=discovery_status,
                discoveryDocumentEmbedding=discovery_embedding_status,
            )
        response = ReadinessResponse(
            status="NOT_READY",
            openai=(
                "CONFIGURED"
                if runtime_settings.openai_configured
                else "OPENAI_API_KEY_NOT_CONFIGURED"
            ),
            knowledgeIndex=knowledge_status,
            knowledgeIngestion=ingestion_status,
            categoryGuidanceIntake=category_status,
            agentPersistence=persistence_status,
            customerServiceApi=customer_service_status,
            marketplaceDiscoveryApi=discovery_status,
            discoveryDocumentEmbedding=discovery_embedding_status,
        )
        return JSONResponse(
            status_code=503,
            content=response.model_dump(by_alias=True),
        )

    async def actor(
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> str:
        """Resolve the authenticated app actor only while the API is available."""

        if (
            not runtime_settings.agent_api.enabled
            or runtime_identity_client is None
            or customer_service is None
        ):
            raise AgentApiError(
                code=AgentApiErrorCode.FEATURE_DISABLED,
                status_code=404,
                message="Agent customer service is not available.",
            )
        return await runtime_identity_client.resolve(
            authorization,
            request.state.correlation_id,
        )

    def require_answer_generation() -> None:
        """Block write-side Agent work before actor, Product, or persistence calls."""

        if not runtime_settings.agent_api.enabled:
            raise AgentApiError(
                AgentApiErrorCode.FEATURE_DISABLED,
                404,
                "Agent customer service is not available.",
            )
        if (
            not runtime_settings.agent_api.generation_enabled
            or not orchestration_available
        ):
            raise AgentApiError(
                AgentApiErrorCode.ORCHESTRATION_UNAVAILABLE,
                503,
                "The customer-service answer engine is not available.",
            )

    async def discovery_actor(
        request: Request,
        authorization: str | None,
    ) -> str:
        """Resolve the actor only after the discovery API capability gate."""

        if (
            not runtime_settings.discovery_api.enabled
            or runtime_identity_client is None
            or discovery_service is None
        ):
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.FEATURE_DISABLED,
                404,
                "Marketplace discovery is not available.",
            )
        try:
            return await runtime_identity_client.resolve(
                authorization,
                request.state.correlation_id,
            )
        except AgentApiError as error:
            if error.status_code == 401:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.AUTHENTICATION_REQUIRED,
                    401,
                    "Authentication is required.",
                ) from error
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace discovery is temporarily unavailable.",
            ) from error

    def require_discovery_generation() -> None:
        """Stop discovery writes before actor, persistence, Product, or model calls."""

        if not runtime_settings.discovery_api.enabled:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.FEATURE_DISABLED,
                404,
                "Marketplace discovery is not available.",
            )
        if (
            not runtime_settings.discovery_api.generation_enabled
            or discovery_service is None
        ):
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace discovery is temporarily unavailable.",
            )

    async def marketplace_v2_actor(
        request: Request,
        authorization: str | None,
    ) -> str:
        """Resolve the actor only after the independent V2 capability gate."""

        if (
            not runtime_settings.marketplace_agent_v2.enabled
            or runtime_identity_client is None
            or marketplace_v2_service is None
        ):
            raise MarketplaceAgentV2ApiError(
                MarketplaceAgentV2ApiErrorCode.FEATURE_DISABLED,
                404,
                "Marketplace Agent V2 is not available.",
            )
        try:
            return await runtime_identity_client.resolve(
                authorization,
                request.state.correlation_id,
            )
        except AgentApiError as error:
            if error.status_code == 401:
                raise MarketplaceAgentV2ApiError(
                    MarketplaceAgentV2ApiErrorCode.AUTHENTICATION_REQUIRED,
                    401,
                    "Authentication is required.",
                ) from error
            raise MarketplaceAgentV2ApiError(
                MarketplaceAgentV2ApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace Agent V2 is temporarily unavailable.",
            ) from error

    def require_marketplace_v2_generation() -> None:
        """Reject V2 writes before actor, persistence, Product, or provider work."""

        if not runtime_settings.marketplace_agent_v2.enabled:
            raise MarketplaceAgentV2ApiError(
                MarketplaceAgentV2ApiErrorCode.FEATURE_DISABLED,
                404,
                "Marketplace Agent V2 is not available.",
            )
        if (
            not runtime_settings.marketplace_agent_v2.generation_enabled
            or marketplace_v2_service is None
        ):
            raise MarketplaceAgentV2ApiError(
                MarketplaceAgentV2ApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace Agent V2 is temporarily unavailable.",
            )

    async def proposal_actor(
        request: Request,
        authorization: str | None,
    ) -> str:
        """Resolve only the bearer actor after the proposal capability boundary."""

        if (
            not runtime_settings.listing_proposal_api.enabled
            or runtime_identity_client is None
            or listing_proposal_service is None
        ):
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.FEATURE_DISABLED,
                404,
                "Listing proposal review is not available.",
            )
        try:
            resolved_actor = await runtime_identity_client.resolve(
                authorization,
                request.state.correlation_id,
            )
            if re.fullmatch(r"[0-9A-Z]{26}", resolved_actor) is None:
                raise ListingProposalApiError(
                    ListingProposalApiErrorCode.UNAVAILABLE,
                    503,
                    "Listing proposal review is temporarily unavailable.",
                )
            return resolved_actor
        except AgentApiError as error:
            if error.status_code == 401:
                raise ListingProposalApiError(
                    ListingProposalApiErrorCode.AUTHENTICATION_REQUIRED,
                    401,
                    "Authentication is required.",
                ) from error
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.UNAVAILABLE,
                503,
                "Listing proposal review is temporarily unavailable.",
            ) from error

    @app.post(
        "/api/v1/agent/sessions",
        response_model=AgentSessionResponse,
        responses={400: {"model": ApiErrorEnvelope}, 401: {"model": ApiErrorEnvelope}},
    )
    async def create_agent_session(
        body: CreateAgentSessionRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> AgentSessionResponse:
        """Create or resume the caller's one open session for an eligible listing."""

        started = time.perf_counter()
        route = "create_session"
        try:
            require_answer_generation()
            actor_user_id = await actor(request, authorization)
            assert customer_service is not None
            response = await customer_service.create_session(
                actor_user_id=actor_user_id,
                listing_id=body.subject.id,
                correlation_id=request.state.correlation_id,
            )
            api_metrics.record(route, "SUCCEEDED", time.perf_counter() - started)
            return response
        except Exception as error:
            api_metrics.record(
                route,
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.get(
        "/api/v1/agent/sessions/{sessionId}",
        response_model=AgentSessionResponse,
    )
    async def get_agent_session(
        sessionId: str,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> AgentSessionResponse:
        """Return one session without revealing whether another actor owns it."""

        started = time.perf_counter()
        try:
            actor_user_id = await actor(request, authorization)
            assert customer_service is not None
            response = await customer_service.get_session(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                correlation_id=request.state.correlation_id,
            )
            api_metrics.record("get_session", "SUCCEEDED", time.perf_counter() - started)
            return response
        except Exception as error:
            api_metrics.record(
                "get_session",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.get(
        "/api/v1/agent/sessions/{sessionId}/messages",
        response_model=AgentMessagePageResponse,
    )
    async def get_agent_messages(
        sessionId: str,
        request: Request,
        cursor: str | None = Query(default=None, max_length=300),
        limit: int | None = Query(default=None, ge=1, le=100),
        authorization: str | None = Header(default=None),
    ) -> AgentMessagePageResponse:
        """Read a bounded keyset page from the caller's actor-isolated session."""

        started = time.perf_counter()
        try:
            actor_user_id = await actor(request, authorization)
            selected_limit = (
                runtime_settings.agent_api.message_page_default_limit
                if limit is None
                else min(limit, runtime_settings.agent_api.message_page_max_limit)
            )
            assert customer_service is not None
            response = await customer_service.list_messages(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                limit=selected_limit,
                cursor=cursor,
            )
            api_metrics.record("list_messages", "SUCCEEDED", time.perf_counter() - started)
            return response
        except Exception as error:
            api_metrics.record(
                "list_messages",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.post(
        "/api/v1/agent/sessions/{sessionId}/messages",
        response_model=SendAgentMessageResponse,
    )
    async def send_agent_message(
        sessionId: str,
        body: SendAgentMessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> SendAgentMessageResponse:
        """Persist one idempotent caller message and fail closed without orchestration."""

        started = time.perf_counter()
        try:
            require_answer_generation()
            actor_user_id = await actor(request, authorization)
            assert customer_service is not None
            response = await customer_service.send_message(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                client_message_id=body.client_message_id,
                body=body.body,
                correlation_id=request.state.correlation_id,
            )
            api_metrics.record("send_message", "SUCCEEDED", time.perf_counter() - started)
            return response
        except Exception as error:
            api_metrics.record(
                "send_message",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    def marketplace_v2_streaming_response(
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
        operation: Callable[..., Awaitable[SendMarketplaceAgentV2MessageResponse]],
    ) -> StreamingResponse:
        """Run one V2 operation with immediate strict events and cancellable ownership."""

        async def events() -> AsyncIterator[str]:
            sequence = 0
            queue: asyncio.Queue[tuple[str, dict[str, object]]] = asyncio.Queue()

            async def accepted(user_message: object) -> None:
                await queue.put(("message_started", {
                    "userMessage": user_message.model_dump(mode="json", by_alias=True),
                }))

            async def activity(tool: str, label: str) -> None:
                await queue.put(("activity", {"tool": tool, "label": label}))

            async def tool_completed(observation: object) -> None:
                await queue.put(("tool_completed", {
                    "tool": observation.tool,
                    "status": observation.status,
                    "reason": observation.reason,
                    "observedAt": observation.observed_at.isoformat(),
                }))

            async def text_delta(delta: str) -> None:
                await queue.put(("text_delta", {"delta": delta}))

            send_task = asyncio.create_task(operation(
                accepted=accepted,
                activity=activity,
                tool_completed=tool_completed,
                text_delta=text_delta,
            ))
            stream_key = (actor_user_id, session_id, client_message_id)
            active_marketplace_v2_streams[stream_key] = send_task
            pending_get: asyncio.Task[tuple[str, dict[str, object]]] | None = None
            try:
                pending_get = asyncio.create_task(queue.get())
                while not send_task.done():
                    done, _ = await asyncio.wait(
                        {send_task, pending_get}, return_when=asyncio.FIRST_COMPLETED
                    )
                    if pending_get in done:
                        event_type, payload = pending_get.result()
                        sequence += 1
                        yield marketplace_v2_stream_event(sequence, event_type, **payload)
                        pending_get = asyncio.create_task(queue.get())
                if pending_get.done():
                    queue.put_nowait(pending_get.result())
                else:
                    pending_get.cancel()
                while not queue.empty():
                    event_type, payload = queue.get_nowait()
                    sequence += 1
                    yield marketplace_v2_stream_event(sequence, event_type, **payload)
                response = await send_task
                if response.message.attachments:
                    sequence += 1
                    yield marketplace_v2_stream_event(
                        sequence,
                        "attachments",
                        items=[item.model_dump(mode="json", by_alias=True)
                               for item in response.message.attachments],
                    )
                sequence += 1
                yield marketplace_v2_stream_event(
                    sequence,
                    "done",
                    messageId=response.assistant_message_id,
                    response=response.model_dump(mode="json", by_alias=True),
                )
            except asyncio.CancelledError:
                if not send_task.done():
                    send_task.cancel()
                raise
            except Exception:
                sequence += 1
                yield marketplace_v2_stream_event(
                    sequence,
                    "error",
                    code=MarketplaceAgentV2ApiErrorCode.STREAM_INTERRUPTED.value,
                    message="The Marketplace Agent V2 response was interrupted.",
                    retryable=True,
                )
            finally:
                if active_marketplace_v2_streams.get(stream_key) is send_task:
                    active_marketplace_v2_streams.pop(stream_key, None)
                if pending_get is not None and not pending_get.done():
                    pending_get.cancel()
                if not send_task.done():
                    send_task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await send_task

        return StreamingResponse(
            events(),
            media_type=None,
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache, no-transform",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post(
        "/api/v1/agent/marketplace-v2/sessions",
        response_model=MarketplaceAgentV2SessionResponse,
    )
    async def create_marketplace_v2_session(
        body: CreateMarketplaceAgentV2SessionRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> MarketplaceAgentV2SessionResponse:
        require_marketplace_v2_generation()
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        return await marketplace_v2_service.create_session(
            actor_user_id=actor_user_id,
            new_conversation=body.new_conversation,
        )

    @app.get(
        "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages",
        response_model=MarketplaceAgentV2HistoryPage,
    )
    async def get_marketplace_v2_messages(
        sessionId: Ulid,
        request: Request,
        limit: int = Query(default=100, ge=1, le=100),
        authorization: str | None = Header(default=None),
    ) -> MarketplaceAgentV2HistoryPage:
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        return await marketplace_v2_service.list_messages(
            actor_user_id=actor_user_id,
            session_id=str(sessionId),
            limit=limit,
        )

    @app.post(
        "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages",
        response_model=SendMarketplaceAgentV2MessageResponse,
    )
    async def send_marketplace_v2_message(
        sessionId: Ulid,
        body: SendMarketplaceAgentV2MessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> SendMarketplaceAgentV2MessageResponse:
        require_marketplace_v2_generation()
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        try:
            return await marketplace_v2_service.send_message(
                actor_user_id=actor_user_id,
                session_id=str(sessionId),
                client_message_id=body.client_message_id,
                body=body.body,
                correlation_id=request.state.correlation_id,
            )
        except MarketplaceAgentV2ApiError:
            raise
        except Exception as error:
            raise MarketplaceAgentV2ApiError(
                MarketplaceAgentV2ApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace Agent V2 is temporarily unavailable.",
            ) from error

    @app.post(
        "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/stream",
        response_class=StreamingResponse,
    )
    async def stream_marketplace_v2_message(
        sessionId: Ulid,
        body: SendMarketplaceAgentV2MessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StreamingResponse:
        require_marketplace_v2_generation()
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        return marketplace_v2_streaming_response(
            actor_user_id=actor_user_id,
            session_id=str(sessionId),
            client_message_id=body.client_message_id,
            operation=lambda **callbacks: marketplace_v2_service.send_message(
                actor_user_id=actor_user_id,
                session_id=str(sessionId),
                client_message_id=body.client_message_id,
                body=body.body,
                correlation_id=request.state.correlation_id,
                **callbacks,
            ),
        )

    @app.post(
        "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/"
        "{clientMessageId}/stop",
        response_model=StopMarketplaceAgentV2Response,
    )
    async def stop_marketplace_v2_message(
        sessionId: Ulid,
        clientMessageId: Ulid,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StopMarketplaceAgentV2Response:
        require_marketplace_v2_generation()
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        task = active_marketplace_v2_streams.get(
            (actor_user_id, str(sessionId), str(clientMessageId))
        )
        if task is not None and not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await task
        return await marketplace_v2_service.stop_message(
            actor_user_id=actor_user_id,
            session_id=str(sessionId),
            client_message_id=str(clientMessageId),
        )

    @app.post(
        "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/"
        "{userMessageId}/response-retry/stream",
        response_class=StreamingResponse,
    )
    async def retry_marketplace_v2_response(
        sessionId: Ulid,
        userMessageId: Ulid,
        body: RetryMarketplaceAgentV2ResponseRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StreamingResponse:
        require_marketplace_v2_generation()
        actor_user_id = await marketplace_v2_actor(request, authorization)
        assert marketplace_v2_service is not None
        return marketplace_v2_streaming_response(
            actor_user_id=actor_user_id,
            session_id=str(sessionId),
            client_message_id=body.client_message_id,
            operation=lambda **callbacks: marketplace_v2_service.retry_response(
                actor_user_id=actor_user_id,
                session_id=str(sessionId),
                user_message_id=str(userMessageId),
                client_message_id=body.client_message_id,
                correlation_id=request.state.correlation_id,
                **callbacks,
            ),
        )

    @app.post(
        "/api/v1/agent/discovery/sessions",
        response_model=DiscoverySessionResponse,
    )
    async def create_discovery_session(
        body: CreateDiscoverySessionRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> DiscoverySessionResponse:
        """Create/resume one actor session or explicitly begin a New search."""

        started = time.perf_counter()
        try:
            require_discovery_generation()
            actor_user_id = await discovery_actor(request, authorization)
            assert discovery_service is not None
            response = await discovery_service.create_session(
                actor_user_id=actor_user_id,
                new_search=body.new_search,
            )
            api_metrics.record(
                "discovery_create_session",
                "SUCCEEDED",
                time.perf_counter() - started,
            )
            return response
        except Exception as error:
            api_metrics.record(
                "discovery_create_session",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.get(
        "/api/v1/agent/discovery/sessions/{sessionId}",
        response_model=DiscoverySessionResponse,
    )
    async def get_discovery_session(
        sessionId: Ulid,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> DiscoverySessionResponse:
        """Return only the authenticated actor's discovery preference state."""

        actor_user_id = await discovery_actor(request, authorization)
        assert discovery_service is not None
        return await discovery_service.get_session(
            actor_user_id=actor_user_id,
            session_id=sessionId,
        )

    @app.get(
        "/api/v1/agent/discovery/sessions/{sessionId}/messages",
        response_model=DiscoveryHistoryPage,
        response_model_exclude_none=False,
    )
    async def get_discovery_messages(
        sessionId: Ulid,
        request: Request,
        cursor: str | None = Query(default=None, max_length=300),
        limit: int = Query(default=50, ge=1, le=100),
        authorization: str | None = Header(default=None),
    ) -> DiscoveryHistoryPage:
        """Read an actor-isolated keyset page of safe structured discovery turns."""

        actor_user_id = await discovery_actor(request, authorization)
        assert discovery_service is not None
        return await discovery_service.list_messages(
            actor_user_id=actor_user_id,
            session_id=sessionId,
            limit=limit,
            cursor=decode_cursor(cursor),
        )

    @app.post(
        "/api/v1/agent/discovery/sessions/{sessionId}/messages",
        response_model=SendDiscoveryMessageResponse,
        response_model_exclude_none=False,
    )
    async def send_discovery_message(
        sessionId: Ulid,
        body: SendDiscoveryMessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> SendDiscoveryMessageResponse:
        """Run one idempotent discovery turn without any automatic action."""

        started = time.perf_counter()
        try:
            require_discovery_generation()
            actor_user_id = await discovery_actor(request, authorization)
            assert discovery_service is not None
            response = await discovery_service.send_message(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                client_message_id=body.client_message_id,
                expected_preference_version=body.expected_preference_version,
                body=body.body,
                correlation_id=request.state.correlation_id,
            )
            api_metrics.record(
                "discovery_send_message",
                "SUCCEEDED",
                time.perf_counter() - started,
            )
            return response
        except Exception as error:
            api_metrics.record(
                "discovery_send_message",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.post(
        "/api/v1/agent/discovery/sessions/{sessionId}/messages/stream",
        response_class=StreamingResponse,
    )
    async def stream_discovery_message(
        sessionId: Ulid,
        body: SendDiscoveryMessageRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StreamingResponse:
        """Stream safe activity and only the persisted guarded discovery answer."""

        require_discovery_generation()
        actor_user_id = await discovery_actor(request, authorization)
        assert discovery_service is not None
        started = time.perf_counter()

        async def events() -> AsyncIterator[str]:
            sequence = 0
            stream_key = (actor_user_id, str(sessionId), str(body.client_message_id))
            if stopped_discovery_streams.pop(stream_key, None) is not None:
                return
            event_queue: asyncio.Queue[
                tuple[str, object, asyncio.Future[None]]
            ] = asyncio.Queue()
            assistant_message_id: str | None = None

            async def publish(event_type: str, value: object) -> None:
                """Apply backpressure until ASGI has accepted this exact SSE frame."""

                delivered = asyncio.get_running_loop().create_future()
                await event_queue.put((event_type, value, delivered))
                await delivered

            async def progress(stage: DiscoveryProgressStage) -> None:
                await publish("activity", stage)

            async def text_delta(delta: str) -> None:
                for chunk in _discovery_text_delta_chunks(delta):
                    await publish("text_delta", chunk)

            async def finalized(message_id: str) -> None:
                nonlocal assistant_message_id
                assistant_message_id = message_id

            send_task = asyncio.create_task(discovery_service.send_message(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                client_message_id=body.client_message_id,
                expected_preference_version=body.expected_preference_version,
                body=body.body,
                correlation_id=request.state.correlation_id,
                progress=progress,
                text_delta=text_delta,
                finalized=finalized,
            ))
            active_discovery_streams[stream_key] = send_task
            event_task: asyncio.Task[
                tuple[str, object, asyncio.Future[None]]
            ] | None = None
            try:
                event_task = asyncio.create_task(event_queue.get())
                while not send_task.done():
                    done, _ = await asyncio.wait(
                        {send_task, event_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if event_task in done:
                        event_type, value, delivered = event_task.result()
                        sequence += 1
                        if event_type == "activity":
                            stage = value
                            assert isinstance(stage, DiscoveryProgressStage)
                            yield _discovery_stream_event(
                                sequence, "activity", stage=stage.value,
                                label=_discovery_activity_label(stage),
                            )
                        else:
                            yield _discovery_stream_event(
                                sequence, "text_delta", delta=value,
                            )
                        if not delivered.done():
                            delivered.set_result(None)
                        event_task = asyncio.create_task(event_queue.get())
                if event_task.done():
                    event_queue.put_nowait(event_task.result())
                else:
                    event_task.cancel()
                while not event_queue.empty():
                    event_type, value, delivered = event_queue.get_nowait()
                    sequence += 1
                    if event_type == "activity":
                        stage = value
                        assert isinstance(stage, DiscoveryProgressStage)
                        yield _discovery_stream_event(
                            sequence, "activity", stage=stage.value,
                            label=_discovery_activity_label(stage),
                        )
                    else:
                        yield _discovery_stream_event(
                            sequence, "text_delta", delta=value,
                        )
                    if not delivered.done():
                        delivered.set_result(None)
                response = await send_task
                if assistant_message_id is None:
                    raise RuntimeError("Discovery completion identity missing")
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "recommendations",
                    items=[item.model_dump(mode="json", by_alias=True)
                           for item in response.result.recommendations],
                )
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "metadata", citations=[],
                    provenance=[item.provenance.model_dump(mode="json", by_alias=True)
                                for item in response.result.recommendations],
                )
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "done", messageId=assistant_message_id,
                    response=response.model_dump(mode="json", by_alias=True),
                )
                api_metrics.record(
                    "discovery_stream_message",
                    "SUCCEEDED",
                    time.perf_counter() - started,
                )
            except asyncio.CancelledError:
                if not send_task.done():
                    send_task.cancel()
                api_metrics.record(
                    "discovery_stream_message",
                    "CANCELLED",
                    time.perf_counter() - started,
                )
                raise
            except DiscoveryApiError as error:
                sequence += 1
                yield _discovery_stream_event(
                    sequence,
                    "error", code=error.code.value,
                    message=(
                        "The answer stream was interrupted. You can retry this response."
                        if error.code == DiscoveryApiErrorCode.STREAM_INTERRUPTED
                        else "Marketplace discovery is temporarily unavailable."
                    ),
                    retryable=(error.code == DiscoveryApiErrorCode.STREAM_INTERRUPTED),
                )
                api_metrics.record(
                    "discovery_stream_message",
                    _metric_result(error),
                    time.perf_counter() - started,
                )
            except Exception:
                sequence += 1
                yield _discovery_stream_event(
                    sequence,
                    "error", code=DiscoveryApiErrorCode.UNAVAILABLE.value,
                    message="Marketplace discovery is temporarily unavailable.",
                    retryable=False,
                )
                api_metrics.record(
                    "discovery_stream_message",
                    "FAILED",
                    time.perf_counter() - started,
                )
            finally:
                active_discovery_streams.pop(stream_key, None)
                if event_task is not None and not event_task.done():
                    event_task.cancel()
                if not send_task.done():
                    send_task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await send_task

        return StreamingResponse(
            events(),
            media_type=None,
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache, no-transform",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post(
        "/api/v1/agent/discovery/sessions/{sessionId}/messages/"
        "{clientMessageId}/stop",
        response_model=StopDiscoveryResponse,
    )
    async def stop_discovery_message(
        sessionId: Ulid,
        clientMessageId: Ulid,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StopDiscoveryResponse:
        """Cancel an active stream and report whether its USER turn committed."""

        require_discovery_generation()
        actor_user_id = await discovery_actor(request, authorization)
        assert discovery_service is not None
        stream_key = (actor_user_id, str(sessionId), str(clientMessageId))
        now = time.monotonic()
        for stale_key, stopped_at in tuple(stopped_discovery_streams.items()):
            if now - stopped_at > 60.0:
                stopped_discovery_streams.pop(stale_key, None)
        stopped_discovery_streams[stream_key] = now
        task = active_discovery_streams.get(stream_key)
        if task is not None and not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await task
        result = await discovery_service.stop_message(
            actor_user_id=actor_user_id,
            session_id=sessionId,
            client_message_id=clientMessageId,
        )
        if task is not None:
            stopped_discovery_streams.pop(stream_key, None)
        return result

    @app.post(
        "/api/v1/agent/discovery/sessions/{sessionId}/messages/"
        "{userMessageId}/response-retry/stream",
        response_class=StreamingResponse,
    )
    async def retry_discovery_response_stream(
        sessionId: Ulid,
        userMessageId: Ulid,
        body: RetryDiscoveryResponseRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> StreamingResponse:
        """Explicitly retry one failed response without accepting another USER row."""

        require_discovery_generation()
        actor_user_id = await discovery_actor(request, authorization)
        assert discovery_service is not None
        started = time.perf_counter()

        async def events() -> AsyncIterator[str]:
            sequence = 0
            event_queue: asyncio.Queue[
                tuple[str, object, asyncio.Future[None]]
            ] = asyncio.Queue()
            assistant_message_id: str | None = None

            async def publish(event_type: str, value: object) -> None:
                """Apply backpressure until ASGI has accepted this exact SSE frame."""

                delivered = asyncio.get_running_loop().create_future()
                await event_queue.put((event_type, value, delivered))
                await delivered

            async def progress(stage: DiscoveryProgressStage) -> None:
                await publish("activity", stage)

            async def text_delta(delta: str) -> None:
                for chunk in _discovery_text_delta_chunks(delta):
                    await publish("text_delta", chunk)

            async def finalized(message_id: str) -> None:
                nonlocal assistant_message_id
                assistant_message_id = message_id

            send_task = asyncio.create_task(discovery_service.retry_response(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                user_message_id=userMessageId,
                expected_preference_version=body.expected_preference_version,
                correlation_id=request.state.correlation_id,
                progress=progress,
                text_delta=text_delta,
                finalized=finalized,
            ))
            event_task: asyncio.Task[
                tuple[str, object, asyncio.Future[None]]
            ] | None = None
            try:
                event_task = asyncio.create_task(event_queue.get())
                while not send_task.done():
                    done, _ = await asyncio.wait(
                        {send_task, event_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if event_task in done:
                        event_type, value, delivered = event_task.result()
                        sequence += 1
                        if event_type == "activity":
                            stage = value
                            assert isinstance(stage, DiscoveryProgressStage)
                            yield _discovery_stream_event(
                                sequence, "activity", stage=stage.value,
                                label=_discovery_activity_label(stage),
                            )
                        else:
                            yield _discovery_stream_event(
                                sequence, "text_delta", delta=value,
                            )
                        if not delivered.done():
                            delivered.set_result(None)
                        event_task = asyncio.create_task(event_queue.get())
                if event_task.done():
                    event_queue.put_nowait(event_task.result())
                else:
                    event_task.cancel()
                while not event_queue.empty():
                    event_type, value, delivered = event_queue.get_nowait()
                    sequence += 1
                    if event_type == "activity":
                        stage = value
                        assert isinstance(stage, DiscoveryProgressStage)
                        yield _discovery_stream_event(
                            sequence, "activity", stage=stage.value,
                            label=_discovery_activity_label(stage),
                        )
                    else:
                        yield _discovery_stream_event(
                            sequence, "text_delta", delta=value,
                        )
                    if not delivered.done():
                        delivered.set_result(None)
                response = await send_task
                if assistant_message_id is None:
                    raise RuntimeError("Discovery completion identity missing")
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "recommendations",
                    items=[item.model_dump(mode="json", by_alias=True)
                           for item in response.result.recommendations],
                )
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "metadata", citations=[],
                    provenance=[item.provenance.model_dump(mode="json", by_alias=True)
                                for item in response.result.recommendations],
                )
                sequence += 1
                yield _discovery_stream_event(
                    sequence, "done", messageId=assistant_message_id,
                    response=response.model_dump(mode="json", by_alias=True),
                )
                api_metrics.record(
                    "discovery_retry_response_stream",
                    "SUCCEEDED",
                    time.perf_counter() - started,
                )
            except asyncio.CancelledError:
                if not send_task.done():
                    send_task.cancel()
                api_metrics.record(
                    "discovery_retry_response_stream",
                    "CANCELLED",
                    time.perf_counter() - started,
                )
                raise
            except DiscoveryApiError as error:
                sequence += 1
                yield _discovery_stream_event(
                    sequence,
                    "error", code=error.code.value,
                    message=(
                        "The answer stream was interrupted. You can retry this response."
                        if error.code == DiscoveryApiErrorCode.STREAM_INTERRUPTED
                        else "Marketplace discovery is temporarily unavailable."
                    ),
                    retryable=(error.code == DiscoveryApiErrorCode.STREAM_INTERRUPTED),
                )
                api_metrics.record(
                    "discovery_retry_response_stream",
                    _metric_result(error),
                    time.perf_counter() - started,
                )
            except Exception:
                sequence += 1
                yield _discovery_stream_event(
                    sequence,
                    "error", code=DiscoveryApiErrorCode.UNAVAILABLE.value,
                    message="Marketplace discovery is temporarily unavailable.",
                    retryable=False,
                )
                api_metrics.record(
                    "discovery_retry_response_stream",
                    "FAILED",
                    time.perf_counter() - started,
                )
            finally:
                if event_task is not None and not event_task.done():
                    event_task.cancel()
                if not send_task.done():
                    send_task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await send_task

        return StreamingResponse(
            events(),
            media_type=None,
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache, no-transform",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post(
        "/api/v1/agent/discovery/sessions/{sessionId}/exclusions",
        response_model=CreateDiscoveryExclusionResponse,
    )
    async def create_discovery_exclusion(
        sessionId: Ulid,
        body: CreateDiscoveryExclusionRequest,
        request: Request,
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(
            default=None,
            alias="Idempotency-Key",
        ),
    ) -> CreateDiscoveryExclusionResponse:
        """Persist one explicit actor-owned exclusion without a chat turn."""

        started = time.perf_counter()
        try:
            require_discovery_generation()
            actor_user_id = await discovery_actor(request, authorization)
            if (
                idempotency_key is None
                or re.fullmatch(r"[\x21-\x7e]{16,128}", idempotency_key)
                is None
            ):
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.INVALID_REQUEST,
                    400,
                    "Idempotency-Key must be 16 to 128 printable ASCII characters.",
                )
            assert discovery_service is not None
            response = await discovery_service.exclude_listing(
                actor_user_id=actor_user_id,
                session_id=sessionId,
                idempotency_key=idempotency_key,
                expected_preference_version=body.expected_preference_version,
                listing_id=body.listing_id,
                reason_code=body.reason_code,
            )
            api_metrics.record(
                "discovery_create_exclusion",
                "SUCCEEDED",
                time.perf_counter() - started,
            )
            return response
        except Exception as error:
            api_metrics.record(
                "discovery_create_exclusion",
                _metric_result(error),
                time.perf_counter() - started,
            )
            raise

    @app.post(
        "/api/v1/agent/listing-proposals",
        response_model=ListingProposalResponse,
        response_model_exclude_none=True,
        responses={
            200: {"model": ListingProposalResponse},
            201: {"model": ListingProposalResponse},
            400: {"model": ApiErrorEnvelope},
            401: {"model": ApiErrorEnvelope},
            409: {"model": ApiErrorEnvelope},
            413: {"model": ApiErrorEnvelope},
            503: {"model": ApiErrorEnvelope},
        },
    )
    async def create_listing_proposal(
        body: CreateListingProposalRequest,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> JSONResponse:
        """Create or replay one authenticated, seller-review-only proposal."""

        actor_user_id = await proposal_actor(request, authorization)
        assert listing_proposal_service is not None
        proposal, created = await listing_proposal_service.create(
            actor_user_id=actor_user_id,
            request=body,
            correlation_id=request.state.correlation_id,
        )
        return JSONResponse(
            status_code=201 if created else 200,
            content=proposal.model_dump(
                mode="json",
                by_alias=True,
                exclude_none=True,
            ),
            headers={"X-Correlation-Id": request.state.correlation_id},
        )

    @app.get(
        "/api/v1/agent/listing-proposals/{proposalId}",
        response_model=ListingProposalResponse,
        response_model_exclude_none=True,
        responses={
            401: {"model": ApiErrorEnvelope},
            404: {"model": ApiErrorEnvelope},
            410: {"model": ApiErrorEnvelope},
        },
    )
    async def get_listing_proposal(
        proposalId: Ulid,
        request: Request,
        authorization: str | None = Header(default=None),
    ) -> ListingProposalResponse:
        """Return only the current actor's proposal without resource enumeration."""

        actor_user_id = await proposal_actor(request, authorization)
        assert listing_proposal_service is not None
        return await listing_proposal_service.get(
            actor_user_id=actor_user_id,
            proposal_id=proposalId,
        )

    @app.post(
        "/api/v1/agent/listing-proposals/{proposalId}/dismiss",
        response_model=ListingProposalResponse,
        response_model_exclude_none=True,
        responses={
            400: {"model": ApiErrorEnvelope},
            401: {"model": ApiErrorEnvelope},
            404: {"model": ApiErrorEnvelope},
            409: {"model": ApiErrorEnvelope},
            410: {"model": ApiErrorEnvelope},
        },
    )
    async def dismiss_listing_proposal(
        proposalId: Ulid,
        request: Request,
        authorization: str | None = Header(default=None),
        idempotency_key: str | None = Header(
            default=None,
            alias="Idempotency-Key",
        ),
    ) -> ListingProposalResponse:
        """Idempotently dismiss and immediately purge one actor-owned proposal."""

        if idempotency_key is None:
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.INVALID_REQUEST,
                400,
                "Idempotency-Key is required.",
            )
        actor_user_id = await proposal_actor(request, authorization)
        assert listing_proposal_service is not None
        return await listing_proposal_service.dismiss(
            actor_user_id=actor_user_id,
            proposal_id=proposalId,
            idempotency_key=idempotency_key,
        )

    return app


def _create_session_validation_diagnostics(
    validation_errors: list[dict[str, object]],
) -> list[tuple[str, str]]:
    """Classify bounded validation errors without retaining locations or values."""

    diagnostics = [
        (
            _validation_field_category(item),
            _validation_error_category(item),
        )
        for item in validation_errors[:_MAX_DIAGNOSTIC_ERRORS]
    ]
    diagnostics.sort(
        key=lambda item: (
            _FIELD_CATEGORY_ORDER[item[0]],
            _ERROR_CATEGORY_ORDER[item[1]],
        )
    )
    return diagnostics or [("unknown", "other")]


def _validation_field_category(item: dict[str, object]) -> str:
    location = tuple(item.get("loc", ()))
    error_type = item.get("type")
    if error_type == "json_invalid":
        return "body_shape"
    if not location or location[0] != "body":
        return "unknown"
    if location == ("body",):
        return "body_shape"
    if location == ("body", "sessionType"):
        return "session_type"
    if location == ("body", "subject"):
        return "subject"
    if location == ("body", "subject", "type"):
        return "subject_type"
    if location == ("body", "subject", "id"):
        return "subject_id"
    if error_type == "extra_forbidden":
        if len(location) == 2:
            return "top_level_extra"
        if len(location) == 3 and location[1] == "subject":
            return "subject_extra"
    return "unknown"


def _validation_error_category(item: dict[str, object]) -> str:
    error_type = item.get("type")
    if error_type == "missing":
        return "missing"
    if error_type == "literal_error":
        return "literal_mismatch"
    if error_type == "string_pattern_mismatch":
        return "pattern_mismatch"
    if error_type in {
        "string_type",
        "model_attributes_type",
        "dict_type",
        "list_type",
    }:
        return "type_mismatch"
    if error_type == "extra_forbidden":
        return "extra_forbidden"
    if error_type == "json_invalid":
        return "invalid_json"
    if error_type in {"model_type", "missing_sentinel_error"}:
        return "invalid_body"
    return "other"


def _create_session_input_shape(body: object) -> dict[str, str]:
    """Describe allowlisted JSON shape and ID type/length without its value."""

    if not isinstance(body, dict):
        return {
            "top_level_keys": "not_object",
            "subject_keys": "not_available",
            "subject_id_type": "not_available",
            "subject_id_length": "not_available",
        }
    top_level_keys = _allowlisted_key_shape(
        body,
        ("sessionType", "subject"),
    )
    if "subject" not in body:
        return {
            "top_level_keys": top_level_keys,
            "subject_keys": "missing",
            "subject_id_type": "missing",
            "subject_id_length": "not_available",
        }
    subject = body["subject"]
    if not isinstance(subject, dict):
        return {
            "top_level_keys": top_level_keys,
            "subject_keys": "not_object",
            "subject_id_type": "missing",
            "subject_id_length": "not_available",
        }
    subject_keys = _allowlisted_key_shape(subject, ("type", "id"))
    if "id" not in subject:
        return {
            "top_level_keys": top_level_keys,
            "subject_keys": subject_keys,
            "subject_id_type": "missing",
            "subject_id_length": "not_available",
        }
    subject_id = subject["id"]
    subject_id_type = _safe_runtime_type(subject_id)
    subject_id_length = (
        str(len(subject_id))
        if isinstance(subject_id, str)
        else "not_available"
    )
    return {
        "top_level_keys": top_level_keys,
        "subject_keys": subject_keys,
        "subject_id_type": subject_id_type,
        "subject_id_length": subject_id_length,
    }


def _allowlisted_key_shape(
    value: dict[object, object],
    allowlist: tuple[str, ...],
) -> str:
    present = [key for key in allowlist if key in value]
    if any(key not in allowlist for key in value):
        present.append("other")
    return ",".join(present) or "none"


def _safe_runtime_type(value: object) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, str):
        return "string"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, dict):
        return "object"
    if isinstance(value, list):
        return "array"
    return "other"


app = create_app()


def _error_response(
    request: Request,
    status_code: int,
    code: str,
    message: str,
    details: list[ApiErrorDetail] | None = None,
) -> JSONResponse:
    envelope = ApiErrorEnvelope(
        error=ApiErrorBody(
            code=code,
            message=message,
            details=details or [],
            correlationId=request.state.correlation_id,
        )
    )
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(mode="json", by_alias=True),
        headers={"X-Correlation-Id": request.state.correlation_id},
    )


def _metric_result(error: Exception) -> str:
    if isinstance(error, AgentApiError):
        return error.code.value
    if isinstance(error, AgentPersistenceError):
        return "PERSISTENCE_ERROR"
    return "FAILED"


def _discovery_activity_label(stage: DiscoveryProgressStage) -> str:
    """Map internal milestones to fixed application-owned user-facing copy."""

    return {
        DiscoveryProgressStage.MESSAGE_ACCEPTED: "Request accepted",
        DiscoveryProgressStage.UNDERSTANDING: "Understanding your request",
        DiscoveryProgressStage.CHECKING_AVAILABILITY: "Checking current availability",
        DiscoveryProgressStage.SEARCHING: "Searching current public listings",
        DiscoveryProgressStage.CHECKING: "Checking price and availability",
        DiscoveryProgressStage.COMPOSING: "Preparing your answer",
    }[stage]


def _discovery_text_delta_chunks(delta: str) -> tuple[str, ...]:
    """Bound one live provider delta without buffering the complete answer."""

    if not delta:
        raise ValueError("Discovery text delta cannot be empty")
    return tuple(delta[index:index + 256] for index in range(0, len(delta), 256))


def _discovery_stream_event(
    sequence: int,
    event_type: str,
    **payload: object,
) -> str:
    """Serialize one strict, single-line, versioned SSE discovery event."""

    event = {
        "schemaVersion": "MARKETPLACE_DISCOVERY_STREAM_EVENT_V2",
        "sequence": sequence,
        "type": event_type,
        **payload,
    }
    data = json.dumps(
        event,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return f"event: {event_type}\ndata: {data}\n\n"


def _discovery_embedding_runtime_enabled(settings: Settings) -> bool:
    embedding = settings.discovery_embedding
    return (
        not embedding.kill_switch_enabled
        and (embedding.intake_enabled or embedding.worker_enabled)
    )


def _discovery_embedding_status(
    settings: Settings,
    runtime: DiscoveryEmbeddingRuntime | None,
) -> DiscoveryEmbeddingRuntimeStatus:
    embedding = settings.discovery_embedding
    if embedding.kill_switch_enabled or not (
        embedding.intake_enabled or embedding.worker_enabled
    ):
        return DiscoveryEmbeddingRuntimeStatus.DISABLED
    if runtime is None:
        return DiscoveryEmbeddingRuntimeStatus.UNAVAILABLE
    return runtime.status()
