"""Narrow validation of customer-explicit edits to one executed search."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Literal

from .schemas import (
    ExecutedSearchSnapshot, RefinementRepairEdit, RefinementRepairSearch,
    RefinementRepairSignal, SearchListingsArguments,
)


RefinementKind = Literal[
    "MAX_PRICE", "MIN_PRICE", "CONDITION", "RAM", "SIZE", "WIRELESS", "NO_RGB",
    "QUERY_REPLACEMENT",
]
RefinementRepair = Literal[
    "UNCHANGED_FILTER_CHANGED", "PRICE_FILTER_CHANGED", "QUERY_TERMS_CHANGED",
    "EDIT_NOT_APPLIED",
]


@dataclass(frozen=True)
class SearchRefinementEdit:
    kind: RefinementKind
    value: Decimal | str
    currency: str | None = None


_PRICE_EDIT = re.compile(
    r"(?:(?:actually|instead|please|now)\s+)?"
    r"(?:(?:make|set|change)\s+(?:(?:it|the\s+price|the\s+budget)\s+)?(?:to\s+)?)?"
    r"(?:(?:only|just)\s+)?"
    r"(?P<bound>under|below|at\s+most|up\s+to|over|above|at\s+least)\s+"
    r"(?P<dollar>\$)?(?P<amount>\d{1,6}(?:,\d{3})*(?:\.\d{1,2})?)"
    r"(?:\s*(?P<usd>usd|dollars?))?(?:\s+instead)?"
)
_CONDITIONS = {
    "new": "NEW", "open box": "OPEN_BOX", "like new": "LIKE_NEW",
    "good": "GOOD", "fair": "FAIR", "for parts": "FOR_PARTS",
}
_CONDITION_EDIT = re.compile(
    r"(?:(?:actually|instead|please|now)\s+)?"
    r"(?:(?:make\s+it|show\s+me|in)\s+)?(?:(?:only|just)\s+)?"
    r"(?P<condition>new|open\s+box|like\s+new|good|fair|for\s+parts)"
    r"(?:\s+(?:ones|condition|only|instead))?"
)
_RAM_EDIT = re.compile(
    r"(?:(?:actually|instead|please|now)\s+)?"
    r"(?:(?:make\s+it|with|at\s+least)\s+)?(?P<size>\d{1,3})\s*gb"
    r"(?:\s+ram)?(?:\s+instead)?"
)
_SIZE_EDIT = re.compile(
    r"(?:(?:actually|instead|please|now)\s+)?"
    r"(?:(?:make\s+it|with)\s+)?(?P<size>\d{1,3})\s*"
    r"(?:inch|inches)(?:\s+instead)?"
)
_RAM_FRAGMENT = re.compile(r"\b\d{1,3}\s*gb(?:\s+ram)?\b", re.I)
_SIZE_FRAGMENT = re.compile(r"\b\d{1,3}[\s-]*(?:inch|inches)\b", re.I)
_WIRELESS_FRAGMENT = re.compile(r"\bwireless\b", re.I)
_NO_RGB_FRAGMENT = re.compile(r"\b(?:no|without)\s+rgb\b", re.I)
_QUERY_FILLERS = frozenset({"with", "at", "least", "only", "and", "a", "an"})
_QUERY_REPLACEMENT_COMMANDS = (
    re.compile(r"(?:search\s+(?:just|only|for)|just\s+search)\s+(?P<query>.+)", re.I),
    re.compile(r"replace\s+that\s+with\s+(?P<query>.+)", re.I),
    re.compile(r"(?:use|search)\s+(?P<query>.+?)\s+instead", re.I),
)


def explicit_search_confirmation_requested(message: str) -> bool:
    """Recognize only a customer's deliberate pre-search permission request."""

    text = " ".join(message.casefold().split())
    return (
        re.search(r"\bbefore\b", text) is not None
        and re.search(r"\bsearch(?:ing)?\b", text) is not None
        and re.search(
            r"\b(?:ask me|confirm with me|my permission|my approval)\b", text
        ) is not None
    )


def customer_search_refinement(
    message: str, snapshot: ExecutedSearchSnapshot | None,
) -> SearchRefinementEdit | None:
    """Identify only short, explicit edits after a fresh executed Product search.

    This does not choose a tool or create arguments. New product requests and
    ambiguous replies stay with the model's ordinary planning path.
    """

    if snapshot is None or snapshot.expires_at <= datetime.now(UTC):
        return None
    text = " ".join(message.casefold().strip().rstrip(".!?").split())
    if not text or explicit_search_confirmation_requested(message):
        return None
    if match := _PRICE_EDIT.fullmatch(text):
        amount = Decimal(match.group("amount").replace(",", ""))
        if amount <= 0:
            return None
        kind: RefinementKind = (
            "MAX_PRICE" if match.group("bound") in {
                "under", "below", "at most", "up to"
            } else "MIN_PRICE"
        )
        if (
            kind == "MAX_PRICE" and (
                amount == snapshot.maximum_price
                or snapshot.minimum_price is not None
                and amount < snapshot.minimum_price
            )
            or kind == "MIN_PRICE" and (
                amount == snapshot.minimum_price
                or snapshot.maximum_price is not None
                and amount > snapshot.maximum_price
            )
        ):
            return None
        currency = "USD" if match.group("dollar") or match.group("usd") else (
            snapshot.currency or "USD"
        )
        return SearchRefinementEdit(kind, amount, currency)
    if match := _CONDITION_EDIT.fullmatch(text):
        condition = _CONDITIONS[match.group("condition")]
        return (
            None if condition == snapshot.condition
            else SearchRefinementEdit("CONDITION", condition)
        )
    if match := _RAM_EDIT.fullmatch(text):
        size = int(match.group("size"))
        if not 1 <= size <= 256:
            return None
        if "ram" not in text and not _RAM_FRAGMENT.search(snapshot.query):
            return None
        value = f"{size}GB RAM"
        prior = _RAM_FRAGMENT.findall(snapshot.query)
        return (
            None if len(prior) == 1 and _words(prior[0]) == _words(value)
            else SearchRefinementEdit("RAM", value)
        )
    if match := _SIZE_EDIT.fullmatch(text):
        size = int(match.group("size"))
        if not 1 <= size <= 100:
            return None
        value = f"{size} inch"
        prior = _SIZE_FRAGMENT.findall(snapshot.query)
        return (
            None if len(prior) == 1 and _words(prior[0]) == _words(value)
            else SearchRefinementEdit("SIZE", value)
        )
    if text in {"wireless", "wireless only", "only wireless"}:
        return (
            None if _WIRELESS_FRAGMENT.search(snapshot.query)
            else SearchRefinementEdit("WIRELESS", "wireless")
        )
    if text in {"no rgb", "without rgb"}:
        return (
            None if _NO_RGB_FRAGMENT.search(snapshot.query)
            else SearchRefinementEdit("NO_RGB", "no RGB")
        )
    replacement = _explicit_query_replacement(message, snapshot.query)
    if replacement is not None:
        return SearchRefinementEdit("QUERY_REPLACEMENT", replacement)
    return None


def _explicit_query_replacement(message: str, prior_query: str) -> str | None:
    """Recognize a self-contained restatement that omits prior free-text terms."""

    replacement = message.strip().rstrip(".!?").strip()
    for command in _QUERY_REPLACEMENT_COMMANDS:
        if match := command.fullmatch(replacement):
            replacement = match.group("query").strip()
            break
    words = _words(replacement)
    prior = _words(prior_query)
    if not words or len(words) >= len(prior) or len(replacement) > 200:
        return None
    if not re.fullmatch(r"[\w\s'-]+", replacement, re.UNICODE):
        return None
    # One-word replies are replacement only when they retain the prior head
    # (e.g. curved monitor -> monitor), not for a lone modifier.
    if len(words) == 1 and words[0] != prior[-1]:
        return None
    remaining = list(prior)
    for word in words:
        if word not in remaining:
            return None
        remaining.remove(word)
    return replacement


def matches_search_refinement(
    arguments: SearchListingsArguments,
    snapshot: ExecutedSearchSnapshot,
    edit: SearchRefinementEdit,
) -> bool:
    """Reject model proposals that drop untouched filters or stack old concepts."""

    return refinement_mismatch_reason(arguments, snapshot, edit) is None


def refinement_mismatch_reason(
    arguments: SearchListingsArguments,
    snapshot: ExecutedSearchSnapshot,
    edit: SearchRefinementEdit,
) -> RefinementRepair | None:
    """Return a bounded repair hint, never proposed replacement arguments."""

    if any((
        arguments.category_id != snapshot.category_id,
        arguments.category_name != snapshot.category_name,
        arguments.city != snapshot.city,
        arguments.county != snapshot.county,
        arguments.limit != snapshot.limit,
    )):
        return "UNCHANGED_FILTER_CHANGED"
    if edit.kind in {"MAX_PRICE", "MIN_PRICE"}:
        if (
            arguments.condition != snapshot.condition
            or arguments.currency != edit.currency
        ):
            return "UNCHANGED_FILTER_CHANGED"
        if _query_terms(arguments.query) != _query_terms(snapshot.query):
            return "QUERY_TERMS_CHANGED"
        if edit.kind == "MAX_PRICE":
            return None if (
                arguments.maximum_price == edit.value
                and arguments.minimum_price == snapshot.minimum_price
            ) else "EDIT_NOT_APPLIED"
        return None if (
            arguments.minimum_price == edit.value
            and arguments.maximum_price == snapshot.maximum_price
        ) else "EDIT_NOT_APPLIED"
    if (
        arguments.minimum_price != snapshot.minimum_price
        or arguments.maximum_price != snapshot.maximum_price
    ):
        return "PRICE_FILTER_CHANGED"
    if (
        arguments.currency != snapshot.currency and not (
            snapshot.minimum_price is None
            and snapshot.maximum_price is None
            and snapshot.currency is None
            and arguments.currency == "USD"
        )
    ):
        return "UNCHANGED_FILTER_CHANGED"
    if edit.kind == "CONDITION":
        if _query_terms(arguments.query) != _query_terms(snapshot.query):
            return "QUERY_TERMS_CHANGED"
        return None if arguments.condition == edit.value else "EDIT_NOT_APPLIED"
    if arguments.condition != snapshot.condition:
        return "UNCHANGED_FILTER_CHANGED"
    if edit.kind == "QUERY_REPLACEMENT":
        return (
            None if _query_terms(arguments.query) == _query_terms(str(edit.value))
            else "QUERY_TERMS_CHANGED"
        )
    pattern = {
        "RAM": _RAM_FRAGMENT,
        "SIZE": _SIZE_FRAGMENT,
        "WIRELESS": _WIRELESS_FRAGMENT,
        "NO_RGB": _NO_RGB_FRAGMENT,
    }[edit.kind]
    found = pattern.findall(arguments.query)
    if len(found) != 1 or _query_terms(found[0]) != _query_terms(str(edit.value)):
        return "EDIT_NOT_APPLIED"
    if _query_core(arguments.query, pattern) != _query_core(snapshot.query, pattern):
        return "QUERY_TERMS_CHANGED"
    return None


def refinement_repair_signal(
    snapshot: ExecutedSearchSnapshot,
    edit: SearchRefinementEdit,
    mismatch: RefinementRepair,
) -> RefinementRepairSignal:
    """Describe the rejected edit using executed facts, without authoring a call."""

    if edit.kind == "QUERY_REPLACEMENT":
        requested = RefinementRepairEdit(
            field="QUERY", operation="REPLACE", value=str(edit.value),
        )
    elif edit.kind in {"RAM", "SIZE", "WIRELESS", "NO_RGB"}:
        pattern = {
            "RAM": _RAM_FRAGMENT, "SIZE": _SIZE_FRAGMENT,
            "WIRELESS": _WIRELESS_FRAGMENT, "NO_RGB": _NO_RGB_FRAGMENT,
        }[edit.kind]
        requested = RefinementRepairEdit(
            field="QUERY_ATTRIBUTE",
            operation="REPLACE" if pattern.search(snapshot.query) else "ADD",
            value=str(edit.value),
        )
    elif edit.kind in {"MAX_PRICE", "MIN_PRICE"}:
        requested = RefinementRepairEdit(
            field="PRICE", operation="UPDATE",
            value=f"{edit.kind} {edit.value} {edit.currency}",
        )
    else:
        requested = RefinementRepairEdit(
            field="CONDITION", operation="UPDATE", value=str(edit.value),
        )
    return RefinementRepairSignal(
        mismatch=mismatch,
        currentSearch=RefinementRepairSearch(
            query=snapshot.query, categoryId=snapshot.category_id,
            categoryName=snapshot.category_name, condition=snapshot.condition,
            minimumPrice=snapshot.minimum_price,
            maximumPrice=snapshot.maximum_price, currency=snapshot.currency,
            city=snapshot.city, county=snapshot.county, limit=snapshot.limit,
        ),
        requestedEdit=requested,
        preserveProductCore=edit.kind != "QUERY_REPLACEMENT",
    )


def _query_core(query: str, pattern: re.Pattern[str]) -> tuple[str, ...]:
    return tuple(sorted(
        word for word in _words(pattern.sub(" ", query))
        if word not in _QUERY_FILLERS
    ))


def _query_terms(query: str) -> tuple[str, ...]:
    """Compare equivalent query terms without requiring model word order."""

    return tuple(sorted(_words(query)))


def _words(query: str) -> tuple[str, ...]:
    return tuple(
        "no" if word == "without" else "inch" if word == "inches" else (
            word[:-1] if len(word) > 4 and word.endswith("s")
            and not word.endswith("ss") else word
        )
        for word in re.findall(r"[a-z0-9]+", query.casefold())
    )
