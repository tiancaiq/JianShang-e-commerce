package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.inventory_service.dto.InventoryReturnRestockRequest;
import com.msb.ecom.inventory_service.dto.InventoryReturnRestockResponse;
import com.msb.ecom.inventory_service.model.InventoryException;
import com.msb.ecom.inventory_service.model.ReservationStatus;
import com.msb.ecom.inventory_service.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class InventoryReturnRestockService{
 private final boolean enabled;private final InternalCommerceAuthenticator auth;private final InventoryReturnRestockRepository returns;
 private final InventoryReservationRepository reservations;private final InventoryRepository inventory;private final UlidGenerator ids;
 private final ObjectMapper mapper;private final TransactionTemplate tx;private final Clock clock=Clock.systemUTC();
 public InventoryReturnRestockService(@Value("${inventory.return-restock-enabled:false}") boolean enabled,
  InternalCommerceAuthenticator auth,InventoryReturnRestockRepository returns,InventoryReservationRepository reservations,
  InventoryRepository inventory,UlidGenerator ids,ObjectMapper mapper,PlatformTransactionManager manager){this.enabled=enabled;this.auth=auth;
  this.returns=returns;this.reservations=reservations;this.inventory=inventory;this.ids=ids;this.mapper=mapper;this.tx=new TransactionTemplate(manager);}

 // Restores only the purchased lines owned by one returned business group, exactly once.
 public InventoryReturnRestockResponse restock(String token,String reservation,String key,InventoryReturnRestockRequest req,String correlation){
  auth.requireAuthenticated(token);if(!enabled)throw error(HttpStatus.NOT_FOUND,"INVENTORY_RETURN_RESTOCK_NOT_AVAILABLE","Return restock is unavailable.");
  if(req==null||blank(req.returnId())||blank(req.orderId())||blank(req.businessId())||blank(reservation)||blank(key))
   throw error(HttpStatus.BAD_REQUEST,"INVENTORY_RETURN_RESTOCK_INVALID","A complete return restock command is required.");
  var keyed=returns.findByKey(key).orElse(null);if(keyed!=null)return replay(keyed,reservation,req);
  return tx.execute(s->execute(reservation,key,req,correlation==null?ids.next():correlation,clock.instant()));
 }
 private InventoryReturnRestockResponse execute(String reservation,String key,InventoryReturnRestockRequest req,String correlation,Instant now){
  var existing=returns.findByReturn(req.returnId()).orElse(null);if(existing!=null)return replay(existing,reservation,req);
  var held=reservations.lock(reservation).orElseThrow(()->error(HttpStatus.NOT_FOUND,"INVENTORY_RESERVATION_NOT_FOUND","Reservation was not found."));
  if(held.status()!=ReservationStatus.COMMITTED)throw error(HttpStatus.CONFLICT,"INVENTORY_RETURN_RESTOCK_STATE_CONFLICT","Only committed purchased inventory can be returned.");
  var lines=returns.lines(reservation,req.businessId());if(lines.isEmpty())throw error(HttpStatus.NOT_FOUND,"INVENTORY_RETURN_GROUP_NOT_FOUND","Return inventory group was not found.");
  var locked=inventory.lockItemsByListingIds(lines.stream().map(InventoryReservationItemRecord::listingId).toList());int restored=0;
  for(var line:lines){var item=locked.get(line.listingId());if(item==null||!item.id().equals(line.inventoryItemId()))throw new IllegalStateException("Return inventory evidence is inconsistent.");
   int after=Math.addExact(item.onHand(),line.quantity());if(inventory.updateBalances(item.id(),item.version(),after,item.reserved(),now)!=1)throw new IllegalStateException("Return restock lost its inventory lock.");
   inventory.insertMovement(ids.next(),item,"ADJUST","CUSTOMER_RETURN_RESTOCK",line.quantity(),item.onHand(),after,
    "Restocked sellable customer return "+req.returnId(),req.returnId(),ids.next(),correlation,now);restored=Math.addExact(restored,line.quantity());}
  String id=ids.next();returns.insert(id,req.returnId(),req.orderId(),req.businessId(),reservation,key,restored,correlation,now);
  try{inventory.insertOutbox(ids.next(),"INVENTORY_RESERVATION",reservation,"inventory.customer-return-restocked.v1",
   mapper.writeValueAsString(Map.of("returnId",req.returnId(),"businessId",req.businessId(),"restoredQuantity",restored)),correlation,req.returnId(),now);}
  catch(Exception e){throw new IllegalStateException("Return restock event serialization failed.",e);}
  return new InventoryReturnRestockResponse(id,req.returnId(),"COMPLETED",restored,now);
 }
 private InventoryReturnRestockResponse replay(InventoryReturnRestockRepository.Record r,String reservation,InventoryReturnRestockRequest q){
  if(!r.returnId().equals(q.returnId())||!r.orderId().equals(q.orderId())||!r.businessId().equals(q.businessId())||!r.reservationId().equals(reservation))
   throw error(HttpStatus.CONFLICT,"INVENTORY_RETURN_RESTOCK_IDEMPOTENCY_CONFLICT","Return restock conflicts with an existing command.");
  return new InventoryReturnRestockResponse(r.id(),r.returnId(),"COMPLETED",r.quantity(),r.completedAt());}
 private boolean blank(String v){return v==null||v.isBlank();}private InventoryException error(HttpStatus s,String c,String m){return new InventoryException(s,c,m);}
}
