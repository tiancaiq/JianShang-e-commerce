import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ChatListingSummary, ChatMessage, ConversationListItem, ConversationSummary } from '../../core/models/chat.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';

@Component({
  selector: 'app-floating-chat',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    @if (authService.isAuthenticated()) {
      <div class="floating-chat" [class.open]="open()">
        @if (open()) {
          <section class="chat-panel" aria-label="Marketplace chat">
            <header class="chat-panel-header">
              <div>
                <h2>Messages</h2>
                <p>{{ panelSubtitle() }}</p>
              </div>
              <button type="button" aria-label="Close chat" (click)="close()">Close</button>
            </header>

            <div class="chat-split">
              <aside class="conversation-list" aria-label="People in recent conversations">
                <label class="search-box">
                  <span>Search conversations</span>
                  <input
                    type="search"
                    name="floatingConversationSearch"
                    placeholder="Search conversations"
                    [ngModel]="conversationQuery()"
                    (ngModelChange)="conversationQuery.set($event)">
                </label>

                <button
                  type="button"
                  class="conversation-row agent-row"
                  [class.active]="agentSelected()"
                  (click)="showAgentPlaceholder()">
                  <span class="avatar-initials agent-initials">AI</span>
                  <span class="conversation-copy">
                    <span class="row-title">
                      <strong>Marketplace agent</strong>
                      <small>Coming soon</small>
                    </span>
                    <span class="row-preview">Chat with an agent</span>
                  </span>
                </button>

                @if (loadingConversations()) {
                  <div class="chat-empty list-empty">Loading conversations...</div>
                } @else if (conversationError()) {
                  <div class="chat-list-state error-state">
                    <strong>Could not load chats.</strong>
                    <button type="button" (click)="refreshConversations()">Retry</button>
                  </div>
                } @else if (!conversations().length) {
                  <div class="chat-list-state">
                    <strong>No buyer/seller chats yet.</strong>
                    <span>Start from an approved listing when you are ready.</span>
                  </div>
                } @else if (!filteredConversations().length) {
                  <div class="chat-list-state">
                    <strong>No conversations found.</strong>
                    <span>Try another name or listing title.</span>
                  </div>
                } @else {
                  @for (conversation of filteredConversations(); track conversation.id) {
                    <button
                      type="button"
                      class="conversation-row"
                      [class.unread]="conversation.unread"
                      [class.active]="activeConversation()?.id === conversation.id"
                      (click)="openConversation(conversation.id)">
                      @if (conversation.otherParticipant.avatarUrl) {
                        <img
                          [src]="conversation.otherParticipant.avatarUrl"
                          [alt]="conversation.otherParticipant.displayName">
                      } @else {
                        <span class="avatar-initials">{{ conversation.otherParticipant.initials }}</span>
                      }
                      <span class="conversation-copy">
                        <span class="row-title">
                          <strong>{{ conversation.otherParticipant.displayName }}</strong>
                          <time>{{ conversationTime(conversation) }}</time>
                        </span>
                        <small>{{ listingTitle(conversation.listing) }}</small>
                        <span class="row-preview">{{ preview(conversation) }}</span>
                      </span>
                      @if (conversation.unread) {
                        <span class="unread-dot" aria-label="Unread conversation"></span>
                      }
                    </button>
                  }
                }
              </aside>

              <section class="thread-pane" aria-label="Selected conversation">
                @if (activeConversation()) {
                  <header class="thread-listing">
                    <div class="listing-identity">
                      @if (activeConversation()?.listing?.thumbnailUrl) {
                        <img [src]="activeConversation()?.listing?.thumbnailUrl || ''" [alt]="activeConversation()?.listing?.title || 'Listing image'">
                      } @else {
                        <span class="listing-fallback">{{ listingInitial(activeConversation()?.listing) }}</span>
                      }
                      <div>
                        <strong>{{ listingTitle(activeConversation()?.listing) }}</strong>
                        @if (listingMeta(activeConversation()?.listing)) {
                          <span>{{ listingMeta(activeConversation()?.listing) }}</span>
                        }
                      </div>
                    </div>
                    @if (listingIsPublic(activeConversation()?.listing)) {
                      <a
                        [routerLink]="['/listings', activeConversation()?.listing?.id]"
                        (click)="close()">
                        View listing
                      </a>
                    }
                  </header>

                  <section class="safety-note">
                    Safety note: Payment and delivery are arranged directly between users. Meet in public.
                  </section>

                  @if (activeConversation()?.completion?.currentUserCanMarkDone) {
                    <section class="trade-card">
                      <div>
                        <strong>Trade completion</strong>
                        <span>Enter how many units were sold.</span>
                      </div>
                      <label class="quantity-field">
                        <span>Qty sold</span>
                        <input
                          type="number"
                          min="1"
                          name="floatingCompletionQuantity"
                          [ngModel]="completionQuantity()"
                          (ngModelChange)="completionQuantity.set(normalizeQuantityInput($event))">
                      </label>
                      <button type="button" [disabled]="completionActionLoading()" (click)="markDone()">
                        {{ completionActionLoading() ? 'Marking...' : 'Mark as done' }}
                      </button>
                    </section>
                  }

                  @if (activeConversation()?.completion?.currentUserCanConfirm || completionStatusText()) {
                    <section class="completion-status" [class.done]="activeConversation()?.completion?.status === 'BUYER_CONFIRMED'">
                      <div>
                        <strong>{{ completionStatusTitle() }}</strong>
                        <span>{{ completionStatusText() }}</span>
                      </div>
                      @if (activeConversation()?.completion?.currentUserCanConfirm) {
                        <button type="button" [disabled]="completionActionLoading()" (click)="confirmCompleted()">
                          {{ completionActionLoading() ? 'Confirming...' : 'Confirm completed' }}
                        </button>
                      }
                    </section>
                  }

                  <section class="message-list" aria-live="polite">
                    @if (loadingThread()) {
                      <div class="chat-empty">Loading messages...</div>
                    } @else if (!messages().length) {
                      <div class="chat-empty">No messages yet.</div>
                    } @else {
                      @for (message of messages(); track message.id; let first = $first) {
                        @if (first) {
                          <div class="day-divider">{{ dayLabel(message.createdAt) }}</div>
                        }
                        <article class="message-bubble" [class.mine]="message.currentUser">
                          <span>{{ senderLabel(message) }} · {{ message.createdAt | date: 'shortTime' }}</span>
                          <p>{{ message.body }}</p>
                        </article>
                      }
                    }
                  </section>

                  <form class="composer" (ngSubmit)="sendMessage()">
                    <textarea
                      name="floatingMessageBody"
                      rows="2"
                      maxlength="2000"
                      [ngModel]="draft()"
                      (ngModelChange)="draft.set($event)"
                      placeholder="Write a message..."></textarea>
                    <div class="composer-actions">
                      <span>{{ draft().length }}/2000</span>
                      <button type="submit" [disabled]="sending() || !draft().trim()">
                        {{ sending() ? 'Sending...' : 'Send' }}
                      </button>
                    </div>
                  </form>
                } @else if (agentSelected()) {
                  <div class="thread-placeholder agent-placeholder">
                    <span class="agent-large-icon">AI</span>
                    <strong>Marketplace agent is coming soon</strong>
                    <span>Buyer and seller messages are ready now. Agent chat will be added in a later slice.</span>
                  </div>
                } @else {
                  <div class="thread-placeholder">
                    <strong>Select a conversation</strong>
                    <span>Choose a buyer or seller on the left to continue chatting.</span>
                  </div>
                }
              </section>
            </div>
          </section>
        }

        <button type="button" class="chat-launcher" aria-label="Open marketplace messages" (click)="toggle()">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 5.75A3.75 3.75 0 0 1 8.75 2h6.5A3.75 3.75 0 0 1 19 5.75v5.5A3.75 3.75 0 0 1 15.25 15H11.2l-4.38 3.28A.75.75 0 0 1 5.6 17.7V15.02A3.75 3.75 0 0 1 2 11.25v-5.5Zm3.75-2.25A2.25 2.25 0 0 0 6.5 5.75v6.02a.75.75 0 0 1-.75.75A2.25 2.25 0 0 0 8 14.77v1.43l2.52-1.89a.75.75 0 0 1 .45-.15h4.28a2.25 2.25 0 0 0 2.25-2.25V5.75a2.25 2.25 0 0 0-2.25-2.25h-6.5Z" />
            <path d="M8 7.25a.75.75 0 0 1 .75-.75h6.5a.75.75 0 0 1 0 1.5h-6.5A.75.75 0 0 1 8 7.25Zm0 3a.75.75 0 0 1 .75-.75h4.5a.75.75 0 0 1 0 1.5h-4.5A.75.75 0 0 1 8 10.25Z" />
          </svg>
          @if (unreadCount() > 0) {
            <span class="launcher-badge">{{ unreadCount() > 9 ? '9+' : unreadCount() }}</span>
          }
        </button>
      </div>
    }
  `,
  styles: [`
    .floating-chat {
      position: fixed;
      right: 1.25rem;
      bottom: 1.25rem;
      z-index: 80;
      color: var(--market-ink);
    }

    .chat-launcher {
      position: relative;
      width: 58px;
      height: 58px;
      display: grid;
      place-items: center;
      border: 0;
      border-radius: 999px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      box-shadow: 0 18px 38px rgba(97, 48, 91, 0.28);
    }

    .chat-launcher svg {
      width: 30px;
      height: 30px;
      fill: currentColor;
    }

    .launcher-badge {
      position: absolute;
      top: -3px;
      right: -3px;
      min-width: 22px;
      height: 22px;
      display: inline-grid;
      place-items: center;
      border: 2px solid #fff;
      border-radius: 999px;
      background: #f7b84b;
      color: #382648;
      font-size: 0.72rem;
      font-weight: 950;
      padding: 0 0.25rem;
    }

    .chat-panel {
      position: absolute;
      right: 0;
      bottom: 72px;
      width: min(760px, calc(100vw - 2rem));
      height: min(640px, calc(100vh - 8rem));
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid rgba(198, 168, 214, 0.92);
      border-radius: 8px;
      background: #fff;
      box-shadow: 0 24px 70px rgba(73, 42, 84, 0.28);
    }

    .chat-panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.85rem 0.95rem;
      border-bottom: 1px solid var(--market-line);
      background: #fff8fc;
    }

    h2,
    p {
      margin: 0;
    }

    h2 {
      font-size: 1rem;
      font-weight: 950;
    }

    .chat-panel-header p {
      margin-top: 0.18rem;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 800;
    }

    .chat-panel-header button {
      min-height: 34px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-size: 0.8rem;
      font-weight: 900;
      padding: 0 0.65rem;
    }

    .chat-split {
      display: grid;
      grid-template-columns: minmax(232px, 270px) minmax(0, 1fr);
      flex: 1 1 auto;
      min-height: 0;
      overflow: hidden;
    }

    .conversation-list {
      flex: 1 1 auto;
      overflow-y: auto;
      min-height: 0;
      border-right: 1px solid var(--market-line);
      background: #fffafd;
      scrollbar-color: rgba(190, 47, 118, 0.28) transparent;
      scrollbar-width: thin;
    }

    .conversation-list::-webkit-scrollbar,
    .message-list::-webkit-scrollbar {
      width: 8px;
    }

    .conversation-list::-webkit-scrollbar-track,
    .message-list::-webkit-scrollbar-track {
      background: transparent;
    }

    .conversation-list::-webkit-scrollbar-thumb,
    .message-list::-webkit-scrollbar-thumb {
      border: 2px solid #fffafd;
      border-radius: 999px;
      background: rgba(190, 47, 118, 0.26);
    }

    .search-box {
      display: grid;
      gap: 0.3rem;
      padding: 0.72rem;
      border-bottom: 1px solid var(--market-line);
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .search-box input {
      min-height: 36px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      font-size: 0.8rem;
      font-weight: 800;
      padding: 0 0.65rem;
    }

    .search-box input:focus,
    .quantity-field input:focus,
    .composer textarea:focus {
      outline: 2px solid rgba(190, 47, 118, 0.18);
      border-color: rgba(190, 47, 118, 0.42);
      box-shadow: 0 0 0 3px rgba(236, 79, 163, 0.08);
    }

    .conversation-row {
      width: 100%;
      display: grid;
      grid-template-columns: 46px minmax(0, 1fr) 9px;
      gap: 0.62rem;
      align-items: center;
      min-height: 82px;
      border: 0;
      border-bottom: 1px solid var(--market-line);
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      padding: 0.68rem 0.72rem;
      text-align: left;
    }

    .conversation-row:hover,
    .conversation-row.active {
      background: #fff0f8;
    }

    .conversation-row.active {
      box-shadow: inset 4px 0 0 var(--market-accent-dark);
    }

    .conversation-row.unread strong {
      color: var(--market-accent-dark);
    }

    .agent-row {
      grid-template-columns: 46px minmax(0, 1fr);
      min-height: 74px;
      background: #fff7fc;
      opacity: 0.86;
    }

    .agent-row.active {
      opacity: 1;
    }

    .conversation-row img,
    .avatar-initials,
    .thread-listing img,
    .listing-fallback {
      width: 46px;
      height: 46px;
      border-radius: 8px;
      object-fit: cover;
      background: #f8edf7;
    }

    .avatar-initials,
    .listing-fallback,
    .agent-large-icon {
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, #ffd0e4, #d9d0ff);
      color: var(--market-accent-dark);
      font-size: 0.84rem;
      font-weight: 950;
    }

    .agent-initials,
    .agent-large-icon {
      background: linear-gradient(135deg, #f7b84b, #ffd0e4);
    }

    .conversation-copy {
      display: grid;
      min-width: 0;
      gap: 0.18rem;
    }

    .row-title {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      gap: 0.45rem;
      align-items: baseline;
      min-width: 0;
    }

    .conversation-copy strong,
    .conversation-copy small,
    .conversation-copy span,
    .conversation-copy time {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .conversation-copy strong {
      font-size: 0.84rem;
      font-weight: 950;
    }

    .conversation-copy small,
    .row-preview,
    .conversation-copy time {
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 800;
    }

    .agent-row small {
      border: 1px solid rgba(190, 47, 118, 0.18);
      border-radius: 999px;
      color: var(--market-accent-dark);
      padding: 0.08rem 0.36rem;
    }

    .unread-dot {
      width: 9px;
      height: 9px;
      border-radius: 999px;
      background: var(--market-accent-dark);
    }

    .thread-pane {
      display: flex;
      flex-direction: column;
      min-width: 0;
      min-height: 0;
      height: 100%;
      overflow: hidden;
      background: #fff;
    }

    .thread-listing {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.85rem;
      flex-shrink: 0;
      padding: 0.72rem 0.85rem;
      border-bottom: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .listing-identity {
      display: flex;
      align-items: center;
      gap: 0.62rem;
      min-width: 0;
    }

    .thread-listing strong,
    .thread-listing span {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .thread-listing strong {
      font-size: 0.9rem;
      font-weight: 950;
      white-space: nowrap;
    }

    .thread-listing span {
      margin-top: 0.18rem;
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 850;
    }

    .thread-listing a {
      min-height: 32px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 auto;
      border: 1px solid rgba(190, 47, 118, 0.2);
      border-radius: 999px;
      background: #fff;
      color: var(--market-accent-dark);
      font-size: 0.74rem;
      font-weight: 950;
      padding: 0 0.68rem;
      text-decoration: none;
      white-space: nowrap;
    }

    .safety-note {
      flex-shrink: 0;
      margin: 0.65rem 0.85rem 0;
      border: 1px solid rgba(247, 184, 75, 0.34);
      border-radius: 8px;
      background: #fffaf0;
      color: #754f19;
      font-size: 0.76rem;
      font-weight: 800;
      line-height: 1.42;
      padding: 0.56rem 0.65rem;
    }

    .trade-card,
    .completion-status {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 86px max-content;
      gap: 0.62rem;
      align-items: end;
      flex-shrink: 0;
      margin: 0.65rem 0.85rem 0;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: #fffafd;
      padding: 0.65rem;
    }

    .completion-status {
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: center;
    }

    .completion-status.done {
      border-color: rgba(42, 168, 135, 0.22);
      background: #f7fff7;
    }

    .trade-card strong,
    .completion-status strong {
      display: block;
      color: var(--market-ink);
      font-size: 0.82rem;
      font-weight: 950;
    }

    .trade-card span,
    .completion-status span {
      display: block;
      margin-top: 0.18rem;
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 800;
      line-height: 1.32;
    }

    .quantity-field {
      display: grid;
      gap: 0.14rem;
      color: var(--market-muted);
      font-size: 0.64rem;
      font-weight: 900;
    }

    .quantity-field input {
      width: 100%;
      min-height: 34px;
      box-sizing: border-box;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      color: var(--market-ink);
      font: inherit;
      font-weight: 900;
      padding: 0 0.42rem;
    }

    .trade-card button,
    .completion-status button,
    .composer-actions button {
      min-height: 34px;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-size: 0.76rem;
      font-weight: 900;
      padding: 0 0.72rem;
      white-space: nowrap;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.62;
    }

    .message-list {
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      gap: 0.6rem;
      overflow-y: auto;
      min-height: 0;
      padding: 0.85rem;
      scrollbar-color: rgba(190, 47, 118, 0.28) transparent;
      scrollbar-width: thin;
    }

    .day-divider {
      align-self: center;
      border: 1px solid var(--market-line);
      border-radius: 999px;
      background: #fffafd;
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 900;
      padding: 0.15rem 0.56rem;
    }

    .message-bubble {
      max-width: 84%;
      align-self: flex-start;
    }

    .message-bubble.mine {
      align-self: flex-end;
    }

    .message-bubble span {
      display: block;
      margin-bottom: 0.22rem;
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 800;
    }

    .message-bubble.mine span {
      text-align: right;
    }

    .message-bubble p {
      margin: 0;
      border: 1px solid var(--market-line);
      border-radius: 16px 16px 16px 6px;
      background: #fff8fc;
      color: var(--market-ink);
      overflow-wrap: anywhere;
      padding: 0.62rem 0.72rem;
      white-space: pre-wrap;
      line-height: 1.42;
      font-weight: 750;
    }

    .message-bubble.mine p {
      border-color: rgba(190, 47, 118, 0.28);
      border-radius: 16px 16px 6px 16px;
      background: var(--market-accent-dark);
      color: #fff;
    }

    .composer {
      display: grid;
      gap: 0.34rem;
      align-content: start;
      grid-auto-rows: max-content;
      flex-shrink: 0;
      padding: 0.58rem 0.75rem;
      border-top: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .composer textarea {
      width: 100%;
      height: 42px;
      min-height: 42px;
      max-height: 92px;
      resize: none;
      box-sizing: border-box;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      color: var(--market-ink);
      font: inherit;
      line-height: 1.4;
      padding: 0.5rem 0.62rem;
    }

    .composer-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.7rem;
    }

    .composer-actions span {
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 800;
    }

    .chat-empty,
    .thread-placeholder {
      margin: auto;
      color: var(--market-muted);
      font-weight: 850;
      padding: 1.2rem;
      text-align: center;
    }

    .list-empty {
      margin: 0;
      border-bottom: 1px solid var(--market-line);
    }

    .chat-list-state {
      display: grid;
      gap: 0.35rem;
      padding: 0.9rem 0.75rem;
      border-bottom: 1px solid var(--market-line);
      color: var(--market-muted);
    }

    .chat-list-state strong {
      color: var(--market-ink);
      font-size: 0.82rem;
      font-weight: 950;
    }

    .chat-list-state span {
      font-size: 0.74rem;
      font-weight: 800;
      line-height: 1.4;
    }

    .chat-list-state button {
      justify-self: start;
      min-height: 30px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-size: 0.76rem;
      font-weight: 900;
      padding: 0 0.62rem;
    }

    .thread-placeholder {
      display: grid;
      place-items: center;
      align-content: center;
      gap: 0.38rem;
      min-height: 100%;
    }

    .thread-placeholder strong {
      color: var(--market-ink);
      font-size: 0.95rem;
      font-weight: 950;
    }

    .thread-placeholder span {
      max-width: 260px;
      font-size: 0.82rem;
      font-weight: 800;
      line-height: 1.45;
    }

    .agent-large-icon {
      width: 54px;
      height: 54px;
      border-radius: 8px;
      font-size: 1rem;
    }

    @media (max-width: 640px) {
      .floating-chat {
        right: 0.75rem;
        bottom: 0.75rem;
      }

      .chat-panel {
        right: -0.75rem;
        bottom: 70px;
        width: 100vw;
        height: min(700px, 84vh);
        border-radius: 8px 8px 0 0;
      }

      .chat-split {
        grid-template-columns: 1fr;
        overflow: hidden;
      }

      .conversation-list {
        max-height: 330px;
        border-right: 0;
        border-bottom: 1px solid var(--market-line);
      }

      .thread-pane {
        min-height: 430px;
        height: min(430px, 54vh);
      }

      .thread-listing,
      .trade-card,
      .completion-status {
        align-items: stretch;
        grid-template-columns: 1fr;
      }

      .thread-listing {
        flex-direction: column;
      }

      .thread-listing a {
        width: max-content;
      }

      .message-bubble {
        max-width: 88%;
      }
    }
  `],
})
export class FloatingChatComponent {
  readonly defaultNotice = 'Payment and delivery are arranged directly by participants.';

  readonly authService = inject(AuthService);
  private readonly chatService = inject(ChatService);

  open = signal(false);
  conversations = signal<ConversationListItem[]>([]);
  activeConversation = signal<ConversationSummary | null>(null);
  agentSelected = signal(false);
  messages = signal<ChatMessage[]>([]);
  draft = signal('');
  conversationQuery = signal('');
  loadingConversations = signal(false);
  conversationError = signal(false);
  loadingThread = signal(false);
  sending = signal(false);
  completionActionLoading = signal(false);
  completionQuantity = signal(1);

  unreadCount = computed(() => this.conversations().filter(conversation => conversation.unread).length);

  filteredConversations = computed(() => {
    const query = this.conversationQuery().trim().toLowerCase();
    if (!query) {
      return this.conversations();
    }
    return this.conversations().filter(conversation => {
      const values = [
        conversation.otherParticipant.displayName,
        conversation.listing.title,
        conversation.lastMessage?.body || '',
      ].join(' ').toLowerCase();
      return values.includes(query);
    });
  });

  constructor() {
    effect(() => {
      if (this.authService.isAuthenticated()) {
        this.refreshConversations();
      } else {
        this.open.set(false);
        this.conversations.set([]);
        this.activeConversation.set(null);
        this.agentSelected.set(false);
        this.messages.set([]);
      }
    });
  }

  toggle(): void {
    this.open.update(value => !value);
    if (this.open()) {
      this.showList();
      this.refreshConversations();
    }
  }

  close(): void {
    this.open.set(false);
  }

  showList(): void {
    this.activeConversation.set(null);
    this.agentSelected.set(false);
    this.messages.set([]);
    this.draft.set('');
    this.completionQuantity.set(1);
  }

  showAgentPlaceholder(): void {
    this.activeConversation.set(null);
    this.agentSelected.set(true);
    this.messages.set([]);
    this.draft.set('');
    this.completionQuantity.set(1);
  }

  openConversation(conversationId: string): void {
    this.agentSelected.set(false);
    this.loadingThread.set(true);
    this.chatService.getConversation(conversationId).subscribe({
      next: conversation => {
        this.activeConversation.set(conversation);
        this.completionQuantity.set(conversation.completion?.quantitySold || 1);
        this.loadMessages(conversation.id);
      },
      error: () => {
        this.loadingThread.set(false);
        this.showList();
      },
    });
  }

  sendMessage(): void {
    const conversation = this.activeConversation();
    const body = this.draft().trim();
    if (!conversation || !body || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.chatService.sendMessage(conversation.id, body)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: message => {
          this.messages.update(messages => [...messages, message]);
          this.draft.set('');
          this.refreshConversations();
        },
      });
  }

  markDone(): void {
    const conversation = this.activeConversation();
    if (!conversation || !conversation.completion?.currentUserCanMarkDone || this.completionActionLoading()) {
      return;
    }
    this.completionActionLoading.set(true);
    this.chatService.markDone(conversation.id, this.completionQuantity())
      .pipe(finalize(() => this.completionActionLoading.set(false)))
      .subscribe({
        next: completion => this.applyCompletion(completion),
      });
  }

  confirmCompleted(): void {
    const conversation = this.activeConversation();
    if (!conversation || !conversation.completion?.currentUserCanConfirm || this.completionActionLoading()) {
      return;
    }
    this.completionActionLoading.set(true);
    this.chatService.confirmCompletion(conversation.id)
      .pipe(finalize(() => this.completionActionLoading.set(false)))
      .subscribe({
        next: completion => this.applyCompletion(completion),
      });
  }

  preview(conversation: ConversationListItem): string {
    return conversation.lastMessage?.body || 'No messages yet.';
  }

  conversationTime(conversation: ConversationListItem): string {
    const value = conversation.lastMessageAt || conversation.updatedAt || conversation.createdAt;
    if (!value) {
      return '';
    }
    return new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' }).format(new Date(value));
  }

  panelSubtitle(): string {
    if (this.agentSelected()) {
      return 'Marketplace agent';
    }
    if (this.activeConversation()) {
      return this.otherParticipantLabel();
    }
    return this.conversations().length ? 'Select a conversation' : 'Ready when a chat starts';
  }

  listingMeta(listing: ChatListingSummary | null | undefined): string {
    if (!listing) {
      return '';
    }
    const parts: string[] = [];
    if (typeof listing.priceAmount === 'number') {
      parts.push(this.priceLabel(listing.priceAmount, listing.currency || 'USD'));
    }
    if (typeof listing.quantity === 'number') {
      parts.push(`Qty ${listing.quantity}`);
    }
    return parts.join(' · ');
  }

  listingTitle(listing: ChatListingSummary | null | undefined): string {
    if (!listing?.title || listing.title === 'Listing unavailable') {
      return 'Listing no longer public';
    }
    return listing.title;
  }

  listingIsPublic(listing: ChatListingSummary | null | undefined): boolean {
    return !!listing?.id && !!listing.title && listing.title !== 'Listing unavailable';
  }

  listingInitial(listing: ChatListingSummary | null | undefined): string {
    const title = listing?.title?.trim() || 'Listing';
    return title.substring(0, 2).toUpperCase();
  }

  dayLabel(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return 'Today';
    }
    const today = new Date();
    if (date.toDateString() === today.toDateString()) {
      return 'Today';
    }
    return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(date);
  }

  otherParticipantLabel(): string {
    const conversation = this.activeConversation();
    return conversation?.participants.find(participant => !participant.currentUser)?.displayName || 'Marketplace user';
  }

  senderLabel(message: ChatMessage): string {
    if (message.currentUser) {
      return 'You';
    }
    const participant = this.activeConversation()?.participants.find(item => item.participantId === message.senderUserId);
    return participant?.displayName || 'Marketplace user';
  }

  completionStatusTitle(): string {
    const completion = this.activeConversation()?.completion;
    if (completion?.status === 'BUYER_CONFIRMED') {
      return 'Trade completed';
    }
    if (completion?.currentUserCanConfirm) {
      return 'Confirm completion';
    }
    if (completion?.status === 'SELLER_MARKED_DONE') {
      return 'Waiting for buyer confirmation';
    }
    return 'Trade completion';
  }

  completionStatusText(): string {
    const completion = this.activeConversation()?.completion;
    if (completion?.status === 'SELLER_MARKED_DONE') {
      const quantity = this.quantityLabel(completion.quantitySold);
      return completion.currentUserCanConfirm
        ? `Confirm after the handoff is complete. ${quantity}`
        : `The buyer in this chat must confirm next. ${quantity}`;
    }
    if (completion?.status === 'BUYER_CONFIRMED') {
      return `The listing is closed and hidden from public search. ${this.quantityLabel(completion.quantitySold)}`;
    }
    return '';
  }

  normalizeQuantityInput(value: unknown): number {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < 1) {
      return 1;
    }
    return Math.floor(parsed);
  }

  refreshConversations(): void {
    this.loadingConversations.set(true);
    this.conversationError.set(false);
    this.chatService.getConversations(null, 20)
      .pipe(finalize(() => this.loadingConversations.set(false)))
      .subscribe({
        next: page => this.conversations.set(page.items),
        error: () => {
          this.conversations.set([]);
          this.conversationError.set(true);
        },
      });
  }

  private loadMessages(conversationId: string): void {
    this.chatService.getMessages(conversationId)
      .pipe(finalize(() => this.loadingThread.set(false)))
      .subscribe({
        next: page => {
          this.messages.set(page.items);
          this.chatService.markRead(conversationId).subscribe({
            next: () => this.markConversationReadLocally(conversationId),
            error: () => undefined,
          });
        },
        error: () => this.messages.set([]),
      });
  }

  private markConversationReadLocally(conversationId: string): void {
    this.conversations.update(conversations => conversations.map(conversation => (
      conversation.id === conversationId ? { ...conversation, unread: false } : conversation
    )));
  }

  private applyCompletion(completion: ConversationSummary['completion']): void {
    this.activeConversation.update(conversation => conversation ? { ...conversation, completion } : conversation);
  }

  private quantityLabel(quantity: number | null | undefined): string {
    return quantity ? `Quantity sold: ${quantity}.` : '';
  }

  private priceLabel(amount: number, currency: string): string {
    try {
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
        maximumFractionDigits: amount % 1 === 0 ? 0 : 2,
      }).format(amount);
    } catch {
      return `${amount} ${currency}`;
    }
  }
}
