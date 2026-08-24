package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.*;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentRefundCommand;
import com.msb.ecom.payment_service.repository.AdminFinanceRepository;
import com.msb.ecom.payment_service.repository.AdminFinanceRepository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminFinanceService {
    public static final String FINANCE_READ="admin.finance.read",REFUND_READ="admin.refund.read",REFUND_EXECUTE="admin.refund.execute",PII_READ="admin.user.pii.read";
    private static final Pattern ULID=Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}"),KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Set<String> PAYMENT_STATUSES=Set.of("CREATED","REQUIRES_ACTION","PROCESSING","SUCCEEDED","FAILED");
    private static final Set<String> REFUND_STATUSES=Set.of("PENDING","PROCESSING","SUCCEEDED","FAILED");
    private static final Set<String> TYPES=Set.of("FULL","PARTIAL");
    private static final Set<String> REASONS=Set.of("DISPUTE_RESOLUTION","ORDER_CANCELLATION","ITEM_NOT_RECEIVED","ITEM_NOT_AS_DESCRIBED","DAMAGED_ITEM","COUNTERFEIT","GOODWILL","CUSTOMER_REMEDIATION","OPERATIONAL_CORRECTION","OTHER");
    private static final Set<String> SORTS=Set.of("createdAt,desc","createdAt,asc","updatedAt,desc","amount,desc","amount,asc");
    private final CurrentActorProvider actors;private final AdminFinanceAuthorizationClient authorization;
    private final AdminFinanceOrderClient orders;private final AdminFinanceGovernanceClient governance;
    private final AdminFinanceRepository repository;
    private final Map<String,PaymentProvider> providers;private final PaymentUlidGenerator ids;private final ObjectMapper mapper;
    private final TransactionTemplate transactions;private final Clock clock;

    @Autowired
    public AdminFinanceService(CurrentActorProvider actors,AdminFinanceAuthorizationClient authorization,
            AdminFinanceOrderClient orders,AdminFinanceGovernanceClient governance,
            AdminFinanceRepository repository,List<PaymentProvider> providers,
            PaymentUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager){
        this(actors,authorization,orders,governance,repository,providers,ids,mapper,manager,Clock.systemUTC());}
    AdminFinanceService(CurrentActorProvider actors,AdminFinanceAuthorizationClient authorization,
            AdminFinanceOrderClient orders,AdminFinanceRepository repository,List<PaymentProvider> providers,
            PaymentUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager,Clock clock){
        this(actors,authorization,orders,(token,paymentId,request,preview,key)->new RefundApproval(
                "APPROVAL_NOT_REQUIRED",null,false,"Governance approval is not required."),repository,
                providers,ids,mapper,manager,clock);}
    AdminFinanceService(CurrentActorProvider actors,AdminFinanceAuthorizationClient authorization,
            AdminFinanceOrderClient orders,AdminFinanceGovernanceClient governance,
            AdminFinanceRepository repository,List<PaymentProvider> providers,
            PaymentUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager,Clock clock){this.actors=actors;this.authorization=authorization;
        this.orders=orders;this.governance=governance;this.repository=repository;this.providers=providers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(PaymentProvider::providerName,p->p));
        this.ids=ids;this.mapper=mapper;this.transactions=new TransactionTemplate(manager);this.clock=clock;}

    public Page<PaymentSummary> searchPayments(String q,String orderId,String buyerId,String businessId,String status,
            String refundStatus,String provider,Instant from,Instant to,BigDecimal min,BigDecimal max,int page,int size,String sort){
        Admin admin=require(FINANCE_READ);validateRange(from,to,min,max);String resolved=null;if(clean(orderId)!=null)resolved=orders.order(requiredId(orderId,"Order ID")).map(AdminFinanceOrderClient.Context::paymentIntentId).orElse("NOT_FOUND");
        int p=Math.max(0,page),s=Math.max(1,Math.min(size,100));String so=sort(sort);
        PaymentSearchResult result=repository.searchPayments(new PaymentFilter(clean(q),null,resolved,optionalId(buyerId,"Buyer ID"),optionalId(businessId,"Business ID"),optionalEnum(status,PAYMENT_STATUSES,"Payment status"),optionalEnum(refundStatus,REFUND_STATUSES,"Refund status"),text(provider,64),from,to,money(min),money(max),p,s,so));
        Set<String> paymentIds=result.rows().stream().map(PaymentRow::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));Map<String,AdminFinanceOrderClient.Context> contexts=orders.contexts(paymentIds);Map<String,List<String>> scopedBusinesses=repository.businessIds(paymentIds);
        Set<String> users=result.rows().stream().map(PaymentRow::buyerId).collect(java.util.stream.Collectors.toSet());Set<String> businesses=new LinkedHashSet<>();scopedBusinesses.values().forEach(businesses::addAll);
        AdminFinanceAuthorizationClient.Labels labels=authorization.labels(admin.actor().accessToken(),users,businesses);
        Map<String,String> userLabels=new LinkedHashMap<>();labels.users().forEach(v->userLabels.put(v.id(),v.displayName()));Map<String,String> businessLabels=new LinkedHashMap<>();labels.businesses().forEach(v->businessLabels.put(v.id(),v.storeName()==null?v.legalName():v.storeName()));
        List<PaymentSummary> content=result.rows().stream().map(r->summary(r,scopedBusinesses.getOrDefault(r.id(),List.of()),contexts.get(r.id()),userLabels,businessLabels)).toList();return new Page<>(content,p,s,result.total(),pages(result.total(),s),so);}

    public PaymentDetail payment(String raw){Admin admin=require(FINANCE_READ);String id=requiredId(raw,"Payment ID");PaymentRow row=repository.payment(id,false).orElseThrow(this::paymentNotFound);AdminFinanceOrderClient.Context context=orders.contexts(Set.of(id)).get(id);
        Set<String> businessIds=new LinkedHashSet<>(repository.businessIds(id));AdminFinanceAuthorizationClient.Labels labels=authorization.labels(admin.actor().accessToken(),Set.of(row.buyerId()),businessIds);
        Map<String,String> users=new LinkedHashMap<>();labels.users().forEach(v->users.put(v.id(),v.displayName()));Map<String,String> businesses=new LinkedHashMap<>();labels.businesses().forEach(v->businesses.put(v.id(),v.storeName()==null?v.legalName():v.storeName()));
        PaymentSummary summary=summary(row,List.copyOf(businessIds),context,users,businesses);List<RefundSummary> refunds=admin.access().has(REFUND_READ)?repository.refunds(id).stream().map(this::refundSummary).toList():List.of();
        List<DisputeContext> disputes=context==null?List.of():context.disputes().stream().map(d->dispute(d,row)).toList();
        Capabilities capabilities=capabilities(admin.access(),row,null);if(context==null)capabilities=new Capabilities(capabilities.canReadFinance(),capabilities.canReadRefund(),false,false,capabilities.canViewPii(),false,false,true,true,"Order context is unavailable; refund execution cannot be verified.");
        return new PaymentDetail(summary,orderContext(context,businesses),refunds,disputes,paymentTimeline(row,repository.refunds(id)),capabilities);}

    public Page<RefundSummary> searchRefunds(String q,String refundId,String paymentId,String orderId,String disputeId,String status,String provider,
            Instant from,Instant to,BigDecimal min,BigDecimal max,int page,int size,String sort){require(REFUND_READ);validateRange(from,to,min,max);int p=Math.max(0,page),s=Math.max(1,Math.min(size,100));String so=sort(sort);
        RefundSearchResult result=repository.searchRefunds(new RefundFilter(clean(q),optionalId(refundId,"Refund ID"),optionalId(paymentId,"Payment ID"),optionalId(orderId,"Order ID"),optionalId(disputeId,"Dispute ID"),optionalEnum(status,REFUND_STATUSES,"Refund status"),text(provider,64),from,to,money(min),money(max),p,s,so));
        return new Page<>(result.rows().stream().map(this::refundSummary).toList(),p,s,result.total(),pages(result.total(),s),so);}

    public RefundDetail refund(String raw){Admin admin=require(REFUND_READ);RefundRow value=repository.refund(requiredId(raw,"Refund ID")).orElseThrow(this::refundNotFound);PaymentRow payment=repository.payment(value.paymentId(),false).orElseThrow(this::paymentNotFound);
        List<AttemptRow> attempts=repository.attempts(value.id());RefundAttempt latest=attempts.isEmpty()?("BUSINESS_RETURN".equals(value.source())?new RefundAttempt(1,"PARTIAL_REFUND","SUCCEEDED",value.providerReference(),null,null,value.completedAt()):null):attempt(attempts.getLast());
        BigDecimal before=repository.succeededBefore(value.paymentId(),value.createdAt(),value.id());return new RefundDetail(refundSummary(value),payment.amount(),payment.captured(),before,payment.refunded(),payment.refundable(),
                attempts.isEmpty()&&"BUSINESS_RETURN".equals(value.source())?1:attempts.size(),latest,value.reconciliation(),value.failureCode(),value.failureSummary(),refundTimeline(value),capabilities(admin.access(),payment,value));}

    public RefundPreview preview(String paymentId,RefundRequest request){Admin admin=require(REFUND_EXECUTE);Validated input=validatedRequest(request,false);PaymentRow payment=repository.payment(requiredId(paymentId,"Payment ID"),false).orElseThrow(this::paymentNotFound);AdminFinanceOrderClient.Context context=orders.contexts(Set.of(payment.id())).get(payment.id());return preview(payment,context,input);}

    // Evaluates governance before entering the provider command; an approval response never mutates Payment state.
    public GovernedRefundResult submit(String paymentId,RefundRequest request,String correlation){
        Admin admin=require(REFUND_EXECUTE);Validated input=validatedRequest(request,true);
        String id=requiredId(paymentId,"Payment ID");
        PaymentRow payment=repository.payment(id,false).orElseThrow(this::paymentNotFound);
        AdminFinanceOrderClient.Context context=orders.contexts(Set.of(id)).get(id);
        RefundPreview impact=preview(payment,context,input);
        if(!impact.allowed())throw conflict(codeForDenial(impact.denialReason()),impact.denialReason());
        RefundRequest normalized=new RefundRequest(input.type(),impact.requestedAmount(),impact.currency(),
                input.reasonCode(),input.reason(),input.disputeId(),input.version(),input.key());
        RefundApproval decision=governance.evaluate(admin.actor().accessToken(),id,normalized,impact,input.key());
        if(decision.approvalRequired())return new GovernedRefundResult(true,null,decision);
        return new GovernedRefundResult(false,execute(id,normalized,correlation),decision);
    }

    // Revalidates the same preview under the Payment row lock and invokes only the existing provider refund command.
    public RefundExecution execute(String paymentId,RefundRequest request,String correlation){Admin admin=require(REFUND_EXECUTE);Validated input=validatedRequest(request,true);String id=requiredId(paymentId,"Payment ID");String hash=hash(id+"|"+input.normalized());CommandRow replay=repository.command(admin.id(),"EXECUTE_REFUND",input.key()).orElse(null);if(replay!=null)return replay(replay,hash);
        AdminFinanceOrderClient.Context context=orders.contexts(Set.of(id)).get(id);Instant now=clock.instant();String safeCorrelation=clean(correlation)==null?ids.next():correlation;
        return transactions.execute(status->{PaymentRow current=repository.payment(id,true).orElseThrow(this::paymentNotFound);String commandId=ids.next();if(!repository.insertCommand(commandId,admin.id(),id,"EXECUTE_REFUND",input.key(),hash,now))return replay(repository.command(admin.id(),"EXECUTE_REFUND",input.key()).orElseThrow(),hash);
            RefundPreview impact=preview(current,context,input);if(!impact.allowed())throw conflict(codeForDenial(impact.denialReason()),impact.denialReason());
            PaymentProvider provider=providers.get(current.provider());if(provider==null)throw unavailable("REFUND_PROVIDER_UNAVAILABLE","The configured refund provider is unavailable.");String refundId=ids.next(),providerReference=null,resultStatus="FAILED",reconciliation="IN_SYNC",failureCode=null,failureSummary=null;
            try{var result=provider.refund(new PaymentRefundCommand(refundId,id,impact.orderId(),commandId,impact.requestedAmount(),impact.currency()));providerReference=result.providerReference();if("SUCCEEDED".equals(result.status())&&providerReference!=null)resultStatus="SUCCEEDED";else{failureCode="REFUND_PROVIDER_FAILED";failureSummary="The refund provider declined the operation.";}}
            catch(RuntimeException e){reconciliation="REQUIRES_ATTENTION";failureCode="REFUND_PROVIDER_STATE_UNCERTAIN";failureSummary="The provider outcome could not be confirmed. Do not retry automatically.";}
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("refundId",refundId);payload.put("paymentIntentId",id);payload.put("orderId",impact.orderId());payload.put("disputeId",impact.disputeId());payload.put("amount",impact.requestedAmount());payload.put("currency",impact.currency());payload.put("status",resultStatus);payload.put("occurredAt",now);
            repository.insertAdminRefund(refundId,id,commandId,impact.orderId(),impact.disputeId(),input.type(),input.reasonCode(),input.reason(),admin.id(),admin.name(),impact.requestedAmount(),impact.currency(),current.provider(),providerReference,resultStatus,reconciliation,failureCode,failureSummary,safeCorrelation,ids.next(),ids.next(),ids.next(),json(payload),now);
            long paymentVersion=repository.touchPayment(id,current.version(),now);if(paymentVersion<0)throw versionConflict();repository.completeCommand(commandId,refundId,now);
            return new RefundExecution(refundId,id,impact.orderId(),impact.disputeId(),impact.requestedAmount(),impact.currency(),resultStatus,reconciliation,paymentVersion,0,false,now,"SUCCEEDED".equals(resultStatus)?now:null);});}

    private RefundExecution replay(CommandRow command,String hash){if(!command.hash().equals(hash))throw conflict("REFUND_IDEMPOTENCY_CONFLICT","The idempotency key was reused with changed refund content.");if(!"COMPLETED".equals(command.state())||command.refundId()==null)throw conflict("REFUND_ALREADY_IN_PROGRESS","The same refund command is still in progress.");RefundRow r=repository.refund(command.refundId()).orElseThrow(this::refundNotFound);PaymentRow p=repository.payment(r.paymentId(),false).orElseThrow(this::paymentNotFound);return new RefundExecution(r.id(),r.paymentId(),r.orderId(),r.disputeId(),r.amount(),r.currency(),r.status(),r.reconciliation(),p.version(),r.version(),true,r.createdAt(),r.completedAt());}
    private RefundPreview preview(PaymentRow p,AdminFinanceOrderClient.Context context,Validated in){if(p.version()!=in.version())throw versionConflict();String orderId=context==null?null:context.orderId();List<String>warnings=new ArrayList<>();String denial=null;BigDecimal requested;
        if(context==null)denial="Order context is unavailable for refund validation.";if(!"SUCCEEDED".equals(p.status()))denial="Only a succeeded payment can be refunded.";if(p.refundable().signum()<=0)denial="No refundable amount remains.";
        if("FULL".equals(in.type()))requested=p.refundable();else{requested=in.amount();if(requested==null||requested.signum()<=0)denial="Partial refund amount must be greater than zero.";if(!p.currency().equals(in.currency()))denial="Refund currency must match the payment currency.";if(requested!=null&&requested.compareTo(p.refundable())>0)denial="Requested refund exceeds the currently refundable amount.";}
        String comparison="No dispute recommendation linked.";if(in.disputeId()!=null){if(context==null)denial="Order context is unavailable for dispute validation.";AdminFinanceOrderClient.Dispute d=context==null?null:context.disputes().stream().filter(v->v.disputeId().equals(in.disputeId())).findFirst().orElse(null);if(d==null)denial="The dispute is not linked to this payment.";else{comparison="Recommended "+d.recommendedRefundAmount()+" "+d.currency()+"; requested "+requested+" "+p.currency()+".";if(!p.currency().equals(d.currency())||requested==null||requested.compareTo(d.recommendedRefundAmount())!=0)denial="The requested refund does not match the dispute recommendation.";if(d.recommendedRefundAmount().compareTo(p.refundable())>0)denial="The dispute recommendation exceeds the currently refundable amount.";}}
        if(p.pending().signum()>0)warnings.add("An in-flight refund already reduces the currently refundable amount.");boolean allowed=denial==null;BigDecimal projected=requested==null?p.refunded():p.refunded().add(requested);BigDecimal remaining=requested==null?p.refundable():p.refundable().subtract(requested).max(BigDecimal.ZERO);
        return new RefundPreview(p.id(),orderId,in.disputeId(),in.type(),requested,p.currency(),p.captured(),p.refunded(),p.pending(),p.refundable(),projected,remaining,p.status(),comparison,warnings,allowed,denial,p.version());}

    private PaymentSummary summary(PaymentRow r,List<String> ids,AdminFinanceOrderClient.Context context,Map<String,String> users,Map<String,String> labels){return new PaymentSummary(r.id(),r.checkoutId(),context==null?null:context.orderId(),r.buyerId(),users.get(r.buyerId()),ids,ids.stream().map(v->labels.getOrDefault(v,v)).toList(),r.provider(),r.providerReference(),r.status(),r.amount(),r.captured(),r.refunded(),r.pending(),r.refundable(),r.currency(),r.refundCount(),r.pending().signum()>0?"PENDING_RECONCILIATION":"IN_SYNC",r.createdAt(),r.updatedAt(),r.version());}
    private OrderContext orderContext(AdminFinanceOrderClient.Context c,Map<String,String> labels){if(c==null)return new OrderContext(null,null,null,null,List.of(),List.of(),null,false);return new OrderContext(c.orderId(),c.orderNumber(),c.totalAmount(),c.currency(),c.businesses().stream().map(b->new BusinessContext(b.businessId(),labels.get(b.businessId()),b.storeNameAtPurchase(),b.totalAmount(),"/admin/businesses/"+b.businessId())).toList(),c.items().stream().map(i->new ItemContext(i.listingId(),i.titleAtPurchase(),i.quantity(),i.lineTotal())).toList(),"/admin/orders/"+c.orderId(),true);}
    private DisputeContext dispute(AdminFinanceOrderClient.Dispute d,PaymentRow p){boolean executable=p.status().equals("SUCCEEDED")&&p.currency().equals(d.currency())&&p.refundable().compareTo(d.recommendedRefundAmount())>=0;return new DisputeContext(d.disputeId(),d.businessId(),d.resolutionType(),d.recommendedRefundAmount(),d.currency(),d.resolutionReason(),d.resolvedAt(),"/admin/disputes/"+d.disputeId(),executable);}
    private RefundSummary refundSummary(RefundRow r){return new RefundSummary(r.id(),r.paymentId(),r.orderId(),r.disputeId(),r.amount(),r.currency(),r.status(),r.provider(),r.providerReference(),r.source(),"HUMAN_ADMIN".equals(r.source())?"HUMAN_ADMIN":"SYSTEM",r.adminId(),r.createdAt(),r.updatedAt(),r.completedAt(),r.version());}
    private Capabilities capabilities(AdminFinanceAuthorizationClient.Access a,PaymentRow p,RefundRow r){boolean exec=a.has(REFUND_EXECUTE),refundable="SUCCEEDED".equals(p.status())&&p.refundable().signum()>0;boolean uncertain=r!=null&&"REQUIRES_ATTENTION".equals(r.reconciliation());return new Capabilities(a.has(FINANCE_READ),a.has(REFUND_READ),exec&&refundable,false,a.has(PII_READ),refundable,false,true,!exec||!refundable,uncertain?"Provider state requires manual reconciliation; automatic retry is unavailable.":!exec?"You do not have permission to execute refunds.":!refundable?"No refundable amount remains.":null);}
    private List<TimelineEntry> paymentTimeline(PaymentRow payment,List<RefundRow> refunds){List<TimelineEntry> result=new ArrayList<>();repository.paymentHistory(payment.id()).forEach(h->result.add(timeline(h,"PAYMENT_"+h.next(),null,null)));for(RefundRow refund:refunds)repository.refundHistory(refund.id()).forEach(h->result.add(timeline(h,"REFUND_"+h.next(),refund.adminId(),refund.adminName())));result.sort(java.util.Comparator.comparing(TimelineEntry::occurredAt).thenComparing(TimelineEntry::eventId));return result;}
    private List<TimelineEntry> refundTimeline(RefundRow refund){return repository.refundHistory(refund.id()).stream().map(h->timeline(h,"REFUND_"+h.next(),refund.adminId(),refund.adminName())).toList();}
    private TimelineEntry timeline(HistoryRow h,String type,String actor,String name){return new TimelineEntry(h.id(),h.at(),type,actor==null?h.actorType():"ADMIN",actor,name,h.previous(),h.next(),h.reasonCode(),null,h.correlation(),Map.of());}
    private RefundAttempt attempt(AttemptRow a){return new RefundAttempt(a.number(),a.operation(),a.outcome(),a.providerReference(),a.failureCode(),a.failureSummary(),a.createdAt());}

    private Admin require(String permission){CurrentActor actor=actors.currentActor();AdminFinanceAuthorizationClient.Access access=authorization.requireAdmin(actor.accessToken());if(!access.has(permission))throw new PaymentIntentException(HttpStatus.FORBIDDEN,"ADMIN_FINANCE_PERMISSION_REQUIRED","Administrative finance permission is required.");return new Admin(access.userId(),actor.displayName(),actor,access);}
    private Validated validatedRequest(RefundRequest r,boolean keyRequired){if(r==null)throw invalid("REFUND_REQUEST_REQUIRED","Refund details are required.");String type=enumValue(r.refundType(),TYPES,"Refund type");String code=enumValue(r.reasonCode(),REASONS,"Refund reason code");String reason=clean(r.reason());if(reason!=null&&reason.length()>1000)throw invalid("REFUND_REASON_INVALID","Refund reason must be at most 1000 characters.");if("OTHER".equals(code)&&reason==null)throw invalid("REFUND_REASON_INVALID","OTHER requires an explanation.");if(r.expectedPaymentVersion()==null||r.expectedPaymentVersion()<0)throw invalid("PAYMENT_VERSION_REQUIRED","A current payment version is required.");BigDecimal amount=money(r.amount());if(amount!=null&&amount.signum()<=0)throw invalid("REFUND_AMOUNT_INVALID","Refund amount must be greater than zero.");String currency=clean(r.currency());String dispute=optionalId(r.disputeId(),"Dispute ID");String key=clean(r.idempotencyKey());if(keyRequired&&(key==null||!KEY.matcher(key).matches()))throw invalid("REFUND_IDEMPOTENCY_KEY_REQUIRED","A valid Idempotency-Key is required.");return new Validated(type,amount,currency,code,reason,dispute,r.expectedPaymentVersion(),key);}
    private static int pages(long total,int size){return total==0?0:(int)((total+size-1)/size);}private static String sort(String s){return s==null||s.isBlank()?"createdAt,desc":SORTS.contains(s)?s:throwInvalid("Finance sort is invalid.");}
    private static void validateRange(Instant from,Instant to,BigDecimal min,BigDecimal max){if(from!=null&&to!=null&&!from.isBefore(to))throw invalid("FINANCE_DATE_RANGE_INVALID","Created-from must be before created-to.");BigDecimal a=money(min),b=money(max);if(a!=null&&a.signum()<0||b!=null&&b.signum()<0||a!=null&&b!=null&&a.compareTo(b)>0)throw invalid("FINANCE_AMOUNT_RANGE_INVALID","Finance amount range is invalid.");}
    private static String codeForDenial(String d){if(d==null)return"PAYMENT_NOT_REFUNDABLE";if(d.contains("recommendation")||d.contains("dispute"))return"DISPUTE_REFUND_RECOMMENDATION_MISMATCH";if(d.contains("currency"))return"REFUND_CURRENCY_MISMATCH";if(d.contains("amount")||d.contains("exceeds"))return"REFUND_AMOUNT_INVALID";return"PAYMENT_NOT_REFUNDABLE";}
    private static String requiredId(String v,String label){String value=clean(v);if(value==null||!ULID.matcher(value).matches())throw invalid("FINANCE_ID_INVALID",label+" is invalid.");return value;}private static String optionalId(String v,String label){return clean(v)==null?null:requiredId(v,label);}
    private static String enumValue(String v,Set<String> allowed,String label){String value=clean(v);if(value==null||!allowed.contains(value))throw invalid("FINANCE_FILTER_INVALID",label+" is invalid.");return value;}private static String text(String v,int max){String value=clean(v);if(value!=null&&value.length()>max)throw invalid("FINANCE_FILTER_INVALID","Finance filter is too long.");return value;}
    private static String optionalEnum(String v,Set<String> allowed,String label){return clean(v)==null?null:enumValue(v,allowed,label);}
    private static String clean(String v){return v==null||v.trim().isEmpty()?null:v.trim();}private static BigDecimal money(BigDecimal v){if(v==null)return null;try{return v.setScale(4,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw invalid("REFUND_AMOUNT_INVALID","Money supports at most four decimal places.");}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Refund event serialization failed.",e);}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private PaymentIntentException paymentNotFound(){return new PaymentIntentException(HttpStatus.NOT_FOUND,"PAYMENT_NOT_FOUND","Payment was not found.");}private PaymentIntentException refundNotFound(){return new PaymentIntentException(HttpStatus.NOT_FOUND,"REFUND_NOT_FOUND","Refund was not found.");}
    private static PaymentIntentException invalid(String c,String m){return new PaymentIntentException(HttpStatus.BAD_REQUEST,c,m);}private static PaymentIntentException conflict(String c,String m){return new PaymentIntentException(HttpStatus.CONFLICT,c,m);}private static PaymentIntentException unavailable(String c,String m){return new PaymentIntentException(HttpStatus.SERVICE_UNAVAILABLE,c,m);}private static PaymentIntentException versionConflict(){return conflict("PAYMENT_VERSION_CONFLICT","The payment changed. Refresh and run a new refund preview.");}
    private static String throwInvalid(String message){throw invalid("FINANCE_SORT_INVALID",message);}
    private record Admin(String id,String name,CurrentActor actor,AdminFinanceAuthorizationClient.Access access){}
    private record Validated(String type,BigDecimal amount,String currency,String reasonCode,String reason,String disputeId,long version,String key){String normalized(){return type+"|"+amount+"|"+currency+"|"+reasonCode+"|"+reason+"|"+disputeId+"|"+version;}}
}
