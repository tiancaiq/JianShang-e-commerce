from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, IntEnum


class AgentSurface(str, Enum):
    """Customer-facing agent surfaces that exist in the current product."""

    MARKETPLACE_CUSTOMER = "MARKETPLACE_CUSTOMER"
    LISTING_CUSTOMER_SERVICE = "LISTING_CUSTOMER_SERVICE"
    SELLER_LISTING_PROPOSAL = "SELLER_LISTING_PROPOSAL"


class CapabilityFamily(str, Enum):
    MARKETPLACE_READ = "MARKETPLACE_READ"
    CUSTOMER_KNOWLEDGE_READ = "CUSTOMER_KNOWLEDGE_READ"
    CUSTOMER_WORKFLOW_CONTROL = "CUSTOMER_WORKFLOW_CONTROL"
    CUSTOMER_COMMERCE_READ = "CUSTOMER_COMMERCE_READ"
    CUSTOMER_CART_MUTATION = "CUSTOMER_CART_MUTATION"
    CUSTOMER_CHECKOUT = "CUSTOMER_CHECKOUT"
    CUSTOMER_ORDER_MUTATION = "CUSTOMER_ORDER_MUTATION"
    CUSTOMER_RETURN_REQUEST = "CUSTOMER_RETURN_REQUEST"


class CapabilityRiskLevel(IntEnum):
    INFORMATIONAL = 0
    READ_ONLY = 1
    LOW_RISK_STATE = 2
    SENSITIVE_STATE = 3
    FORBIDDEN_CUSTOMER_AUTHORITY = 4
    PROHIBITED = 5


class CapabilityDecisionCode(str, Enum):
    ALLOWED = "ALLOWED"
    UNKNOWN_CAPABILITY = "UNKNOWN_CAPABILITY"
    CAPABILITY_DISABLED = "CAPABILITY_DISABLED"
    SURFACE_MISMATCH = "SURFACE_MISMATCH"


@dataclass(frozen=True)
class CustomerCapabilityDefinition:
    name: str
    family: CapabilityFamily
    surface: AgentSurface
    risk_level: CapabilityRiskLevel
    mutates_marketplace_state: bool
    requires_confirmation: bool


@dataclass(frozen=True)
class CapabilityDecision:
    allowed: bool
    code: CapabilityDecisionCode
    definition: CustomerCapabilityDefinition | None = None


CUSTOMER_CAPABILITIES: tuple[CustomerCapabilityDefinition, ...] = (
    CustomerCapabilityDefinition(
        "retrieve_help", CapabilityFamily.CUSTOMER_KNOWLEDGE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "check_availability", CapabilityFamily.MARKETPLACE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "search_listings", CapabilityFamily.MARKETPLACE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "get_listing", CapabilityFamily.MARKETPLACE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "request_confirmation", CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "collect_listing_information",
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "get_my_cart", CapabilityFamily.CUSTOMER_COMMERCE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "list_my_orders", CapabilityFamily.CUSTOMER_COMMERCE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "get_my_order", CapabilityFamily.CUSTOMER_COMMERCE_READ,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "add_to_my_cart", CapabilityFamily.CUSTOMER_CART_MUTATION,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        True, False,
    ),
    CustomerCapabilityDefinition(
        "update_my_cart_quantity", CapabilityFamily.CUSTOMER_CART_MUTATION,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        True, False,
    ),
    CustomerCapabilityDefinition(
        "remove_from_my_cart", CapabilityFamily.CUSTOMER_CART_MUTATION,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        True, False,
    ),
    CustomerCapabilityDefinition(
        "prepare_my_checkout", CapabilityFamily.CUSTOMER_CHECKOUT,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.LOW_RISK_STATE,
        True, False,
    ),
    CustomerCapabilityDefinition(
        "get_my_checkout", CapabilityFamily.CUSTOMER_CHECKOUT,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "submit_my_checkout", CapabilityFamily.CUSTOMER_CHECKOUT,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.SENSITIVE_STATE,
        True, True,
    ),
    CustomerCapabilityDefinition(
        "preview_my_order_cancellation",
        CapabilityFamily.CUSTOMER_ORDER_MUTATION,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "cancel_my_order", CapabilityFamily.CUSTOMER_ORDER_MUTATION,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.SENSITIVE_STATE,
        True, True,
    ),
    CustomerCapabilityDefinition(
        "get_my_return", CapabilityFamily.CUSTOMER_RETURN_REQUEST,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "prepare_my_return_request", CapabilityFamily.CUSTOMER_RETURN_REQUEST,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.READ_ONLY,
        False, False,
    ),
    CustomerCapabilityDefinition(
        "submit_my_return_request", CapabilityFamily.CUSTOMER_RETURN_REQUEST,
        AgentSurface.MARKETPLACE_CUSTOMER, CapabilityRiskLevel.SENSITIVE_STATE,
        True, True,
    ),
)


DEFAULT_CUSTOMER_CAPABILITY_FAMILIES = frozenset({
    CapabilityFamily.MARKETPLACE_READ,
    CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
})


class MarketplaceCustomerCapabilityBoundary:
    """Fail-closed executable boundary for the Marketplace customer agent."""

    def __init__(
        self,
        enabled_families: frozenset[CapabilityFamily] | None = None,
    ) -> None:
        self._enabled_families = (
            DEFAULT_CUSTOMER_CAPABILITY_FAMILIES
            if enabled_families is None else frozenset(enabled_families)
        )
        self._definitions = {item.name: item for item in CUSTOMER_CAPABILITIES}

    @property
    def known_names(self) -> tuple[str, ...]:
        return tuple(self._definitions)

    @property
    def enabled_names(self) -> tuple[str, ...]:
        return tuple(
            item.name for item in CUSTOMER_CAPABILITIES
            if item.family in self._enabled_families
        )

    def evaluate(
        self,
        capability_name: str,
        *,
        surface: AgentSurface = AgentSurface.MARKETPLACE_CUSTOMER,
    ) -> CapabilityDecision:
        definition = self._definitions.get(capability_name)
        if definition is None:
            return CapabilityDecision(False, CapabilityDecisionCode.UNKNOWN_CAPABILITY)
        if definition.surface != surface:
            return CapabilityDecision(
                False, CapabilityDecisionCode.SURFACE_MISMATCH, definition
            )
        if definition.family not in self._enabled_families:
            return CapabilityDecision(
                False, CapabilityDecisionCode.CAPABILITY_DISABLED, definition
            )
        return CapabilityDecision(True, CapabilityDecisionCode.ALLOWED, definition)
