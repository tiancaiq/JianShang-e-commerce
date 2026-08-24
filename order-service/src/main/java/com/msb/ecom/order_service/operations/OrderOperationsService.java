package com.msb.ecom.order_service.operations;

import com.msb.ecom.order_service.config.CheckoutProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class OrderOperationsService {
    private final OrderOperationsRepository repository;
    private final CheckoutProperties checkout;
    private final Clock clock = Clock.systemUTC();
    @Value("${order.notification-outbox.enabled:false}") private boolean notificationEnabled;
    @Value("${order.notification-outbox.worker-enabled:false}") private boolean notificationWorkerEnabled;

    public OrderOperationsService(OrderOperationsRepository repository, CheckoutProperties checkout) {
        this.repository = repository;
        this.checkout = checkout;
    }

    public OrderOperationsContracts.Snapshot snapshot(){return new OrderOperationsContracts.Snapshot("ORDER",true,
            "Order operational signals loaded.",repository.jobs(),repository.outbox(),List.of(),List.of(),null,List.of(
            feature("CART_RECONCILIATION","Purchased cart reconciliation",checkout.enabled(),"Reconciles purchased cart lines after order confirmation."),
            feature("ORDER_NOTIFICATION_OUTBOX","Order notification outbox",notificationEnabled&&notificationWorkerEnabled,"Delivers order notifications through the existing outbox worker.")));}

    @Transactional
    public OrderOperationsContracts.Action action(OrderOperationsContracts.ActionRequest r){if(r==null)throw new IllegalArgumentException("Action is required.");return switch(r.commandType()){
        case"RETRY_JOB"->job(r);case"RETRY_OUTBOX"->outbox(r);default->unsupported(r);};}
    private OrderOperationsContracts.Action job(OrderOperationsContracts.ActionRequest r){var job=repository.job(r.targetId()).orElse(null);if(job==null)return unsupported(r);boolean allowed=job.retryable();if(!r.dryRun()&&allowed)allowed=repository.retryJob(job.jobId(),clock.instant());return action(r,job.status(),allowed,"JOB_NOT_RETRYABLE",List.of("Order cart reconciliation worker"),"Queue the pending reconciliation for another worker attempt.","Order state is not directly edited.","Order accepted the job retry request.");}
    private OrderOperationsContracts.Action outbox(OrderOperationsContracts.ActionRequest r){var event=repository.outbox(r.targetId()).orElse(null);if(event==null)return unsupported(r);boolean allowed=event.retryable()&&notificationEnabled&&notificationWorkerEnabled;if(!r.dryRun()&&allowed)allowed=repository.retryOutbox(event.eventId(),clock.instant());return action(r,event.status(),allowed,"OUTBOX_EVENT_NOT_RETRYABLE",List.of("Order notification outbox worker","Notification consumer deduplication"),"Schedule the existing event for delivery.","Order and fulfillment state remain unchanged.","Order accepted the outbox retry request.");}
    private OrderOperationsContracts.Action action(OrderOperationsContracts.ActionRequest r,String state,boolean allowed,String denial,List<String> dependencies,String operation,String impact,String summary){return new OrderOperationsContracts.Action("ORDER",r.targetType(),r.targetId(),r.commandType(),state,allowed,allowed?null:denial,dependencies,List.of("The retry request does not mean delivery or reconciliation completed."),operation,impact,r.dryRun()?null:allowed?"ACCEPTED":"NOT_RETRYABLE",allowed?summary:"The target is not currently retryable.");}
    private OrderOperationsContracts.Action unsupported(OrderOperationsContracts.ActionRequest r){return new OrderOperationsContracts.Action("ORDER",r.targetType(),r.targetId(),r.commandType(),"UNKNOWN",false,"SYSTEM_OPERATION_NOT_SUPPORTED",List.of(),List.of(),"No operation will run.","No customer data is changed.",r.dryRun()?null:"NOT_RETRYABLE","The Order operation is unavailable.");}
    private OrderOperationsContracts.Feature feature(String key,String name,boolean enabled,String summary){return new OrderOperationsContracts.Feature(key,name,enabled?"ENABLED":"DISABLED","SERVICE","Order runtime configuration",null,false,summary);}
}
