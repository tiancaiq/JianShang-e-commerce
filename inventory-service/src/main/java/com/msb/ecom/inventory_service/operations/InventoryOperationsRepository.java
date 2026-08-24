package com.msb.ecom.inventory_service.operations;import lombok.RequiredArgsConstructor;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Repository;import java.sql.Timestamp;import java.time.*;import java.util.List;
@Repository @RequiredArgsConstructor
public class InventoryOperationsRepository {private final JdbcTemplate jdbc;
 public List<InventoryOperationsContracts.Issue> issues(Instant now){return jdbc.query("""
  select r.id,r.status,r.created_at,r.expires_at,min(i.listing_id) listing_id,sum(i.quantity) quantity
  from inventory_reservations r join inventory_reservation_items i on i.reservation_id=r.id
  where r.status='ACTIVE' and r.expires_at<=? group by r.id,r.status,r.created_at,r.expires_at order by r.expires_at limit 100
  """,(r,n)->{Instant created=r.getTimestamp("created_at").toInstant(),expires=r.getTimestamp("expires_at").toInstant();return new InventoryOperationsContracts.Issue(r.getString("id"),null,r.getString("listing_id"),r.getInt("quantity"),r.getString("status"),created,expires,Math.max(0,Duration.between(expires,now).toSeconds()),"EXPIRED_ACTIVE_RESERVATION","The reservation is past its expiry and remains active. The existing expiry worker owns cleanup.",false);},Timestamp.from(now));}
 public List<InventoryOperationsContracts.Outbox> outbox(){return jdbc.query("""
  select id,aggregate_type,aggregate_id,event_type,correlation_id,created_at,published_at,retry_count from inventory_outbox_events where published_at is null or retry_count>0 order by created_at desc limit 100
  """,(r,n)->new InventoryOperationsContracts.Outbox(r.getString("id"),"INVENTORY",r.getString("event_type"),r.getString("aggregate_type"),r.getString("aggregate_id"),r.getTimestamp("published_at")!=null?"SENT":"PENDING",r.getInt("retry_count"),r.getTimestamp("created_at").toInstant(),null,null,r.getString("correlation_id"),null,r.getTimestamp("published_at")==null?"Inventory does not currently expose a retry-capable publisher for this event.":null,false));}
}
