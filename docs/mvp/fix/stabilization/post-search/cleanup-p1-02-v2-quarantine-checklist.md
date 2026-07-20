# CLEAN-P1-02 V2 Quarantine Checklist

Status: Complete  
Date: 2026-07-19

## Goal

Keep V2 commerce work intact while preventing it from entering the MVP
stabilization PR.

This checklist does not delete, revert, stage, or change application behavior.

## Implementation Result

The current worktree was inspected with:

```powershell
git status --short --untracked-files=all inventory-service order-service frontend/src/app/features/cart frontend/src/app/features/business/business-inventory.component.ts frontend/src/app/features/business/business-inventory.component.spec.ts frontend/src/app/core/models/cart.model.ts frontend/src/app/core/models/inventory.model.ts frontend/src/app/core/models/address.model.ts frontend/src/app/core/services/cart.service.ts frontend/src/app/core/services/address-book.service.ts frontend/src/app/core/services/inventory.service.ts docs/v2/commerce
git diff -- api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java frontend/src/app/app.routes.ts frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts pom.xml
```

Result: V2 commerce is present in both whole-file additions and mixed tracked
files. It must be kept out of the MVP stabilization PR through a combination
of whole-file exclusion and hunk staging.

No files were staged during this cleanup. No environment files were edited.

## V2 Work To Quarantine

### Backend Services

Park these service-level changes together:

- `inventory-service/`
- `order-service/`
- root `pom.xml` module additions for `inventory-service` and `order-service`
- gateway routes for:
  - `/api/v1/cart`
  - `/api/v1/cart/**`
  - `/api/v1/businesses/*/inventory`
  - `/api/v1/businesses/*/inventory/**`

Current tracked service changes to quarantine:

- `inventory-service/pom.xml`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/InventoryServiceApplication.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/controller/InventoryController.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/Inventory.java`
  - Currently deleted in the worktree as part of the V2 rewrite.
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryRepository.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/service/InventoryService.java`
- `inventory-service/src/main/resources/application.properties`
- `inventory-service/src/test/java/com/msb/ecom/inventory_service/InventoryServiceApplicationTests.java`
- `order-service/pom.xml`
- `order-service/src/main/java/com/msb/ecom/order_service/controller/OrderController.java`
  - Currently deleted in the worktree as part of the V2 cart rewrite.
- `order-service/src/main/java/com/msb/ecom/order_service/dto/OrderRequest.java`
  - Currently deleted in the worktree.
- `order-service/src/main/java/com/msb/ecom/order_service/dto/OrderResponse.java`
  - Currently deleted in the worktree.
- `order-service/src/main/java/com/msb/ecom/order_service/model/Order.java`
  - Currently deleted in the worktree.
- `order-service/src/main/java/com/msb/ecom/order_service/repository/OrderRepository.java`
  - Currently deleted in the worktree.
- `order-service/src/main/java/com/msb/ecom/order_service/service/OrderService.java`
  - Currently deleted in the worktree.
- `order-service/src/main/resources/application.properties`
- `order-service/src/test/java/com/msb/ecom/order_service/OrderServiceApplicationTests.java`
  - Currently deleted in the worktree.

Current untracked service additions to quarantine:

- `inventory-service/src/main/java/com/msb/ecom/inventory_service/config/SecurityConfig.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/controller/InternalInventoryController.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/controller/InventoryExceptionHandler.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/dto/`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/InventoryException.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/InventoryOperation.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/InventoryReason.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/ReservationPurpose.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/ReservationReleaseReason.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/model/ReservationStatus.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/IdempotencyRecord.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryItemRecord.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryMovementRecord.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryReservationItemRecord.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryReservationRecord.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/repository/InventoryReservationRepository.java`
- `inventory-service/src/main/java/com/msb/ecom/inventory_service/service/`
- `inventory-service/src/main/resources/db/migration/V2__create_business_inventory_foundation.sql`
- `inventory-service/src/main/resources/db/migration/V3__create_inventory_reservation_lifecycle.sql`
- `order-service/src/main/java/com/msb/ecom/order_service/config/SecurityConfig.java`
- `order-service/src/main/java/com/msb/ecom/order_service/controller/CartController.java`
- `order-service/src/main/java/com/msb/ecom/order_service/controller/CartExceptionHandler.java`
- `order-service/src/main/java/com/msb/ecom/order_service/dto/`
- `order-service/src/main/java/com/msb/ecom/order_service/model/CartDocument.java`
- `order-service/src/main/java/com/msb/ecom/order_service/model/CartException.java`
- `order-service/src/main/java/com/msb/ecom/order_service/model/CartStoredItem.java`
- `order-service/src/main/java/com/msb/ecom/order_service/repository/CartRepository.java`
- `order-service/src/main/java/com/msb/ecom/order_service/repository/RedisCartRepository.java`
- `order-service/src/main/java/com/msb/ecom/order_service/service/`
- `order-service/src/test/java/com/msb/ecom/order_service/CartControllerTests.java`
- `order-service/src/test/java/com/msb/ecom/order_service/repository/RedisCartRepositoryTests.java`
- `order-service/src/test/java/com/msb/ecom/order_service/service/`

### Auth Address Book

Park these auth-service additions together:

- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AddressController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/InternalBusinessStoreCommerceController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/InternalBuyerAddressController.java`
- address request/response DTOs.
- commerce eligibility DTOs.
- `auth-service/src/main/java/com/msb/ecom/auth_service/model/Address.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/repository/AddressRepository.java`
- address-book services, exceptions, and metrics.
- internal commerce authentication/authorization services.
- `auth-service/src/main/resources/db/migration/identity/V202607181800__create_user_addresses.sql`
- `auth-service/src/main/resources/db/migration/identity/V202607191400__cascade_user_address_deletion.sql`
- gateway address-book routes.

Reason: buyer address book supports V2 checkout/orders. It is not needed for
MVP individual trade or public browsing.

### Frontend Commerce UI

Park these frontend additions together:

- `frontend/src/app/core/models/address.model.ts`
- `frontend/src/app/core/models/cart.model.ts`
- `frontend/src/app/core/models/inventory.model.ts`
- `frontend/src/app/core/services/address-book.service.ts`
- `frontend/src/app/core/services/address-book.service.spec.ts`
- `frontend/src/app/core/services/cart.service.ts`
- `frontend/src/app/core/services/cart.service.spec.ts`
- `frontend/src/app/core/services/inventory.service.ts`
- `frontend/src/app/core/services/inventory.service.spec.ts`
- `frontend/src/app/features/account/address-book.component.ts`
- `frontend/src/app/features/account/address-book.component.spec.ts`
- `frontend/src/app/features/cart/cart.component.ts`
- `frontend/src/app/features/cart/cart.component.spec.ts`
- `frontend/src/app/features/business/business-inventory.component.ts`
- `frontend/src/app/features/business/business-inventory.component.spec.ts`
- cart route and seller inventory route hunks in `frontend/src/app/app.routes.ts`
- account address-book route hunk in `frontend/src/app/app.routes.ts`
- cart count and cart reset/load hunks in
  `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- cart-specific navbar/test hunks.

### Product Commerce Internals

Park these product-service additions together:

- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalBusinessStoreItemCommerceController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalStoreItemCommerceController.java`
- `BusinessStoreItemCommerceContext*` DTOs.
- `BusinessStoreItemSearch*` DTOs and criteria if they exist only for seller
  inventory/cart validation.
- `BusinessSkuConflictException` if it is only needed for V2 commerce
  workflows.
- `BusinessStoreItemCommerceContextService`
- `BusinessStoreItemSearchRequests`

Current product-service files to review carefully for quarantine:

- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalBusinessStoreItemCommerceController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalStoreItemCommerceController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/BusinessStoreItemCommerceContextPageResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/BusinessStoreItemCommerceContextResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/BusinessStoreItemSearchPageResponse.java`
- `product-service/src/main/java/com/msb/ecom/product_service/dto/BusinessStoreItemSearchRequest.java`
- `product-service/src/main/java/com/msb/ecom/product_service/model/BusinessSkuConflictException.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/BusinessStoreItemSearchCriteria.java`
- `product-service/src/main/java/com/msb/ecom/product_service/repository/BusinessStoreItemStatusCounts.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/BusinessStoreItemCommerceContextService.java`
- `product-service/src/main/java/com/msb/ecom/product_service/service/BusinessStoreItemSearchRequests.java`

Review note: some business store management search may be MVP. If a class is
used by both MVP store management and V2 cart/inventory validation, split it in
a later focused PR instead of staging it blindly.

### Documentation

Park these docs together:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`
- `docs/v2/commerce/v2-inv-01-business-inventory-foundation-plan.md`
- `docs/v2/commerce/v2-inv-02-inventory-reservation-lifecycle-plan.md`
- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-iam-01-buyer-address-book-plan.md`
- V2 cart/inventory/address sections added to `docs/mvp/api-contract.md`
- V2 status entries added to `docs/mvp/development-roadmap.md`

## Mixed File Hunk Rules

When the MVP PR is staged, use these hunk decisions:

- `pom.xml`
  - Do not stage `inventory-service` or `order-service` module entries.
  - Stage unrelated MVP test configuration only if required by the MVP
    verification baseline.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`
  - Do not stage cart routes.
  - Do not stage inventory routes.
  - Do not stage address-book routes.
  - Stage fallback `PATCH`/`DELETE` only if the included MVP routes need them.
- `frontend/src/app/app.routes.ts`
  - Do not stage `/cart`.
  - Do not stage `/account/addresses`.
  - Do not stage `sellerInventoryRoute` or `/seller/inventory`.
  - Stage `/stores/:storeSlug` only if the MVP storefront files are included.
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
  - Do not stage `CartService` import/injection.
  - Do not stage `[cartCount]`.
  - Do not stage `cartService.load()` or `cartService.reset()`.
  - Stage signed-out confirmation only if the matching logout behavior is in
    the MVP PR.
- `frontend/src/app/features/marketplace/components/marketplace-navbar.component.ts`
  - Do not stage cart-count UI or cart navigation hunks.
  - Stage MVP browse/search/login/logout navigation hunks only.
- `docs/mvp/api-contract.md`
  - Do not stage address-book, cart, inventory, checkout, order, payment, or
    shipping contract expansions.
- `docs/mvp/development-roadmap.md`
  - Do not stage V2 completion statuses in the MVP PR.

## Quarantine Branch Shape

When V2 is ready for review, prefer one branch with this scope:

```text
codex/v2-commerce-quarantine
```

Suggested PR contents:

- root Maven module additions for inventory/order.
- gateway routes for cart, inventory, and address book.
- auth address-book and internal commerce eligibility APIs.
- inventory-service V2 implementation.
- order-service cart implementation.
- frontend cart, inventory, and address-book pages/services/models.
- V2 commerce docs.

Keep AI/agent-service out of the V2 branch unless a later architecture decision
makes AI part of commerce verification.

## Quarantine Validation

Before the MVP PR is opened:

- `/cart` should not be part of the active MVP route set.
- `/seller/inventory` should redirect or remain unreachable unless a V2 branch
  intentionally enables it.
- MVP Maven verification should use:

```powershell
cmd /c mvnw.cmd -pl api-gateway,auth-service,product-service,chat-service -am test
```

- A full reactor build can wait until the V2 branch is prepared.

## Acceptance Criteria

- V2 code remains available in the worktree for later review.
- MVP staging excludes V2 files and hunks.
- The team knows which files belong in the future V2 PR.
- No environment files are edited or staged by this cleanup.
