package com.msb.ecom.order_service.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderOperationsRepository {
    private static final String SUPPORTED_NOTIFICATION_EVENTS = """
            ('business_order.accepted','business_order.processing_started','business_order.shipped',
             'business_order.delivered_demo','order.cancellation_completed','return.requested',
             'return.authorized','return.received','return.refund_completed')
            """;
    private final JdbcTemplate jdbc;
    public OrderOperationsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OrderOperationsContracts.Job> jobs() {
        return jdbc.query("""
                select checkout_id,state,attempt_count,last_error_code,next_attempt_at,
                       reconciled_at,created_at,updated_at
                from checkout_cart_reconciliations
                where state <> 'COMPLETED' or attempt_count > 0
                order by updated_at desc limit 100
                """,(rs,n)->{
            int attempts=rs.getInt("attempt_count");String state=rs.getString("state");String code=rs.getString("last_error_code");
            String status="COMPLETED".equals(state)?"SUCCEEDED":attempts>0?"FAILED":"PENDING";
            return new OrderOperationsContracts.Job(rs.getString("checkout_id"),"PURCHASED_CART_RECONCILIATION",
                    "ORDER",status,attempts,null,rs.getTimestamp("created_at").toInstant(),
                    instant(rs.getTimestamp("updated_at")),instant(rs.getTimestamp("next_attempt_at")),
                    null,code,summary(code),"FAILED".equals(status),"CHECKOUT",rs.getString("checkout_id"));});
    }

    public List<OrderOperationsContracts.Outbox> outbox() {
        return jdbc.query("""
                select id,aggregate_type,aggregate_id,event_type,notification_published_at,
                       notification_attempt_count,notification_next_attempt_at,
                       notification_last_error_code,correlation_id,created_at
                from order_outbox_events
                where (notification_published_at is null or notification_attempt_count > 0)
                  and ((event_type='order.confirmed' and event_version=2)
                    or event_type in """ + SUPPORTED_NOTIFICATION_EVENTS + """
                  )
                order by created_at desc limit 100
                """,(rs,n)->{
            Instant published=instant(rs.getTimestamp("notification_published_at"));int attempts=rs.getInt("notification_attempt_count");
            String status=published!=null?"SENT":attempts>0?"FAILED":"PENDING";String code=rs.getString("notification_last_error_code");
            return new OrderOperationsContracts.Outbox(rs.getString("id"),"ORDER",rs.getString("event_type"),
                    rs.getString("aggregate_type"),rs.getString("aggregate_id"),status,attempts,
                    rs.getTimestamp("created_at").toInstant(),null,instant(rs.getTimestamp("notification_next_attempt_at")),
                    rs.getString("correlation_id"),code,summary(code),"FAILED".equals(status));});
    }

    public Optional<OrderOperationsContracts.Job> job(String id){return jobs().stream().filter(v->id.equals(v.jobId())).findFirst();}
    public Optional<OrderOperationsContracts.Outbox> outbox(String id){return outbox().stream().filter(v->id.equals(v.eventId())).findFirst();}
    public boolean retryJob(String id,Instant now){return jdbc.update("update checkout_cart_reconciliations set next_attempt_at=?,updated_at=? where checkout_id=? and state='PENDING' and attempt_count>0",Timestamp.from(now),Timestamp.from(now),id)==1;}
    public boolean retryOutbox(String id,Instant now){return jdbc.update("""
            update order_outbox_events set notification_next_attempt_at=?
            where id=? and notification_published_at is null and notification_attempt_count>0
              and ((event_type='order.confirmed' and event_version=2)
                or event_type in """ + SUPPORTED_NOTIFICATION_EVENTS + ")",Timestamp.from(now),id)==1;}
    private static String summary(String code){return code==null?null:"The worker recorded "+code+". Raw exception details are not exposed.";}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
}
