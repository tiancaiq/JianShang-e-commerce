package com.msb.ecom.auth_service.support;

import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.msb.ecom.auth_service.support.SupportContracts.*;

@Service
public class SupportService {
    private static final Pattern ULID=Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Set<String> SORTS=Set.of("updatedAt,desc","createdAt,asc","priority,desc");
    private final AuthService auth;
    private final AdminAuthorizationService authorization;
    private final SupportRepository repository;
    private final SupportExternalContextClient external;
    private final UlidGenerator ids;
    private final Clock clock;

    public SupportService(AuthService auth,AdminAuthorizationService authorization,SupportRepository repository,
            SupportExternalContextClient external,UlidGenerator ids,Clock clock){this.auth=auth;this.authorization=authorization;
        this.repository=repository;this.external=external;this.ids=ids;this.clock=clock;}

    @Transactional
    public UserDetail create(CreateTicketRequest request,String key,String correlation){
        User user=auth.ensureUserEntity();ValidatedCreate input=validateCreate(request,key);String requestHash=hash(input.normalized());
        SupportRepository.CommandRow replay=repository.command(user.getId(),"CREATE_TICKET",input.key()).orElse(null);
        if(replay!=null)return userReplay(replay,requestHash,user.getId());
        List<ValidatedLink> links=new ArrayList<>();
        links.add(localLink(TargetType.USER,user.getId(),user.getId()));
        if(input.order()!=null)links.add(validatedLink(TargetType.ORDER,input.order(),user.getId()));
        if(input.business()!=null){if(!repository.activeBusinessMembership(user.getId(),input.business()))throw SupportException.invalidLink();links.add(localLink(TargetType.BUSINESS,input.business(),user.getId()));}
        if(input.listing()!=null)links.add(validatedLink(TargetType.LISTING,input.listing(),user.getId()));
        String fingerprint=hash(user.getId()+"|"+input.category()+"|"+input.subject().toLowerCase()+"|"+input.order()+"|"+input.business()+"|"+input.listing());
        if(repository.recentDuplicate(user.getId(),fingerprint,clock.instant().minus(Duration.ofMinutes(10))).isPresent())
            throw SupportException.conflict("SUPPORT_DUPLICATE_TICKET","A matching support ticket was submitted recently.");
        Instant now=clock.instant();String ticketId=ids.next();SupportRepository.CommandRow command=new SupportRepository.CommandRow(ids.next(),user.getId(),"CREATE_TICKET",input.key(),requestHash,null,now);
        if(!repository.insertCommand(command))return userReplay(repository.command(user.getId(),"CREATE_TICKET",input.key()).orElseThrow(),requestHash,user.getId());
        repository.insertTicket(new SupportRepository.TicketRow(ticketId,user.getId(),input.category(),input.subject(),input.description(),Status.OPEN,Priority.MEDIUM,null,null,null,null,fingerprint,correlation,0,now,now,null,null,null));
        for(ValidatedLink link:links)repository.insertLink(new SupportRepository.LinkRow(ticketId,link.type(),link.id(),RelationType.SUBMITTED_WITH,link.label(),link.path(),"REQUESTER",user.getId(),now));
        repository.insertEvent(event(ticketId,"SUPPORT_TICKET_CREATED",now,"REQUESTER",user.getId(),safeName(user),"USER_PORTAL",null,"OPEN",null,null,correlation,ids.next(),Map.of("category",input.category().name())));
        repository.completeCommand(command.id(),ticketId);return userDetail(repository.owned(ticketId,user.getId()).orElseThrow(SupportException::notFound));
    }

    @Transactional(readOnly=true)
    public UserPage mine(int page,int size){User user=auth.ensureUserEntity();int p=Math.max(0,page),s=Math.max(1,Math.min(size,50));var result=repository.mine(user.getId(),p,s);
        return new UserPage(result.rows().stream().map(this::userSummary).toList(),p,s,result.total(),pages(result.total(),s));}

    @Transactional(readOnly=true)
    public UserDetail userDetail(String raw){User user=auth.ensureUserEntity();return userDetail(repository.owned(id(raw),user.getId()).orElseThrow(SupportException::notFound));}

    @Transactional
    public UserDetail userMessage(String raw,MessageRequest request,String key,String correlation){User user=auth.ensureUserEntity();String ticket=id(raw);var before=repository.owned(ticket,user.getId()).orElseThrow(SupportException::notFound);
        String body=body(request==null?null:request.body());String retryKey=key(request==null?null:key==null?request.idempotencyKey():key);String requestHash=hash(body);
        var retry=repository.messageRetry(ticket,user.getId(),retryKey).orElse(null);if(retry!=null){same(retry.hash(),requestHash);return userDetail(repository.owned(ticket,user.getId()).orElseThrow());}
        if(before.status()==Status.RESOLVED)throw SupportException.conflict("SUPPORT_TICKET_ALREADY_RESOLVED","Resolved support tickets are read-only.");long expected=version(request==null?null:request.expectedVersion());Instant now=clock.instant();
        if(!repository.requesterReply(ticket,expected,user.getId(),now))mutationConflict();
        if(!repository.insertMessage(new SupportRepository.MessageRow(ids.next(),ticket,"REQUESTER",user.getId(),body,retryKey,requestHash,now,null)))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The message retry could not be reconciled.");
        repository.insertEvent(event(ticket,"USER_MESSAGE_RECEIVED",now,"REQUESTER",user.getId(),safeName(user),"USER_PORTAL",before.status().name(),before.status()==Status.WAITING_FOR_USER?"UNDER_REVIEW":before.status().name(),null,null,correlation,ids.next(),Map.of()));
        return userDetail(repository.owned(ticket,user.getId()).orElseThrow());}

    @Transactional(readOnly=true)
    public AdminPage search(String q,String requester,Category category,Status status,Priority priority,Assignment assignment,
            String orderId,String businessId,Instant from,Instant to,int page,int size,String sort){Admin admin=admin(AdminPermission.SUPPORT_READ);int p=Math.max(0,page),s=Math.max(1,Math.min(size,100));String normalizedSort=sort(sort);
        if(from!=null&&to!=null&&!from.isBefore(to))throw SupportException.invalid("SUPPORT_DATE_RANGE_INVALID","Created-from must be before created-to.");
        var result=repository.search(new SupportRepository.AdminSearch(text(q,160),optionalId(requester),category,status,priority,assignment==null?Assignment.ALL:assignment,optionalId(orderId),optionalId(businessId),from,to,p,s,normalizedSort,admin.user().getId()));
        Map<String,String> orderIds=repository.firstOrderIds(result.rows().stream().map(SupportRepository.TicketRow::id).toList());
        return new AdminPage(result.rows().stream().map(row->adminSummary(row,orderIds.get(row.id()))).toList(),p,s,result.total(),pages(result.total(),s),normalizedSort);}

    @Transactional(readOnly=true)
    public AdminDetail adminDetail(String raw){Admin admin=admin(AdminPermission.SUPPORT_READ);return adminDetail(repository.ticket(id(raw),false).orElseThrow(SupportException::notFound),admin);}

    @Transactional public AdminDetail claim(String raw,VersionRequest request,String correlation){Admin a=admin(AdminPermission.SUPPORT_ASSIGN);var before=ticket(raw);long v=version(request==null?null:request.expectedVersion());Instant now=clock.instant();
        if(before.version()!=v)mutationConflict();if(before.status()==Status.RESOLVED||before.assignedAdmin()!=null)throw SupportException.conflict("SUPPORT_TICKET_NOT_ASSIGNABLE","The support ticket cannot be claimed in its current state.");
        if(!repository.claim(before.id(),v,a.user().getId(),now))mutationConflict();eventMutation(before,a,"SUPPORT_TICKET_CLAIMED","ASSIGNED",null,null,correlation,now);return adminDetail(ticket(raw),a);}
    @Transactional public AdminDetail release(String raw,VersionRequest request,String correlation){Admin a=admin(AdminPermission.SUPPORT_ASSIGN);var before=ticket(raw);long v=version(request==null?null:request.expectedVersion());Instant now=clock.instant();
        if(before.version()!=v)mutationConflict();assigned(before,a);
        if(!repository.release(before.id(),v,a.user().getId(),now))mutationConflict();eventMutation(before,a,"SUPPORT_TICKET_RELEASED","OPEN",null,null,correlation,now);return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail respond(String raw,MessageRequest request,String key,String correlation){return adminMessage(raw,request,key,false,correlation);}
    @Transactional public AdminDetail requestInformation(String raw,MessageRequest request,String key,String correlation){return adminMessage(raw,request,key,true,correlation);}

    private AdminDetail adminMessage(String raw,MessageRequest request,String headerKey,boolean information,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESPOND);var before=ticket(raw);assigned(before,a);String body=body(request==null?null:request.body());String retryKey=key(request==null?null:headerKey==null?request.idempotencyKey():headerKey);String requestHash=hash((information?"INFO|":"MESSAGE|")+body);
        var retry=repository.messageRetry(before.id(),a.user().getId(),retryKey).orElse(null);if(retry!=null){same(retry.hash(),requestHash);return adminDetail(ticket(raw),a);}long v=version(request==null?null:request.expectedVersion());Instant now=clock.instant();Status next=information?Status.WAITING_FOR_USER:Status.UNDER_REVIEW;
        if(!repository.touchForAdmin(before.id(),v,a.user().getId(),next,now))mutationConflict();if(!repository.insertMessage(new SupportRepository.MessageRow(ids.next(),before.id(),"SUPPORT_ADMIN",a.user().getId(),body,retryKey,requestHash,now,a.name())))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The response retry could not be reconciled.");
        String eventType=information?"INFORMATION_REQUESTED":"SUPPORT_MESSAGE_SENT";repository.insertEvent(event(before.id(),eventType,now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),next.name(),null,null,correlation,ids.next(),Map.of()));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail note(String raw,NoteRequest request,String headerKey,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESPOND);var before=ticket(raw);assigned(before,a);String body=body(request==null?null:request.body());String retryKey=key(request==null?null:headerKey==null?request.idempotencyKey():headerKey);String requestHash=hash(body);
        var retry=repository.noteRetry(before.id(),a.user().getId(),retryKey).orElse(null);if(retry!=null){same(retry.hash(),requestHash);return adminDetail(ticket(raw),a);}long v=version(request==null?null:request.expectedVersion());Instant now=clock.instant();if(!repository.touchForAdmin(before.id(),v,a.user().getId(),before.status()==Status.ASSIGNED?Status.UNDER_REVIEW:before.status(),now))mutationConflict();
        if(!repository.insertNote(new SupportRepository.NoteRow(ids.next(),before.id(),a.user().getId(),a.name(),body,retryKey,requestHash,now)))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The note retry could not be reconciled.");repository.insertEvent(event(before.id(),"INTERNAL_NOTE_ADDED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),before.status()==Status.ASSIGNED?"UNDER_REVIEW":before.status().name(),null,null,correlation,ids.next(),Map.of()));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail priority(String raw,PriorityRequest request,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESPOND);var before=ticket(raw);assigned(before,a);if(request==null||request.priority()==null)throw SupportException.invalid("SUPPORT_PRIORITY_REQUIRED","Priority is required.");String reason=reason(request.reason());long v=version(request.expectedVersion());Instant now=clock.instant();
        if(!repository.priority(before.id(),v,a.user().getId(),request.priority(),now))mutationConflict();repository.insertEvent(event(before.id(),"PRIORITY_CHANGED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.priority().name(),request.priority().name(),"OPERATIONAL_REASSESSMENT",reason,correlation,ids.next(),Map.of()));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail link(String raw,LinkRequest request,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESPOND);var before=ticket(raw);assigned(before,a);if(request==null||request.targetType()==null)throw SupportException.invalid("SUPPORT_LINK_REQUIRED","Link type and target are required.");TargetType type=request.targetType();String target=id(request.targetId());ValidatedLink context=validatedLink(type,target,null);long v=version(request.expectedVersion());Instant now=clock.instant();
        if(repository.link(before.id(),type,target).isPresent())throw SupportException.conflict("SUPPORT_LINK_ALREADY_EXISTS","The marketplace record is already linked.");if(!repository.touchLink(before.id(),v,a.user().getId(),now))mutationConflict();repository.insertLink(new SupportRepository.LinkRow(before.id(),type,target,request.relationType()==null?RelationType.RELATED:request.relationType(),context.label(),context.path(),"ADMIN",a.user().getId(),now));repository.insertEvent(event(before.id(),"ENTITY_LINKED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),before.status()==Status.ASSIGNED?"UNDER_REVIEW":before.status().name(),null,null,correlation,ids.next(),Map.of("targetType",type.name(),"targetId",target)));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail unlink(String raw,TargetType type,String targetId,UnlinkRequest request,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESPOND);var before=ticket(raw);assigned(before,a);String target=id(targetId);String reason=reason(request==null?null:request.reason());long v=version(request==null?null:request.expectedVersion());if(repository.link(before.id(),type,target).isEmpty())throw SupportException.invalidLink();Instant now=clock.instant();if(!repository.touchLink(before.id(),v,a.user().getId(),now))mutationConflict();repository.deleteLink(before.id(),type,target);repository.insertEvent(event(before.id(),"ENTITY_UNLINKED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),before.status()==Status.ASSIGNED?"UNDER_REVIEW":before.status().name(),null,reason,correlation,ids.next(),Map.of("targetType",type.name(),"targetId",target)));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail escalate(String raw,EscalationRequest request,String headerKey,String correlation){Admin a=admin(AdminPermission.SUPPORT_ESCALATE);var before=ticket(raw);assigned(before,a);if(request==null||request.destinationType()==null)throw SupportException.invalid("SUPPORT_ESCALATION_REQUIRED","Escalation destination is required.");String destination=id(request.destinationId());String reason=reason(request.reason());String retryKey=key(headerKey==null?request.idempotencyKey():headerKey);TargetType target=targetFor(request.destinationType());ValidatedLink context=validatedLink(target,destination,null);String requestHash=hash(request.destinationType()+"|"+destination+"|"+reason);
        var retry=repository.escalationRetry(before.id(),a.user().getId(),retryKey).orElse(null);if(retry!=null){same(retry.hash(),requestHash);return adminDetail(ticket(raw),a);}long v=version(request.expectedVersion());Instant now=clock.instant();if(!repository.touchLink(before.id(),v,a.user().getId(),now))mutationConflict();
        repository.insertLink(new SupportRepository.LinkRow(before.id(),target,destination,RelationType.ESCALATION,context.label(),context.path(),"ADMIN",a.user().getId(),now));if(!repository.insertEscalation(new SupportRepository.EscalationRow(ids.next(),before.id(),request.destinationType(),destination,a.user().getId(),reason,retryKey,requestHash,now)))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The escalation retry could not be reconciled.");repository.insertEvent(event(before.id(),"ESCALATION_CREATED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),before.status()==Status.ASSIGNED?"UNDER_REVIEW":before.status().name(),request.destinationType().name(),reason,correlation,ids.next(),Map.of("destinationId",destination)));return adminDetail(ticket(raw),a);}

    @Transactional public AdminDetail resolve(String raw,ResolutionRequest request,String headerKey,String correlation){Admin a=admin(AdminPermission.SUPPORT_RESOLVE);var before=ticket(raw);assigned(before,a);if(request==null||request.resolutionCode()==null)throw SupportException.invalid("SUPPORT_RESOLUTION_REQUIRED","Resolution code is required.");String reason=reason(request.reason());String retryKey=key(headerKey==null?request.idempotencyKey():headerKey);String requestHash=hash(before.id()+"|"+request.resolutionCode()+"|"+reason);var replay=repository.command(a.user().getId(),"RESOLVE_TICKET",retryKey).orElse(null);if(replay!=null){same(replay.hash(),requestHash);return adminDetail(repository.ticket(before.id(),false).orElseThrow(),a);}long v=version(request.expectedVersion());Instant now=clock.instant();var command=new SupportRepository.CommandRow(ids.next(),a.user().getId(),"RESOLVE_TICKET",retryKey,requestHash,null,now);if(!repository.insertCommand(command))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The resolution retry could not be reconciled.");if(!repository.resolve(before.id(),v,a.user().getId(),request.resolutionCode(),reason,now))mutationConflict();repository.insertEvent(event(before.id(),"SUPPORT_TICKET_RESOLVED",now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),"RESOLVED",request.resolutionCode().name(),reason,correlation,ids.next(),Map.of()));repository.completeCommand(command.id(),before.id());return adminDetail(ticket(raw),a);}

    private UserDetail userReplay(SupportRepository.CommandRow row,String requestHash,String user){same(row.hash(),requestHash);if(row.ticket()==null)throw SupportException.conflict("SUPPORT_COMMAND_IN_PROGRESS","The same support request is still being processed.");return userDetail(repository.owned(row.ticket(),user).orElseThrow(SupportException::notFound));}
    private UserDetail userDetail(SupportRepository.TicketRow row){List<UserLink> links=repository.links(row.id()).stream().filter(l->l.type()!=TargetType.USER).map(l->new UserLink(l.type(),l.targetId(),l.label())).toList();List<UserMessage> messages=repository.messages(row.id()).stream().map(m->new UserMessage(m.id(),"REQUESTER".equals(m.authorType())?"You":"Support team",m.body(),m.createdAt())).toList();Set<String> visible=Set.of("SUPPORT_TICKET_CREATED","SUPPORT_MESSAGE_SENT","USER_MESSAGE_RECEIVED","INFORMATION_REQUESTED","SUPPORT_TICKET_RESOLVED");List<UserEvent> history=repository.events(row.id()).stream().filter(e->visible.contains(e.type())).map(e->new UserEvent(e.id(),e.at(),e.type(),userEventLabel(e.type()))).toList();return new UserDetail(row.id(),row.category(),row.subject(),row.description(),row.status(),row.createdAt(),row.updatedAt(),row.version(),links,messages,row.resolutionCode(),row.resolutionReason(),row.resolvedAt(),history);}
    private AdminDetail adminDetail(SupportRepository.TicketRow row,Admin admin){var access=admin.access();List<SupportRepository.LinkRow> rawLinks=repository.links(row.id());Map<String,Object> contexts=new LinkedHashMap<>();for(var link:rawLinks){try{ValidatedLink value=validatedLink(link.type(),link.targetId(),null);contexts.put(link.type().name()+":"+link.targetId(),value.details());}catch(SupportException ignored){contexts.put(link.type().name()+":"+link.targetId(),Map.of("available",false,"state","TEMPORARILY_UNAVAILABLE"));}}
        List<AdminMessage> messages=repository.messages(row.id()).stream().map(m->new AdminMessage(m.id(),m.authorType(),m.authorId(),"REQUESTER".equals(m.authorType())?row.requesterLabel():m.authorName(),m.body(),m.createdAt())).toList();List<InternalNote> notes=repository.notes(row.id()).stream().map(n->new InternalNote(n.id(),n.adminId(),n.adminName(),n.body(),n.createdAt())).toList();List<AdminLink> links=rawLinks.stream().map(l->new AdminLink(l.type(),l.targetId(),l.relation(),l.label(),l.path(),l.linkedByType(),l.linkedBy(),l.linkedAt())).toList();List<Escalation> escalations=repository.escalations(row.id()).stream().map(e->new Escalation(e.id(),e.type(),e.destinationId(),pathFor(e.type(),e.destinationId()),e.admin(),e.reason(),e.createdAt())).toList();List<TimelineEntry> timeline=repository.events(row.id()).stream().map(e->new TimelineEntry(e.id(),e.at(),e.type(),e.actorType(),e.actorId(),e.actorName(),e.source(),e.previous(),e.next(),e.reasonCode(),e.reason(),e.correlation(),e.requestId(),e.metadata())).toList();boolean pii=access.has(AdminPermission.USER_PII_READ);String orderId=rawLinks.stream().filter(l->l.type()==TargetType.ORDER).map(SupportRepository.LinkRow::targetId).findFirst().orElse(null);return new AdminDetail(adminSummary(row,orderId),new RequesterSummary(row.requester(),row.requesterLabel(),row.requesterStatus(),pii,pii?row.email():null),row.description(),messages,notes,links,escalations,contexts,timeline,row.resolutionCode(),row.resolutionReason(),row.resolvedAt(),capabilities(row,admin));}
    private Capabilities capabilities(SupportRepository.TicketRow row,Admin admin){boolean mine=admin.user().getId().equals(row.assignedAdmin()),other=row.assignedAdmin()!=null&&!mine,finalState=row.status()==Status.RESOLVED;boolean canAssign=admin.access().has(AdminPermission.SUPPORT_ASSIGN),respond=admin.access().has(AdminPermission.SUPPORT_RESPOND)&&mine&&!finalState,resolve=admin.access().has(AdminPermission.SUPPORT_RESOLVE)&&mine&&!finalState,escalate=admin.access().has(AdminPermission.SUPPORT_ESCALATE)&&mine&&!finalState;boolean claim=canAssign&&row.assignedAdmin()==null&&row.status()==Status.OPEN,release=canAssign&&mine&&!finalState;String reason=finalState?"This ticket is resolved and read-only.":other?"This ticket is assigned to another administrator.":row.assignedAdmin()==null?"Claim this ticket before working on it.":!respond?"You have read-only support access.":null;return new Capabilities(true,claim,release,respond,respond,respond,respond,respond,respond,resolve,escalate,mine,other,row.status()==Status.WAITING_FOR_USER,finalState,!claim&&!release&&!respond&&!resolve&&!escalate,reason);}
    private AdminSummary adminSummary(SupportRepository.TicketRow row,String orderId){return new AdminSummary(row.id(),row.requester(),row.requesterLabel(),row.category(),row.subject(),row.priority(),row.status(),row.assignedAdmin(),orderId,row.createdAt(),row.updatedAt(),row.version());}
    private UserSummary userSummary(SupportRepository.TicketRow row){return new UserSummary(row.id(),row.category(),row.subject(),row.status(),row.createdAt(),row.updatedAt());}
    // Validates a reference through its owning service (or the local Auth schema) without importing domain state.
    private ValidatedLink validatedLink(TargetType type,String target,String requester){if(type==TargetType.USER||type==TargetType.BUSINESS||type==TargetType.TRUST_AND_SAFETY_REPORT||type==TargetType.INVESTIGATION_CASE)return localLink(type,target,requester);var value=external.find(type,target,requester).orElseThrow(SupportException::invalidLink);return new ValidatedLink(type,target,value.safeLabel(),value.adminPath(),value.details());}
    private ValidatedLink localLink(TargetType type,String target,String requester){if(type==TargetType.USER&&requester!=null&&!target.equals(requester))throw SupportException.invalidLink();if(type==TargetType.BUSINESS&&requester!=null&&!repository.activeBusinessMembership(requester,target))throw SupportException.invalidLink();var value=repository.local(type,target).orElseThrow(SupportException::invalidLink);return new ValidatedLink(type,target,value.label(),value.path(),value.details());}
    private void assigned(SupportRepository.TicketRow row,Admin admin){if(row.status()==Status.RESOLVED)throw SupportException.conflict("SUPPORT_TICKET_ALREADY_RESOLVED","Resolved support tickets are read-only.");if(row.assignedAdmin()==null||!row.assignedAdmin().equals(admin.user().getId()))throw SupportException.forbidden("SUPPORT_TICKET_NOT_ASSIGNED_TO_CURRENT_ADMIN","Claim the support ticket before changing it.");}
    private SupportRepository.TicketRow ticket(String raw){return repository.ticket(id(raw),false).orElseThrow(SupportException::notFound);}
    private Admin admin(AdminPermission permission){User user=auth.ensureUserEntity();authorization.requirePermission(user,permission);return new Admin(user,repository.adminName(user.getId()),authorization.accessFor(user));}
    private void eventMutation(SupportRepository.TicketRow before,Admin a,String type,String next,String code,String reason,String correlation,Instant now){repository.insertEvent(event(before.id(),type,now,"SUPPORT_ADMIN",a.user().getId(),a.name(),"HUMAN_ADMIN",before.status().name(),next,code,reason,correlation,ids.next(),Map.of()));}
    private SupportRepository.EventRow event(String ticket,String type,Instant at,String actorType,String actorId,String actorName,String source,String previous,String next,String code,String reason,String correlation,String requestId,Map<String,String> metadata){return new SupportRepository.EventRow(ids.next(),ticket,type,at,actorType,actorId,actorName,source,previous,next,code,reason,correlation,requestId,metadata);}
    private static TargetType targetFor(DestinationType type){return switch(type){case DISPUTE->TargetType.DISPUTE;case TRUST_AND_SAFETY->TargetType.TRUST_AND_SAFETY_REPORT;case FINANCE->TargetType.PAYMENT;case ORDER_OPERATIONS->TargetType.ORDER;};}
    private static String pathFor(DestinationType type,String id){return switch(type){case DISPUTE->"/admin/disputes/"+id;case TRUST_AND_SAFETY->"/admin/reports/"+id;case FINANCE->"/admin/payments/"+id;case ORDER_OPERATIONS->"/admin/orders/"+id;};}
    private static ValidatedCreate validateCreate(CreateTicketRequest r,String headerKey){if(r==null||r.category()==null)throw SupportException.invalid("SUPPORT_CATEGORY_REQUIRED","Choose a support category.");String subject=text(r.subject(),160);if(subject==null||subject.length()<5)throw SupportException.invalid("SUPPORT_SUBJECT_INVALID","Subject must be between 5 and 160 characters.");String description=text(r.description(),4000);if(description==null||description.length()<20)throw SupportException.invalid("SUPPORT_DESCRIPTION_INVALID","Description must be between 20 and 4000 characters.");return new ValidatedCreate(r.category(),subject,description,optionalId(r.linkedOrderId()),optionalId(r.linkedBusinessId()),optionalId(r.linkedListingId()),key(headerKey));}
    private static String body(String value){String result=text(value,4000);if(result==null||result.length()<2)throw SupportException.invalid("SUPPORT_MESSAGE_INVALID","Message must be between 2 and 4000 characters.");return result;}
    private static String reason(String value){String result=text(value,1000);if(result==null||result.length()<3)throw SupportException.invalid("SUPPORT_REASON_REQUIRED","A reason of at least 3 characters is required.");return result;}
    private static String text(String value,int max){if(value==null)return null;String result=value.trim().replaceAll("\\s+"," ");if(result.isEmpty())return null;if(result.length()>max)throw SupportException.invalid("SUPPORT_TEXT_TOO_LONG","Support text exceeds the allowed length.");return result;}
    private static String id(String value){String result=value==null?null:value.trim();if(result==null||!ULID.matcher(result).matches())throw SupportException.invalid("SUPPORT_ID_INVALID","Marketplace reference is invalid.");return result;}
    private static String optionalId(String value){return value==null||value.isBlank()?null:id(value);}
    private static String key(String value){String result=value==null?null:value.trim();if(result==null||!KEY.matcher(result).matches())throw SupportException.invalid("SUPPORT_IDEMPOTENCY_KEY_REQUIRED","A valid Idempotency-Key is required.");return result;}
    private static long version(Long value){if(value==null||value<0)throw SupportException.invalid("SUPPORT_VERSION_REQUIRED","A current support ticket version is required.");return value;}
    private static String sort(String value){String result=value==null||value.isBlank()?"updatedAt,desc":value.trim();if(!SORTS.contains(result))throw SupportException.invalid("SUPPORT_SORT_INVALID","Support ticket sort is invalid.");return result;}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void same(String expected,String actual){if(!expected.equals(actual))throw SupportException.conflict("SUPPORT_IDEMPOTENCY_CONFLICT","The idempotency key was reused with changed content.");}
    private static void mutationConflict(){throw SupportException.conflict("SUPPORT_TICKET_VERSION_CONFLICT","The support ticket changed. Reload it before trying again.");}
    private static int pages(long total,int size){return total==0?0:(int)((total+size-1)/size);}
    private static String safeName(User user){return user.getDisplayName()==null||user.getDisplayName().isBlank()?"Marketplace user":user.getDisplayName();}
    private static String userEventLabel(String type){return switch(type){case"SUPPORT_TICKET_CREATED"->"Ticket submitted";case"SUPPORT_MESSAGE_SENT"->"Support replied";case"USER_MESSAGE_RECEIVED"->"You replied";case"INFORMATION_REQUESTED"->"More information requested";case"SUPPORT_TICKET_RESOLVED"->"Issue resolved";default->"Ticket updated";};}
    private record Admin(User user,String name,AdminAuthorizationService.AdminAccessSnapshot access) { }
    private record ValidatedCreate(Category category,String subject,String description,String order,String business,String listing,String key){String normalized(){return category+"|"+subject+"|"+description+"|"+order+"|"+business+"|"+listing;}}
    private record ValidatedLink(TargetType type,String id,String label,String path,Map<String,Object> details) { }
}
