package com.msb.ecom.chat_service;

import com.msb.ecom.chat_service.dto.ProductListingEligibilityResponse;
import com.msb.ecom.chat_service.service.ChatAuthClient;
import com.msb.ecom.chat_service.service.ChatProductClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationApiTests {

    private static final String LISTING_ID = "01L00000000000000000000001";
    private static final String BUYER_ID = "01U00000000000000000000011";
    private static final String SELLER_ID = "01U00000000000000000000022";

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    static {
        mysqlContainer.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ChatAuthClient authClient;

    @MockBean
    ChatProductClient productClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from listing_trade_completions");
        jdbcTemplate.update("delete from messages");
        jdbcTemplate.update("delete from conversation_participants");
        jdbcTemplate.update("delete from conversations");
    }

    @Test
    void buyerCanStartConversationForActiveIndividualListing() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.conversationType", equalTo("LISTING_BUYER_SELLER")))
                .andExpect(jsonPath("$.status", equalTo("OPEN")))
                .andExpect(jsonPath("$.listing.id", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.listing.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.participants", hasSize(2)))
                .andExpect(jsonPath("$.participants[0].participantId", equalTo(BUYER_ID)))
                .andExpect(jsonPath("$.participants[0].displayName", equalTo("You")))
                .andExpect(jsonPath("$.participants[0].roleInConversation", equalTo("BUYER")))
                .andExpect(jsonPath("$.participants[0].currentUser", equalTo(true)))
                .andExpect(jsonPath("$.participants[1].participantId", equalTo(SELLER_ID)))
                .andExpect(jsonPath("$.participants[1].displayName", equalTo("Alex Seller")))
                .andExpect(jsonPath("$.participants[1].roleInConversation", equalTo("SELLER")))
                .andExpect(jsonPath("$.participants[1].currentUser", equalTo(false)))
                .andExpect(jsonPath("$.participants[1].avatarUrl", equalTo("/api/v1/public/user-avatars/%s?v=4".formatted(SELLER_ID))))
                .andExpect(jsonPath("$.participants[1].email").doesNotExist());

        Integer conversations = jdbcTemplate.queryForObject("select count(*) from conversations", Integer.class);
        Integer participants = jdbcTemplate.queryForObject("select count(*) from conversation_participants", Integer.class);
        org.assertj.core.api.Assertions.assertThat(conversations).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(participants).isEqualTo(2);
    }

    @Test
    void duplicateStartReturnsExistingConversation() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk());

        Integer conversations = jdbcTemplate.queryForObject("select count(*) from conversations", Integer.class);
        org.assertj.core.api.Assertions.assertThat(conversations).isEqualTo(1);
    }

    @Test
    void sellerCannotStartSelfConversation() throws Exception {
        givenBuyer(SELLER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_SELF_CONVERSATION_NOT_ALLOWED")));
    }

    @Test
    void businessListingCannotStartMvpChat() throws Exception {
        givenBuyer(BUYER_ID);
        when(productClient.listingEligibility(anyString(), anyString()))
                .thenReturn(new ProductListingEligibilityResponse(
                        LISTING_ID,
                        false,
                        "BUSINESS",
                        null,
                        null,
                        "Store item",
                        "Irvine",
                        "CA",
                        null,
                        null));

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_LISTING_NOT_AVAILABLE")));
    }

    @Test
    void individualSellerCanBeBuyerForAnotherSellerListing() throws Exception {
        String buyerWhoAlsoSells = "01U00000000000000000000033";
        givenBuyer(buyerWhoAlsoSells);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();

        mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-buyer-token"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participants[0].participantId", equalTo(buyerWhoAlsoSells)))
                .andExpect(jsonPath("$.participants[0].roleInConversation", equalTo("BUYER")))
                .andExpect(jsonPath("$.participants[1].participantId", equalTo(SELLER_ID)))
                .andExpect(jsonPath("$.participants[1].roleInConversation", equalTo("SELLER")));
    }

    @Test
    void participantCanReadConversationDetail() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);

        mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(conversationId)))
                .andExpect(jsonPath("$.listing.id", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.participants", hasSize(2)));
    }

    @Test
    void nonParticipantCannotReadConversationDetail() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer("01U00000000000000000000044");

        mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));
    }

    @Test
    void participantCanSendTextMessage() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageType": "TEXT",
                                  "body": "Is this still available?"
                                }
                                """)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.conversationId", equalTo(conversationId)))
                .andExpect(jsonPath("$.senderUserId", equalTo(BUYER_ID)))
                .andExpect(jsonPath("$.messageType", equalTo("TEXT")))
                .andExpect(jsonPath("$.body", equalTo("Is this still available?")))
                .andExpect(jsonPath("$.currentUser", equalTo(true)));

        Integer messages = jdbcTemplate.queryForObject("select count(*) from messages", Integer.class);
        String lastMessageId = jdbcTemplate.queryForObject(
                "select last_message_id from conversations where id = ?",
                String.class,
                conversationId);
        org.assertj.core.api.Assertions.assertThat(messages).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(lastMessageId).isNotBlank();
    }

    @Test
    void chatApiResponsesDoNotExposePrivateContactFields() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();

        String createResponse = mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", LISTING_ID)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String conversationId = objectMapper.readTree(createResponse).path("id").asText();
        sendMessage(conversationId, "Is this still available?");

        String detailResponse = mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String inboxResponse = mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String messagesResponse = mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertNoPrivateContactFields(createResponse);
        assertNoPrivateContactFields(detailResponse);
        assertNoPrivateContactFields(inboxResponse);
        assertNoPrivateContactFields(messagesResponse);
    }

    @Test
    void nonParticipantCannotSendMessage() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer("01U00000000000000000000044");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello\"}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_INVALID_MESSAGE")));
    }

    @Test
    void unsupportedMessageTypeIsRejected() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageType\":\"IMAGE\",\"body\":\"photo\"}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_INVALID_MESSAGE")));
    }

    @Test
    void tooLongMessageIsRejected() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "a".repeat(2001) + "\"}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_INVALID_MESSAGE")));
    }

    @Test
    void participantCanReadPaginatedMessagesInOrder() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        sendMessage(conversationId, "First");
        sendMessage(conversationId, "Second");

        String nextCursor = mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .queryParam("limit", "1")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].body", equalTo("First")))
                .andExpect(jsonPath("$.nextCursor", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        nextCursor = objectMapper.readTree(nextCursor).path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .queryParam("cursor", nextCursor)
                        .queryParam("limit", "1")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].body", equalTo("Second")));
    }

    @Test
    void nonParticipantCannotReadMessages() throws Exception {
        givenBuyer(BUYER_ID);
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer("01U00000000000000000000044");

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));
    }

    @Test
    void participantListsOnlyTheirConversations() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        createConversation("01U00000000000000000000044");
        givenBuyer(BUYER_ID);

        mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(conversationId)))
                .andExpect(jsonPath("$.items[0].listing.id", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.items[0].otherParticipant.participantId", equalTo(SELLER_ID)))
                .andExpect(jsonPath("$.items[0].otherParticipant.avatarUrl", equalTo("/api/v1/public/user-avatars/%s?v=4".formatted(SELLER_ID))))
                .andExpect(jsonPath("$.items[0].unread", equalTo(false)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void unreadStateIsIsolatedPerParticipant() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);
        sendMessage(conversationId, "Still available.");
        givenBuyer(BUYER_ID);

        mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(conversationId)))
                .andExpect(jsonPath("$.items[0].unread", equalTo(true)))
                .andExpect(jsonPath("$.items[0].lastMessage.body", equalTo("Still available.")))
                .andExpect(jsonPath("$.items[0].lastMessage.currentUser", equalTo(false)));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/read", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId", equalTo(conversationId)))
                .andExpect(jsonPath("$.lastReadMessageId", notNullValue()))
                .andExpect(jsonPath("$.unread", equalTo(false)));

        mockMvc.perform(get("/api/v1/conversations")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unread", equalTo(false)));

        String sellerLastRead = jdbcTemplate.queryForObject("""
                select last_read_message_id
                from conversation_participants
                where conversation_id = ?
                  and user_id = ?
                """, String.class, conversationId, SELLER_ID);
        org.assertj.core.api.Assertions.assertThat(sellerLastRead).isNull();
    }

    @Test
    void conversationListSupportsCursorPagination() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String firstConversationId = createConversation(BUYER_ID);
        String secondListingId = "01L00000000000000000000002";
        givenEligibleListing(secondListingId, SELLER_ID);
        String secondConversationId = createConversationForListing(BUYER_ID, secondListingId);
        givenBuyer(BUYER_ID);

        String firstPage = mockMvc.perform(get("/api/v1/conversations")
                        .queryParam("limit", "1")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(secondConversationId)))
                .andExpect(jsonPath("$.nextCursor", notNullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String nextCursor = objectMapper.readTree(firstPage).path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/conversations")
                        .queryParam("cursor", nextCursor)
                        .queryParam("limit", "1")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(firstConversationId)));
    }

    @Test
    void nonParticipantCannotMarkRead() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer("01U00000000000000000000044");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/read", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));
    }

    @Test
    void noChatMeansSellerCannotMarkDone() throws Exception {
        givenBuyer(SELLER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", "01C00000000000000000009999")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));

        verify(productClient, never()).completeListingTrade(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void sellerMarkDoneIsConversationGatedAndIdempotent() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantitySold\":2}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.conversationId", equalTo(conversationId)))
                .andExpect(jsonPath("$.sellerUserId", equalTo(SELLER_ID)))
                .andExpect(jsonPath("$.buyerUserId", equalTo(BUYER_ID)))
                .andExpect(jsonPath("$.quantitySold", equalTo(2)))
                .andExpect(jsonPath("$.status", equalTo("SELLER_MARKED_DONE")))
                .andExpect(jsonPath("$.currentUserCanConfirm", equalTo(false)));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("SELLER_MARKED_DONE")));

        Integer completions = jdbcTemplate.queryForObject("select count(*) from listing_trade_completions", Integer.class);
        org.assertj.core.api.Assertions.assertThat(completions).isEqualTo(1);
    }

    @Test
    void sellerCannotMarkDoneForMoreThanListingQuantity() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantitySold\":4}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_COMPLETION_NOT_ALLOWED")));

        Integer completions = jdbcTemplate.queryForObject("select count(*) from listing_trade_completions", Integer.class);
        org.assertj.core.api.Assertions.assertThat(completions).isZero();
    }

    @Test
    void buyerCanConfirmOnlyFromTheirConversationAndDuplicateConfirmIsSafe() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantitySold\":2}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk());
        givenBuyer(BUYER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/confirm", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("BUYER_CONFIRMED")))
                .andExpect(jsonPath("$.currentUserCanConfirm", equalTo(false)));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/confirm", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("BUYER_CONFIRMED")));

        Integer completions = jdbcTemplate.queryForObject("select count(*) from listing_trade_completions", Integer.class);
        org.assertj.core.api.Assertions.assertThat(completions).isEqualTo(1);
        verify(productClient, times(1)).completeListingTrade(
                anyString(),
                org.mockito.ArgumentMatchers.eq(LISTING_ID),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(SELLER_ID),
                org.mockito.ArgumentMatchers.eq(BUYER_ID),
                org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void unrelatedUserCannotConfirmCompletion() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk());
        givenBuyer("01U00000000000000000000044");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/confirm", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("other-token"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_CONVERSATION_NOT_FOUND")));
    }

    @Test
    void sellerCannotConfirmOwnCompletion() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String conversationId = createConversation(BUYER_ID);
        givenBuyer(SELLER_ID);
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/confirm", conversationId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_COMPLETION_NOT_ALLOWED")));
    }

    @Test
    void completionCannotHappenFromDifferentConversationForSameListing() throws Exception {
        givenEligibleListing(LISTING_ID, SELLER_ID);
        givenLabels();
        String firstConversationId = createConversation(BUYER_ID);
        String secondConversationId = createConversation("01U00000000000000000000044");
        givenBuyer(SELLER_ID);

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", firstConversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantitySold\":1}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/completion/mark-done", secondConversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantitySold\":1}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("seller-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CHAT_COMPLETION_NOT_ALLOWED")));

        Integer completions = jdbcTemplate.queryForObject("select count(*) from listing_trade_completions", Integer.class);
        org.assertj.core.api.Assertions.assertThat(completions).isEqualTo(1);
    }

    private String createConversation(String buyerId) throws Exception {
        return createConversationForListing(buyerId, LISTING_ID);
    }

    private String createConversationForListing(String buyerId, String listingId) throws Exception {
        givenBuyer(buyerId);
        String response = mockMvc.perform(post("/api/v1/listings/{listingId}/conversations", listingId)
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("id").asText();
    }

    private void sendMessage(String conversationId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("buyer-token"))))
                .andExpect(status().isCreated());
    }

    private void givenBuyer(String userId) {
        when(authClient.currentUser(anyString()))
                .thenReturn(new ChatAuthClient.CurrentUser(userId, "Buyer User", null));
    }

    private void givenEligibleListing(String listingId, String sellerId) {
        when(productClient.listingEligibility(anyString(), anyString()))
                .thenReturn(new ProductListingEligibilityResponse(
                        listingId,
                        true,
                        "INDIVIDUAL",
                        sellerId,
                        2,
                        "Used bicycle",
                        "Irvine",
                        "CA",
                        "/api/v1/public/listing-media/01I00000000000000000000001",
                        "Payment and delivery are arranged directly by participants."));
    }

    private void givenLabels() {
        when(authClient.publicLabels(anySet()))
                .thenAnswer(invocation -> {
                    Set<String> ids = invocation.getArgument(0);
                    List<ChatAuthClient.UserLabel> labels = ids.stream()
                            .map(id -> new ChatAuthClient.UserLabel(
                                    id,
                                    id.equals(SELLER_ID) ? "Alex Seller" : "Buyer User",
                                    id.equals(SELLER_ID) ? "/api/v1/public/user-avatars/%s?v=4".formatted(id) : null))
                            .toList();
                    return new ChatAuthClient.IdentityLabels(labels);
                });
    }

    private void assertNoPrivateContactFields(String json) {
        String normalized = json.toLowerCase();
        org.assertj.core.api.Assertions.assertThat(normalized)
                .doesNotContain("email")
                .doesNotContain("phone")
                .doesNotContain("contact")
                .doesNotContain("keycloak")
                .doesNotContain("subject")
                .doesNotContain("objectkey")
                .doesNotContain("objectbucket")
                .doesNotContain("signedurl");
    }
}
