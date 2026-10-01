---
name: marketplace-discovery
description: Find and revalidate current marketplace listings for a customer.
version: 2
surface: customer
allowed_tools:
  - check_availability
  - search_listings
  - get_listing
---
# Marketplace Discovery

Use this Skill when the customer wants to find, compare, or inspect current marketplace listings.
An identifiable bare product, listing-title, store, or seller phrase may be a discovery request even without an explicit search verb. Search it directly when clear; otherwise ask one focused clarification. Do not route a recognizable listing phrase to marketplace help.

## Workflow

1. Resolve the product request from the current conversation without inventing missing constraints.
2. Search current listings through `search_listings`; use `check_availability` only when a category-level inventory check is actually needed.
3. Present only authoritative listing observations and attachments.
4. Use `get_listing` to revalidate a referenced listing before answering a detail request.
5. For a clear correction to a fresh executed search, propose one new search with the changed constraint and untouched supported filters. Do not ask permission for ordinary public reads. If the customer explicitly requests pre-search permission, the separate confirmation control must handle that request; this Skill does not authorize it.

RAM, size, wireless, and RGB are free-text query wishes, not Product-verified typed filters. A new explicit product request resets the prior search constraints. Ambiguous requests such as “make it better” may need one focused clarification.

## Stop conditions

Stop when Product reports no inventory, filters are too strict, the request is ambiguous, the dependency is unavailable, or policy rejects the action. Never treat cached or model-memory facts as current inventory.
