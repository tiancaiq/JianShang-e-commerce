package com.msb.ecom.payment_service.operations;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Repository;import java.sql.Timestamp;import java.time.Instant;import java.util.*;
@Repository
public class PaymentOperationsRepository {private final JdbcTemplate jdbc;public PaymentOperationsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<PaymentOperationsContracts.Outbox> outbox(Instant now,int max){return jdbc.query("""
  select id,aggregate_type,aggregate_id,event_type,published_at,retry_count,attempt_count,next_attempt_at,last_attempt_at,claim_token,claim_expires_at,last_error_code,last_error_message,terminal_failure_at,correlation_id,created_at
  from payment_outbox_events where published_at is null or attempt_count>0 order by created_at desc limit 100
  """,(r,n)->{Instant published=i(r.getTimestamp("published_at")),terminal=i(r.getTimestamp("terminal_failure_at")),claimExpiry=i(r.getTimestamp("claim_expires_at"));int attempts=r.getInt("attempt_count");boolean claimed=r.getString("claim_token")!=null&&claimExpiry!=null&&claimExpiry.isAfter(now);String status=published!=null?"SENT":terminal!=null?"DEAD_LETTER":attempts>0?"FAILED":"PENDING";return new PaymentOperationsContracts.Outbox(r.getString("id"),"PAYMENT",r.getString("event_type"),r.getString("aggregate_type"),r.getString("aggregate_id"),status,attempts,r.getTimestamp("created_at").toInstant(),i(r.getTimestamp("last_attempt_at")),i(r.getTimestamp("next_attempt_at")),r.getString("correlation_id"),r.getString("last_error_code"),safe(r.getString("last_error_code"),r.getString("last_error_message")),"FAILED".equals(status)&&!claimed&&attempts<max);});}
 public List<PaymentOperationsContracts.Reconciliation> reconciliation(){return jdbc.query("""
  select r.id,r.payment_intent_id,r.status,r.reconciliation_state,r.safe_failure_code,r.safe_failure_summary,r.updated_at,(select count(*) from payment_refund_attempts a where a.refund_id=r.id) attempt_count
  from payment_refunds r where r.reconciliation_state='REQUIRES_ATTENTION' or r.status='PROCESSING' order by r.updated_at desc limit 100
  """,(r,n)->new PaymentOperationsContracts.Reconciliation("REFUND:"+r.getString("id"),"REFUND_PROVIDER_RECONCILIATION","PAYMENT","REFUND",r.getString("id"),r.getString("status"),"UNCONFIRMED","HIGH","REQUIRES_ATTENTION",r.getTimestamp("updated_at").toInstant(),r.getInt("attempt_count"),r.getString("safe_failure_summary")!=null?r.getString("safe_failure_summary"):"Refund provider and local outcome require an operator review.",false,null));}
 public Optional<PaymentOperationsContracts.Outbox> outbox(String id,Instant now,int max){return outbox(now,max).stream().filter(v->id.equals(v.eventId())).findFirst();}
 public boolean retryOutbox(String id,Instant now){return jdbc.update("""
  update payment_outbox_events set next_attempt_at=?,claim_token=null,claimed_at=null,claim_expires_at=null
  where id=? and published_at is null and terminal_failure_at is null and attempt_count>0 and (claim_token is null or claim_expires_at<=?)
  """,Timestamp.from(now),id,Timestamp.from(now))==1;}
 private static Instant i(Timestamp v){return v==null?null:v.toInstant();}
 private static String safe(String code,String message){if(code==null)return null;return message==null||message.isBlank()?"The dispatcher recorded "+code+". Raw transport details are not exposed.":message;}
}
