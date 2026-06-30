package com.msb.ecom.product_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ListingDraftApiTests {

    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String USER_ID = "01U00000000000000000000001";
    private static final String BUSINESS_ID = "01B00000000000000000000001";

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

    @MockBean
    AuthServiceClient authServiceClient;

    @MockBean
    ListingMediaStorage listingMediaStorage;

    @BeforeEach
    void configureMediaStorage() {
        when(listingMediaStorage.createUploadTarget(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> new StorageUploadTarget(
                        "listing-media-test",
                        invocation.getArgument(0),
                        "PUT",
                        "https://storage.example.test/" + invocation.getArgument(0, String.class)));
        when(listingMediaStorage.readObject(anyString()))
                .thenReturn(new byte[]{1, 2, 3});
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
        when(authServiceClient.requireBusinessListingPermission(anyString(), eq(BUSINESS_ID)))
                .thenReturn(new AuthServiceClient.BusinessMembershipAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER", "ACTIVE", List.of("LISTING_DRAFT_CREATE")));

        mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerType", equalTo("BUSINESS")))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.individualSellerUserId").doesNotExist())
                .andExpect(jsonPath("$.sku", equalTo("SKU-100")))
                .andExpect(jsonPath("$.quantity", equalTo(3)))
                .andExpect(jsonPath("$.negotiable", equalTo(false)))
                .andExpect(jsonPath("$.status", equalTo("DRAFT")));
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
                .andExpect(jsonPath("$.moderationStatus", equalTo("CHANGES_REQUESTED")));
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

        mockMvc.perform(get("/api/v1/public/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(approvedListingId)))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(pendingListingId))))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(rejectedListingId))))
                .andExpect(jsonPath("$[0].individualSellerUserId").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].moderationStatus").doesNotExist());
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
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city, public_region,
                    status, moderation_status, published_at, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', ?, null, null, ?, ?, 'Public browse fixture', 'GOOD', null,
                        10.00, 'USD', true, null, 1, 'Irvine', 'CA',
                        'ACTIVE', 'APPROVED', ?, 0, ?, ?)
                """,
                listingId,
                USER_ID,
                CATEGORY_ID,
                "Public browse fixture " + listingId,
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt));
    }

    private String createBusinessDraft() throws Exception {
        String response = mockMvc.perform(post("/api/v1/listings")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("business-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(businessRequest("SKU-200")))
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
        String response = mockMvc.perform(post("/api/v1/listings/{listingId}/media/upload-request", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
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
        String mediaId = createPendingMedia(listingId, fileName);
        mockMvc.perform(post("/api/v1/listings/{listingId}/media/{mediaId}/confirm", listingId, mediaId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
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
        mockMvc.perform(put("/api/v1/listings/{listingId}/images", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("individual-token")))
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
}
