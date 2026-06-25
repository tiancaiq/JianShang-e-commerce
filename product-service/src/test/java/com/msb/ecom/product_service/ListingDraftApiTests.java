package com.msb.ecom.product_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.listing.AuthServiceClient;
import com.msb.ecom.product_service.listing.ListingAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ListingDraftApiTests {

    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String USER_ID = "01U00000000000000000000001";
    private static final String BUSINESS_ID = "01B00000000000000000000001";

    @ServiceConnection
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0.7");

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mongoDBContainer.start();
        mysqlContainer.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AuthServiceClient authServiceClient;

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
                        .content(individualRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.individualSellerUserId", equalTo(USER_ID)))
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andExpect(jsonPath("$.quantity", equalTo(1)))
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
                .andExpect(jsonPath("$.uploadMethod", equalTo("LOCAL_DEMO")))
                .andExpect(jsonPath("$.uploadUrl", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String mediaId = objectMapper.readTree(uploadResponse).get("id").asText();

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

    private String individualRequest() {
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
                  "quantity": 1
                }
                """.formatted(CATEGORY_ID);
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
}
