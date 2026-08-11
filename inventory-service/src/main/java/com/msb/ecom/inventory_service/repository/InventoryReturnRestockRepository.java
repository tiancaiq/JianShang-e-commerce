package com.msb.ecom.inventory_service.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
@Repository
public class InventoryReturnRestockRepository{
 private final JdbcTemplate jdbc; public InventoryReturnRestockRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<Record> findByReturn(String id){return query("return_id",id);}
 public Optional<Record> findByKey(String id){return query("idempotency_key",id);}
 private Optional<Record> query(String field,String value){return jdbc.query("SELECT * FROM inventory_return_restocks WHERE "+field+"=?",
   (rs,n)->new Record(rs.getString("id"),rs.getString("return_id"),rs.getString("order_id"),rs.getString("business_id"),
    rs.getString("reservation_id"),rs.getString("idempotency_key"),rs.getInt("restored_quantity"),rs.getTimestamp("completed_at").toInstant()),value).stream().findFirst();}
 public List<InventoryReservationItemRecord> lines(String reservation,String business){return jdbc.query("""
   SELECT id,reservation_id,inventory_item_id,business_id,listing_id,quantity,created_at,updated_at
   FROM inventory_reservation_items WHERE reservation_id=? AND business_id=? ORDER BY listing_id
   """,(rs,n)->new InventoryReservationItemRecord(rs.getString("id"),rs.getString("reservation_id"),rs.getString("inventory_item_id"),
   rs.getString("business_id"),rs.getString("listing_id"),rs.getInt("quantity"),rs.getTimestamp("created_at").toInstant(),
   rs.getTimestamp("updated_at").toInstant()),reservation,business);}
 public void insert(String id,String returnId,String orderId,String business,String reservation,String key,int quantity,String correlation,Instant now){jdbc.update("""
   INSERT INTO inventory_return_restocks(id,return_id,order_id,business_id,reservation_id,idempotency_key,restored_quantity,correlation_id,completed_at)
   VALUES (?,?,?,?,?,?,?,?,?)
   """,id,returnId,orderId,business,reservation,key,quantity,correlation,Timestamp.from(now));}
 public record Record(String id,String returnId,String orderId,String businessId,String reservationId,String key,int quantity,Instant completedAt){}
}
