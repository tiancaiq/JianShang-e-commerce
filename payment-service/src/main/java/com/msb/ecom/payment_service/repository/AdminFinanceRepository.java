package com.msb.ecom.payment_service.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AdminFinanceRepository {
    private static final String PAYMENT_COLUMNS = """
            p.id,p.checkout_id,p.buyer_id,p.amount,p.currency,p.provider,p.provider_reference,p.status,
            p.version,p.created_at,p.updated_at,
            CASE WHEN p.status='SUCCEEDED' THEN p.amount ELSE 0 END captured_amount,
            COALESCE((SELECT SUM(r.amount) FROM payment_refunds r WHERE r.payment_intent_id=p.id AND r.status='SUCCEEDED'),0)
              + COALESCE((SELECT SUM(r.amount) FROM payment_return_refunds r WHERE r.payment_intent_id=p.id AND r.status='SUCCEEDED'),0) refunded_amount,
            COALESCE((SELECT SUM(r.amount) FROM payment_refunds r WHERE r.payment_intent_id=p.id AND r.status IN ('PENDING','PROCESSING')),0) pending_amount,
            (SELECT COUNT(*) FROM payment_refunds r WHERE r.payment_intent_id=p.id)
              + (SELECT COUNT(*) FROM payment_return_refunds r WHERE r.payment_intent_id=p.id) refund_count
            """;
    private static final String REFUND_UNION = """
            SELECT r.id,r.payment_intent_id,r.order_id,r.dispute_id,r.amount,r.currency,r.status,r.provider,
                   r.provider_reference,r.source,r.refund_type,r.reason_code,r.reason,r.initiated_by_admin_id,
                   r.initiated_by_admin_name,r.version,r.reconciliation_state,r.safe_failure_code,
                   r.safe_failure_summary,r.created_at,r.updated_at,r.completed_at
            FROM payment_refunds r
            UNION ALL
            SELECT r.id,r.payment_intent_id,r.order_id,NULL dispute_id,r.amount,r.currency,r.status,r.provider,
                   r.provider_reference,'BUSINESS_RETURN' source,'PARTIAL' refund_type,'RETURN_APPROVED' reason_code,
                   NULL reason,NULL initiated_by_admin_id,NULL initiated_by_admin_name,0 version,'IN_SYNC' reconciliation_state,
                   NULL safe_failure_code,NULL safe_failure_summary,r.completed_at created_at,r.completed_at updated_at,r.completed_at
            FROM payment_return_refunds r
            """;
    private final JdbcTemplate jdbc;
    public AdminFinanceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public PaymentSearchResult searchPayments(PaymentFilter f){StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object>a=new ArrayList<>();
        add(where,a,"p.id=?",f.paymentId());add(where,a,"p.buyer_id=?",f.buyerId());add(where,a,"p.status=?",f.status());add(where,a,"p.provider=?",f.provider());
        add(where,a,"p.id=?",f.resolvedPaymentId());
        if(f.businessId()!=null){where.append(" AND EXISTS(SELECT 1 FROM payment_intent_business_scopes s WHERE s.payment_intent_id=p.id AND s.business_id=?)");a.add(f.businessId());}
        if(f.query()!=null){where.append(" AND (p.id=? OR p.checkout_id=? OR p.provider_reference=?)");a.add(f.query());a.add(f.query());a.add(f.query());}
        if(f.refundStatus()!=null){where.append(" AND (EXISTS(SELECT 1 FROM payment_refunds r WHERE r.payment_intent_id=p.id AND r.status=?) OR EXISTS(SELECT 1 FROM payment_return_refunds rr WHERE rr.payment_intent_id=p.id AND rr.status=?))");a.add(f.refundStatus());a.add(f.refundStatus());}
        range(where,a,"p.created_at",f.createdFrom(),f.createdTo());
        if(f.amountMin()!=null){where.append(" AND p.amount>=?");a.add(f.amountMin());}if(f.amountMax()!=null){where.append(" AND p.amount<=?");a.add(f.amountMax());}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents p"+where,Long.class,a.toArray());
        List<Object>page=new ArrayList<>(a);page.add(f.size());page.add(f.page()*f.size());
        List<PaymentRow>rows=jdbc.query("SELECT "+PAYMENT_COLUMNS+" FROM payment_intents p"+where+" ORDER BY "+paymentSort(f.sort())+" LIMIT ? OFFSET ?",AdminFinanceRepository::paymentRow,page.toArray());
        return new PaymentSearchResult(rows,total==null?0:total);
    }
    // Locks the authoritative intent before reading derived refund totals so a waiter observes the winner's commit.
    public Optional<PaymentRow> payment(String id,boolean lock){if(lock&&jdbc.query("SELECT id FROM payment_intents WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id).isEmpty())return Optional.empty();return jdbc.query("SELECT "+PAYMENT_COLUMNS+" FROM payment_intents p WHERE p.id=?",AdminFinanceRepository::paymentRow,id).stream().findFirst();}
    public List<String> businessIds(String paymentId){return jdbc.query("SELECT business_id FROM payment_intent_business_scopes WHERE payment_intent_id=? ORDER BY scope_order",(r,n)->r.getString(1),paymentId);}
    public Map<String,List<String>> businessIds(Set<String> paymentIds){Map<String,List<String>> result=new LinkedHashMap<>();paymentIds.forEach(id->result.put(id,new ArrayList<>()));if(paymentIds.isEmpty())return result;String in=String.join(",",java.util.Collections.nCopies(paymentIds.size(),"?"));jdbc.query("SELECT payment_intent_id,business_id FROM payment_intent_business_scopes WHERE payment_intent_id IN ("+in+") ORDER BY payment_intent_id,scope_order",(org.springframework.jdbc.core.RowCallbackHandler)r->result.get(r.getString(1)).add(r.getString(2)),paymentIds.toArray());return result;}

    public RefundSearchResult searchRefunds(RefundFilter f){StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object>a=new ArrayList<>();
        add(where,a,"r.id=?",f.refundId());add(where,a,"r.payment_intent_id=?",f.paymentId());add(where,a,"r.order_id=?",f.orderId());add(where,a,"r.dispute_id=?",f.disputeId());add(where,a,"r.status=?",f.status());add(where,a,"r.provider=?",f.provider());
        if(f.query()!=null){where.append(" AND (r.id=? OR r.payment_intent_id=? OR r.order_id=? OR r.provider_reference=?)");for(int i=0;i<4;i++)a.add(f.query());}
        range(where,a,"r.created_at",f.createdFrom(),f.createdTo());if(f.amountMin()!=null){where.append(" AND r.amount>=?");a.add(f.amountMin());}if(f.amountMax()!=null){where.append(" AND r.amount<=?");a.add(f.amountMax());}
        String base="("+REFUND_UNION+") r";Long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+base+where,Long.class,a.toArray());List<Object>page=new ArrayList<>(a);page.add(f.size());page.add(f.page()*f.size());
        List<RefundRow>rows=jdbc.query("SELECT * FROM "+base+where+" ORDER BY "+refundSort(f.sort())+" LIMIT ? OFFSET ?",AdminFinanceRepository::refundRow,page.toArray());return new RefundSearchResult(rows,total==null?0:total);}
    public Optional<RefundRow> refund(String id){return jdbc.query("SELECT * FROM ("+REFUND_UNION+") r WHERE r.id=?",AdminFinanceRepository::refundRow,id).stream().findFirst();}
    public List<RefundRow> refunds(String paymentId){return jdbc.query("SELECT * FROM ("+REFUND_UNION+") r WHERE r.payment_intent_id=? ORDER BY r.created_at,r.id",AdminFinanceRepository::refundRow,paymentId);}
    public BigDecimal succeededBefore(String paymentId,Instant created,String refundId){return money(jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount),0) FROM (
              SELECT id,amount,created_at FROM payment_refunds WHERE payment_intent_id=? AND status='SUCCEEDED'
              UNION ALL SELECT id,amount,completed_at created_at FROM payment_return_refunds WHERE payment_intent_id=? AND status='SUCCEEDED'
            ) x WHERE x.created_at<? OR (x.created_at=? AND x.id<?)
            """,BigDecimal.class,paymentId,paymentId,Timestamp.from(created),Timestamp.from(created),refundId));}

    public Optional<CommandRow> command(String admin,String operation,String key){return jdbc.query("SELECT id,request_hash,state,refund_id FROM payment_admin_refund_commands WHERE admin_actor_id=? AND operation=? AND idempotency_key=?",(r,n)->new CommandRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),admin,operation,key).stream().findFirst();}
    public boolean insertCommand(String id,String admin,String payment,String operation,String key,String hash,Instant now){try{jdbc.update("INSERT INTO payment_admin_refund_commands(id,admin_actor_id,payment_intent_id,operation,idempotency_key,request_hash,state,created_at,expires_at) VALUES(?,?,?,?,?,?,'IN_PROGRESS',?,?)",id,admin,payment,operation,key,hash,Timestamp.from(now),Timestamp.from(now.plusSeconds(604800)));return true;}catch(DuplicateKeyException e){return false;}}
    public void completeCommand(String commandId,String refundId,Instant now){jdbc.update("UPDATE payment_admin_refund_commands SET state='COMPLETED',refund_id=?,completed_at=? WHERE id=?",refundId,Timestamp.from(now),commandId);}

    public void insertAdminRefund(String id,String payment,String operationId,String order,String dispute,String type,
            String reasonCode,String reason,String admin,String adminName,BigDecimal amount,String currency,String provider,
            String providerReference,String status,String reconciliation,String failureCode,String failureSummary,
            String correlation,String attemptId,String historyId,String outboxId,String payload,Instant now){
        Instant completed="SUCCEEDED".equals(status)?now:null;
        jdbc.update("""
                INSERT INTO payment_refunds(id,payment_intent_id,cancellation_request_id,order_id,dispute_id,source,
                  refund_type,reason_code,reason,initiated_by_admin_id,initiated_by_admin_name,idempotency_key,
                  amount,currency,provider,provider_reference,status,version,reconciliation_state,safe_failure_code,
                  safe_failure_summary,created_at,updated_at,completed_at)
                VALUES(?,?,?,?,?,'HUMAN_ADMIN',?,?,?,?,?,?, ?,?,?,?,?,0,?,?,?,?,?,?)
                """,id,payment,operationId,order,dispute,type,reasonCode,reason,admin,adminName,"admin-refund:"+operationId,
                amount,currency,provider,providerReference,status,reconciliation,failureCode,failureSummary,
                Timestamp.from(now),Timestamp.from(now),completed==null?null:Timestamp.from(completed));
        String outcome="REQUIRES_ATTENTION".equals(reconciliation)?"UNCERTAIN":status;
        jdbc.update("INSERT INTO payment_refund_attempts(id,refund_id,attempt_number,operation,outcome,provider_reference,safe_failure_code,safe_failure_summary,created_at) VALUES(?,?,1,?,?,?,?,?,?)",
                attemptId,id,type+"_REFUND",outcome,providerReference,failureCode,failureSummary,Timestamp.from(now));
        jdbc.update("INSERT INTO payment_refund_status_history(id,refund_id,from_status,to_status,reason_code,correlation_id,created_at) VALUES(?,?,NULL,?,?,?,?)",
                historyId,id,status,reasonCode,correlation,Timestamp.from(now));
        if("SUCCEEDED".equals(status))jdbc.update("""
                INSERT INTO payment_outbox_events(id,aggregate_type,aggregate_id,event_type,event_version,producer,
                  payload_json,correlation_id,causation_id,occurred_at,created_at)
                VALUES(?,'PAYMENT_INTENT',?,'payment.refunded',1,'payment-service',CAST(? AS JSON),?,?,?,?)
                """,outboxId,payment,payload,correlation,operationId,Timestamp.from(now),Timestamp.from(now));
    }
    public long touchPayment(String id,long expected,Instant now){int changed=jdbc.update("UPDATE payment_intents SET version=version+1,updated_at=? WHERE id=? AND version=?",Timestamp.from(now),id,expected);return changed==1?expected+1:-1;}
    public List<AttemptRow> attempts(String refundId){return jdbc.query("SELECT attempt_number,operation,outcome,provider_reference,safe_failure_code,safe_failure_summary,created_at FROM payment_refund_attempts WHERE refund_id=? ORDER BY attempt_number",(r,n)->new AttemptRow(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant()),refundId);}
    public List<HistoryRow> paymentHistory(String paymentId){return jdbc.query("SELECT id,from_status,to_status,reason_code,actor_scope,correlation_id,created_at FROM payment_status_history WHERE payment_intent_id=? ORDER BY created_at,id",(r,n)->new HistoryRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),null,r.getString(6),r.getTimestamp(7).toInstant()),paymentId);}
    public List<HistoryRow> refundHistory(String refundId){return jdbc.query("SELECT id,from_status,to_status,reason_code,correlation_id,created_at FROM payment_refund_status_history WHERE refund_id=? ORDER BY created_at,id",(r,n)->new HistoryRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),"SYSTEM",null,r.getString(5),r.getTimestamp(6).toInstant()),refundId);}

    private static PaymentRow paymentRow(ResultSet r,int n)throws SQLException{return new PaymentRow(r.getString("id"),r.getString("checkout_id"),r.getString("buyer_id"),r.getBigDecimal("amount"),r.getString("currency"),r.getString("provider"),r.getString("provider_reference"),r.getString("status"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getBigDecimal("captured_amount"),r.getBigDecimal("refunded_amount"),r.getBigDecimal("pending_amount"),r.getInt("refund_count"));}
    private static RefundRow refundRow(ResultSet r,int n)throws SQLException{return new RefundRow(r.getString("id"),r.getString("payment_intent_id"),r.getString("order_id"),r.getString("dispute_id"),r.getBigDecimal("amount"),r.getString("currency"),r.getString("status"),r.getString("provider"),r.getString("provider_reference"),r.getString("source"),r.getString("refund_type"),r.getString("reason_code"),r.getString("reason"),r.getString("initiated_by_admin_id"),r.getString("initiated_by_admin_name"),r.getLong("version"),r.getString("reconciliation_state"),r.getString("safe_failure_code"),r.getString("safe_failure_summary"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),instant(r.getTimestamp("completed_at")));}
    private static void add(StringBuilder w,List<Object>a,String sql,Object value){if(value!=null){w.append(" AND ").append(sql);a.add(value);}}
    private static void range(StringBuilder w,List<Object>a,String field,Instant from,Instant to){if(from!=null){w.append(" AND ").append(field).append(">=?");a.add(Timestamp.from(from));}if(to!=null){w.append(" AND ").append(field).append("<?");a.add(Timestamp.from(to));}}
    private static String paymentSort(String s){return switch(s){case"createdAt,asc"->"p.created_at ASC,p.id ASC";case"amount,desc"->"p.amount DESC,p.id DESC";case"amount,asc"->"p.amount ASC,p.id ASC";case"updatedAt,desc"->"p.updated_at DESC,p.id DESC";default->"p.created_at DESC,p.id DESC";};}
    private static String refundSort(String s){return switch(s){case"createdAt,asc"->"r.created_at ASC,r.id ASC";case"amount,desc"->"r.amount DESC,r.id DESC";case"amount,asc"->"r.amount ASC,r.id ASC";case"updatedAt,desc"->"r.updated_at DESC,r.id DESC";default->"r.created_at DESC,r.id DESC";};}
    private static BigDecimal money(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static Instant instant(Timestamp v){return v==null?null:v.toInstant();}

    public record PaymentFilter(String query,String paymentId,String resolvedPaymentId,String buyerId,String businessId,String status,String refundStatus,String provider,Instant createdFrom,Instant createdTo,BigDecimal amountMin,BigDecimal amountMax,int page,int size,String sort){}
    public record RefundFilter(String query,String refundId,String paymentId,String orderId,String disputeId,String status,String provider,Instant createdFrom,Instant createdTo,BigDecimal amountMin,BigDecimal amountMax,int page,int size,String sort){}
    public record PaymentSearchResult(List<PaymentRow> rows,long total){}
    public record RefundSearchResult(List<RefundRow> rows,long total){}
    public record PaymentRow(String id,String checkoutId,String buyerId,BigDecimal amount,String currency,String provider,String providerReference,String status,long version,Instant createdAt,Instant updatedAt,BigDecimal captured,BigDecimal refunded,BigDecimal pending,int refundCount){public BigDecimal refundable(){return captured.subtract(refunded).subtract(pending).max(BigDecimal.ZERO);}}
    public record RefundRow(String id,String paymentId,String orderId,String disputeId,BigDecimal amount,String currency,String status,String provider,String providerReference,String source,String refundType,String reasonCode,String reason,String adminId,String adminName,long version,String reconciliation,String failureCode,String failureSummary,Instant createdAt,Instant updatedAt,Instant completedAt){}
    public record CommandRow(String id,String hash,String state,String refundId){}
    public record AttemptRow(int number,String operation,String outcome,String providerReference,String failureCode,String failureSummary,Instant createdAt){}
    public record HistoryRow(String id,String previous,String next,String reasonCode,String actorType,String actorId,String correlation,Instant at){}
}
