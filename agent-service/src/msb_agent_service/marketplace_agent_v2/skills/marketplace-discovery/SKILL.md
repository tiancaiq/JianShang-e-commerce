---
name: marketplace-discovery
description: Find and revalidate current marketplace listings for a customer.
version: 1
surface: customer
allowed_tools:
  - check_availability
  - search_listings
  - get_listing
---
# Marketplace Discovery

Use this Skill when the customer wants to find, compare, or inspect current marketplace listings.

## Workflow

1. Resolve the product request from the current conversation without inventing missing constraints.
2. Search current listings through `search_listings`; use `check_availability` only when a category-level inventory check is actually needed.
3. Present only authoritative listing observations and attachments.
4. Use `get_listing` to revalidate a referenced listing before answering a detail request.

## Stop conditions

Stop when Product reports no inventory, filters are too strict, the request is ambiguous, the dependency is unavailable, or policy rejects the action. Never treat cached or model-memory facts as current inventory.
