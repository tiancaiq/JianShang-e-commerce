package com.msb.ecom.auth_service.support;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import static com.msb.ecom.auth_service.support.SupportContracts.*;

@RestController
@RequestMapping("/api/v1/admin/support/tickets")
@RequiredArgsConstructor
public class AdminSupportController {
    private final SupportService service;

    @GetMapping ApiDataResponse<AdminPage> search(@RequestParam(required=false)String q,
            @RequestParam(required=false)String requesterUserId,@RequestParam(required=false)Category category,
            @RequestParam(required=false)Status status,@RequestParam(required=false)Priority priority,
            @RequestParam(required=false)Assignment assignment,@RequestParam(required=false)String linkedOrderId,
            @RequestParam(required=false)String linkedBusinessId,@RequestParam(required=false)Instant createdFrom,
            @RequestParam(required=false)Instant createdTo,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="25")int size,@RequestParam(defaultValue="updatedAt,desc")String sort){return new ApiDataResponse<>(service.search(q,requesterUserId,category,status,priority,assignment,linkedOrderId,linkedBusinessId,createdFrom,createdTo,page,size,sort));}
    @GetMapping("/{ticketId}") ApiDataResponse<AdminDetail> detail(@PathVariable String ticketId){return new ApiDataResponse<>(service.adminDetail(ticketId));}
    @PostMapping("/{ticketId}/claim") ApiDataResponse<AdminDetail> claim(@PathVariable String ticketId,@RequestBody(required=false)VersionRequest request,HttpServletRequest http){return data(service.claim(ticketId,request,correlation(http)));}
    @PostMapping("/{ticketId}/release") ApiDataResponse<AdminDetail> release(@PathVariable String ticketId,@RequestBody(required=false)VersionRequest request,HttpServletRequest http){return data(service.release(ticketId,request,correlation(http)));}
    @PostMapping("/{ticketId}/messages") ApiDataResponse<AdminDetail> message(@PathVariable String ticketId,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)MessageRequest request,HttpServletRequest http){return data(service.respond(ticketId,request,key,correlation(http)));}
    @PostMapping("/{ticketId}/request-information") ApiDataResponse<AdminDetail> information(@PathVariable String ticketId,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)MessageRequest request,HttpServletRequest http){return data(service.requestInformation(ticketId,request,key,correlation(http)));}
    @PostMapping("/{ticketId}/notes") ApiDataResponse<AdminDetail> note(@PathVariable String ticketId,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)NoteRequest request,HttpServletRequest http){return data(service.note(ticketId,request,key,correlation(http)));}
    @PatchMapping("/{ticketId}/priority") ApiDataResponse<AdminDetail> priority(@PathVariable String ticketId,@RequestBody(required=false)PriorityRequest request,HttpServletRequest http){return data(service.priority(ticketId,request,correlation(http)));}
    @PostMapping("/{ticketId}/links") ApiDataResponse<AdminDetail> link(@PathVariable String ticketId,@RequestBody(required=false)LinkRequest request,HttpServletRequest http){return data(service.link(ticketId,request,correlation(http)));}
    @PostMapping("/{ticketId}/links/{targetType}/{targetId}/unlink") ApiDataResponse<AdminDetail> unlink(@PathVariable String ticketId,@PathVariable TargetType targetType,@PathVariable String targetId,@RequestBody(required=false)UnlinkRequest request,HttpServletRequest http){return data(service.unlink(ticketId,targetType,targetId,request,correlation(http)));}
    @PostMapping("/{ticketId}/escalations") ApiDataResponse<AdminDetail> escalate(@PathVariable String ticketId,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)EscalationRequest request,HttpServletRequest http){return data(service.escalate(ticketId,request,key,correlation(http)));}
    @PostMapping("/{ticketId}/resolve") ApiDataResponse<AdminDetail> resolve(@PathVariable String ticketId,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)ResolutionRequest request,HttpServletRequest http){return data(service.resolve(ticketId,request,key,correlation(http)));}
    private static ApiDataResponse<AdminDetail> data(AdminDetail value){return new ApiDataResponse<>(value);}
    private static String correlation(HttpServletRequest request){return CorrelationIdFilter.current(request);}
}
