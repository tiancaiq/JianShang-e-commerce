import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ChatListingSummary, ChatMessage, ConversationListItem, ConversationSummary } from '../../core/models/chat.model';
import { ChatService } from '../../core/services/chat.service';
import {
  AGENT_CUSTOMER_SERVICE_ENABLED,
  AGENT_DISCOVERY_ENABLED,
  AGENT_MARKETPLACE_V2_ENABLED,
} from '../agent/agent-customer-service.capability';
import { AgentMarketplaceDiscoveryComponent } from '../agent/agent-marketplace-discovery.component';
import { AgentMarketplaceV2PageComponent } from '../agent/agent-marketplace-v2-page.component';

@Component({
  selector: 'app-conversation-shell',
  standalone: true,
  imports: [
    AgentMarketplaceDiscoveryComponent,
    AgentMarketplaceV2PageComponent,
    DatePipe,
    FormsModule,
    RouterLink,
  ],
  template: `
    <section class="messages-page">
      <header class="messages-title">
        <div>
          <h1>Messages</h1>
          <p>Buyer and seller conversations for your marketplace account.</p>
        </div>
        <a routerLink="/account">Account</a>
      </header>

      <div class="messages-layout">
        <aside class="conversation-sidebar" aria-label="Conversations">
          <label class="search-box">
            <span>Search conversations</span>
            <input
              type="search"
              name="conversationSearch"
              placeholder="Search conversations"
              [ngModel]="conversationQuery()"
              (ngModelChange)="conversationQuery.set($event)" />
          </label>

          @if (agentEntryEnabled) {
            <button
              type="button"
              class="conversation-row agent-row"
              [class.active]="agentSelected()"
              (click)="selectAgent()">
              <span class="avatar-initials agent-initials">AI</span>
              <span class="conversation-copy">
                <span class="row-title">
                  <strong>Marketplace assistant</strong>
                  <small>AI assistant</small>
                </span>
                <span class="row-subtitle">
                  {{ agentV2Enabled ? 'Customer service and listing help' : 'Search and compare listings' }}
                </span>
              </span>
            </button>
          }

          @if (loadingConversations()) {
            <div class="empty-state compact">Loading conversations...</div>
          } @else if (!conversations().length) {
            <div class="empty-state compact">No conversations yet.</div>
          } @else if (!filteredConversations().length) {
            <div class="empty-state compact">No conversations found.</div>
          } @else {
            @for (conversation of filteredConversations(); track conversation.id) {
              <button
                type="button"
                class="conversation-row"
                [class.active]="conversation.id === selectedConversationId()"
                [class.unread]="conversation.unread"
                (click)="selectConversation(conversation.id)">
                @if (conversation.otherParticipant.avatarUrl) {
                  <img [src]="conversation.otherParticipant.avatarUrl" [alt]="conversation.otherParticipant.displayName" />
                } @else {
                  <span class="avatar-initials">{{ conversation.otherParticipant.initials }}</span>
                }
                <span class="conversation-copy">
                  <span class="row-title">
                    <strong>{{ conversation.otherParticipant.displayName }}</strong>
                    <time>{{ conversationTime(conversation) }}</time>
                  </span>
                  <small>{{ participantHandle(conversation.otherParticipant) }} - {{ conversation.listing.title }}</small>
                  <span class="row-preview">{{ preview(conversation) }}</span>
                </span>
                @if (conversation.unread) {
                  <span class="unread-dot" aria-label="Unread"></span>
                }
              </button>
            }
          }
        </aside>

        <article class="thread-panel">
          @if (agentEntryEnabled && agentSelected()) {
            <section class="agent-discovery-pane" aria-label="Marketplace assistant">
              @if (agentV2Enabled) {
                <app-agent-marketplace-v2-page [embedded]="true" />
              } @else {
                <app-agent-marketplace-discovery />
              }
            </section>
          } @else if (loadingThread()) {
            <div class="empty-state">Loading messages...</div>
          } @else if (threadError()) {
            <div class="empty-state">{{ threadError() }}</div>
          } @else if (selectedConversation()) {
            <header class="listing-header">
              <div class="listing-identity">
                @if (selectedConversation()?.listing?.thumbnailUrl) {
                  <img [src]="selectedConversation()?.listing?.thumbnailUrl || ''" [alt]="selectedConversation()?.listing?.title || 'Listing image'" />
                } @else {
                  <span class="listing-fallback">{{ listingInitial(selectedConversation()?.listing) }}</span>
                }
                <div>
                  <h2>{{ selectedConversation()?.listing?.title }}</h2>
                  @if (listingMeta(selectedConversation()?.listing)) {
                    <p>{{ listingMeta(selectedConversation()?.listing) }}</p>
                  }
                </div>
              </div>
              <a [routerLink]="['/listings', selectedConversation()?.listing?.id]">View listing</a>
            </header>

            <section class="safety-note">
              Safety note: Payment and delivery are arranged directly between users. Meet in public.
            </section>

            <ol class="trade-progress" aria-label="Trade completion status">
              <li [class.current]="completionStage() === 0" [class.complete]="completionStage() > 0">Discussing</li>
              <li [class.current]="completionStage() === 1" [class.complete]="completionStage() > 1">Seller marked done</li>
              <li [class.current]="completionStage() === 2" [class.complete]="completionStage() > 2">Awaiting buyer</li>
              <li [class.current]="completionStage() === 3">Completed</li>
            </ol>

            @if (selectedConversation()?.completion?.currentUserCanMarkDone) {
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
                    name="completionQuantity"
                    [ngModel]="completionQuantity()"
                    (ngModelChange)="completionQuantity.set(normalizeQuantityInput($event))" />
                </label>
                <button type="button" [disabled]="completionActionLoading()" (click)="markDone()">
                  {{ completionActionLoading() ? 'Marking...' : 'Mark as done' }}
                </button>
              </section>
            }

            @if (selectedConversation()?.completion?.currentUserCanConfirm || completionStatusText()) {
              <section class="completion-status" [class.done]="selectedConversation()?.completion?.status === 'BUYER_CONFIRMED'">
                <div>
                  <strong>{{ completionStatusTitle() }}</strong>
                  <span>{{ completionStatusText() }}</span>
                </div>
                @if (selectedConversation()?.completion?.currentUserCanConfirm) {
                  <button type="button" [disabled]="completionActionLoading()" (click)="openConfirmationReview()">
                    Review confirmation
                  </button>
                }
              </section>
            }

            @if (confirmationReviewOpen() && selectedConversation()?.completion?.currentUserCanConfirm) {
              <section class="confirmation-review" aria-label="Review trade confirmation">
                <header>
                  <strong>Confirm this handoff</strong>
                  <span>Check the trade details before closing the conversation.</span>
                </header>
                <dl>
                  <div><dt>Item</dt><dd>{{ selectedConversation()?.listing?.title }}</dd></div>
                  <div><dt>Quantity</dt><dd>{{ selectedConversation()?.completion?.quantitySold || 1 }}</dd></div>
                  <div><dt>Seller</dt><dd>{{ sellerLabel() }} <small>{{ sellerHandle() }}</small></dd></div>
                </dl>
                <p>Payment and delivery happened off-platform. MSB Commerce does not verify or protect that payment.</p>
                <div class="review-actions">
                  <button type="button" class="secondary" (click)="confirmationReviewOpen.set(false)">Back</button>
                  <button type="button" [disabled]="completionActionLoading()" (click)="confirmCompleted()">
                    {{ completionActionLoading() ? 'Confirming...' : 'Confirm completed' }}
                  </button>
                </div>
              </section>
            }

            <section class="message-list" aria-live="polite">
              @if (!messages().length) {
                <div class="empty-state">No messages yet.</div>
              } @else {
                @for (message of messages(); track message.id; let first = $first) {
                  @if (first) {
                    <div class="day-divider">{{ dayLabel(message.createdAt) }}</div>
                  }
                  <article class="message-bubble" [class.mine]="message.currentUser">
                    <span>{{ senderLabel(message) }} - {{ message.createdAt | date: 'shortTime' }}</span>
                    <p>{{ message.body }}</p>
                  </article>
                }
              }
            </section>

            @if (conversationReadOnly()) {
              <footer class="completed-footer">
                <span><strong>Completed</strong> This conversation is now read-only.</span>
                @if (currentUserIsSeller()) {
                  <a routerLink="/account/listings">View completed listing history</a>
                }
              </footer>
            } @else {
              <form class="composer" (ngSubmit)="sendMessage()">
                <textarea
                  name="messageBody"
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
            }
          } @else {
            <div class="thread-placeholder">
              <strong>Select a conversation</strong>
              <span>Choose a buyer or seller from the conversation list.</span>
            </div>
          }
        </article>
      </div>
    </section>
  `,
  styles: [`
    .messages-page {
      display: flex;
      flex-direction: column;
      min-height: 0;
      gap: 1rem;
      color: var(--market-ink);
    }

    .messages-title,
    .messages-layout {
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .messages-title {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem;
    }

    h1,
    h2,
    p {
      margin: 0;
    }

    h1 {
      font-size: 1.55rem;
      font-weight: 950;
    }

    .messages-title p {
      margin-top: 0.25rem;
      color: var(--market-muted);
      font-weight: 750;
    }

    .messages-title a,
    .listing-header a {
      color: var(--market-accent-dark);
      font-weight: 900;
      text-decoration: none;
    }

    .messages-layout {
      display: grid;
      grid-template-columns: minmax(300px, 0.38fr) minmax(0, 1fr);
      height: min(760px, calc(100dvh - 210px));
      min-height: 420px;
      overflow: hidden;
    }

    .conversation-sidebar {
      display: flex;
      flex-direction: column;
      overflow-y: auto;
      border-right: 1px solid rgba(234, 215, 242, 0.95);
      background: #fffafd;
    }

    .search-box {
      display: grid;
      gap: 0.35rem;
      padding: 0.85rem;
      border-bottom: 1px solid var(--market-line);
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 900;
    }

    .search-box input {
      min-height: 40px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      font-size: 0.86rem;
      font-weight: 800;
      padding: 0 0.75rem;
    }

    .conversation-row {
      width: 100%;
      display: grid;
      grid-template-columns: 48px minmax(0, 1fr) 10px;
      gap: 0.75rem;
      align-items: center;
      min-height: 86px;
      border: 0;
      border-bottom: 1px solid var(--market-line);
      background: transparent;
      color: inherit;
      cursor: pointer;
      font: inherit;
      padding: 0.75rem 0.85rem;
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
      grid-template-columns: 48px minmax(0, 1fr);
      min-height: 76px;
      background: #fff7fc;
      opacity: 0.86;
    }

    .agent-row.active {
      opacity: 1;
    }

    .conversation-row img,
    .avatar-initials,
    .listing-header img,
    .listing-fallback {
      width: 48px;
      height: 48px;
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
      font-size: 0.86rem;
      font-weight: 950;
    }

    .agent-initials,
    .agent-large-icon {
      background: linear-gradient(135deg, #f7b84b, #ffd0e4);
    }

    .conversation-copy {
      display: grid;
      min-width: 0;
      gap: 0.2rem;
    }

    .row-title {
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      gap: 0.5rem;
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
      font-size: 0.9rem;
      font-weight: 950;
    }

    .conversation-copy small,
    .row-preview,
    .conversation-copy time {
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 800;
    }

    .agent-row small {
      border: 1px solid rgba(190, 47, 118, 0.18);
      border-radius: 999px;
      color: var(--market-accent-dark);
      padding: 0.08rem 0.42rem;
    }

    .unread-dot {
      width: 9px;
      height: 9px;
      border-radius: 999px;
      background: var(--market-accent-dark);
    }

    .thread-panel {
      display: flex;
      flex-direction: column;
      min-width: 0;
      min-height: 0;
      height: 100%;
      overflow: hidden;
      background: #fff;
    }

    .agent-discovery-pane {
      display: flex;
      flex: 1 1 auto;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
      padding: 0.75rem;
      background: #fffafd;
    }

    .listing-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      flex-shrink: 0;
      padding: 0.9rem 1rem;
      border-bottom: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .listing-identity {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      min-width: 0;
    }

    .listing-header h2 {
      overflow: hidden;
      font-size: 1rem;
      font-weight: 950;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .listing-header p {
      margin-top: 0.22rem;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 850;
    }

    .listing-header a {
      min-height: 36px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: 0 0 auto;
      border: 1px solid rgba(190, 47, 118, 0.2);
      border-radius: 8px;
      background: #fff;
      padding: 0 0.75rem;
    }

    .safety-note {
      flex-shrink: 0;
      margin: 0.85rem 1rem 0;
      border: 1px solid rgba(247, 184, 75, 0.34);
      border-radius: 8px;
      background: #fffaf0;
      color: #754f19;
      font-size: 0.82rem;
      font-weight: 800;
      line-height: 1.45;
      padding: 0.65rem 0.75rem;
    }

    .trade-progress {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      flex-shrink: 0;
      gap: 0;
      margin: 0.85rem 1rem 0;
      padding: 0;
      list-style: none;
    }

    .trade-progress li {
      position: relative;
      border-top: 3px solid var(--market-line);
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 900;
      padding: 0.45rem 0.25rem 0;
      text-align: center;
    }

    .trade-progress li.complete,
    .trade-progress li.current {
      border-color: var(--market-accent-dark);
      color: var(--market-accent-dark);
    }

    .trade-card,
    .completion-status {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 110px max-content;
      gap: 0.75rem;
      align-items: end;
      flex-shrink: 0;
      margin: 0.85rem 1rem 0;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: #fffafd;
      padding: 0.75rem;
    }

    .completion-status {
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: center;
    }

    .completion-status.done {
      border-color: rgba(42, 168, 135, 0.22);
      background: #f7fff7;
    }

    .confirmation-review {
      flex-shrink: 0;
      margin: 0.85rem 1rem 0;
      border: 1px solid rgba(190, 47, 118, 0.28);
      border-radius: 8px;
      background: #fff8fc;
      padding: 0.8rem;
    }

    .confirmation-review header span,
    .confirmation-review p {
      display: block;
      margin: 0.2rem 0 0;
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 800;
      line-height: 1.4;
    }

    .confirmation-review dl {
      display: grid;
      grid-template-columns: 2fr 0.7fr 1.5fr;
      gap: 0.55rem;
      margin: 0.7rem 0;
    }

    .confirmation-review dl div {
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      padding: 0.5rem;
    }

    .confirmation-review dt {
      color: var(--market-muted);
      font-size: 0.65rem;
      font-weight: 900;
    }

    .confirmation-review dd {
      margin: 0.18rem 0 0;
      font-size: 0.78rem;
      font-weight: 900;
    }

    .confirmation-review dd small {
      color: var(--market-accent-dark);
    }

    .review-actions,
    .completed-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
    }

    .review-actions button,
    .completed-footer a {
      min-height: 36px;
      display: inline-flex;
      align-items: center;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-size: 0.76rem;
      font-weight: 900;
      padding: 0 0.75rem;
      text-decoration: none;
    }

    .review-actions button.secondary {
      border: 1px solid var(--market-line);
      background: #fff;
      color: var(--market-muted);
    }

    .completed-footer {
      flex-shrink: 0;
      border-top: 1px solid var(--market-line);
      background: #f7fff7;
      color: #286c59;
      font-size: 0.78rem;
      font-weight: 800;
      padding: 0.75rem 1rem;
    }


    .trade-card strong,
    .completion-status strong {
      display: block;
      color: var(--market-ink);
      font-size: 0.88rem;
      font-weight: 950;
    }

    .trade-card span,
    .completion-status span {
      display: block;
      margin-top: 0.2rem;
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 800;
      line-height: 1.35;
    }

    .quantity-field {
      display: grid;
      gap: 0.18rem;
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 900;
    }

    .quantity-field input {
      width: 100%;
      min-height: 36px;
      box-sizing: border-box;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      color: var(--market-ink);
      font: inherit;
      font-weight: 900;
      padding: 0 0.45rem;
    }

    .trade-card button,
    .completion-status button,
    .composer-actions button {
      min-height: 38px;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-size: 0.82rem;
      font-weight: 900;
      padding: 0 0.9rem;
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
      gap: 0.7rem;
      overflow-y: auto;
      min-height: 0;
      padding: 1rem;
    }

    .day-divider {
      align-self: center;
      border: 1px solid var(--market-line);
      border-radius: 999px;
      background: #fffafd;
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 900;
      padding: 0.18rem 0.62rem;
    }

    .message-bubble {
      max-width: min(620px, 78%);
      align-self: flex-start;
    }

    .message-bubble.mine {
      align-self: flex-end;
    }

    .message-bubble span {
      display: block;
      margin-bottom: 0.24rem;
      color: var(--market-muted);
      font-size: 0.72rem;
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
      overflow-wrap: anywhere;
      padding: 0.68rem 0.78rem;
      white-space: pre-wrap;
      line-height: 1.45;
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
      gap: 0.45rem;
      flex-shrink: 0;
      padding: 0.8rem 1rem;
      border-top: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .composer textarea {
      width: 100%;
      min-height: 54px;
      resize: vertical;
      box-sizing: border-box;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      color: var(--market-ink);
      font: inherit;
      line-height: 1.4;
      padding: 0.65rem 0.75rem;
    }

    .composer-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
    }

    .composer-actions span {
      color: var(--market-muted);
      font-size: 0.74rem;
      font-weight: 800;
    }

    .empty-state,
    .thread-placeholder {
      margin: auto;
      color: var(--market-muted);
      font-weight: 850;
      padding: 1.5rem;
      text-align: center;
    }

    .empty-state.compact {
      margin: 0;
      border-bottom: 1px solid var(--market-line);
    }

    .thread-placeholder {
      display: grid;
      place-items: center;
      align-content: center;
      gap: 0.4rem;
      min-height: 100%;
    }

    .thread-placeholder strong {
      color: var(--market-ink);
      font-size: 1rem;
      font-weight: 950;
    }

    .thread-placeholder span {
      max-width: 280px;
      line-height: 1.45;
    }

    .agent-large-icon {
      width: 58px;
      height: 58px;
      border-radius: 8px;
      font-size: 1rem;
    }

    @media (max-width: 780px) {
      .messages-title {
        align-items: flex-start;
        flex-direction: column;
      }

      .messages-layout {
        grid-template-columns: 1fr;
        grid-template-rows: auto minmax(0, 1fr);
        height: min(760px, calc(100dvh - 150px));
      }

      .conversation-sidebar {
        max-height: 220px;
        border-right: 0;
        border-bottom: 1px solid rgba(234, 215, 242, 0.95);
      }

      .listing-header,
      .trade-card,
      .completion-status,
      .confirmation-review dl {
        align-items: stretch;
        grid-template-columns: 1fr;
      }

      .listing-header {
        flex-direction: column;
      }

      .listing-header a {
        width: max-content;
      }

      .message-bubble {
        max-width: 88%;
      }
    }
  `],
})
export class ConversationShellComponent implements OnInit {
  readonly defaultNotice = 'Payment and delivery are arranged directly by participants.';
  readonly aiAssistantEnabled = inject(AGENT_CUSTOMER_SERVICE_ENABLED);
  readonly aiDiscoveryEnabled = inject(AGENT_DISCOVERY_ENABLED);
  readonly agentV2Enabled = inject(AGENT_MARKETPLACE_V2_ENABLED);
  get agentEntryEnabled(): boolean {
    return this.agentV2Enabled || this.aiAssistantEnabled || this.aiDiscoveryEnabled;
  }

  private readonly chatService = inject(ChatService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly locallyReadConversationIds = new Set<string>();

  conversations = signal<ConversationListItem[]>([]);
  selectedConversation = signal<ConversationSummary | null>(null);
  selectedConversationId = signal<string | null>(null);
  agentSelected = signal(false);
  messages = signal<ChatMessage[]>([]);
  draft = signal('');
  conversationQuery = signal('');
  loadingConversations = signal(false);
  loadingThread = signal(false);
  sending = signal(false);
  completionActionLoading = signal(false);
  completionQuantity = signal(1);
  confirmationReviewOpen = signal(false);
  threadError = signal('');

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
    this.chatService.conversationRead$
      .pipe(takeUntilDestroyed())
      .subscribe(conversationId => this.markConversationReadLocally(conversationId, false));
  }

  ngOnInit(): void {
    this.route.paramMap.subscribe(params => {
      const conversationId = params.get('conversationId');
      if (conversationId === 'agent') {
        this.loadConversations(null, false);
        this.selectAgent();
        return;
      }
      this.loadConversations(conversationId);
    });
  }

  selectConversation(conversationId: string): void {
    this.agentSelected.set(false);
    void this.router.navigate(['/account/messages', conversationId]);
    this.openConversation(conversationId);
  }

  /** Opens the discovery assistant without treating it as a Chat Service conversation. */
  selectAgent(): void {
    if (!this.agentEntryEnabled) {
      return;
    }
    this.agentSelected.set(true);
    this.selectedConversation.set(null);
    this.selectedConversationId.set(null);
    this.messages.set([]);
    this.threadError.set('');
  }

  sendMessage(): void {
    const conversation = this.selectedConversation();
    const body = this.draft().trim();
    if (!conversation || !body || this.sending() || this.conversationReadOnly()) {
      return;
    }
    this.sending.set(true);
    this.chatService.sendMessage(conversation.id, body)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: message => {
          this.messages.update(messages => [...messages, message]);
          this.draft.set('');
          this.loadConversations(conversation.id, false);
        },
        error: () => this.threadError.set('Message could not be sent.'),
      });
  }

  markDone(): void {
    const conversation = this.selectedConversation();
    if (!conversation || !conversation.completion?.currentUserCanMarkDone || this.completionActionLoading()) {
      return;
    }
    this.completionActionLoading.set(true);
    this.chatService.markDone(conversation.id, this.completionQuantity())
      .pipe(finalize(() => this.completionActionLoading.set(false)))
      .subscribe({
        next: completion => this.applyCompletion(completion),
        error: () => this.threadError.set('Could not mark this deal done.'),
      });
  }

  confirmCompleted(): void {
    const conversation = this.selectedConversation();
    if (!conversation || !conversation.completion?.currentUserCanConfirm
      || !this.confirmationReviewOpen() || this.completionActionLoading()) {
      return;
    }
    this.completionActionLoading.set(true);
    this.chatService.confirmCompletion(conversation.id)
      .pipe(finalize(() => this.completionActionLoading.set(false)))
      .subscribe({
        next: completion => this.applyCompletion(completion),
        error: () => this.threadError.set('Could not confirm this completion.'),
      });
  }

  completionStatusTitle(): string {
    const completion = this.selectedConversation()?.completion;
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
    const completion = this.selectedConversation()?.completion;
    if (completion?.status === 'SELLER_MARKED_DONE') {
      const quantity = this.quantityLabel(completion.quantitySold);
      return completion.currentUserCanConfirm
        ? `Confirm only after the handoff is complete. ${quantity}`
        : `The buyer in this chat must confirm next. ${quantity}`;
    }
    if (completion?.status === 'BUYER_CONFIRMED') {
      return `The listing is closed and hidden from public search. ${this.quantityLabel(completion.quantitySold)}`;
    }
    return '';
  }

  openConfirmationReview(): void {
    if (this.selectedConversation()?.completion?.currentUserCanConfirm) {
      this.confirmationReviewOpen.set(true);
    }
  }

  completionStage(): number {
    const status = this.selectedConversation()?.completion?.status;
    if (status === 'BUYER_CONFIRMED') {
      return 3;
    }
    if (status === 'SELLER_MARKED_DONE') {
      return 2;
    }
    return 0;
  }

  conversationReadOnly(): boolean {
    return this.selectedConversation()?.completion?.status === 'BUYER_CONFIRMED';
  }

  sellerLabel(): string {
    return this.selectedConversation()?.participants
      .find(participant => participant.roleInConversation === 'SELLER')?.displayName || 'Marketplace seller';
  }

  currentUserIsSeller(): boolean {
    return this.selectedConversation()?.participants
      .some(participant => participant.currentUser && participant.roleInConversation === 'SELLER') || false;
  }

  sellerHandle(): string {
    const participant = this.selectedConversation()?.participants
      .find(item => item.roleInConversation === 'SELLER');
    return this.participantHandle(participant);
  }

  participantHandle(participant: { participantId: string; publicHandle?: string | null } | null | undefined): string {
    const handle = participant?.publicHandle?.trim()
      || `member-${participant?.participantId?.slice(-8).toLowerCase() || 'unknown'}`;
    return `@${handle.replace(/^@/, '')}`;
  }

  normalizeQuantityInput(value: unknown): number {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < 1) {
      return 1;
    }
    return Math.floor(parsed);
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
    return parts.join(' - ');
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
    const conversation = this.selectedConversation();
    return conversation?.participants.find(participant => !participant.currentUser)?.displayName || 'Marketplace user';
  }

  senderLabel(message: ChatMessage): string {
    if (message.currentUser) {
      return 'You';
    }
    const participant = this.selectedConversation()?.participants.find(item => item.participantId === message.senderUserId);
    return participant?.displayName || 'Marketplace user';
  }

  private applyCompletion(completion: ConversationSummary['completion']): void {
    this.confirmationReviewOpen.set(false);
    this.selectedConversation.update(conversation => conversation ? {
      ...conversation,
      status: completion?.status === 'BUYER_CONFIRMED' ? 'LOCKED' : conversation.status,
      completion,
    } : conversation);
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

  private loadConversations(preferredConversationId: string | null, openPreferred = true): void {
    this.loadingConversations.set(true);
    this.chatService.getConversations()
      .pipe(finalize(() => this.loadingConversations.set(false)))
      .subscribe({
        next: page => {
          this.conversations.set(this.applyLocalReadState(page.items));
          if (!openPreferred) {
            return;
          }
          const target = preferredConversationId || page.items[0]?.id || null;
          if (target) {
            this.openConversation(target);
          }
        },
        error: () => this.threadError.set('Messages are temporarily unavailable.'),
      });
  }

  private openConversation(conversationId: string): void {
    this.selectedConversationId.set(conversationId);
    this.confirmationReviewOpen.set(false);
    this.loadingThread.set(true);
    this.threadError.set('');
    this.markConversationReadLocally(conversationId);
    this.chatService.getConversation(conversationId).subscribe({
      next: conversation => {
        this.selectedConversation.set(conversation);
        this.completionQuantity.set(conversation.completion?.quantitySold || 1);
        this.loadMessages(conversation.id);
      },
      error: () => {
        this.loadingThread.set(false);
        this.selectedConversation.set(null);
        this.messages.set([]);
        this.threadError.set('Conversation was not found.');
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
        error: () => {
          this.messages.set([]);
          this.threadError.set('Messages could not be loaded.');
        },
      });
  }

  private markConversationReadLocally(conversationId: string, notify = true): void {
    this.locallyReadConversationIds.add(conversationId);
    this.conversations.update(conversations => conversations.map(conversation => (
      conversation.id === conversationId ? { ...conversation, unread: false } : conversation
    )));
    if (notify) {
      this.chatService.notifyConversationRead(conversationId);
    }
  }

  private applyLocalReadState(conversations: ConversationListItem[]): ConversationListItem[] {
    if (!this.locallyReadConversationIds.size) {
      return conversations;
    }
    return conversations.map(conversation => (
      this.locallyReadConversationIds.has(conversation.id) ? { ...conversation, unread: false } : conversation
    ));
  }
}
