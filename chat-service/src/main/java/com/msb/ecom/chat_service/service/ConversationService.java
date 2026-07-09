package com.msb.ecom.chat_service.service;

import com.msb.ecom.chat_service.dto.ChatListingSummary;
import com.msb.ecom.chat_service.dto.ChatParticipantSummary;
import com.msb.ecom.chat_service.dto.ConversationCompletionResponse;
import com.msb.ecom.chat_service.dto.ConversationListItemResponse;
import com.msb.ecom.chat_service.dto.ConversationPageResponse;
import com.msb.ecom.chat_service.dto.ConversationResponse;
import com.msb.ecom.chat_service.dto.MarkConversationDoneRequest;
import com.msb.ecom.chat_service.dto.MarkConversationReadResponse;
import com.msb.ecom.chat_service.dto.MessagePageResponse;
import com.msb.ecom.chat_service.dto.MessageResponse;
import com.msb.ecom.chat_service.dto.ProductListingEligibilityResponse;
import com.msb.ecom.chat_service.dto.SendMessageRequest;
import com.msb.ecom.chat_service.dto.StartConversationResult;
import com.msb.ecom.chat_service.model.ChatConversationNotFoundException;
import com.msb.ecom.chat_service.model.ChatCompletionNotAllowedException;
import com.msb.ecom.chat_service.model.ChatDependencyUnavailableException;
import com.msb.ecom.chat_service.model.ChatListingNotEligibleException;
import com.msb.ecom.chat_service.model.ChatMessageValidationException;
import com.msb.ecom.chat_service.model.SelfConversationNotAllowedException;
import com.msb.ecom.chat_service.repository.ConversationRecord;
import com.msb.ecom.chat_service.repository.ConversationListRecord;
import com.msb.ecom.chat_service.repository.ConversationRepository;
import com.msb.ecom.chat_service.repository.CompletionRecord;
import com.msb.ecom.chat_service.repository.MessageRecord;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private static final String INDIVIDUAL = "INDIVIDUAL";
    private static final String TEXT = "TEXT";
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;
    private static final int DEFAULT_CONVERSATION_LIMIT = 20;
    private static final int MAX_CONVERSATION_LIMIT = 50;
    private static final int MAX_BODY_LENGTH = 2000;

    private final ConversationRepository conversationRepository;
    private final ChatProductClient productClient;
    private final ChatAuthClient authClient;
    private final CurrentActorProvider currentActorProvider;
    private final UlidGenerator ulidGenerator;

    @Transactional
    // Creates the fixed buyer/seller room for an eligible individual listing.
    public StartConversationResult startListingConversation(String listingId) {
        String normalizedListingId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        CurrentActor actor = currentActorProvider.currentActor();
        ChatAuthClient.CurrentUser buyer = authClient.currentUser(actor.accessToken());
        ProductListingEligibilityResponse listing =
                productClient.listingEligibility(actor.accessToken(), normalizedListingId);
        validateListingEligibility(listing);
        if (buyer.id().equals(listing.sellerUserId())) {
            log.warn("Rejected self-chat listingId={} userId={}", normalizedListingId, buyer.id());
            throw new SelfConversationNotAllowedException();
        }

        var existing = conversationRepository
                .findListingConversation(normalizedListingId, buyer.id(), listing.sellerUserId());
        ConversationRecord conversation;
        boolean created;
        if (existing.isPresent()) {
            conversation = existing.get();
            created = false;
        } else {
            var result = conversationRepository.createListingConversation(
                        ulidGenerator.next(),
                        normalizedListingId,
                        buyer.id(),
                        listing.sellerUserId(),
                    Instant.now());
            conversation = result.conversation();
            created = result.created();
        }
        log.info("Started listing conversation id={} listingId={} buyerUserId={} sellerUserId={}",
                conversation.id(), normalizedListingId, buyer.id(), listing.sellerUserId());
        return new StartConversationResult(toResponse(conversation, listing, buyer.id()), created);
    }

    @Transactional(readOnly = true)
    // Lists only conversations where the current user has a chat-owned participant row.
    public ConversationPageResponse listConversations(String cursor, Integer limit) {
        ActorUser actor = actorUser();
        CursorPosition position = decodeCursor(cursor, "Conversation cursor ID");
        int normalizedLimit = normalizeConversationLimit(limit);
        List<ConversationListRecord> rows = conversationRepository.findParticipantConversations(
                actor.userId(),
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                normalizedLimit + 1);
        boolean hasMore = rows.size() > normalizedLimit;
        List<ConversationListRecord> page = hasMore ? rows.subList(0, normalizedLimit) : rows;
        String nextCursor = hasMore && !page.isEmpty() ? encodeConversationCursor(page.get(page.size() - 1)) : null;
        Map<String, ChatAuthClient.UserLabel> labels = labels(page.stream()
                .flatMap(row -> List.of(
                        row.conversation().buyerUserId(),
                        row.conversation().sellerUserId()).stream())
                .collect(Collectors.toSet()));
        return new ConversationPageResponse(
                page.stream()
                        .map(row -> toListItem(row, actor, labels))
                        .toList(),
                nextCursor);
    }

    @Transactional(readOnly = true)
    // Loads a conversation only when the current user is one of its fixed participants.
    public ConversationResponse getConversation(String conversationId) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        ProductListingEligibilityResponse listing = listingContextForConversation(actor.accessToken(), conversation);
        return toResponse(conversation, listing, actor.userId());
    }

    @Transactional(readOnly = true)
    // Returns ordered text history using an opaque cursor based on the last message read.
    public MessagePageResponse getMessages(String conversationId, String cursor, Integer limit) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        requireParticipantConversation(normalizedConversationId, actor.userId());

        CursorPosition position = decodeCursor(cursor);
        int normalizedLimit = normalizeLimit(limit);
        List<MessageRecord> messages = conversationRepository.findMessages(
                normalizedConversationId,
                position == null ? null : position.createdAt(),
                position == null ? null : position.id(),
                normalizedLimit + 1);
        boolean hasMore = messages.size() > normalizedLimit;
        List<MessageRecord> page = hasMore ? messages.subList(0, normalizedLimit) : messages;
        String nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : null;
        return new MessagePageResponse(
                page.stream().map(message -> toMessageResponse(message, actor.userId())).toList(),
                nextCursor);
    }

    @Transactional
    // Persists a participant-authored text message and advances the conversation summary fields.
    public MessageResponse sendMessage(String conversationId, SendMessageRequest request) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        if (!"OPEN".equals(conversation.status())) {
            throw new ChatMessageValidationException("Conversation is not open for messages.");
        }
        validateMessageRequest(request);
        Instant now = Instant.now();
        MessageRecord message = conversationRepository.insertTextMessage(
                ulidGenerator.next(),
                normalizedConversationId,
                actor.userId(),
                request.body().trim(),
                now);
        log.info("Sent chat message id={} conversationId={} senderUserId={}",
                message.id(), normalizedConversationId, actor.userId());
        return toMessageResponse(message, actor.userId());
    }

    @Transactional
    // Advances only the current participant's read marker to the latest message in the conversation.
    public MarkConversationReadResponse markRead(String conversationId) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        var read = conversationRepository.markRead(normalizedConversationId, actor.userId(), Instant.now())
                .orElseThrow(ChatConversationNotFoundException::new);
        boolean unread = read.lastReadMessageId() == null && conversationRepository.latestMessageId(conversation.id()).isPresent();
        return new MarkConversationReadResponse(
                normalizedConversationId,
                read.lastReadMessageId(),
                read.lastReadAt(),
                unread);
    }

    @Transactional(readOnly = true)
    // Reads completion state only for a fixed conversation participant.
    public ConversationCompletionResponse getCompletion(String conversationId) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        CompletionRecord completion = conversationRepository.findCompletionByConversationId(normalizedConversationId)
                .orElse(null);
        ProductListingEligibilityResponse listing = listingContextForConversation(actor.accessToken(), conversation);
        return toCompletionResponse(completion, conversation, actor.userId(), listing.eligible());
    }

    @Transactional
    // Seller marks completion for the buyer derived from this listing conversation.
    public ConversationCompletionResponse markDone(String conversationId, MarkConversationDoneRequest request) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        if (!actor.userId().equals(conversation.sellerUserId())) {
            throw new ChatCompletionNotAllowedException("Only the listing seller can mark this conversation done.");
        }
        ProductListingEligibilityResponse listing =
                productClient.listingEligibility(actor.accessToken(), conversation.subjectListingId());
        validateListingEligibility(listing);
        if (!conversation.sellerUserId().equals(listing.sellerUserId())) {
            throw new ChatCompletionNotAllowedException("Conversation seller no longer matches the listing owner.");
        }
        CompletionRecord existingForListing = conversationRepository.findOpenCompletionByListingId(conversation.subjectListingId())
                .orElse(null);
        if (existingForListing != null && !existingForListing.conversationId().equals(conversation.id())) {
            throw new ChatCompletionNotAllowedException("This listing already has a completion in another conversation.");
        }
        int quantitySold = normalizeQuantitySold(request == null ? null : request.quantitySold(), listing.quantity());
        CompletionRecord completion = conversationRepository.createSellerMarkedDoneCompletion(
                ulidGenerator.next(),
                conversation,
                quantitySold,
                Instant.now());
        log.info("Seller marked listing conversation done completionId={} listingId={} conversationId={} quantitySold={}",
                completion.id(), completion.listingId(), completion.conversationId(), completion.quantitySold());
        return toCompletionResponse(completion, conversation, actor.userId(), true);
    }

    @Transactional
    // Buyer confirmation closes the listing after verifying the buyer is the fixed conversation participant.
    public ConversationCompletionResponse confirmCompletion(String conversationId) {
        String normalizedConversationId = FixedLengthIds.requireTrimmed("Conversation ID", conversationId, 26);
        ActorUser actor = actorUser();
        ConversationRecord conversation = requireParticipantConversation(normalizedConversationId, actor.userId());
        if (!actor.userId().equals(conversation.buyerUserId())) {
            throw new ChatCompletionNotAllowedException("Only the buyer in this conversation can confirm completion.");
        }
        CompletionRecord completion = conversationRepository.findCompletionByConversationId(normalizedConversationId)
                .orElseThrow(() -> new ChatCompletionNotAllowedException("The seller has not marked this deal done yet."));
        if ("CANCELLED".equals(completion.status())) {
            throw new ChatCompletionNotAllowedException("This completion has been cancelled.");
        }
        if ("BUYER_CONFIRMED".equals(completion.status())) {
            return toCompletionResponse(completion, conversation, actor.userId(), true);
        }
        if (!"SELLER_MARKED_DONE".equals(completion.status())) {
            throw new ChatCompletionNotAllowedException("This completion is not ready for buyer confirmation.");
        }
        CompletionRecord confirmed = conversationRepository.confirmCompletion(completion.id(), Instant.now());
        productClient.completeListingTrade(
                actor.accessToken(),
                confirmed.listingId(),
                confirmed.conversationId(),
                confirmed.sellerUserId(),
                confirmed.buyerUserId(),
                confirmed.quantitySold());
        log.info("Buyer confirmed listing completion completionId={} listingId={} conversationId={} quantitySold={}",
                confirmed.id(), confirmed.listingId(), confirmed.conversationId(), confirmed.quantitySold());
        return toCompletionResponse(confirmed, conversation, actor.userId(), true);
    }

    private void validateListingEligibility(ProductListingEligibilityResponse listing) {
        if (listing == null || !listing.eligible() || !INDIVIDUAL.equals(listing.sellerType()) || listing.sellerUserId() == null) {
            throw new ChatListingNotEligibleException("Listing is not available for chat.");
        }
    }

    private int normalizeQuantitySold(Integer requestedQuantity, Integer listingQuantity) {
        int quantitySold = requestedQuantity == null ? 1 : requestedQuantity;
        if (quantitySold < 1) {
            throw new ChatCompletionNotAllowedException("Quantity sold must be at least 1.");
        }
        if (listingQuantity != null && quantitySold > listingQuantity) {
            throw new ChatCompletionNotAllowedException("Quantity sold cannot exceed listing quantity.");
        }
        return quantitySold;
    }

    private ActorUser actorUser() {
        CurrentActor actor = currentActorProvider.currentActor();
        ChatAuthClient.CurrentUser currentUser = authClient.currentUser(actor.accessToken());
        return new ActorUser(actor.accessToken(), currentUser.id());
    }

    private ConversationRecord requireParticipantConversation(String conversationId, String userId) {
        ConversationRecord conversation = conversationRepository.findById(conversationId)
                .orElseThrow(ChatConversationNotFoundException::new);
        if (!conversationRepository.isParticipant(conversationId, userId)) {
            log.warn("Rejected non-participant chat access conversationId={} userId={}", conversationId, userId);
            throw new ChatConversationNotFoundException();
        }
        return conversation;
    }

    private void validateMessageRequest(SendMessageRequest request) {
        if (request == null) {
            throw new ChatMessageValidationException("Message body is required.");
        }
        String messageType = request.messageType() == null || request.messageType().isBlank()
                ? TEXT
                : request.messageType().trim();
        if (!TEXT.equals(messageType)) {
            throw new ChatMessageValidationException("Only text messages are supported.");
        }
        if (request.body() == null || request.body().trim().isBlank()) {
            throw new ChatMessageValidationException("Message body is required.");
        }
        if (request.body().length() > MAX_BODY_LENGTH) {
            throw new ChatMessageValidationException("Message body must be 2000 characters or fewer.");
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_MESSAGE_LIMIT;
        }
        if (limit < 1) {
            throw new ChatMessageValidationException("Message limit must be at least 1.");
        }
        return Math.min(limit, MAX_MESSAGE_LIMIT);
    }

    private int normalizeConversationLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_CONVERSATION_LIMIT;
        }
        if (limit < 1) {
            throw new ChatMessageValidationException("Conversation limit must be at least 1.");
        }
        return Math.min(limit, MAX_CONVERSATION_LIMIT);
    }

    private CursorPosition decodeCursor(String cursor) {
        return decodeCursor(cursor, "Message cursor ID");
    }

    private CursorPosition decodeCursor(String cursor, String idLabel) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor.");
            }
            return new CursorPosition(Instant.parse(parts[0]), FixedLengthIds.requireTrimmed(idLabel, parts[1], 26));
        } catch (RuntimeException exception) {
            throw new ChatMessageValidationException("Cursor is invalid.");
        }
    }

    private String encodeCursor(MessageRecord message) {
        String value = message.createdAt() + "|" + message.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeConversationCursor(ConversationListRecord row) {
        String value = row.conversation().updatedAt() + "|" + row.conversation().id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ConversationListItemResponse toListItem(
            ConversationListRecord row,
            ActorUser actor,
            Map<String, ChatAuthClient.UserLabel> labels) {
        ConversationRecord conversation = row.conversation();
        String otherUserId = conversation.buyerUserId().equals(actor.userId())
                ? conversation.sellerUserId()
                : conversation.buyerUserId();
        String otherRole = conversation.buyerUserId().equals(otherUserId) ? "BUYER" : "SELLER";
        MessageRecord lastMessage = row.lastMessage();
        return new ConversationListItemResponse(
                conversation.id(),
                conversation.conversationType(),
                conversation.status(),
                listingSummaryForInbox(actor.accessToken(), conversation.subjectListingId()),
                participant(otherUserId, otherRole, actor.userId(), labels),
                lastMessage == null ? null : toMessageResponse(lastMessage, actor.userId()),
                unread(row, actor.userId()),
                lastMessage == null ? null : lastMessage.createdAt(),
                conversation.createdAt(),
                conversation.updatedAt());
    }

    private boolean unread(ConversationListRecord row, String currentUserId) {
        MessageRecord lastMessage = row.lastMessage();
        if (lastMessage == null || lastMessage.senderUserId().equals(currentUserId)) {
            return false;
        }
        if (row.lastReadMessageId() == null) {
            return true;
        }
        MessageRecord lastRead = conversationRepository
                .findMessageByIdForConversation(row.conversation().id(), row.lastReadMessageId())
                .orElse(null);
        return lastRead == null || lastMessage.createdAt().isAfter(lastRead.createdAt());
    }

    private ChatListingSummary listingSummaryForInbox(String accessToken, String listingId) {
        try {
            ProductListingEligibilityResponse listing = productClient.listingEligibility(accessToken, listingId);
            return new ChatListingSummary(
                    listing.listingId(),
                    listing.title(),
                    listing.sellerType(),
                    listing.quantity(),
                    listing.publicCity(),
                    listing.publicRegion(),
                    listing.thumbnailUrl(),
                    listing.transactionNotice());
        } catch (ChatListingNotEligibleException | ChatDependencyUnavailableException unavailable) {
            log.warn("Conversation inbox listing context unavailable listingId={} reason={}", listingId, unavailable.getMessage());
            return new ChatListingSummary(
                    listingId,
                    "Listing unavailable",
                    INDIVIDUAL,
                    null,
                    null,
                    null,
                    null,
                    "Payment and delivery are arranged directly by participants.");
        }
    }

    private ProductListingEligibilityResponse listingContextForConversation(String accessToken, ConversationRecord conversation) {
        try {
            return productClient.listingEligibility(accessToken, conversation.subjectListingId());
        } catch (ChatListingNotEligibleException | ChatDependencyUnavailableException unavailable) {
            log.warn("Conversation listing context unavailable conversationId={} listingId={} reason={}",
                    conversation.id(), conversation.subjectListingId(), unavailable.getMessage());
            return new ProductListingEligibilityResponse(
                    conversation.subjectListingId(),
                    false,
                    INDIVIDUAL,
                    conversation.sellerUserId(),
                    null,
                    "Listing unavailable",
                    null,
                    null,
                    null,
                    "Payment and delivery are arranged directly by participants.");
        }
    }

    private ConversationResponse toResponse(
            ConversationRecord conversation,
            ProductListingEligibilityResponse listing,
            String currentUserId) {
        Map<String, ChatAuthClient.UserLabel> labels = labels(Set.of(
                conversation.buyerUserId(),
                conversation.sellerUserId()));
        return new ConversationResponse(
                conversation.id(),
                conversation.conversationType(),
                conversation.status(),
                new ChatListingSummary(
                        listing.listingId(),
                        listing.title(),
                        listing.sellerType(),
                        listing.quantity(),
                        listing.publicCity(),
                        listing.publicRegion(),
                        listing.thumbnailUrl(),
                        listing.transactionNotice()),
                List.of(
                        participant(conversation.buyerUserId(), "BUYER", currentUserId, labels),
                        participant(conversation.sellerUserId(), "SELLER", currentUserId, labels)),
                toCompletionResponse(
                        conversationRepository.findCompletionByConversationId(conversation.id()).orElse(null),
                        conversation,
                        currentUserId,
                        listing.eligible()),
                conversation.createdAt(),
                conversation.updatedAt());
    }

    private ConversationCompletionResponse toCompletionResponse(
            CompletionRecord completion,
            ConversationRecord conversation,
            String currentUserId,
            boolean listingEligibleForMarkDone) {
        boolean seller = conversation.sellerUserId().equals(currentUserId);
        boolean buyer = conversation.buyerUserId().equals(currentUserId);
        if (completion == null) {
            return new ConversationCompletionResponse(
                    null,
                    conversation.subjectListingId(),
                    conversation.id(),
                    conversation.sellerUserId(),
                    conversation.buyerUserId(),
                    null,
                    "NOT_STARTED",
                    null,
                    null,
                    null,
                    seller && listingEligibleForMarkDone,
                    false);
        }
        boolean canConfirm = buyer && "SELLER_MARKED_DONE".equals(completion.status());
        return new ConversationCompletionResponse(
                completion.id(),
                completion.listingId(),
                completion.conversationId(),
                completion.sellerUserId(),
                completion.buyerUserId(),
                completion.quantitySold(),
                completion.status(),
                completion.sellerMarkedDoneAt(),
                completion.buyerConfirmedAt(),
                completion.cancelledAt(),
                seller && "NOT_STARTED".equals(completion.status()),
                canConfirm);
    }

    private MessageResponse toMessageResponse(MessageRecord message, String currentUserId) {
        return new MessageResponse(
                message.id(),
                message.conversationId(),
                message.senderUserId(),
                message.messageType(),
                message.body(),
                message.moderationState(),
                message.senderUserId().equals(currentUserId),
                message.createdAt());
    }

    private Map<String, ChatAuthClient.UserLabel> labels(Set<String> userIds) {
        try {
            return authClient.publicLabels(userIds).users().stream()
                    .collect(Collectors.toMap(
                            ChatAuthClient.UserLabel::id,
                            label -> label,
                            (first, second) -> first,
                            LinkedHashMap::new));
        } catch (ChatDependencyUnavailableException unavailable) {
            log.warn("Participant label hydration failed userCount={} reason={}", userIds.size(), unavailable.getMessage());
            return Map.of();
        }
    }

    private ChatParticipantSummary participant(
            String userId,
            String role,
            String currentUserId,
            Map<String, ChatAuthClient.UserLabel> labels) {
        boolean currentUser = userId.equals(currentUserId);
        ChatAuthClient.UserLabel label = labels.get(userId);
        String displayName = currentUser ? "You" : displayName(role, label);
        String avatarUrl = label == null ? null : label.avatarUrl();
        return new ChatParticipantSummary(
                userId,
                displayName,
                avatarUrl,
                initials(displayName),
                role,
                currentUser);
    }

    private String displayName(String role, ChatAuthClient.UserLabel label) {
        if (label != null && label.displayName() != null && !label.displayName().isBlank()) {
            return label.displayName().trim();
        }
        return "SELLER".equals(role) ? "Marketplace seller" : "Marketplace user";
    }

    private String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "MU";
        }
        String[] parts = displayName.trim().split("\\s+");
        String value = parts.length == 1
                ? parts[0].substring(0, Math.min(2, parts[0].length()))
                : parts[0].substring(0, 1) + parts[1].substring(0, 1);
        return value.toUpperCase(Locale.ROOT);
    }

    private record ActorUser(String accessToken, String userId) {
    }

    private record CursorPosition(Instant createdAt, String id) {
    }
}
