import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  BusinessStoreListing,
  BusinessStoreListingSearchParams,
  BusinessStoreListingSort,
  Category,
  ListingCondition,
  PublicListing,
} from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import {
  publicListingLocationLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';

interface StorePreview {
  name: string;
  city: string;
  region: string;
  listings: BusinessStoreListing[];
}

@Component({
  selector: 'app-business-stores',
  standalone: true,
  imports: [DecimalPipe, FormsModule, RouterLink],
  template: `
    <section class="stores-page">
      <header class="stores-hero">
        <p class="eyebrow">Business stores</p>
        <h1>Browse verified store listings.</h1>
        <p>
          Business storefronts are separate from individual trades, with a calmer
          store-focused browsing experience.
        </p>
        <form class="store-search" role="search" (submit)="runSearch(); $event.preventDefault()">
          <label>
            <span>Search stores</span>
            <input
              name="q"
              type="search"
              [(ngModel)]="searchTerm"
              placeholder="Search store listings..."
            />
          </label>
          <button type="submit">Search</button>
        </form>
      </header>

      <section class="stores-layout" aria-labelledby="stores-title">
        <main class="stores-main">
          <div class="stores-panel">
            <div class="section-head">
              <div>
                <p class="eyebrow">Storefront finds</p>
                <h2 id="stores-title">Business Storefronts</h2>
              </div>
              <div class="section-tools">
                <span>{{ stores().length }} shown</span>
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
              <div class="empty-state">Loading stores...</div>
            } @else if (errorMsg()) {
              <div class="empty-state">{{ errorMsg() }}</div>
            } @else if (stores().length === 0) {
              <div class="empty-state">
                {{ hasActiveSearch() ? 'No business store listings match these filters.' : 'No approved business storefront listings yet.' }}
              </div>
            } @else {
              <div class="store-grid">
                @for (store of stores(); track store.name) {
                  <article class="store-card">
                    <div class="store-heading">
                      <div>
                        <p class="eyebrow">Verified business</p>
                        <h2>{{ store.name }}</h2>
                      </div>
                      <span>{{ store.listings.length }} listings</span>
                    </div>
                    <p class="store-location">{{ locationLabel(store) }}</p>

                    <div class="listing-strip">
                      @for (listing of store.listings.slice(0, 3); track listing.id) {
                        <a [routerLink]="['/listings', listing.id]" class="store-listing">
                          <div class="listing-image">
                            @if (listing.images[0]?.url || listing.images[0]?.uploadUrl) {
                              <img [src]="imageUrl(listing)" [alt]="listing.images[0]?.altText || listing.title" />
                            } @else {
                              <span>{{ listing.categoryName }}</span>
                            }
                          </div>
                          <strong>{{ listing.title }}</strong>
                          <span>{{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}</span>
                        </a>
                      }
                    </div>
                  </article>
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

        <aside class="filter-panel" aria-label="Filter business store listings">
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
        </aside>
      </section>
    </section>
  `,
  styles: [`
    .stores-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .stores-hero,
    .stores-panel,
    .filter-panel,
    .store-card,
    .empty-state {
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .stores-hero {
      padding: clamp(1rem, 4vw, 2rem);
      background:
        linear-gradient(135deg, rgba(241, 235, 255, 0.95), rgba(239, 251, 248, 0.9)),
        #fff;
    }

    .eyebrow {
      margin: 0 0 0.4rem;
      color: var(--market-lavender);
      font-size: 0.78rem;
      font-weight: 900;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1,
    h2,
    p {
      margin: 0;
    }

    h1 {
      max-width: 760px;
      color: var(--market-ink);
      font-size: clamp(2.2rem, 5vw, 4.4rem);
      line-height: 0.98;
      letter-spacing: 0;
    }

    .stores-hero p:not(.eyebrow) {
      max-width: 760px;
      margin-top: 0.9rem;
      color: var(--market-muted);
      font-weight: 700;
      line-height: 1.6;
    }

    .store-search {
      max-width: 760px;
      margin-top: 1rem;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.75rem;
      align-items: end;
    }

    .stores-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 280px;
      gap: 1rem;
      align-items: start;
    }

    .stores-main {
      min-width: 0;
    }

    .stores-panel,
    .filter-panel {
      padding: 1rem;
    }

    .filter-panel {
      position: sticky;
      top: 92px;
      display: flex;
      flex-direction: column;
      gap: 0.9rem;
    }

    .section-head,
    .filter-heading {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 1rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--market-line);
    }

    .section-head {
      margin-bottom: 1rem;
    }

    .section-head h2 {
      color: var(--market-ink);
      font-size: 1.5rem;
      letter-spacing: 0;
    }

    .section-tools {
      display: flex;
      align-items: end;
      justify-content: flex-end;
      gap: 0.75rem;
      min-width: min(100%, 360px);
    }

    .section-tools > span {
      color: var(--market-muted);
      font-size: 0.9rem;
      font-weight: 850;
      white-space: nowrap;
    }

    .filter-heading {
      align-items: center;
      color: var(--market-ink);
      font-weight: 900;
    }

    label,
    .sort-control {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 0.35rem;
      color: var(--market-muted);
      font-size: 0.8rem;
      font-weight: 900;
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

    .sort-control {
      min-width: 190px;
    }

    .sort-control select {
      min-height: 40px;
    }

    input,
    select {
      width: 100%;
      min-height: 46px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      color: var(--market-ink);
      padding: 0 0.85rem;
      font: inherit;
      font-size: 0.98rem;
      font-weight: 800;
      outline: none;
    }

    button {
      min-height: 46px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      color: var(--market-accent-dark);
      padding: 0 1rem;
      cursor: pointer;
      font-weight: 900;
    }

    .clear-button {
      min-height: 32px;
      padding: 0 0.65rem;
      font-size: 0.8rem;
    }

    input:focus,
    select:focus,
    button:focus {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(244, 114, 182, 0.18);
    }

    .store-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .load-more-row {
      display: flex;
      justify-content: center;
      padding-top: 1rem;
    }

    .load-more-row button {
      min-width: 160px;
      background: linear-gradient(135deg, #54c7ad, #8b6fe8);
      color: #fff;
      border: 0;
      box-shadow: 0 10px 22px rgba(70, 153, 141, 0.16);
    }

    .load-more-row button:disabled {
      cursor: not-allowed;
      opacity: 0.68;
    }

    .store-card {
      display: flex;
      flex-direction: column;
      gap: 0.9rem;
      padding: 1rem;
    }

    .store-heading {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .store-heading h2 {
      color: var(--market-ink);
      font-size: 1.35rem;
      letter-spacing: 0;
    }

    .store-heading > span {
      border-radius: 999px;
      background: #effbf8;
      color: var(--market-mint);
      padding: 0.35rem 0.65rem;
      font-size: 0.78rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .store-location {
      color: var(--market-muted);
      font-weight: 800;
    }

    .listing-strip {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.65rem;
    }

    .store-listing {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 0.35rem;
      color: inherit;
      text-decoration: none;
    }

    .listing-image {
      display: grid;
      place-items: center;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      border-radius: 8px;
      background: linear-gradient(135deg, #eafaf6, #efe8ff);
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 900;
      text-align: center;
    }

    .listing-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .store-listing strong {
      color: var(--market-ink);
      font-size: 0.9rem;
      overflow-wrap: anywhere;
    }

    .store-listing span {
      color: var(--market-accent-dark);
      font-size: 0.84rem;
      font-weight: 900;
    }

    .empty-state {
      min-height: 260px;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      color: var(--market-muted);
      font-weight: 850;
      text-align: center;
    }

    @media (max-width: 900px) {
      .stores-layout,
      .store-grid,
      .listing-strip {
        grid-template-columns: 1fr;
      }

      .filter-panel {
        position: static;
      }

      .store-search,
      .section-tools,
      .price-fields {
        grid-template-columns: 1fr;
      }

      .section-head {
        align-items: flex-start;
        flex-direction: column;
      }

      .section-tools {
        width: 100%;
        align-items: stretch;
        flex-direction: column;
      }
    }
  `],
})
export class BusinessStoresComponent implements OnInit {
  private readonly listingService = inject(ListingService);

  listings = signal<PublicListing[]>([]);
  stores = signal<StorePreview[]>([]);
  categories = signal<Category[]>([]);
  loading = signal(false);
  loadingMore = signal(false);
  hasMore = signal(false);
  errorMsg = signal('');
  nextCursor = signal<string | null>(null);

  searchTerm = '';
  selectedCategoryId = 'ALL';
  selectedCondition: ListingCondition | 'ALL' = 'ALL';
  sortMode: BusinessStoreListingSort = 'none';
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
    this.listingService.searchBusinessStoreListings(this.searchParams()).subscribe({
      next: response => {
        this.listings.set(response.data);
        this.stores.set(this.groupBusinessListings(this.listings()));
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(error.status
          ? `Business stores could not be loaded. HTTP ${error.status}.`
          : 'Business stores could not be loaded.');
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
    this.listingService.searchBusinessStoreListings(this.searchParams(cursor)).subscribe({
      next: response => {
        this.listings.set([...this.listings(), ...response.data]);
        this.stores.set(this.groupBusinessListings(this.listings()));
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loadingMore.set(false);
      },
      error: error => {
        this.errorMsg.set(error.status
          ? `Business stores could not be loaded. HTTP ${error.status}.`
          : 'Business stores could not be loaded.');
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

  imageUrl(listing: BusinessStoreListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  locationLabel(store: StorePreview): string {
    return publicListingLocationLabel({ publicCity: store.city, publicRegion: store.region });
  }

  private groupBusinessListings(listings: PublicListing[]): StorePreview[] {
    const stores = new Map<string, StorePreview>();
    for (const listing of this.businessStoreListings(listings)) {
      const name = listing.sellerDisplayName?.trim() || 'Business seller';
      const store = stores.get(name) || {
        name,
        city: listing.publicCity || '',
        region: listing.publicRegion || '',
        listings: [],
      };
      store.listings.push(listing);
      stores.set(name, store);
    }
    return [...stores.values()].sort((left, right) => left.name.localeCompare(right.name));
  }

  private businessStoreListings(listings: PublicListing[]): BusinessStoreListing[] {
    return listings.filter(listing => listing.sellerType === 'BUSINESS');
  }

  private searchParams(cursor: string | null = null): BusinessStoreListingSearchParams {
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
