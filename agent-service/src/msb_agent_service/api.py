from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import re
import time
import uuid
import logging
from typing import Awaitable, Callable

from fastapi import FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

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
from .customer_service_metrics import CustomerServiceApiMetrics
from .discovery_api import (
    CreateDiscoverySessionRequest,
    DiscoveryApiError,
    DiscoveryApiErrorCode,
    DiscoveryHistoryPage,
    DiscoverySessionResponse,
    MarketplaceDiscoveryService,
    SendDiscoveryMessageRequest,
    SendDiscoveryMessageResponse,
)
from .discovery_persistence import DiscoveryPersistenceRepository
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
LOGGER = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    knowledge_readiness_probe: KnowledgeReadinessProbe | None = None,
    ingestion_runtime_factory: KnowledgeIngestionRuntimeFactory | None = None,
    persistence_repository_factory: AgentPersistenceRepositoryFactory | None = None,
    identity_client: ActorIdentityClient | None = None,
    listing_client: ListingEligibilityClient | None = None,
    question_answerer: QuestionAnswerer | None = None,
    listing_proposal_repository_factory: (
        ListingProposalRepositoryFactory | None
    ) = None,
    listing_proposal_generator: ListingProposalGenerator | None = None,
    discovery_orchestrator: MarketplaceDiscoveryOrchestrator | None = None,
    discovery_service_override: MarketplaceDiscoveryService | None = None,
) -> FastAPI:
    """Create the service app without performing provider calls at startup."""

    runtime_settings = settings or Settings.from_env()
    runtime_settings.listing_proposal_api.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled
    )
    runtime_settings.discovery_api.validate(
        persistence_enabled=runtime_settings.agent_persistence.enabled
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
    listing_proposal_repository: ListingProposalRepositoryProtocol | None = None
    listing_proposal_service: ListingProposalReviewService | None = None
    listing_proposal_retention_task: asyncio.Task[None] | None = None
    create_listing_proposal_repository = (
        listing_proposal_repository_factory
        or ListingProposalRepository.from_agent_repository
    )
    runtime_identity_client = identity_client
    runtime_listing_client = listing_client
    orchestration_available = question_answerer is not None

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            nonlocal ingestion_runtime
            nonlocal persistence_repository
            nonlocal customer_service
            nonlocal discovery_repository
            nonlocal discovery_service
            nonlocal runtime_identity_client
            nonlocal runtime_listing_client
            nonlocal listing_proposal_repository
            nonlocal listing_proposal_service
            nonlocal listing_proposal_retention_task
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
                customer_service = AgentCustomerService(
                    persistence_repository,
                    runtime_listing_client,
                    question_answerer or DeferredQuestionAnswerer(),
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
                if (
                    runtime_settings.discovery_api.generation_enabled
                    and discovery_orchestrator is None
                    and discovery_service_override is None
                ):
                    raise RuntimeError(
                        "Enabled marketplace discovery requires an injected "
                        "LangChain orchestrator"
                    )
                if discovery_service_override is not None:
                    discovery_service = discovery_service_override
                else:
                    discovery_repository = DiscoveryPersistenceRepository(
                        persistence_repository
                    )
                    await discovery_repository.validate_schema()
                    discovery_service = MarketplaceDiscoveryService(
                        discovery_repository,
                        discovery_orchestrator,
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
            yield
        finally:
            if listing_proposal_retention_task is not None:
                listing_proposal_retention_task.cancel()
                with suppress(asyncio.CancelledError):
                    await listing_proposal_retention_task
            if persistence_repository is not None:
                await persistence_repository.close()
            if ingestion_runtime is not None:
                await ingestion_runtime.stop()
            if knowledge_client is not None:
                await close_open_search_client(knowledge_client)

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
        details = [
            ApiErrorDetail(
                field=".".join(str(part) for part in item["loc"][1:]) or None,
                message=item["msg"],
            )
            for item in error.errors()[:20]
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
        if (
            runtime_settings.openai_configured
            and knowledge_ready
            and ingestion_ready
            and category_ready
            and persistence_ready
            and customer_service_ready
            and discovery_ready
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
