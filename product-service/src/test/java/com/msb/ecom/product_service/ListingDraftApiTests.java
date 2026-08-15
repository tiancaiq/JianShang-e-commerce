package com.msb.ecom.product_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.UserCapabilityRestrictedException;
import com.msb.ecom.product_service.model.BusinessCapabilityRestrictedException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import com.msb.ecom.product_service.storage.StorageObjectAccessDeniedException;
import com.msb.ecom.product_service.storage.StorageObjectNotFoundException;
import com.msb.ecom.product_service.storage.StorageUploadTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commerce.internal-service-token=test-commerce-token")
@AutoConfigureMockMvc
class ListingDraftApiTests {

    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String USER_ID = "01U00000000000000000000001";
    private static final String BUSINESS_ID = "01B00000000000000000000001";
    private static final String STORE_ID = "01S00000000000000000000001";

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysqlContainer.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ListingDraftRepository listingDraftRepository;

    @MockBean
    AuthServiceClient authServiceClient;

    @MockBean
    ListingMediaStorage listingMediaStorage;

    @BeforeEach
    void configureMediaStorage() {
        when(authServiceClient.requireCurrentUser(anyString()))
                .thenReturn(new AuthServiceClient.CurrentUser(USER_ID, "ACTIVE"));
        when(listingMediaStorage.createUploadTarget(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> new StorageUploadTarget(
                        "listing-media-test",
                        invocation.getArgument(0),
                        "PUT",
                        "https://storage.example.test/" + invocation.getArgument(0, String.class)));
        when(listingMediaStorage.readObject(anyString()))
                .thenReturn(new byte[]{1, 2, 3});
        when(authServiceClient.searchPublicBusinessStores(
                org.mockito.ArgumentMatchers.nullable(String.class),
                anySet(),
                anySet()))
                .thenReturn(List.of(new AuthServiceClient.PublicBusinessStoreSearchResult(
                        BUSINESS_ID,
                        STORE_ID,
                        "Acme Trading Store",
                        "Acme Trading LLC")));
        when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(),
                        List.of(new AuthServiceClient.BusinessIdentityLabel(
                                BUSINESS_ID,
                                "Acme Trading LLC",
                                STORE_ID,
                                "acme-trading-store",
                                "Acme Trading Store",
                                "Irvine",
                                "Orange County",
                                true))));
    }

    @Test
    void categoriesArePublicReferenceData() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", equalTo(CATEGORY_ID)))
                .andExpect(jsonPath("$[0].slug", equalTo("general")))
                .andExpect(jsonPath("$[0].name", equalTo("General")));
    }

    @Test
    void buyerOrInactiveSellerCannotCreateIndividualDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenThrow(new ListingAuthorizationException("Active individual seller profile is required."));

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void activeIndividualSellerCanCreateDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andExpect(jsonPath("$.quantity", equalTo(4)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")));
    }

    @Test
    void businessMemberWithPermissionCanCreateDraft() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.storeId", equalTo(STORE_ID)))
                .andExpect(jsonPath("$.individualSellerUserId").doesNotExist())
                .andExpect(jsonPath("$.sku", equalTo("SKU-100")))
                .andExpect(jsonPath("$.quantity", equalTo(3)))
                .andExpect(jsonPath("$.negotiable", equalTo(false)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")));
    }

    @Test
    void sellingRestrictionBlocksIndividualDraftWithoutChangingListingState() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        doThrow(new UserCapabilityRestrictedException(
                "USER_SELLING", "SUSPEND", null, "ENF-00000001"))
                .when(authServiceClient).requireUserCapability(USER_ID, "USER_SELLING");
        Integer before = jdbcTemplate.queryForObject("select count(*) from listings", Integer.class);

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(4)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("USER_CAPABILITY_RESTRICTED")));

        Integer after = jdbcTemplate.queryForObject("select count(*) from listings", Integer.class);
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before);
    }

    @Test
    void businessStoreItemRoutesCreateListReadAndEditDrafts() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));

        String createResponse = mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellerType": "INDIVIDUAL",
                                  "businessId": "01BATTACKER000000000000001",
                                  "categoryId": "%s",
                                  "title": "Store keyboard",
                                  "description": "Catalog draft for the business store.",
                                  "condition": "NEW",
                                  "price": {"amount": 59.99, "currency": "USD"},
                                  "negotiable": true,
                                  "sku": "SKU-STORE-1",
                                  "quantity": 5
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.storeId", equalTo(STORE_ID)))
                .andExpect(jsonPath("$.negotiable", equalTo(false)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listingId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", equalTo(listingId)))
                .andExpect(jsonPath("$[0].storeId", equalTo(STORE_ID)));

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/{listingId}", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.storeId", equalTo(STORE_ID)));

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store/items/{listingId}", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellerType": "BUSINESS",
                                  "businessId": "%s",
                                  "categoryId": "%s",
                                  "title": "Store keyboard updated",
                                  "description": "Updated catalog draft.",
                                  "condition": "OPEN_BOX",
                                  "conditionNotes": "Opened for display.",
                                  "price": {"amount": 49.99, "currency": "USD"},
                                  "sku": "SKU-STORE-1",
                                  "quantity": 4
                                }
                                """.formatted(BUSINESS_ID, CATEGORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", equalTo("Store keyboard updated")))
                .andExpect(jsonPath("$.condition", equalTo("OPEN_BOX")))
                .andExpect(jsonPath("$.quantity", equalTo(4)))
                .andExpect(jsonPath("$.version", equalTo(1)));
    }

    @Test
    void internalCommerceContextReturnsBoundedCatalogFactsWithServiceAuthentication() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);

        String createResponse = mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("SKU-COMMERCE-1")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listingId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/store/items/{listingId}/commerce-context",
                        BUSINESS_ID,
                        listingId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.storeId", equalTo(STORE_ID)))
                .andExpect(jsonPath("$.storeName", equalTo("Acme Trading Store")))
                .andExpect(jsonPath("$.storeSlug", equalTo("acme-trading-store")))
                .andExpect(jsonPath("$.businessVerified", equalTo(true)))
                .andExpect(jsonPath("$.publicCity", equalTo("Irvine")))
                .andExpect(jsonPath("$.publicRegion", equalTo("Orange County")))
                .andExpect(jsonPath("$.businessLegalName").doesNotExist())
                .andExpect(jsonPath("$.sku", equalTo("SKU-COMMERCE-1")))
                .andExpect(jsonPath("$.priceAmount", equalTo(59.99)))
                .andExpect(jsonPath("$.currency", equalTo("USD")))
                .andExpect(jsonPath("$.quantity", equalTo(3)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")));

        mockMvc.perform(get(
                        "/api/v1/internal/store/items/{listingId}/commerce-context",
                        listingId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.storeName", equalTo("Acme Trading Store")));

        mockMvc.perform(get(
                        "/api/v1/internal/businesses/{businessId}/store/items/{listingId}/commerce-context",
                        BUSINESS_ID,
                        listingId)
                        .header("X-Internal-Service-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalCommerceContextTreatsAuthLabelOutageAsRetryableDependencyFailure() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);

        String createResponse = mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("SKU-COMMERCE-2")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listingId = objectMapper.readTree(createResponse).get("id").asText();

        when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                .thenThrow(new AuthServiceClient.DependencyUnavailableException());

        mockMvc.perform(get(
                        "/api/v1/internal/store/items/{listingId}/commerce-context",
                        listingId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_DEPENDENCY_UNAVAILABLE")));
    }

    @Test
    void businessStoreItemCreateRequiresMatchingCurrentStoreContext() throws Exception {
        when(authServiceClient.requireBusinessStoreContext(anyString(), eq(BUSINESS_ID)))
                .thenThrow(new ListingAuthorizationException("Active business store context is required."));

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void businessListingCreationRestrictionReturnsSafeForbiddenBeforeDraftInsert() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        int before = countBusinessItems(BUSINESS_ID);
        doThrow(new BusinessCapabilityRestrictedException(
                "BUSINESS_LISTING_CREATION", "RESTRICT", null, "ENF-00000001"))
                .when(authServiceClient).requireBusinessCapability(BUSINESS_ID, "BUSINESS_LISTING_CREATION");

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("BUS-RESTRICTED-CREATE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("BUSINESS_CAPABILITY_RESTRICTED")))
                .andExpect(jsonPath("$.error.fieldErrors[0].field", equalTo("scope")))
                .andExpect(jsonPath("$.error.fieldErrors[0].code", equalTo("BUSINESS_LISTING_CREATION")));

        assertThat(countBusinessItems(BUSINESS_ID)).isEqualTo(before);
    }

    @Test
    void duplicateBusinessStoreItemSkuReturnsConflictWithoutCreatingAnotherDraft() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        String sku = "SKU-CONFLICT-CREATE";

        createBusinessStoreItemDraft(sku);

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest(sku)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("BUSINESS_SKU_CONFLICT")))
                .andExpect(jsonPath("$.error.message", equalTo(
                        "This store already has an item with that SKU. Edit the existing item or use a different SKU.")));

        Integer matches = jdbcTemplate.queryForObject(
                "select count(*) from listings where business_id = ? and sku = ?",
                Integer.class,
                BUSINESS_ID,
                sku);
        org.assertj.core.api.Assertions.assertThat(matches).isEqualTo(1);
    }

    @Test
    void businessStoreItemEditCannotTakeAnotherItemsSku() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        String firstSku = "SKU-CONFLICT-EDIT-A";
        String secondSku = "SKU-CONFLICT-EDIT-B";
        createBusinessStoreItemDraft(firstSku);
        String secondListingId = createBusinessStoreItemDraft(secondSku);

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store/items/{listingId}",
                                BUSINESS_ID,
                                secondListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest(firstSku)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("BUSINESS_SKU_CONFLICT")));

        String persistedSku = jdbcTemplate.queryForObject(
                "select sku from listings where id = ?",
                String.class,
                secondListingId);
        org.assertj.core.api.Assertions.assertThat(persistedSku).isEqualTo(secondSku);
    }

    @Test
    void businessStoreItemMediaRoutesUploadConfirmAndAttachImages() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));

        String createResponse = mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("SKU-MEDIA-1")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listingId = objectMapper.readTree(createResponse).get("id").asText();

        String uploadResponse = mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/upload-request",
                                BUSINESS_ID,
                                listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "keyboard.png",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode upload = objectMapper.readTree(uploadResponse);
        String mediaId = upload.get("id").asText();
        org.assertj.core.api.Assertions.assertThat(upload.get("uploadUrl").asText())
                .isEqualTo("/api/v1/businesses/" + BUSINESS_ID + "/store/items/" + listingId
                        + "/media/" + mediaId + "/content");

        mockMvc.perform(put(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/content",
                                BUSINESS_ID,
                                listingId,
                                mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.IMAGE_PNG)
                        .content(new byte[1024]))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/confirm",
                                BUSINESS_ID,
                                listingId,
                                mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(mediaId)))
                .andExpect(jsonPath("$.uploadStatus", equalTo("UPLOADED")));

        mockMvc.perform(put("/api/v1/businesses/{businessId}/store/items/{listingId}/images", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s", "altText": "keyboard.png"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mediaObjectId", equalTo(mediaId)))
                .andExpect(jsonPath("$[0].altText", equalTo("keyboard.png")));

        verify(listingMediaStorage).uploadObject(
                eq("listings/" + listingId + "/" + mediaId + "/keyboard.png"),
                eq("image/png"),
                argThat(bytes -> bytes.length == 1024));
    }

    @Test
    void businessStoreItemCanBeSelfPublishedPausedAndRelistedToStores() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));

        String listingId = createBusinessStoreItemDraft("SKU-PUBLISH-1");
        String mediaId = createConfirmedBusinessStoreItemMedia(listingId, "keyboard.png");
        attachBusinessStoreItemImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/publish", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.publicationSource", equalTo("BUSINESS_SELF_PUBLISHED")))
                .andExpect(jsonPath("$.publishedAt", notNullValue()))
                .andExpect(jsonPath("$.version", equalTo(1)));

        mockMvc.perform(get("/api/v1/public/stores/listings/search")
                        .queryParam("q", "keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", equalTo(listingId)))
                .andExpect(jsonPath("$.data[0].sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.data[0].transactionNotice").doesNotExist())
                .andExpect(jsonPath("$.data[0].images[0].url", equalTo("/api/v1/public/listing-media/" + imageIdForMedia(mediaId))));

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/pause", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("PAUSED")))
                .andExpect(jsonPath("$.version", equalTo(2)));

        mockMvc.perform(get("/api/v1/public/stores/listings/search")
                        .queryParam("q", "keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/relist", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.publicationSource", equalTo("BUSINESS_SELF_PUBLISHED")))
                .andExpect(jsonPath("$.version", equalTo(3)));

        mockMvc.perform(get("/api/v1/public/stores/listings/search")
                        .queryParam("q", "keyboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", equalTo(listingId)));
    }

    @Test
    void businessStoreItemPublishRequiresAttachedImage() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        String listingId = createBusinessStoreItemDraft("SKU-NO-IMAGE");

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/publish", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")))
                .andExpect(jsonPath("$.error.message", equalTo("At least one attached image is required before publishing.")));
    }

    @Test
    void businessStoreItemManagementSearchFiltersPagesAndReturnsCatalogSummary() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        String firstId = createBusinessStoreItemDraft("BUS06-NEEDLE-A");
        String secondId = createBusinessStoreItemDraft("BUS06-NEEDLE-B");
        String thirdId = createBusinessStoreItemDraft("BUS06-OTHER-C");
        try {
            jdbcTemplate.update(
                    "update listings set title = ?, updated_at = ? where id = ?",
                    "BUS06 Needle keyboard",
                    Timestamp.from(Instant.parse("2026-07-17T10:03:00Z")),
                    firstId);
            jdbcTemplate.update(
                    "update listings set status = 'ACTIVE', publication_source = 'BUSINESS_SELF_PUBLISHED', "
                            + "published_at = ?, updated_at = ? where id = ?",
                    Timestamp.from(Instant.parse("2026-07-17T10:02:00Z")),
                    Timestamp.from(Instant.parse("2026-07-17T10:02:00Z")),
                    secondId);
            jdbcTemplate.update(
                    "update listings set status = 'PAUSED', publication_source = 'BUSINESS_SELF_PUBLISHED', "
                            + "published_at = ?, updated_at = ? where id = ?",
                    Timestamp.from(Instant.parse("2026-07-17T10:01:00Z")),
                    Timestamp.from(Instant.parse("2026-07-17T10:01:00Z")),
                    thirdId);

            String firstPage = mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/search", BUSINESS_ID)
                            .queryParam("q", "bus06-needle")
                            .queryParam("limit", "1")
                            .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(firstId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(true)))
                    .andExpect(jsonPath("$.page.nextCursor", notNullValue()))
                    .andExpect(jsonPath("$.summary.total", equalTo(countBusinessItems(BUSINESS_ID))))
                    .andExpect(jsonPath("$.summary.draft", equalTo(countBusinessItemsByStatus(BUSINESS_ID, "DRAFT"))))
                    .andExpect(jsonPath("$.summary.active", equalTo(countBusinessItemsByStatus(BUSINESS_ID, "ACTIVE"))))
                    .andExpect(jsonPath("$.summary.paused", equalTo(countBusinessItemsByStatus(BUSINESS_ID, "PAUSED"))))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String cursor = objectMapper.readTree(firstPage).path("page").path("nextCursor").asText();
            mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/search", BUSINESS_ID)
                            .queryParam("q", "bus06-needle")
                            .queryParam("cursor", cursor)
                            .queryParam("limit", "1")
                            .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(secondId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(false)));

            mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/search", BUSINESS_ID)
                            .queryParam("status", "PAUSED")
                            .queryParam("q", "BUS06-OTHER-C")
                            .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(thirdId)))
                    .andExpect(jsonPath("$.data[0].status", equalTo("PAUSED")));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?, ?)", firstId, secondId, thirdId);
        }
    }

    @Test
    void businessStoreItemManagementSearchRejectsCrossBusinessAccess() throws Exception {
        when(authServiceClient.requireBusinessStoreContext(anyString(), eq(BUSINESS_ID)))
                .thenThrow(new ListingAuthorizationException("Active business store context is required."));

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/search", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-business-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void pausedBusinessStoreItemCanBeEditedBeforeRelistWhileActiveEditsAreRejected() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        String listingId = createBusinessStoreItemDraft("BUS06-EDIT-PAUSED");
        String mediaId = createConfirmedBusinessStoreItemMedia(listingId, "paused-keyboard.png");
        attachBusinessStoreItemImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/publish", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/pause", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store/items/{listingId}", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellerType": "BUSINESS",
                                  "businessId": "%s",
                                  "categoryId": "%s",
                                  "title": "Paused keyboard updated",
                                  "description": "Updated while private.",
                                  "condition": "OPEN_BOX",
                                  "price": {"amount": 44.99, "currency": "USD"},
                                  "sku": "BUS06-EDIT-PAUSED",
                                  "quantity": 6
                                }
                                """.formatted(BUSINESS_ID, CATEGORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", equalTo("Paused keyboard updated")))
                .andExpect(jsonPath("$.status", equalTo("PAUSED")))
                .andExpect(jsonPath("$.version", equalTo(3)));

        mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/upload-request",
                                BUSINESS_ID,
                                listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "paused-extra.png",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items/{listingId}/relist", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.version", equalTo(4)));

        mockMvc.perform(patch("/api/v1/businesses/{businessId}/store/items/{listingId}", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("BUS06-EDIT-PAUSED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", equalTo("Pause an active store item before editing it.")));

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("BUS06-EDIT-PAUSED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", equalTo(
                        "Use the business store item route to edit paused items; active items must be paused first.")));

        mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/upload-request",
                                BUSINESS_ID,
                                listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "active-extra.png",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", equalTo("Pause an active store item before changing its images.")));

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "active-generic-extra.png",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", equalTo("Pause an active store item before changing its images.")));
    }

    @Test
    void clientOwnerAndStatusFieldsAreIgnored() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellerType": "INDIVIDUAL",
                                  "categoryId": "%s",
                                  "title": "Server-owned draft",
                                  "description": "Client tries to smuggle owner and status fields.",
                                  "condition": "GOOD",
                                  "price": {"amount": 12.50, "currency": "USD"},
                                  "negotiable": true,
                                  "quantity": 1,
                                  "individualSellerUserId": "01UATTACKER00000000000001",
                                  "status": "ACTIVE",
                                  "moderationStatus": "APPROVED"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")));
    }

    @Test
    void activeIndividualSellerCanRequestAndConfirmMediaUpload() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        String uploadResponse = mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "bike.png",
                                  "sizeBytes": 1024,
                                  "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.contentType", equalTo("image/png")))
                .andExpect(jsonPath("$.uploadStatus", equalTo("PENDING_UPLOAD")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.objectBucket", equalTo("listing-media-test")))
                .andExpect(jsonPath("$.uploadMethod", equalTo("PUT")))
                .andExpect(jsonPath("$.uploadUrl", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode upload = objectMapper.readTree(uploadResponse);
        String mediaId = upload.get("id").asText();
        org.assertj.core.api.Assertions.assertThat(upload.get("uploadUrl").asText())
                .isEqualTo("/api/v1/listings/" + listingId + "/media/" + mediaId + "/content");

        mockMvc.perform(put("/api/v1/listings/{listingId}/media/{mediaId}/content", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.IMAGE_PNG)
                        .content(new byte[1024]))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/{mediaId}/confirm", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024,
                                  "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(mediaId)))
                .andExpect(jsonPath("$.uploadStatus", equalTo("UPLOADED")))
                .andExpect(jsonPath("$.version", equalTo(1)));

        verify(listingMediaStorage).uploadObject(
                eq("listings/" + listingId + "/" + mediaId + "/bike.png"),
                eq("image/png"),
                argThat(bytes -> bytes.length == 1024));
        verify(listingMediaStorage).verifyUploaded(
                "listings/" + listingId + "/" + mediaId + "/bike.png",
                "image/png",
                1024L);
    }

    @Test
    void confirmMediaUploadRequiresObjectStorageObject() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createPendingMedia(listingId, "bike.png");

        doThrow(new IllegalArgumentException("Uploaded object was not found in storage."))
                .when(listingMediaStorage)
                .verifyUploaded(anyString(), anyString(), anyLong());

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/{mediaId}/confirm", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")))
                .andExpect(jsonPath("$.error.message", equalTo("Uploaded object was not found in storage.")));
    }

    @Test
    void businessMemberWithPermissionCanRequestMediaUpload() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        String listingId = createBusinessDraft();

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/webp",
                                  "fileName": "keyboard.webp",
                                  "sizeBytes": 2048
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.uploadStatus", equalTo("PENDING_UPLOAD")));
    }

    @Test
    void invalidMediaTypeIsRejected() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "application/pdf",
                                  "fileName": "bike.pdf",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void sellerCannotRequestMediaForAnotherSellersDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/jpeg",
                                  "fileName": "bike.jpg",
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void sellerCannotUploadMediaBytesForAnotherSellersDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createPendingMedia(listingId, "bike.png");

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(put("/api/v1/listings/{listingId}/media/{mediaId}/content", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .contentType(MediaType.IMAGE_PNG)
                        .content(new byte[1024]))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void sellerCannotConfirmMediaForAnotherSellersDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createPendingMedia(listingId, "bike.png");

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/media/{mediaId}/confirm", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void activeIndividualSellerCanAttachConfirmedImages() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");

        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {
                                      "mediaId": "%s",
                                      "altText": "Blue bike"
                                    }
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listingId", equalTo(listingId)))
                .andExpect(jsonPath("$[0].mediaObjectId", equalTo(mediaId)))
                .andExpect(jsonPath("$[0].displayOrder", equalTo(0)))
                .andExpect(jsonPath("$[0].altText", equalTo("Blue bike")))
                .andExpect(jsonPath("$[0].moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$[0].uploadStatus", equalTo("UPLOADED")));

        mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].mediaObjectId", equalTo(mediaId)))
                .andExpect(jsonPath("$.images[0].originalFileName", equalTo("bike.png")));
    }

    @Test
    void pendingMediaCannotBeAttachedAsListingImage() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createPendingMedia(listingId, "bike.png");

        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void mediaFromAnotherListingCannotBeAttached() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String firstListingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(firstListingId, "bike.png");
        String secondListingId = createIndividualDraft();

        mockMvc.perform(put("/api/v1/listings/{listingId}/images", secondListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_MEDIA_NOT_FOUND")));
    }

    @Test
    void sellerCannotAttachImagesForAnotherSellersDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void activeIndividualSellerCanReadAndListOwnDrafts() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)));

        mockMvc.perform(get("/api/v1/users/me/listings")
                .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(listingId)));
    }

    @Test
    void ownerCanEditIndividualDraftWithCurrentVersion() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellerType": "INDIVIDUAL",
                                  "categoryId": "%s",
                                  "title": "Updated bicycle",
                                  "description": "Freshly tuned city bike.",
                                  "condition": "LIKE_NEW",
                                  "price": {"amount": 275.00, "currency": "USD"},
                                  "negotiable": false,
                                  "location": {"city": "Tustin", "region": "CA"},
                                  "quantity": 1,
                                  "individualSellerUserId": "01UATTACKER00000000000001",
                                  "status": "ACTIVE",
                                  "moderationStatus": "APPROVED"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.title", equalTo("Updated bicycle")))
                .andExpect(jsonPath("$.condition", equalTo("LIKE_NEW")))
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.version", equalTo(1)));
    }

    @Test
    void staleDraftEditReturnsConflict() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));
    }

    @Test
    void anotherSellerCannotEditDraft() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void draftWithoutImageCannotBeSubmittedForReview() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void ownerCanSubmitDraftWithAttachedImageForReview() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("PENDING_REVIEW")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("PENDING")))
                .andExpect(jsonPath("$.version", equalTo(1)))
                .andExpect(jsonPath("$.images[0].moderationStatus", equalTo("PENDING")));

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.version", equalTo(2)));
    }

    @Test
    void sellerCanEditPendingReviewListingBackToDraftForResubmission() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.quantity", equalTo(2)))
                .andExpect(jsonPath("$.version", equalTo(2)));
    }

    @Test
    void sellerCanClosePendingReviewListing() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();

        mockMvc.perform(post("/api/v1/listings/{listingId}/close", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("CLOSED")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("PENDING")))
                .andExpect(jsonPath("$.version", equalTo(2)));
    }

    @Test
    void individualSubmitCreatesListingReviewCaseWithSellerSnapshot() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "case_type")).isEqualTo("LISTING_REVIEW");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_seller_type")).isEqualTo("INDIVIDUAL");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_individual_seller_user_id")).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_business_id")).isNull();
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "submitted_by_user_id")).isEqualTo(USER_ID);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "priority")).isEqualTo("NORMAL");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(0L);
    }

    @Test
    void businessSubmitCreatesListingReviewCaseWithBusinessSnapshotAndSubmitter() throws Exception {
        allowBusinessStoreContext(BUSINESS_ID, STORE_ID);
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        String listingId = createBusinessDraft();
        String mediaId = createConfirmedMedia(listingId, "keyboard.png", "business-token");
        attachImage(listingId, mediaId, "business-token");

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_seller_type")).isEqualTo("BUSINESS");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_individual_seller_user_id")).isNull();
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "subject_business_id")).isEqualTo(BUSINESS_ID);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "submitted_by_user_id")).isEqualTo(USER_ID);
    }

    @Test
    void staleDraftSubmitDoesNotCreateListingReviewCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isZero();
    }

    @Test
    void unauthorizedDraftSubmitDoesNotCreateListingReviewCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isZero();
    }

    @Test
    void submitReusesExistingOpenListingReviewCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);
        insertOpenModerationCase("01M00000000000000000000091", listingId);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "id")).isEqualTo("01M00000000000000000000091");
    }

    @Test
    void staleDraftSubmitReturnsConflict() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));
    }

    @Test
    void nonAdminCannotListModerationQueue() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(get("/api/v1/admin/listings/moderation")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void nonAdminCannotMakeListingModerationDecision() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Seller cannot approve own listing"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void platformAdminCanListPendingReviewListings() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/listings/moderation")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(listingId)))
                .andExpect(jsonPath("$[0].images[0].moderationStatus", equalTo("PENDING")));
    }

    @Test
    void platformAdminCanListListingModerationCases() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("admin-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", null)),
                        List.of()));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "open")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(caseId)))
                .andExpect(jsonPath("$[?(@.id == '%s')].caseStatus".formatted(caseId), hasItem("OPEN")))
                .andExpect(jsonPath("$[?(@.id == '%s')].priority".formatted(caseId), hasItem("NORMAL")))
                .andExpect(jsonPath("$[?(@.id == '%s')].listingId".formatted(caseId), hasItem(listingId)))
                .andExpect(jsonPath("$[?(@.id == '%s')].title".formatted(caseId), hasItem("Used bicycle")))
                .andExpect(jsonPath("$[?(@.id == '%s')].sellerType".formatted(caseId), hasItem("INDIVIDUAL")))
                .andExpect(jsonPath("$[?(@.id == '%s')].listingStatus".formatted(caseId), hasItem("PENDING_REVIEW")))
                .andExpect(jsonPath("$[?(@.id == '%s')].listingModerationStatus".formatted(caseId), hasItem("PENDING")))
                .andExpect(jsonPath("$[?(@.id == '%s')].submittedByUserId".formatted(caseId), hasItem(USER_ID)))
                .andExpect(jsonPath("$[?(@.id == '%s')].sellerId".formatted(caseId), hasItem(USER_ID)))
                .andExpect(jsonPath("$[?(@.id == '%s')].sellerDisplayName".formatted(caseId), hasItem("Alex Seller")))
                .andExpect(jsonPath("$[?(@.id == '%s')].version".formatted(caseId), hasItem(0)));
    }

    @Test
    void platformAdminCanSearchListingModerationCasesByTitle() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String matchingListingId = createSubmittedIndividualListing();
        String otherListingId = createSubmittedIndividualListing();
        jdbcTemplate.update("update listings set title = ? where id = ?",
                "ADM-LIST-05-search-vintage-camera",
                matchingListingId);
        jdbcTemplate.update("update listings set title = ? where id = ?",
                "ADM-LIST-05-search-road-scooter",
                otherListingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "open")
                        .queryParam("q", "vintage-camera")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].listingId", equalTo(matchingListingId)))
                .andExpect(jsonPath("$[0].title", equalTo("ADM-LIST-05-search-vintage-camera")));
    }

    @Test
    void platformAdminCanSearchListingModerationCasesByListingId() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String matchingListingId = createSubmittedIndividualListing();
        createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "open")
                        .queryParam("q", matchingListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].listingId", equalTo(matchingListingId)));
    }

    @Test
    void listingModerationCaseSearchRespectsSelectedFilter() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String unassignedListingId = createSubmittedIndividualListing();
        String assignedListingId = createSubmittedIndividualListing();
        String assignedCaseId = caseIdForListing(assignedListingId);
        jdbcTemplate.update("update listings set title = ? where id in (?, ?)",
                "ADM-LIST-05-filter-needle",
                unassignedListingId,
                assignedListingId);
        jdbcTemplate.update("""
                update moderation_cases
                set status = 'CLAIMED', assigned_admin_user_id = ?, version = 1, updated_at = now(6)
                where id = ?
                """,
                "01A00000000000000000000001",
                assignedCaseId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "unassigned")
                        .queryParam("q", "filter-needle")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].listingId", equalTo(unassignedListingId)));
    }

    @Test
    void nonAdminCannotListListingModerationCases() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void platformAdminCanClaimListingModerationCaseWithCurrentVersion() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));
        when(authServiceClient.lookupAdminIdentityLabels(
                eq("admin-token"),
                anySet(),
                anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(
                                new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", null),
                                new AuthServiceClient.UserIdentityLabel("01A00000000000000000000001", "Morgan Admin", null)),
                        List.of()));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(caseId)))
                .andExpect(jsonPath("$.caseStatus", equalTo("CLAIMED")))
                .andExpect(jsonPath("$.assignedAdminUserId", equalTo("01A00000000000000000000001")))
                .andExpect(jsonPath("$.assignedAdminDisplayName", equalTo("Morgan Admin")))
                .andExpect(jsonPath("$.sellerId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.sellerDisplayName", equalTo("Alex Seller")))
                .andExpect(jsonPath("$.version", equalTo(1)));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("CLAIMED");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "assigned_admin_user_id"))
                .isEqualTo("01A00000000000000000000001");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(1L);
    }

    @Test
    void listingModeratorCanReadClaimAndReleaseListingModerationCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001",
                        "PLATFORM_ADMIN",
                        List.of("LISTING_MODERATOR"),
                        List.of(
                                AdminPermission.LISTING_MODERATION_READ.id(),
                                AdminPermission.LISTING_MODERATION_CLAIM.id()),
                        "ACTIVE"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("moderator-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("moderator-token"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("moderator-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseStatus", equalTo("CLAIMED")));
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/release", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("moderator-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseStatus", equalTo("OPEN")))
                .andExpect(jsonPath("$.assignedAdminUserId").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "assigned_admin_user_id")).isNull();
    }

    @Test
    void platformAdminCanReadListingModerationCaseDetail() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("admin-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", null)),
                        List.of()));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.id", equalTo(caseId)))
                .andExpect(jsonPath("$.moderationCase.sellerDisplayName", equalTo("Alex Seller")))
                .andExpect(jsonPath("$.listing.id", equalTo(listingId)))
                .andExpect(jsonPath("$.listing.version", equalTo(1)))
                .andExpect(jsonPath("$.listing.images[0].moderationStatus", equalTo("PENDING")))
                .andExpect(jsonPath("$.decisions").isArray())
                .andExpect(jsonPath("$.decisions").isEmpty());
    }

    @Test
    void nonAdminCannotReadListingModerationCaseDetail() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}", "01MC0000000000000000000001")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void assignedAdminCanResolveListingModerationCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001",
                        "PLATFORM_ADMIN",
                        List.of("LISTING_MODERATOR"),
                        List.of(
                                AdminPermission.DASHBOARD_READ.id(),
                                AdminPermission.AUDIT_READ.id(),
                                AdminPermission.LISTING_MODERATION_READ.id(),
                                AdminPermission.LISTING_MODERATION_CLAIM.id(),
                                AdminPermission.LISTING_MODERATION_RESOLVE.id(),
                                AdminPermission.LISTING_EDIT.id(),
                                AdminPermission.LISTING_REMOVE.id()),
                        "ACTIVE"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("admin-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(
                                new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", null),
                                new AuthServiceClient.UserIdentityLabel("01A00000000000000000000001", "Morgan Admin", null)),
                        List.of()));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Listing looks good"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.id", equalTo(caseId)))
                .andExpect(jsonPath("$.moderationCase.caseStatus", equalTo("RESOLVED")))
                .andExpect(jsonPath("$.moderationCase.assignedAdminDisplayName", equalTo("Morgan Admin")))
                .andExpect(jsonPath("$.listing.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.listing.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.listing.images[0].moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.decisions[0].decision", equalTo("APPROVE")))
                .andExpect(jsonPath("$.decisions[0].reason", equalTo("Listing looks good")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "resolved_at")).isNotNull();
        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ? and decision = 'APPROVE'",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisions).isEqualTo(1);
        String decisionCaseId = jdbcTemplate.queryForObject(
                "select moderation_case_id from listing_moderation_decisions where listing_id = ? and decision = 'APPROVE'",
                String.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisionCaseId).isEqualTo(caseId);
        Integer resolvedEvents = jdbcTemplate.queryForObject(
                "select count(*) from moderation_case_events where moderation_case_id = ? and event_type = 'CASE_RESOLVED'",
                Integer.class,
                caseId);
        org.assertj.core.api.Assertions.assertThat(resolvedEvents).isEqualTo(1);
        Integer activeKnowledgeVersions = jdbcTemplate.queryForObject(
                "select count(*) from listing_knowledge_versions where listing_id = ? and source_version = 2 and lifecycle = 'ACTIVE'",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(activeKnowledgeVersions).isEqualTo(1);
        Integer activationOutboxEvents = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and event_type = 'listing.activated'",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(activationOutboxEvents).isEqualTo(1);
    }

    @Test
    void platformAdminCanReadOrderedListingModerationAuditTimeline() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        String adminId = "01A00000000000000000000001";
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(adminId, "PLATFORM_ADMIN"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("admin-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(
                                new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", null),
                                new AuthServiceClient.UserIdentityLabel(adminId, "Morgan Admin", null)),
                        List.of()));

        Timestamp caseCreatedAt = jdbcTemplate.queryForObject(
                "select created_at from moderation_cases where id = ?",
                Timestamp.class,
                caseId);
        jdbcTemplate.update("""
                        insert into listing_moderation_decisions (
                            id, listing_id, moderation_case_id, decision, reason, reviewer_user_id,
                            listing_version, previous_state, new_state, correlation_id, created_at
                        ) values (?, ?, null, 'APPROVE', ?, ?, 0, null, null, null, ?)
                        """,
                "01LEGACYDECISION0000000001",
                listingId,
                "Decision from a previous legacy review",
                adminId,
                Timestamp.from(caseCreatedAt.toInstant().minusSeconds(7200)));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .header("X-Correlation-Id", "claim-correlation"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/release", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .header("X-Correlation-Id", "release-correlation"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "2")
                        .header("X-Correlation-Id", "reclaim-correlation"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "3")
                        .header("X-Correlation-Id", "resolve-correlation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Audit timeline approval"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}/timeline", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].eventType", equalTo("CASE_CREATED")))
                .andExpect(jsonPath("$[1].eventType", equalTo("CASE_CLAIMED")))
                .andExpect(jsonPath("$[1].actorDisplay", equalTo("Morgan Admin")))
                .andExpect(jsonPath("$[1].correlationId", equalTo("claim-correlation")))
                .andExpect(jsonPath("$[2].eventType", equalTo("CASE_RELEASED")))
                .andExpect(jsonPath("$[2].correlationId", equalTo("release-correlation")))
                .andExpect(jsonPath("$[3].eventType", equalTo("CASE_CLAIMED")))
                .andExpect(jsonPath("$[4].eventType", equalTo("LISTING_MODERATION_RESOLVED")))
                .andExpect(jsonPath("$[4].reason", equalTo("Audit timeline approval")))
                .andExpect(jsonPath("$[4].correlationId", equalTo("resolve-correlation")))
                .andExpect(jsonPath("$[5].eventType", equalTo("CASE_RESOLVED")))
                .andExpect(jsonPath("$[5].correlationId", equalTo("resolve-correlation")));
    }

    @Test
    void nonAdminCannotReadListingModerationAuditTimeline() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}/timeline",
                        "01MC0000000000000000000001")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void auditorCanReadListingAuditButCannotClaimCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        String auditorId = "01A00000000000000000000009";
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        auditorId,
                        "PLATFORM_ADMIN",
                        List.of("AUDITOR"),
                        List.of(AdminPermission.LISTING_MODERATION_READ.id(), AdminPermission.AUDIT_READ.id()),
                        "ACTIVE"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("auditor-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("auditor-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.id", equalTo(caseId)));
        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases/{caseId}/timeline", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("auditor-token"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("auditor-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("OPEN");
    }

    @Test
    void missingResolvePermissionReturns403WithoutDecisionMutation() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        String adminId = "01A00000000000000000000008";
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        adminId,
                        "PLATFORM_ADMIN",
                        List.of("LIMITED_TEST_ADMIN"),
                        List.of(
                                AdminPermission.LISTING_MODERATION_READ.id(),
                                AdminPermission.LISTING_MODERATION_CLAIM.id()),
                        "ACTIVE"));
        when(authServiceClient.lookupAdminIdentityLabels(eq("limited-token"), anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("limited-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("limited-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"This must not be applied"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("CLAIMED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ?",
                Integer.class,
                listingId)).isZero();
    }

    @Test
    void assignedAdminCanResolveListingModerationCaseWithChangesRequested() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REQUEST_CHANGES",
                                  "reason": "Photo is unclear"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.caseStatus", equalTo("RESOLVED")))
                .andExpect(jsonPath("$.listing.status", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.listing.moderationStatus", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.listing.images[0].moderationStatus", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.decisions[0].decision", equalTo("REQUEST_CHANGES")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "resolved_at")).isNotNull();
    }

    @Test
    void unassignedListingModerationCaseCannotBeResolved() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Needs review ownership"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));
    }

    @Test
    void anotherAdminCannotResolveClaimedListingModerationCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000002", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Wrong admin should not decide"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("CLAIMED");
        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ?",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisions).isZero();
    }

    @Test
    void staleListingModerationCaseResolveReturnsConflict() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Stale case version"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));

        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ?",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisions).isZero();
    }

    @Test
    void resolvedListingModerationCaseCannotBeResolvedAgain() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "First decision"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Second decision should not apply"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));

        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ?",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisions).isEqualTo(1);
    }

    @Test
    void platformAdminCanReadActiveListingDetail() throws Exception {
        String listingId = "01L0000000000000000000A401";
        Instant publishedAt = Instant.parse("2026-06-17T12:00:00Z");
        insertApprovedPublicListing(listingId, publishedAt);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.version", equalTo(0)));
    }

    @Test
    void platformAdminCanEditActiveListing() throws Exception {
        String listingId = "01L0000000000000000000A402";
        Instant publishedAt = Instant.parse("2026-06-17T12:00:00Z");
        insertApprovedPublicListing(listingId, publishedAt);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(patch("/api/v1/admin/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": "%s",
                                  "title": "Admin edited bicycle",
                                  "description": "Updated because the live listing had unsafe wording.",
                                  "condition": "GOOD",
                                  "conditionNotes": "Verified from listing photos",
                                  "price": {"amount": 12.50, "currency": "USD"},
                                  "negotiable": false,
                                  "location": {"city": "Santa Ana", "region": "CA"},
                                  "sku": null,
                                  "quantity": 1,
                                  "reason": "Removed unsafe wording from active listing"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.title", equalTo("Admin edited bicycle")))
                .andExpect(jsonPath("$.description", equalTo("Updated because the live listing had unsafe wording.")))
                .andExpect(jsonPath("$.priceAmount", equalTo(12.50)))
                .andExpect(jsonPath("$.publicCity", equalTo("Santa Ana")))
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.version", equalTo(1)));

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", equalTo("Admin edited bicycle")));
        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "ADMIN_EDIT")).isEqualTo(1);
    }

    @Test
    void staleActiveListingAdminEditReturnsConflict() throws Exception {
        String listingId = "01L0000000000000000000A403";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"));
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(patch("/api/v1/admin/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": "%s",
                                  "title": "Stale edit",
                                  "description": "This stale edit should not apply.",
                                  "condition": "GOOD",
                                  "price": {"amount": 10.00, "currency": "USD"},
                                  "negotiable": true,
                                  "location": {"city": "Irvine", "region": "CA"},
                                  "quantity": 1,
                                  "reason": "Stale edit"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));

        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "ADMIN_EDIT")).isZero();
    }

    @Test
    void nonAdminCannotEditActiveListing() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(patch("/api/v1/admin/listings/{listingId}", "01L0000000000000000000A404")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": "%s",
                                  "title": "Denied edit",
                                  "description": "Denied edit",
                                  "condition": "GOOD",
                                  "price": {"amount": 10.00, "currency": "USD"},
                                  "negotiable": true,
                                  "location": {"city": "Irvine", "region": "CA"},
                                  "quantity": 1,
                                  "reason": "Denied edit"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void platformAdminCanRemoveActiveListingFromPublicMarketplace() throws Exception {
        String listingId = "01L0000000000000000000A405";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"));
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/remove", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Listing violates marketplace policy"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.status", equalTo("REMOVED_BY_ADMIN")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.version", equalTo(1)));

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "ADMIN_REMOVE")).isEqualTo(1);
    }

    @Test
    void platformAdminCanRemoveSelfPublishedBusinessItemAndSellerSeesReason() throws Exception {
        String listingId = "01L0000000000000000000A406";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"), "BUSINESS");
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001",
                        "PLATFORM_ADMIN",
                        List.of("LISTING_MODERATOR"),
                        List.of(
                                AdminPermission.DASHBOARD_READ.id(),
                                AdminPermission.AUDIT_READ.id(),
                                AdminPermission.LISTING_MODERATION_READ.id(),
                                AdminPermission.LISTING_MODERATION_CLAIM.id(),
                                AdminPermission.LISTING_MODERATION_RESOLVE.id(),
                                AdminPermission.LISTING_EDIT.id(),
                                AdminPermission.LISTING_REMOVE.id()),
                        "ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/remove", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Business item violates marketplace policy"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("REMOVED_BY_ADMIN")))
                .andExpect(jsonPath("$.moderationAction", equalTo("ADMIN_REMOVE")))
                .andExpect(jsonPath("$.moderationReason", equalTo("Business item violates marketplace policy")));

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/businesses/{businessId}/store/items/{listingId}", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("REMOVED_BY_ADMIN")))
                .andExpect(jsonPath("$.moderationAction", equalTo("ADMIN_REMOVE")))
                .andExpect(jsonPath("$.moderationReason", equalTo("Business item violates marketplace policy")));

        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "ADMIN_REMOVE")).isEqualTo(1);
    }

    @Test
    void pendingListingCannotBeRemovedByAdminActiveAction() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/remove", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Pending listing is not active"
                                }
                """))
        .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void staleListingModerationCaseClaimReturnsConflict() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));
    }

    @Test
    void platformAdminCanReleaseOwnListingModerationCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/release", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseStatus", equalTo("OPEN")))
                .andExpect(jsonPath("$.assignedAdminUserId").doesNotExist())
                .andExpect(jsonPath("$.version", equalTo(2)));
    }

    @Test
    void anotherAdminCannotReleaseClaimedListingModerationCase() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000002", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/release", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-admin-token")))
                        .header("If-Match", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("MODERATION_CASE_VERSION_CONFLICT")));

        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "assigned_admin_user_id"))
                .isEqualTo("01A00000000000000000000001");
    }

    @Test
    void listingModerationCaseFiltersReturnExpectedCases() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String unassignedListingId = createSubmittedIndividualListing();
        String assignedListingId = createSubmittedIndividualListing();
        String resolvedListingId = createSubmittedIndividualListing();
        String assignedCaseId = caseIdForListing(assignedListingId);
        String resolvedCaseId = caseIdForListing(resolvedListingId);
        jdbcTemplate.update("""
                update moderation_cases
                set status = 'CLAIMED', assigned_admin_user_id = ?, version = 1, updated_at = now(6)
                where id = ?
                """,
                "01A00000000000000000000001",
                assignedCaseId);
        jdbcTemplate.update("""
                update moderation_cases
                set status = 'RESOLVED', resolved_at = now(6), version = 1, updated_at = now(6)
                where id = ?
                """,
                resolvedCaseId);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "unassigned")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].listingId", hasItem(unassignedListingId)));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "assigned_to_me")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(assignedCaseId)));

        mockMvc.perform(get("/api/v1/admin/moderation/listing-cases")
                        .queryParam("filter", "resolved")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(resolvedCaseId)));
    }

    @Test
    void nonAdminCannotReadListingModerationSummary() throws Exception {
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenThrow(new ListingAuthorizationException("Platform admin access is required."));

        mockMvc.perform(get("/api/v1/admin/listings/moderation/summary")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void platformAdminCanReadListingModerationSummary() throws Exception {
        Integer before = jdbcTemplate.queryForObject("""
                select count(*)
                from listings
                where status = 'PENDING_REVIEW'
                  and moderation_status = 'PENDING'
                """, Integer.class);
        Long assignedBefore = jdbcTemplate.queryForObject("""
                select count(*)
                from moderation_cases
                where case_type = 'LISTING_REVIEW'
                  and status = 'CLAIMED'
                  and assigned_admin_user_id = '01A00000000000000000000001'
                """, Long.class);
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/v1/admin/listings/moderation/summary")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingListingReviews", equalTo(before + 1)))
                .andExpect(jsonPath("$.assignedToMeListingReviews", equalTo(assignedBefore.intValue())));
    }

    @Test
    void platformAdminCanApprovePendingListing() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Listing looks good"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.decision", equalTo("APPROVE")))
                .andExpect(jsonPath("$.reviewerUserId", equalTo("01A00000000000000000000001")))
                .andExpect(jsonPath("$.listingVersion", equalTo(2)));

        mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.images[0].moderationStatus", equalTo("APPROVED")));

        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ? and decision = 'APPROVE'",
                Integer.class,
                listingId);
        org.assertj.core.api.Assertions.assertThat(decisions).isEqualTo(1);
    }

    @Test
    void platformAdminCanRequestListingChanges() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000002", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REQUEST_CHANGES",
                                  "reason": "Add clearer photos"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", equalTo("REQUEST_CHANGES")));

        mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.moderationAction", equalTo("REQUEST_CHANGES")))
                .andExpect(jsonPath("$.moderationReason", equalTo("Add clearer photos")))
                .andExpect(jsonPath("$.moderationActionAt").isNotEmpty());
    }

    @Test
    void changesRequestedResubmissionReopensSameResolvedCaseAndPreservesDecisionHistory() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        String caseId = caseIdForListing(listingId);
        String adminUserId = "01A00000000000000000000002";
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        adminUserId, "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REQUEST_CHANGES",
                                  "reason": "Add clearer photos"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.id", equalTo(caseId)))
                .andExpect(jsonPath("$.moderationCase.caseStatus", equalTo("RESOLVED")))
                .andExpect(jsonPath("$.listing.status", equalTo("CHANGES_REQUESTED")))
                .andExpect(jsonPath("$.listing.version", equalTo(2)));

        String firstDecisionId = jdbcTemplate.queryForObject(
                "select id from listing_moderation_decisions where listing_id = ? and decision = 'REQUEST_CHANGES'",
                String.class,
                listingId);
        Object firstDecisionCreatedAt = jdbcTemplate.queryForObject(
                "select created_at from listing_moderation_decisions where id = ?",
                Object.class,
                firstDecisionId);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "assigned_admin_user_id"))
                .isEqualTo(adminUserId);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "resolved_at")).isNotNull();
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(2L);

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));
        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.quantity", equalTo(2)))
                .andExpect(jsonPath("$.version", equalTo(3)))
                .andExpect(jsonPath("$.moderationAction", equalTo("REQUEST_CHANGES")))
                .andExpect(jsonPath("$.moderationReason", equalTo("Add clearer photos")));

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("RESOLVED");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(2L);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("PENDING_REVIEW")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("PENDING")))
                .andExpect(jsonPath("$.version", equalTo(4)));

        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "id")).isEqualTo(caseId);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "status")).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "assigned_admin_user_id")).isNull();
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "resolved_at")).isNull();
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(3L);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "submitted_by_user_id")).isEqualTo(USER_ID);

        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(caseValue(listingId, "version")).isEqualTo(3L);

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/claim", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseStatus", equalTo("CLAIMED")))
                .andExpect(jsonPath("$.version", equalTo(4)));

        mockMvc.perform(post("/api/v1/admin/moderation/listing-cases/{caseId}/resolve", caseId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Seller supplied the requested changes"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.id", equalTo(caseId)))
                .andExpect(jsonPath("$.moderationCase.caseStatus", equalTo("RESOLVED")))
                .andExpect(jsonPath("$.moderationCase.version", equalTo(5)))
                .andExpect(jsonPath("$.listing.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.listing.moderationStatus", equalTo("APPROVED")))
                .andExpect(jsonPath("$.listing.version", equalTo(5)));

        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "REQUEST_CHANGES")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(decisionCount(listingId, "APPROVE")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "select reason from listing_moderation_decisions where id = ?",
                        String.class,
                        firstDecisionId))
                .isEqualTo("Add clearer photos");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                        "select created_at from listing_moderation_decisions where id = ?",
                        Object.class,
                        firstDecisionId))
                .isEqualTo(firstDecisionCreatedAt);
        org.assertj.core.api.Assertions.assertThat(caseCountForListing(listingId)).isEqualTo(1);
    }

    @Test
    void ownerCanReplaceChangesRequestedMediaWhileAnotherSellerCannot() throws Exception {
        String listingId = createChangesRequestedIndividualListing();
        String mediaId = jdbcTemplate.queryForObject(
                "select media_object_id from listing_images where listing_id = ?",
                String.class,
                listingId);

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));
        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s", "altText": "Clearer bike photo"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s", "altText": "Clearer bike photo"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].altText", equalTo("Clearer bike photo")));

        mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.version", equalTo(3)));
    }

    @Test
    void staleListingModerationDecisionReturnsConflict() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "reason": "Stale decision"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_VERSION_CONFLICT")));
    }

    @Test
    void draftListingCannotReceiveModerationDecision() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Too early"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void guestCanReadApprovedPublicListingDetailWithoutPrivateFields() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.categoryId", equalTo(CATEGORY_ID)))
                .andExpect(jsonPath("$.categorySlug", equalTo("general")))
                .andExpect(jsonPath("$.categoryName", equalTo("General")))
                .andExpect(jsonPath("$.title", equalTo("Used bicycle")))
                .andExpect(jsonPath("$.priceAmount", equalTo(250.00)))
                .andExpect(jsonPath("$.currency", equalTo("USD")))
                .andExpect(jsonPath("$.publicCity", equalTo("Irvine")))
                .andExpect(jsonPath("$.publicRegion", equalTo("CA")))
                .andExpect(jsonPath("$.publishedAt", notNullValue()))
                .andExpect(jsonPath("$.transactionNotice", notNullValue()))
                .andExpect(jsonPath("$.images[0].originalFileName", equalTo("bike.png")))
                .andExpect(jsonPath("$.images[0].uploadUrl", notNullValue()))
                .andExpect(jsonPath("$.images[0].url", notNullValue()))
                .andExpect(jsonPath("$.individualSellerUserId").doesNotExist())
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.moderationStatus").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.images[0].mediaObjectId").doesNotExist())
                .andExpect(jsonPath("$.images[0].objectBucket").doesNotExist())
                .andExpect(jsonPath("$.images[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.images[0].moderationStatus").doesNotExist());
    }

    @Test
    void chatServiceCanReadEligibleIndividualListingWithoutPrivateFields() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(get("/api/v1/internal/chat/listings/{listingId}/conversation-eligibility", listingId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.eligible", equalTo(true)))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.sellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.quantity", equalTo(1)))
                .andExpect(jsonPath("$.title", equalTo("Used bicycle")))
                .andExpect(jsonPath("$.publicCity", equalTo("Irvine")))
                .andExpect(jsonPath("$.publicRegion", equalTo("CA")))
                .andExpect(jsonPath("$.thumbnailUrl", notNullValue()))
                .andExpect(jsonPath("$.transactionNotice", notNullValue()))
                .andExpect(jsonPath("$.sellerDisplayName").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.moderationStatus").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());
    }

    @Test
    void chatEligibilityRejectsBusinessListing() throws Exception {
        String listingId = "01L00000000000000000000991";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"), "BUSINESS");

        mockMvc.perform(get("/api/v1/internal/chat/listings/{listingId}/conversation-eligibility", listingId)
                        .with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void chatEligibilityHidesInactiveListing() throws Exception {
        String listingId = "01L00000000000000000000992";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"));
        jdbcTemplate.update("update listings set status = 'CLOSED' where id = ?", listingId);

        mockMvc.perform(get("/api/v1/internal/chat/listings/{listingId}/conversation-eligibility", listingId)
                        .with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void chatEligibilityHidesUnapprovedListing() throws Exception {
        String listingId = "01L00000000000000000000993";
        insertApprovedPublicListing(listingId, Instant.parse("2026-06-17T12:00:00Z"));
        jdbcTemplate.update("update listings set moderation_status = 'PENDING' where id = ?", listingId);

        mockMvc.perform(get("/api/v1/internal/chat/listings/{listingId}/conversation-eligibility", listingId)
                        .with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void chatTradeCompletionClosesListingForPublicSearchButKeepsSellerHistory() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(post("/api/v1/internal/chat/listings/{listingId}/complete-trade", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": "01C00000000000000000000001",
                                  "sellerUserId": "%s",
                                  "buyerUserId": "01U00000000000000000000022",
                                  "quantitySold": 1
                                }
                                """.formatted(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(listingId)))
                .andExpect(jsonPath("$.status", equalTo("CLOSED")));

        mockMvc.perform(get("/api/v1/public/marketplace/listings/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(listingId))));

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        mockMvc.perform(get("/api/v1/users/me/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(listingId), hasItem("CLOSED")));
    }

    @Test
    void chatTradeCompletionFailsBeforeListingMutationWhenIndividualSellerIsRestricted() throws Exception {
        String listingId = createApprovedIndividualListing();
        doThrow(new UserCapabilityRestrictedException(
                "USER_SELLING", "SUSPEND", null, "ENF-SELLER-01"))
                .when(authServiceClient).requireUserCapability(USER_ID, "USER_SELLING");

        mockMvc.perform(post("/api/v1/internal/chat/listings/{listingId}/complete-trade", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": "01C00000000000000000000001",
                                  "sellerUserId": "%s",
                                  "buyerUserId": "01U00000000000000000000022",
                                  "quantitySold": 1
                                }
                                """.formatted(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("USER_CAPABILITY_RESTRICTED")))
                .andExpect(jsonPath("$.error.fieldErrors[?(@.field == 'scope')].code",
                        hasItem("USER_SELLING")));

        assertThat(jdbcTemplate.queryForObject(
                "select status from listings where id = ?", String.class, listingId)).isEqualTo("ACTIVE");
    }

    @Test
    void sellerCanCloseActiveListingAndHideItFromPublicDetail() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/{listingId}/close", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("CLOSED")))
                .andExpect(jsonPath("$.version", equalTo(3)));

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void sellerCanEditActiveListingBackToDraftAndHideItFromPublicDetail() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.quantity", equalTo(2)))
                .andExpect(jsonPath("$.version", equalTo(3)));

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void sellerCanEditClosedListingBackToDraftForResubmission() throws Exception {
        String listingId = createApprovedIndividualListing();

        mockMvc.perform(post("/api/v1/listings/{listingId}/close", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("CLOSED")))
                .andExpect(jsonPath("$.version", equalTo(3)));

        mockMvc.perform(patch("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("DRAFT")))
                .andExpect(jsonPath("$.moderationStatus", equalTo("NOT_SUBMITTED")))
                .andExpect(jsonPath("$.quantity", equalTo(2)))
                .andExpect(jsonPath("$.version", equalTo(4)));
    }

    @Test
    void guestCanReadApprovedPublicListingImageThroughAppEndpoint() throws Exception {
        String listingId = createApprovedIndividualListing();
        String response = mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = objectMapper.readTree(response).get("images").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/public/listing-media/{imageId}", imageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void publicListingMediaHidesPendingReviewImage() throws Exception {
        String listingId = createSubmittedListingForAdminDecision();
        String response = mockMvc.perform(get("/api/v1/listings/{listingId}", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = objectMapper.readTree(response).get("images").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/public/listing-media/{imageId}", imageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_MEDIA_NOT_FOUND")));
    }

    @Test
    void publicListingMediaReturnsNotFoundWhenStorageObjectIsMissing() throws Exception {
        String listingId = createApprovedIndividualListing();
        String response = mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = objectMapper.readTree(response).get("images").get(0).get("id").asText();

        doThrow(new StorageObjectNotFoundException("Stored media object was not found."))
                .when(listingMediaStorage)
                .readObject(anyString());

        mockMvc.perform(get("/api/v1/public/listing-media/{imageId}", imageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_MEDIA_NOT_FOUND")));
    }

    @Test
    void publicListingMediaReturnsBadGatewayWhenStorageReadIsDenied() throws Exception {
        String listingId = createApprovedIndividualListing();
        String response = mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String imageId = objectMapper.readTree(response).get("images").get(0).get("id").asText();

        doThrow(new StorageObjectAccessDeniedException("Stored media object could not be read."))
                .when(listingMediaStorage)
                .readObject(anyString());

        mockMvc.perform(get("/api/v1/public/listing-media/{imageId}", imageId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_MEDIA_STORAGE_ACCESS_DENIED")));
    }

    @Test
    void sellerCanPreviewOwnedDraftImageThroughAppEndpoint() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");

        mockMvc.perform(get("/api/v1/listings/{listingId}/media/{mediaId}/content", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void platformAdminCanPreviewUploadedListingImageForModeration() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");

        mockMvc.perform(get("/api/v1/admin/listings/{listingId}/media/{mediaId}/content", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void sellerCannotPreviewAnotherSellersDraftImage() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");

        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        "01U00000000000000000000099", "Irvine", "CA", "ACTIVE"));

        mockMvc.perform(get("/api/v1/listings/{listingId}/media/{mediaId}/content", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_FORBIDDEN")));
    }

    @Test
    void guestCanBrowseApprovedPublicListingsOnly() throws Exception {
        String approvedListingId = createApprovedIndividualListing();
        String pendingListingId = createSubmittedListingForAdminDecision();
        String rejectedListingId = createRejectedIndividualListing();
        when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                        List.of(new AuthServiceClient.UserIdentityLabel(USER_ID, "Alex Seller", "https://example.com/alex.png")),
                        List.of()));

        mockMvc.perform(get("/api/v1/public/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(approvedListingId)))
                .andExpect(jsonPath("$[?(@.id == '%s')].sellerDisplayName".formatted(approvedListingId), hasItem("Alex Seller")))
                .andExpect(jsonPath("$[?(@.id == '%s')].sellerAvatarUrl".formatted(approvedListingId), hasItem("https://example.com/alex.png")))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(pendingListingId))))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(rejectedListingId))))
                .andExpect(jsonPath("$[0].individualSellerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].sellerId").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].moderationStatus").doesNotExist());
    }

    @Test
    void authenticatedUserRecordsOneVisitPerListing() throws Exception {
        String listingId = createApprovedIndividualListing();
        when(authServiceClient.requireCurrentUser("buyer-token"))
                .thenReturn(new AuthServiceClient.CurrentUser("01U00000000000000000000002", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/visit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.visitCount", equalTo(1)))
                .andExpect(jsonPath("$.likeCount", equalTo(0)))
                .andExpect(jsonPath("$.visitedByMe", equalTo(true)))
                .andExpect(jsonPath("$.likedByMe", equalTo(false)));

        mockMvc.perform(post("/api/v1/listings/{listingId}/visit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitCount", equalTo(1)))
                .andExpect(jsonPath("$.visitedByMe", equalTo(true)));

        when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                .thenReturn(new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()));
        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitCount", equalTo(1)))
                .andExpect(jsonPath("$.likeCount", equalTo(0)));
    }

    @Test
    void authenticatedUserCanLikeUnlikeAndRelikeOnce() throws Exception {
        String listingId = createApprovedIndividualListing();
        when(authServiceClient.requireCurrentUser("buyer-token"))
                .thenReturn(new AuthServiceClient.CurrentUser("01U00000000000000000000002", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(1)))
                .andExpect(jsonPath("$.likedByMe", equalTo(true)));

        mockMvc.perform(post("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(1)))
                .andExpect(jsonPath("$.likedByMe", equalTo(true)));

        mockMvc.perform(delete("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(0)))
                .andExpect(jsonPath("$.likedByMe", equalTo(false)));

        mockMvc.perform(delete("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(0)))
                .andExpect(jsonPath("$.likedByMe", equalTo(false)));

        mockMvc.perform(post("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(1)))
                .andExpect(jsonPath("$.likedByMe", equalTo(true)));
    }

    @Test
    void currentUserCanListActiveLikedPublicListings() throws Exception {
        String likedListingId = createApprovedIndividualListing();
        String unlikedListingId = createApprovedIndividualListing();
        when(authServiceClient.requireCurrentUser("buyer-token"))
                .thenReturn(new AuthServiceClient.CurrentUser("01U00000000000000000000002", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/like", likedListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/listings/{listingId}/like", unlikedListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/listings/{listingId}/like", unlikedListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/liked-listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(likedListingId)))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(unlikedListingId))))
                .andExpect(jsonPath("$[?(@.id == '%s')].likeCount".formatted(likedListingId), hasItem(1)))
                .andExpect(jsonPath("$[0].sellerId").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void ownerSelfEngagementCountsOnceLikeAnyAccount() throws Exception {
        String listingId = createApprovedIndividualListing();
        when(authServiceClient.requireCurrentUser("individual-token"))
                .thenReturn(new AuthServiceClient.CurrentUser(USER_ID, "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/visit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitCount", equalTo(1)))
                .andExpect(jsonPath("$.visitedByMe", equalTo(true)));

        mockMvc.perform(post("/api/v1/listings/{listingId}/like", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", equalTo(1)))
                .andExpect(jsonPath("$.likedByMe", equalTo(true)));
    }

    @Test
    void hiddenListingRejectsEngagementCommands() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String draftListingId = createIndividualDraft();
        when(authServiceClient.requireCurrentUser("buyer-token"))
                .thenReturn(new AuthServiceClient.CurrentUser("01U00000000000000000000002", "ACTIVE"));

        mockMvc.perform(post("/api/v1/listings/{listingId}/visit", draftListingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void individualMarketplaceSearchReturnsOnlyApprovedIndividualListings() throws Exception {
        String individualListingId = "01S00000000000000000000001";
        String businessListingId = "01S00000000000000000000002";
        try {
            insertApprovedPublicListing(individualListingId, Instant.parse("2099-06-17T12:00:00Z"), "INDIVIDUAL");
            insertApprovedPublicListing(businessListingId, Instant.parse("2099-06-17T12:00:01Z"), "BUSINESS");

            mockMvc.perform(get("/api/v1/public/marketplace/listings/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(individualListingId)))
                    .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(businessListingId))))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].sellerType".formatted(individualListingId), hasItem("INDIVIDUAL")))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(false)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", individualListingId, businessListingId);
        }
    }

    @Test
    void individualMarketplaceSearchAppliesKeywordCategoryConditionPriceAndLocationFilters() throws Exception {
        String matchingListingId = "01S00000000000000000000005";
        String nonMatchingListingId = "01S00000000000000000000006";
        try {
            insertApprovedPublicListing(
                    matchingListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "INDIVIDUAL",
                    "Anime bicycle",
                    "Cute figure basket included",
                    "GOOD",
                    new BigDecimal("50.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    nonMatchingListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "INDIVIDUAL",
                    "Laptop stand",
                    "Desk accessory",
                    "LIKE_NEW",
                    new BigDecimal("90.00"),
                    "Tustin",
                    "Orange County",
                    "01K00000000000000000000002");

            mockMvc.perform(get("/api/v1/public/marketplace/listings/search")
                            .param("q", "bicycle")
                            .param("categoryId", CATEGORY_ID)
                            .param("condition", "GOOD")
                            .param("minPrice", "40.00")
                            .param("maxPrice", "60.00")
                            .param("city", "irvine")
                            .param("county", "orange county"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(matchingListingId)))
                    .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(nonMatchingListingId))));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", matchingListingId, nonMatchingListingId);
        }
    }

    @Test
    void individualMarketplaceSearchSortsByPrice() throws Exception {
        String lowPriceListingId = "01S00000000000000000000007";
        String highPriceListingId = "01S00000000000000000000008";
        try {
            insertApprovedPublicListing(
                    highPriceListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "INDIVIDUAL",
                    "Collector figure",
                    "Individual price sort fixture",
                    "GOOD",
                    new BigDecimal("80.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    lowPriceListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "INDIVIDUAL",
                    "Small plush",
                    "Individual price sort fixture",
                    "GOOD",
                    new BigDecimal("20.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);

            mockMvc.perform(get("/api/v1/public/marketplace/listings/search")
                            .param("q", "individual price sort fixture")
                            .param("sort", "price_asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id", equalTo(lowPriceListingId)))
                    .andExpect(jsonPath("$.data[1].id", equalTo(highPriceListingId)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", lowPriceListingId, highPriceListingId);
        }
    }

    @Test
    void individualMarketplaceSearchUsesCursorPaginationWithoutRepeatingRows() throws Exception {
        String newestListingId = "01S00000000000000000000013";
        String olderListingId = "01S00000000000000000000014";
        try {
            insertApprovedPublicListing(
                    olderListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "INDIVIDUAL",
                    "Cursor pagination fixture older",
                    "Individual cursor fixture",
                    "GOOD",
                    new BigDecimal("20.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    newestListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "INDIVIDUAL",
                    "Cursor pagination fixture newest",
                    "Individual cursor fixture",
                    "GOOD",
                    new BigDecimal("20.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);

            String firstPage = mockMvc.perform(get("/api/v1/public/marketplace/listings/search")
                            .param("q", "individual cursor fixture")
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", equalTo(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(newestListingId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(true)))
                    .andExpect(jsonPath("$.page.nextCursor", notNullValue()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String cursor = objectMapper.readTree(firstPage).get("page").get("nextCursor").asText();

            mockMvc.perform(get("/api/v1/public/marketplace/listings/search")
                            .param("q", "individual cursor fixture")
                            .param("limit", "1")
                            .param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", equalTo(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(olderListingId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(false)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", newestListingId, olderListingId);
        }
    }

    @Test
    void individualMarketplaceSearchRejectsInvalidSort() throws Exception {
        mockMvc.perform(get("/api/v1/public/marketplace/listings/search")
                        .param("sort", "popular"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void individualMarketplaceSearchFallsBackWhenSellerLabelsAreUnavailable() throws Exception {
        String listingId = "01S00000000000000000000024";
        try {
            insertApprovedPublicListing(listingId, Instant.parse("2099-06-17T12:00:00Z"), "INDIVIDUAL");
            when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                    .thenThrow(new RuntimeException("auth-service unavailable"));

            mockMvc.perform(get("/api/v1/public/marketplace/listings/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(listingId)))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].sellerDisplayName".formatted(listingId),
                            hasItem("Marketplace seller")));
        } finally {
            jdbcTemplate.update("delete from listings where id = ?", listingId);
        }
    }

    @Test
    void closedListingFromStaleSearchCandidateFailsAuthoritativeMysqlVisibilityCheck() throws Exception {
        String listingId = "01S00000000000000000000028";
        try {
            insertApprovedPublicListing(listingId, Instant.parse("2099-06-17T12:00:00Z"), "INDIVIDUAL");
            jdbcTemplate.update(
                    "update listings set status = 'CLOSED', updated_at = ? where id = ?",
                    Timestamp.from(Instant.parse("2099-06-17T12:01:00Z")),
                    listingId);

            org.assertj.core.api.Assertions.assertThat(
                            listingDraftRepository.findPublicListingsByIds(List.of(listingId)))
                    .isEmpty();
            mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                    .andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update("delete from listings where id = ?", listingId);
        }
    }

    @Test
    void businessStoreListingsSearchReturnsOnlySelfPublishedBusinessListings() throws Exception {
        String individualListingId = "01S00000000000000000000003";
        String businessListingId = "01S00000000000000000000004";
        try {
            insertApprovedPublicListing(individualListingId, Instant.parse("2099-06-17T12:00:00Z"), "INDIVIDUAL");
            insertApprovedPublicListing(businessListingId, Instant.parse("2099-06-17T12:00:01Z"), "BUSINESS");

            mockMvc.perform(get("/api/v1/public/stores/listings/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(businessListingId)))
                    .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(individualListingId))))
                    .andExpect(jsonPath("$.data[?(@.id == '%s')].sellerType".formatted(businessListingId), hasItem("BUSINESS")))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(false)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", individualListingId, businessListingId);
        }
    }

    @Test
    void businessStoreListingsSearchAppliesKeywordCategoryConditionPriceAndLocationFilters() throws Exception {
        String matchingListingId = "01S00000000000000000000009";
        String nonMatchingListingId = "01S00000000000000000000010";
        try {
            insertApprovedPublicListing(
                    matchingListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "BUSINESS",
                    "Store plush",
                    "Business storefront fixture",
                    "NEW",
                    new BigDecimal("30.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    nonMatchingListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "BUSINESS",
                    "Store laptop stand",
                    "Business storefront fixture",
                    "LIKE_NEW",
                    new BigDecimal("90.00"),
                    "Tustin",
                    "Orange County",
                    "01K00000000000000000000002");

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "plush")
                            .param("categoryId", CATEGORY_ID)
                            .param("condition", "NEW")
                            .param("minPrice", "20.00")
                            .param("maxPrice", "40.00")
                            .param("city", "irvine")
                            .param("county", "orange county"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(matchingListingId)))
                    .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(nonMatchingListingId))));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", matchingListingId, nonMatchingListingId);
        }
    }

    @Test
    void businessStoreLocationFilterUsesTheLocationDisplayedFromPublicStoreMetadata() throws Exception {
        String listingId = "01S00000000000000000000029";
        try {
            insertApprovedPublicListing(
                    listingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "BUSINESS",
                    "Store location fixture",
                    "Business listing without duplicated location columns",
                    "NEW",
                    new BigDecimal("30.00"),
                    null,
                    null,
                    CATEGORY_ID);
            when(authServiceClient.lookupPublicSellerLabels(anySet(), anySet()))
                    .thenReturn(new AuthServiceClient.AdminIdentityLabels(
                            List.of(),
                            List.of(new AuthServiceClient.BusinessIdentityLabel(
                                    BUSINESS_ID,
                                    "Acme Trading LLC",
                                    STORE_ID,
                                    "acme-trading-store",
                                    "Acme Trading Store",
                                    "Irvine",
                                    "CA",
                                    true))));

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("city", "irvine")
                            .param("county", "ca"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(listingId)))
                    .andExpect(jsonPath(
                            "$.data[?(@.id == '%s')].publicCity".formatted(listingId),
                            hasItem("Irvine")))
                    .andExpect(jsonPath(
                            "$.data[?(@.id == '%s')].publicRegion".formatted(listingId),
                            hasItem("CA")));

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("city", "Tustin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id").value(
                            org.hamcrest.Matchers.not(hasItem(listingId))));
        } finally {
            jdbcTemplate.update("delete from listings where id = ?", listingId);
        }
    }

    @Test
    void businessStoreListingsSearchMatchesSkuAndStoreName() throws Exception {
        String skuListingId = "01S00000000000000000000021";
        String storeNameListingId = "01S00000000000000000000022";
        try {
            insertApprovedPublicListing(
                    skuListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "BUSINESS",
                    "Desk organizer",
                    "Business SKU fixture",
                    "NEW",
                    new BigDecimal("30.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            jdbcTemplate.update("update listings set sku = ? where id = ?", "MOCHI-SKU-7788", skuListingId);
            insertApprovedPublicListing(
                    storeNameListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "BUSINESS",
                    "Shelf display stand",
                    "Business store-name fixture",
                    "NEW",
                    new BigDecimal("40.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            when(authServiceClient.searchPublicBusinessStores(eq("Mochi House"), anySet(), anySet()))
                    .thenReturn(List.of(new AuthServiceClient.PublicBusinessStoreSearchResult(
                            BUSINESS_ID,
                            STORE_ID,
                            "Mochi House",
                            "Acme Trading LLC")));

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "MOCHI-SKU-7788"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(skuListingId)));

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "Mochi House"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(storeNameListingId)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", skuListingId, storeNameListingId);
        }
    }

    @Test
    void businessStoreListingsSearchHidesItemsWhenBusinessStoreIsNotPublicActive() throws Exception {
        String hiddenListingId = "01S00000000000000000000023";
        try {
            insertApprovedPublicListing(hiddenListingId, Instant.parse("2099-06-17T12:00:00Z"), "BUSINESS");
            when(authServiceClient.searchPublicBusinessStores(
                    org.mockito.ArgumentMatchers.isNull(),
                    anySet(),
                    anySet()))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/public/stores/listings/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(hiddenListingId))));
        } finally {
            jdbcTemplate.update("delete from listings where id = ?", hiddenListingId);
        }
    }

    @Test
    void businessStoreListingsSearchFailsClosedWhenBusinessVisibilityLookupIsUnavailable() throws Exception {
        String hiddenListingId = "01S00000000000000000000025";
        try {
            insertApprovedPublicListing(hiddenListingId, Instant.parse("2099-06-17T12:00:00Z"), "BUSINESS");
            when(authServiceClient.searchPublicBusinessStores(
                    org.mockito.ArgumentMatchers.isNull(),
                    anySet(),
                    anySet()))
                    .thenThrow(new RuntimeException("auth-service unavailable"));

            mockMvc.perform(get("/api/v1/public/stores/listings/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        } finally {
            jdbcTemplate.update("delete from listings where id = ?", hiddenListingId);
        }
    }

    @Test
    void businessStoreListingsSearchSortsByPrice() throws Exception {
        String lowPriceListingId = "01S00000000000000000000011";
        String highPriceListingId = "01S00000000000000000000012";
        try {
            insertApprovedPublicListing(
                    highPriceListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "BUSINESS",
                    "Store premium figure",
                    "Business price sort fixture",
                    "NEW",
                    new BigDecimal("80.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    lowPriceListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "BUSINESS",
                    "Store small plush",
                    "Business price sort fixture",
                    "NEW",
                    new BigDecimal("20.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "business price sort fixture")
                            .param("sort", "price_asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id", equalTo(lowPriceListingId)))
                    .andExpect(jsonPath("$.data[1].id", equalTo(highPriceListingId)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", lowPriceListingId, highPriceListingId);
        }
    }

    @Test
    void businessStoreListingsSearchUsesPriceCursorPaginationWithoutRepeatingRows() throws Exception {
        String lowPriceListingId = "01S00000000000000000000015";
        String highPriceListingId = "01S00000000000000000000016";
        try {
            insertApprovedPublicListing(
                    highPriceListingId,
                    Instant.parse("2099-06-17T12:00:01Z"),
                    "BUSINESS",
                    "Store cursor high",
                    "Business cursor fixture",
                    "NEW",
                    new BigDecimal("80.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);
            insertApprovedPublicListing(
                    lowPriceListingId,
                    Instant.parse("2099-06-17T12:00:00Z"),
                    "BUSINESS",
                    "Store cursor low",
                    "Business cursor fixture",
                    "NEW",
                    new BigDecimal("20.00"),
                    "Irvine",
                    "Orange County",
                    CATEGORY_ID);

            String firstPage = mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "business cursor fixture")
                            .param("sort", "price_asc")
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", equalTo(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(lowPriceListingId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(true)))
                    .andExpect(jsonPath("$.page.nextCursor", notNullValue()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String cursor = objectMapper.readTree(firstPage).get("page").get("nextCursor").asText();

            mockMvc.perform(get("/api/v1/public/stores/listings/search")
                            .param("q", "business cursor fixture")
                            .param("sort", "price_asc")
                            .param("limit", "1")
                            .param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()", equalTo(1)))
                    .andExpect(jsonPath("$.data[0].id", equalTo(highPriceListingId)))
                    .andExpect(jsonPath("$.page.hasMore", equalTo(false)));
        } finally {
            jdbcTemplate.update("delete from listings where id in (?, ?)", lowPriceListingId, highPriceListingId);
        }
    }

    @Test
    void businessStoreListingsSearchRejectsInvalidSort() throws Exception {
        mockMvc.perform(get("/api/v1/public/stores/listings/search")
                        .param("sort", "popular"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
    }

    @Test
    void publicBrowseIsCappedToFixedMvpLimit() throws Exception {
        Instant basePublishedAt = Instant.parse("2099-06-17T12:00:00Z");
        try {
            for (int index = 0; index < 25; index++) {
                insertApprovedPublicListing(
                        String.format("01P%023d", index),
                        basePublishedAt.plusSeconds(index));
            }

            mockMvc.perform(get("/api/v1/public/listings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", equalTo(24)))
                    .andExpect(jsonPath("$[0].id", equalTo("01P00000000000000000000024")))
                    .andExpect(jsonPath("$[23].id", equalTo("01P00000000000000000000001")));
        } finally {
            jdbcTemplate.update("delete from listings where id like '01P%'");
        }
    }

    @Test
    void publicBrowseToleratesLegacyApprovedListingWithoutPublishedAt() throws Exception {
        String approvedListingId = createApprovedIndividualListing();
        jdbcTemplate.update("update listings set published_at = null where id = ?", approvedListingId);

        mockMvc.perform(get("/api/v1/public/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(approvedListingId)))
                .andExpect(jsonPath("$[0].publishedAt", notNullValue()));
    }

    @Test
    void publicListingDetailHidesDraftListing() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createIndividualDraft();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void publicListingDetailHidesPendingReviewListing() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void publicListingDetailHidesRejectedListing() throws Exception {
        String listingId = createRejectedIndividualListing();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    @Test
    void publicListingDetailHidesChangesRequestedListing() throws Exception {
        String listingId = createChangesRequestedIndividualListing();

        mockMvc.perform(get("/api/v1/public/listings/{listingId}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_NOT_FOUND")));
    }

    private String individualRequest() {
        return individualRequest(1);
    }

    private String individualRequest(int quantity) {
        return """
                {
                  "sellerType": "INDIVIDUAL",
                  "categoryId": "%s",
                  "title": "Used bicycle",
                  "description": "A reliable city bike.",
                  "condition": "GOOD",
                  "price": {"amount": 250.00, "currency": "USD"},
                  "negotiable": true,
                  "location": {"city": "Irvine", "region": "CA"},
                  "quantity": %d
                }
                """.formatted(CATEGORY_ID, quantity);
    }

    private String businessRequest() {
        return businessRequest("SKU-100");
    }

    private String businessRequest(String sku) {
        return """
                {
                  "sellerType": "BUSINESS",
                  "businessId": "%s",
                  "categoryId": "%s",
                  "title": "Packaged keyboard",
                  "description": "New keyboard from store inventory.",
                  "condition": "NEW",
                  "price": {"amount": 59.99, "currency": "USD"},
                  "sku": "%s",
                  "quantity": 3
                }
                """.formatted(BUSINESS_ID, CATEGORY_ID, sku);
    }

    private String createIndividualDraft() throws Exception {
        String response = mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(individualRequest()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asText();
    }

    private void insertApprovedPublicListing(String listingId, Instant publishedAt) {
        insertApprovedPublicListing(listingId, publishedAt, "INDIVIDUAL");
    }

    private void insertApprovedPublicListing(String listingId, Instant publishedAt, String sellerType) {
        insertApprovedPublicListing(
                listingId,
                publishedAt,
                sellerType,
                "Public browse fixture " + listingId,
                "Public browse fixture",
                "GOOD",
                new BigDecimal("10.00"),
                "Irvine",
                "CA",
                CATEGORY_ID);
    }

    private void insertApprovedPublicListing(
            String listingId,
            Instant publishedAt,
            String sellerType,
            String title,
            String description,
            String condition,
            BigDecimal priceAmount,
            String city,
            String region,
            String categoryId) {
        String individualSellerUserId = "BUSINESS".equals(sellerType) ? null : USER_ID;
        String businessId = "BUSINESS".equals(sellerType) ? BUSINESS_ID : null;
        String storeId = "BUSINESS".equals(sellerType) ? STORE_ID : null;
        String moderationStatus = "BUSINESS".equals(sellerType) ? "NOT_SUBMITTED" : "APPROVED";
        String publicationSource = "BUSINESS".equals(sellerType) ? "BUSINESS_SELF_PUBLISHED" : "ADMIN_REVIEW";
        boolean negotiable = !"BUSINESS".equals(sellerType);
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city, public_region,
                    status, moderation_status, publication_source, published_at, version, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, 'Public browse fixture', 'GOOD', null,
                        10.00, 'USD', ?, null, 1, 'Irvine', 'CA',
                        'ACTIVE', ?, ?, ?, 0, ?, ?)
                """,
                listingId,
                sellerType,
                individualSellerUserId,
                businessId,
                storeId,
                categoryId,
                title,
                negotiable,
                moderationStatus,
                publicationSource,
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt));
        jdbcTemplate.update("""
                update listings
                set description = ?,
                    condition_code = ?,
                    price_amount = ?,
                    public_city = ?,
                    public_region = ?
                where id = ?
                """,
                description,
                condition,
                priceAmount,
                city,
                region,
                listingId);
    }

    private int decisionCount(String listingId, String decision) {
        Integer decisions = jdbcTemplate.queryForObject(
                "select count(*) from listing_moderation_decisions where listing_id = ? and decision = ?",
                Integer.class,
                listingId,
                decision);
        return decisions == null ? 0 : decisions;
    }

    private String createBusinessDraft() throws Exception {
        return createBusinessDraft("SKU-" + System.nanoTime());
    }

    private String createBusinessDraft(String sku) throws Exception {
        String response = mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest(sku)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asText();
    }

    private String createSubmittedIndividualListing() throws Exception {
        String listingId = createIndividualDraft();
        String mediaId = createConfirmedMedia(listingId, "bike.png");
        attachImage(listingId, mediaId);
        mockMvc.perform(post("/api/v1/listings/{listingId}/submit", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
                        .header("If-Match", "0"))
                .andExpect(status().isOk());
        return listingId;
    }

    private String createApprovedIndividualListing() throws Exception {
        String listingId = createSubmittedListingForAdminDecision();
        decideListing(listingId, "APPROVE", "Listing looks good");
        return listingId;
    }

    private String createRejectedIndividualListing() throws Exception {
        String listingId = createSubmittedListingForAdminDecision();
        decideListing(listingId, "REJECT", "Rejected for test");
        return listingId;
    }

    private String createChangesRequestedIndividualListing() throws Exception {
        String listingId = createSubmittedListingForAdminDecision();
        decideListing(listingId, "REQUEST_CHANGES", "Needs clearer photos");
        return listingId;
    }

    private String createSubmittedListingForAdminDecision() throws Exception {
        when(authServiceClient.requireActiveIndividualSeller(anyString()))
                .thenReturn(new AuthServiceClient.IndividualSellerAuthorization(
                        USER_ID, "Irvine", "CA", "ACTIVE"));
        String listingId = createSubmittedIndividualListing();
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001", "PLATFORM_ADMIN"));
        return listingId;
    }

    private void decideListing(String listingId, String decision, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "%s",
                                  "reason": "%s"
                                }
                                """.formatted(decision, reason)))
                .andExpect(status().isOk());
    }

    private String createPendingMedia(String listingId, String fileName) throws Exception {
        return createPendingMedia(listingId, fileName, "individual-token");
    }

    private String createPendingMedia(String listingId, String fileName, String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue(token)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "%s",
                                  "sizeBytes": 1024
                                }
                                """.formatted(fileName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createConfirmedMedia(String listingId, String fileName) throws Exception {
        return createConfirmedMedia(listingId, fileName, "individual-token");
    }

    private String createConfirmedMedia(String listingId, String fileName, String token) throws Exception {
        String mediaId = createPendingMedia(listingId, fileName, token);
        mockMvc.perform(post("/api/v1/listings/{listingId}/media/{mediaId}/confirm", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue(token)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isOk());
        return mediaId;
    }

    private void attachImage(String listingId, String mediaId) throws Exception {
        attachImage(listingId, mediaId, "individual-token");
    }

    private void attachImage(String listingId, String mediaId, String token) throws Exception {
        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue(token)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s", "altText": "bike.png"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isOk());
    }

    private String createBusinessStoreItemDraft(String sku) throws Exception {
        String response = mockMvc.perform(post("/api/v1/businesses/{businessId}/store/items", BUSINESS_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest(sku)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private int countBusinessItems(String businessId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from listings where seller_type = 'BUSINESS' and business_id = ?",
                Integer.class,
                businessId);
        return count == null ? 0 : count;
    }

    private int countBusinessItemsByStatus(String businessId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from listings where seller_type = 'BUSINESS' and business_id = ? and status = ?",
                Integer.class,
                businessId,
                status);
        return count == null ? 0 : count;
    }

    private String createConfirmedBusinessStoreItemMedia(String listingId, String fileName) throws Exception {
        String response = mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/upload-request",
                                BUSINESS_ID,
                                listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contentType": "image/png",
                                  "fileName": "%s",
                                  "sizeBytes": 1024
                                }
                                """.formatted(fileName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String mediaId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post(
                                "/api/v1/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/confirm",
                                BUSINESS_ID,
                                listingId,
                                mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sizeBytes": 1024
                                }
                                """))
                .andExpect(status().isOk());
        return mediaId;
    }

    private void attachBusinessStoreItemImage(String listingId, String mediaId) throws Exception {
        mockMvc.perform(put("/api/v1/businesses/{businessId}/store/items/{listingId}/images", BUSINESS_ID, listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "images": [
                                    {"mediaId": "%s", "altText": "keyboard.png"}
                                  ]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isOk());
    }

    private String imageIdForMedia(String mediaId) {
        return jdbcTemplate.queryForObject(
                "select id from listing_images where media_object_id = ?",
                String.class,
                mediaId);
    }

    private Integer caseCountForListing(String listingId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from moderation_cases where subject_listing_id = ?",
                Integer.class,
                listingId);
    }

    private Object caseValue(String listingId, String columnName) {
        return jdbcTemplate.queryForObject(
                "select " + columnName + " from moderation_cases where subject_listing_id = ?",
                Object.class,
                listingId);
    }

    private String caseIdForListing(String listingId) {
        return jdbcTemplate.queryForObject(
                "select id from moderation_cases where subject_listing_id = ? order by created_at asc limit 1",
                String.class,
                listingId);
    }

    private void insertOpenModerationCase(String caseId, String listingId) {
        jdbcTemplate.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id,
                    submitted_by_user_id, status, priority, version, created_at, updated_at
                )
                values (?, 'LISTING_REVIEW', ?, 'INDIVIDUAL', ?, null, ?, 'OPEN', 'NORMAL', 0, now(6), now(6))
                """,
                caseId,
                listingId,
                USER_ID,
                USER_ID);
    }

    private void allowBusinessStoreContext(String businessId, String storeId) {
        when(authServiceClient.requireBusinessStoreContext(anyString(), eq(businessId)))
                .thenReturn(new AuthServiceClient.BusinessStoreContextAuthorization(
                        businessId,
                        "Acme Trading LLC",
                        "ACTIVE",
                        "OWNER",
                        List.of("LISTING_DRAFT_CREATE"),
                        new AuthServiceClient.BusinessStoreAuthorization(
                                storeId,
                                businessId,
                                "acme-trading",
                                "Acme Trading",
                                "ACTIVE")));
    }
}
