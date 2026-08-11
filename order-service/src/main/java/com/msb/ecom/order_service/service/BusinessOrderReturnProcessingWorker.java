package com.msb.ecom.order_service.service;
import com.fasterxml.jackson.databind.ObjectMapper;import com.msb.ecom.order_service.config.BusinessOrderReturnProperties;import com.msb.ecom.order_service.repository.BusinessOrderReturnRepository;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;import java.util.Map;
@Component
public class BusinessOrderReturnProcessingWorker{
 private static final Logger log=LoggerFactory.getLogger(BusinessOrderReturnProcessingWorker.class);private final BusinessOrderReturnProperties props;private final BusinessOrderReturnRepository repo;
 private final ReturnInventoryClient inventory;private final ReturnPaymentClient payment;private final CheckoutUlidGenerator ids;private final ObjectMapper mapper;private final TransactionTemplate tx;private final Clock clock=Clock.systemUTC();
 public BusinessOrderReturnProcessingWorker(BusinessOrderReturnProperties props,BusinessOrderReturnRepository repo,ReturnInventoryClient inventory,ReturnPaymentClient payment,CheckoutUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager){this.props=props;this.repo=repo;this.inventory=inventory;this.payment=payment;this.ids=ids;this.mapper=mapper;this.tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelayString="${business-orders.return-worker-interval-ms:500}") public void process(){if(!props.enabled()||!props.processingEnabled())return;for(var r:repo.due(clock.instant(),props.batchSize())){if(!repo.claimProcessing(r.id()))continue;try{
   if("RESTOCK_SELLABLE".equals(r.disposition()))inventory.restock(r.reservationId(),r.orderId(),r.id(),r.businessId(),"return-restock:"+r.id());
   var refund=payment.refund(r.paymentIntentId(),r.orderId(),r.businessOrderId(),r.id(),groupMerchandiseAmount(r),r.currency(),"return-refund:"+r.id());
   var now=clock.instant();String event=ids.next();String payload=json(Map.of("returnId",r.id(),"orderId",r.orderId(),"businessOrderId",r.businessOrderId(),"businessId",r.businessId(),"refundId",refund.refundId(),"amount",refund.amount(),"currency",r.currency(),"status","RETURN_COMPLETED","occurredAt",now));
   tx.executeWithoutResult(s->repo.complete(r.id(),refund.refundId(),refund.amount(),r.id(),now,ids.next(),ids.next(),event,payload));
  }catch(RuntimeException e){repo.retry(r.id(),clock.instant().plus(props.retryDelay()),"RETURN_DEPENDENCY_UNAVAILABLE");log.warn("Return processing deferred returnRef={}",r.id());}}}
 // The aggregate captured business-order subtotal at request time through the immutable Order snapshot.
 private java.math.BigDecimal groupMerchandiseAmount(BusinessOrderReturnRepository.ReturnRecord r){return repo.lockBusinessGroup(r.businessId(),r.businessOrderId()).orElseThrow().refundAmount();}
 private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
}
