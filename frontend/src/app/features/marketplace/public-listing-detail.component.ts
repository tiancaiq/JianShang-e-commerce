import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ChatMessage, ChatParticipantSummary, ConversationSummary } from '../../core/models/chat.model';
import { ListingEngagement, PublicListing } from '../../core/models/listing.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { ListingService } from '../../core/services/listing.service';
import { ListingImageGalleryComponent } from '../../shared/components/ui/listing-image-gallery.component';
import {
  publicListingConditionLabel,
  publicListingLocationLabel,
  publicListingOwnerLabel,
} from '../../shared/listing/public-listing-display';
import { ProfileCardUser, UserProfileCardComponent } from '../account/user-profile-card.component';

@Component({
  selector: 'app-public-listing-detail',
  standalone: true,
  imports: [DecimalPipe, FormsModule, ListingImageGalleryComponent, RouterLink, UserProfileCardComponent],
  template: `
    <section class="listing-detail">
      @if (loading()) {
        <div class="empty-state">Loading listing...</div>
      } @else if (errorMsg()) {
        <div class="error-state">
          <h1>Listing unavailable</h1>
          <p>{{ errorMsg() }}</p>
          <a routerLink="/">Back to marketplace</a>
        </div>
      } @else if (listing()) {
        <nav class="crumbs" aria-label="Breadcrumb">
          <a routerLink="/">Marketplace</a>
          <span>/</span>
          <span>{{ listing()?.categoryName }}</span>
        </nav>

        <div class="detail-grid">
          <section class="gallery" aria-label="Listing images">
            <app-listing-image-gallery [images]="listing()?.images || []" [fallbackAlt]="listing()?.title || 'Listing image'" />
          </section>

          <div class="detail-aside">
            <article class="purchase-panel">
              <div class="badge-row" aria-label="Listing summary labels">
                <span class="category-tag">{{ listing()?.categoryName }}</span>
                <span class="condition-tag">{{ conditionLabel(listing()?.condition || '') }}</span>
                <strong class="seller-type-tag" [class.business]="listing()?.sellerType === 'BUSINESS'">
                  {{ listing()?.sellerType === 'INDIVIDUAL' ? 'Individual seller' : 'Business seller' }}
                </strong>
              </div>

              <h1>{{ listing()?.title }}</h1>
              <p class="owner-line">Listed by {{ ownerLabel(listing()) }}</p>

              <div class="price-line">
                <strong>{{ listing()?.priceAmount | number: '1.2-2' }} {{ listing()?.currency }}</strong>
                @if (listing()?.negotiable) {
                  <span class="negotiable-pill">Negotiable</span>
                }
              </div>

              <div class="engagement-row" aria-label="Listing engagement">
                <span>{{ viewCountLabel() }}</span>
                <button
                  class="like-button"
                  type="button"
                  [class.liked]="likedByMe()"
                  [disabled]="engagementLoading()"
                  [attr.aria-pressed]="likedByMe()"
                  (click)="toggleLike()">
                  {{ likeButtonLabel() }}
                </button>
              </div>
              @if (engagementError()) {
                <p class="conversation-error">{{ engagementError() }}</p>
              }

              <div class="detail-support" aria-label="Marketplace trade reminders">
                <article>
                  <span>♡</span>
                  <strong>Seller contact</strong>
                  <p>Start a conversation before arranging any trade details.</p>
                </article>
                <article>
                  <span>✦</span>
                  <strong>Public area only</strong>
                  <p>Use city or region context. Exact personal addresses stay private.</p>
                </article>
              </div>

              @if (listing()?.sellerType === 'INDIVIDUAL') {
                @if (selfChatBlocked()) {
                  <p class="conversation-error">This is your listing.</p>
                } @else {
                  <button class="message-button" type="button" [disabled]="startingConversation()" (click)="messageSeller()">
                    {{ messageButtonLabel() }}
                  </button>
                  @if (conversationError()) {
                    <p class="conversation-error">{{ conversationError() }}</p>
                  }
                }
              }

              <section class="product-facts" aria-labelledby="product-details-heading">
                <h2 id="product-details-heading">Product details</h2>
                <dl class="facts">
                  <div>
                    <dt>Condition</dt>
                    <dd>{{ conditionLabel(listing()?.condition || '') }}</dd>
                  </div>
                  <div>
                    <dt>Quantity</dt>
                    <dd>{{ listing()?.quantity || 1 }} available</dd>
                  </div>
                  <div>
                    <dt>Seller</dt>
                    <dd>{{ ownerLabel(listing()) }}</dd>
                  </div>
                  <div>
                    <dt>Location</dt>
                    <dd>{{ locationLabel(listing()) }}</dd>
                  </div>
                </dl>
              </section>

              <aside class="notice-panel" [class.business]="listing()?.sellerType === 'BUSINESS'">
                <strong>Safety note</strong>
                <p>{{ marketplaceNotice(listing()) }}</p>
              </aside>
            </article>

            <app-user-profile-card class="seller-profile" [user]="sellerProfile()" [showSellAction]="false" />
          </div>
        </div>

        <section class="description-panel">
          <div>
            <h2>Description</h2>
            <p>{{ listing()?.description }}</p>
          </div>

          @if (listing()?.conditionNotes) {
            <div>
              <h2>Condition Notes</h2>
              <p>{{ listing()?.conditionNotes }}</p>
            </div>
          }
        </section>

        @if (chatOpen() && conversation()) {
          <div class="chat-backdrop" (click)="closeChat()"></div>
          <aside class="chat-drawer" role="dialog" aria-modal="true" aria-label="Listing chat">
            <header class="chat-header">
              @if (conversation()?.listing?.thumbnailUrl) {
                <img [src]="conversation()?.listing?.thumbnailUrl || ''" [alt]="conversation()?.listing?.title || 'Listing image'" />
              }
              <div>
                <h2>{{ conversation()?.listing?.title }}</h2>
                <p>{{ otherParticipantLabel(conversation()) }}</p>
              </div>
              <button type="button" class="chat-close" (click)="closeChat()">Close</button>
            </header>

            <p class="chat-notice">{{ conversation()?.listing?.transactionNotice || marketplaceNotice(listing()) }}</p>

            <section class="chat-messages" aria-live="polite">
              @if (loadingMessages()) {
                <div class="chat-empty">Loading messages...</div>
              } @else if (!messages().length) {
                <div class="chat-empty">No messages yet.</div>
              } @else {
                @for (message of messages(); track message.id) {
                  <article class="chat-message" [class.mine]="message.currentUser">
                    <span>{{ senderLabel(message) }}</span>
                    <p>{{ message.body }}</p>
                  </article>
                }
              }
            </section>

            <form class="chat-composer" (ngSubmit)="sendChatMessage()">
              <textarea
                name="messageBody"
                maxlength="2000"
                rows="3"
                [ngModel]="messageDraft()"
                (ngModelChange)="messageDraft.set($event)"
                placeholder="Write a message"></textarea>
              <div class="chat-compose-actions">
                <span>{{ messageDraft().length }}/2000</span>
                <button type="submit" [disabled]="sendingMessage() || !messageDraft().trim()">
                  {{ sendingMessage() ? 'Sending...' : 'Send' }}
                </button>
              </div>
            </form>
          </aside>
        }
      }
    </section>
  `,
  styles: [`
    .listing-detail {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      color: var(--market-ink);
    }

    .crumbs {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      width: fit-content;
      padding: 0.5rem 0.7rem;
      border: 1px solid rgba(234, 215, 242, 0.85);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.78);
      color: var(--market-muted);
      font-size: 0.875rem;
      font-weight: 800;
      box-shadow: 0 10px 22px rgba(143, 92, 144, 0.08);
    }

    .crumbs a {
      color: var(--market-accent-dark);
      text-decoration: none;
    }

    .detail-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.12fr) minmax(330px, 0.72fr);
      gap: 1rem;
      align-items: start;
    }

    .gallery,
    .purchase-panel,
    .description-panel,
    .empty-state,
    .error-state {
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .gallery {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      padding: 0.75rem;
      background:
        linear-gradient(180deg, rgba(255, 248, 252, 0.96), rgba(246, 240, 255, 0.92)),
        #fff;
    }

    .detail-aside {
      position: sticky;
      top: 92px;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .purchase-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1.15rem;
      background:
        radial-gradient(circle at 100% 0%, rgba(246, 190, 222, 0.28), transparent 34%),
        linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(255, 248, 252, 0.94));
    }

    .seller-profile {
      display: block;
    }

    .badge-row,
    .price-line {
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      align-items: center;
    }

    .price-line {
      justify-content: flex-start;
      gap: 0.75rem;
    }

    .category-tag,
    .condition-tag,
    .seller-type-tag,
    .negotiable-pill {
      min-height: 28px;
      display: inline-flex;
      align-items: center;
      border-radius: 999px;
      padding: 0 0.65rem;
      font-size: 0.78rem;
      font-weight: 900;
    }

    .category-tag {
      background: #fff0f7;
      color: var(--market-accent-dark);
    }

    .condition-tag {
      background: #fff;
      color: var(--market-purple, #8b6fe8);
      box-shadow: 0 6px 14px rgba(143, 92, 144, 0.1);
    }

    .seller-type-tag {
      background: #eafaf6;
      color: var(--market-mint);
    }

    .seller-type-tag:not(.business) {
      background: #f1ecff;
      color: var(--market-lavender);
    }

    .negotiable-pill {
      min-height: 26px;
      background: #f1ecff;
      color: var(--market-lavender);
      font-size: 0.78rem;
      box-shadow: 0 8px 18px rgba(143, 92, 144, 0.1);
    }

    h1,
    h2 {
      margin: 0;
      color: var(--market-ink);
      letter-spacing: 0;
    }

    h1 {
      font-size: clamp(1.8rem, 4vw, 3rem);
      line-height: 1.05;
      font-weight: 900;
    }

    h2 {
      font-size: 1.05rem;
    }

    .price-line strong {
      color: var(--market-accent-dark);
      font-size: 1.75rem;
      font-weight: 950;
      text-shadow: 0 1px 0 rgba(255, 255, 255, 0.86);
    }

    .owner-line {
      margin: -0.35rem 0 0;
      color: var(--market-lavender);
      font-weight: 900;
    }

    .engagement-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      align-items: center;
    }

    .engagement-row span,
    .like-button {
      min-height: 34px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--market-line);
      border-radius: 999px;
      background: #fff8fc;
      color: var(--market-muted);
      font: inherit;
      font-size: 0.86rem;
      font-weight: 900;
      padding: 0 0.75rem;
    }

    .like-button {
      min-width: 0;
      min-height: 34px;
      background: rgba(255, 255, 255, 0.84);
      color: var(--market-accent-dark);
      cursor: pointer;
      border: 1px solid rgba(244, 114, 182, 0.28);
      box-shadow: 0 8px 16px rgba(190, 58, 131, 0.1);
    }

    .like-button.liked {
      border-color: transparent;
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.2);
    }

    .like-button:disabled {
      cursor: wait;
      opacity: 0.68;
    }

    .product-facts {
      display: grid;
      gap: 0.75rem;
      padding-top: 0.2rem;
    }

    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0;
      margin: 0;
      border: 1px solid var(--market-line);
      border-radius: 16px;
      background: rgba(255, 248, 252, 0.78);
      overflow: hidden;
    }

    .facts div {
      min-height: 64px;
      border-right: 1px solid var(--market-line);
      border-bottom: 1px solid var(--market-line);
      padding: 0.75rem 0.85rem;
      background: rgba(255, 255, 255, 0.58);
    }

    .facts div:nth-child(2n) {
      border-right: 0;
    }

    .facts div:nth-last-child(-n + 2) {
      border-bottom: 0;
    }

    dt {
      margin-bottom: 0.3rem;
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 850;
    }

    dd {
      margin: 0;
      color: var(--market-ink);
      overflow-wrap: anywhere;
      font-weight: 850;
    }

    .notice-panel {
      display: grid;
      gap: 0.25rem;
      border: 1px solid rgba(244, 114, 182, 0.18);
      border-radius: 16px;
      background: rgba(255, 246, 251, 0.72);
      color: #774163;
      padding: 0.8rem 0.9rem;
      line-height: 1.45;
      font-weight: 700;
    }

    .notice-panel strong {
      color: var(--market-accent-dark);
      font-size: 0.82rem;
      font-weight: 950;
    }

    .notice-panel p {
      margin: 0;
      color: #84516c;
      font-size: 0.82rem;
      line-height: 1.45;
      font-weight: 750;
    }

    .notice-panel.business {
      border-color: rgba(56, 168, 149, 0.24);
      background: #effbf8;
      color: #246558;
    }

    .detail-support {
      display: none;
    }

    .message-button {
      min-height: 48px;
      border: 0;
      border-radius: 999px;
      background: linear-gradient(135deg, #ff79b8, #8b6fe8);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-weight: 900;
      padding: 0.75rem 1rem;
      box-shadow: 0 14px 26px rgba(190, 58, 131, 0.2);
    }

    .message-button:disabled {
      cursor: wait;
      opacity: 0.68;
    }

    .conversation-message,
    .conversation-error {
      margin: -0.35rem 0 0;
      line-height: 1.45;
      font-size: 0.9rem;
      font-weight: 800;
    }

    .conversation-message {
      color: var(--market-mint);
    }

    .conversation-error {
      color: #b4235a;
    }

    .chat-backdrop {
      position: fixed;
      inset: 0;
      z-index: 40;
      background: rgba(42, 26, 50, 0.18);
    }

    .chat-drawer {
      position: fixed;
      right: 1.25rem;
      bottom: 1.25rem;
      z-index: 41;
      width: min(390px, calc(100vw - 2rem));
      max-height: min(680px, calc(100vh - 2rem));
      display: grid;
      grid-template-rows: auto auto minmax(220px, 1fr) auto;
      overflow: hidden;
      border: 1px solid rgba(198, 168, 214, 0.9);
      border-radius: 8px;
      background: #fff;
      box-shadow: 0 24px 70px rgba(73, 42, 84, 0.28);
    }

    .chat-header {
      display: grid;
      grid-template-columns: 48px minmax(0, 1fr) auto;
      gap: 0.75rem;
      align-items: center;
      padding: 0.85rem;
      border-bottom: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .chat-header img {
      width: 48px;
      height: 48px;
      border-radius: 8px;
      object-fit: cover;
      background: #f8edf7;
    }

    .chat-header h2 {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 0.95rem;
      font-weight: 950;
    }

    .chat-header p {
      margin: 0.2rem 0 0;
      overflow: hidden;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 800;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .chat-close {
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

    .chat-notice {
      margin: 0;
      padding: 0.75rem 0.85rem;
      border-bottom: 1px solid var(--market-line);
      background: #fff6fb;
      color: #774163;
      font-size: 0.8rem;
      font-weight: 750;
      line-height: 1.45;
    }

    .chat-messages {
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
      min-height: 220px;
      overflow-y: auto;
      padding: 0.85rem;
      background: #fff;
    }

    .chat-empty {
      margin: auto;
      color: var(--market-muted);
      font-weight: 850;
      text-align: center;
    }

    .chat-message {
      max-width: 86%;
      align-self: flex-start;
    }

    .chat-message.mine {
      align-self: flex-end;
    }

    .chat-message span {
      display: block;
      margin: 0 0 0.25rem;
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 850;
    }

    .chat-message.mine span {
      text-align: right;
    }

    .chat-message p {
      margin: 0;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff8fc;
      color: var(--market-ink);
      overflow-wrap: anywhere;
      padding: 0.65rem 0.75rem;
      white-space: pre-wrap;
      line-height: 1.45;
      font-weight: 750;
    }

    .chat-message.mine p {
      border-color: rgba(190, 47, 118, 0.28);
      background: var(--market-accent-dark);
      color: #fff;
    }

    .chat-composer {
      display: flex;
      flex-direction: column;
      gap: 0.55rem;
      padding: 0.85rem;
      border-top: 1px solid var(--market-line);
      background: #fff8fc;
    }

    .chat-composer textarea {
      width: 100%;
      resize: none;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      color: var(--market-ink);
      font: inherit;
      line-height: 1.45;
      padding: 0.7rem;
    }

    .chat-composer textarea:focus {
      outline: 2px solid rgba(190, 47, 118, 0.24);
      outline-offset: 1px;
    }

    .chat-compose-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
    }

    .chat-compose-actions span {
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 800;
    }

    .chat-compose-actions button {
      min-width: 86px;
      min-height: 38px;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-weight: 900;
      padding: 0 0.9rem;
    }

    .chat-compose-actions button:disabled {
      cursor: not-allowed;
      opacity: 0.62;
    }

    .description-panel {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 1rem;
      padding: 1.15rem;
      background:
        linear-gradient(180deg, rgba(255, 255, 255, 0.95), rgba(255, 248, 252, 0.92)),
        #fff;
    }

    .description-panel div {
      display: flex;
      flex-direction: column;
      gap: 0.45rem;
    }

    .description-panel p,
    .error-state p {
      margin: 0;
      color: var(--market-muted);
      line-height: 1.6;
      white-space: pre-wrap;
    }

    .empty-state,
    .error-state {
      padding: 2rem;
      color: var(--market-muted);
    }

    .error-state {
      display: flex;
      flex-direction: column;
      gap: 0.8rem;
    }

    .error-state a {
      color: var(--market-accent-dark);
      font-weight: 850;
      text-decoration: none;
    }

    @media (max-width: 980px) {
      .detail-grid {
        grid-template-columns: 1fr;
      }

      .detail-aside {
        position: static;
      }

    }

    @media (max-width: 640px) {
      .facts {
        grid-template-columns: 1fr;
      }

      .engagement-row,
      .facts {
        grid-template-columns: 1fr;
      }

      .facts div,
      .facts div:nth-child(2n),
      .facts div:nth-last-child(-n + 2) {
        border-right: 0;
        border-bottom: 1px solid var(--market-line);
      }

      .facts div:last-child {
        border-bottom: 0;
      }

      .badge-row,
      .price-line {
        align-items: flex-start;
        flex-direction: column;
      }

      .chat-drawer {
        right: 0;
        bottom: 0;
        width: 100vw;
        max-height: min(720px, 92vh);
        border-radius: 8px 8px 0 0;
      }

    }
  `],
})
export class PublicListingDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly listingService = inject(ListingService);
  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);

  listing = signal<PublicListing | null>(null);
  loading = signal(false);
  errorMsg = signal('');
  startingConversation = signal(false);
  conversationError = signal('');
  selfChatBlocked = signal(false);
  chatOpen = signal(false);
  conversation = signal<ConversationSummary | null>(null);
  messages = signal<ChatMessage[]>([]);
  loadingMessages = signal(false);
  sendingMessage = signal(false);
  messageDraft = signal('');
  visitCount = signal(0);
  likeCount = signal(0);
  likedByMe = signal(false);
  engagementLoading = signal(false);
  engagementError = signal('');

  ngOnInit(): void {
    const listingId = this.route.snapshot.paramMap.get('listingId') || '';
    if (!listingId) {
      this.errorMsg.set('This listing could not be found.');
      return;
    }

    this.loading.set(true);
    this.listingService.getPublicListing(listingId).subscribe({
      next: listing => {
        this.listing.set(listing);
        this.applyListingCounts(listing);
        this.selfChatBlocked.set(false);
        this.loading.set(false);
        this.recordVisitIfAuthenticated(listing.id);
      },
      error: () => {
        this.loading.set(false);
        this.errorMsg.set('This listing is not available.');
      },
    });
  }

  conditionLabel(condition: string): string {
    return publicListingConditionLabel(condition);
  }

  locationLabel(listing: PublicListing | null): string {
    return publicListingLocationLabel(listing, 'Not set');
  }

  ownerLabel(listing: PublicListing | null): string {
    return publicListingOwnerLabel(listing);
  }

  viewCountLabel(): string {
    const count = this.visitCount();
    return `👁 ${count} ${count === 1 ? 'view' : 'views'}`;
  }

  likeCountLabel(): string {
    const count = this.likeCount();
    return `♡ ${count} ${count === 1 ? 'like' : 'likes'}`;
  }

  sellerProfile(): ProfileCardUser | null {
    const listing = this.listing();
    if (!listing) {
      return null;
    }
    return {
      displayName: this.ownerLabel(listing),
      email: null,
      avatarUrl: listing.sellerAvatarUrl || null,
    };
  }

  marketplaceNotice(listing: PublicListing | null): string {
    if (!listing) {
      return '';
    }
    if (listing.sellerType === 'BUSINESS') {
      return 'Business listings are view-only in the MVP. Purchase flows are not available yet.';
    }
    return 'Payment and delivery are arranged directly between users. Meet in public and avoid sharing private addresses.';
  }

  messageButtonLabel(): string {
    if (this.startingConversation()) {
      return 'Opening conversation...';
    }
    return this.authService.isAuthenticated() ? 'Message seller' : 'Sign in to message seller';
  }

  likeButtonLabel(): string {
    if (this.engagementLoading()) {
      return 'Saving...';
    }
    return this.likeCountLabel();
  }

  toggleLike(): void {
    const listing = this.listing();
    if (!listing || this.engagementLoading()) {
      return;
    }
    this.engagementError.set('');
    if (!this.authService.isAuthenticated()) {
      this.authService.login('marketplace', this.router.url);
      return;
    }
    this.engagementLoading.set(true);
    const request = this.likedByMe()
      ? this.listingService.unlikeListing(listing.id)
      : this.listingService.likeListing(listing.id);
    request.pipe(finalize(() => this.engagementLoading.set(false))).subscribe({
      next: engagement => this.applyEngagement(engagement),
      error: () => this.engagementError.set('Listing like could not be updated.'),
    });
  }

  messageSeller(): void {
    const listing = this.listing();
    if (!listing || listing.sellerType !== 'INDIVIDUAL') {
      return;
    }
    this.conversationError.set('');
    if (!this.authService.isAuthenticated()) {
      this.authService.login('marketplace', this.router.url);
      return;
    }

    this.startingConversation.set(true);
    this.chatService.startListingConversation(listing.id)
      .pipe(finalize(() => this.startingConversation.set(false)))
      .subscribe({
        next: conversation => {
          this.conversation.set(conversation);
          this.chatOpen.set(true);
          this.loadMessages(conversation.id);
        },
        error: error => {
          if (this.conversationErrorCode(error) === 'CHAT_SELF_CONVERSATION_NOT_ALLOWED') {
            this.selfChatBlocked.set(true);
            this.conversationError.set('');
            return;
          }
          this.conversationError.set(this.conversationErrorMessage(error));
        },
      });
  }

  closeChat(): void {
    this.chatOpen.set(false);
  }

  sendChatMessage(): void {
    const conversation = this.conversation();
    const body = this.messageDraft().trim();
    if (!conversation || !body || this.sendingMessage()) {
      return;
    }
    this.conversationError.set('');
    this.sendingMessage.set(true);
    this.chatService.sendMessage(conversation.id, body)
      .pipe(finalize(() => this.sendingMessage.set(false)))
      .subscribe({
        next: message => {
          this.messages.update(messages => [...messages, message]);
          this.messageDraft.set('');
        },
        error: error => {
          this.conversationError.set(this.conversationErrorMessage(error));
        },
      });
  }

  otherParticipantLabel(conversation: ConversationSummary | null): string {
    const participant = conversation?.participants.find(item => !item.currentUser);
    return participant?.displayName || 'Marketplace seller';
  }

  senderLabel(message: ChatMessage): string {
    if (message.currentUser) {
      return 'You';
    }
    const participant = this.conversation()?.participants.find(item => item.participantId === message.senderUserId);
    return participantLabel(participant);
  }

  private loadMessages(conversationId: string): void {
    this.loadingMessages.set(true);
    this.messages.set([]);
    this.chatService.getMessages(conversationId)
      .pipe(finalize(() => this.loadingMessages.set(false)))
      .subscribe({
        next: page => this.messages.set(page.items),
        error: error => {
          this.conversationError.set(this.conversationErrorMessage(error));
        },
      });
  }

  private applyListingCounts(listing: PublicListing): void {
    this.visitCount.set(listing.visitCount || 0);
    this.likeCount.set(listing.likeCount || 0);
    this.likedByMe.set(false);
  }

  private recordVisitIfAuthenticated(listingId: string): void {
    if (!this.authService.isAuthenticated()) {
      return;
    }
    this.listingService.recordListingVisit(listingId).subscribe({
      next: engagement => this.applyEngagement(engagement),
      error: () => {
        // Visit tracking is best-effort and should not block public listing detail.
      },
    });
  }

  private applyEngagement(engagement: ListingEngagement): void {
    this.visitCount.set(engagement.visitCount);
    this.likeCount.set(engagement.likeCount);
    this.likedByMe.set(engagement.likedByMe);
  }

  private conversationErrorMessage(error: { status?: number; error?: { error?: { code?: string } } }): string {
    const code = this.conversationErrorCode(error);
    if (code === 'CHAT_SELF_CONVERSATION_NOT_ALLOWED') {
      return 'This is your listing.';
    }
    if (error?.status === 401 || error?.status === 403) {
      return 'Please sign in to message this seller.';
    }
    if (error?.status === 404) {
      return 'This listing is not available for chat.';
    }
    return 'Chat is temporarily unavailable.';
  }

  private conversationErrorCode(error: { error?: { error?: { code?: string } } }): string | undefined {
    return error?.error?.error?.code;
  }

}

function participantLabel(participant: ChatParticipantSummary | undefined): string {
  return participant?.displayName || 'Marketplace user';
}
