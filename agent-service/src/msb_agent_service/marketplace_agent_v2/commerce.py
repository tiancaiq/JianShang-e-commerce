from __future__ import annotations

import asyncio
import hashlib
import json
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from msb_agent_service.agent_persistence import new_ulid

from .schemas import (
    CartItemSnapshot,
    CommerceMoney,
    CustomerCartItemReference,
    CustomerCartMutationReference,
    CustomerCartSnapshot,
    CustomerOrderDetail,
    CustomerOrderCancellationSnapshot,
    CustomerOrderGroupDetail,
    CustomerOrderItemSnapshot,
    CustomerOrderItemReference,
    CustomerOrderReference,
    CustomerOrderStoreSummary,
    CustomerOrderSummary,
    CustomerOrderTimelineEntry,
    CustomerReturnSnapshot,
    CustomerShipmentSummary,
    CustomerCheckoutItemSnapshot,
    CustomerCheckoutSnapshot,
    MarketplaceAgentV2PendingInteraction,
    CancelOrderConfirmationArguments,
    SubmitReturnConfirmationArguments,
    SubmitCheckoutConfirmationArguments,
    ToolObservation,
)


_MAX_RESPONSE_BYTES = 512_000


def _camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class _OwnerResponse(BaseModel):
    """Accepts additive owner-service fields; normalization below is allowlist-only."""

    model_config = ConfigDict(
        alias_generator=_camel,
        populate_by_name=True,
        extra="ignore",
    )


class _ErrorBody(_OwnerResponse):
    code: str
    message: str | None = None
    details: tuple[dict[str, str], ...] = ()


class _ErrorEnvelope(_OwnerResponse):
    error: _ErrorBody


class _Money(_OwnerResponse):
    currency: str
    amount: Decimal


class _CartItem(_OwnerResponse):
    listing_id: str
    title: str
    store_name: str | None = None
    quantity: int
    observed_price: Decimal
    currency: str


class _Cart(_OwnerResponse):
    version: int
    expires_at: datetime | None = None
    item_count: int
    total_quantity: int
    totals: tuple[_Money, ...] = ()
    items: tuple[_CartItem, ...] = ()


class _CartValidationItem(_OwnerResponse):
    listing_id: str
    title: str
    requested_quantity: int
    available_quantity: int | None = None
    observed_price: Decimal
    current_price: Decimal | None = None
    observed_currency: str
    current_currency: str | None = None
    status: str


class _CartValidation(_OwnerResponse):
    cart_version: int
    checkout_ready: bool
    item_count: int
    total_quantity: int
    validated_totals: tuple[_Money, ...] = ()
    items: tuple[_CartValidationItem, ...] = ()


class _Address(_OwnerResponse):
    id: str
    label: str | None = None
    city: str
    region: str
    country_code: str
    is_default: bool
    version: int


class _AddressEnvelope(_OwnerResponse):
    data: tuple[_Address, ...] = ()


class _CheckoutReservation(_OwnerResponse):
    id: str | None = None
    status: str | None = None
    release_status: str


class _CheckoutAddress(_OwnerResponse):
    source_address_id: str
    source_version: int
    label: str | None = None
    city: str
    region: str
    country_code: str


class _CheckoutItem(_OwnerResponse):
    listing_id: str
    catalog_version: int
    title: str
    quantity: int
    unit_price: Decimal
    line_total: Decimal


class _Checkout(_OwnerResponse):
    id: str
    status: str
    cart_version: int
    currency: str
    subtotal: Decimal
    shipping: Decimal
    tax: Decimal
    discount: Decimal
    total: Decimal
    expires_at: datetime
    reservation: _CheckoutReservation
    address: _CheckoutAddress
    items: tuple[_CheckoutItem, ...]


class _PaymentIntent(_OwnerResponse):
    id: str
    checkout_id: str
    status: str
    version: int
    amount: Decimal
    currency: str
    expires_at: datetime


class _OrderResolution(_OwnerResponse):
    order_id: str | None = None
    confirmed: bool


@dataclass(frozen=True)
class CheckoutConfirmationState:
    valid: bool
    reason: str
    current_versions: dict[str, str]
    current_snapshots: dict[str, str]


@dataclass(frozen=True)
class OrderCancellationConfirmationState:
    valid: bool
    reason: str
    current_versions: dict[str, str]
    current_snapshots: dict[str, str]


@dataclass(frozen=True)
class ReturnConfirmationState:
    valid: bool
    reason: str
    current_versions: dict[str, str]
    current_snapshots: dict[str, str]


class _OrderGroupSummary(_OwnerResponse):
    store_name: str | None = None
    status: str
    total_amount: Decimal
    currency: str


class _OrderSummary(_OwnerResponse):
    order_id: str
    status: str
    payment_status: str
    total_amount: Decimal
    currency: str
    created_at: datetime
    updated_at: datetime
    groups: tuple[_OrderGroupSummary, ...] = ()


class _OrderPageMetadata(_OwnerResponse):
    next_cursor: str | None = Field(default=None, max_length=512)
    has_more: bool


class _OrderPage(_OwnerResponse):
    items: tuple[_OrderSummary, ...] = ()
    page: _OrderPageMetadata


class _OrderItem(_OwnerResponse):
    listing_id: str
    title: str
    unit_price: Decimal
    currency: str
    quantity: int
    line_total: Decimal


class _TimelineEntry(_OwnerResponse):
    status: str
    occurred_at: datetime


class _Shipment(_OwnerResponse):
    carrier_display_name: str | None = None
    service_display_name: str | None = None
    status: str
    shipped_at: datetime | None = None
    delivered_at: datetime | None = None


class _OrderGroupDetail(_OrderGroupSummary):
    business_order_id: str
    version: int = 0
    items: tuple[_OrderItem, ...] = ()
    timeline: tuple[_TimelineEntry, ...] = ()
    shipment: _Shipment | None = None


class _OrderRefund(_OwnerResponse):
    status: str


class _OrderCancellation(_OwnerResponse):
    eligible: bool
    ineligibility_code: str | None = None
    request_id: str | None = None
    request_status: str | None = None
    inventory_status: str | None = None
    refund: _OrderRefund | None = None


class _OrderDetail(_OwnerResponse):
    order_id: str
    status: str
    payment_status: str
    total_amount: Decimal
    currency: str
    version: int = 0
    created_at: datetime
    updated_at: datetime
    groups: tuple[_OrderGroupDetail, ...] = ()
    cancellation: _OrderCancellation | None = None


class _OrderCancellationResponse(_OwnerResponse):
    order_id: str
    cancellation_request_id: str
    status: str
    request_status: str
    version: int
    requested_at: datetime


class _BusinessOrderReturn(_OwnerResponse):
    eligible: bool
    ineligibility_code: str | None = None
    return_id: str | None = None
    order_id: str
    business_order_id: str
    store_name: str | None = None
    reason_code: str | None = None
    buyer_comment: str | None = None
    requested_at: datetime | None = None
    window_expires_at: datetime | None = None
    status: str | None = None
    refund_status: str | None = None
    received_at: datetime | None = None
    refund_amount: Decimal | None = None
    currency: str
    completed_at: datetime | None = None
    version: int
    timeline: tuple[_TimelineEntry, ...] = ()


class CommerceReadClient:
    """Uses the existing actor-owned Order APIs without becoming cart authority."""

    def __init__(
        self,
        base_url: str,
        *,
        auth_base_url: str | None = None,
        timeout_seconds: float,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._auth_base_url = (
            base_url.rstrip("/")
            if auth_base_url is None else auth_base_url.rstrip("/")
        )
        self._timeout = timeout_seconds
        self._transport = transport

    async def get_my_cart(
        self, *, authorization: str | None, correlation_id: str
    ) -> ToolObservation:
        response = await self._get(
            "/api/v1/cart", authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return response.model_copy(update={"tool": "get_my_cart"})
        if response.status_code != 200:
            return _failure("get_my_cart", response.status_code)
        try:
            cart = _Cart.model_validate(_json(response))
            normalized = _normalize_cart(cart)
        except (ValueError, ValidationError):
            return _unavailable("get_my_cart")
        return ToolObservation(
            tool="get_my_cart",
            status="SUCCEEDED",
            reason="CART_AVAILABLE" if normalized.items else "CART_EMPTY",
            resultCount=normalized.item_count,
            cart=normalized,
            cartItemReferences=_cart_references(normalized),
        )

    async def add_to_my_cart(
        self,
        *,
        listing_id: str,
        quantity: int,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        return await self._mutate_cart(
            tool="add_to_my_cart",
            operation="ADD",
            listing_id=listing_id,
            quantity=quantity,
            action_reference=action_reference,
            authorization=authorization,
            correlation_id=correlation_id,
        )

    async def update_my_cart_quantity(
        self,
        *,
        listing_id: str,
        quantity: int,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        return await self._mutate_cart(
            tool="update_my_cart_quantity",
            operation="UPDATE_QUANTITY",
            listing_id=listing_id,
            quantity=quantity,
            action_reference=action_reference,
            authorization=authorization,
            correlation_id=correlation_id,
        )

    async def remove_from_my_cart(
        self,
        *,
        listing_id: str,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        return await self._mutate_cart(
            tool="remove_from_my_cart",
            operation="REMOVE",
            listing_id=listing_id,
            quantity=None,
            action_reference=action_reference,
            authorization=authorization,
            correlation_id=correlation_id,
        )

    async def prepare_my_checkout(
        self,
        *,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Creates the normal reversible checkout/reservation and binds its exact facts."""

        started = time.monotonic()
        if not _valid_bearer(authorization):
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="INVALID_ARGUMENTS",
            ), started)
        validation_response = await self._request(
            "POST", self._base_url, "/api/v1/cart/validate",
            authorization=authorization, correlation_id=correlation_id,
        )
        if validation_response is None:
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if validation_response.status_code != 200:
            return _timed(_checkout_failure(
                "prepare_my_checkout", validation_response
            ), started)
        try:
            validation = _CartValidation.model_validate(_json(validation_response))
        except (ValueError, ValidationError):
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if not validation.items:
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="CHECKOUT_EMPTY",
            ), started)
        if not validation.checkout_ready:
            reason = (
                "PRICE_CHANGED"
                if any(
                    item.current_price is not None
                    and item.current_price != item.observed_price
                    for item in validation.items
                )
                else "ITEM_UNAVAILABLE"
            )
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED", reason=reason,
            ), started)

        address_response = await self._request(
            "GET", self._auth_base_url, "/api/v1/users/me/addresses",
            authorization=authorization, correlation_id=correlation_id,
        )
        if address_response is None:
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if address_response.status_code in {401, 403}:
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        try:
            addresses = _AddressEnvelope.model_validate(_json(address_response)).data
        except (ValueError, ValidationError):
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if not addresses:
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="CHECKOUT_ADDRESS_REQUIRED",
            ), started)
        address = next((item for item in addresses if item.is_default), addresses[0])

        checkout_response = await self._request(
            "POST", self._base_url, "/api/v1/checkouts",
            authorization=authorization, correlation_id=correlation_id,
            headers={"Idempotency-Key": f"agent-checkout-{action_reference}"},
            body={"cartVersion": validation.cart_version, "addressId": address.id},
        )
        if checkout_response is None:
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if checkout_response.status_code == 409 and _error_code(
            checkout_response
        ) == "CHECKOUT_ALREADY_ACTIVE":
            active_id = _error_detail(checkout_response, "checkoutId")
            if active_id is not None:
                checkout_response = await self._request(
                    "GET", self._base_url, f"/api/v1/checkouts/{active_id}",
                    authorization=authorization, correlation_id=correlation_id,
                )
        if checkout_response is None or checkout_response.status_code not in {200, 201}:
            return _timed(
                _checkout_unavailable("prepare_my_checkout")
                if checkout_response is None
                else _checkout_failure("prepare_my_checkout", checkout_response),
                started,
            )
        try:
            checkout = _Checkout.model_validate(_json(checkout_response))
            normalized = _normalize_checkout(checkout)
            binding = _checkout_binding(checkout, validation)
        except (ValueError, ValidationError):
            return _timed(_checkout_unavailable("prepare_my_checkout"), started)
        if (
            checkout.cart_version != validation.cart_version
            or checkout.status != "PENDING_PAYMENT"
            or checkout.reservation.status != "ACTIVE"
            or checkout.reservation.release_status != "NOT_REQUIRED"
        ):
            return _timed(ToolObservation(
                tool="prepare_my_checkout", status="REJECTED",
                reason="CHECKOUT_STALE", checkout=normalized,
            ), started)
        confirmation_id = new_ulid()
        pending = MarketplaceAgentV2PendingInteraction(
            id=confirmation_id,
            confirmationId=confirmation_id,
            type="CONFIRM_ACTION",
            action="SUBMIT_CHECKOUT",
            arguments=binding.model_dump(mode="json", by_alias=True),
            summary=_checkout_confirmation_summary(normalized),
            status="WAITING",
            createdAt=datetime.now(UTC),
        )
        return _timed(ToolObservation(
            tool="prepare_my_checkout", status="SUCCEEDED",
            reason="CHECKOUT_READY", resultCount=len(normalized.items),
            checkout=normalized, pendingInteraction=pending,
        ), started)

    async def get_my_checkout(
        self,
        *,
        checkout_id: str,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        response = await self._request(
            "GET", self._base_url, f"/api/v1/checkouts/{checkout_id}",
            authorization=authorization, correlation_id=correlation_id,
        )
        if response is None:
            return _checkout_unavailable("get_my_checkout")
        if response.status_code != 200:
            return _checkout_failure("get_my_checkout", response)
        try:
            checkout = _normalize_checkout(_Checkout.model_validate(_json(response)))
        except (ValueError, ValidationError):
            return _checkout_unavailable("get_my_checkout")
        return ToolObservation(
            tool="get_my_checkout", status="SUCCEEDED", reason="CHECKOUT_READY",
            resultCount=len(checkout.items), checkout=checkout,
        )

    async def cancel_prepared_checkout(
        self,
        *,
        checkout_id: str,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> bool:
        """Cancels one actor-owned unpaid checkout and verifies its hold release."""

        if (
            not _valid_bearer(authorization)
            or not _valid_action_reference(action_reference)
        ):
            return False
        path = f"/api/v1/checkouts/{checkout_id}/cancel"
        headers = {
            "Idempotency-Key": f"agent-checkout-cancel-{action_reference}"
        }
        response = await self._request(
            "POST", self._base_url, path,
            authorization=authorization, correlation_id=correlation_id,
            headers=headers,
        )
        if response is None or response.status_code >= 500:
            response = await self._request(
                "POST", self._base_url, path,
                authorization=authorization, correlation_id=correlation_id,
                headers=headers,
            )
        if response is not None and response.status_code == 200:
            try:
                checkout = _Checkout.model_validate(_json(response))
                if _checkout_release_complete(checkout):
                    return True
            except (ValueError, ValidationError):
                pass
        observed = await self._request(
            "GET", self._base_url, f"/api/v1/checkouts/{checkout_id}",
            authorization=authorization, correlation_id=correlation_id,
        )
        if observed is None or observed.status_code != 200:
            return False
        try:
            return _checkout_release_complete(
                _Checkout.model_validate(_json(observed))
            )
        except (ValueError, ValidationError):
            return False

    async def revalidate_checkout_confirmation(
        self,
        *,
        binding: SubmitCheckoutConfirmationArguments,
        authorization: str | None,
        correlation_id: str,
    ) -> CheckoutConfirmationState:
        checkout_response, cart_response = await asyncio.gather(
            self._request(
                "GET", self._base_url,
                f"/api/v1/checkouts/{binding.checkout_id}",
                authorization=authorization, correlation_id=correlation_id,
            ),
            self._request(
                "POST", self._base_url, "/api/v1/cart/validate",
                authorization=authorization, correlation_id=correlation_id,
            ),
        )
        if checkout_response is None or cart_response is None:
            return CheckoutConfirmationState(False, "CHECKOUT_UPSTREAM_UNAVAILABLE", {}, {})
        if checkout_response.status_code in {401, 403} or cart_response.status_code in {401, 403}:
            return CheckoutConfirmationState(False, "AUTHENTICATION_REQUIRED", {}, {})
        if checkout_response.status_code != 200:
            return CheckoutConfirmationState(False, "CHECKOUT_NOT_FOUND", {}, {})
        try:
            checkout = _Checkout.model_validate(_json(checkout_response))
            validation = _CartValidation.model_validate(_json(cart_response))
        except (ValueError, ValidationError):
            return CheckoutConfirmationState(False, "CHECKOUT_UPSTREAM_UNAVAILABLE", {}, {})
        checkout_fingerprint = _checkout_fingerprint(checkout)
        cart_fingerprint = _cart_validation_fingerprint(validation)
        versions = {
            f"CHECKOUT:{checkout.id}": str(checkout.cart_version),
            "CART:CURRENT": str(validation.cart_version),
        }
        snapshots = {
            f"CHECKOUT:{checkout.id}": checkout_fingerprint,
            "CART:CURRENT": cart_fingerprint,
        }
        reason = "CHECKOUT_READY"
        if validation.cart_version != binding.cart_version:
            reason = "CHECKOUT_STALE"
        elif cart_fingerprint != binding.cart_fingerprint:
            reason = (
                "PRICE_CHANGED"
                if _checkout_prices_changed(checkout, validation)
                else "CHECKOUT_STALE"
            )
        elif checkout_fingerprint != binding.checkout_fingerprint:
            reason = "CHECKOUT_STALE"
        elif checkout.total != binding.total or checkout.currency != binding.currency:
            reason = "PRICE_CHANGED"
        elif (
            checkout.status != "PENDING_PAYMENT"
            or checkout.reservation.status != "ACTIVE"
            or checkout.reservation.release_status != "NOT_REQUIRED"
            or checkout.expires_at <= datetime.now(checkout.expires_at.tzinfo)
        ):
            reason = "ITEM_UNAVAILABLE"
        return CheckoutConfirmationState(
            reason == "CHECKOUT_READY", reason, versions, snapshots
        )

    async def submit_my_checkout(
        self,
        *,
        checkout_id: str,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Submits one confirmed checkout through the existing Order/Payment boundary."""

        if not _valid_bearer(authorization):
            return ToolObservation(
                tool="submit_my_checkout", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            )
        if not _valid_action_reference(action_reference):
            return ToolObservation(
                tool="submit_my_checkout", status="REJECTED",
                reason="CONFIRMATION_REQUIRED",
            )
        action_key = f"agent-action-{action_reference}"
        intent_response = await self._request(
            "POST", self._base_url,
            f"/api/v1/checkouts/{checkout_id}/payment-intent",
            authorization=authorization, correlation_id=correlation_id,
            headers={"Idempotency-Key": action_key},
        )
        if intent_response is None or intent_response.status_code >= 500:
            intent_response = await self._request(
                "POST", self._base_url,
                f"/api/v1/checkouts/{checkout_id}/payment-intent",
                authorization=authorization, correlation_id=correlation_id,
                headers={"Idempotency-Key": action_key},
            )
        intent = await self._resolve_payment_intent(
            checkout_id=checkout_id,
            response=intent_response,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if intent is None:
            return ToolObservation(
                tool="submit_my_checkout", status="FAILED",
                reason="PAYMENT_OUTCOME_UNKNOWN",
            )
        if intent.status == "FAILED":
            return ToolObservation(
                tool="submit_my_checkout", status="REJECTED", reason="PAYMENT_FAILED",
            )
        if intent.status == "REQUIRES_ACTION":
            completion = await self._request(
                "POST", self._base_url,
                f"/api/v1/checkouts/{checkout_id}/complete-demo-payment",
                authorization=authorization, correlation_id=correlation_id,
            )
            if completion is None or completion.status_code >= 500:
                intent = await self._resolve_payment_intent(
                    checkout_id=checkout_id,
                    response=None,
                    authorization=authorization,
                    correlation_id=correlation_id,
                )
                if intent is None or intent.status not in {"SUCCEEDED", "PROCESSING"}:
                    return ToolObservation(
                        tool="submit_my_checkout", status="FAILED",
                        reason="PAYMENT_OUTCOME_UNKNOWN",
                    )
            elif completion.status_code not in {200, 201}:
                return _checkout_failure("submit_my_checkout", completion)
        return await self._resolve_confirmed_order(
            checkout_id=checkout_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )

    async def _resolve_payment_intent(
        self,
        *,
        checkout_id: str,
        response: httpx.Response | None,
        authorization: str,
        correlation_id: str,
    ) -> _PaymentIntent | None:
        candidates = [response]
        candidates.append(await self._request(
            "GET", self._base_url,
            f"/api/v1/checkouts/{checkout_id}/payment-intent",
            authorization=authorization, correlation_id=correlation_id,
        ))
        for candidate in candidates:
            if candidate is None or candidate.status_code != 200 and candidate.status_code != 201:
                continue
            try:
                intent = _PaymentIntent.model_validate(_json(candidate))
            except (ValueError, ValidationError):
                continue
            if intent.checkout_id == checkout_id:
                return intent
        return None

    async def _resolve_confirmed_order(
        self,
        *,
        checkout_id: str,
        authorization: str,
        correlation_id: str,
    ) -> ToolObservation:
        for attempt in range(11):
            response = await self._request(
                "GET", self._base_url,
                f"/api/v1/checkouts/{checkout_id}/confirmed-order",
                authorization=authorization, correlation_id=correlation_id,
            )
            if response is not None and response.status_code in {200, 202}:
                try:
                    resolution = _OrderResolution.model_validate(_json(response))
                except (ValueError, ValidationError):
                    resolution = None
                if resolution is not None and resolution.confirmed and resolution.order_id:
                    order = await self.get_my_order(
                        order_id=resolution.order_id,
                        authorization=authorization,
                        correlation_id=correlation_id,
                    )
                    if order.status == "SUCCEEDED" and order.order is not None:
                        return order.model_copy(update={
                            "tool": "submit_my_checkout",
                            "reason": "ORDER_CONFIRMED",
                        })
            if attempt < 10:
                await asyncio.sleep(0.1)
        checkout = await self.get_my_checkout(
            checkout_id=checkout_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        return checkout.model_copy(update={
            "tool": "submit_my_checkout",
            "status": "SUCCEEDED",
            "reason": "ORDER_CONFIRMATION_PENDING",
        })

    async def _mutate_cart(
        self,
        *,
        tool: Literal[
            "add_to_my_cart", "update_my_cart_quantity", "remove_from_my_cart"
        ],
        operation: Literal["ADD", "UPDATE_QUANTITY", "REMOVE"],
        listing_id: str,
        quantity: int | None,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Binds one invocation to one optimistic, replay-safe cart command."""

        started = time.monotonic()
        if not _valid_bearer(authorization):
            return _timed(ToolObservation(
                tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
            ), started)
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS"
            ), started)

        current_response = await self._get(
            "/api/v1/cart",
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(current_response, ToolObservation):
            return _timed(current_response.model_copy(update={
                "tool": tool,
                "reason": (
                    "CART_UPSTREAM_UNAVAILABLE"
                    if current_response.reason == "COMMERCE_UPSTREAM_UNAVAILABLE"
                    else current_response.reason
                ),
            }), started)
        if current_response.status_code != 200:
            return _timed(_cart_failure(tool, operation, current_response), started)
        try:
            before = _normalize_cart(_Cart.model_validate(_json(current_response)))
        except (ValueError, ValidationError):
            return _timed(ToolObservation(
                tool=tool, status="FAILED", reason="CART_UPSTREAM_UNAVAILABLE"
            ), started)

        expected_version = before.version
        idempotency_key = f"agent-cart-{action_reference}"
        if operation == "ADD":
            method = "POST"
            path = "/api/v1/cart/items"
            body: dict[str, object] | None = {
                "listingId": listing_id, "quantity": quantity,
            }
        elif operation == "UPDATE_QUANTITY":
            method = "PATCH"
            path = f"/api/v1/cart/items/{listing_id}"
            body = {"quantity": quantity}
        else:
            method = "DELETE"
            path = f"/api/v1/cart/items/{listing_id}"
            body = None

        response = await self._write(
            method,
            path,
            body=body,
            expected_version=expected_version,
            idempotency_key=idempotency_key,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        # The Order-owned key makes one same-request replay safe even if the first
        # response was lost after Redis committed the command.
        if response is None or response.status_code >= 500:
            replay = await self._write(
                method,
                path,
                body=body,
                expected_version=expected_version,
                idempotency_key=idempotency_key,
                authorization=authorization,
                correlation_id=correlation_id,
            )
            if replay is not None and replay.status_code < 500:
                response = replay
            else:
                reconciled = await self._get(
                    "/api/v1/cart",
                    authorization=authorization,
                    correlation_id=correlation_id,
                )
                if not isinstance(reconciled, ToolObservation) and reconciled.status_code == 200:
                    try:
                        cart = _normalize_cart(_Cart.model_validate(_json(reconciled)))
                        if _mutation_visible(
                            cart,
                            operation=operation,
                            listing_id=listing_id,
                            quantity=quantity,
                        ):
                            return _timed(_cart_mutation_success(
                                tool=tool,
                                operation=operation,
                                listing_id=listing_id,
                                quantity=quantity,
                                cart=cart,
                                reason="CART_MUTATION_RECONCILED",
                            ), started)
                    except (ValueError, ValidationError):
                        pass
                return _timed(ToolObservation(
                    tool=tool,
                    status="FAILED",
                    reason="OUTCOME_UNKNOWN",
                    cartMutationReference=CustomerCartMutationReference(
                        listingId=listing_id,
                        operation=operation,
                        requestedQuantity=quantity,
                    ),
                ), started)

        assert response is not None
        if response.status_code != 200:
            return _timed(_cart_failure(tool, operation, response), started)
        try:
            cart = _normalize_cart(_Cart.model_validate(_json(response)))
        except (ValueError, ValidationError):
            return _timed(ToolObservation(
                tool=tool, status="FAILED", reason="OUTCOME_UNKNOWN",
                cartMutationReference=CustomerCartMutationReference(
                    listingId=listing_id,
                    operation=operation,
                    requestedQuantity=quantity,
                ),
            ), started)
        return _timed(_cart_mutation_success(
            tool=tool,
            operation=operation,
            listing_id=listing_id,
            quantity=quantity,
            cart=cart,
            reason={
                "ADD": "CART_ITEM_ADDED",
                "UPDATE_QUANTITY": "CART_QUANTITY_UPDATED",
                "REMOVE": "CART_ITEM_REMOVED",
            }[operation],
        ), started)

    async def list_my_orders(
        self,
        *,
        limit: int,
        cursor: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        params: dict[str, str] = {"limit": str(limit)}
        if cursor is not None:
            params["cursor"] = cursor
        response = await self._get(
            "/api/v1/orders", params=params, authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return response.model_copy(update={"tool": "list_my_orders"})
        if response.status_code != 200:
            return _failure("list_my_orders", response.status_code)
        try:
            page = _OrderPage.model_validate(_json(response))
            orders = tuple(_normalize_order_summary(item) for item in page.items)
        except (ValueError, ValidationError):
            return _unavailable("list_my_orders")
        return ToolObservation(
            tool="list_my_orders",
            status="SUCCEEDED",
            reason="ORDERS_AVAILABLE" if orders else "NO_ORDERS",
            resultCount=len(orders),
            orders=orders,
            orderReferences=tuple(
                CustomerOrderReference(
                    orderId=item.order_id,
                    position=index,
                    createdAt=item.created_at,
                )
                for index, item in enumerate(orders, 1)
            ),
            nextCursor=page.page.next_cursor,
            hasMore=page.page.has_more,
        )

    async def get_my_order(
        self,
        *,
        order_id: str,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        response = await self._get(
            f"/api/v1/orders/{order_id}", authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return response.model_copy(update={"tool": "get_my_order"})
        if response.status_code == 404 and _error_code(response) == "ORDER_NOT_FOUND":
            return ToolObservation(
                tool="get_my_order", status="SUCCEEDED", reason="ORDER_NOT_FOUND",
                resultCount=0,
            )
        if response.status_code != 200:
            return _failure("get_my_order", response.status_code)
        try:
            source = _OrderDetail.model_validate(_json(response))
            detail = _normalize_order_detail(source)
        except (ValueError, ValidationError):
            return _unavailable("get_my_order")
        return ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            resultCount=1,
            order=detail,
            orderReferences=(CustomerOrderReference(orderId=detail.order_id),),
            orderItemReferences=tuple(
                CustomerOrderItemReference(
                    orderId=detail.order_id,
                    listingId=item.listing_id,
                    title=item.title,
                    storeName=group.store_name,
                )
                for group in detail.groups for item in group.items
            ),
        )

    async def preview_my_order_cancellation(
        self,
        *,
        order_id: str,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Prepare one exact cancellation from the Order-owned buyer-safe projection."""

        started = time.monotonic()
        if not _valid_bearer(authorization):
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason="INVALID_ARGUMENTS",
            ), started)
        response = await self._get(
            f"/api/v1/orders/{order_id}", authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return _timed(_order_cancellation_unavailable(
                "preview_my_order_cancellation"
            ), started)
        if response.status_code == 404:
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason="ORDER_NOT_FOUND",
            ), started)
        if response.status_code in {401, 403}:
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        if response.status_code != 200:
            return _timed(_order_cancellation_unavailable(
                "preview_my_order_cancellation"
            ), started)
        try:
            source = _OrderDetail.model_validate(_json(response))
            detail = _normalize_order_detail(source)
        except (ValueError, ValidationError):
            return _timed(_order_cancellation_unavailable(
                "preview_my_order_cancellation"
            ), started)
        if source.cancellation is None:
            return _timed(_order_cancellation_unavailable(
                "preview_my_order_cancellation"
            ), started)
        if source.cancellation.request_id is not None:
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason="ORDER_CANCELLATION_ALREADY_REQUESTED", order=detail,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        if not source.cancellation.eligible:
            return _timed(ToolObservation(
                tool="preview_my_order_cancellation", status="REJECTED",
                reason=_cancellation_ineligibility_reason(
                    source.cancellation.ineligibility_code
                ),
                order=detail,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        binding = _order_cancellation_binding(source)
        confirmation_id = new_ulid()
        pending = MarketplaceAgentV2PendingInteraction(
            id=confirmation_id,
            confirmationId=confirmation_id,
            type="CONFIRM_ACTION",
            action="CANCEL_ORDER",
            arguments=binding.model_dump(mode="json", by_alias=True),
            summary=_order_cancellation_confirmation_summary(detail),
            status="WAITING",
            createdAt=datetime.now(UTC),
        )
        return _timed(ToolObservation(
            tool="preview_my_order_cancellation", status="SUCCEEDED",
            reason="ORDER_CANCELLATION_READY", resultCount=1, order=detail,
            orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            pendingInteraction=pending,
        ), started)

    async def revalidate_order_cancellation_confirmation(
        self,
        *,
        binding: CancelOrderConfirmationArguments,
        authorization: str | None,
        correlation_id: str,
    ) -> OrderCancellationConfirmationState:
        response = await self._get(
            f"/api/v1/orders/{binding.order_id}", authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return OrderCancellationConfirmationState(
                False, response.reason, {}, {}
            )
        if response.status_code in {401, 403}:
            return OrderCancellationConfirmationState(
                False, "AUTHENTICATION_REQUIRED", {}, {}
            )
        if response.status_code != 200:
            return OrderCancellationConfirmationState(
                False,
                "ORDER_NOT_FOUND" if response.status_code == 404
                else "ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE",
                {}, {},
            )
        try:
            source = _OrderDetail.model_validate(_json(response))
            fingerprint = _order_cancellation_fingerprint(source)
        except (ValueError, ValidationError):
            return OrderCancellationConfirmationState(
                False, "ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE", {}, {}
            )
        key = f"ORDER:{source.order_id}"
        versions = {key: str(source.version)}
        snapshots = {key: fingerprint}
        reason = "ORDER_CANCELLATION_READY"
        cancellation = source.cancellation
        if source.version != binding.order_version:
            reason = "ORDER_VERSION_CONFLICT"
        elif fingerprint != binding.order_fingerprint:
            reason = "ORDER_VERSION_CONFLICT"
        elif source.total_amount != binding.total or source.currency != binding.currency:
            reason = "ORDER_VERSION_CONFLICT"
        elif cancellation is None:
            reason = "ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE"
        elif cancellation.request_id is not None:
            reason = "ORDER_CANCELLATION_ALREADY_REQUESTED"
        elif not cancellation.eligible:
            reason = _cancellation_ineligibility_reason(
                cancellation.ineligibility_code
            )
        return OrderCancellationConfirmationState(
            reason == "ORDER_CANCELLATION_READY", reason, versions, snapshots
        )

    async def cancel_my_order(
        self,
        *,
        order_id: str,
        expected_version: int,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Submit one confirmed request to the existing Order cancellation workflow."""

        started = time.monotonic()
        if not _valid_bearer(authorization):
            return _timed(ToolObservation(
                tool="cancel_my_order", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool="cancel_my_order", status="REJECTED",
                reason="CONFIRMATION_REQUIRED",
            ), started)
        key = f"agent-action-{action_reference}"
        response = await self._post_empty(
            f"/api/v1/orders/{order_id}/cancellation-requests",
            expected_version=expected_version,
            idempotency_key=key,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if response is None or response.status_code >= 500:
            response = await self._post_empty(
                f"/api/v1/orders/{order_id}/cancellation-requests",
                expected_version=expected_version,
                idempotency_key=key,
                authorization=authorization,
                correlation_id=correlation_id,
            )
        if response is not None and response.status_code in {200, 201}:
            try:
                accepted = _OrderCancellationResponse.model_validate(_json(response))
                if accepted.order_id == order_id:
                    observed = await self.get_my_order(
                        order_id=order_id,
                        authorization=authorization,
                        correlation_id=correlation_id,
                    )
                    if observed.status == "SUCCEEDED" and observed.order is not None:
                        request_status = (
                            None if observed.order.cancellation is None
                            else observed.order.cancellation.request_status
                        )
                        return _timed(observed.model_copy(update={
                            "tool": "cancel_my_order",
                            "reason": (
                                "ORDER_CANCELLATION_COMPLETED"
                                if request_status == "COMPLETED"
                                else "ORDER_CANCELLATION_REQUESTED"
                            ),
                        }), started)
                    return _timed(ToolObservation(
                        tool="cancel_my_order", status="SUCCEEDED",
                        reason="ORDER_CANCELLATION_REQUESTED", resultCount=1,
                        orderReferences=(CustomerOrderReference(orderId=order_id),),
                    ), started)
            except (ValueError, ValidationError):
                pass
        if response is not None and response.status_code < 500:
            return _timed(_order_cancellation_failure(
                "cancel_my_order", response
            ), started)
        observed = await self.get_my_order(
            order_id=order_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if (
            observed.status == "SUCCEEDED"
            and observed.order is not None
            and observed.order.cancellation is not None
            and observed.order.cancellation.request_status is not None
        ):
            return _timed(observed.model_copy(update={
                "tool": "cancel_my_order",
                "reason": (
                    "ORDER_CANCELLATION_COMPLETED"
                    if observed.order.cancellation.request_status == "COMPLETED"
                    else "ORDER_CANCELLATION_REQUESTED"
                ),
            }), started)
        return _timed(ToolObservation(
            tool="cancel_my_order", status="FAILED", reason="OUTCOME_UNKNOWN",
        ), started)

    async def get_my_return(
        self,
        *,
        order_id: str,
        listing_id: str | None,
        authorization: str | None,
        correlation_id: str,
        store_name: str | None = None,
    ) -> ToolObservation:
        """Read one buyer-owned business-group return or its current eligibility."""

        started = time.monotonic()
        source, failure = await self._owned_order_for_return(
            order_id=order_id,
            authorization=authorization,
            correlation_id=correlation_id,
            tool="get_my_return",
        )
        if failure is not None:
            return _timed(failure, started)
        assert source is not None
        group, reason = _resolve_return_group(source, listing_id, store_name)
        if (
            group is None
            and listing_id is None
            and store_name is None
            and len(source.groups) > 1
        ):
            candidates: list[tuple[_OrderGroupDetail, _BusinessOrderReturn]] = []
            scan_failed = False
            for current in source.groups:
                projection = await self._read_return(
                    order_id=source.order_id,
                    business_order_id=current.business_order_id,
                    authorization=authorization,
                    correlation_id=correlation_id,
                )
                if isinstance(projection, ToolObservation):
                    scan_failed = True
                elif projection.return_id:
                    candidates.append((current, projection))
            if scan_failed:
                return _timed(_return_unavailable("get_my_return"), started)
            if len(candidates) == 1:
                group, projection = candidates[0]
                return _timed(_return_observation(
                    tool="get_my_return", source=projection, group=group,
                ), started)
        if group is None:
            return _timed(ToolObservation(
                tool="get_my_return", status="REJECTED", reason=reason,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        projection = await self._read_return(
            order_id=source.order_id,
            business_order_id=group.business_order_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(projection, ToolObservation):
            return _timed(projection.model_copy(update={"tool": "get_my_return"}), started)
        return _timed(_return_observation(
            tool="get_my_return", source=projection, group=group,
        ), started)

    async def prepare_my_return_request(
        self,
        *,
        order_id: str,
        listing_id: str | None,
        reason_code: str,
        comment: str | None,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
        store_name: str | None = None,
    ) -> ToolObservation:
        """Prepare one exact whole-group request using Order-owned eligibility."""

        started = time.monotonic()
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool="prepare_my_return_request", status="REJECTED",
                reason="INVALID_ARGUMENTS",
            ), started)
        source, failure = await self._owned_order_for_return(
            order_id=order_id,
            authorization=authorization,
            correlation_id=correlation_id,
            tool="prepare_my_return_request",
        )
        if failure is not None:
            return _timed(failure, started)
        assert source is not None
        group, reason = _resolve_return_group(source, listing_id, store_name)
        if group is None:
            return _timed(ToolObservation(
                tool="prepare_my_return_request", status="REJECTED", reason=reason,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        projection = await self._read_return(
            order_id=source.order_id,
            business_order_id=group.business_order_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(projection, ToolObservation):
            return _timed(projection.model_copy(
                update={"tool": "prepare_my_return_request"}
            ), started)
        normalized = _normalize_return(projection, group)
        if projection.return_id is not None:
            return _timed(ToolObservation(
                tool="prepare_my_return_request", status="REJECTED",
                reason="RETURN_ALREADY_EXISTS", resultCount=1,
                returnRequest=normalized,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        if not projection.eligible:
            return _timed(ToolObservation(
                tool="prepare_my_return_request", status="REJECTED",
                reason=_return_ineligibility_reason(projection.ineligibility_code),
                returnRequest=normalized,
                orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            ), started)
        preview = normalized.model_copy(update={
            "reason_code": reason_code,
            "buyer_comment": comment,
        })
        binding = SubmitReturnConfirmationArguments(
            orderId=source.order_id,
            businessOrderId=group.business_order_id,
            groupVersion=projection.version,
            groupFingerprint=_return_group_fingerprint(group),
            reasonCode=reason_code,
            comment=comment,
        )
        confirmation_id = new_ulid()
        pending = MarketplaceAgentV2PendingInteraction(
            id=confirmation_id,
            confirmationId=confirmation_id,
            type="CONFIRM_ACTION",
            action="SUBMIT_RETURN_REQUEST",
            arguments=binding.model_dump(mode="json", by_alias=True),
            summary=_return_confirmation_summary(preview),
            status="WAITING",
            createdAt=datetime.now(UTC),
        )
        return _timed(ToolObservation(
            tool="prepare_my_return_request", status="SUCCEEDED",
            reason="RETURN_REQUEST_READY", resultCount=1,
            returnRequest=preview,
            orderReferences=(CustomerOrderReference(orderId=source.order_id),),
            pendingInteraction=pending,
        ), started)

    async def revalidate_return_confirmation(
        self,
        *,
        binding: SubmitReturnConfirmationArguments,
        authorization: str | None,
        correlation_id: str,
    ) -> ReturnConfirmationState:
        source, failure = await self._owned_order_for_return(
            order_id=binding.order_id,
            authorization=authorization,
            correlation_id=correlation_id,
            tool="prepare_my_return_request",
        )
        if failure is not None or source is None:
            reason = failure.reason if failure is not None else "RETURN_UPSTREAM_UNAVAILABLE"
            return ReturnConfirmationState(False, reason, {}, {})
        group = next((
            item for item in source.groups
            if item.business_order_id == binding.business_order_id
        ), None)
        if group is None:
            return ReturnConfirmationState(False, "RETURN_ITEM_NOT_FOUND", {}, {})
        projection = await self._read_return(
            order_id=source.order_id,
            business_order_id=group.business_order_id,
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(projection, ToolObservation):
            return ReturnConfirmationState(False, projection.reason, {}, {})
        key = f"BUSINESS_ORDER_GROUP:{group.business_order_id}"
        fingerprint = _return_group_fingerprint(group)
        versions = {key: str(projection.version)}
        snapshots = {key: fingerprint}
        reason = "RETURN_REQUEST_READY"
        if projection.version != binding.group_version:
            reason = "RETURN_VERSION_CONFLICT"
        elif fingerprint != binding.group_fingerprint:
            reason = "RETURN_VERSION_CONFLICT"
        elif projection.return_id is not None:
            reason = "RETURN_ALREADY_EXISTS"
        elif not projection.eligible:
            reason = _return_ineligibility_reason(projection.ineligibility_code)
        return ReturnConfirmationState(
            reason == "RETURN_REQUEST_READY", reason, versions, snapshots
        )

    async def submit_my_return_request(
        self,
        *,
        binding: SubmitReturnConfirmationArguments,
        action_reference: str | None,
        authorization: str | None,
        correlation_id: str,
    ) -> ToolObservation:
        """Submit a confirmed customer request; never approve or execute a refund."""

        started = time.monotonic()
        if not _valid_bearer(authorization):
            return _timed(ToolObservation(
                tool="submit_my_return_request", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            ), started)
        if not _valid_action_reference(action_reference):
            return _timed(ToolObservation(
                tool="submit_my_return_request", status="REJECTED",
                reason="CONFIRMATION_REQUIRED",
            ), started)
        path = (
            f"/api/v1/orders/{binding.order_id}/groups/"
            f"{binding.business_order_id}/returns"
        )
        body = {"reasonCode": binding.reason_code, "comment": binding.comment}
        response = await self._request(
            "POST", self._base_url, path,
            headers={
                "If-Match": f'"{binding.group_version}"',
                "Idempotency-Key": f"agent-action-{action_reference}",
            },
            body=body,
            authorization=authorization or "",
            correlation_id=correlation_id,
        )
        if response is None or response.status_code >= 500:
            response = await self._request(
                "POST", self._base_url, path,
                headers={
                    "If-Match": f'"{binding.group_version}"',
                    "Idempotency-Key": f"agent-action-{action_reference}",
                },
                body=body,
                authorization=authorization or "",
                correlation_id=correlation_id,
            )
        order_source, _ = await self._owned_order_for_return(
            order_id=binding.order_id,
            authorization=authorization,
            correlation_id=correlation_id,
            tool="submit_my_return_request",
        )
        group = None if order_source is None else next((
            item for item in order_source.groups
            if item.business_order_id == binding.business_order_id
        ), None)
        if response is not None and response.status_code in {200, 201}:
            try:
                accepted = _BusinessOrderReturn.model_validate(_json(response))
                if accepted.return_id is not None and group is not None:
                    return _timed(_return_observation(
                        tool="submit_my_return_request", source=accepted,
                        group=group, submitted=True,
                    ), started)
            except (ValueError, ValidationError):
                pass
        if response is not None and response.status_code < 500:
            return _timed(_return_failure(
                "submit_my_return_request", response
            ), started)
        if group is not None:
            observed = await self._read_return(
                order_id=binding.order_id,
                business_order_id=binding.business_order_id,
                authorization=authorization,
                correlation_id=correlation_id,
            )
            if isinstance(observed, _BusinessOrderReturn) and observed.return_id:
                return _timed(_return_observation(
                    tool="submit_my_return_request", source=observed,
                    group=group, submitted=True,
                ), started)
        return _timed(ToolObservation(
            tool="submit_my_return_request", status="FAILED", reason="OUTCOME_UNKNOWN",
        ), started)

    async def _owned_order_for_return(
        self,
        *,
        order_id: str,
        authorization: str | None,
        correlation_id: str,
        tool: str,
    ) -> tuple[_OrderDetail | None, ToolObservation | None]:
        if not _valid_bearer(authorization):
            return None, ToolObservation(
                tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
            )
        response = await self._get(
            f"/api/v1/orders/{order_id}", authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation):
            return None, _return_unavailable(tool)
        if response.status_code in {401, 403}:
            return None, ToolObservation(
                tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
            )
        if response.status_code == 404:
            return None, ToolObservation(
                tool=tool, status="REJECTED", reason="ORDER_NOT_FOUND"
            )
        if response.status_code != 200:
            return None, _return_unavailable(tool)
        try:
            return _OrderDetail.model_validate(_json(response)), None
        except (ValueError, ValidationError):
            return None, _return_unavailable(tool)

    async def _read_return(
        self,
        *,
        order_id: str,
        business_order_id: str,
        authorization: str | None,
        correlation_id: str,
    ) -> _BusinessOrderReturn | ToolObservation:
        response = await self._get(
            f"/api/v1/orders/{order_id}/groups/{business_order_id}/return",
            authorization=authorization,
            correlation_id=correlation_id,
        )
        if isinstance(response, ToolObservation) or response.status_code >= 500:
            return _return_unavailable("get_my_return")
        if response.status_code in {401, 403}:
            return ToolObservation(
                tool="get_my_return", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            )
        if response.status_code == 404:
            return ToolObservation(
                tool="get_my_return", status="REJECTED", reason="RETURN_NOT_FOUND"
            )
        if response.status_code != 200:
            return _return_failure("get_my_return", response)
        try:
            return _BusinessOrderReturn.model_validate(_json(response))
        except (ValueError, ValidationError):
            return _return_unavailable("get_my_return")

    async def _request(
        self,
        method: Literal["GET", "POST"],
        base_url: str,
        path: str,
        *,
        authorization: str,
        correlation_id: str,
        headers: dict[str, str] | None = None,
        body: dict[str, object] | None = None,
    ) -> httpx.Response | None:
        request_headers = {
            "Authorization": authorization,
            "X-Correlation-Id": correlation_id,
            "Accept": "application/json",
        }
        request_headers.update(headers or {})
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    method,
                    f"{base_url}{path}",
                    json=body if method == "POST" else None,
                    headers=request_headers,
                ) as streamed:
                    response_body = bytearray()
                    async for chunk in streamed.aiter_bytes():
                        if len(response_body) + len(chunk) > _MAX_RESPONSE_BYTES:
                            return None
                        response_body.extend(chunk)
                    return httpx.Response(
                        streamed.status_code,
                        headers=streamed.headers,
                        content=bytes(response_body),
                        request=streamed.request,
                    )
        except httpx.HTTPError:
            return None

    async def _write(
        self,
        method: Literal["POST", "PATCH", "DELETE"],
        path: str,
        *,
        body: dict[str, object] | None,
        expected_version: int,
        idempotency_key: str,
        authorization: str,
        correlation_id: str,
    ) -> httpx.Response | None:
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    method,
                    f"{self._base_url}{path}",
                    json=body,
                    headers={
                        "Authorization": authorization,
                        "X-Correlation-Id": correlation_id,
                        "Accept": "application/json",
                        "Content-Type": "application/json",
                        "If-Match": f'"{expected_version}"',
                        "Idempotency-Key": idempotency_key,
                    },
                ) as streamed:
                    response_body = bytearray()
                    async for chunk in streamed.aiter_bytes():
                        if len(response_body) + len(chunk) > _MAX_RESPONSE_BYTES:
                            return None
                        response_body.extend(chunk)
                    return httpx.Response(
                        streamed.status_code,
                        headers=streamed.headers,
                        content=bytes(response_body),
                        request=streamed.request,
                    )
        except httpx.HTTPError:
            return None

    async def _post_empty(
        self,
        path: str,
        *,
        expected_version: int,
        idempotency_key: str,
        authorization: str,
        correlation_id: str,
    ) -> httpx.Response | None:
        """Send a versioned bodyless command; Order cancellation rejects JSON null."""

        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    "POST", f"{self._base_url}{path}", headers={
                        "Authorization": authorization,
                        "X-Correlation-Id": correlation_id,
                        "Accept": "application/json",
                        "If-Match": f'"{expected_version}"',
                        "Idempotency-Key": idempotency_key,
                    },
                ) as streamed:
                    response_body = bytearray()
                    async for chunk in streamed.aiter_bytes():
                        if len(response_body) + len(chunk) > _MAX_RESPONSE_BYTES:
                            return None
                        response_body.extend(chunk)
                    return httpx.Response(
                        streamed.status_code,
                        headers=streamed.headers,
                        content=bytes(response_body),
                        request=streamed.request,
                    )
        except httpx.HTTPError:
            return None

    async def _get(
        self,
        path: str,
        *,
        authorization: str | None,
        correlation_id: str,
        params: dict[str, str] | None = None,
    ) -> httpx.Response | ToolObservation:
        if not _valid_bearer(authorization):
            return ToolObservation(
                tool="UNREGISTERED", status="REJECTED",
                reason="AUTHENTICATION_REQUIRED",
            )
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    "GET",
                    f"{self._base_url}{path}",
                    params=params,
                    headers={
                        "Authorization": authorization,
                        "X-Correlation-Id": correlation_id,
                        "Accept": "application/json",
                    },
                ) as streamed:
                    body = bytearray()
                    async for chunk in streamed.aiter_bytes():
                        if len(body) + len(chunk) > _MAX_RESPONSE_BYTES:
                            return _unavailable("UNREGISTERED")
                        body.extend(chunk)
                    response = httpx.Response(
                        streamed.status_code,
                        headers=streamed.headers,
                        content=bytes(body),
                        request=streamed.request,
                    )
        except httpx.HTTPError:
            return _unavailable("UNREGISTERED")
        return response


def _normalize_cart(source: _Cart) -> CustomerCartSnapshot:
    return CustomerCartSnapshot(
        version=source.version,
        expiresAt=source.expires_at,
        itemCount=source.item_count,
        totalQuantity=source.total_quantity,
        totals=tuple(
            CommerceMoney(amount=item.amount, currency=item.currency)
            for item in source.totals
        ),
        items=tuple(
            CartItemSnapshot(
                listingId=item.listing_id,
                title=item.title,
                storeName=item.store_name,
                quantity=item.quantity,
                observedPrice=item.observed_price,
                currency=item.currency,
            )
            for item in source.items
        ),
    )


def _normalize_checkout(source: _Checkout) -> CustomerCheckoutSnapshot:
    label = source.address.label or "Saved address"
    return CustomerCheckoutSnapshot(
        checkoutId=source.id,
        status=source.status,
        currency=source.currency,
        subtotal=source.subtotal,
        shipping=source.shipping,
        tax=source.tax,
        discount=source.discount,
        total=source.total,
        expiresAt=source.expires_at,
        addressSummary=(
            f"{label} · {source.address.city}, {source.address.region} · "
            f"{source.address.country_code}"
        ),
        shippingSummary="Delivery uses the saved address shown above.",
        items=tuple(
            CustomerCheckoutItemSnapshot(
                listingId=item.listing_id,
                title=item.title,
                quantity=item.quantity,
                unitPrice=item.unit_price,
                lineTotal=item.line_total,
            )
            for item in source.items
        ),
    )


def _checkout_binding(
    checkout: _Checkout,
    validation: _CartValidation,
) -> SubmitCheckoutConfirmationArguments:
    return SubmitCheckoutConfirmationArguments(
        checkoutId=checkout.id,
        checkoutFingerprint=_checkout_fingerprint(checkout),
        cartVersion=validation.cart_version,
        cartFingerprint=_cart_validation_fingerprint(validation),
        total=checkout.total,
        currency=checkout.currency,
    )


def _checkout_fingerprint(checkout: _Checkout) -> str:
    return _fingerprint({
        "checkoutId": checkout.id,
        "cartVersion": checkout.cart_version,
        "currency": checkout.currency,
        "subtotal": _decimal(checkout.subtotal),
        "shipping": _decimal(checkout.shipping),
        "tax": _decimal(checkout.tax),
        "discount": _decimal(checkout.discount),
        "total": _decimal(checkout.total),
        "expiresAt": checkout.expires_at.isoformat(),
        "reservation": {
            "id": checkout.reservation.id,
            "status": checkout.reservation.status,
            "releaseStatus": checkout.reservation.release_status,
        },
        "address": {
            "id": checkout.address.source_address_id,
            "version": checkout.address.source_version,
        },
        "items": [
            {
                "listingId": item.listing_id,
                "catalogVersion": item.catalog_version,
                "quantity": item.quantity,
                "unitPrice": _decimal(item.unit_price),
                "lineTotal": _decimal(item.line_total),
            }
            for item in sorted(checkout.items, key=lambda value: value.listing_id)
        ],
    })


def _checkout_release_complete(checkout: _Checkout) -> bool:
    return (
        checkout.status in {"CANCELLED", "EXPIRED"}
        and checkout.reservation.release_status == "COMPLETE"
    )


def _cart_validation_fingerprint(validation: _CartValidation) -> str:
    return _fingerprint({
        "cartVersion": validation.cart_version,
        "items": [
            {
                "listingId": item.listing_id,
                "quantity": item.requested_quantity,
                "currentPrice": _decimal(
                    item.current_price
                    if item.current_price is not None else item.observed_price
                ),
                "currency": item.current_currency or item.observed_currency,
            }
            for item in sorted(validation.items, key=lambda value: value.listing_id)
        ],
    })


def _checkout_prices_changed(
    checkout: _Checkout,
    validation: _CartValidation,
) -> bool:
    current = {
        item.listing_id: (
            item.current_price if item.current_price is not None else item.observed_price,
            item.current_currency or item.observed_currency,
            item.requested_quantity,
        )
        for item in validation.items
    }
    return any(
        current.get(item.listing_id)
        != (item.unit_price, checkout.currency, item.quantity)
        for item in checkout.items
    )


def _checkout_confirmation_summary(checkout: CustomerCheckoutSnapshot) -> str:
    lines = [
        f"{item.title} ×{item.quantity} — {_decimal(item.line_total)} "
        f"{checkout.currency}"
        for item in checkout.items
    ]
    summary = (
        "Your prepared checkout contains:\n- "
        + "\n- ".join(lines)
        + f"\nSubtotal: {_decimal(checkout.subtotal)} {checkout.currency}; "
        + f"shipping: {_decimal(checkout.shipping)}; tax: {_decimal(checkout.tax)}; "
        + f"discount: {_decimal(checkout.discount)}; "
        + f"total: {_decimal(checkout.total)}. Place this order using demo payment."
    )
    if len(summary) <= 500:
        return summary
    return (
        f"Your prepared checkout has {len(checkout.items)} item types. "
        f"Subtotal: {_decimal(checkout.subtotal)} {checkout.currency}; "
        f"shipping: {_decimal(checkout.shipping)}; tax: {_decimal(checkout.tax)}; "
        f"discount: {_decimal(checkout.discount)}; "
        f"total: {_decimal(checkout.total)}. Place this order using demo payment."
    )


def _fingerprint(value: object) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _decimal(value: Decimal) -> str:
    return format(value, "f")


def _cart_references(
    cart: CustomerCartSnapshot,
) -> tuple[CustomerCartItemReference, ...]:
    return tuple(
        CustomerCartItemReference(
            listingId=item.listing_id, title=item.title, position=position
        )
        for position, item in enumerate(cart.items, 1)
    )


def _cart_mutation_success(
    *,
    tool: str,
    operation: Literal["ADD", "UPDATE_QUANTITY", "REMOVE"],
    listing_id: str,
    quantity: int | None,
    cart: CustomerCartSnapshot,
    reason: str,
) -> ToolObservation:
    return ToolObservation(
        tool=tool,
        status="SUCCEEDED",
        reason=reason,
        resultCount=cart.item_count,
        cart=cart,
        cartItemReferences=_cart_references(cart),
        cartMutationReference=CustomerCartMutationReference(
            listingId=listing_id,
            operation=operation,
            requestedQuantity=quantity,
        ),
    )


def _mutation_visible(
    cart: CustomerCartSnapshot,
    *,
    operation: Literal["ADD", "UPDATE_QUANTITY", "REMOVE"],
    listing_id: str,
    quantity: int | None,
) -> bool:
    item = next(
        (candidate for candidate in cart.items if candidate.listing_id == listing_id),
        None,
    )
    if operation == "REMOVE":
        return item is None
    return item is not None and item.quantity == quantity


def _cart_failure(
    tool: str,
    operation: Literal["ADD", "UPDATE_QUANTITY", "REMOVE"],
    response: httpx.Response,
) -> ToolObservation:
    code = _error_code(response)
    if response.status_code in {401, 403}:
        reason = "AUTHENTICATION_REQUIRED"
        status = "REJECTED"
    elif code == "CART_ITEM_NOT_ELIGIBLE":
        reason = "NOT_PURCHASABLE"
        status = "REJECTED"
    elif code == "CART_INSUFFICIENT_STOCK":
        reason = "OUT_OF_STOCK"
        status = "REJECTED"
    elif code == "CART_ITEM_NOT_FOUND" or response.status_code == 404:
        reason = "NOT_FOUND"
        status = "REJECTED"
    elif code == "CART_VERSION_CONFLICT":
        reason = "CART_CONFLICT"
        status = "REJECTED"
    elif code == "CART_ITEM_LIMIT_EXCEEDED":
        reason = "CART_LIMIT_EXCEEDED"
        status = "REJECTED"
    elif code == "CART_IDEMPOTENCY_CONFLICT":
        reason = "IDEMPOTENCY_CONFLICT"
        status = "REJECTED"
    elif response.status_code == 400:
        reason = "INVALID_QUANTITY" if operation != "REMOVE" else "INVALID_ARGUMENTS"
        status = "REJECTED"
    else:
        reason = "CART_UPSTREAM_UNAVAILABLE"
        status = "FAILED"
    return ToolObservation(tool=tool, status=status, reason=reason)


def _valid_action_reference(value: str | None) -> bool:
    return bool(
        value
        and len(value) == 26
        and all(character in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" for character in value)
    )


def _timed(observation: ToolObservation, started: float) -> ToolObservation:
    return observation.model_copy(update={
        "latency_ms": min(86_400_000, max(0, round((time.monotonic() - started) * 1_000)))
    })


def _normalize_order_summary(source: _OrderSummary) -> CustomerOrderSummary:
    return CustomerOrderSummary(
        orderId=source.order_id,
        status=source.status,
        paymentStatus=source.payment_status,
        total=CommerceMoney(amount=source.total_amount, currency=source.currency),
        createdAt=source.created_at,
        updatedAt=source.updated_at,
        stores=tuple(
            CustomerOrderStoreSummary(
                storeName=item.store_name,
                status=item.status,
                total=CommerceMoney(amount=item.total_amount, currency=item.currency),
            )
            for item in source.groups
        ),
    )


def _normalize_order_detail(source: _OrderDetail) -> CustomerOrderDetail:
    cancellation = None
    if source.cancellation is not None:
        cancellation = CustomerOrderCancellationSnapshot(
            eligible=source.cancellation.eligible,
            ineligibilityCode=source.cancellation.ineligibility_code,
            requestStatus=source.cancellation.request_status,
            inventoryStatus=source.cancellation.inventory_status,
            refundStatus=(
                None if source.cancellation.refund is None
                else source.cancellation.refund.status
            ),
        )
    return CustomerOrderDetail(
        orderId=source.order_id,
        status=source.status,
        paymentStatus=source.payment_status,
        total=CommerceMoney(amount=source.total_amount, currency=source.currency),
        version=source.version,
        createdAt=source.created_at,
        updatedAt=source.updated_at,
        groups=tuple(_normalize_order_group(item) for item in source.groups),
        cancellation=cancellation,
    )


def _order_cancellation_binding(
    source: _OrderDetail,
) -> CancelOrderConfirmationArguments:
    return CancelOrderConfirmationArguments(
        orderId=source.order_id,
        orderVersion=source.version,
        orderFingerprint=_order_cancellation_fingerprint(source),
        total=source.total_amount,
        currency=source.currency,
    )


def _order_cancellation_fingerprint(source: _OrderDetail) -> str:
    cancellation = source.cancellation
    return _fingerprint({
        "orderId": source.order_id,
        "version": source.version,
        "status": source.status,
        "paymentStatus": source.payment_status,
        "total": _decimal(source.total_amount),
        "currency": source.currency,
        "groups": [
            {
                "status": group.status,
                "items": [
                    {
                        "listingId": item.listing_id,
                        "quantity": item.quantity,
                        "lineTotal": _decimal(item.line_total),
                    }
                    for item in sorted(group.items, key=lambda value: value.listing_id)
                ],
            }
            for group in source.groups
        ],
        "cancellation": None if cancellation is None else {
            "eligible": cancellation.eligible,
            "ineligibilityCode": cancellation.ineligibility_code,
            "requestId": cancellation.request_id,
            "requestStatus": cancellation.request_status,
            "inventoryStatus": cancellation.inventory_status,
            "refundStatus": (
                None if cancellation.refund is None else cancellation.refund.status
            ),
        },
    })


def _order_cancellation_confirmation_summary(
    order: CustomerOrderDetail,
) -> str:
    item_count = sum(
        item.quantity for group in order.groups for item in group.items
    )
    return (
        f"Request cancellation of your whole order with {item_count} item"
        f"{'s' if item_count != 1 else ''}, totaling {_decimal(order.total.amount)} "
        f"{order.total.currency}. The marketplace will recheck eligibility and handle "
        "the cancellation, inventory release, and any local demo refund."
    )


def _cancellation_ineligibility_reason(code: str | None) -> str:
    return {
        "FULFILLMENT_STARTED": "ORDER_CANCELLATION_FULFILLMENT_STARTED",
        "WINDOW_CLOSED": "ORDER_CANCELLATION_WINDOW_CLOSED",
        "POLICY_NOT_ALLOWED": "ORDER_CANCELLATION_NOT_ALLOWED",
    }.get(code, "ORDER_CANCELLATION_NOT_ALLOWED")


def _normalize_order_group(source: _OrderGroupDetail) -> CustomerOrderGroupDetail:
    shipment = None
    if source.shipment is not None:
        shipment = CustomerShipmentSummary(
            carrier=source.shipment.carrier_display_name,
            service=source.shipment.service_display_name,
            status=source.shipment.status,
            shippedAt=source.shipment.shipped_at,
            deliveredAt=source.shipment.delivered_at,
        )
    return CustomerOrderGroupDetail(
        storeName=source.store_name,
        status=source.status,
        total=CommerceMoney(amount=source.total_amount, currency=source.currency),
        items=tuple(
            CustomerOrderItemSnapshot(
                listingId=item.listing_id,
                title=item.title,
                quantity=item.quantity,
                purchaseUnitPrice=item.unit_price,
                lineTotal=item.line_total,
                currency=item.currency,
            )
            for item in source.items
        ),
        timeline=tuple(
            CustomerOrderTimelineEntry(status=item.status, occurredAt=item.occurred_at)
            for item in source.timeline
        ),
        shipment=shipment,
    )


def _resolve_return_group(
    source: _OrderDetail,
    listing_id: str | None,
    store_name: str | None = None,
) -> tuple[_OrderGroupDetail | None, str]:
    if listing_id is None and store_name is None:
        if len(source.groups) == 1:
            return source.groups[0], "RETURN_ELIGIBLE"
        return None, "RETURN_GROUP_AMBIGUOUS"
    normalized_store = None if store_name is None else " ".join(
        store_name.casefold().split()
    )
    matching = tuple(
        group for group in source.groups
        if (
            listing_id is None
            or any(item.listing_id == listing_id for item in group.items)
        )
        and (
            normalized_store is None
            or " ".join((group.store_name or "").casefold().split())
            == normalized_store
        )
    )
    if len(matching) == 1:
        return matching[0], "RETURN_ELIGIBLE"
    return None, (
        "RETURN_ITEM_NOT_FOUND"
        if not matching and listing_id is not None
        else "RETURN_GROUP_AMBIGUOUS"
    )


def _normalize_return(
    source: _BusinessOrderReturn, group: _OrderGroupDetail
) -> CustomerReturnSnapshot:
    return CustomerReturnSnapshot(
        eligible=source.eligible,
        ineligibilityCode=source.ineligibility_code,
        storeName=source.store_name or group.store_name,
        reasonCode=source.reason_code,
        buyerComment=source.buyer_comment,
        requestedAt=source.requested_at,
        windowExpiresAt=source.window_expires_at,
        status=source.status,
        refundStatus=source.refund_status,
        refundAmount=source.refund_amount,
        currency=source.currency,
        receivedAt=source.received_at,
        completedAt=source.completed_at,
        items=tuple(
            CustomerOrderItemSnapshot(
                listingId=item.listing_id,
                title=item.title,
                quantity=item.quantity,
                purchaseUnitPrice=item.unit_price,
                lineTotal=item.line_total,
                currency=item.currency,
            )
            for item in group.items
        ),
        timeline=tuple(
            CustomerOrderTimelineEntry(
                status=item.status, occurredAt=item.occurred_at
            )
            for item in source.timeline
        ),
    )


def _return_observation(
    *,
    tool: str,
    source: _BusinessOrderReturn,
    group: _OrderGroupDetail,
    submitted: bool = False,
) -> ToolObservation:
    normalized = _normalize_return(source, group)
    reason = (
        "RETURN_REQUEST_SUBMITTED"
        if submitted
        else "RETURN_AVAILABLE"
        if source.return_id is not None
        else "RETURN_ELIGIBLE"
        if source.eligible
        else _return_ineligibility_reason(source.ineligibility_code)
    )
    return ToolObservation(
        tool=tool,
        status="SUCCEEDED" if submitted or source.return_id is not None or source.eligible else "REJECTED",
        reason=reason,
        resultCount=1,
        returnRequest=normalized,
        orderReferences=(CustomerOrderReference(orderId=source.order_id),),
        orderItemReferences=tuple(
            CustomerOrderItemReference(
                orderId=source.order_id,
                listingId=item.listing_id,
                title=item.title,
                storeName=group.store_name,
            )
            for item in group.items
        ),
    )


def _return_group_fingerprint(group: _OrderGroupDetail) -> str:
    return _fingerprint({
        "businessOrderId": group.business_order_id,
        "version": group.version,
        "status": group.status,
        "total": _decimal(group.total_amount),
        "currency": group.currency,
        "items": [
            {
                "listingId": item.listing_id,
                "quantity": item.quantity,
                "unitPrice": _decimal(item.unit_price),
                "lineTotal": _decimal(item.line_total),
            }
            for item in sorted(group.items, key=lambda current: current.listing_id)
        ],
        "shipment": None if group.shipment is None else {
            "status": group.shipment.status,
            "deliveredAt": (
                None if group.shipment.delivered_at is None
                else group.shipment.delivered_at.isoformat()
            ),
        },
    })


def _return_confirmation_summary(value: CustomerReturnSnapshot) -> str:
    quantity = sum(item.quantity for item in value.items)
    titles = ", ".join(item.title for item in value.items[:3])[:100].rstrip()
    if len(value.items) > 3:
        titles += f", and {len(value.items) - 3} more"
    store = (value.store_name or "this store")[:50].rstrip()
    reason = (value.reason_code or "OTHER").replace("_", " ").lower()
    comment = ""
    if value.buyer_comment is not None:
        safe_comment = " ".join(value.buyer_comment.split())[:60].rstrip()
        comment = f' Customer comment: "{safe_comment}".'
    return (
        f"Submit a return request for the entire {store} order group: {quantity} "
        f"item{'s' if quantity != 1 else ''} ({titles}). Reason: {reason}."
        f"{comment} This submits a customer "
        "request only; the marketplace and seller workflow decide the return, and "
        "any local demo refund is processed later if authorized."
    )


def _json(response: httpx.Response) -> Any:
    if "application/json" not in response.headers.get("content-type", ""):
        raise ValueError("Unexpected commerce response type")
    return response.json()


def _error_code(response: httpx.Response) -> str | None:
    try:
        return _ErrorEnvelope.model_validate(_json(response)).error.code
    except (ValueError, ValidationError):
        return None


def _error_detail(response: httpx.Response, field: str) -> str | None:
    try:
        envelope = _ErrorEnvelope.model_validate(_json(response))
        return next(
            (
                item.get("code")
                for item in envelope.error.details
                if item.get("field") == field and item.get("code")
            ),
            None,
        )
    except (ValueError, ValidationError):
        return None


def _valid_bearer(value: str | None) -> bool:
    return bool(value and value.startswith("Bearer ") and 7 < len(value) <= 8_192)


def _failure(tool: str, status_code: int) -> ToolObservation:
    if status_code in {401, 403}:
        return ToolObservation(
            tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
        )
    if status_code == 400:
        return ToolObservation(tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS")
    return _unavailable(tool)


def _checkout_failure(tool: str, response: httpx.Response) -> ToolObservation:
    code = _error_code(response)
    if response.status_code in {401, 403}:
        return ToolObservation(
            tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
        )
    reason = {
        "CHECKOUT_CART_EMPTY": "CHECKOUT_EMPTY",
        "CHECKOUT_CART_VERSION_CONFLICT": "CHECKOUT_STALE",
        "CHECKOUT_CART_INVALID": "ITEM_UNAVAILABLE",
        "CHECKOUT_INSUFFICIENT_STOCK": "ITEM_UNAVAILABLE",
        "CHECKOUT_NOT_PAYABLE": "CHECKOUT_STALE",
        "CHECKOUT_NOT_FOUND": "CHECKOUT_NOT_FOUND",
        "PAYMENT_INTENT_EXPIRED": "CHECKOUT_STALE",
        "PAYMENT_INTENT_TERMINAL": "PAYMENT_FAILED",
    }.get(code)
    if reason is not None:
        return ToolObservation(tool=tool, status="REJECTED", reason=reason)
    if response.status_code == 400:
        return ToolObservation(tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS")
    return _checkout_unavailable(tool)


def _checkout_unavailable(tool: str) -> ToolObservation:
    return ToolObservation(
        tool=tool, status="FAILED", reason="CHECKOUT_UPSTREAM_UNAVAILABLE"
    )


def _order_cancellation_failure(
    tool: str, response: httpx.Response
) -> ToolObservation:
    code = _error_code(response)
    if response.status_code in {401, 403}:
        return ToolObservation(
            tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
        )
    reason = {
        "ORDER_NOT_FOUND": "ORDER_NOT_FOUND",
        "ORDER_VERSION_CONFLICT": "ORDER_VERSION_CONFLICT",
        "ORDER_CANCELLATION_FULFILLMENT_STARTED": (
            "ORDER_CANCELLATION_FULFILLMENT_STARTED"
        ),
        "ORDER_CANCELLATION_WINDOW_CLOSED": "ORDER_CANCELLATION_WINDOW_CLOSED",
        "ORDER_CANCELLATION_NOT_ALLOWED": "ORDER_CANCELLATION_NOT_ALLOWED",
        "ORDER_CANCELLATION_STATE_CONFLICT": "ORDER_CANCELLATION_NOT_ALLOWED",
        "ORDER_CANCELLATION_IN_PROGRESS": "ORDER_CANCELLATION_ALREADY_REQUESTED",
        "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT": "IDEMPOTENCY_CONFLICT",
    }.get(code)
    if reason is not None:
        return ToolObservation(tool=tool, status="REJECTED", reason=reason)
    if response.status_code == 400:
        return ToolObservation(
            tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS"
        )
    return _order_cancellation_unavailable(tool)


def _order_cancellation_unavailable(tool: str) -> ToolObservation:
    return ToolObservation(
        tool=tool, status="FAILED",
        reason="ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE",
    )


def _return_ineligibility_reason(code: str | None) -> str:
    return {
        "RETURN_REQUIRES_DELIVERY": "RETURN_REQUIRES_DELIVERY",
        "RETURN_CANCELLED_GROUP": "RETURN_CANCELLED_GROUP",
        "RETURN_WINDOW_EXPIRED": "RETURN_WINDOW_EXPIRED",
        "RETURN_ALREADY_EXISTS": "RETURN_ALREADY_EXISTS",
    }.get(code, "RETURN_UPSTREAM_UNAVAILABLE")


def _return_failure(tool: str, response: httpx.Response) -> ToolObservation:
    code = _error_code(response)
    if response.status_code in {401, 403}:
        return ToolObservation(
            tool=tool, status="REJECTED", reason="AUTHENTICATION_REQUIRED"
        )
    reason = {
        "ORDER_NOT_FOUND": "ORDER_NOT_FOUND",
        "RETURN_NOT_FOUND": "RETURN_NOT_FOUND",
        "RETURN_REQUIRES_DELIVERY": "RETURN_REQUIRES_DELIVERY",
        "RETURN_CANCELLED_GROUP": "RETURN_CANCELLED_GROUP",
        "RETURN_WINDOW_EXPIRED": "RETURN_WINDOW_EXPIRED",
        "RETURN_ALREADY_EXISTS": "RETURN_ALREADY_EXISTS",
        "RETURN_VERSION_CONFLICT": "RETURN_VERSION_CONFLICT",
        "RETURN_IDEMPOTENCY_CONFLICT": "RETURN_IDEMPOTENCY_CONFLICT",
        "RETURN_REASON_INVALID": "INVALID_ARGUMENTS",
        "RETURN_COMMENT_INVALID": "INVALID_ARGUMENTS",
        "RETURN_ID_INVALID": "INVALID_ARGUMENTS",
    }.get(code)
    if reason is not None:
        return ToolObservation(tool=tool, status="REJECTED", reason=reason)
    if response.status_code == 400:
        return ToolObservation(tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS")
    return _return_unavailable(tool)


def _return_unavailable(tool: str) -> ToolObservation:
    return ToolObservation(
        tool=tool, status="FAILED", reason="RETURN_UPSTREAM_UNAVAILABLE"
    )


def _unavailable(tool: str) -> ToolObservation:
    return ToolObservation(
        tool=tool, status="FAILED", reason="COMMERCE_UPSTREAM_UNAVAILABLE"
    )
