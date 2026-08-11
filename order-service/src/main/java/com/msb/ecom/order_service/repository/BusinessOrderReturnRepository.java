package com.msb.ecom.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BusinessOrderReturnRepository {
    private final JdbcTemplate jdbc;

    public BusinessOrderReturnRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Group> lockBuyerGroup(String buyerId, String orderId, String groupId) {
        return jdbc.query("""
                SELECT bo.id business_order_id, bo.order_id, bo.business_id, bo.store_name,
                       bo.fulfillment_status, bo.cancellation_status, bo.version group_version,
                       bo.subtotal refund_amount, o.currency, o.payment_intent_id,
                       c.reservation_id, s.delivered_at
                FROM business_orders bo
                JOIN orders o ON o.id=bo.order_id
                JOIN checkout_sessions c ON c.id=o.checkout_id
                LEFT JOIN shipments s ON s.business_order_id=bo.id
                WHERE o.buyer_id=? AND o.id=? AND bo.id=? FOR UPDATE
                """, mapper(), buyerId, orderId, groupId).stream().findFirst();
    }

    public Optional<Group> lockBusinessGroup(String businessId, String groupId) {
        return jdbc.query("""
                SELECT bo.id business_order_id, bo.order_id, bo.business_id, bo.store_name,
                       bo.fulfillment_status, bo.cancellation_status, bo.version group_version,
                       bo.subtotal refund_amount, o.currency, o.payment_intent_id,
                       c.reservation_id, s.delivered_at
                FROM business_orders bo
                JOIN orders o ON o.id=bo.order_id
                JOIN checkout_sessions c ON c.id=o.checkout_id
                LEFT JOIN shipments s ON s.business_order_id=bo.id
                WHERE bo.business_id=? AND bo.id=? FOR UPDATE
                """, mapper(), businessId, groupId).stream().findFirst();
    }

    public Optional<ReturnRecord> findByGroup(String groupId) {
        return queryReturn("WHERE r.business_order_id=?", groupId);
    }

    public Optional<ReturnRecord> lockById(String returnId) {
        return queryReturn("WHERE r.id=? FOR UPDATE", returnId);
    }

    public Optional<ReturnRecord> findBuyer(String buyerId, String orderId, String groupId) {
        return queryReturn("WHERE r.buyer_id=? AND r.order_id=? AND r.business_order_id=?",
                buyerId, orderId, groupId);
    }

    public Optional<ReturnRecord> findBusiness(String businessId, String groupId) {
        return queryReturn("WHERE r.business_id=? AND r.business_order_id=?", businessId, groupId);
    }

    public Optional<Command> lockCommand(String actor, String businessId, String operation, String key) {
        return jdbc.query("""
                SELECT * FROM business_order_return_commands
                WHERE actor_user_id=? AND business_id=? AND operation=? AND idempotency_key=?
                FOR UPDATE
                """, (rs,n)->new Command(rs.getString("id"), rs.getString("request_hash"),
                rs.getString("return_id"), rs.getString("state"),
                number(rs.getObject("result_version"))), actor,businessId,operation,key)
                .stream().findFirst();
    }

    public void insertCommand(String id, String actor, String businessId, String operation,
            String key, String hash, String returnId, Instant now) {
        jdbc.update("""
                INSERT INTO business_order_return_commands
                (id,actor_user_id,business_id,operation,idempotency_key,request_hash,
                 return_id,state,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?,'IN_PROGRESS',?,?)
                """, id,actor,businessId,operation,key,hash,returnId,
                Timestamp.from(now.plusSeconds(604800)),Timestamp.from(now));
    }

    public void completeCommand(String id, long version, Instant now) {
        jdbc.update("""
                UPDATE business_order_return_commands SET state='COMPLETED',result_version=?,completed_at=?
                WHERE id=? AND state='IN_PROGRESS'
                """,version,Timestamp.from(now),id);
    }

    public void insertReturn(String id, String buyerId, Group group, String reason,
            String comment, Instant requestedAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO business_order_returns
                (id,buyer_id,order_id,business_order_id,business_id,reason_code,buyer_comment,
                 policy_version,policy_window_days,requested_at,window_expires_at,status,
                 refund_status,currency,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'LOCAL_DEMO_RETURN_POLICY_V1',30,?,?,'RETURN_REQUESTED',
                        'NONE',?,0,?,?)
                """,id,buyerId,group.orderId(),group.businessOrderId(),group.businessId(),reason,
                comment,Timestamp.from(requestedAt),Timestamp.from(expiresAt),group.currency(),
                Timestamp.from(requestedAt),Timestamp.from(requestedAt));
    }

    public int transition(String id, long expectedVersion, String from, String to, Instant now) {
        return jdbc.update("""
                UPDATE business_order_returns SET status=?,version=version+1,updated_at=?
                WHERE id=? AND version=? AND status=?
                """,to,Timestamp.from(now),id,expectedVersion,from);
    }

    public void setReceived(String id, long expectedVersion, String disposition, Instant now) {
        int changed=jdbc.update("""
                UPDATE business_order_returns SET status='RETURN_RECEIVED',refund_status='PENDING',
                    inventory_disposition=?,received_at=?,version=version+1,
                    processing_next_attempt_at=?,updated_at=?
                WHERE id=? AND version=? AND status='RETURN_IN_TRANSIT'
                """,disposition,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),id,expectedVersion);
        if(changed!=1) throw new IllegalStateException("Return receipt transition lost its lock.");
    }

    public void insertShipment(String id,String returnId,String tracking,Instant now){
        jdbc.update("""
                INSERT INTO business_order_return_shipments
                (id,return_id,carrier_display_name,tracking_reference,created_at,in_transit_at,version)
                VALUES (?,?,'Demo Returns',?,?,?,0)
                """,id,returnId,tracking,Timestamp.from(now),Timestamp.from(now));
    }

    public void history(String id,String returnId,String from,String to,long version,String reason,
            String actor,String correlation,String causation,Instant now){
        jdbc.update("""
                INSERT INTO business_order_return_history
                (id,return_id,from_status,to_status,return_version,reason_code,actor_user_id,
                 correlation_id,causation_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,id,returnId,from,to,version,reason,actor,correlation,causation,Timestamp.from(now));
    }

    public void outbox(String id,String returnId,String eventType,String payload,
            String correlation,String causation,Instant now){
        jdbc.update("""
                INSERT INTO order_outbox_events
                (id,aggregate_type,aggregate_id,event_type,event_version,payload_json,
                 correlation_id,causation_id,created_at)
                VALUES (?,'RETURN',?,?,1,CAST(? AS JSON),?,?,?)
                """,id,returnId,eventType,payload,correlation,causation,Timestamp.from(now));
    }

    public List<ReturnRecord> due(Instant now,int limit){
        return jdbc.query(returnSelect()+"""
                WHERE r.status='RETURN_RECEIVED' AND r.refund_status='PENDING'
                  AND r.processing_next_attempt_at<=? ORDER BY r.processing_next_attempt_at,r.id LIMIT ?
                """, returnMapper(),Timestamp.from(now),limit);
    }

    public boolean claimProcessing(String id){
        return jdbc.update("""
                UPDATE business_order_returns SET refund_status='PROCESSING',
                    processing_attempt_count=processing_attempt_count+1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND status='RETURN_RECEIVED' AND refund_status='PENDING'
                """,id)==1;
    }

    public void retry(String id,Instant next,String code){
        jdbc.update("""
                UPDATE business_order_returns SET refund_status='PENDING',processing_next_attempt_at=?,
                    processing_last_error_code=?,updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND status='RETURN_RECEIVED' AND refund_status='PROCESSING'
                """,Timestamp.from(next),code,id);
    }

    public void complete(String id,String refundId,BigDecimal amount,String correlation,Instant now,
            String historyRefundId,String historyCompletedId,String eventId,String payload){
        ReturnRecord r=lockById(id).orElseThrow();
        if("RETURN_COMPLETED".equals(r.status())) return;
        if(!"RETURN_RECEIVED".equals(r.status())||!"PROCESSING".equals(r.refundStatus()))
            throw new IllegalStateException("Return completion state changed.");
        int changed=jdbc.update("""
                UPDATE business_order_returns SET status='RETURN_COMPLETED',refund_status='SUCCEEDED',
                    refund_id=?,refund_amount=?,completed_at=?,version=version+2,
                    processing_next_attempt_at=NULL,processing_last_error_code=NULL,updated_at=?
                WHERE id=? AND version=?
                """,refundId,amount,Timestamp.from(now),Timestamp.from(now),id,r.version());
        if(changed!=1) throw new IllegalStateException("Return completion lost its optimistic lock.");
        history(historyRefundId,id,"RETURN_RECEIVED","RETURN_RECEIVED",r.version()+1,
                "DEMO_REFUND_COMPLETED",null,correlation,eventId,now);
        history(historyCompletedId,id,"RETURN_RECEIVED","RETURN_COMPLETED",r.version()+2,
                "RETURN_COMPLETED",null,correlation,eventId,now);
        outbox(eventId,id,"return.refund_completed",payload,correlation,id,now);
    }

    public List<History> history(String returnId){
        return jdbc.query("""
                SELECT to_status,reason_code,occurred_at FROM business_order_return_history
                WHERE return_id=? ORDER BY return_version
                """,(rs,n)->new History(rs.getString("to_status"),rs.getString("reason_code"),
                rs.getTimestamp("occurred_at").toInstant()),returnId);
    }

    private Optional<ReturnRecord> queryReturn(String where,Object...args){
        return jdbc.query(returnSelect()+where,returnMapper(),args).stream().findFirst();
    }
    private String returnSelect(){return """
        SELECT r.*,bo.store_name,o.payment_intent_id,c.reservation_id,
               s.carrier_display_name,s.tracking_reference,s.created_at shipment_created_at,
               s.in_transit_at
        FROM business_order_returns r
        JOIN business_orders bo ON bo.id=r.business_order_id
        JOIN orders o ON o.id=r.order_id
        JOIN checkout_sessions c ON c.id=o.checkout_id
        LEFT JOIN business_order_return_shipments s ON s.return_id=r.id
        """;}
    private org.springframework.jdbc.core.RowMapper<ReturnRecord> returnMapper(){return (rs,n)->new ReturnRecord(
            rs.getString("id"),rs.getString("buyer_id"),rs.getString("order_id"),
            rs.getString("business_order_id"),rs.getString("business_id"),rs.getString("store_name"),
            rs.getString("reason_code"),rs.getString("buyer_comment"),rs.getString("policy_version"),
            rs.getTimestamp("requested_at").toInstant(),rs.getTimestamp("window_expires_at").toInstant(),
            rs.getString("status"),rs.getString("refund_status"),rs.getString("inventory_disposition"),
            instant(rs.getTimestamp("received_at")),rs.getString("refund_id"),rs.getBigDecimal("refund_amount"),
            rs.getString("currency"),instant(rs.getTimestamp("completed_at")),rs.getLong("version"),
            rs.getString("payment_intent_id"),rs.getString("reservation_id"),rs.getString("carrier_display_name"),
            rs.getString("tracking_reference"),instant(rs.getTimestamp("shipment_created_at")),
            instant(rs.getTimestamp("in_transit_at")) );}
    private org.springframework.jdbc.core.RowMapper<Group> mapper(){return (rs,n)->new Group(
            rs.getString("business_order_id"),rs.getString("order_id"),rs.getString("business_id"),
            rs.getString("store_name"),rs.getString("fulfillment_status"),rs.getString("cancellation_status"),
            rs.getLong("group_version"),rs.getBigDecimal("refund_amount"),rs.getString("currency"),
            rs.getString("payment_intent_id"),rs.getString("reservation_id"),
            instant(rs.getTimestamp("delivered_at")));}
    private Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private Long number(Object value){return value==null?null:((Number)value).longValue();}

    public record Group(String businessOrderId,String orderId,String businessId,String storeName,
            String fulfillmentStatus,String cancellationStatus,long groupVersion,BigDecimal refundAmount,
            String currency,String paymentIntentId,String reservationId,Instant deliveredAt){}
    public record ReturnRecord(String id,String buyerId,String orderId,String businessOrderId,
            String businessId,String storeName,String reasonCode,String buyerComment,String policyVersion,
            Instant requestedAt,Instant windowExpiresAt,String status,String refundStatus,
            String disposition,Instant receivedAt,String refundId,BigDecimal refundAmount,String currency,
            Instant completedAt,long version,String paymentIntentId,String reservationId,
            String carrier,String tracking,Instant shipmentCreatedAt,Instant inTransitAt){}
    public record Command(String id,String requestHash,String returnId,String state,Long resultVersion){}
    public record History(String status,String reason,Instant occurredAt){}
}
