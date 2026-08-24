package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.*;
import com.msb.ecom.order_service.model.OrderDisputeException;
import com.msb.ecom.order_service.repository.OrderDisputeRepository;
import com.msb.ecom.order_service.repository.OrderDisputeRepository.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class OrderDisputeService {
    private static final Set<String> REASONS=Set.of("ITEM_NOT_RECEIVED","ITEM_DAMAGED","ITEM_NOT_AS_DESCRIBED",
            "WRONG_ITEM","COUNTERFEIT_OR_INAUTHENTIC","MISSING_PARTS","QUANTITY_INCORRECT",
            "SELLER_FAILED_TO_FULFILL","RETURN_DISAGREEMENT","OTHER");
    private static final Set<String> SELLER_REASONS=Set.of("RETURN_DISAGREEMENT","OTHER");
    private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern ID=Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private final CurrentActorProvider actors; private final BuyerIdentityClient buyers;
    private final BusinessOrderAuthorizationClient businesses; private final OrderDisputeRepository repository;
    private final AdminOrderListingContextClient listings; private final CheckoutUlidGenerator ids; private final Clock clock;

    @Autowired
    public OrderDisputeService(CurrentActorProvider actors,BuyerIdentityClient buyers,
            BusinessOrderAuthorizationClient businesses,OrderDisputeRepository repository,
            AdminOrderListingContextClient listings,CheckoutUlidGenerator ids){this(actors,buyers,businesses,repository,listings,ids,Clock.systemUTC());}
    OrderDisputeService(CurrentActorProvider actors,BuyerIdentityClient buyers,
            BusinessOrderAuthorizationClient businesses,OrderDisputeRepository repository,
            AdminOrderListingContextClient listings,CheckoutUlidGenerator ids,Clock clock){this.actors=actors;this.buyers=buyers;this.businesses=businesses;
        this.repository=repository;this.listings=listings;this.ids=ids;this.clock=clock;}

    @Transactional
    // Creates one immutable transaction-segment dispute after server-side ownership and lifecycle checks.
    public Detail createBuyer(String orderId,CreateRequest request,String correlation){
        validId(orderId,"Order ID");validateCreate(request,false);CurrentActor actor=actors.currentActor();
        String buyer=buyers.resolveBuyer(actor.subject());String group=request.businessGroupId();
        Scope scope=repository.buyerScope(buyer,orderId,group,true).orElseThrow(OrderDisputeException::notFound);
        return create(scope,"BUYER",buyer,request,correlation);
    }

    @Transactional
    public Detail createSeller(String businessId,CreateRequest request,String correlation){
        validateCreate(request,true);CurrentActor actor=actors.currentActor();
        BusinessOrderAuthorizationClient.Access access=businesses.authorize(actor.accessToken(),businessId);
        Scope scope=repository.groupScope(businessId,request.businessGroupId(),true)
                .orElseThrow(OrderDisputeException::notFound);
        return create(scope,"SELLER_BUSINESS",access.userId(),request,correlation);
    }

    private Detail create(Scope scope,String actorType,String actorId,CreateRequest request,String correlation){
        List<ItemRow> groupItems=repository.items(scope.businessOrderId());
        List<String> itemIds=validatedItems(request.itemIds(),groupItems);String disputeId=ids.next();Instant now=clock.instant();
        String hash=hash("CREATE|"+scope.orderId()+"|"+scope.businessOrderId()+"|"+request.reasonCode()+"|"+
                text(request.description(),2000,"Description")+"|"+String.join(",",itemIds)+"|"+evidenceHash(request.evidence()));
        CommandRow replay=claim(actorType,actorId,"CREATE",request.idempotencyKey(),hash,disputeId,now);
        if(replay!=null)return participantDetail(repository.find(replay.disputeId(),false).orElseThrow(),actorType,actorId);
        requireEligible(scope,request.reasonCode());
        DisputeRow row=new DisputeRow(disputeId,scope.orderId(),scope.businessOrderId(),scope.businessId(),scope.buyerId(),
                actorType,actorId,request.reasonCode(),request.description().trim(),Status.OPEN,Priority.MEDIUM,null,null,
                null,null,null,scope.currency(),null,null,now,now,null,0,safeCorrelation(correlation));
        try{repository.insert(row,itemIds);}catch(DuplicateKeyException e){throw conflict("DISPUTE_ALREADY_EXISTS","A dispute already exists for this business-order group.");}
        addEvidence(disputeId,actorType,actorId,request.evidence(),groupItems,itemIds,now);
        event(disputeId,"DISPUTE_OPENED",actorType,actorId,null,null,"OPEN",request.reasonCode(),
                request.description().trim(),correlation,Map.of("orderId",scope.orderId(),"businessGroupId",scope.businessOrderId()));
        if(request.evidence()!=null&&!request.evidence().isEmpty())event(disputeId,"EVIDENCE_ADDED",actorType,actorId,
                null,"OPEN","OPEN",null,null,correlation,Map.of("count",request.evidence().size()));
        repository.completeCommand(replayId(actorType,actorId,"CREATE",request.idempotencyKey()),0,now);
        return participantDetail(repository.find(disputeId,false).orElseThrow(),actorType,actorId);
    }

    @Transactional(readOnly=true)
    public List<Summary> buyerList(String orderId){validId(orderId,"Order ID");String buyer=buyer();
        return repository.buyerDisputes(buyer,orderId).stream().map(this::participantSummary).toList();}

    @Transactional(readOnly=true)
    public Detail buyerDetail(String disputeId){DisputeRow d=dispute(disputeId,false);String buyer=buyer();
        if(!d.buyerId().equals(buyer))throw OrderDisputeException.notFound();return participantDetail(d,"BUYER",buyer);}

    @Transactional(readOnly=true)
    public Detail sellerDetail(String businessId,String disputeId){CurrentActor a=actors.currentActor();var access=businesses.authorize(a.accessToken(),businessId);
        DisputeRow d=dispute(disputeId,false);if(!d.businessId().equals(businessId))throw OrderDisputeException.notFound();
        return participantDetail(d,"SELLER_BUSINESS",access.userId());}

    @Transactional
    public Detail buyerStatement(String disputeId,StatementRequest request,String correlation){String buyer=buyer();
        return statement(disputeId,"BUYER",buyer,null,request,correlation);}

    @Transactional
    public Detail sellerStatement(String businessId,String disputeId,StatementRequest request,String correlation){
        CurrentActor a=actors.currentActor();var access=businesses.authorize(a.accessToken(),businessId);
        return statement(disputeId,"SELLER_BUSINESS",access.userId(),businessId,request,correlation);}

    private Detail statement(String disputeId,String actorType,String actorId,String businessId,
            StatementRequest request,String correlation){if(request==null)throw invalid("DISPUTE_STATEMENT_REQUIRED","Statement details are required.");
        String body=text(request.body(),4000,"Statement");validKey(request.idempotencyKey());DisputeRow d=dispute(disputeId,true);
        if("BUYER".equals(actorType)&&!d.buyerId().equals(actorId)||businessId!=null&&!d.businessId().equals(businessId))throw OrderDisputeException.notFound();
        String hash=hash("STATEMENT|"+d.id()+"|"+body+"|"+evidenceHash(request.evidence()));Instant now=clock.instant();
        CommandRow replay=claim(actorType,actorId,"STATEMENT",request.idempotencyKey(),hash,d.id(),now);
        if(replay!=null)return participantDetail(repository.find(d.id(),false).orElseThrow(),actorType,actorId);
        if(d.status().isFinal())throw conflict("DISPUTE_ALREADY_RESOLVED","Resolved disputes are read-only.");
        repository.statement(ids.next(),d.id(),actorType,actorId,"STATEMENT",body,now);
        List<ItemRow> all=repository.items(d.businessOrderId());List<String> disputed=repository.disputedItems(d.id()).stream().map(ItemRow::id).toList();
        addEvidence(d.id(),actorType,actorId,request.evidence(),all,disputed,now);
        Status waiting="BUYER".equals(actorType)?Status.WAITING_FOR_BUYER:Status.WAITING_FOR_SELLER;
        int updated=d.status()==waiting?repository.participantResponse(d.id(),d.version(),waiting,now)
                :repository.touchParticipant(d.id(),d.version(),now);
        if(updated!=1)throw versionConflict();
        event(d.id(),"BUYER".equals(actorType)?"BUYER_STATEMENT_ADDED":"SELLER_STATEMENT_ADDED",actorType,actorId,null,
                d.status().name(),d.status()==waiting?Status.UNDER_ADMIN_REVIEW.name():d.status().name(),null,null,correlation,Map.of());
        if(request.evidence()!=null&&!request.evidence().isEmpty())event(d.id(),"EVIDENCE_ADDED",actorType,actorId,
                null,d.status().name(),d.status()==waiting?Status.UNDER_ADMIN_REVIEW.name():d.status().name(),
                null,null,correlation,Map.of("count",request.evidence().size()));
        DisputeRow after=repository.find(d.id(),false).orElseThrow();repository.completeCommand(replayId(actorType,actorId,"STATEMENT",request.idempotencyKey()),after.version(),now);
        return participantDetail(after,actorType,actorId);
    }

    Detail participantDetail(DisputeRow d,String actorType,String actorId){Scope scope=repository.scopeForDispute(d.id(),false).orElseThrow();
        List<Timeline> safeEvents=repository.events(d.id()).stream().filter(e->Set.of("DISPUTE_OPENED","BUYER_STATEMENT_ADDED",
                "SELLER_STATEMENT_ADDED","INFORMATION_REQUESTED_FROM_BUYER","INFORMATION_REQUESTED_FROM_SELLER",
                "DISPUTE_READY_FOR_DECISION","DISPUTE_RESOLVED_NO_ACTION","RETURN_APPROVED","REFUND_RECOMMENDED",
                "PARTIAL_REFUND_RECOMMENDED").contains(e.type())).map(e->timeline(e,false)).toList();
        Summary visibleSummary=participantSummary(d);
        if("SELLER_BUSINESS".equals(actorType))visibleSummary=new Summary(visibleSummary.disputeId(),visibleSummary.orderId(),
                visibleSummary.businessGroupId(),null,visibleSummary.businessId(),visibleSummary.reasonCode(),
                visibleSummary.priority(),visibleSummary.status(),null,visibleSummary.disputedAmount(),
                visibleSummary.currency(),visibleSummary.createdAt(),visibleSummary.updatedAt(),visibleSummary.version());
        String visibleOpener=d.openedByUserId().equals(actorId)?d.openedByUserId():null;
        return new Detail(visibleSummary,d.openedByType(),visibleOpener,d.description(),items(d.id(),false),
                repository.statements(d.id()).stream().map(s->new Statement(s.id(),s.authorType(),s.body(),s.createdAt())).toList(),
                repository.evidence(d.id()).stream().map(e->new Evidence(e.id(),e.authorType(),e.type(),e.reference(),e.label(),e.createdAt())).toList(),
                List.of(),safeEvents,resolution(d),scope.refundable(),scope.refundedAmount(),scope.orderStatus(),scope.paymentStatus(),
                scope.reservationStatus(),scope.releaseStatus(),scope.fulfillmentStatus(),shipment(scope),scope.cancellationStatus(),
                null,null,List.of(),new Capabilities(true,false,false,false,false,false,false,false,false,false,
                false,false,d.status().isFinal(),d.status().isFinal(),d.status().isFinal()?"This dispute has a final decision.":null));}

    List<Item> items(String disputeId,boolean admin){return repository.disputedItems(disputeId).stream().map(i->{AdminOrderListingContextClient.Context c=admin?listings.find(i.listingId()):null;CurrentListing current=c==null?null:new CurrentListing(true,c.title(),c.status(),c.price(),c.currency(),c.version());return new Item(i.id(),i.listingId(),i.title(),i.sku(),i.condition(),i.image(),i.quantity(),i.unitPrice(),i.lineTotal(),i.currency(),current,admin?"/admin/listings/moderation/"+i.listingId():null);}).toList();}
    Shipment shipment(Scope s){return s.shipmentStatus()==null?null:new Shipment(s.shipmentSource(),s.carrierDisplayName(),s.serviceDisplayName(),s.trackingNumber(),s.shipmentStatus(),s.shippedAt(),s.deliveredAt());}
    Summary participantSummary(DisputeRow d){return summary(d,null);}
    Summary summary(DisputeRow d,String assigned){BigDecimal amount=repository.disputedItems(d.id()).stream().map(ItemRow::lineTotal).reduce(BigDecimal.ZERO,BigDecimal::add);return new Summary(d.id(),d.orderId(),d.businessOrderId(),d.buyerId(),d.businessId(),d.reasonCode(),d.priority(),d.status(),assigned,amount,d.currency(),d.createdAt(),d.updatedAt(),d.version());}
    ResolutionDetail resolution(DisputeRow d){return d.resolution()==null?null:new ResolutionDetail(d.resolution(),d.resolutionReasonCode(),d.resolutionReason(),d.recommendedRefundAmount(),d.currency(),d.returnInstructions(),d.returnDeadline(),d.resolvedAt());}
    Timeline timeline(EventRow e,boolean admin){return new Timeline(e.id(),e.at(),e.type(),e.actorType(),admin?e.actorId():null,admin?e.actorName():null,e.previous(),e.next(),e.reasonCode(),e.reason(),admin?e.correlation():null,admin?e.requestId():null,e.metadata());}

    private void requireEligible(Scope s,String reason){if(!"CONFIRMED".equals(s.orderStatus())||!"SUCCEEDED".equals(s.paymentStatus())||!"NONE".equals(s.cancellationStatus()))throw conflict("DISPUTE_NOT_ELIGIBLE","The order group is not dispute eligible.");
        Set<String> active=Set.of("ACCEPTED","PROCESSING","SHIPPED","DELIVERED");if(!active.contains(s.fulfillmentStatus()))throw conflict("DISPUTE_NOT_ELIGIBLE","Fulfillment has not reached a dispute-eligible state.");
        Instant expiry=s.deliveredAt()!=null?s.deliveredAt().plus(Duration.ofDays(30)):s.orderCreatedAt().plus(Duration.ofDays(60));if(clock.instant().isAfter(expiry))throw conflict("DISPUTE_NOT_ELIGIBLE","The dispute window has closed.");
        if(Set.of("ITEM_DAMAGED","ITEM_NOT_AS_DESCRIBED","WRONG_ITEM","COUNTERFEIT_OR_INAUTHENTIC","MISSING_PARTS","QUANTITY_INCORRECT","RETURN_DISAGREEMENT").contains(reason)&&!"DELIVERED".equals(s.fulfillmentStatus()))throw conflict("DISPUTE_NOT_ELIGIBLE","This reason requires delivered fulfillment.");
        if("ITEM_NOT_RECEIVED".equals(reason)&&!Set.of("SHIPPED","DELIVERED").contains(s.fulfillmentStatus()))throw conflict("DISPUTE_NOT_ELIGIBLE","This reason requires shipped fulfillment.");}
    private void validateCreate(CreateRequest r,boolean seller){if(r==null)throw invalid("DISPUTE_INVALID_REQUEST","Dispute details are required.");validId(r.businessGroupId(),"Business group ID");validKey(r.idempotencyKey());if(!REASONS.contains(r.reasonCode())||seller&&!SELLER_REASONS.contains(r.reasonCode()))throw invalid("DISPUTE_INVALID_REASON","The dispute reason is not supported for this participant.");String d=text(r.description(),2000,"Description");if("OTHER".equals(r.reasonCode())&&d.length()<10)throw invalid("DISPUTE_INVALID_REASON","OTHER requires a meaningful explanation.");}
    private List<String> validatedItems(List<String> requested,List<ItemRow> all){if(requested==null||requested.isEmpty())return all.stream().map(ItemRow::id).toList();Set<String> allowed=all.stream().map(ItemRow::id).collect(java.util.stream.Collectors.toSet());List<String> result=requested.stream().distinct().sorted().toList();if(result.isEmpty()||result.size()>50||!allowed.containsAll(result))throw invalid("DISPUTE_ITEM_SCOPE_INVALID","Disputed items must belong to the selected business-order group.");return result;}
    private void addEvidence(String disputeId,String actorType,String actorId,List<EvidenceReference> evidence,List<ItemRow> all,List<String> disputed,Instant now){if(evidence==null)return;if(evidence.size()>10)throw invalid("DISPUTE_EVIDENCE_INVALID","At most 10 evidence references are allowed.");Set<String> itemIds=new HashSet<>(disputed);Set<String> listings=all.stream().filter(i->itemIds.contains(i.id())).map(ItemRow::listingId).collect(java.util.stream.Collectors.toSet());for(EvidenceReference e:evidence){if(e==null||!Set.of("ORDER_ITEM","LISTING_IMAGE","SHIPMENT").contains(e.referenceType()))throw invalid("DISPUTE_EVIDENCE_INVALID","Evidence must reference an existing platform object.");String ref=text(e.referenceId(),128,"Evidence reference");if("ORDER_ITEM".equals(e.referenceType())&&!itemIds.contains(ref)||"LISTING_IMAGE".equals(e.referenceType())&&!listings.contains(ref)||"SHIPMENT".equals(e.referenceType())&&!repository.scopeForDispute(disputeId,false).orElseThrow().businessOrderId().equals(ref))throw invalid("DISPUTE_EVIDENCE_INVALID","Evidence does not belong to this dispute scope.");try{repository.evidence(ids.next(),disputeId,actorType,actorId,e.referenceType(),ref,text(e.label(),200,"Evidence label"),now);}catch(DuplicateKeyException x){throw conflict("DISPUTE_EVIDENCE_ALREADY_EXISTS","This evidence reference is already attached to the dispute.");}}}
    private String evidenceHash(List<EvidenceReference> e){if(e==null)return"";return e.stream().map(v->v.referenceType()+":"+v.referenceId()+":"+v.label()).sorted().reduce("",(a,b)->a+"|"+b);}
    private CommandRow claim(String type,String actor,String op,String key,String hash,String dispute,Instant now){validKey(key);CommandRow existing=repository.command(type,actor,op,key,true).orElse(null);if(existing!=null){if(!existing.hash().equals(hash))throw conflict("DISPUTE_IDEMPOTENCY_CONFLICT","The idempotency key was reused with changed content.");if(!"COMPLETED".equals(existing.state()))throw conflict("DISPUTE_ACTION_IN_PROGRESS","The same command is already in progress.");return existing;}String id=ids.next();if(!repository.insertCommand(id,type,actor,op,key,hash,dispute,now))return claim(type,actor,op,key,hash,dispute,now);return null;}
    private String replayId(String type,String actor,String op,String key){return repository.command(type,actor,op,key,true).orElseThrow().id();}
    private void event(String dispute,String type,String actorType,String actorId,String actorName,String previous,String next,String code,String reason,String correlation,Map<String,Object> metadata){String request=ids.next();repository.event(new EventRow(ids.next(),dispute,type,clock.instant(),actorType,actorId,actorName,previous,next,code,reason,safeCorrelation(correlation),request,metadata));}
    private DisputeRow dispute(String id,boolean lock){validId(id,"Dispute ID");return repository.find(id,lock).orElseThrow(OrderDisputeException::notFound);}
    private String buyer(){return buyers.resolveBuyer(actors.currentActor().subject());}
    private static String text(String v,int max,String label){if(v==null||v.trim().isEmpty()||v.trim().length()>max)throw invalid("DISPUTE_INVALID_REQUEST",label+" is required and must be at most "+max+" characters.");return v.trim();}
    private static void validKey(String v){if(v==null||!KEY.matcher(v).matches())throw invalid("DISPUTE_IDEMPOTENCY_KEY_REQUIRED","A valid idempotency key is required.");}
    static void validKeyForAdmin(String v){validKey(v);}
    private static void validId(String v,String label){if(v==null||!ID.matcher(v).matches())throw invalid("DISPUTE_INVALID_REQUEST",label+" is invalid.");}
    private static String safeCorrelation(String v){return v==null||v.isBlank()?"generated":v.substring(0,Math.min(128,v.length()));}
    private static String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String hashForAdmin(String v){return hash(v);}
    static OrderDisputeException invalid(String c,String m){return new OrderDisputeException(HttpStatus.BAD_REQUEST,c,m);}static OrderDisputeException conflict(String c,String m){return new OrderDisputeException(HttpStatus.CONFLICT,c,m);}static OrderDisputeException versionConflict(){return conflict("DISPUTE_VERSION_CONFLICT","The dispute changed. Refresh and try again.");}
}
