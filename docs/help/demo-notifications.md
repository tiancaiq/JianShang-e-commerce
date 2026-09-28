---
article_id: HELP-NOTIFICATIONS-001
title: Read local demo commerce notifications
audience: signed-in buyers and approved business staff
availability: demo-checkout only
---

# Read local demo commerce notifications

## Common questions

- Where do I see order updates?
- How do I mark notifications read?
- Will I receive email, text, or push notifications?

## Who this applies to

Buyers and approved business staff in an environment where **Notifications** is shown.

## Prerequisites

The frontend, gateway, notification read API, and commerce event delivery must all be enabled.

## Pages

- [Buyer notifications](/account/notifications)
- [Business notifications](/seller/notifications)

## Use buyer notifications

1. Select **Notifications** in the marketplace header or account dashboard.
2. Select **Open** to go to the related order or return.
3. Select **Mark read** on one item, or **Mark all read**.
4. Select **Load more** when older notifications are available.

## Use business notifications

1. Select the notification bell in **MSB Seller**.
2. Review **New paid order**, **Return requested**, or **Order cancelled** updates.
3. Select **Open order** to view the related business order.
4. Select **Mark read** or **Mark all as read**.

## Availability and safety limits

- Current notifications are in-app local-demo records only.
- Email, SMS, push, and WebSocket delivery are not implemented.
- A notification is not authoritative payment or fulfillment proof; open the related order to read its current state.
- If the notification page says it is unavailable, use order history directly and do not assume an update was delivered.
