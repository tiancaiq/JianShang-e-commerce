---
article_id: HELP-SELL-BUS-001
title: Set up a business seller account and manage a store
audience: signed-in business applicants and approved business staff
availability: core
---

# Set up a business seller account and manage a store

## Common questions

- How do I apply to sell as a business?
- How do I set up the public store profile?
- How do I publish, pause, or relist store items?
- Where are inventory and business orders?

## Who this applies to

Business applicants and approved staff using the **MSB Seller** portal. Individual sellers should use `/account/listings` instead.

## Prerequisites

- Sign in through the seller portal.
- Store and catalog access requires an approved business and active membership/permission for that business.

## Pages

- [Seller dashboard](/seller/dashboard)
- [Business application](/seller/business/apply)
- [Business account](/seller/account)
- [Store profile after approval](/seller/businesses/{businessId}/store)
- [Store items](/seller/store/items)
- [New store item](/seller/store/items/new)

## Apply as a business seller

1. In **MSB Seller**, select **Business Apply**.
2. Enter **Legal business name**, **Business type**, two-letter **Country**, **Contact email**, **Public city**, and **Public region**. **Contact phone**, **Website URL**, and **Description** are optional.
3. Select **Save draft**.
4. Review the saved details and select **Submit application**.
5. Return to **Business Apply** to see the application timeline and decision.
6. If an application is rejected, review the displayed reason and use **Start new application** when appropriate.

## Update the public store profile

1. After approval, select **Store profile** from the application or **Business Account**.
2. Edit **Store name**, **Public slug**, **Support email**, **Support phone**, **Logo URL**, **Banner URL**, and **Description**.
3. Select **Save store**.
4. The public profile uses `/stores/{publicSlug}` and exposes only the public store fields.

## Create and manage store items

1. Select **Store Items**, then **New item**.
2. Choose **Category** and **Condition**, complete required category fields, and enter **Title**, **Description**, optional **Condition notes**, **Price**, **Currency**, **SKU**, and **Quantity**.
3. Add up to 10 JPEG, PNG, or WebP images, each 10 MB or less.
4. Select **Save draft**.
5. Open the saved draft and select **Publish** when the required fields and saved images are ready.
6. For an active item, select **Pause** before changing details or images.
7. Edit the paused item, save it, and select **Relist** to make it public again.
8. Use the status rail and **Search items** field on **Store Items** to filter by title, SKU, or status.

## Availability and safety limits

- A submitted application is not approval. Store management requires the approved business context returned by the backend.
- Business access is membership- and permission-scoped. Knowing a business ID does not grant access.
- Publishing a store item makes eligible catalog content public; it does not guarantee inventory, payment, shipment, refund, or buyer protection.
- **Inventory** is not enabled in any committed frontend environment, even though implementation code exists. Do not direct users to it.
- **Orders** and seller notifications appear only in the local `demo-checkout` environment. See [Demo business order fulfillment](demo-business-order-fulfillment.md).
- Admin review and moderation instructions are intentionally excluded from this customer guide.
