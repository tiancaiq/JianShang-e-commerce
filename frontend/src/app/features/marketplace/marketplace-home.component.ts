import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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
import { BrandLoadingScreenComponent } from '../../shared/components/ui/brand-loading-screen.component';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';
import { MarketplaceFilterPanelComponent } from './components/marketplace-filter-panel.component';
import { MarketplaceHeroBannerComponent } from './components/marketplace-hero-banner.component';
import { MarketplaceProductCardComponent } from './components/marketplace-product-card.component';
import { MarketplaceSellerCardComponent } from './components/marketplace-seller-card.component';
import { MarketplaceSidebarComponent } from './components/marketplace-sidebar.component';
import { MarketplaceUiProduct } from './components/marketplace-ui.model';

@Component({
  selector: 'app-marketplace-home',
  standalone: true,
  imports: [
    BrandLoadingScreenComponent,
    BrandMascotComponent,
    FormsModule,
    MarketplaceFilterPanelComponent,
    MarketplaceHeroBannerComponent,
    MarketplaceProductCardComponent,
    MarketplaceSellerCardComponent,
    MarketplaceSidebarComponent,
    RouterLink,
  ],
  template: `
    <section class="marketplace-home">
      <app-marketplace-hero-banner
        [featuredProduct]="featuredProduct()"
        [authenticated]="isSignedIn()"
        (searchRequested)="runHeroSearch($event)"
        (loginRequested)="startHeroSellFlow()"
      />

      <section id="listings" class="browse-section" aria-labelledby="browse-title">
        <app-marketplace-sidebar
          [categories]="categoryOptions()"
          [selectedCategoryId]="selectedCategoryId"
          (categorySelected)="selectCategory($event)"
        />

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

            <div class="browse-tabs" role="list" aria-label="Marketplace browse shortcuts">
              <button type="button" [class.active]="!hasActiveSearch()" (click)="clearFilters()">All Items</button>
              <button type="button" [class.active]="sortMode === 'newest'" (click)="selectNewest()">New Arrivals</button>
              <button type="button" [class.active]="maxPrice === 50" (click)="selectBudgetFinds()">Under $50</button>
              <a routerLink="/account/listings">List an Item</a>
            </div>

            @if (loading()) {
              <app-brand-loading-screen
                label="Loading approved listings"
                detail="The marketplace mascot is arranging fresh local finds."
              />
            } @else if (errorMsg()) {
              <div class="empty-list">{{ errorMsg() }}</div>
            } @else if (marketplaceListings().length === 0) {
              <div class="empty-list cute-empty">
                <app-brand-mascot variant="badge" alt="MSB marketplace mascot empty state" />
                <div>
                  <strong>{{ hasActiveSearch() ? 'No sweet finds yet' : 'No approved individual listings yet' }}</strong>
                  <p>{{ hasActiveSearch() ? 'Try changing your filters or check back soon.' : 'Fresh local listings will appear here once approved.' }}</p>
                  @if (hasActiveSearch()) {
                    <button type="button" (click)="clearFilters()">Clear filters</button>
                  }
                </div>
              </div>
            } @else {
              <div class="listing-grid">
                @for (listing of marketplaceListings(); track listing.id) {
                  <app-marketplace-product-card
                    [product]="productFor(listing)"
                    [detailLink]="['/listings', listing.id]"
                  />
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
            <app-marketplace-seller-card [user]="user" />
          }

          <app-marketplace-filter-panel
            [hasActiveSearch]="hasActiveSearch()"
            [selectedCondition]="selectedCondition"
            [minPrice]="minPrice"
            [maxPrice]="maxPrice"
            [city]="city"
            [county]="county"
            (selectedConditionChange)="selectedCondition = $event; runSearch()"
            (minPriceChange)="minPrice = $event"
            (maxPriceChange)="maxPrice = $event"
            (cityChange)="city = $event"
            (countyChange)="county = $event"
            (applyFilters)="runSearch()"
            (clearFilters)="clearFilters()"
          />
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
    .hero-art {
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

    .hero-art {
      min-height: 320px;
      overflow: hidden;
      border: 1px solid rgba(255, 255, 255, 0.72);
      border-radius: 8px;
      box-shadow: 0 18px 42px rgba(132, 83, 143, 0.16);
    }

    .hero-art img {
      width: 100%;
      height: 100%;
      min-height: inherit;
      display: block;
      object-fit: cover;
      object-position: center;
    }

    .hero-art-card {
      position: absolute;
      right: 0.85rem;
      bottom: 0.85rem;
      width: min(78%, 270px);
      display: grid;
      gap: 0.18rem;
      padding: 0.75rem;
      border: 1px solid rgba(255, 255, 255, 0.78);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.86);
      box-shadow: 0 14px 32px rgba(100, 63, 120, 0.18);
      backdrop-filter: blur(16px);
    }

    .hero-art-card span,
    .hero-art-card small {
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 850;
    }

    .hero-art-card strong {
      color: var(--market-ink);
      font-weight: 950;
      line-height: 1.2;
      overflow-wrap: anywhere;
    }

    .browse-section {
      display: grid;
      grid-template-columns: 180px minmax(0, 1fr) 280px;
      gap: 1rem;
      align-items: start;
      min-height: calc(100dvh - 112px);
    }

    .category-rail {
      position: sticky;
      top: 92px;
      display: grid;
      gap: 0.45rem;
      padding: 0.75rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 12px 26px rgba(143, 92, 144, 0.1);
    }

    .rail-title {
      display: grid;
      gap: 0.1rem;
      padding: 0.25rem 0.25rem 0.45rem;
    }

    .rail-title span,
    .rail-note span {
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 850;
    }

    .rail-title strong,
    .rail-note strong {
      color: var(--market-ink);
      font-weight: 950;
    }

    .category-rail button {
      width: 100%;
      min-height: 36px;
      justify-content: flex-start;
      border: 0;
      background: transparent;
      color: var(--market-muted);
      text-align: left;
      font-size: 0.84rem;
      box-shadow: none;
    }

    .category-rail button.active,
    .category-rail button:hover {
      background: linear-gradient(135deg, #ffe6f1, #f2ecff);
      color: var(--market-accent-dark);
    }

    .rail-note {
      display: grid;
      gap: 0.2rem;
      margin-top: 0.35rem;
      padding: 0.75rem;
      border: 1px solid rgba(244, 114, 182, 0.2);
      border-radius: 8px;
      background: #fff6fb;
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

    .browse-tabs {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.45rem;
    }

    .browse-tabs button,
    .browse-tabs a {
      min-height: 36px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff8fc;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 900;
      padding: 0 0.75rem;
      text-decoration: none;
    }

    .browse-tabs button.active,
    .browse-tabs button:hover,
    .browse-tabs a:hover {
      border-color: rgba(244, 114, 182, 0.42);
      background: linear-gradient(135deg, #ff8fc4, #9c83ef);
      color: #fff;
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

    .cute-empty {
      grid-template-columns: auto minmax(0, 360px);
      justify-content: center;
      gap: 1rem;
    }

    .cute-empty div {
      display: grid;
      gap: 0.35rem;
      justify-items: start;
      text-align: left;
    }

    .cute-empty strong {
      color: var(--market-ink);
      font-size: 1.15rem;
      font-weight: 950;
    }

    .cute-empty p {
      margin: 0;
      color: var(--market-muted);
      font-weight: 750;
    }

    .cute-empty button {
      min-height: 38px;
      margin-top: 0.35rem;
      border: 0;
      border-radius: 999px;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.18);
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

    .view-badge {
      position: absolute;
      right: 0.65rem;
      top: 0.65rem;
      min-height: 26px;
      display: inline-flex;
      align-items: center;
      padding: 0 0.55rem;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.92);
      color: var(--market-accent-dark);
      font-size: 0.72rem;
      font-weight: 900;
      box-shadow: 0 4px 12px rgba(143, 92, 144, 0.15);
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
      .browse-section {
        grid-template-columns: 160px minmax(0, 1fr);
      }

      .marketplace-rail {
        grid-column: 1 / -1;
        grid-template-columns: minmax(0, 1fr);
      }

      .listing-grid {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }

    @media (max-width: 980px) {
      .hero-section,
      .browse-section {
        grid-template-columns: 1fr;
      }

      .category-rail {
        position: static;
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .rail-title,
      .rail-note {
        grid-column: 1 / -1;
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

      .hero-art {
        min-height: 240px;
      }

      .category-rail {
        grid-template-columns: 1fr;
      }

      .preview-notes,
      .hero-search,
      .browse-tools,
      .price-fields,
      .cute-empty,
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
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
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
    this.route.queryParamMap.subscribe(params => {
      this.searchTerm = params.get('q') || '';
      this.runSearch();
    });
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

  runHeroSearch(query: string): void {
    this.searchTerm = query;
    this.runSearch();
  }

  startHeroSellFlow(): void {
    this.router.navigateByUrl('/account/listings');
  }

  isSignedIn(): boolean {
    return Boolean(this.authService.user());
  }

  selectNewest(): void {
    this.sortMode = 'newest';
    this.runSearch();
  }

  selectBudgetFinds(): void {
    this.maxPrice = 50;
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

  featuredProduct(): MarketplaceUiProduct | null {
    const listing = this.featuredListing();
    return listing ? this.productFor(listing) : null;
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

  productFor(listing: MarketplaceBrowseListing): MarketplaceUiProduct {
    const firstImage = listing.images[0];
    const hasImage = Boolean(firstImage?.url || firstImage?.uploadUrl);
    return {
      id: listing.id,
      title: listing.title,
      sellerName: this.ownerLabel(listing),
      priceAmount: listing.priceAmount,
      currency: listing.currency,
      categoryName: listing.categoryName,
      conditionLabel: this.conditionLabel(listing.condition),
      locationLabel: this.locationLabel(listing),
      imageUrl: hasImage ? this.imageUrl(listing) : null,
      imageAlt: firstImage?.altText || listing.title,
      badge: this.productBadge(listing.id),
      favoriteCount: listing.likeCount || 0,
      visitCount: listing.visitCount || 0,
    };
  }

  imageUrl(listing: MarketplaceBrowseListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  private productBadge(id: string): 'NEW' | 'HOT' | 'SALE' {
    const badges = ['NEW', 'HOT', 'SALE'] as const;
    return badges[id.length % badges.length];
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
