package com.msb.ecom.chat_service.controller;

import com.msb.ecom.chat_service.dto.ConversationResponse;
import com.msb.ecom.chat_service.dto.ConversationCompletionResponse;
import com.msb.ecom.chat_service.dto.ConversationPageResponse;
import com.msb.ecom.chat_service.dto.MarkConversationDoneRequest;
import com.msb.ecom.chat_service.dto.MarkConversationReadResponse;
import com.msb.ecom.chat_service.dto.MessagePageResponse;
import com.msb.ecom.chat_service.dto.MessageResponse;
import com.msb.ecom.chat_service.dto.SendMessageRequest;
import com.msb.ecom.chat_service.dto.StartConversationResult;
import com.msb.ecom.chat_service.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping("/listings/{listingId}/conversations")
    public ResponseEntity<ConversationResponse> startListingConversation(@PathVariable String listingId) {
        StartConversationResult result = conversationService.startListingConversation(listingId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping("/conversations")
    public ConversationPageResponse listConversations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return conversationService.listConversations(cursor, limit);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationResponse getConversation(@PathVariable String conversationId) {
        return conversationService.getConversation(conversationId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePageResponse getMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return conversationService.getMessages(conversationId, cursor, limit);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessageResponse> sendMessage(
            @PathVariable String conversationId,
            @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.sendMessage(conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public MarkConversationReadResponse markRead(@PathVariable String conversationId) {
        return conversationService.markRead(conversationId);
    }

    @GetMapping("/conversations/{conversationId}/completion")
    public ConversationCompletionResponse getCompletion(@PathVariable String conversationId) {
        return conversationService.getCompletion(conversationId);
    }

    @PostMapping("/conversations/{conversationId}/completion/mark-done")
    public ConversationCompletionResponse markDone(
            @PathVariable String conversationId,
            @RequestBody(required = false) MarkConversationDoneRequest request) {
        return conversationService.markDone(conversationId, request);
    }

    @PostMapping("/conversations/{conversationId}/completion/confirm")
    public ConversationCompletionResponse confirmCompletion(@PathVariable String conversationId) {
        return conversationService.confirmCompletion(conversationId);
    }
}
