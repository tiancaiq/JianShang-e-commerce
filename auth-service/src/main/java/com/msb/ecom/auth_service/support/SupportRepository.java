package com.msb.ecom.auth_service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.msb.ecom.auth_service.support.SupportContracts.*;

@Repository
@RequiredArgsConstructor
class SupportRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final String TICKET_SELECT = """
            select t.id,t.requester_user_id,t.category,t.subject,t.description,t.status,t.priority,
                   t.assigned_admin_id,t.resolved_at,t.resolution_code,t.resolution_reason,
                   t.duplicate_fingerprint,t.correlation_id,t.version,t.created_at,t.updated_at,
                   coalesce(nullif(u.display_name,''),'Marketplace user') requester_label,u.status requester_status,u.email
            from support_tickets t join users u on u.id=t.requester_user_id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    Optional<TicketRow> ticket(String id, boolean lock) {
        return jdbc.query(TICKET_SELECT + " where t.id=?" + (lock ? " for update" : ""), this::ticket, id)
                .stream().findFirst();
    }

    Optional<TicketRow> owned(String id, String requester) {
        return jdbc.query(TICKET_SELECT + " where t.id=? and t.requester_user_id=?", this::ticket, id, requester)
                .stream().findFirst();
    }

    UserSearchResult mine(String requester, int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from support_tickets where requester_user_id=?",
                Long.class, requester);
        List<TicketRow> rows = jdbc.query(TICKET_SELECT + " where t.requester_user_id=? order by t.updated_at desc,t.id desc limit ? offset ?",
                this::ticket, requester, size, (long) page * size);
        return new UserSearchResult(rows, total == null ? 0 : total);
    }

    AdminSearchResult search(AdminSearch filter) {
        StringBuilder where = new StringBuilder(" where 1=1\n");
        List<Object> args = new ArrayList<>();
        if (filter.query() != null) {
            where.append(" and (t.id=? or t.requester_user_id=? or lower(t.subject) like ? escape '!')\n");
            args.add(filter.query()); args.add(filter.query()); args.add("%" + like(filter.query().toLowerCase()) + "%");
        }
        add(where, args, "t.requester_user_id=?", filter.requester());
        addEnum(where, args, "t.category=?", filter.category());
        addEnum(where, args, "t.status=?", filter.status());
        addEnum(where, args, "t.priority=?", filter.priority());
        if (filter.assignment() != null && filter.assignment() != Assignment.ALL) {
            switch (filter.assignment()) {
                case UNASSIGNED -> where.append(" and t.assigned_admin_id is null\n");
                case ASSIGNED_TO_ME -> { where.append(" and t.assigned_admin_id=?\n"); args.add(filter.admin()); }
                case ASSIGNED -> where.append(" and t.assigned_admin_id is not null\n");
                default -> { }
            }
        }
        if (filter.orderId() != null) {
            where.append(" and exists(select 1 from support_ticket_links l where l.ticket_id=t.id and l.target_type='ORDER' and l.target_id=?)\n");
            args.add(filter.orderId());
        }
        if (filter.businessId() != null) {
            where.append(" and exists(select 1 from support_ticket_links l where l.ticket_id=t.id and l.target_type='BUSINESS' and l.target_id=?)\n");
            args.add(filter.businessId());
        }
        if (filter.from() != null) { where.append(" and t.created_at>=?\n"); args.add(Timestamp.from(filter.from())); }
        if (filter.to() != null) { where.append(" and t.created_at<?\n"); args.add(Timestamp.from(filter.to())); }
        Long total = jdbc.queryForObject("select count(*) from support_tickets t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(filter.size()); pageArgs.add((long) filter.page() * filter.size());
        List<TicketRow> rows = jdbc.query(TICKET_SELECT + where + " order by " + sort(filter.sort()) + " limit ? offset ?",
                this::ticket, pageArgs.toArray());
        return new AdminSearchResult(rows, total == null ? 0 : total);
    }

    void insertTicket(TicketRow row) {
        jdbc.update("""
                insert into support_tickets(id,requester_user_id,category,subject,description,status,priority,
                    assigned_admin_id,resolved_at,resolution_code,resolution_reason,duplicate_fingerprint,
                    correlation_id,version,created_at,updated_at)
                values(?,?,?,?,?,'OPEN','MEDIUM',null,null,null,null,?,?,0,?,?)
                """, row.id(), row.requester(), row.category().name(), row.subject(), row.description(),
                row.fingerprint(), row.correlation(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
    }

    Optional<String> recentDuplicate(String requester, String fingerprint, Instant since) {
        return jdbc.query("""
                select id from support_tickets where requester_user_id=? and duplicate_fingerprint=?
                  and created_at>=? order by created_at desc,id desc limit 1
                """, (r, n) -> r.getString(1), requester, fingerprint, Timestamp.from(since)).stream().findFirst();
    }

    boolean claim(String id, long version, String admin, Instant now) {
        return jdbc.update("""
                update support_tickets set assigned_admin_id=?,status='ASSIGNED',version=version+1,updated_at=?
                where id=? and version=? and assigned_admin_id is null and status='OPEN'
                """, admin, Timestamp.from(now), id, version) == 1;
    }
    boolean release(String id, long version, String admin, Instant now) {
        return jdbc.update("""
                update support_tickets set assigned_admin_id=null,status='OPEN',version=version+1,updated_at=?
                where id=? and version=? and assigned_admin_id=? and status in ('ASSIGNED','UNDER_REVIEW','WAITING_FOR_USER')
                """, Timestamp.from(now), id, version, admin) == 1;
    }
    boolean touchForAdmin(String id, long version, String admin, Status status, Instant now) {
        return jdbc.update("""
                update support_tickets set status=?,version=version+1,updated_at=?
                where id=? and version=? and assigned_admin_id=? and status in ('ASSIGNED','UNDER_REVIEW','WAITING_FOR_USER')
                """, status.name(), Timestamp.from(now), id, version, admin) == 1;
    }
    boolean requesterReply(String id, long version, String requester, Instant now) {
        return jdbc.update("""
                update support_tickets set status=case when status='WAITING_FOR_USER' then 'UNDER_REVIEW' else status end,
                    version=version+1,updated_at=?
                where id=? and version=? and requester_user_id=? and status<>'RESOLVED'
                """, Timestamp.from(now), id, version, requester) == 1;
    }
    boolean priority(String id, long version, String admin, Priority priority, Instant now) {
        return jdbc.update("""
                update support_tickets set priority=?,status=case when status='ASSIGNED' then 'UNDER_REVIEW' else status end,
                    version=version+1,updated_at=?
                where id=? and version=? and assigned_admin_id=? and status<>'RESOLVED'
                """, priority.name(), Timestamp.from(now), id, version, admin) == 1;
    }
    boolean resolve(String id, long version, String admin, ResolutionCode code, String reason, Instant now) {
        return jdbc.update("""
                update support_tickets set status='RESOLVED',resolution_code=?,resolution_reason=?,resolved_at=?,
                    version=version+1,updated_at=? where id=? and version=? and assigned_admin_id=? and status<>'RESOLVED'
                """, code.name(), reason, Timestamp.from(now), Timestamp.from(now), id, version, admin) == 1;
    }
    boolean touchLink(String id, long version, String admin, Instant now) {
        return jdbc.update("""
                update support_tickets set status=case when status='ASSIGNED' then 'UNDER_REVIEW' else status end,
                    version=version+1,updated_at=? where id=? and version=? and assigned_admin_id=? and status<>'RESOLVED'
                """, Timestamp.from(now), id, version, admin) == 1;
    }

    boolean insertMessage(MessageRow row) {
        try {
            return jdbc.update("""
                    insert into support_messages(id,ticket_id,author_type,author_user_id,body,idempotency_key,request_hash,created_at)
                    values(?,?,?,?,?,?,?,?)
                    """, row.id(), row.ticket(), row.authorType(), row.authorId(), row.body(), row.key(), row.hash(),
                    Timestamp.from(row.createdAt())) == 1;
        } catch (DuplicateKeyException ignored) { return false; }
    }
    Optional<MessageRow> messageRetry(String ticket, String author, String key) {
        return jdbc.query("""
                select id,ticket_id,author_type,author_user_id,body,idempotency_key,request_hash,created_at,
                       null author_display_name
                from support_messages where ticket_id=? and author_user_id=? and idempotency_key=?
                """, this::message, ticket, author, key).stream().findFirst();
    }
    List<MessageRow> messages(String ticket) {
        return jdbc.query("""
                select message.id,message.ticket_id,message.author_type,message.author_user_id,message.body,
                       message.idempotency_key,message.request_hash,message.created_at,
                       coalesce(nullif(trim(admin.display_name),''),'Support administrator') author_display_name
                from support_messages message
                left join users admin on admin.id=message.author_user_id and message.author_type='SUPPORT_ADMIN'
                where message.ticket_id=? order by message.created_at,message.id
                """, this::message, ticket);
    }
    boolean insertNote(NoteRow row) {
        try {
            return jdbc.update("""
                    insert into support_internal_notes(id,ticket_id,author_admin_id,author_display_name,body,
                        idempotency_key,request_hash,created_at) values(?,?,?,?,?,?,?,?)
                    """, row.id(), row.ticket(), row.adminId(), row.adminName(), row.body(), row.key(), row.hash(),
                    Timestamp.from(row.createdAt())) == 1;
        } catch (DuplicateKeyException ignored) { return false; }
    }
    Optional<NoteRow> noteRetry(String ticket, String admin, String key) {
        return jdbc.query("""
                select id,ticket_id,author_admin_id,author_display_name,body,idempotency_key,request_hash,created_at
                from support_internal_notes where ticket_id=? and author_admin_id=? and idempotency_key=?
                """, this::note, ticket, admin, key).stream().findFirst();
    }
    List<NoteRow> notes(String ticket) {
        return jdbc.query("""
                select id,ticket_id,author_admin_id,author_display_name,body,idempotency_key,request_hash,created_at
                from support_internal_notes where ticket_id=? order by created_at,id
                """, this::note, ticket);
    }

    boolean insertLink(LinkRow row) {
        try {
            return jdbc.update("""
                    insert into support_ticket_links(ticket_id,target_type,target_id,relation_type,safe_label,admin_path,
                        linked_by_type,linked_by,linked_at) values(?,?,?,?,?,?,?,?,?)
                    """, row.ticket(), row.type().name(), row.targetId(), row.relation().name(), row.label(), row.path(),
                    row.linkedByType(), row.linkedBy(), Timestamp.from(row.linkedAt())) == 1;
        } catch (DuplicateKeyException ignored) { return false; }
    }
    boolean deleteLink(String ticket, TargetType type, String id) {
        return jdbc.update("delete from support_ticket_links where ticket_id=? and target_type=? and target_id=?",
                ticket, type.name(), id) == 1;
    }
    List<LinkRow> links(String ticket) {
        return jdbc.query("""
                select ticket_id,target_type,target_id,relation_type,safe_label,admin_path,linked_by_type,linked_by,linked_at
                from support_ticket_links where ticket_id=? order by linked_at,target_type,target_id
                """, this::link, ticket);
    }
    // Loads the first linked order for every ticket on an inbox page without a query per row.
    Map<String, String> firstOrderIds(List<String> tickets) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tickets.isEmpty()) return result;
        String placeholders = String.join(",", tickets.stream().map(ignored -> "?").toList());
        jdbc.query("""
                select ticket_id,target_id from support_ticket_links
                where target_type='ORDER' and ticket_id in (%s)
                order by linked_at,target_id
                """.formatted(placeholders), (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)), tickets.toArray())
                .forEach(order -> result.putIfAbsent(order.getKey(), order.getValue()));
        return result;
    }
    Optional<LinkRow> link(String ticket, TargetType type, String target) {
        return jdbc.query("""
                select ticket_id,target_type,target_id,relation_type,safe_label,admin_path,linked_by_type,linked_by,linked_at
                from support_ticket_links where ticket_id=? and target_type=? and target_id=?
                """, this::link, ticket, type.name(), target).stream().findFirst();
    }

    boolean insertEscalation(EscalationRow row) {
        try {
            return jdbc.update("""
                    insert into support_escalations(id,ticket_id,destination_type,destination_id,created_by_admin_id,
                        reason,idempotency_key,request_hash,created_at) values(?,?,?,?,?,?,?,?,?)
                    """, row.id(), row.ticket(), row.type().name(), row.destinationId(), row.admin(), row.reason(),
                    row.key(), row.hash(), Timestamp.from(row.createdAt())) == 1;
        } catch (DuplicateKeyException ignored) { return false; }
    }
    Optional<EscalationRow> escalationRetry(String ticket, String admin, String key) {
        return jdbc.query("""
                select id,ticket_id,destination_type,destination_id,created_by_admin_id,reason,idempotency_key,request_hash,created_at
                from support_escalations where ticket_id=? and created_by_admin_id=? and idempotency_key=?
                """, this::escalation, ticket, admin, key).stream().findFirst();
    }
    List<EscalationRow> escalations(String ticket) {
        return jdbc.query("""
                select id,ticket_id,destination_type,destination_id,created_by_admin_id,reason,idempotency_key,request_hash,created_at
                from support_escalations where ticket_id=? order by created_at,id
                """, this::escalation, ticket);
    }

    void insertEvent(EventRow row) {
        jdbc.update("""
                insert into support_ticket_events(event_id,ticket_id,event_type,occurred_at,actor_type,actor_id,
                    actor_display_name,source,previous_state,new_state,reason_code,reason,correlation_id,request_id,safe_metadata)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as json))
                """, row.id(), row.ticket(), row.type(), Timestamp.from(row.at()), row.actorType(), row.actorId(),
                row.actorName(), row.source(), row.previous(), row.next(), row.reasonCode(), row.reason(),
                row.correlation(), row.requestId(), json(row.metadata()));
    }
    List<EventRow> events(String ticket) {
        return jdbc.query("""
                select event_id,ticket_id,event_type,occurred_at,actor_type,actor_id,actor_display_name,source,
                    previous_state,new_state,reason_code,reason,correlation_id,request_id,safe_metadata
                from support_ticket_events where ticket_id=? order by occurred_at,event_id
                """, this::event, ticket);
    }

    Optional<CommandRow> command(String actor, String operation, String key) {
        return jdbc.query("""
                select id,actor_user_id,operation,idempotency_key,request_hash,ticket_id,created_at
                from support_command_idempotency where actor_user_id=? and operation=? and idempotency_key=?
                """, this::command, actor, operation, key).stream().findFirst();
    }
    boolean insertCommand(CommandRow row) {
        try {
            return jdbc.update("""
                    insert into support_command_idempotency(id,actor_user_id,operation,idempotency_key,request_hash,
                        ticket_id,created_at,expires_at) values(?,?,?,?,?,null,?,?)
                    """, row.id(), row.actor(), row.operation(), row.key(), row.hash(), Timestamp.from(row.createdAt()),
                    Timestamp.from(row.createdAt().plusSeconds(604800))) == 1;
        } catch (DuplicateKeyException ignored) { return false; }
    }
    void completeCommand(String id, String ticket) {
        jdbc.update("update support_command_idempotency set ticket_id=? where id=? and ticket_id is null", ticket, id);
    }

    Optional<LocalContext> local(TargetType type, String id) {
        return switch (type) {
            case USER -> jdbc.query("select coalesce(nullif(display_name,''),'Marketplace user') label,status from users where id=?",
                    (r,n)->new LocalContext(r.getString("label"), "/admin/users/"+id,
                            Map.of("userId",id,"accountState",r.getString("status"))), id).stream().findFirst();
            case BUSINESS -> jdbc.query("""
                    select coalesce(nullif(s.name,''),b.legal_name) label,b.status
                    from businesses b left join stores s on s.business_id=b.id where b.id=?
                    """, (r,n)->new LocalContext(r.getString("label"), "/admin/businesses/"+id,
                            Map.of("businessId",id,"status",r.getString("status"))), id).stream().findFirst();
            case TRUST_AND_SAFETY_REPORT -> jdbc.query("select safe_target_label,status from reports where id=?",
                    (r,n)->new LocalContext("Report · "+r.getString(1), "/admin/reports/"+id,
                            Map.of("reportId",id,"status",r.getString(2))), id).stream().findFirst();
            case INVESTIGATION_CASE -> jdbc.query("select title,status from investigation_cases where id=?",
                    (r,n)->new LocalContext(r.getString(1), "/admin/cases/"+id,
                            Map.of("caseId",id,"status",r.getString(2))), id).stream().findFirst();
            default -> Optional.empty();
        };
    }
    boolean activeBusinessMembership(String user, String business) {
        Long count=jdbc.queryForObject("""
                select count(*) from business_memberships where user_id=? and business_id=? and status='ACTIVE'
                """, Long.class, user, business);
        return count != null && count > 0;
    }
    String adminName(String id) { return jdbc.query("select coalesce(nullif(display_name,''),'Support administrator') from users where id=?",
            (r,n)->r.getString(1), id).stream().findFirst().orElse("Support administrator"); }

    private TicketRow ticket(ResultSet r, int n) throws SQLException {
        return new TicketRow(r.getString("id"),r.getString("requester_user_id"),Category.valueOf(r.getString("category")),
                r.getString("subject"),r.getString("description"),Status.valueOf(r.getString("status")),
                Priority.valueOf(r.getString("priority")),r.getString("assigned_admin_id"),instant(r.getTimestamp("resolved_at")),
                r.getString("resolution_code"),r.getString("resolution_reason"),r.getString("duplicate_fingerprint"),
                r.getString("correlation_id"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("updated_at").toInstant(),r.getString("requester_label"),r.getString("requester_status"),r.getString("email"));
    }
    private MessageRow message(ResultSet r,int n)throws SQLException{return new MessageRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getTimestamp(8).toInstant(),r.getString(9));}
    private NoteRow note(ResultSet r,int n)throws SQLException{return new NoteRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getTimestamp(8).toInstant());}
    private LinkRow link(ResultSet r,int n)throws SQLException{return new LinkRow(r.getString(1),TargetType.valueOf(r.getString(2)),r.getString(3),RelationType.valueOf(r.getString(4)),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getTimestamp(9).toInstant());}
    private EscalationRow escalation(ResultSet r,int n)throws SQLException{return new EscalationRow(r.getString(1),r.getString(2),DestinationType.valueOf(r.getString(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getTimestamp(9).toInstant());}
    private EventRow event(ResultSet r,int n)throws SQLException{return new EventRow(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),map(r.getString(15)));}
    private CommandRow command(ResultSet r,int n)throws SQLException{return new CommandRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant());}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,String> map(String value){try{return mapper.readValue(value,STRING_MAP);}catch(Exception e){return Map.of();}}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static void add(StringBuilder w,List<Object>a,String sql,Object value){if(value!=null){w.append(" and ").append(sql).append('\n');a.add(value);}}
    private static void addEnum(StringBuilder w,List<Object>a,String sql,Enum<?> value){if(value!=null){w.append(" and ").append(sql).append('\n');a.add(value.name());}}
    private static String like(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
    private static String sort(String value){return switch(value){case"createdAt,asc"->"t.created_at asc,t.id asc";case"priority,desc"->"field(t.priority,'URGENT','HIGH','MEDIUM','LOW'),t.updated_at desc,t.id desc";default->"t.updated_at desc,t.id desc";};}

    record TicketRow(String id,String requester,Category category,String subject,String description,Status status,
                     Priority priority,String assignedAdmin,Instant resolvedAt,String resolutionCode,String resolutionReason,
                     String fingerprint,String correlation,long version,Instant createdAt,Instant updatedAt,
                     String requesterLabel,String requesterStatus,String email) { }
    record UserSearchResult(List<TicketRow> rows,long total) { }
    record AdminSearchResult(List<TicketRow> rows,long total) { }
    record AdminSearch(String query,String requester,Category category,Status status,Priority priority,
                       Assignment assignment,String orderId,String businessId,Instant from,Instant to,
                       int page,int size,String sort,String admin) { }
    record MessageRow(String id,String ticket,String authorType,String authorId,String body,String key,String hash,
                      Instant createdAt,String authorName) { }
    record NoteRow(String id,String ticket,String adminId,String adminName,String body,String key,String hash,Instant createdAt) { }
    record LinkRow(String ticket,TargetType type,String targetId,RelationType relation,String label,String path,
                   String linkedByType,String linkedBy,Instant linkedAt) { }
    record EscalationRow(String id,String ticket,DestinationType type,String destinationId,String admin,String reason,
                         String key,String hash,Instant createdAt) { }
    record EventRow(String id,String ticket,String type,Instant at,String actorType,String actorId,String actorName,
                    String source,String previous,String next,String reasonCode,String reason,String correlation,
                    String requestId,Map<String,String> metadata) { }
    record CommandRow(String id,String actor,String operation,String key,String hash,String ticket,Instant createdAt) { }
    record LocalContext(String label,String path,Map<String,Object> details) { }
}
