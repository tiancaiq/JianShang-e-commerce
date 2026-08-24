package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Assignment;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Priority;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Resolution;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Status;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrderDisputeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderDisputeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    public Optional<Scope> buyerScope(String buyerId, String orderId, String groupId, boolean lock) {
        return scope("WHERE o.buyer_id=? AND o.id=? AND bo.id=?", lock, buyerId, orderId, groupId);
    }

    public Optional<Scope> groupScope(String businessId, String groupId, boolean lock) {
        return scope("WHERE bo.business_id=? AND bo.id=?", lock, businessId, groupId);
    }

    public Optional<Scope> scopeForDispute(String disputeId, boolean lock) {
        return scope("JOIN order_disputes d ON d.business_order_id=bo.id WHERE d.id=?", lock, disputeId);
    }

    private Optional<Scope> scope(String where, boolean lock, Object... args) {
        return jdbc.query("""
                SELECT o.id order_id,o.buyer_id,o.status order_status,o.payment_status,o.payment_intent_id,
                       o.created_at order_created_at,o.currency,bo.id business_order_id,bo.business_id,
                       bo.fulfillment_status,bo.cancellation_status,bo.total group_total,bo.created_at group_created_at,
                       bo.updated_at group_updated_at,bo.version group_version,
                       c.reservation_status,c.release_status,
                       s.source shipment_source,s.carrier_display_name,s.service_display_name,s.tracking_number,
                       s.status shipment_status,s.shipped_at,s.delivered_at,
                       COALESCE((SELECT SUM(r.refund_amount) FROM business_order_returns r
                         WHERE r.business_order_id=bo.id AND r.refund_status='SUCCEEDED'),0) refunded_amount
                FROM orders o JOIN business_orders bo ON bo.order_id=o.id
                JOIN checkout_sessions c ON c.id=o.checkout_id
                LEFT JOIN shipments s ON s.business_order_id=bo.id
                """ + where + (lock ? " FOR UPDATE" : ""), OrderDisputeRepository::scopeRow, args)
                .stream().findFirst();
    }

    public List<ItemRow> items(String groupId) {
        return jdbc.query("""
                SELECT id,listing_id,title,sku,item_condition,thumbnail_url,quantity,unit_price,line_total,currency
                FROM order_items WHERE business_order_id=? ORDER BY line_number,id
                """, OrderDisputeRepository::itemRow, groupId);
    }

    public Optional<DisputeRow> find(String id, boolean lock) {
        return jdbc.query("""
                SELECT id,order_id,business_order_id,business_id,buyer_user_id,opened_by_type,opened_by_user_id,
                       reason_code,description,status,priority,assigned_admin_id,resolution_type,
                       resolution_reason_code,resolution_reason,recommended_refund_amount,currency,
                       return_instructions,return_deadline,created_at,updated_at,resolved_at,version,correlation_id
                FROM order_disputes WHERE id=?
                """ + (lock ? " FOR UPDATE" : ""), OrderDisputeRepository::disputeRow, id).stream().findFirst();
    }

    public Optional<DisputeRow> byGroup(String groupId) {
        return jdbc.query("""
                SELECT id,order_id,business_order_id,business_id,buyer_user_id,opened_by_type,opened_by_user_id,
                       reason_code,description,status,priority,assigned_admin_id,resolution_type,
                       resolution_reason_code,resolution_reason,recommended_refund_amount,currency,
                       return_instructions,return_deadline,created_at,updated_at,resolved_at,version,correlation_id
                FROM order_disputes WHERE business_order_id=?
                """, OrderDisputeRepository::disputeRow, groupId).stream().findFirst();
    }

    public List<DisputeRow> buyerDisputes(String buyerId, String orderId) {
        return jdbc.query("""
                SELECT id,order_id,business_order_id,business_id,buyer_user_id,opened_by_type,opened_by_user_id,
                       reason_code,description,status,priority,assigned_admin_id,resolution_type,
                       resolution_reason_code,resolution_reason,recommended_refund_amount,currency,
                       return_instructions,return_deadline,created_at,updated_at,resolved_at,version,correlation_id
                FROM order_disputes WHERE buyer_user_id=? AND order_id=? ORDER BY created_at,id
                """, OrderDisputeRepository::disputeRow, buyerId, orderId);
    }

    public void insert(DisputeRow d, List<String> itemIds) {
        jdbc.update("""
                INSERT INTO order_disputes(id,order_id,business_order_id,business_id,buyer_user_id,
                  opened_by_type,opened_by_user_id,reason_code,description,status,priority,currency,
                  created_at,updated_at,version,correlation_id)
                VALUES(?,?,?,?,?,?,?,?,?,'OPEN','MEDIUM',?,?,?,0,?)
                """, d.id(),d.orderId(),d.businessOrderId(),d.businessId(),d.buyerId(),d.openedByType(),
                d.openedByUserId(),d.reasonCode(),d.description(),d.currency(),ts(d.createdAt()),
                ts(d.updatedAt()),d.correlationId());
        for (String itemId : itemIds) jdbc.update(
                "INSERT INTO order_dispute_items(dispute_id,order_item_id,created_at) VALUES(?,?,?)",
                d.id(),itemId,ts(d.createdAt()));
    }

    public SearchResult search(SearchFilter f, String adminId) {
        List<Object> args = new ArrayList<>(); StringBuilder where = new StringBuilder(" WHERE 1=1");
        add(where,args,"d.order_id=?",f.orderId()); add(where,args,"d.buyer_user_id=?",f.buyerId());
        add(where,args,"d.business_id=?",f.businessId()); add(where,args,"d.status=?",name(f.status()));
        add(where,args,"d.reason_code=?",f.reasonCode()); add(where,args,"d.priority=?",name(f.priority()));
        if (f.query()!=null) { where.append(" AND (d.id=? OR d.order_id=?)"); args.add(f.query()); args.add(f.query()); }
        if (f.createdFrom()!=null){where.append(" AND d.created_at>=?");args.add(ts(f.createdFrom()));}
        if (f.createdTo()!=null){where.append(" AND d.created_at<?");args.add(ts(f.createdTo()));}
        Assignment a=f.assignment()==null?Assignment.ALL:f.assignment();
        if(a==Assignment.UNASSIGNED)where.append(" AND d.assigned_admin_id IS NULL");
        else if(a==Assignment.ASSIGNED_TO_ME){where.append(" AND d.assigned_admin_id=?");args.add(adminId);}
        else if(a==Assignment.ASSIGNED)where.append(" AND d.assigned_admin_id IS NOT NULL");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM order_disputes d"+where,Long.class,args.toArray());
        String order=switch(f.sort()){case "updatedAt,desc"->"d.updated_at DESC,d.id DESC";case "priority,desc"->"FIELD(d.priority,'CRITICAL','HIGH','MEDIUM','LOW'),d.created_at,d.id";default->"d.created_at DESC,d.id DESC";};
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(f.size());pageArgs.add(f.page()*f.size());
        List<DisputeRow> rows=jdbc.query("""
                SELECT id,order_id,business_order_id,business_id,buyer_user_id,opened_by_type,opened_by_user_id,
                       reason_code,description,status,priority,assigned_admin_id,resolution_type,
                       resolution_reason_code,resolution_reason,recommended_refund_amount,currency,
                       return_instructions,return_deadline,created_at,updated_at,resolved_at,version,correlation_id
                FROM order_disputes d
                """+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",OrderDisputeRepository::disputeRow,pageArgs.toArray());
        return new SearchResult(rows,total);
    }

    public List<ItemRow> disputedItems(String disputeId) {
        return jdbc.query("""
                SELECT i.id,i.listing_id,i.title,i.sku,i.item_condition,i.thumbnail_url,i.quantity,
                       i.unit_price,i.line_total,i.currency FROM order_items i
                JOIN order_dispute_items di ON di.order_item_id=i.id WHERE di.dispute_id=?
                ORDER BY i.line_number,i.id
                """,OrderDisputeRepository::itemRow,disputeId);
    }
    public List<StatementRow> statements(String id){return jdbc.query("SELECT id,author_type,author_user_id,statement_type,body,created_at FROM order_dispute_statements WHERE dispute_id=? ORDER BY created_at,id",(r,n)->new StatementRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),instant(r,6)),id);}
    public List<EvidenceRow> evidence(String id){return jdbc.query("SELECT id,author_type,author_user_id,reference_type,reference_id,safe_label,created_at FROM order_dispute_evidence WHERE dispute_id=? ORDER BY created_at,id",(r,n)->new EvidenceRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),instant(r,7)),id);}
    public List<NoteRow> notes(String id){return jdbc.query("SELECT id,author_admin_id,author_display_name,body,created_at FROM order_dispute_admin_notes WHERE dispute_id=? ORDER BY created_at,id",(r,n)->new NoteRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),instant(r,5)),id);}
    public List<EventRow> events(String id){return jdbc.query("SELECT event_id,event_type,occurred_at,actor_type,actor_id,actor_display_name,previous_state,new_state,reason_code,reason,correlation_id,request_id,safe_metadata FROM order_dispute_events WHERE dispute_id=? ORDER BY occurred_at,event_id",this::eventRow,id);}

    public void statement(String id,String disputeId,String authorType,String authorId,String type,String body,Instant now){jdbc.update("INSERT INTO order_dispute_statements(id,dispute_id,author_type,author_user_id,statement_type,body,created_at) VALUES(?,?,?,?,?,?,?)",id,disputeId,authorType,authorId,type,body,ts(now));}
    public void evidence(String id,String disputeId,String authorType,String authorId,String type,String reference,String label,Instant now){jdbc.update("INSERT INTO order_dispute_evidence(id,dispute_id,author_type,author_user_id,reference_type,reference_id,safe_label,created_at) VALUES(?,?,?,?,?,?,?,?)",id,disputeId,authorType,authorId,type,reference,label,ts(now));}
    public void note(String id,String disputeId,String adminId,String name,String body,Instant now){jdbc.update("INSERT INTO order_dispute_admin_notes(id,dispute_id,author_admin_id,author_display_name,body,created_at) VALUES(?,?,?,?,?,?)",id,disputeId,adminId,name,body,ts(now));}
    public void event(EventRow e){try{jdbc.update("INSERT INTO order_dispute_events(event_id,dispute_id,event_type,occurred_at,actor_type,actor_id,actor_display_name,previous_state,new_state,reason_code,reason,correlation_id,request_id,safe_metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.id(),e.disputeId(),e.type(),ts(e.at()),e.actorType(),e.actorId(),e.actorName(),e.previous(),e.next(),e.reasonCode(),e.reason(),e.correlation(),e.requestId(),mapper.writeValueAsString(e.metadata()));}catch(Exception x){throw new IllegalStateException(x);}}

    public int claim(String id,long v,String admin,Instant now){return jdbc.update("UPDATE order_disputes SET assigned_admin_id=?,status=CASE WHEN status='OPEN' THEN 'UNDER_ADMIN_REVIEW' ELSE status END,updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id IS NULL AND resolved_at IS NULL",admin,ts(now),id,v);}
    public int release(String id,long v,String admin,Instant now){return jdbc.update("UPDATE order_disputes SET assigned_admin_id=NULL,updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id=? AND resolved_at IS NULL",ts(now),id,v,admin);}
    public int priority(String id,long v,String admin,Priority p,Instant now){return jdbc.update("UPDATE order_disputes SET priority=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id=? AND resolved_at IS NULL",p.name(),ts(now),id,v,admin);}
    public int status(String id,long v,String admin,Status from,Status to,Instant now){return jdbc.update("UPDATE order_disputes SET status=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id=? AND status=? AND resolved_at IS NULL",to.name(),ts(now),id,v,admin,from.name());}
    public int participantResponse(String id,long v,Status waiting,Instant now){return jdbc.update("UPDATE order_disputes SET status='UNDER_ADMIN_REVIEW',updated_at=?,version=version+1 WHERE id=? AND version=? AND status=? AND resolved_at IS NULL",ts(now),id,v,waiting.name());}
    public int touchParticipant(String id,long v,Instant now){return jdbc.update("UPDATE order_disputes SET updated_at=?,version=version+1 WHERE id=? AND version=? AND resolved_at IS NULL",ts(now),id,v);}
    public int touchOwned(String id,long v,String admin,Instant now){return jdbc.update("UPDATE order_disputes SET updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id=? AND resolved_at IS NULL",ts(now),id,v,admin);}
    public int resolve(String id,long v,String admin,Resolution type,String code,String reason,BigDecimal amount,String instructions,Instant deadline,Instant now){return jdbc.update("UPDATE order_disputes SET status=?,resolution_type=?,resolution_reason_code=?,resolution_reason=?,recommended_refund_amount=?,return_instructions=?,return_deadline=?,resolved_at=?,updated_at=?,version=version+1 WHERE id=? AND version=? AND assigned_admin_id=? AND status='READY_FOR_DECISION' AND resolved_at IS NULL",type.name(),type.name(),code,reason,amount,instructions,deadline==null?null:ts(deadline),ts(now),ts(now),id,v,admin);}

    public Optional<CommandRow> command(String actorType,String actorId,String op,String key,boolean lock){return jdbc.query("SELECT id,request_hash,dispute_id,state,result_version FROM order_dispute_commands WHERE actor_type=? AND actor_id=? AND operation=? AND idempotency_key=?"+(lock?" FOR UPDATE":""),(r,n)->new CommandRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),(Long)r.getObject(5)),actorType,actorId,op,key).stream().findFirst();}
    public boolean insertCommand(String id,String actorType,String actorId,String op,String key,String hash,String disputeId,Instant now){try{jdbc.update("INSERT INTO order_dispute_commands(id,actor_type,actor_id,operation,idempotency_key,request_hash,dispute_id,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,'IN_PROGRESS',?,?)",id,actorType,actorId,op,key,hash,disputeId,ts(now),ts(now.plusSeconds(604800)));return true;}catch(DuplicateKeyException e){return false;}}
    public void completeCommand(String id,long version,Instant now){jdbc.update("UPDATE order_dispute_commands SET state='COMPLETED',result_version=?,completed_at=? WHERE id=?",version,ts(now),id);}

    private EventRow eventRow(ResultSet r,int n)throws SQLException{Map<String,Object> metadata;try{metadata=mapper.readValue(r.getString("safe_metadata"),new TypeReference<>(){});}catch(Exception e){metadata=Map.of();}return new EventRow(r.getString("event_id"),null,r.getString("event_type"),instant(r,"occurred_at"),r.getString("actor_type"),r.getString("actor_id"),r.getString("actor_display_name"),r.getString("previous_state"),r.getString("new_state"),r.getString("reason_code"),r.getString("reason"),r.getString("correlation_id"),r.getString("request_id"),metadata);}
    private static Scope scopeRow(ResultSet r,int n)throws SQLException{return new Scope(r.getString("order_id"),r.getString("buyer_id"),r.getString("order_status"),r.getString("payment_status"),r.getString("payment_intent_id"),instant(r,"order_created_at"),r.getString("currency"),r.getString("business_order_id"),r.getString("business_id"),r.getString("fulfillment_status"),r.getString("cancellation_status"),r.getBigDecimal("group_total"),instant(r,"group_created_at"),instant(r,"group_updated_at"),r.getLong("group_version"),r.getString("reservation_status"),r.getString("release_status"),r.getString("shipment_source"),r.getString("carrier_display_name"),r.getString("service_display_name"),r.getString("tracking_number"),r.getString("shipment_status"),nullableInstant(r,"shipped_at"),nullableInstant(r,"delivered_at"),r.getBigDecimal("refunded_amount"));}
    private static ItemRow itemRow(ResultSet r,int n)throws SQLException{return new ItemRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),r.getBigDecimal(8),r.getBigDecimal(9),r.getString(10));}
    private static DisputeRow disputeRow(ResultSet r,int n)throws SQLException{return new DisputeRow(r.getString("id"),r.getString("order_id"),r.getString("business_order_id"),r.getString("business_id"),r.getString("buyer_user_id"),r.getString("opened_by_type"),r.getString("opened_by_user_id"),r.getString("reason_code"),r.getString("description"),Status.valueOf(r.getString("status")),Priority.valueOf(r.getString("priority")),r.getString("assigned_admin_id"),r.getString("resolution_type")==null?null:Resolution.valueOf(r.getString("resolution_type")),r.getString("resolution_reason_code"),r.getString("resolution_reason"),r.getBigDecimal("recommended_refund_amount"),r.getString("currency"),r.getString("return_instructions"),nullableInstant(r,"return_deadline"),instant(r,"created_at"),instant(r,"updated_at"),nullableInstant(r,"resolved_at"),r.getLong("version"),r.getString("correlation_id"));}
    private static void add(StringBuilder w,List<Object>a,String clause,Object v){if(v!=null){w.append(" AND ").append(clause);a.add(v);}}
    private static String name(Enum<?> e){return e==null?null:e.name();}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static Instant instant(ResultSet r,int i)throws SQLException{return r.getTimestamp(i).toInstant();}
    private static Instant instant(ResultSet r,String s)throws SQLException{return r.getTimestamp(s).toInstant();}
    private static Instant nullableInstant(ResultSet r,String s)throws SQLException{Timestamp t=r.getTimestamp(s);return t==null?null:t.toInstant();}

    public record Scope(String orderId,String buyerId,String orderStatus,String paymentStatus,String paymentIntentId,Instant orderCreatedAt,String currency,String businessOrderId,String businessId,String fulfillmentStatus,String cancellationStatus,BigDecimal groupTotal,Instant groupCreatedAt,Instant groupUpdatedAt,long groupVersion,String reservationStatus,String releaseStatus,String shipmentSource,String carrierDisplayName,String serviceDisplayName,String trackingNumber,String shipmentStatus,Instant shippedAt,Instant deliveredAt,BigDecimal refundedAmount){public BigDecimal refundable(){return groupTotal.subtract(refundedAmount);}}
    public record ItemRow(String id,String listingId,String title,String sku,String condition,String image,int quantity,BigDecimal unitPrice,BigDecimal lineTotal,String currency){}
    public record DisputeRow(String id,String orderId,String businessOrderId,String businessId,String buyerId,String openedByType,String openedByUserId,String reasonCode,String description,Status status,Priority priority,String assignedAdminId,Resolution resolution,String resolutionReasonCode,String resolutionReason,BigDecimal recommendedRefundAmount,String currency,String returnInstructions,Instant returnDeadline,Instant createdAt,Instant updatedAt,Instant resolvedAt,long version,String correlationId){}
    public record StatementRow(String id,String authorType,String authorId,String type,String body,Instant createdAt){}
    public record EvidenceRow(String id,String authorType,String authorId,String type,String reference,String label,Instant createdAt){}
    public record NoteRow(String id,String adminId,String adminName,String body,Instant createdAt){}
    public record EventRow(String id,String disputeId,String type,Instant at,String actorType,String actorId,String actorName,String previous,String next,String reasonCode,String reason,String correlation,String requestId,Map<String,Object> metadata){}
    public record CommandRow(String id,String hash,String disputeId,String state,Long resultVersion){}
    public record SearchFilter(String query,String orderId,String buyerId,String businessId,Status status,String reasonCode,Priority priority,Assignment assignment,Instant createdFrom,Instant createdTo,int page,int size,String sort){}
    public record SearchResult(List<DisputeRow> rows,long total){}
}
