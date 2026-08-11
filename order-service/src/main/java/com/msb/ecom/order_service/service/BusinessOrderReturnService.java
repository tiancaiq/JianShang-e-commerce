package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderReturnProperties;
import com.msb.ecom.order_service.dto.BusinessOrderReturnResponse;
import com.msb.ecom.order_service.dto.CreateBusinessOrderReturnRequest;
import com.msb.ecom.order_service.dto.ReceiveBusinessOrderReturnRequest;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderReturnRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class BusinessOrderReturnService {
    private static final String REQUEST="REQUEST_RETURN";
    private static final String AUTHORIZE="AUTHORIZE_RETURN";
    private static final String RECEIVE="RECEIVE_RETURN";
    private static final Set<String> REASONS=Set.of("NO_LONGER_NEEDED","NOT_AS_EXPECTED","DAMAGED","WRONG_ITEM","OTHER");
    private static final Set<String> DISPOSITIONS=Set.of("RESTOCK_SELLABLE","DO_NOT_RESTOCK");
    private static final Pattern ID=Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern BUSINESS_ID=Pattern.compile("[0-9A-Z]{26}");
    private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final BusinessOrderReturnProperties properties;
    private final CurrentActorProvider actors;
    private final BuyerIdentityClient buyers;
    private final BusinessOrderAuthorizationClient businesses;
    private final BusinessOrderReturnRepository repository;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public BusinessOrderReturnService(BusinessOrderReturnProperties properties,
            CurrentActorProvider actors,BuyerIdentityClient buyers,
            BusinessOrderAuthorizationClient businesses,BusinessOrderReturnRepository repository,
            CheckoutUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager tx){
        this(properties,actors,buyers,businesses,repository,ids,mapper,tx,Clock.systemUTC());
    }

    BusinessOrderReturnService(BusinessOrderReturnProperties properties,
            CurrentActorProvider actors,BuyerIdentityClient buyers,
            BusinessOrderAuthorizationClient businesses,BusinessOrderReturnRepository repository,
            CheckoutUlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager tx,Clock clock){
        this.properties=properties;this.actors=actors;this.buyers=buyers;this.businesses=businesses;
        this.repository=repository;this.ids=ids;this.mapper=mapper;this.transactions=new TransactionTemplate(tx);
        this.clock=clock;
    }

    public BusinessOrderReturnResponse buyerDetail(String orderId,String groupId){
        enabled(); ids(orderId,groupId);
        String buyer=resolveBuyer();
        var group=repository.lockBuyerGroup(buyer,orderId,groupId).orElseThrow(this::buyerNotFound);
        var existing=repository.findBuyer(buyer,orderId,groupId).orElse(null);
        return existing==null?eligible(group):response(existing);
    }

    // Creates one whole-group return after server-authoritative delivery-window evaluation.
    public BusinessOrderReturnResponse request(String orderId,String groupId,String ifMatch,
            String key,CreateBusinessOrderReturnRequest body,String correlation){
        enabled();ids(orderId,groupId);long expected=version(ifMatch);validKey(key);
        if(body==null||!REASONS.contains(body.reasonCode())) throw buyerBad("RETURN_REASON_INVALID","A supported return reason is required.");
        String comment=body.comment()==null?null:body.comment().trim();
        if(comment!=null&&(comment.isEmpty()||comment.length()>500)) throw buyerBad("RETURN_COMMENT_INVALID","Return comment must be 1 to 500 characters.");
        String buyer=resolveBuyer();String returnId=ids.next();String commandId=ids.next();
        String hash=sha(REQUEST+'|'+orderId+'|'+groupId+'|'+expected+'|'+body.reasonCode()+'|'+comment);
        return transactions.execute(s->{
            var group=repository.lockBuyerGroup(buyer,orderId,groupId).orElseThrow(this::buyerNotFound);
            var replay=claim(buyer,group.businessId(),REQUEST,key,hash,returnId,commandId);
            if(replay!=null)return response(repository.findByGroup(groupId).orElseThrow());
            if(group.groupVersion()!=expected)throw buyerConflict("RETURN_VERSION_CONFLICT","Order-group version does not match.");
            requireEligible(group);
            Instant now=clock.instant();Instant expires=group.deliveredAt().plus(properties.window());
            try{repository.insertReturn(returnId,buyer,group,body.reasonCode(),comment,now,expires);}
            catch(DuplicateKeyException e){throw buyerConflict("RETURN_ALREADY_EXISTS","This order group already has a return.");}
            repository.history(ids.next(),returnId,null,"RETURN_REQUESTED",0,"BUYER_RETURN_REQUESTED",
                    buyer,safe(correlation),commandId,now);
            String eventId=ids.next();
            repository.outbox(eventId,returnId,"return.requested",json(Map.of(
                    "returnId",returnId,"orderId",orderId,"businessOrderId",groupId,
                    "businessId",group.businessId(),"status","RETURN_REQUESTED","occurredAt",now)),
                    safe(correlation),commandId,now);
            repository.completeCommand(commandId,0,now);
            return response(repository.findByGroup(groupId).orElseThrow());
        });
    }

    public BusinessOrderReturnResponse sellerDetail(String businessId,String groupId){
        enabled();validBusiness(businessId);ids(groupId);seller(businessId);
        repository.lockBusinessGroup(businessId,groupId).orElseThrow(this::sellerNotFound);
        return repository.findBusiness(businessId,groupId).map(this::response).orElse(null);
    }

    // Authorizes the deterministic policy and creates a clearly labeled demo return shipment.
    public BusinessOrderReturnResponse authorize(String businessId,String groupId,String returnId,
            String ifMatch,String key,String correlation){
        enabled();validBusiness(businessId);ids(groupId,returnId);long expected=version(ifMatch);validKey(key);
        var access=seller(businessId);String commandId=ids.next();
        String hash=sha(AUTHORIZE+'|'+businessId+'|'+groupId+'|'+returnId+'|'+expected);
        return transactions.execute(s->{
            var replay=claim(access.userId(),businessId,AUTHORIZE,key,hash,returnId,commandId);
            if(replay!=null)return response(repository.findBusiness(businessId,groupId).orElseThrow(this::sellerNotFound));
            var r=repository.lockById(returnId).orElseThrow(this::sellerNotFound);
            scope(r,businessId,groupId); exact(r,expected,"RETURN_REQUESTED");Instant now=clock.instant();
            if(repository.transition(returnId,expected,"RETURN_REQUESTED","RETURN_AUTHORIZED",now)!=1)throw sellerConflict();
            repository.history(ids.next(),returnId,"RETURN_REQUESTED","RETURN_AUTHORIZED",expected+1,
                    "SELLER_RETURN_AUTHORIZED",access.userId(),safe(correlation),commandId,now);
            repository.insertShipment(ids.next(),returnId,"RETURN-"+returnId,now);
            if(repository.transition(returnId,expected+1,"RETURN_AUTHORIZED","RETURN_IN_TRANSIT",now)!=1)throw sellerConflict();
            repository.history(ids.next(),returnId,"RETURN_AUTHORIZED","RETURN_IN_TRANSIT",expected+2,
                    "DEMO_RETURN_IN_TRANSIT",access.userId(),safe(correlation),commandId,now);
            String eventId=ids.next();repository.outbox(eventId,returnId,"return.authorized",json(Map.of(
                    "returnId",returnId,"orderId",r.orderId(),"businessOrderId",groupId,
                    "businessId",businessId,"status","RETURN_IN_TRANSIT","occurredAt",now)),
                    safe(correlation),commandId,now);
            repository.completeCommand(commandId,expected+2,now);
            return response(repository.findBusiness(businessId,groupId).orElseThrow());
        });
    }

    // Records physical demo receipt and an explicit inventory disposition before refund work begins.
    public BusinessOrderReturnResponse receive(String businessId,String groupId,String returnId,
            String ifMatch,String key,ReceiveBusinessOrderReturnRequest body,String correlation){
        enabled();validBusiness(businessId);ids(groupId,returnId);long expected=version(ifMatch);validKey(key);
        if(body==null||!DISPOSITIONS.contains(body.inventoryDisposition()))throw new BusinessOrderException(
                HttpStatus.BAD_REQUEST,"RETURN_DISPOSITION_INVALID","A supported inventory disposition is required.");
        var access=seller(businessId);String commandId=ids.next();
        String hash=sha(RECEIVE+'|'+businessId+'|'+groupId+'|'+returnId+'|'+expected+'|'+body.inventoryDisposition());
        return transactions.execute(s->{
            var replay=claim(access.userId(),businessId,RECEIVE,key,hash,returnId,commandId);
            if(replay!=null)return response(repository.findBusiness(businessId,groupId).orElseThrow(this::sellerNotFound));
            var r=repository.lockById(returnId).orElseThrow(this::sellerNotFound);scope(r,businessId,groupId);
            exact(r,expected,"RETURN_IN_TRANSIT");Instant now=clock.instant();
            repository.setReceived(returnId,expected,body.inventoryDisposition(),now);
            repository.history(ids.next(),returnId,"RETURN_IN_TRANSIT","RETURN_RECEIVED",expected+1,
                    "SELLER_RETURN_RECEIVED",access.userId(),safe(correlation),commandId,now);
            String eventId=ids.next();repository.outbox(eventId,returnId,"return.received",json(Map.of(
                    "returnId",returnId,"orderId",r.orderId(),"businessOrderId",groupId,
                    "businessId",businessId,"status","RETURN_RECEIVED","occurredAt",now)),
                    safe(correlation),commandId,now);
            repository.completeCommand(commandId,expected+1,now);
            return response(repository.findBusiness(businessId,groupId).orElseThrow());
        });
    }

    private BusinessOrderReturnRepository.Command claim(String actor,String business,String op,String key,
            String hash,String returnId,String commandId){
        var existing=repository.lockCommand(actor,business,op,key).orElse(null);
        if(existing!=null){if(!existing.requestHash().equals(hash))throw buyerConflict("RETURN_IDEMPOTENCY_CONFLICT","Idempotency-Key was reused with changed content.");return existing;}
        try{repository.insertCommand(commandId,actor,business,op,key,hash,returnId,clock.instant());return null;}
        catch(DuplicateKeyException e){var concurrent=repository.lockCommand(actor,business,op,key).orElseThrow();if(!concurrent.requestHash().equals(hash))throw buyerConflict("RETURN_IDEMPOTENCY_CONFLICT","Idempotency-Key was reused with changed content.");return concurrent;}
    }
    private BusinessOrderReturnResponse eligible(BusinessOrderReturnRepository.Group g){
        String code=eligibility(g);return new BusinessOrderReturnResponse(code==null,code,null,g.orderId(),
                g.businessOrderId(),g.storeName(),null,null,"LOCAL_DEMO_RETURN_POLICY_V1",null,
                g.deliveredAt()==null?null:g.deliveredAt().plus(properties.window()),null,null,null,null,
                null,null,g.currency(),null,g.groupVersion(),null,java.util.List.of());
    }
    private void requireEligible(BusinessOrderReturnRepository.Group g){String code=eligibility(g);if(code!=null)throw buyerConflict(code,"This order group is not eligible for return.");}
    private String eligibility(BusinessOrderReturnRepository.Group g){
        if(!"DELIVERED".equals(g.fulfillmentStatus())||g.deliveredAt()==null)return "RETURN_REQUIRES_DELIVERY";
        if(!"NONE".equals(g.cancellationStatus()))return "RETURN_CANCELLED_GROUP";
        if(clock.instant().isAfter(g.deliveredAt().plus(properties.window())))return "RETURN_WINDOW_EXPIRED";
        if(repository.findByGroup(g.businessOrderId()).isPresent())return "RETURN_ALREADY_EXISTS";return null;
    }
    private BusinessOrderReturnResponse response(BusinessOrderReturnRepository.ReturnRecord r){
        var history=repository.history(r.id()).stream().map(h->new BusinessOrderReturnResponse.TimelineEntry(
                "DEMO_REFUND_COMPLETED".equals(h.reason())?"REFUND_COMPLETED":h.status(),h.occurredAt())).toList();
        var shipment=r.tracking()==null?null:new BusinessOrderReturnResponse.DemoShipment(r.carrier(),r.tracking(),
                r.shipmentCreatedAt(),r.inTransitAt(),"No real shipment has been created.");
        return new BusinessOrderReturnResponse(false,"RETURN_ALREADY_EXISTS",r.id(),r.orderId(),r.businessOrderId(),
                r.storeName(),r.reasonCode(),r.buyerComment(),r.policyVersion(),r.requestedAt(),r.windowExpiresAt(),
                r.status(),r.refundStatus(),r.disposition(),r.receivedAt(),r.refundId(),r.refundAmount(),r.currency(),
                r.completedAt(),r.version(),shipment,history);
    }
    private BusinessOrderAuthorizationClient.Access seller(String businessId){CurrentActor a=actors.currentActor();if(a.accessToken()==null)throw sellerNotFound();return businesses.authorizeFulfillment(a.accessToken(),businessId);}
    private String resolveBuyer(){return buyers.resolveBuyer(actors.currentActor().subject());}
    private void scope(BusinessOrderReturnRepository.ReturnRecord r,String business,String group){if(!business.equals(r.businessId())||!group.equals(r.businessOrderId()))throw sellerNotFound();}
    private void exact(BusinessOrderReturnRepository.ReturnRecord r,long version,String state){if(r.version()!=version)throw new BusinessOrderException(HttpStatus.CONFLICT,"RETURN_VERSION_CONFLICT","Return version does not match.");if(!state.equals(r.status()))throw sellerConflict();}
    private long version(String value){try{if(value==null)throw new Exception();String v=value.replace("\"","").trim();long n=Long.parseLong(v);if(n<0)throw new Exception();return n;}catch(Exception e){throw buyerBad("RETURN_VERSION_REQUIRED","A valid If-Match version is required.");}}
    private void validKey(String key){if(key==null||!KEY.matcher(key).matches())throw buyerBad("RETURN_IDEMPOTENCY_KEY_REQUIRED","A valid Idempotency-Key is required.");}
    private void ids(String... values){for(String v:values)if(v==null||!ID.matcher(v).matches())throw buyerBad("RETURN_ID_INVALID","A return identifier is invalid.");}
    private void validBusiness(String value){if(value==null||!BUSINESS_ID.matcher(value).matches())throw sellerNotFound();}
    private void enabled(){if(!properties.enabled())throw buyerBad("RETURNS_NOT_AVAILABLE","Returns are not available.");}
    private String safe(String c){return c==null||c.isBlank()?ids.next():c;}
    private String json(Object v){try{return mapper.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private BuyerOrderException buyerBad(String c,String m){return new BuyerOrderException(HttpStatus.BAD_REQUEST,c,m);}
    private BuyerOrderException buyerConflict(String c,String m){return new BuyerOrderException(HttpStatus.CONFLICT,c,m);}
    private BuyerOrderException buyerNotFound(){return new BuyerOrderException(HttpStatus.NOT_FOUND,"RETURN_NOT_FOUND","Return was not found.");}
    private BusinessOrderException sellerNotFound(){return new BusinessOrderException(HttpStatus.NOT_FOUND,"BUSINESS_RETURN_NOT_FOUND","Return was not found.");}
    private BusinessOrderException sellerConflict(){return new BusinessOrderException(HttpStatus.CONFLICT,"RETURN_STATE_CONFLICT","Return state changed.");}
}
