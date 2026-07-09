import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
  ChatMessage,
  ChatParticipantSummary,
  ConversationCompletion,
  ChatListingSummary,
  ChatMessagePage,
  ConversationListItem,
  ConversationPage,
  ConversationSummary,
  MarkConversationReadResponse,
} from '../models/chat.model';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly baseUrl = `${environment.apiGatewayUrl}/api/v1`;

  constructor(private http: HttpClient) {}

  startListingConversation(listingId: string): Observable<ConversationSummary> {
    return this.http.post<ConversationSummary>(`${this.baseUrl}/listings/${listingId}/conversations`, null, {
      withCredentials: true,
    }).pipe(map(conversation => this.normalizeConversation(conversation)));
  }

  getConversation(conversationId: string): Observable<ConversationSummary> {
    return this.http.get<ConversationSummary>(`${this.baseUrl}/conversations/${conversationId}`, {
      withCredentials: true,
    }).pipe(map(conversation => this.normalizeConversation(conversation)));
  }

  getConversations(cursor?: string | null, limit = 20): Observable<ConversationPage> {
    const params: Record<string, string> = { limit: String(limit) };
    if (cursor) {
      params['cursor'] = cursor;
    }
    return this.http.get<ConversationPage>(`${this.baseUrl}/conversations`, {
      params,
      withCredentials: true,
    }).pipe(map(page => ({
      ...page,
      items: page.items.map(item => this.normalizeConversationListItem(item)),
    })));
  }

  getMessages(conversationId: string, cursor?: string | null, limit = 50): Observable<ChatMessagePage> {
    const params: Record<string, string> = { limit: String(limit) };
    if (cursor) {
      params['cursor'] = cursor;
    }
    return this.http.get<ChatMessagePage>(`${this.baseUrl}/conversations/${conversationId}/messages`, {
      params,
      withCredentials: true,
    });
  }

  sendMessage(conversationId: string, body: string): Observable<ChatMessage> {
    return this.http.post<ChatMessage>(
      `${this.baseUrl}/conversations/${conversationId}/messages`,
      { messageType: 'TEXT', body },
      { withCredentials: true },
    );
  }

  markRead(conversationId: string): Observable<MarkConversationReadResponse> {
    return this.http.post<MarkConversationReadResponse>(
      `${this.baseUrl}/conversations/${conversationId}/read`,
      null,
      { withCredentials: true },
    );
  }

  markDone(conversationId: string, quantitySold = 1): Observable<ConversationCompletion> {
    return this.http.post<ConversationCompletion>(
      `${this.baseUrl}/conversations/${conversationId}/completion/mark-done`,
      { quantitySold },
      { withCredentials: true },
    );
  }

  confirmCompletion(conversationId: string): Observable<ConversationCompletion> {
    return this.http.post<ConversationCompletion>(
      `${this.baseUrl}/conversations/${conversationId}/completion/confirm`,
      null,
      { withCredentials: true },
    );
  }

  private normalizeConversation(conversation: ConversationSummary): ConversationSummary {
    return {
      ...conversation,
      listing: this.normalizeListing(conversation.listing),
      participants: conversation.participants.map(participant => this.normalizeParticipant(participant)),
    };
  }

  private normalizeConversationListItem(item: ConversationListItem): ConversationListItem {
    return {
      ...item,
      listing: this.normalizeListing(item.listing),
      otherParticipant: this.normalizeParticipant(item.otherParticipant),
    };
  }

  private normalizeListing(listing: ChatListingSummary): ChatListingSummary {
    return {
      ...listing,
      thumbnailUrl: this.gatewayUrl(listing.thumbnailUrl),
    };
  }

  private normalizeParticipant(participant: ChatParticipantSummary): ChatParticipantSummary {
    return {
      ...participant,
      avatarUrl: this.gatewayUrl(participant.avatarUrl),
    };
  }

  private gatewayUrl(url: string | null | undefined): string | null {
    if (!url) {
      return null;
    }
    if (url.startsWith('/api/')) {
      return `${environment.apiGatewayUrl}${url}`;
    }
    return url;
  }
}
