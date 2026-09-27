from __future__ import annotations

from dataclasses import dataclass

from prometheus_client import CollectorRegistry

from msb_agent_service.config import Settings
from msb_agent_service.discovery_embedding_contract import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
)
from msb_agent_service.embedding_provider import OpenAIEmbeddingProvider
from msb_agent_service.marketplace_discovery import ProductMarketplaceDiscoveryClient
from msb_agent_service.marketplace_discovery_runtime import (
    DiscoveryEmbeddingMetrics,
    _Utf8ByteEncoding,
)
from msb_agent_service.marketplace_listing_retrieval import HybridMarketplaceDiscoveryClient

from .orchestrator import MarketplaceAgentV2Orchestrator
from .provider import OpenAIMarketplaceAgentV2Model
from .tools import MarketplaceAgentV2ToolRegistry
from .capabilities import (
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from .commerce import CommerceReadClient


@dataclass
class MarketplaceAgentV2Runtime:
    orchestrator: MarketplaceAgentV2Orchestrator
    model: OpenAIMarketplaceAgentV2Model
    embedding_provider: OpenAIEmbeddingProvider | None = None

    async def close(self) -> None:
        try:
            if self.embedding_provider is not None:
                await self.embedding_provider.close()
        finally:
            await self.orchestrator.close()


def build_marketplace_agent_v2_runtime(
    settings: Settings,
    *,
    registry: CollectorRegistry | None = None,
) -> MarketplaceAgentV2Runtime:
    """Compose V2 only after its independent complete activation gate passes."""

    selected = settings.marketplace_agent_v2
    if (
        not selected.enabled
        or not selected.provider_enabled
        or not settings.openai_configured
        or selected.product_service_url is None
    ):
        raise RuntimeError("Marketplace Agent V2 generation is not fully enabled")
    product = ProductMarketplaceDiscoveryClient(
        selected.product_service_url,
        timeout_seconds=selected.dependency_timeout_seconds,
        internal_service_token=selected.product_service_token,
    )
    embedding_provider = None
    if selected.hybrid_retrieval_enabled:
        if not selected.product_service_token:
            raise RuntimeError("Marketplace Agent V2 hybrid search requires Product authentication")
        embedding_provider = OpenAIEmbeddingProvider(
            api_key=str(settings.openai_api_key),
            model=EMBEDDING_MODEL,
            dimensions=EMBEDDING_DIMENSIONS,
            timeout_seconds=settings.openai_timeout_seconds,
            max_retries=0,
            maximum_inputs=1,
            maximum_input_tokens=512,
            maximum_total_tokens=512,
            metrics=DiscoveryEmbeddingMetrics(registry or CollectorRegistry()),
            encoding=_Utf8ByteEncoding(),
        )
        product = HybridMarketplaceDiscoveryClient(
            product=product,
            product_base_url=selected.product_service_url,
            product_service_token=selected.product_service_token,
            embedding_provider=embedding_provider,
            timeout_seconds=selected.dependency_timeout_seconds,
            query_timeout_seconds=min(settings.openai_timeout_seconds, 8.0),
            response_schema_version="MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
        )
    commerce = None
    enabled_families = {
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
    }
    if selected.commerce_reads_enabled:
        if selected.order_service_url is None:
            raise RuntimeError("Marketplace Agent V2 commerce reads require Order Service")
        commerce = CommerceReadClient(
            selected.order_service_url,
            auth_base_url=selected.auth_service_url,
            timeout_seconds=selected.dependency_timeout_seconds,
        )
        enabled_families.add(CapabilityFamily.CUSTOMER_COMMERCE_READ)
    if selected.cart_mutations_enabled:
        if commerce is None:
            raise RuntimeError(
                "Marketplace Agent V2 cart mutations require commerce reads"
            )
        enabled_families.add(CapabilityFamily.CUSTOMER_CART_MUTATION)
    if selected.checkout_enabled:
        if commerce is None:
            raise RuntimeError(
                "Marketplace Agent V2 checkout requires commerce reads"
            )
        enabled_families.add(CapabilityFamily.CUSTOMER_CHECKOUT)
    if selected.order_mutations_enabled:
        if commerce is None:
            raise RuntimeError(
                "Marketplace Agent V2 order mutations require commerce reads"
            )
        enabled_families.add(CapabilityFamily.CUSTOMER_ORDER_MUTATION)
    if selected.return_requests_enabled:
        if commerce is None:
            raise RuntimeError(
                "Marketplace Agent V2 return requests require commerce reads"
            )
        enabled_families.add(CapabilityFamily.CUSTOMER_RETURN_REQUEST)
    capability_boundary = MarketplaceCustomerCapabilityBoundary(
        frozenset(enabled_families)
    )
    tool_registry = MarketplaceAgentV2ToolRegistry(
        product,
        commerce=commerce,
        direct_result_max=selected.direct_result_max,
        clarification_result_min=selected.clarification_result_min,
        max_clarification_options=selected.max_clarification_options,
        default_discovery_top_k=selected.default_discovery_top_k,
        max_discovery_top_k=selected.max_discovery_top_k,
        capability_boundary=capability_boundary,
    )
    model = OpenAIMarketplaceAgentV2Model(
        settings, allowed_tool_names=tool_registry.names
    )
    orchestrator = MarketplaceAgentV2Orchestrator(
        model,
        tool_registry,
        model_timeout_seconds=selected.model_call_timeout_seconds,
        confirmation_execution_enabled=selected.generation_enabled,
    )
    return MarketplaceAgentV2Runtime(
        orchestrator=orchestrator,
        model=model,
        embedding_provider=embedding_provider,
    )
