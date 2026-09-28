---
article_id: HELP-AI-001
title: Use the optional Marketplace Assistant
audience: signed-in users in AI demo builds
availability: AI demo builds only
---

# Use the optional Marketplace Assistant

## Common questions

- Where is the Marketplace Assistant?
- Can it find or compare listings?
- Can it buy, pay, publish, or message a seller for me?
- What should I do if a response is interrupted?

## Who this applies to

Signed-in users in an environment that shows **Marketplace assistant** in messages or the optional agent page.

## Prerequisites

The matching frontend, gateway routes, agent API, provider, and product tools must be enabled. Normal marketplace browsing and selling continue to work when the assistant is unavailable.

## Pages

- [Messages and assistant entry](/account/messages)
- [Legacy assistant route when enabled](/account/messages/agent)
- [Agent V2 evaluation route when enabled](/account/marketplace-agent-v2)

## Ask for marketplace help

1. Open **Inbox** or **Messages**.
2. Select **Marketplace assistant** when the entry is shown.
3. Enter a request in **Message the marketplace assistant** or **Message**. You can describe an item, budget, location, or ask a marketplace question.
4. Select **Send**.
5. Review attached listings and select **View listing** before taking any next step.
6. Use the displayed refinement choices, **Not interested**, or a follow-up message to narrow results when those controls are shown.
7. Select **New conversation** or **Start new conversation** when you want to clear the current search context.
8. Select **Stop generation** to interrupt a response. If the result is uncertain, refresh history before choosing **Retry response**.

## Listing-specific help

On an individual listing, an enabled build may show **Ask AI about this listing**. It uses published, public-safe listing details and does not send a message to the seller.

## Availability and safety limits

- The assistant is optional and off by default in normal production and development configurations.
- It can surface current public listing information only through allowed application tools. Open the listing page to confirm current price, status, seller type, and availability.
- It cannot bypass sign-in, ownership, business membership, validation, moderation, or confirmation rules.
- It does not make an individual payment safe or protected and cannot turn an individual trade into a platform order.
- It does not approve businesses, publish listings automatically, charge real payments, issue significant refunds, or settle disputes.
- An interrupted or failed response is not proof that an action occurred. Use the authoritative cart, order, listing, or message page to confirm state.
