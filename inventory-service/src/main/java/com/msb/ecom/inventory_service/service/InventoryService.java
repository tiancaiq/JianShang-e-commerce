package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.inventory_service.dto.InventoryAdjustmentRequest;
import com.msb.ecom.inventory_service.dto.InventoryCatalogItemResponse;
import com.msb.ecom.inventory_service.dto.InventoryCatalogPageResponse;
import com.msb.ecom.inventory_service.dto.InventoryInitializeRequest;
import com.msb.ecom.inventory_service.dto.InventoryMovementPageResponse;
import com.msb.ecom.inventory_service.dto.InventoryMovementResponse;
import com.msb.ecom.inventory_service.dto.InventoryResponse;
import com.msb.ecom.inventory_service.model.InventoryException;
import com.msb.ecom.inventory_service.model.InventoryOperation;
import com.msb.ecom.inventory_service.repository.IdempotencyRecord;
import com.msb.ecom.inventory_service.repository.InventoryItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryMovementRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class InventoryService {

    public static final String INVENTORY_VIEW = "INVENTORY_VIEW";
    public static final String INVENTORY_MANAGE = "INVENTORY_MANAGE";

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    private static final int DEFAULT_PAGE_LIMIT = 24;
    private static final int MAX_PAGE_LIMIT = 60;

    private final InventoryRepository repository;
    private final InventoryAuthorizationClient authorizationClient;
    private final ProductCommerceClient productCommerceClient;
    private final CurrentActorProvider currentActorProvider;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;

    public InventoryService(
            InventoryRepository repository,
            InventoryAuthorizationClient authorizationClient,
            ProductCommerceClient productCommerceClient,
            CurrentActorProvider currentActorProvider,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.authorizationClient = authorizationClient;
        this.productCommerceClient = productCommerceClient;
        this.currentActorProvider = currentActorProvider;
        this.ulidGenerator = ulidGenerator;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    // Composes current catalog facts with inventory balances for one authorized business page.
    public InventoryCatalogPageResponse list(
            String businessId,
            String query,
            String listingStatus,
            String cursor,
            Integer limit) {
        String normalizedBusinessId = requiredId("Business ID", businessId);
        CurrentActor actor = currentActorProvider.currentActor();
        authorizationClient.requirePermission(actor.accessToken(), normalizedBusinessId, INVENTORY_VIEW);

        int normalizedLimit = normalizedLimit(limit);
        ProductCommerceClient.CatalogPage catalog = productCommerceClient.getBusinessItems(
                normalizedBusinessId,
                query,
                listingStatus,
                cursor,
                normalizedLimit);
        Map<String, InventoryItemRecord> inventoryByListing = repository.findItems(
                normalizedBusinessId,
                catalog.data().stream().map(ProductCommerceClient.CatalogItem::listingId).toList());

        List<InventoryCatalogItemResponse> rows = catalog.data().stream()
                .map(item -> {
                    InventoryItemRecord inventory = inventoryByListing.get(item.listingId());
                    return new InventoryCatalogItemResponse(
                            item.listingId(),
                            item.title(),
                            item.sku(),
                            item.status(),
                            item.quantity(),
                            item.version(),
                            inventory == null ? "NOT_INITIALIZED" : "INITIALIZED",
                            inventory == null ? null : response(inventory));
                })
                .toList();
        return new InventoryCatalogPageResponse(
                rows,
                new InventoryCatalogPageResponse.PageMetadata(
                        catalog.page().nextCursor(),
                        catalog.page().hasMore()));
    }

    @Transactional(readOnly = true)
    public InventoryResponse get(String businessId, String listingId) {
        AuthorizedRequest request = authorize(businessId, INVENTORY_VIEW);
        return response(requireItem(request.businessId(), requiredId("Listing ID", listingId)));
    }

    @Transactional
    // Initializes authoritative stock once and records the opening movement and outbox event atomically.
    public InventoryResponse initialize(
            String businessId,
            String listingId,
            String idempotencyKey,
            InventoryInitializeRequest request,
            String correlationId) {
        AuthorizedRequest authorized = authorize(businessId, INVENTORY_MANAGE);
        String normalizedListingId = requiredId("Listing ID", listingId);
        String key = requiredIdempotencyKey(idempotencyKey);
        String note = normalizedNote(request.note());
        String callerScope = callerScope(authorized.userId(), "INITIALIZE", authorized.businessId(), normalizedListingId);
        String requestHash = hash("INITIALIZE", Integer.toString(request.onHand()), note);

        InventoryResponse replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            log.info("Replayed inventory initialization businessId={} listingId={}",
                    authorized.businessId(), normalizedListingId);
            return replay;
        }

        ProductCommerceClient.CatalogItem catalog =
                productCommerceClient.getBusinessItem(authorized.businessId(), normalizedListingId);
        requireEligible(catalog, authorized.businessId(), normalizedListingId);
        if (repository.findItem(authorized.businessId(), normalizedListingId).isPresent()) {
            throw conflict("INVENTORY_ALREADY_INITIALIZED", "Inventory is already initialized for this listing.");
        }

        Instant now = Instant.now();
        String inventoryItemId = ulidGenerator.next();
        String commandId = ulidGenerator.next();
        InventoryItemRecord item = new InventoryItemRecord(
                inventoryItemId,
                authorized.businessId(),
                normalizedListingId,
                catalog.sku(),
                catalog.version(),
                request.onHand(),
                0,
                0,
                now,
                now,
                now);
        try {
            repository.insertItem(item);
        } catch (DuplicateKeyException exception) {
            throw conflict("INVENTORY_ALREADY_INITIALIZED", "Inventory is already initialized for this listing.");
        }
        repository.insertMovement(
                ulidGenerator.next(),
                item,
                "INITIALIZE",
                "INITIAL_STOCK",
                request.onHand(),
                0,
                request.onHand(),
                note,
                authorized.userId(),
                commandId,
                correlationId,
                now);

        InventoryResponse response = response(item);
        repository.insertOutbox(
                ulidGenerator.next(),
                item.id(),
                "inventory.initialized.v1",
                eventPayload(response, commandId, "INITIALIZE", "INITIAL_STOCK"),
                correlationId,
                commandId,
                now);
        repository.insertIdempotency(
                commandId,
                callerScope,
                key,
                requestHash,
                "INITIALIZE",
                item.id(),
                HttpStatus.CREATED.value(),
                json(response),
                now);

        log.info("Initialized inventory businessId={} listingId={} onHand={}",
                authorized.businessId(), normalizedListingId, request.onHand());
        return response;
    }

    @Transactional
    // Applies one optimistic stock command and writes its movement, idempotency result, and event atomically.
    public InventoryResponse adjust(
            String businessId,
            String listingId,
            long expectedVersion,
            String idempotencyKey,
            InventoryAdjustmentRequest request,
            String correlationId) {
        AuthorizedRequest authorized = authorize(businessId, INVENTORY_MANAGE);
        String normalizedListingId = requiredId("Listing ID", listingId);
        String key = requiredIdempotencyKey(idempotencyKey);
        String note = normalizedNote(request.note());
        String callerScope = callerScope(authorized.userId(), "ADJUST", authorized.businessId(), normalizedListingId);
        String requestHash = hash(
                "ADJUST",
                request.operation().name(),
                Integer.toString(request.quantity()),
                request.reason().name(),
                note,
                Long.toString(expectedVersion));

        InventoryResponse replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            log.info("Replayed inventory adjustment businessId={} listingId={}",
                    authorized.businessId(), normalizedListingId);
            return replay;
        }

        ProductCommerceClient.CatalogItem catalog =
                productCommerceClient.getBusinessItem(authorized.businessId(), normalizedListingId);
        requireEligible(catalog, authorized.businessId(), normalizedListingId);
        InventoryItemRecord item = requireItem(authorized.businessId(), normalizedListingId);

        int nextOnHand = nextOnHand(item.onHand(), request);
        if (nextOnHand < item.reserved()) {
            throw new InventoryException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVENTORY_AVAILABLE_WOULD_BE_NEGATIVE",
                    "On-hand quantity cannot be lower than reserved quantity.");
        }

        Instant now = Instant.now();
        int updated = repository.updateOnHand(
                item.id(),
                authorized.businessId(),
                expectedVersion,
                nextOnHand,
                now);
        if (updated == 0) {
            InventoryItemRecord current = requireItem(authorized.businessId(), normalizedListingId);
            if (current.version() != expectedVersion) {
                throw conflict("INVENTORY_VERSION_CONFLICT", "Inventory was changed by another request.");
            }
            throw new InventoryException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVENTORY_AVAILABLE_WOULD_BE_NEGATIVE",
                    "On-hand quantity cannot be lower than reserved quantity.");
        }

        String commandId = ulidGenerator.next();
        int delta = Math.subtractExact(nextOnHand, item.onHand());
        repository.insertMovement(
                ulidGenerator.next(),
                item,
                request.operation().name(),
                request.reason().name(),
                delta,
                item.onHand(),
                nextOnHand,
                note,
                authorized.userId(),
                commandId,
                correlationId,
                now);

        InventoryItemRecord changed = new InventoryItemRecord(
                item.id(),
                item.businessId(),
                item.listingId(),
                item.skuSnapshot(),
                item.catalogVersionSnapshot(),
                nextOnHand,
                item.reserved(),
                item.version() + 1,
                item.initializedAt(),
                item.createdAt(),
                now);
        InventoryResponse response = response(changed);
        repository.insertOutbox(
                ulidGenerator.next(),
                item.id(),
                "inventory.adjusted.v1",
                eventPayload(response, commandId, request.operation().name(), request.reason().name()),
                correlationId,
                commandId,
                now);
        repository.insertIdempotency(
                commandId,
                callerScope,
                key,
                requestHash,
                request.operation().name(),
                item.id(),
                HttpStatus.OK.value(),
                json(response),
                now);

        log.info("Adjusted inventory businessId={} listingId={} operation={} delta={} onHand={}",
                authorized.businessId(), normalizedListingId, request.operation(), delta, nextOnHand);
        return response;
    }

    @Transactional(readOnly = true)
    public InventoryMovementPageResponse movements(
            String businessId,
            String listingId,
            String cursor,
            Integer limit) {
        AuthorizedRequest authorized = authorize(businessId, INVENTORY_VIEW);
        InventoryItemRecord item = requireItem(authorized.businessId(), requiredId("Listing ID", listingId));
        int normalizedLimit = normalizedLimit(limit);
        InventoryCursors.MovementCursor decoded = InventoryCursors.decodeMovementCursor(cursor);
        List<InventoryMovementRecord> fetched = repository.findMovements(
                item.id(),
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                normalizedLimit + 1);
        boolean hasMore = fetched.size() > normalizedLimit;
        List<InventoryMovementRecord> page = hasMore ? fetched.subList(0, normalizedLimit) : fetched;
        String nextCursor = hasMore && !page.isEmpty()
                ? InventoryCursors.encodeMovementCursor(page.get(page.size() - 1))
                : null;
        return new InventoryMovementPageResponse(
                page.stream().map(this::movementResponse).toList(),
                new InventoryMovementPageResponse.PageMetadata(nextCursor, hasMore));
    }

    private AuthorizedRequest authorize(String businessId, String permission) {
        String normalizedBusinessId = requiredId("Business ID", businessId);
        CurrentActor actor = currentActorProvider.currentActor();
        InventoryAuthorizationClient.BusinessAuthorization authorization =
                authorizationClient.requirePermission(actor.accessToken(), normalizedBusinessId, permission);
        return new AuthorizedRequest(authorization.businessId(), authorization.userId());
    }

    private InventoryItemRecord requireItem(String businessId, String listingId) {
        return repository.findItem(businessId, listingId)
                .orElseThrow(() -> new InventoryException(
                        HttpStatus.NOT_FOUND,
                        "INVENTORY_NOT_FOUND",
                        "Inventory has not been initialized for this listing."));
    }

    private void requireEligible(
            ProductCommerceClient.CatalogItem item,
            String businessId,
            String listingId) {
        boolean eligible = item != null
                && businessId.equals(item.businessId())
                && listingId.equals(item.listingId())
                && "BUSINESS".equals(item.sellerType())
                && item.sku() != null
                && !item.sku().isBlank()
                && ("ACTIVE".equals(item.status()) || "PAUSED".equals(item.status()));
        if (!eligible) {
            throw new InventoryException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVENTORY_LISTING_INELIGIBLE",
                    "This listing is not eligible for business inventory.");
        }
    }

    private int nextOnHand(int currentOnHand, InventoryAdjustmentRequest request) {
        if (request.operation() == InventoryOperation.SET) {
            if (request.quantity() < 0) {
                throw invalidAdjustment("Set quantity must be zero or greater.");
            }
            return request.quantity();
        }
        if (request.quantity() == 0) {
            throw invalidAdjustment("Adjustment quantity cannot be zero.");
        }
        try {
            int result = Math.addExact(currentOnHand, request.quantity());
            if (result < 0) {
                throw invalidAdjustment("Adjustment would make on-hand quantity negative.");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw invalidAdjustment("Adjustment exceeds the supported quantity range.");
        }
    }

    private InventoryResponse replay(String callerScope, String key, String requestHash) {
        IdempotencyRecord record = repository.findIdempotency(callerScope, key).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.requestHash().equals(requestHash)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for another request.");
        }
        try {
            return objectMapper.readValue(record.responseJson(), InventoryResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored inventory response could not be read.", exception);
        }
    }

    private InventoryResponse response(InventoryItemRecord item) {
        return new InventoryResponse(
                item.id(),
                item.businessId(),
                item.listingId(),
                item.skuSnapshot(),
                item.onHand(),
                item.reserved(),
                item.onHand() - item.reserved(),
                item.version(),
                item.initializedAt(),
                item.updatedAt());
    }

    private InventoryMovementResponse movementResponse(InventoryMovementRecord movement) {
        return new InventoryMovementResponse(
                movement.id(),
                movement.operation(),
                movement.reason(),
                movement.quantityDelta(),
                movement.onHandBefore(),
                movement.onHandAfter(),
                movement.reservedSnapshot(),
                movement.note(),
                movement.createdAt());
    }

    private String eventPayload(
            InventoryResponse response,
            String commandId,
            String operation,
            String reason) {
        return json(Map.ofEntries(
                Map.entry("commandId", commandId),
                Map.entry("businessId", response.businessId()),
                Map.entry("listingId", response.listingId()),
                Map.entry("inventoryItemId", response.id()),
                Map.entry("onHand", response.onHand()),
                Map.entry("reserved", response.reserved()),
                Map.entry("available", response.available()),
                Map.entry("version", response.version()),
                Map.entry("operation", operation),
                Map.entry("reason", reason),
                Map.entry("occurredAt", response.updatedAt().toString())));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Inventory response could not be serialized.", exception);
        }
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String callerScope(String userId, String operation, String businessId, String listingId) {
        return userId + ":" + operation + ":" + businessId + ":" + listingId;
    }

    private String requiredId(String fieldName, String value) {
        return FixedLengthIds.requireTrimmed(fieldName, value, 26);
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be a safe value.");
        }
        return value.trim();
    }

    private String normalizedNote(String value) {
        String note = TextInputs.collapseWhitespaceToNull(value);
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("Inventory note is too long.");
        }
        return note;
    }

    private int normalizedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be at least 1.");
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    private InventoryException invalidAdjustment(String message) {
        return new InventoryException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVENTORY_INVALID_ADJUSTMENT",
                message);
    }

    private InventoryException conflict(String code, String message) {
        return new InventoryException(HttpStatus.CONFLICT, code, message);
    }

    private record AuthorizedRequest(String businessId, String userId) {
    }
}
