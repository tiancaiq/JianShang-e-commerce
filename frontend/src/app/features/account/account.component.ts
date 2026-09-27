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
import { NOTIFICATION_CENTER_ENABLED } from './notification-center.capability';

@Component({
  selector: 'app-account',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './account.component.html',
  styleUrls: ['./account.component.css', './account-responsive.component.css'],
})
export class AccountComponent implements OnInit {
  authService = inject(AuthService);
  readonly buyerAddressesEnabled = environment.features.buyerAddresses;
  readonly buyerCheckoutEnabled = environment.features.buyerCheckout;
  readonly notificationsEnabled = inject(NOTIFICATION_CENTER_ENABLED);
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
  sellerActivationRequired = computed(() =>
    !this.loadingSellerProfile() && !this.sellerProfileError() && this.sellerProfile()?.status !== 'ACTIVE'
  );

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
    return `${Math.floor(hours / 24)}d ago`;
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
