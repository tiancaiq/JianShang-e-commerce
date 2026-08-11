package com.msb.ecom.payment_service.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.PaymentRefundProperties;
import com.msb.ecom.payment_service.dto.*;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.*;
import com.msb.ecom.payment_service.repository.*;
import org.springframework.http.HttpStatus;import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;import java.time.Clock;import java.time.Instant;import java.util.*;
@Service
public class PaymentReturnRefundService{
 private final PaymentRefundProperties props;private final InternalPaymentAuthenticator auth;private final PaymentRefundRepository intents;
 private final PaymentReturnRefundRepository refunds;private final Map<String,PaymentProvider> providers;private final PaymentUlidGenerator ids;
 private final ObjectMapper mapper;private final TransactionTemplate tx;private final Clock clock=Clock.systemUTC();
 public PaymentReturnRefundService(PaymentRefundProperties props,InternalPaymentAuthenticator auth,PaymentRefundRepository intents,
  PaymentReturnRefundRepository refunds,List<PaymentProvider> providers,PaymentUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager){
  this.props=props;this.auth=auth;this.intents=intents;this.refunds=refunds;this.providers=providers.stream().collect(java.util.stream.Collectors.toMap(PaymentProvider::providerName,p->p));this.ids=ids;this.mapper=mapper;this.tx=new TransactionTemplate(manager);}
 // Uses the existing deterministic provider while limiting the refund to the immutable group merchandise amount supplied by Order Service.
 public ReturnRefundResponse refund(String token,String intentId,String key,CreateReturnRefundRequest req,String correlation){
  auth.requireAuthenticated(token);if(!props.enabled())throw err(HttpStatus.NOT_FOUND,"PAYMENT_REFUND_NOT_AVAILABLE","Payment refunds are unavailable.");
  if(req==null||blank(req.returnId())||blank(req.orderId())||blank(req.businessOrderId())||req.amount()==null||req.amount().signum()<=0||!"USD".equals(req.currency())||blank(key))
   throw err(HttpStatus.BAD_REQUEST,"PAYMENT_RETURN_REFUND_INVALID","A complete return refund command is required.");
  var keyed=refunds.findByKey(key).orElse(null);if(keyed!=null)return replay(keyed,intentId,req);
  return tx.execute(s->execute(intentId,key,req,correlation==null?ids.next():correlation,clock.instant()));
 }
 private ReturnRefundResponse execute(String intentId,String key,CreateReturnRefundRequest req,String correlation,Instant now){
  var existing=refunds.findByReturn(req.returnId()).orElse(null);if(existing!=null)return replay(existing,intentId,req);
  var intent=intents.lockIntent(intentId).orElseThrow(()->err(HttpStatus.NOT_FOUND,"PAYMENT_INTENT_NOT_FOUND","Payment intent was not found."));
  BigDecimal alreadyRefunded=refunds.completedAmount(intentId);
  if(!"SUCCEEDED".equals(intent.status())||!req.currency().equals(intent.currency())
    ||req.amount().compareTo(intent.amount())>0
    ||alreadyRefunded.add(req.amount()).compareTo(intent.amount())>0)
   throw err(HttpStatus.CONFLICT,"PAYMENT_RETURN_REFUND_STATE_CONFLICT","Return refund exceeds the succeeded payment boundary.");
  var provider=providers.get(intent.provider());if(provider==null||!DeterministicFakePaymentProvider.PROVIDER.equals(intent.provider()))
   throw err(HttpStatus.CONFLICT,"PAYMENT_REFUND_PROVIDER_UNSUPPORTED","Provider does not support bounded demo returns.");
  String id=ids.next();var result=provider.refund(new PaymentRefundCommand(id,intentId,req.orderId(),req.returnId(),req.amount(),req.currency()));
  if(!"SUCCEEDED".equals(result.status()))throw new IllegalStateException("Deterministic return refund failed.");
  try{String payload=mapper.writeValueAsString(Map.of("refundId",id,"returnId",req.returnId(),"orderId",req.orderId(),
   "businessOrderId",req.businessOrderId(),"amount",req.amount(),"currency",req.currency(),"status","SUCCEEDED","occurredAt",now));
   refunds.insert(id,intentId,req.returnId(),req.orderId(),req.businessOrderId(),key,req.amount(),req.currency(),intent.provider(),result.providerReference(),ids.next(),ids.next(),payload,correlation,now);}
  catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  return new ReturnRefundResponse(id,req.returnId(),req.orderId(),req.businessOrderId(),req.amount(),req.currency(),"SUCCEEDED",now,"Local demo refund","No real money is moved.");
 }
 private ReturnRefundResponse replay(PaymentReturnRefundRepository.Record r,String intent,CreateReturnRefundRequest q){if(!r.paymentIntentId().equals(intent)||!r.returnId().equals(q.returnId())||!r.orderId().equals(q.orderId())||!r.businessOrderId().equals(q.businessOrderId())||r.amount().compareTo(q.amount())!=0||!r.currency().equals(q.currency()))throw err(HttpStatus.CONFLICT,"PAYMENT_RETURN_REFUND_IDEMPOTENCY_CONFLICT","Return refund conflicts with an existing command.");return new ReturnRefundResponse(r.id(),r.returnId(),r.orderId(),r.businessOrderId(),r.amount(),r.currency(),"SUCCEEDED",r.completedAt(),"Local demo refund","No real money is moved.");}
 private boolean blank(String v){return v==null||v.isBlank();}private PaymentIntentException err(HttpStatus s,String c,String m){return new PaymentIntentException(s,c,m);}
}
