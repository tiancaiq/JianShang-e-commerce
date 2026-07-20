import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ConversationListItem } from '../../core/models/chat.model';
import { IndividualSellerProfile } from '../../core/models/individual-seller.model';
import { ListingCondition, ListingDraft } from '../../core/models/listing.model';
import { CurrentUser } from '../../core/models/user.model';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { ListingService } from '../../core/services/listing.service';
import { UserProfileService } from '../../core/services/user-profile.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-account',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="account-page">
      <div class="account-container">
        <div class="top-grid">
          <header class="welcome-card">
            <img class="welcome-illustration" src="/marketplace/brand-mascot.png" alt="" />
            <div class="welcome-content">
              <p class="eyebrow">Marketplace account</p>
              <h1>{{ greeting() }}</h1>
              <p class="email-line">{{ accountEmail() }}</p>

              <div class="status-row" aria-label="Account summary">
                <span>
                  <svg viewBox="0 0 24 24"><path d="M12 3 20 6v5.8c0 4.8-3.4 8-8 9.2-4.6-1.2-8-4.4-8-9.2V6l8-3zm3.5 6.8-4.4 4.4-2-2-1.4 1.4 3.4 3.4 5.8-5.8-1.4-1.4z"/></svg>
                  {{ sellerProfileLabel() }}
                </span>
                <span>
                  <svg viewBox="0 0 24 24"><path d="M20.6 13.2 12 21.8 3.4 13.2 12 4.6l8.6 8.6z"/></svg>
                  {{ activeListingCount() }} active listings
                </span>
                <span>
                  <svg viewBox="0 0 24 24"><path d="M5 5h14v10H8.8L5 18.5V5z"/></svg>
                  {{ unreadCount() }} unread messages
                </span>
              </div>

              <div class="welcome-actions">
                <a routerLink="/account/listings/new" class="primary-action">
                  <svg viewBox="0 0 24 24"><path d="M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6V5z"/></svg>
                  Sell an Item
                </a>
                <a routerLink="/account/profile" class="secondary-action">
                  <svg viewBox="0 0 24 24"><path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 8a7 7 0 0 1 14 0H5z"/></svg>
                  Edit profile
                </a>
              </div>
            </div>
          </header>

          <aside class="profile-card" aria-label="Profile summary">
            <div class="profile-main">
              <span class="avatar-ring">
                <span class="avatar-image-frame">
                  @if (avatarUrl()) {
                    <img [src]="avatarUrl() || ''" [alt]="displayName() + ' avatar'" (error)="avatarLoadFailed.set(true)">
                  } @else {
                    <span class="avatar-fallback">{{ initials() }}</span>
                  }
                </span>
                <span class="seller-star" aria-hidden="true">
                  <svg viewBox="0 0 24 24"><path d="m12 3 2.3 4.7 5.2.8-3.8 3.7.9 5.2L12 15l-4.6 2.4.9-5.2-3.8-3.7 5.2-.8L12 3z"/></svg>
                </span>
              </span>

              <div class="profile-copy">
                <h2>{{ displayName() }}</h2>
                <p>{{ handle() }}</p>
                <span>{{ sellerBadgeLabel() }}</span>
              </div>
            </div>

            <dl class="profile-stats">
              <div>
                <dt>Listings</dt>
                <dd>{{ totalListingCount() }}</dd>
              </div>
              <div>
                <dt>Trades</dt>
                <dd>{{ completedTradesCount() }}</dd>
              </div>
              <div>
                <dt>Rating</dt>
                <dd>{{ ratingLabel() }}</dd>
              </div>
            </dl>
          </aside>
        </div>

        <div class="middle-grid">
          <article class="panel listings-panel">
            <div class="panel-heading">
              <h2>
                <svg viewBox="0 0 24 24"><path d="M20.6 13.2 12 21.8 3.4 13.2 12 4.6l8.6 8.6z"/></svg>
                Active Listings
              </h2>
              <a routerLink="/account/listings">Manage</a>
            </div>

            @if (loadingListings()) {
              <div class="skeleton-list" aria-label="Loading listings">
                <span></span>
                <span></span>
              </div>
            } @else if (listingsError()) {
              <p class="panel-error">{{ listingsError() }}</p>
            } @else if (listingPreview().length === 0) {
              <div class="panel-empty">
                <strong>No listings yet</strong>
                <a routerLink="/account/listings/new">+ Sell an Item</a>
              </div>
            } @else {
              <div class="listing-list">
                @for (listing of listingPreview(); track listing.id; let i = $index) {
                  <a routerLink="/account/listings" class="listing-row">
                    @if (listingImage(listing)) {
                      <img [src]="listingImage(listing) || ''" alt="" />
                    } @else {
                      <span class="listing-image-fallback">{{ listingInitial(listing) }}</span>
                    }
                    <span class="listing-copy">
                      <span>
                        <b>{{ listing.title }}</b>
                        <i class="badge" [class.new]="listing.condition === 'NEW'" [class.hot]="listing.condition !== 'NEW'">
                          {{ conditionLabel(listing.condition) }}
                        </i>
                      </span>
                      <small [class.completed-status]="listing.status === 'CLOSED'">{{ listingSubtitle(listing) }}</small>
                      <strong>{{ priceLabel(listing) }}</strong>
                    </span>
                    <svg class="row-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6V6z"/></svg>
                  </a>
                }
              </div>
            }

            <a routerLink="/account/listings" class="panel-footer">View all listings</a>
          </article>

          <article class="panel messages-panel">
            <div class="panel-heading">
              <h2>
                <svg viewBox="0 0 24 24"><path d="M5 5h14v10H8.8L5 18.5V5z"/></svg>
                Messages
              </h2>
              <span class="count-pill">{{ unreadCount() }}</span>
              <a routerLink="/account/messages">View all</a>
            </div>

            @if (loadingConversations()) {
              <div class="skeleton-list" aria-label="Loading messages">
                <span></span>
                <span></span>
              </div>
            } @else if (conversationsError()) {
              <p class="panel-error">{{ conversationsError() }}</p>
            } @else if (conversationPreview().length === 0) {
              <p class="panel-empty">No messages yet.</p>
            } @else {
              <div class="message-list">
                @for (conversation of conversationPreview(); track conversation.id) {
                  <a routerLink="/account/messages" class="message-row" [class.unread]="conversation.unread">
                    @if (conversationAvatar(conversation)) {
                      <img [src]="conversationAvatar(conversation) || ''" alt="" />
                    } @else {
                      <span class="message-avatar-fallback">{{ conversation.otherParticipant.initials }}</span>
                    }
                    <span>
                      <b>{{ conversation.otherParticipant.displayName }}</b>
                      <small>{{ conversationPreviewText(conversation) }}</small>
                    </span>
                    <i>{{ timeAgo(conversation.lastMessageAt || conversation.updatedAt) }}</i>
                  </a>
                }
              </div>
            }

            <a routerLink="/account/messages" class="message-button">
              <svg viewBox="0 0 24 24"><path d="M5 5h14v10H8.8L5 18.5V5z"/></svg>
              Go to messages
            </a>
          </article>

          <article class="panel trade-panel">
            <div class="panel-heading">
              <h2>
                <svg viewBox="0 0 24 24"><path d="M8 7h8l-2.2-2.2L15 3.6 19.4 8 15 12.4l-1.2-1.2L16 9H8V7zm8 10H8l2.2 2.2L9 20.4 4.6 16 9 11.6l1.2 1.2L8 15h8v2z"/></svg>
                Trade Overview
              </h2>
            </div>

            <dl class="trade-stats">
              <div>
                <dt>Listings</dt>
                <dd>{{ totalListingCount() }}</dd>
              </div>
              <div>
                <dt>Conversations</dt>
                <dd>{{ conversationCount() }}</dd>
              </div>
              <div>
                <dt>Rating</dt>
                <dd>{{ ratingLabel() }}</dd>
              </div>
            </dl>

            <div class="trade-note">
              <svg viewBox="0 0 24 24"><path d="M12 3 20 6v5.8c0 4.8-3.4 8-8 9.2-4.6-1.2-8-4.4-8-9.2V6l8-3z"/></svg>
              <p>Great start! Keep listing items and engage with the community.</p>
              <img src="/marketplace/help-mascot.png" alt="" />
            </div>

            <span class="panel-footer muted-link">View trade history</span>
          </article>
        </div>

        <div class="lower-grid">
          <a routerLink="/account/profile" class="account-tile">
            <span class="tile-icon pink">
              <svg viewBox="0 0 24 24"><path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 8a7 7 0 0 1 14 0H5z"/></svg>
            </span>
            <span>
              <b>Account details</b>
              <small>Update your display name, email, and contact info.</small>
            </span>
            <svg class="row-arrow" viewBox="0 0 24 24"><path d="m9 6 6 6-6 6V6z"/></svg>
          </a>

          <a routerLink="/account/seller-profile" class="account-tile">
            <span class="tile-icon purple">
              <svg viewBox="0 0 24 24"><path d="M4 9h16v11H4V9zm2 2v7h12v-7H6zm1-7h10l2 4H5l2-4z"/></svg>
            </span>
            <span>
              <b>Seller profile</b>
              <small>Manage your public seller profile and storefront.</small>
            </span>
            <svg class="row-arrow" viewBox="0 0 24 24"><path d="m9 6 6 6-6 6V6z"/></svg>
          </a>

          @if (buyerAddressesEnabled) {
            <a routerLink="/account/addresses" class="account-tile">
              <span class="tile-icon purple">
                <svg viewBox="0 0 24 24"><path d="M12 2a7 7 0 0 0-7 7c0 5.1 7 13 7 13s7-7.9 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6a2.5 2.5 0 0 1 0 5.5z"/></svg>
              </span>
              <span>
                <b>Addresses</b>
                <small>Manage saved delivery addresses.</small>
              </span>
              <svg class="row-arrow" viewBox="0 0 24 24"><path d="m9 6 6 6-6 6V6z"/></svg>
            </a>
          }

          <a routerLink="/account/liked" class="account-tile">
            <span class="tile-icon pink">
              <svg viewBox="0 0 24 24"><path d="M12 20.4 5.4 14C2 10.8 3.8 5 8.4 5c1.5 0 2.8.7 3.6 1.8C12.8 5.7 14.1 5 15.6 5 20.2 5 22 10.8 18.6 14L12 20.4z"/></svg>
            </span>
            <span>
              <b>Liked Listings</b>
              <small>View items you've liked and saved for later.</small>
            </span>
            <svg class="row-arrow" viewBox="0 0 24 24"><path d="m9 6 6 6-6 6V6z"/></svg>
          </a>
        </div>
      </div>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background:
        radial-gradient(circle at 10% 8%, rgba(236, 79, 163, 0.13), transparent 28%),
        radial-gradient(circle at 93% 2%, rgba(139, 92, 246, 0.14), transparent 26%),
        linear-gradient(135deg, #fff7fc 0%, #f8efff 48%, #fff6fb 100%);
    }

    :host::before {
      content: '';
      position: fixed;
      inset: 0;
      pointer-events: none;
      opacity: 0.2;
      background-image:
        radial-gradient(circle, rgba(236, 79, 163, 0.18) 0 1px, transparent 1.5px),
        linear-gradient(45deg, rgba(139, 92, 246, 0.08) 25%, transparent 25%);
      background-size: 34px 34px, 42px 42px;
    }

    .account-page {
      position: relative;
      z-index: 1;
      color: #38244f;
      font-size: 15px;
    }

    .account-nav,
    .welcome-card,
    .profile-card,
    .panel,
    .account-tile {
      background: rgba(255, 255, 255, 0.88);
      border: 1px solid rgba(190, 120, 230, 0.25);
      box-shadow: 0 14px 34px rgba(126, 77, 155, 0.1);
      backdrop-filter: blur(12px);
    }

    .account-nav {
      min-height: 60px;
      display: grid;
      grid-template-columns: max-content minmax(240px, 1fr) max-content;
      align-items: center;
      gap: 22px;
      padding: 6px max(28px, calc((100vw - 1280px) / 2 + 24px));
      background: rgba(255, 255, 255, 0.9);
      border-width: 0 0 1px;
      border-radius: 0;
      position: sticky;
      top: 0;
      z-index: 10;
    }

    .brand-mark,
    .nav-tab,
    .primary-action,
    .secondary-action,
    .listing-row,
    .message-row,
    .message-button,
    .panel-footer,
    .account-tile {
      text-decoration: none;
    }

    svg {
      width: 1em;
      height: 1em;
      fill: currentColor;
      flex: 0 0 auto;
    }

    .brand-mark {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      color: #38244f;
      font-size: 1.25rem;
      font-weight: 950;
      white-space: nowrap;
    }

    .brand-mark span span {
      color: #ec4fa3;
    }

    .brand-mark i {
      color: #ec4fa3;
      font-size: 0.58rem;
      font-style: normal;
      font-weight: 950;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    .brand-icon {
      width: 38px;
      height: 38px;
      display: grid;
      place-items: center;
      border-radius: 12px;
      color: #fff;
      font-size: 1.12rem;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
    }

    .nav-search {
      display: grid;
      grid-template-columns: minmax(220px, 1fr) auto;
      align-items: center;
      gap: 8px;
      min-width: 0;
    }

    .nav-search input {
      width: 100%;
      min-height: 44px;
      padding: 0 18px;
      border: 1px solid rgba(190, 120, 230, 0.26);
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.84);
      color: #38244f;
      font: inherit;
      font-weight: 850;
      outline: none;
    }

    .nav-search input::placeholder {
      color: #9b8aaf;
    }

    .nav-search input:focus {
      border-color: rgba(236, 79, 163, 0.58);
      box-shadow: 0 0 0 3px rgba(236, 79, 163, 0.13);
    }

    .nav-search button,
    .sell-pill {
      border: 0;
      border-radius: 999px;
      background: linear-gradient(135deg, #ec6bb6, #9b63e8);
      color: #fff;
      font: inherit;
      font-weight: 950;
      box-shadow: 0 10px 22px rgba(139, 92, 246, 0.18);
    }

    .nav-search button {
      min-height: 44px;
      padding: 0 20px;
      cursor: pointer;
    }

    .nav-links {
      display: inline-flex;
      align-items: center;
      justify-content: flex-end;
      gap: clamp(10px, 1.4vw, 22px);
      min-width: 0;
    }

    .nav-links a,
    .nav-links button {
      color: #79658d;
      border: 0;
      background: transparent;
      font: inherit;
      font-weight: 850;
      white-space: nowrap;
      text-decoration: none;
      cursor: pointer;
    }

    .avatar-crop {
      object-position: 12% 14%;
    }

    .nav-links .sell-pill {
      min-height: 42px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 18px;
      color: #fff;
    }

    .nav-links .active {
      color: #38244f;
    }

    .account-container {
      width: min(100% - 48px, 1280px);
      margin: 0 auto;
      padding: 32px 0 40px;
      display: grid;
      gap: 24px;
    }

    .top-grid {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(360px, 2fr);
      gap: 24px;
    }

    .middle-grid,
    .lower-grid {
      display: grid;
      gap: 24px;
    }

    .middle-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    .lower-grid {
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }

    .welcome-card,
    .profile-card,
    .panel,
    .account-tile {
      border-radius: 22px;
    }

    .welcome-card,
    .profile-card {
      min-height: 280px;
    }

    .welcome-card {
      position: relative;
      overflow: hidden;
      display: grid;
      align-items: end;
      padding: 36px 38px;
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(255, 245, 251, 0.86));
    }

    .welcome-illustration {
      position: absolute;
      right: -18px;
      top: 0;
      width: 47%;
      height: 100%;
      object-fit: cover;
      object-position: 0% 0%;
      opacity: 0.23;
      pointer-events: none;
    }

    .welcome-content {
      position: relative;
      z-index: 1;
      display: grid;
      gap: 18px;
      max-width: 620px;
    }

    .eyebrow,
    h1,
    h2,
    p,
    dl {
      margin: 0;
    }

    .eyebrow {
      color: #ec4fa3;
      font-size: 0.78rem;
      font-weight: 950;
      text-transform: uppercase;
    }

    h1 {
      color: #38244f;
      font-size: clamp(2.2rem, 4.4vw, 3rem);
      line-height: 0.98;
      letter-spacing: 0;
    }

    .email-line {
      margin-top: -10px;
      color: #7f6b94;
      font-size: 1rem;
      font-weight: 750;
    }

    .status-row {
      width: fit-content;
      max-width: 100%;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 14px;
      padding: 12px 14px;
      border: 1px solid rgba(190, 120, 230, 0.22);
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.74);
    }

    .status-row span {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      color: #6f5b84;
      font-weight: 850;
      white-space: nowrap;
    }

    .status-row span + span {
      position: relative;
    }

    .status-row span + span::before {
      content: '';
      width: 1px;
      height: 18px;
      margin-right: 6px;
      background: rgba(190, 120, 230, 0.24);
    }

    .status-row svg {
      color: #2aa887;
      font-size: 1.05rem;
    }

    .status-row span:last-child,
    .status-row span:last-child svg {
      color: #8b5cf6;
    }

    .welcome-actions {
      display: grid;
      grid-template-columns: minmax(220px, 280px) minmax(170px, 240px);
      gap: 18px;
      margin-top: 2px;
    }

    .primary-action,
    .secondary-action {
      min-height: 50px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 9px;
      border-radius: 12px;
      font-weight: 950;
    }

    .primary-action {
      color: #fff;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      box-shadow: 0 12px 26px rgba(139, 92, 246, 0.2);
    }

    .secondary-action {
      color: #8b5cf6;
      background: rgba(255, 255, 255, 0.78);
      border: 1px solid rgba(190, 120, 230, 0.26);
    }

    .profile-card {
      display: grid;
      align-content: center;
      gap: 26px;
      padding: 34px 40px;
    }

    .profile-main {
      display: grid;
      grid-template-columns: 124px minmax(0, 1fr);
      align-items: center;
      gap: 24px;
    }

    .avatar-ring {
      width: 118px;
      aspect-ratio: 1;
      position: relative;
      display: block;
      border-radius: 50%;
      padding: 4px;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      box-shadow: 0 16px 30px rgba(139, 92, 246, 0.16);
    }

    .avatar-image-frame {
      width: 100%;
      height: 100%;
      display: block;
      box-sizing: border-box;
      border-radius: 50%;
      overflow: hidden;
      background: #fff7fb;
      border: 4px solid #fff;
    }

    .avatar-image-frame img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
      object-position: center;
    }

    .avatar-fallback,
    .message-avatar-fallback {
      display: grid;
      place-items: center;
      border-radius: 50%;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      font-weight: 950;
    }

    .avatar-fallback {
      width: 100%;
      height: 100%;
      font-size: 2rem;
    }

    .seller-star {
      position: absolute;
      right: -6px;
      bottom: 12px;
      width: 38px;
      height: 38px;
      display: grid;
      place-items: center;
      border: 3px solid #fff;
      border-radius: 50%;
      background: #8b5cf6;
      color: #fff;
      font-size: 1rem;
    }

    .profile-copy h2 {
      color: #38244f;
      font-size: 1.55rem;
      line-height: 1.08;
    }

    .profile-copy p {
      margin: 7px 0 12px;
      color: #7e6a92;
      font-weight: 800;
    }

    .profile-copy span {
      display: inline-flex;
      padding: 8px 14px;
      border-radius: 11px;
      background: #f1dcff;
      color: #8b5cf6;
      font-weight: 950;
    }

    .profile-stats {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      border-top: 1px solid rgba(190, 120, 230, 0.22);
      padding-top: 22px;
    }

    .profile-stats div {
      display: grid;
      justify-items: center;
      gap: 8px;
      min-width: 0;
    }

    .profile-stats div + div {
      border-left: 1px solid rgba(190, 120, 230, 0.22);
    }

    dt {
      color: #855fa5;
      font-size: 0.86rem;
      font-weight: 850;
    }

    dd {
      margin: 0;
      color: #38244f;
      font-size: 1.65rem;
      font-weight: 950;
    }

    .profile-stats dd:last-child,
    .trade-stats dd:last-child {
      color: #8b5cf6;
      font-size: 1.35rem;
    }

    .panel {
      min-height: 330px;
      display: grid;
      align-content: start;
      gap: 16px;
      padding: 20px;
    }

    .panel-heading {
      display: flex;
      align-items: center;
      gap: 10px;
      min-height: 30px;
    }

    .panel-heading h2 {
      display: inline-flex;
      align-items: center;
      gap: 9px;
      margin-right: auto;
      color: #38244f;
      font-size: 1.08rem;
      line-height: 1.2;
    }

    .panel-heading h2 svg {
      color: #8b5cf6;
      font-size: 1.12rem;
    }

    .panel-heading a,
    .panel-footer,
    .muted-link {
      color: #8b5cf6;
      font-size: 0.86rem;
      font-weight: 950;
    }

    .listing-list,
    .message-list {
      display: grid;
      gap: 12px;
    }

    .listing-row,
    .message-row {
      display: grid;
      align-items: center;
      border: 1px solid rgba(190, 120, 230, 0.2);
      border-radius: 15px;
      background: rgba(255, 255, 255, 0.68);
      color: inherit;
    }

    .listing-row {
      grid-template-columns: 82px minmax(0, 1fr) 20px;
      gap: 12px;
      padding: 10px;
    }

    .listing-row img {
      width: 82px;
      height: 82px;
      border-radius: 13px;
      object-fit: cover;
    }

    .listing-image-fallback {
      width: 82px;
      height: 82px;
      display: grid;
      place-items: center;
      border-radius: 13px;
      background:
        radial-gradient(circle at 28% 24%, rgba(255, 255, 255, 0.72), transparent 22%),
        linear-gradient(135deg, #ffe1f2, #eadfff);
      color: #8b5cf6;
      font-size: 1.5rem;
      font-weight: 950;
      border: 1px solid rgba(190, 120, 230, 0.2);
    }

    .listing-copy {
      min-width: 0;
      display: grid;
      gap: 4px;
    }

    .listing-copy span {
      min-width: 0;
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: center;
      gap: 8px;
    }

    .listing-copy b,
    .message-row b,
    .account-tile b {
      color: #38244f;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .listing-copy small,
    .message-row small,
    .account-tile small,
    .trade-note p {
      color: #7f6b94;
      font-weight: 760;
      line-height: 1.35;
    }

    .listing-copy strong {
      color: #ec4fa3;
      font-size: 1rem;
    }

    .listing-copy small.completed-status {
      width: max-content;
      max-width: 100%;
      border: 1px solid rgba(42, 168, 135, 0.22);
      border-radius: 999px;
      background: rgba(42, 168, 135, 0.1);
      color: #19735f;
      font-weight: 950;
      padding: 0.16rem 0.48rem;
    }

    .badge {
      padding: 5px 8px;
      border-radius: 999px;
      font-size: 0.68rem;
      font-style: normal;
      font-weight: 950;
      text-transform: uppercase;
    }

    .badge.new {
      color: #ec4fa3;
      background: #ffe6f4;
    }

    .badge.hot {
      color: #8b5cf6;
      background: #efe4ff;
    }

    .row-arrow {
      color: #9a64e8;
    }

    .panel-footer {
      justify-self: center;
      align-self: end;
    }

    .count-pill {
      min-width: 26px;
      height: 24px;
      display: grid;
      place-items: center;
      border-radius: 999px;
      background: #ffe6f4;
      color: #ec4fa3;
      font-weight: 950;
    }

    .message-row {
      grid-template-columns: 52px minmax(0, 1fr) max-content;
      gap: 12px;
      padding: 13px;
    }

    .message-row img {
      width: 52px;
      height: 52px;
      border-radius: 50%;
      object-fit: cover;
    }

    .message-avatar-fallback {
      width: 52px;
      height: 52px;
      font-size: 0.95rem;
    }

    .message-row.unread {
      box-shadow: inset 4px 0 0 #ec4fa3;
    }

    .message-row span {
      min-width: 0;
      display: grid;
      gap: 4px;
    }

    .message-row i {
      align-self: start;
      color: #9b8aaf;
      font-size: 0.74rem;
      font-style: normal;
      font-weight: 850;
    }

    .message-button {
      min-height: 42px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      border: 1px solid rgba(190, 120, 230, 0.22);
      border-radius: 13px;
      color: #8b5cf6;
      font-weight: 950;
      background: rgba(255, 255, 255, 0.68);
      align-self: end;
    }

    .panel-empty,
    .panel-error {
      min-height: 172px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 10px;
      margin: 0;
      padding: 18px;
      border: 1px dashed rgba(190, 120, 230, 0.25);
      border-radius: 15px;
      color: #7f6b94;
      background: rgba(255, 255, 255, 0.54);
      text-align: center;
      font-weight: 850;
    }

    .panel-empty strong {
      color: #38244f;
    }

    .panel-empty a {
      min-height: 36px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 14px;
      border-radius: 999px;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      text-decoration: none;
      font-weight: 950;
    }

    .panel-error {
      color: #ad2f65;
      background: rgba(244, 63, 94, 0.08);
      border-color: rgba(244, 63, 94, 0.18);
    }

    .skeleton-list {
      display: grid;
      gap: 12px;
    }

    .skeleton-list span {
      min-height: 104px;
      border-radius: 15px;
      background: linear-gradient(90deg, rgba(255, 255, 255, 0.5), rgba(255, 230, 246, 0.78), rgba(255, 255, 255, 0.5));
      background-size: 220% 100%;
      animation: account-shimmer 1.2s linear infinite;
      border: 1px solid rgba(190, 120, 230, 0.16);
    }

    @keyframes account-shimmer {
      from {
        background-position: 100% 0;
      }

      to {
        background-position: -100% 0;
      }
    }

    .trade-stats {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
    }

    .trade-stats div {
      display: grid;
      justify-items: center;
      gap: 8px;
      min-height: 118px;
      align-content: center;
      border: 1px solid rgba(190, 120, 230, 0.2);
      border-radius: 15px;
      background: rgba(255, 255, 255, 0.68);
    }

    .trade-note {
      display: grid;
      grid-template-columns: 28px minmax(0, 1fr) 58px;
      align-items: center;
      gap: 12px;
      padding: 12px;
      border: 1px solid rgba(190, 120, 230, 0.18);
      border-radius: 15px;
      background: #fff8fc;
    }

    .trade-note svg {
      color: #2aa887;
      font-size: 1.4rem;
    }

    .trade-note img {
      width: 58px;
      height: 48px;
      object-fit: cover;
      border-radius: 12px;
    }

    .muted-link {
      justify-self: center;
      align-self: end;
    }

    .account-tile {
      min-height: 118px;
      display: grid;
      grid-template-columns: 64px minmax(0, 1fr) 20px;
      align-items: center;
      gap: 18px;
      padding: 20px;
      color: inherit;
      box-shadow: 0 10px 24px rgba(126, 77, 155, 0.08);
    }

    .tile-icon {
      width: 62px;
      aspect-ratio: 1;
      display: grid;
      place-items: center;
      border-radius: 14px;
      color: #fff;
      font-size: 1.7rem;
    }

    .tile-icon.pink {
      background: linear-gradient(135deg, #ec4fa3, #ff7bc0);
    }

    .tile-icon.purple {
      background: linear-gradient(135deg, #8b5cf6, #c492ff);
    }

    .account-tile span:nth-child(2) {
      min-width: 0;
      display: grid;
      gap: 5px;
    }

    @media (max-width: 1120px) {
      .account-nav {
        grid-template-columns: 1fr;
      }

      .nav-links {
        justify-content: flex-start;
        overflow-x: auto;
        padding-bottom: 2px;
      }

     .top-grid,
      .middle-grid,
      .lower-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .profile-card,
      .trade-panel {
        grid-column: span 2;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .skeleton-list span {
        animation: none;
      }
    }

    @media (max-width: 760px) {
      .account-nav {
        padding: 10px 16px;
      }

      .account-container {
        width: min(100% - 28px, 1280px);
        padding: 18px 0 28px;
        gap: 18px;
      }

      .top-grid,
      .middle-grid,
      .lower-grid {
        grid-template-columns: 1fr;
        gap: 18px;
      }

      .profile-card,
      .trade-panel {
        grid-column: auto;
      }

      .welcome-card {
        min-height: auto;
        padding: 24px;
      }

      .welcome-illustration {
        width: 90%;
        opacity: 0.16;
      }

      .status-row,
      .status-row span {
        width: 100%;
      }

      .status-row span + span::before {
        display: none;
      }

      .welcome-actions,
      .profile-main,
      .profile-stats,
      .trade-stats,
      .account-tile {
        grid-template-columns: 1fr;
      }

      .profile-main {
        justify-items: center;
        text-align: center;
      }

      .profile-stats div + div {
        border-left: 0;
        border-top: 1px solid rgba(190, 120, 230, 0.22);
        padding-top: 14px;
      }

      .message-row {
        grid-template-columns: 44px minmax(0, 1fr);
      }

      .message-row i {
        grid-column: 2;
      }
    }
  `],
})
export class AccountComponent implements OnInit {
  authService = inject(AuthService);
  readonly buyerAddressesEnabled = environment.features.buyerAddresses;
  private readonly router = inject(Router);
  private readonly listingService = inject(ListingService);
  private readonly chatService = inject(ChatService);
  private readonly individualSellerService = inject(IndividualSellerService);
  private readonly userProfileService = inject(UserProfileService);

  currentUser = signal<CurrentUser | null>(null);
  sellerProfile = signal<IndividualSellerProfile | null>(null);
  listings = signal<ListingDraft[]>([]);
  conversations = signal<ConversationListItem[]>([]);
  avatarLoadFailed = signal(false);
  loadingSellerProfile = signal(false);
  loadingListings = signal(false);
  loadingConversations = signal(false);
  sellerProfileError = signal('');
  listingsError = signal('');
  conversationsError = signal('');

  activeListingCount = computed(() => this.listings().filter(listing => listing.status === 'ACTIVE').length);
  totalListingCount = computed(() => this.listings().length);
  conversationCount = computed(() => this.conversations().length);
  listingPreview = computed(() => this.listings()
    .filter(listing => ['ACTIVE', 'DRAFT', 'CLOSED'].includes(listing.status))
    .sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
    .slice(0, 2));
  conversationPreview = computed(() => this.conversations().slice(0, 2));
  unreadCount = computed(() => this.conversations().filter(conversation => conversation.unread).length);

  ngOnInit(): void {
    this.loadCurrentUser();
    this.loadSellerProfile();
    this.loadListings();
    this.loadConversations();
  }

  accountEmail(): string {
    return this.user()?.email || 'Email unavailable';
  }

  avatarUrl(): string | null {
    if (this.avatarLoadFailed()) {
      return null;
    }
    return this.displayAvatarUrl(this.user()?.avatarUrl || '');
  }

  greeting(): string {
    return `Welcome, ${this.displayName()}`;
  }

  displayName(): string {
    const user = this.user();
    return user?.displayName?.trim() || user?.email?.split('@')[0] || 'Account';
  }

  handle(): string {
    const user = this.user();
    const source = user?.email?.split('@')[0] || this.displayName();
    return `@${source.toLowerCase().replace(/[^a-z0-9]+/g, '') || 'account'}`;
  }

  initials(): string {
    const initials = this.displayName()
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map(part => part.charAt(0).toUpperCase())
      .join('');
    return initials || 'A';
  }

  sellerProfileLabel(): string {
    if (this.loadingSellerProfile()) {
      return 'Checking seller profile';
    }
    if (this.sellerProfileError()) {
      return this.sellerProfileError();
    }
    const profile = this.sellerProfile();
    if (!profile) {
      return 'Seller profile not active';
    }
    return profile.status === 'ACTIVE' ? 'Seller profile active' : `Seller profile ${profile.status.toLowerCase()}`;
  }

  completedTradesCount(): number {
    return this.sellerProfile()?.completedSalesCount || 0;
  }

  sellerBadgeLabel(): string {
    if (this.loadingSellerProfile()) {
      return 'Checking profile';
    }
    if (this.sellerProfileError()) {
      return 'Profile unavailable';
    }
    const profile = this.sellerProfile();
    if (!profile) {
      return 'Marketplace account';
    }
    return profile.status === 'ACTIVE' ? 'Marketplace seller' : `Seller ${profile.status.toLowerCase()}`;
  }

  ratingLabel(): string {
    return 'Not rated';
  }

  searchMarketplace(query: string): void {
    const q = query.trim();
    this.router.navigate(['/marketplace'], {
      queryParams: q ? { q } : {},
    });
  }

  listingImage(listing: ListingDraft): string | null {
    const image = listing.images?.[0];
    const url = this.listingService.mediaUrl(image?.url || image?.uploadUrl || '');
    return url || null;
  }

  listingInitial(listing: ListingDraft): string {
    return listing.title.trim().charAt(0).toUpperCase() || 'L';
  }

  conditionLabel(condition: ListingCondition): string {
    return condition
      .toLowerCase()
      .split('_')
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  listingSubtitle(listing: ListingDraft): string {
    if (listing.status === 'CLOSED') {
      return 'Completed / closed';
    }
    const status = this.titleCase(listing.status);
    const moderation = this.titleCase(listing.moderationStatus);
    return moderation && moderation !== status ? `${status} · ${moderation}` : status;
  }

  priceLabel(listing: ListingDraft): string {
    try {
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: listing.currency || 'USD',
        maximumFractionDigits: listing.priceAmount % 1 === 0 ? 0 : 2,
      }).format(listing.priceAmount);
    } catch {
      return `${listing.priceAmount} ${listing.currency}`;
    }
  }

  conversationAvatar(conversation: ConversationListItem): string | null {
    return conversation.otherParticipant.avatarUrl || null;
  }

  conversationPreviewText(conversation: ConversationListItem): string {
    return conversation.lastMessage?.body || `Chat about ${conversation.listing.title}`;
  }

  timeAgo(value: string | null): string {
    if (!value) {
      return '';
    }
    const timestamp = new Date(value).getTime();
    if (Number.isNaN(timestamp)) {
      return '';
    }
    const diffMs = Math.max(0, Date.now() - timestamp);
    const minutes = Math.floor(diffMs / 60000);
    if (minutes < 1) {
      return 'now';
    }
    if (minutes < 60) {
      return `${minutes}m ago`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
      return `${hours}h ago`;
    }
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  }

  private user(): CurrentUser | null {
    return this.currentUser() || this.authService.user();
  }

  private loadCurrentUser(): void {
    this.userProfileService.getMe().subscribe({
      next: user => {
        this.currentUser.set(user);
        this.avatarLoadFailed.set(false);
      },
      error: error => {
        if (error.status === 401) {
          this.router.navigate(['/login']);
        }
      },
    });
  }

  private loadSellerProfile(): void {
    this.loadingSellerProfile.set(true);
    this.sellerProfileError.set('');
    this.individualSellerService.getMe().subscribe({
      next: profile => {
        this.sellerProfile.set(profile);
        this.loadingSellerProfile.set(false);
      },
      error: error => {
        if (error.status === 401) {
          this.loadingSellerProfile.set(false);
          this.router.navigate(['/login']);
          return;
        }
        this.sellerProfile.set(null);
        if (error.status !== 404) {
          this.sellerProfileError.set('Seller profile unavailable');
        }
        this.loadingSellerProfile.set(false);
      },
    });
  }

  private loadListings(): void {
    this.loadingListings.set(true);
    this.listingsError.set('');
    this.listingService.getMyListings().subscribe({
      next: listings => {
        this.listings.set(listings);
        this.loadingListings.set(false);
      },
      error: () => {
        this.listings.set([]);
        this.listingsError.set('Listings could not be loaded.');
        this.loadingListings.set(false);
      },
    });
  }

  private loadConversations(): void {
    this.loadingConversations.set(true);
    this.conversationsError.set('');
    this.chatService.getConversations(null, 20).subscribe({
      next: page => {
        this.conversations.set(page.items);
        this.loadingConversations.set(false);
      },
      error: () => {
        this.conversations.set([]);
        this.conversationsError.set('Messages could not be loaded.');
        this.loadingConversations.set(false);
      },
    });
  }

  private titleCase(value: string): string {
    return value
      .toLowerCase()
      .split('_')
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  private displayAvatarUrl(value: string): string | null {
    const trimmed = value.trim();
    if (!trimmed) {
      return null;
    }
    if (trimmed.startsWith('/api/')) {
      return `${environment.apiGatewayUrl}${trimmed}`;
    }
    return trimmed;
  }
}
