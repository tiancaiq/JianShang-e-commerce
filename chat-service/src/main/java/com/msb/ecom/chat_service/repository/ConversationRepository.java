package com.msb.ecom.chat_service.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ConversationRepository {

    private static final String LISTING_BUYER_SELLER = "LISTING_BUYER_SELLER";
    private static final String OPEN = "OPEN";

    private final JdbcTemplate jdbcTemplate;

    public Optional<ConversationRecord> findListingConversation(String listingId, String buyerUserId, String sellerUserId) {
        List<ConversationRecord> rows = jdbcTemplate.query("""
                select id, conversation_type, subject_listing_id, buyer_user_id, seller_user_id,
                       status, created_at, updated_at
                from conversations
                where conversation_type = ?
                  and subject_listing_id = ?
                  and buyer_user_id = ?
                  and seller_user_id = ?
                """,
                (rs, rowNum) -> conversation(rs),
                LISTING_BUYER_SELLER,
                listingId,
                buyerUserId,
                sellerUserId);
        return rows.stream().findFirst();
    }

    public CreateConversationResult createListingConversation(
            String id,
            String listingId,
            String buyerUserId,
            String sellerUserId,
            Instant now) {
        try {
            jdbcTemplate.update("""
                    insert into conversations (
                        id, conversation_type, subject_listing_id, buyer_user_id, seller_user_id,
                        status, version, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    id,
                    LISTING_BUYER_SELLER,
                    listingId,
                    buyerUserId,
                    sellerUserId,
                    OPEN,
                    Timestamp.from(now),
                    Timestamp.from(now));
            insertParticipant(id, buyerUserId, "BUYER", now);
            insertParticipant(id, sellerUserId, "SELLER", now);
            return new CreateConversationResult(findById(id).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            return new CreateConversationResult(
                    findListingConversation(listingId, buyerUserId, sellerUserId).orElseThrow(),
                    false);
        }
    }

    public Optional<ConversationRecord> findById(String conversationId) {
        List<ConversationRecord> rows = jdbcTemplate.query("""
                select id, conversation_type, subject_listing_id, buyer_user_id, seller_user_id,
                       status, created_at, updated_at
                from conversations
                where id = ?
                """,
                (rs, rowNum) -> conversation(rs),
                conversationId);
        return rows.stream().findFirst();
    }

    public Optional<CompletionRecord> findCompletionByConversationId(String conversationId) {
        List<CompletionRecord> rows = jdbcTemplate.query("""
                select id, listing_id, conversation_id, seller_user_id, buyer_user_id, quantity_sold, status,
                       seller_marked_done_at, buyer_confirmed_at, cancelled_at, created_at, updated_at
                from listing_trade_completions
                where conversation_id = ?
                """,
                (rs, rowNum) -> completion(rs),
                conversationId);
        return rows.stream().findFirst();
    }

    public Optional<CompletionRecord> findOpenCompletionByListingId(String listingId) {
        List<CompletionRecord> rows = jdbcTemplate.query("""
                select id, listing_id, conversation_id, seller_user_id, buyer_user_id, quantity_sold, status,
                       seller_marked_done_at, buyer_confirmed_at, cancelled_at, created_at, updated_at
                from listing_trade_completions
                where listing_id = ?
                  and status in ('SELLER_MARKED_DONE', 'BUYER_CONFIRMED')
                order by updated_at desc, id desc
                limit 1
                """,
                (rs, rowNum) -> completion(rs),
                listingId);
        return rows.stream().findFirst();
    }

    public CompletionRecord createSellerMarkedDoneCompletion(
            String id,
            ConversationRecord conversation,
            int quantitySold,
            Instant now) {
        try {
            jdbcTemplate.update("""
                    insert into listing_trade_completions (
                        id, listing_id, conversation_id, seller_user_id, buyer_user_id, quantity_sold, status,
                        seller_marked_done_at, version, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, 'SELLER_MARKED_DONE', ?, 0, ?, ?)
                    """,
                    id,
                    conversation.subjectListingId(),
                    conversation.id(),
                    conversation.sellerUserId(),
                    conversation.buyerUserId(),
                    quantitySold,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // Natural idempotency key is the conversation; return the existing row below.
        }
        return findCompletionByConversationId(conversation.id()).orElseThrow();
    }

    public CompletionRecord confirmCompletion(String completionId, Instant now) {
        jdbcTemplate.update("""
                update listing_trade_completions
                set status = 'BUYER_CONFIRMED',
                    buyer_confirmed_at = coalesce(buyer_confirmed_at, ?),
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'SELLER_MARKED_DONE'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                completionId);
        return findCompletionById(completionId).orElseThrow();
    }

    // Locks a completed trade conversation so neither participant can add messages after confirmation.
    public void lockConversation(String conversationId, Instant now) {
        jdbcTemplate.update("""
                update conversations
                set status = 'LOCKED',
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status <> 'LOCKED'
                """,
                Timestamp.from(now),
                conversationId);
    }

    public List<ConversationListRecord> findParticipantConversations(
            String userId,
            Instant beforeUpdatedAt,
            String beforeId,
            int limit) {
        if (beforeUpdatedAt == null || beforeId == null) {
            return jdbcTemplate.query("""
                    select c.id, c.conversation_type, c.subject_listing_id, c.buyer_user_id, c.seller_user_id,
                           c.status, c.created_at, c.updated_at,
                           cp.role_in_conversation, cp.last_read_message_id, cp.last_read_at,
                           m.id as message_id, m.conversation_id as message_conversation_id,
                           m.sender_user_id as message_sender_user_id, m.message_type,
                           m.body as message_body, m.moderation_state, m.created_at as message_created_at
                    from conversation_participants cp
                    join conversations c on c.id = cp.conversation_id
                    left join messages m on m.id = c.last_message_id
                    where cp.user_id = ?
                    order by c.updated_at desc, c.id desc
                    limit ?
                    """,
                    (rs, rowNum) -> conversationListRecord(rs),
                    userId,
                    limit);
        }
        return jdbcTemplate.query("""
                select c.id, c.conversation_type, c.subject_listing_id, c.buyer_user_id, c.seller_user_id,
                       c.status, c.created_at, c.updated_at,
                       cp.role_in_conversation, cp.last_read_message_id, cp.last_read_at,
                       m.id as message_id, m.conversation_id as message_conversation_id,
                       m.sender_user_id as message_sender_user_id, m.message_type,
                       m.body as message_body, m.moderation_state, m.created_at as message_created_at
                from conversation_participants cp
                join conversations c on c.id = cp.conversation_id
                left join messages m on m.id = c.last_message_id
                where cp.user_id = ?
                  and (c.updated_at < ? or (c.updated_at = ? and c.id < ?))
                order by c.updated_at desc, c.id desc
                limit ?
                """,
                (rs, rowNum) -> conversationListRecord(rs),
                userId,
                Timestamp.from(beforeUpdatedAt),
                Timestamp.from(beforeUpdatedAt),
                beforeId,
                limit);
    }

    public boolean isParticipant(String conversationId, String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from conversation_participants
                where conversation_id = ?
                  and user_id = ?
                """,
                Integer.class,
                conversationId,
                userId);
        return count != null && count > 0;
    }

    public Optional<String> latestMessageId(String conversationId) {
        List<String> rows = jdbcTemplate.query("""
                select last_message_id
                from conversations
                where id = ?
                  and last_message_id is not null
                """,
                (rs, rowNum) -> rs.getString("last_message_id"),
                conversationId);
        return rows.stream().findFirst();
    }

    public Optional<ParticipantReadRecord> markRead(String conversationId, String userId, Instant now) {
        Optional<String> latestMessageId = latestMessageId(conversationId);
        jdbcTemplate.update("""
                update conversation_participants
                set last_read_message_id = ?,
                    last_read_at = ?
                where conversation_id = ?
                  and user_id = ?
                """,
                latestMessageId.orElse(null),
                Timestamp.from(now),
                conversationId,
                userId);
        return findParticipantRead(conversationId, userId);
    }

    public MessageRecord insertTextMessage(String messageId, String conversationId, String senderUserId, String body, Instant now) {
        jdbcTemplate.update("""
                insert into messages (
                    id, conversation_id, sender_user_id, message_type, body, moderation_state, created_at
                )
                values (?, ?, ?, 'TEXT', ?, 'VISIBLE', ?)
                """,
                messageId,
                conversationId,
                senderUserId,
                body,
                Timestamp.from(now));
        jdbcTemplate.update("""
                update conversations
                set last_message_id = ?,
                    last_message_at = ?,
                    updated_at = ?,
                    version = version + 1
                where id = ?
                """,
                messageId,
                Timestamp.from(now),
                Timestamp.from(now),
                conversationId);
        return findMessageById(messageId).orElseThrow();
    }

    public Optional<MessageRecord> findMessageByIdForConversation(String conversationId, String messageId) {
        List<MessageRecord> rows = jdbcTemplate.query("""
                select id, conversation_id, sender_user_id, message_type, body, moderation_state, created_at
                from messages
                where conversation_id = ?
                  and id = ?
                """,
                (rs, rowNum) -> message(rs),
                conversationId,
                messageId);
        return rows.stream().findFirst();
    }

    public List<MessageRecord> findMessages(String conversationId, Instant afterCreatedAt, String afterId, int limit) {
        if (afterCreatedAt == null || afterId == null) {
            return jdbcTemplate.query("""
                    select id, conversation_id, sender_user_id, message_type, body, moderation_state, created_at
                    from messages
                    where conversation_id = ?
                    order by created_at, id
                    limit ?
                    """,
                    (rs, rowNum) -> message(rs),
                    conversationId,
                    limit);
        }
        return jdbcTemplate.query("""
                select id, conversation_id, sender_user_id, message_type, body, moderation_state, created_at
                from messages
                where conversation_id = ?
                  and (created_at > ? or (created_at = ? and id > ?))
                order by created_at, id
                limit ?
                """,
                (rs, rowNum) -> message(rs),
                conversationId,
                Timestamp.from(afterCreatedAt),
                Timestamp.from(afterCreatedAt),
                afterId,
                limit);
    }

    private Optional<MessageRecord> findMessageById(String messageId) {
        List<MessageRecord> rows = jdbcTemplate.query("""
                select id, conversation_id, sender_user_id, message_type, body, moderation_state, created_at
                from messages
                where id = ?
                """,
                (rs, rowNum) -> message(rs),
                messageId);
        return rows.stream().findFirst();
    }

    private void insertParticipant(String conversationId, String userId, String role, Instant now) {
        jdbcTemplate.update("""
                insert into conversation_participants (
                    conversation_id, user_id, role_in_conversation, joined_at
                )
                values (?, ?, ?, ?)
                """,
                conversationId,
                userId,
                role,
                Timestamp.from(now));
    }

    private ConversationRecord conversation(ResultSet rs) throws SQLException {
        return new ConversationRecord(
                rs.getString("id"),
                rs.getString("conversation_type"),
                rs.getString("subject_listing_id"),
                rs.getString("buyer_user_id"),
                rs.getString("seller_user_id"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private MessageRecord message(ResultSet rs) throws SQLException {
        return new MessageRecord(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("sender_user_id"),
                rs.getString("message_type"),
                rs.getString("body"),
                rs.getString("moderation_state"),
                rs.getTimestamp("created_at").toInstant());
    }

    private CompletionRecord completion(ResultSet rs) throws SQLException {
        Timestamp sellerMarkedDoneAt = rs.getTimestamp("seller_marked_done_at");
        Timestamp buyerConfirmedAt = rs.getTimestamp("buyer_confirmed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new CompletionRecord(
                rs.getString("id"),
                rs.getString("listing_id"),
                rs.getString("conversation_id"),
                rs.getString("seller_user_id"),
                rs.getString("buyer_user_id"),
                rs.getInt("quantity_sold"),
                rs.getString("status"),
                sellerMarkedDoneAt == null ? null : sellerMarkedDoneAt.toInstant(),
                buyerConfirmedAt == null ? null : buyerConfirmedAt.toInstant(),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Optional<CompletionRecord> findCompletionById(String completionId) {
        List<CompletionRecord> rows = jdbcTemplate.query("""
                select id, listing_id, conversation_id, seller_user_id, buyer_user_id, quantity_sold, status,
                       seller_marked_done_at, buyer_confirmed_at, cancelled_at, created_at, updated_at
                from listing_trade_completions
                where id = ?
                """,
                (rs, rowNum) -> completion(rs),
                completionId);
        return rows.stream().findFirst();
    }

    private ConversationListRecord conversationListRecord(ResultSet rs) throws SQLException {
        ConversationRecord conversation = conversation(rs);
        Timestamp lastReadAt = rs.getTimestamp("last_read_at");
        MessageRecord lastMessage = rs.getString("message_id") == null
                ? null
                : new MessageRecord(
                        rs.getString("message_id"),
                        rs.getString("message_conversation_id"),
                        rs.getString("message_sender_user_id"),
                        rs.getString("message_type"),
                        rs.getString("message_body"),
                        rs.getString("moderation_state"),
                        rs.getTimestamp("message_created_at").toInstant());
        return new ConversationListRecord(
                conversation,
                rs.getString("role_in_conversation"),
                rs.getString("last_read_message_id"),
                lastReadAt == null ? null : lastReadAt.toInstant(),
                lastMessage);
    }

    private Optional<ParticipantReadRecord> findParticipantRead(String conversationId, String userId) {
        List<ParticipantReadRecord> rows = jdbcTemplate.query("""
                select conversation_id, user_id, last_read_message_id, last_read_at
                from conversation_participants
                where conversation_id = ?
                  and user_id = ?
                """,
                (rs, rowNum) -> {
                    Timestamp lastReadAt = rs.getTimestamp("last_read_at");
                    return new ParticipantReadRecord(
                            rs.getString("conversation_id"),
                            rs.getString("user_id"),
                            rs.getString("last_read_message_id"),
                            lastReadAt == null ? null : lastReadAt.toInstant());
                },
                conversationId,
                userId);
        return rows.stream().findFirst();
    }

    public record CreateConversationResult(ConversationRecord conversation, boolean created) {
    }

    public record ParticipantReadRecord(
            String conversationId,
            String userId,
            String lastReadMessageId,
            Instant lastReadAt
    ) {
    }
}
