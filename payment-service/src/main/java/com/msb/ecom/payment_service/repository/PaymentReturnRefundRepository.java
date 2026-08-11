package com.msb.ecom.payment_service.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;import java.sql.Timestamp;import java.time.Instant;import java.util.Optional;
@Repository
public class PaymentReturnRefundRepository{
 private final JdbcTemplate jdbc;public PaymentReturnRefundRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<Record> findByReturn(String id){return query("return_id",id);}public Optional<Record> findByKey(String id){return query("idempotency_key",id);}
 public BigDecimal completedAmount(String intent){BigDecimal amount=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment_return_refunds WHERE payment_intent_id=?",BigDecimal.class,intent);return amount==null?BigDecimal.ZERO:amount;}
 private Optional<Record> query(String field,String value){return jdbc.query("SELECT * FROM payment_return_refunds WHERE "+field+"=?",(rs,n)->new Record(
  rs.getString("id"),rs.getString("payment_intent_id"),rs.getString("return_id"),rs.getString("order_id"),rs.getString("business_order_id"),
  rs.getString("idempotency_key"),rs.getBigDecimal("amount"),rs.getString("currency"),rs.getString("provider"),rs.getString("provider_reference"),
  rs.getTimestamp("completed_at").toInstant()),value).stream().findFirst();}
 public void insert(String id,String intent,String returnId,String order,String group,String key,BigDecimal amount,String currency,String provider,
  String providerRef,String attemptId,String outboxId,String payload,String correlation,Instant now){
  jdbc.update("INSERT INTO payment_return_refunds(id,payment_intent_id,return_id,order_id,business_order_id,idempotency_key,amount,currency,provider,provider_reference,status,completed_at) "
   + "VALUES (?,?,?,?,?,?,?,?,?,?,'SUCCEEDED',?)",id,intent,returnId,order,group,key,amount,currency,provider,providerRef,Timestamp.from(now));
  jdbc.update("INSERT INTO payment_return_refund_attempts(id,refund_id,attempt_number,outcome,created_at) VALUES (?,?,1,'SUCCEEDED',?)",attemptId,id,Timestamp.from(now));
  jdbc.update("INSERT INTO payment_outbox_events(id,aggregate_type,aggregate_id,event_type,event_version,producer,payload_json,correlation_id,causation_id,occurred_at,created_at) "
   + "VALUES (?,'PAYMENT_INTENT',?,'payment.refunded',1,'payment-service',CAST(? AS JSON),?,?,?,?)",outboxId,intent,payload,correlation,returnId,Timestamp.from(now),Timestamp.from(now));}
 public record Record(String id,String paymentIntentId,String returnId,String orderId,String businessOrderId,String key,BigDecimal amount,String currency,String provider,String providerReference,Instant completedAt){}
}
