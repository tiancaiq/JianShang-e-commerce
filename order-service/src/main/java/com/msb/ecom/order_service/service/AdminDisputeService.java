package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.*;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.model.OrderDisputeException;
import com.msb.ecom.order_service.repository.OrderDisputeRepository;
import com.msb.ecom.order_service.repository.OrderDisputeRepository.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminDisputeService {
    public static final String READ="admin.dispute.read",ASSIGN="admin.dispute.assign",
            INVESTIGATE="admin.dispute.investigate",RESOLVE="admin.dispute.resolve";
    private final CurrentActorProvider actors;private final AdminOrderAuthorizationClient authorization;
    private final OrderDisputeRepository repository;private final OrderDisputeService participants;
    private final PaymentIntentClient payments;private final CheckoutUlidGenerator ids;private final Clock clock;
    @Autowired
    public AdminDisputeService(CurrentActorProvider actors,AdminOrderAuthorizationClient authorization,
            OrderDisputeRepository repository,OrderDisputeService participants,PaymentIntentClient payments,
            CheckoutUlidGenerator ids){this(actors,authorization,repository,participants,payments,ids,Clock.systemUTC());}
    AdminDisputeService(CurrentActorProvider actors,AdminOrderAuthorizationClient authorization,
            OrderDisputeRepository repository,OrderDisputeService participants,PaymentIntentClient payments,
            CheckoutUlidGenerator ids,Clock clock){this.actors=actors;this.authorization=authorization;this.repository=repository;
        this.participants=participants;this.payments=payments;this.ids=ids;this.clock=clock;}

    @Transactional(readOnly=true)
    public Page search(String q,String orderId,String buyerId,String businessId,Status status,String reasonCode,
            Priority priority,Assignment assignment,Instant from,Instant to,int page,int size,String sort){Admin a=require(READ);
        int p=Math.max(0,page),s=Math.max(1,Math.min(size,100));String normalized=Set.of("createdAt,desc","updatedAt,desc","priority,desc").contains(sort)?sort:"createdAt,desc";
        SearchResult result=repository.search(new SearchFilter(clean(q),clean(orderId),clean(buyerId),clean(businessId),status,
                clean(reasonCode),priority,assignment,from,to,p,s,normalized),a.id());
        return new Page(result.rows().stream().map(d->participants.summary(d,d.assignedAdminId())).toList(),p,s,result.total(),result.total()==0?0:(int)((result.total()+s-1)/s),normalized);}

    @Transactional(readOnly=true)
    public Detail detail(String id){Admin a=require(READ);return adminDetail(dispute(id,false),a);}

    @Transactional
    public Detail claim(String id,VersionRequest request,String correlation){Admin a=require(ASSIGN);DisputeRow d=dispute(id,true);long v=version(request);
        if(d.status().isFinal())throw conflict("DISPUTE_ALREADY_RESOLVED","Resolved disputes cannot be assigned.");
        if(repository.claim(d.id(),v,a.id(),clock.instant())!=1)throw assignmentConflict(d,a,v);
        event(d,"DISPUTE_CLAIMED",a,"UNASSIGNED",a.id(),null,"Dispute claimed",correlation,Map.of());return adminDetail(dispute(id,false),a);}
    @Transactional
    public Detail release(String id,VersionRequest request,String correlation){Admin a=require(ASSIGN);DisputeRow d=dispute(id,true);long v=version(request);
        owned(d,a);if(repository.release(d.id(),v,a.id(),clock.instant())!=1)throw versionConflict();
        event(d,"DISPUTE_RELEASED",a,a.id(),"UNASSIGNED",null,"Dispute released",correlation,Map.of());return adminDetail(dispute(id,false),a);}

    @Transactional
    public Detail priority(String id,PriorityRequest request,String correlation){Admin a=require(INVESTIGATE);if(request==null||request.priority()==null)throw invalid("DISPUTE_PRIORITY_INVALID","Priority is required.");
        DisputeRow d=dispute(id,true);owned(d,a);String reason=text(request.reason(),1000,"Priority reason");
        if(repository.priority(id,requiredVersion(request.expectedVersion()),a.id(),request.priority(),clock.instant())!=1)throw versionConflict();
        event(d,"PRIORITY_CHANGED",a,d.priority().name(),request.priority().name(),"PRIORITY_REASSESSMENT",reason,correlation,Map.of());return adminDetail(dispute(id,false),a);}

    @Transactional
    public Detail note(String id,NoteRequest request,String correlation){Admin a=require(INVESTIGATE);if(request==null)throw invalid("DISPUTE_NOTE_REQUIRED","Internal note details are required.");
        String body=text(request.body(),4000,"Internal note");String key=text(request.idempotencyKey(),128,"Idempotency key");Instant now=clock.instant();
        String hash=sha("NOTE|"+id+"|"+body);CommandRow replay=command(a,"NOTE",key,hash,id,now);if(replay!=null)return adminDetail(dispute(id,false),a);
        DisputeRow d=dispute(id,true);owned(d,a);
        repository.note(ids.next(),id,a.id(),a.name(),body,now);if(repository.touchOwned(id,d.version(),a.id(),now)!=1)throw versionConflict();
        event(d,"ADMIN_NOTE_ADDED",a,d.status().name(),d.status().name(),null,null,correlation,Map.of());complete(a,"NOTE",key,id);return adminDetail(dispute(id,false),a);}

    @Transactional
    public Detail requestInformation(String id,InformationRequest request,String correlation){Admin a=require(INVESTIGATE);if(request==null||request.party()==null)throw invalid("DISPUTE_INFORMATION_REQUEST_INVALID","Requested party is required.");
        long v=requiredVersion(request.expectedVersion());String message=text(request.message(),2000,"Information request");String key=text(request.idempotencyKey(),128,"Idempotency key");Instant now=clock.instant();
        String hash=sha("INFO|"+id+"|"+request.party()+"|"+message+"|"+v);CommandRow replay=command(a,"REQUEST_INFORMATION",key,hash,id,now);if(replay!=null)return adminDetail(dispute(id,false),a);
        DisputeRow d=dispute(id,true);owned(d,a);if(d.status()!=Status.UNDER_ADMIN_REVIEW)throw conflict("DISPUTE_INVALID_STATE","Information can be requested only during admin review.");
        Status next=request.party()==Party.BUYER?Status.WAITING_FOR_BUYER:Status.WAITING_FOR_SELLER;
        if(repository.status(id,v,a.id(),d.status(),next,now)!=1)throw versionConflict();repository.statement(ids.next(),id,"ADMIN",a.id(),"INFORMATION_REQUEST",message,now);
        event(d,request.party()==Party.BUYER?"INFORMATION_REQUESTED_FROM_BUYER":"INFORMATION_REQUESTED_FROM_SELLER",a,d.status().name(),next.name(),"ADDITIONAL_INFORMATION_REQUIRED",message,correlation,Map.of("party",request.party().name()));
        complete(a,"REQUEST_INFORMATION",key,id);return adminDetail(dispute(id,false),a);}

    @Transactional
    public Detail ready(String id,ReasonRequest request,String correlation){Admin a=require(INVESTIGATE);DisputeRow d=dispute(id,true);owned(d,a);if(d.status()!=Status.UNDER_ADMIN_REVIEW)throw conflict("DISPUTE_INVALID_STATE","Only reviewed disputes can be marked ready.");
        long v=requiredVersion(request==null?null:request.expectedVersion());String reason=text(request==null?null:request.reason(),1000,"Decision readiness reason");
        if(repository.status(id,v,a.id(),d.status(),Status.READY_FOR_DECISION,clock.instant())!=1)throw versionConflict();
        event(d,"DISPUTE_READY_FOR_DECISION",a,d.status().name(),Status.READY_FOR_DECISION.name(),"REVIEW_COMPLETE",reason,correlation,Map.of());return adminDetail(dispute(id,false),a);}

    @Transactional
    // Records a transaction remedy decision only; this method deliberately has no refund adapter dependency.
    public Detail resolve(String id,ResolveRequest request,String correlation){Admin a=require(RESOLVE);if(request==null||request.resolutionType()==null)throw invalid("DISPUTE_INVALID_RESOLUTION","Resolution is required.");
        long v=requiredVersion(request.expectedVersion());String code=text(request.reasonCode(),64,"Resolution reason code");String reason=text(request.reason(),2000,"Resolution reason");String key=text(request.idempotencyKey(),128,"Idempotency key");Instant now=clock.instant();
        String hash=sha("RESOLVE|"+id+"|"+request.resolutionType()+"|"+code+"|"+reason+"|"+
                request.recommendedRefundAmount()+"|"+request.returnInstructions()+"|"+request.returnDeadline()+"|"+v);
        CommandRow replay=command(a,"RESOLVE",key,hash,id,now);if(replay!=null)return adminDetail(dispute(id,false),a);
        DisputeRow d=dispute(id,true);owned(d,a);if(d.status()!=Status.READY_FOR_DECISION)throw conflict("DISPUTE_INVALID_STATE","The dispute is not ready for decision.");
        Scope scope=repository.scopeForDispute(id,true).orElseThrow();BigDecimal amount=validatedAmount(request,scope);
        String instructions=request.resolutionType()==Resolution.RETURN_APPROVED?text(request.returnInstructions(),2000,"Return instructions"):null;
        Instant deadline=request.resolutionType()==Resolution.RETURN_APPROVED?request.returnDeadline():null;
        if(request.resolutionType()==Resolution.RETURN_APPROVED&&(deadline==null||!deadline.isAfter(now)))throw invalid("DISPUTE_INVALID_RESOLUTION","A future return deadline is required.");
        if(repository.resolve(id,v,a.id(),request.resolutionType(),code,reason,amount,instructions,deadline,now)!=1)throw versionConflict();
        event(d,eventType(request.resolutionType()),a,d.status().name(),request.resolutionType().name(),code,reason,correlation,
                amount==null?Map.of("financialMutation",false):Map.of("financialMutation",false,"recommendedRefundAmount",amount,"currency",scope.currency()));
        complete(a,"RESOLVE",key,id);return adminDetail(dispute(id,false),a);}

    private BigDecimal validatedAmount(ResolveRequest r,Scope scope){boolean full=r.resolutionType()==Resolution.REFUND_RECOMMENDED,partial=r.resolutionType()==Resolution.PARTIAL_REFUND_RECOMMENDED;
        if(!full&&!partial){if(r.recommendedRefundAmount()!=null)throw invalid("DISPUTE_REFUND_AMOUNT_INVALID","This resolution cannot include a refund recommendation.");return null;}
        PaymentIntentClient.PaymentIntent payment;try{payment=payments.get(scope.paymentIntentId(),scope.buyerId());}catch(CheckoutException|UnsupportedOperationException e){throw conflict("DISPUTE_FINANCIAL_STATE_CHANGED","Current payment state could not be safely verified.");}
        if(payment==null||!"SUCCEEDED".equals(payment.status())||!scope.currency().equals(payment.currency()))throw conflict("DISPUTE_FINANCIAL_STATE_CHANGED","Current payment state no longer supports this recommendation.");
        BigDecimal max=scope.refundable().setScale(4,RoundingMode.UNNECESSARY);if(max.signum()<=0)throw conflict("DISPUTE_FINANCIAL_STATE_CHANGED","No refundable amount remains.");
        if(full)return max;BigDecimal amount=r.recommendedRefundAmount();if(amount==null||amount.signum()<=0||amount.compareTo(max)>=0)throw invalid("DISPUTE_REFUND_AMOUNT_INVALID","Partial recommendation must be greater than zero and less than the current refundable amount.");return amount.setScale(4,RoundingMode.UNNECESSARY);}

    private Detail adminDetail(DisputeRow d,Admin a){Scope scope=repository.scopeForDispute(d.id(),false).orElseThrow();boolean mine=a.id().equals(d.assignedAdminId()),other=d.assignedAdminId()!=null&&!mine,fin=d.status().isFinal();boolean investigate=a.access().has(INVESTIGATE)&&mine&&!fin;boolean resolve=a.access().has(RESOLVE)&&mine&&d.status()==Status.READY_FOR_DECISION;
        Capabilities caps=new Capabilities(true,a.access().has(ASSIGN)&&d.assignedAdminId()==null&&!fin,a.access().has(ASSIGN)&&mine&&!fin,investigate,investigate,investigate&&d.status()==Status.UNDER_ADMIN_REVIEW,investigate&&d.status()==Status.UNDER_ADMIN_REVIEW,investigate,investigate&&d.status()==Status.UNDER_ADMIN_REVIEW,resolve,mine,other,fin,fin||other,fin?"This dispute has a final decision.":other?"This dispute is assigned to another administrator.":null);
        return new Detail(participants.summary(d,d.assignedAdminId()),d.openedByType(),d.openedByUserId(),d.description(),participants.items(d.id(),true),repository.statements(d.id()).stream().map(s->new Statement(s.id(),s.authorType(),s.body(),s.createdAt())).toList(),repository.evidence(d.id()).stream().map(e->new Evidence(e.id(),e.authorType(),e.type(),e.reference(),e.label(),e.createdAt())).toList(),repository.notes(d.id()).stream().map(n->new Note(n.id(),n.adminId(),n.adminName(),n.body(),n.createdAt())).toList(),repository.events(d.id()).stream().map(e->participants.timeline(e,true)).toList(),participants.resolution(d),scope.refundable(),scope.refundedAmount(),scope.orderStatus(),scope.paymentStatus(),scope.reservationStatus(),scope.releaseStatus(),scope.fulfillmentStatus(),participants.shipment(scope),scope.cancellationStatus(),"/admin/users/"+d.buyerId(),"/admin/businesses/"+d.businessId(),List.of("/admin/reports?targetType=BUSINESS&targetId="+d.businessId(),"/admin/cases"),caps);}

    private Admin require(String permission){CurrentActor actor=actors.currentActor();AdminOrderAuthorizationClient.Access access=authorization.requireAdmin(actor.accessToken());if(!access.has(permission))throw new OrderDisputeException(HttpStatus.FORBIDDEN,"ADMIN_DISPUTE_PERMISSION_REQUIRED","Administrative dispute permission is required.");return new Admin(access.userId(),actor.displayName(),access);}
    private void owned(DisputeRow d,Admin a){if(d.status().isFinal())throw conflict("DISPUTE_ALREADY_RESOLVED","Resolved disputes are read-only.");if(!a.id().equals(d.assignedAdminId()))throw conflict("DISPUTE_NOT_ASSIGNED_TO_CURRENT_ADMIN","The dispute must be assigned to the current administrator.");}
    private OrderDisputeException assignmentConflict(DisputeRow d,Admin a,long v){DisputeRow current=dispute(d.id(),false);if(current.version()!=v)return versionConflict();if(current.assignedAdminId()!=null&&!current.assignedAdminId().equals(a.id()))return conflict("DISPUTE_NOT_ASSIGNABLE","The dispute is assigned to another administrator.");return conflict("DISPUTE_NOT_ASSIGNABLE","The dispute cannot be claimed.");}
    private CommandRow command(Admin a,String op,String key,String hash,String dispute,Instant now){OrderDisputeService.validKeyForAdmin(key);CommandRow existing=repository.command("ADMIN",a.id(),op,key,true).orElse(null);if(existing!=null){if(!existing.hash().equals(hash))throw conflict("DISPUTE_IDEMPOTENCY_CONFLICT","The idempotency key was reused with changed content.");if(!"COMPLETED".equals(existing.state()))throw conflict("DISPUTE_ACTION_IN_PROGRESS","The same command is in progress.");return existing;}if(!repository.insertCommand(ids.next(),"ADMIN",a.id(),op,key,hash,dispute,now))return command(a,op,key,hash,dispute,now);return null;}
    private void complete(Admin a,String op,String key,String dispute){CommandRow c=repository.command("ADMIN",a.id(),op,key,true).orElseThrow();repository.completeCommand(c.id(),repository.find(dispute,false).orElseThrow().version(),clock.instant());}
    private void event(DisputeRow d,String type,Admin a,String previous,String next,String code,String reason,String correlation,Map<String,Object> metadata){repository.event(new EventRow(ids.next(),d.id(),type,clock.instant(),"ADMIN",a.id(),a.name(),previous,next,code,reason,correlation==null?"generated":correlation,ids.next(),metadata));}
    private DisputeRow dispute(String id,boolean lock){return repository.find(id,lock).orElseThrow(OrderDisputeException::notFound);}
    private static String eventType(Resolution r){return switch(r){case RESOLVED_NO_ACTION->"DISPUTE_RESOLVED_NO_ACTION";case RETURN_APPROVED->"RETURN_APPROVED";case REFUND_RECOMMENDED->"REFUND_RECOMMENDED";case PARTIAL_REFUND_RECOMMENDED->"PARTIAL_REFUND_RECOMMENDED";};}
    private static long version(VersionRequest r){return requiredVersion(r==null?null:r.expectedVersion());}private static long requiredVersion(Long v){if(v==null||v<0)throw invalid("DISPUTE_VERSION_REQUIRED","A current dispute version is required.");return v;}
    private static String clean(String v){return v==null||v.isBlank()?null:v.trim();}private static String text(String v,int max,String label){if(v==null||v.trim().isEmpty()||v.trim().length()>max)throw invalid("DISPUTE_INVALID_REQUEST",label+" is required and must be at most "+max+" characters.");return v.trim();}
    private static String sha(String value){return OrderDisputeService.hashForAdmin(value);}private static OrderDisputeException invalid(String c,String m){return new OrderDisputeException(HttpStatus.BAD_REQUEST,c,m);}private static OrderDisputeException conflict(String c,String m){return new OrderDisputeException(HttpStatus.CONFLICT,c,m);}private static OrderDisputeException versionConflict(){return conflict("DISPUTE_VERSION_CONFLICT","The dispute changed. Refresh and try again.");}
    private record Admin(String id,String name,AdminOrderAuthorizationClient.Access access){}
}
