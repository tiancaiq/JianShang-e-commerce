import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  Category,
  ListingCondition,
  MarketplaceBrowseListing,
  MarketplaceListingSearchParams,
  MarketplaceListingSort,
  PublicListing,
} from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import {
  publicListingConditionLabel,
  publicListingLocationLabel,
  publicListingOwnerLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';
import { AuthService } from '../../core/services/auth.service';
import { UserProfileCardComponent } from '../account/user-profile-card.component';

@Component({
  selector: 'app-marketplace-home',
  standalone: true,
  imports: [DecimalPipe, FormsModule, RouterLink, UserProfileCardComponent],
  template: `
    <section class="marketplace-home">
      <section class="hero-section" aria-labelledby="marketplace-title">
        <div class="hero-copy">
          <p class="eyebrow">MSB cute market</p>
          <h1 id="marketplace-title">Find sweet local treasures.</h1>
          <p class="summary">Browse approved individual listings with clear seller labels and public pickup areas.</p>

          <form class="hero-search" role="search" (submit)="runSearch(); $event.preventDefault()">
            <label>
              <span>Search marketplace</span>
              <input
                name="search"
                type="search"
                [(ngModel)]="searchTerm"
                placeholder="Search figures, books, bikes, decor..."
              />
            </label>
            <button type="submit">Search</button>
          </form>

          <div class="hero-actions">
            <a href="#listings" class="primary-link">Browse Listings</a>
            <a routerLink="/account/listings" class="secondary-link">My Listings</a>
          </div>
        </div>

        <div class="hero-preview" aria-label="Marketplace preview">
          <div class="preview-window">
            <div class="preview-bar">
              <span></span>
              <span></span>
              <span></span>
            </div>

            @if (featuredListing()) {
              <a class="featured-card" [routerLink]="['/listings', featuredListing()?.id]">
                <div class="featured-image">
                  @if (featuredListing()?.images?.[0]?.url || featuredListing()?.images?.[0]?.uploadUrl) {
                    <img [src]="imageUrl(featuredListing()!)" [alt]="featuredListing()?.images?.[0]?.altText || featuredListing()?.title || 'Featured listing'" />
                  } @else {
                    <span>{{ featuredListing()?.categoryName }}</span>
                  }
                </div>
                <div>
                  <span class="mini-label">Featured approved listing</span>
                  <strong>{{ featuredListing()?.title }}</strong>
                  <small>By {{ ownerLabel(featuredListing()) }}</small>
                  <p>{{ featuredListing()?.priceAmount | number: '1.2-2' }} {{ featuredListing()?.currency }}</p>
                </div>
              </a>
            } @else {
              <div class="featured-empty">
                <span>Approved listings will appear here.</span>
              </div>
            }

            <div class="preview-notes">
              <span>Individual trades stay off-platform</span>
              <span>Payment and delivery are arranged directly</span>
            </div>
          </div>
        </div>
      </section>

      <section id="listings" class="browse-section" aria-labelledby="browse-title">
        <main class="listing-area">
          <div class="browse-panel">
            <div class="browse-header">
              <div>
                <p class="eyebrow">Fresh finds</p>
                <h2 id="browse-title">Individual Marketplace</h2>
              </div>
              <div class="browse-tools">
                <span>{{ marketplaceListings().length }} shown</span>
                <label class="sort-control">
                  <span>Sort by</span>
                  <select name="sortMode" [(ngModel)]="sortMode" (ngModelChange)="runSearch()">
                    <option value="none">Default</option>
                    <option value="newest">Newest</option>
                    <option value="price_asc">Price low to high</option>
                    <option value="price_desc">Price high to low</option>
                  </select>
                </label>
              </div>
            </div>

            @if (loading()) {
              <div class="empty-list">Loading approved listings...</div>
            } @else if (errorMsg()) {
              <div class="empty-list">{{ errorMsg() }}</div>
            } @else if (marketplaceListings().length === 0) {
              <div class="empty-list">{{ hasActiveSearch() ? 'No listings match these filters.' : 'No approved individual listings yet.' }}</div>
            } @else {
              <div class="listing-grid">
                @for (listing of marketplaceListings(); track listing.id) {
                  <a class="listing-card" [routerLink]="['/listings', listing.id]">
                    <div class="listing-image">
                      @if (listing.images[0]?.url || listing.images[0]?.uploadUrl) {
                        <img [src]="imageUrl(listing)" [alt]="listing.images[0]?.altText || listing.title" />
                      } @else {
                        <span>{{ listing.categoryName }}</span>
                      }
                    </div>
                    <div class="listing-body">
                      <div class="listing-meta">
                        <span>{{ listing.categoryName }}</span>
                        <span>{{ conditionLabel(listing.condition) }}</span>
                      </div>
                      <h3>{{ listing.title }}</h3>
                      <p class="seller-name">By {{ ownerLabel(listing) }}</p>
                      <p>{{ locationLabel(listing) }}</p>
                      <div class="listing-price">
                        {{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}
                      </div>
                      <div class="card-foot">
                        <span>Off-platform trade</span>
                        <strong>Details</strong>
                      </div>
                    </div>
                  </a>
                }
              </div>
              @if (hasMore()) {
                <div class="load-more-row">
                  <button type="button" (click)="loadMore()" [disabled]="loadingMore()">
                    {{ loadingMore() ? 'Loading...' : 'Load more' }}
                  </button>
                </div>
              }
            }
          </div>
        </main>

        <aside class="marketplace-rail" aria-label="Marketplace account and filters">
          @if (authService.user(); as user) {
            <app-user-profile-card [user]="user" />
          }

          <section class="filter-panel" aria-label="Filter marketplace listings">
            <div class="filter-heading">
              <span>Filter by</span>
              @if (hasActiveSearch()) {
                <button type="button" class="clear-button" (click)="clearFilters()">Clear</button>
              }
            </div>

            <form class="filter-stack" (submit)="runSearch(); $event.preventDefault()">
              <label>
                <span>Condition</span>
                <select name="condition" [(ngModel)]="selectedCondition" (ngModelChange)="runSearch()">
                  <option value="ALL">Any condition</option>
                  <option value="NEW">New</option>
                  <option value="OPEN_BOX">Open box</option>
                  <option value="LIKE_NEW">Like new</option>
                  <option value="GOOD">Good</option>
                  <option value="FAIR">Fair</option>
                  <option value="FOR_PARTS">For parts</option>
                </select>
              </label>

              <div class="price-fields">
                <label>
                  <span>Min price</span>
                  <input name="minPrice" type="number" min="0" inputmode="decimal" [(ngModel)]="minPrice" />
                </label>

                <label>
                  <span>Max price</span>
                  <input name="maxPrice" type="number" min="0" inputmode="decimal" [(ngModel)]="maxPrice" />
                </label>
              </div>

              <label>
                <span>City</span>
                <input name="city" type="search" [(ngModel)]="city" placeholder="Irvine" />
              </label>

              <label>
                <span>County</span>
                <input name="county" type="search" [(ngModel)]="county" placeholder="Orange County" />
              </label>

              <button type="submit">Apply</button>
            </form>
          </section>
        </aside>
      </section>
    </section>
  `,
  styles: [`
    .marketplace-home {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
    }

    .hero-section {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(320px, 470px);
      gap: 1rem;
      align-items: stretch;
      min-height: 360px;
      padding: clamp(1rem, 4vw, 2rem);
      border: 1px solid rgba(234, 215, 242, 0.9);
      border-radius: 8px;
      background:
        linear-gradient(135deg, rgba(255, 240, 247, 0.94), rgba(241, 235, 255, 0.92)),
        var(--market-surface);
      box-shadow: 0 18px 44px rgba(159, 91, 144, 0.13);
      position: relative;
      overflow: hidden;
    }

    .hero-copy,
    .hero-preview {
      position: relative;
      z-index: 1;
    }

    .hero-copy {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1rem;
      max-width: 760px;
    }

    .eyebrow,
    .mini-label {
      margin: 0;
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 850;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1,
    h2,
    h3 {
      margin: 0;
      color: var(--market-ink);
      letter-spacing: 0;
    }

    h1 {
      max-width: 720px;
      font-size: clamp(2.5rem, 6vw, 5rem);
      line-height: 0.96;
      font-weight: 900;
      text-shadow: 0 2px 0 rgba(255, 255, 255, 0.9);
    }

    .summary {
      max-width: 560px;
      margin: 0;
      color: var(--market-muted);
      font-size: 1.05rem;
      font-weight: 650;
    }

    .hero-search {
      max-width: 760px;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.75rem;
      align-items: end;
    }

    .hero-search label,
    .filter-stack label,
    .sort-control {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .hero-search span,
    .filter-stack span,
    .sort-control span {
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 850;
    }

    input,
    select {
      width: 100%;
      min-height: 46px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
      color: var(--market-ink);
      padding: 0 0.9rem;
      outline: none;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
    }

    button {
      min-height: 46px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
      color: var(--market-accent-dark);
      padding: 0 1rem;
      cursor: pointer;
      font-weight: 900;
    }

    input:focus,
    select:focus,
    button:focus {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(244, 114, 182, 0.18);
    }

    .hero-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .primary-link,
    .secondary-link {
      min-height: 44px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 1rem;
      border-radius: 8px;
      font-weight: 900;
      text-decoration: none;
    }

    .primary-link {
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      box-shadow: 0 10px 22px rgba(190, 58, 131, 0.2);
    }

    .secondary-link {
      border: 1px solid var(--market-line);
      background: rgba(255, 255, 255, 0.86);
      color: var(--market-accent-dark);
    }

    .browse-section {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 280px;
      gap: 1rem;
      align-items: start;
    }

    .marketplace-rail {
      position: sticky;
      top: 92px;
      display: grid;
      gap: 1rem;
    }

    .filter-panel {
      display: flex;
      flex-direction: column;
      gap: 0.9rem;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 12px 26px rgba(143, 92, 144, 0.1);
    }

    .filter-heading {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--market-line);
      color: var(--market-ink);
      font-weight: 900;
    }

    .clear-button {
      min-height: 32px;
      padding: 0 0.65rem;
      font-size: 0.8rem;
    }

    .listing-area {
      min-width: 0;
    }

    .browse-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 12px 26px rgba(143, 92, 144, 0.1);
    }

    .browse-header {
      display: flex;
      justify-content: space-between;
      align-items: end;
      gap: 1rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--market-line);
    }

    .browse-tools {
      display: flex;
      align-items: end;
      justify-content: flex-end;
      gap: 0.75rem;
      min-width: min(100%, 360px);
    }

    .browse-header h2 {
      font-size: 1.5rem;
    }

    .browse-tools > span {
      color: var(--market-muted);
      font-size: 0.9rem;
      font-weight: 850;
      white-space: nowrap;
    }

    .sort-control {
      min-width: 190px;
    }

    .sort-control select {
      min-height: 40px;
    }

    .filter-stack {
      display: grid;
      gap: 0.75rem;
    }

    .price-fields {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.75rem;
    }

    .empty-list {
      min-height: 260px;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      border: 1px dashed rgba(244, 114, 182, 0.35);
      border-radius: 8px;
      background: #fff8fc;
      color: var(--market-muted);
      text-align: center;
      font-weight: 800;
    }

    .listing-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.9rem;
    }

    .load-more-row {
      display: flex;
      justify-content: center;
      padding-top: 0.25rem;
    }

    .load-more-row button {
      min-width: 160px;
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      border: 0;
      box-shadow: 0 10px 22px rgba(190, 58, 131, 0.16);
    }

    .load-more-row button:disabled {
      cursor: not-allowed;
      opacity: 0.68;
    }

    .listing-card {
      display: block;
      min-width: 0;
      overflow: hidden;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      color: inherit;
      text-decoration: none;
      box-shadow: 0 12px 26px rgba(143, 92, 144, 0.1);
    }

    .listing-image {
      position: relative;
      display: grid;
      place-items: center;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      background: linear-gradient(135deg, #ffe5f0, #efe8ff);
      color: var(--market-muted);
      font-weight: 900;
    }

    .listing-image img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
    }

    .listing-body {
      display: flex;
      flex-direction: column;
      gap: 0.45rem;
      padding: 0.8rem;
    }

    .listing-meta,
    .card-foot {
      display: flex;
      justify-content: space-between;
      gap: 0.75rem;
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 850;
    }

    .listing-meta span:first-child,
    .card-foot strong {
      color: var(--market-accent-dark);
    }

    .listing-body h3 {
      font-size: 1rem;
      line-height: 1.2;
    }

    .listing-body p {
      margin: 0;
      color: var(--market-muted);
      font-size: 0.88rem;
      font-weight: 750;
    }

    .featured-card small,
    .seller-name {
      color: var(--market-lavender);
      font-size: 0.82rem;
      font-weight: 900;
    }

    .listing-price {
      color: var(--market-ink);
      font-size: 1.05rem;
      font-weight: 950;
    }

    @media (max-width: 1180px) {
      .listing-grid {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }

    @media (max-width: 980px) {
      .hero-section,
      .browse-section {
        grid-template-columns: 1fr;
      }

      .filter-panel {
        position: static;
      }

      .marketplace-rail {
        position: static;
      }

      .listing-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 640px) {
      .hero-section,
      .browse-panel {
        padding: 0.85rem;
      }

      .preview-notes,
      .hero-search,
      .browse-tools,
      .price-fields,
      .listing-grid {
        grid-template-columns: 1fr;
      }

      .browse-tools {
        width: 100%;
        align-items: stretch;
        flex-direction: column;
      }

      .browse-header {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  `],
})
export class MarketplaceHomeComponent implements OnInit {
  private readonly listingService = inject(ListingService);
  readonly authService = inject(AuthService);

  listings = signal<PublicListing[]>([]);
  categories = signal<Category[]>([]);
  loading = signal(false);
  loadingMore = signal(false);
  hasMore = signal(false);
  errorMsg = signal('');
  nextCursor = signal<string | null>(null);

  searchTerm = '';
  selectedCategoryId = 'ALL';
  selectedCondition: ListingCondition | 'ALL' = 'ALL';
  sortMode: MarketplaceListingSort = 'none';
  minPrice: string | number = '';
  maxPrice: string | number = '';
  city = '';
  county = '';

  ngOnInit(): void {
    this.listingService.getCategories().subscribe({
      next: categories => this.categories.set(categories),
      error: () => this.categories.set([]),
    });
    this.runSearch();
  }

  runSearch(): void {
    this.loading.set(true);
    this.loadingMore.set(false);
    this.errorMsg.set('');
    this.nextCursor.set(null);
    this.listingService.searchMarketplaceListings(this.searchParams()).subscribe({
      next: response => {
        this.listings.set(response.data);
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(this.publicListingLoadMessage(error));
        this.loading.set(false);
      },
    });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loading() || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.errorMsg.set('');
    this.listingService.searchMarketplaceListings(this.searchParams(cursor)).subscribe({
      next: response => {
        this.listings.set([...this.listings(), ...response.data]);
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loadingMore.set(false);
      },
      error: error => {
        this.errorMsg.set(this.publicListingLoadMessage(error));
        this.loadingMore.set(false);
      },
    });
  }

  categoryOptions(): Category[] {
    return [...this.categories()].sort((left, right) => {
      const displayOrder = left.displayOrder - right.displayOrder;
      return displayOrder || left.name.localeCompare(right.name);
    });
  }

  selectCategory(categoryId: string): void {
    this.selectedCategoryId = categoryId;
    this.runSearch();
  }

  clearFilters(): void {
    this.searchTerm = '';
    this.selectedCategoryId = 'ALL';
    this.selectedCondition = 'ALL';
    this.sortMode = 'none';
    this.minPrice = '';
    this.maxPrice = '';
    this.city = '';
    this.county = '';
    this.runSearch();
  }

  featuredListing(): MarketplaceBrowseListing | null {
    return this.marketplaceListings()[0] || null;
  }

  locationLabel(listing: MarketplaceBrowseListing): string {
    return publicListingLocationLabel(listing);
  }

  marketplaceListings(): MarketplaceBrowseListing[] {
    return this.listings().filter(listing => listing.sellerType === 'INDIVIDUAL');
  }

  hasActiveSearch(): boolean {
    return Boolean(
      this.searchTerm.trim()
      || this.selectedCategoryId !== 'ALL'
      || this.selectedCondition !== 'ALL'
      || this.hasNumberInput(this.minPrice)
      || this.hasNumberInput(this.maxPrice)
      || this.city.trim()
      || this.county.trim()
      || this.sortMode !== 'none',
    );
  }

  ownerLabel(listing: MarketplaceBrowseListing | null | undefined): string {
    return publicListingOwnerLabel(listing);
  }

  conditionLabel(condition: ListingCondition): string {
    return publicListingConditionLabel(condition);
  }

  imageUrl(listing: MarketplaceBrowseListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  private publicListingLoadMessage(error: { status?: number }): string {
    return error.status
      ? `Approved listings could not be loaded. HTTP ${error.status}.`
      : 'Approved listings could not be loaded.';
  }

  private searchParams(cursor: string | null = null): MarketplaceListingSearchParams {
    return {
      q: this.searchTerm,
      categoryId: this.selectedCategoryId === 'ALL' ? null : this.selectedCategoryId,
      condition: this.selectedCondition === 'ALL' ? null : this.selectedCondition,
      minPrice: this.optionalNumber(this.minPrice),
      maxPrice: this.optionalNumber(this.maxPrice),
      city: this.city,
      county: this.county,
      sort: this.sortMode === 'none' ? null : this.sortMode,
      cursor,
    };
  }

  private optionalNumber(value: string | number | null | undefined): number | null {
    if (typeof value === 'number') {
      return Number.isFinite(value) ? value : null;
    }
    const text = String(value ?? '').trim();
    if (!text) {
      return null;
    }
    const parsed = Number(text);
    return Number.isFinite(parsed) ? parsed : null;
  }

  private hasNumberInput(value: string | number | null | undefined): boolean {
    return String(value ?? '').trim().length > 0;
  }
}
