import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Category, PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { BrandLoadingScreenComponent } from '../../shared/components/ui/brand-loading-screen.component';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';
import { publicListingPrimaryImageUrl } from '../../shared/listing/public-listing-display';

interface StorefrontPreview {
  key: string;
  name: string;
  city: string;
  county: string;
  activeListings: number;
  categories: string[];
  coverImageUrl: string;
  coverAlt: string;
  logoUrl: string | null;
}

@Component({
  selector: 'app-business-stores',
  standalone: true,
  imports: [BrandLoadingScreenComponent, BrandMascotComponent, FormsModule, RouterLink],
  template: `
    <section class="stores-page">
      <header class="stores-hero">
        <div class="hero-copy">
          <p class="eyebrow">Verified storefronts</p>
          <h1>Browse verified stores</h1>
          <p>Shop from approved business sellers and discover local storefronts.</p>
          <form class="store-search" role="search" (submit)="runSearch(); $event.preventDefault()">
            <label>
              <span>Search stores</span>
              <input
                name="q"
                type="search"
                [(ngModel)]="searchTerm"
                placeholder="Search stores, categories, or cities..."
              />
            </label>
            <button type="submit">Search</button>
            <a routerLink="/business/apply" class="hero-store-link">Create store</a>
          </form>
        </div>

        <div class="hero-illustration" aria-hidden="true">
          <div class="shop-window">
            <app-brand-mascot variant="badge" alt="" />
            <div class="shelf shelf-top"></div>
            <div class="shelf shelf-bottom"></div>
            <span class="sparkle s1"></span>
            <span class="sparkle s2"></span>
          </div>
        </div>
      </header>

      <section class="stores-layout" aria-labelledby="stores-title">
        <main class="stores-main">
          <div class="stores-panel">
            <div class="section-head">
              <div>
                <p class="eyebrow">Approved sellers</p>
                <h2 id="stores-title">Business Storefronts</h2>
              </div>
              <span class="result-count">{{ stores().length }} {{ stores().length === 1 ? 'store found' : 'stores found' }}</span>
            </div>

            @if (loading()) {
              <app-brand-loading-screen
                label="Loading verified stores"
                detail="Fetching approved storefronts and active listings."
              />
            } @else if (errorMsg()) {
              <div class="empty-state error-state">
                <app-brand-mascot variant="badge" alt="Store loading error" />
                <div>
                  <strong>{{ errorMsg() }}</strong>
                  <p>Refresh the search to try loading business storefronts again.</p>
                  <button type="button" (click)="runSearch()">Retry</button>
                </div>
              </div>
            } @else if (stores().length === 0) {
              <div class="empty-state">
                <app-brand-mascot variant="badge" alt="No verified stores found" />
                <div>
                  <strong>No verified stores found</strong>
                  <p>Try changing your filters or search another city.</p>
                  <button type="button" (click)="clearFilters()">Clear filters</button>
                </div>
              </div>
            } @else {
              <div class="store-grid">
                @for (store of stores(); track store.key) {
                  <article class="store-card">
                    <div class="store-cover">
                      @if (store.coverImageUrl) {
                        <img [src]="store.coverImageUrl" [alt]="store.coverAlt" />
                      } @else {
                        <div class="cover-fallback">
                          <span>Verified store</span>
                        </div>
                      }
                      <div class="store-logo" aria-hidden="true">
                        @if (store.logoUrl) {
                          <img [src]="store.logoUrl" [alt]="store.name" />
                        } @else {
                          <span>{{ initials(store.name) }}</span>
                        }
                      </div>
                    </div>

                    <div class="store-body">
                      <div class="store-title-row">
                        <h3>{{ store.name }}</h3>
                        <span class="verified-badge">Verified</span>
                      </div>
                      <p class="store-location">{{ locationLabel(store) }}</p>

                      <div class="store-meta">
                        <span>{{ store.activeListings }} {{ store.activeListings === 1 ? 'active listing' : 'active listings' }}</span>
                      </div>

                      <div class="category-chips" aria-label="Store categories">
                        @for (category of store.categories.slice(0, 3); track category) {
                          <span>{{ category }}</span>
                        }
                      </div>

                      <a routerLink="/stores" class="visit-store-link">
                        Visit store
                      </a>
                    </div>
                  </article>
                }
              </div>
              @if (hasMore()) {
                <div class="load-more-row">
                  <button type="button" (click)="loadMore()" [disabled]="loadingMore()">
                    {{ loadingMore() ? 'Loading...' : 'Load more stores' }}
                  </button>
                </div>
              }
            }
          </div>
        </main>

        <aside class="filter-panel" aria-label="Filter business storefronts">
          <div class="filter-heading">
            <div>
              <span>Store filters</span>
              <small>Verified business sellers only</small>
            </div>
            @if (hasActiveSearch()) {
              <button type="button" class="clear-button" (click)="clearFilters()">Clear</button>
            }
          </div>

          <form class="filter-stack" (submit)="runSearch(); $event.preventDefault()">
            <label>
              <span>Category</span>
              <select name="category" [(ngModel)]="selectedCategoryId">
                <option value="ALL">All categories</option>
                @for (category of categoryOptions(); track category.id) {
                  <option [value]="category.id">{{ category.name }}</option>
                }
              </select>
            </label>

            <label>
              <span>City</span>
              <input name="city" type="search" [(ngModel)]="city" placeholder="Irvine" />
            </label>

            <label>
              <span>County</span>
              <input name="county" type="search" [(ngModel)]="county" placeholder="Orange County" />
            </label>

            <label class="check-row">
              <input name="verifiedOnly" type="checkbox" [(ngModel)]="verifiedOnly" />
              <span>Verified only</span>
            </label>

            <label class="check-row">
              <input name="hasActiveListings" type="checkbox" [(ngModel)]="hasActiveListingsOnly" />
              <span>Has active listings</span>
            </label>

            <div class="filter-actions">
              <button type="submit">Apply</button>
              <button type="button" class="secondary-button" (click)="clearFilters()">Clear filters</button>
            </div>
          </form>
        </aside>
      </section>
    </section>
  `,
  styles: [`
    .stores-page {
      display: flex;
      flex-direction: column;
      gap: 1.1rem;
    }

    .stores-hero,
    .stores-panel,
    .filter-panel,
    .store-card,
    .empty-state {
      border: 1px solid rgba(236, 79, 163, 0.18);
      border-radius: 24px;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 18px 42px rgba(132, 77, 160, 0.11);
    }

    .stores-hero {
      min-height: 260px;
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(280px, 390px);
      gap: 1rem;
      overflow: hidden;
      position: relative;
      padding: clamp(1.25rem, 3vw, 2rem);
      background:
        radial-gradient(circle at 82% 18%, rgba(236, 79, 163, 0.18), transparent 34%),
        radial-gradient(circle at 58% 96%, rgba(139, 92, 246, 0.16), transparent 32%),
        linear-gradient(135deg, rgba(255, 245, 251, 0.97), rgba(244, 239, 255, 0.92));
    }

    .stores-hero::before {
      content: "";
      position: absolute;
      inset: 0;
      background-image:
        linear-gradient(45deg, rgba(236, 79, 163, 0.08) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(139, 92, 246, 0.07) 25%, transparent 25%);
      background-size: 34px 34px;
      opacity: 0.23;
      pointer-events: none;
    }

    .hero-copy,
    .hero-illustration {
      position: relative;
      z-index: 1;
    }

    .hero-copy {
      display: flex;
      max-width: 760px;
      flex-direction: column;
      justify-content: center;
    }

    .eyebrow {
      margin: 0 0 0.4rem;
      color: #c43d91;
      font-size: 0.78rem;
      font-weight: 900;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1,
    h2,
    h3,
    p {
      margin: 0;
    }

    h1 {
      color: var(--market-ink);
      font-size: clamp(2.4rem, 5vw, 4.2rem);
      line-height: 0.98;
      letter-spacing: 0;
    }

    .stores-hero p:not(.eyebrow) {
      max-width: 620px;
      margin-top: 0.8rem;
      color: var(--market-muted);
      font-weight: 800;
      line-height: 1.55;
    }

    .store-search {
      max-width: 760px;
      margin-top: 1.15rem;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: 0.75rem;
      align-items: end;
    }

    .hero-illustration {
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .shop-window {
      width: min(100%, 340px);
      aspect-ratio: 1 / 0.86;
      position: relative;
      display: grid;
      place-items: center;
      border: 1px solid rgba(236, 79, 163, 0.18);
      border-radius: 28px;
      background:
        linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(255, 232, 248, 0.48)),
        linear-gradient(135deg, rgba(236, 79, 163, 0.16), rgba(139, 92, 246, 0.16));
      box-shadow: inset 0 0 0 8px rgba(255, 255, 255, 0.34);
      opacity: 0.72;
    }

    .shop-window app-brand-mascot {
      width: 132px;
      filter: drop-shadow(0 18px 22px rgba(132, 77, 160, 0.16));
    }

    .shelf,
    .sparkle {
      position: absolute;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.72);
    }

    .shelf {
      left: 12%;
      right: 12%;
      height: 12px;
      box-shadow: 0 12px 22px rgba(132, 77, 160, 0.08);
    }

    .shelf-top {
      top: 24%;
    }

    .shelf-bottom {
      bottom: 20%;
    }

    .sparkle {
      width: 12px;
      height: 12px;
      background: #ff8cc8;
    }

    .s1 {
      top: 18%;
      right: 20%;
    }

    .s2 {
      bottom: 26%;
      left: 18%;
      background: #9b7cf8;
    }

    .stores-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 300px;
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
      padding-bottom: 0.85rem;
      border-bottom: 1px solid rgba(139, 92, 246, 0.18);
    }

    .section-head {
      margin-bottom: 1rem;
    }

    .section-head h2 {
      color: var(--market-ink);
      font-size: 1.55rem;
      letter-spacing: 0;
    }

    .result-count {
      color: var(--market-muted);
      font-size: 0.92rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .filter-heading {
      align-items: flex-start;
      color: var(--market-ink);
      font-weight: 900;
    }

    .filter-heading div {
      display: grid;
      gap: 0.18rem;
    }

    .filter-heading small {
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 800;
    }

    label {
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
      gap: 0.8rem;
    }

    input,
    select {
      width: 100%;
      min-height: 46px;
      border: 1px solid rgba(139, 92, 246, 0.22);
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.94);
      color: var(--market-ink);
      padding: 0 0.85rem;
      font: inherit;
      font-size: 0.96rem;
      font-weight: 800;
      outline: none;
    }

    button,
    .hero-store-link,
    .visit-store-link {
      min-height: 46px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(236, 79, 163, 0.3);
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.94);
      color: #9d3fb9;
      padding: 0 1rem;
      cursor: pointer;
      font-weight: 900;
      text-decoration: none;
      white-space: nowrap;
    }

    .store-search button,
    .hero-store-link,
    .filter-actions button:first-child,
    .visit-store-link {
      border: 0;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      box-shadow: 0 12px 26px rgba(139, 92, 246, 0.2);
    }

    .secondary-button,
    .clear-button {
      background: rgba(255, 255, 255, 0.94);
      color: #c43d91;
      box-shadow: none;
    }

    .clear-button {
      min-height: 32px;
      padding: 0 0.65rem;
      font-size: 0.8rem;
    }

    input:focus,
    select:focus,
    button:focus,
    .hero-store-link:focus,
    .visit-store-link:focus {
      border-color: #ec4fa3;
      box-shadow: 0 0 0 3px rgba(236, 79, 163, 0.18);
      outline: none;
    }

    .check-row {
      min-height: 48px;
      flex-direction: row;
      align-items: center;
      justify-content: flex-start;
      gap: 0.6rem;
      border: 1px solid rgba(139, 92, 246, 0.18);
      border-radius: 16px;
      background: rgba(255, 245, 251, 0.64);
      padding: 0 0.8rem;
      color: var(--market-ink);
    }

    .check-row input {
      width: 18px;
      min-height: 18px;
      accent-color: #ec4fa3;
    }

    .filter-actions {
      display: grid;
      grid-template-columns: 1fr;
      gap: 0.65rem;
    }

    .store-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 1rem;
    }

    .store-card {
      overflow: hidden;
      display: flex;
      min-width: 0;
      flex-direction: column;
      transition: transform 160ms ease, box-shadow 160ms ease;
    }

    .store-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 22px 46px rgba(132, 77, 160, 0.15);
    }

    .store-cover {
      position: relative;
      aspect-ratio: 16 / 9;
      overflow: hidden;
      background: linear-gradient(135deg, rgba(255, 219, 239, 0.92), rgba(228, 218, 255, 0.92));
    }

    .store-cover img,
    .store-logo img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      object-position: center;
    }

    .store-cover::after {
      content: "";
      position: absolute;
      inset: 0;
      background: linear-gradient(180deg, transparent 45%, rgba(69, 38, 86, 0.2));
      pointer-events: none;
    }

    .cover-fallback {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      color: #9d3fb9;
      font-weight: 900;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }

    .store-logo {
      position: absolute;
      left: 1rem;
      bottom: -26px;
      z-index: 1;
      width: 66px;
      height: 66px;
      display: grid;
      place-items: center;
      overflow: hidden;
      border: 4px solid #fff;
      border-radius: 999px;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      font-size: 1.3rem;
      font-weight: 950;
      box-shadow: 0 14px 26px rgba(132, 77, 160, 0.18);
    }

    .store-body {
      display: flex;
      flex: 1;
      min-width: 0;
      flex-direction: column;
      gap: 0.65rem;
      padding: 2.3rem 1rem 1rem;
    }

    .store-title-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.75rem;
    }

    .store-title-row h3 {
      min-width: 0;
      color: var(--market-ink);
      font-size: 1.12rem;
      line-height: 1.18;
      overflow-wrap: anywhere;
    }

    .verified-badge,
    .store-meta span,
    .category-chips span {
      border-radius: 999px;
      font-size: 0.76rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .verified-badge {
      background: rgba(16, 185, 129, 0.11);
      color: #149475;
      padding: 0.32rem 0.58rem;
    }

    .store-location {
      min-width: 0;
      color: var(--market-muted);
      font-size: 0.88rem;
      font-weight: 850;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .store-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0.45rem;
    }

    .store-meta span {
      background: rgba(139, 92, 246, 0.1);
      color: #7b55cf;
      padding: 0.34rem 0.62rem;
    }

    .category-chips {
      min-height: 30px;
      display: flex;
      flex-wrap: wrap;
      gap: 0.45rem;
    }

    .category-chips span {
      background: rgba(236, 79, 163, 0.1);
      color: #c43d91;
      padding: 0.32rem 0.58rem;
    }

    .visit-store-link {
      width: 100%;
      margin-top: auto;
    }

    .load-more-row {
      display: flex;
      justify-content: center;
      padding-top: 1rem;
    }

    .load-more-row button {
      min-width: 180px;
      border: 0;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      box-shadow: 0 12px 26px rgba(139, 92, 246, 0.2);
    }

    .load-more-row button:disabled {
      cursor: not-allowed;
      opacity: 0.68;
    }

    .empty-state {
      min-height: 320px;
      display: grid;
      grid-template-columns: 120px minmax(0, 420px);
      place-content: center;
      align-items: center;
      gap: 1rem;
      padding: 2rem;
      color: var(--market-muted);
      font-weight: 850;
      text-align: left;
    }

    .empty-state app-brand-mascot {
      width: 112px;
    }

    .empty-state strong {
      display: block;
      color: var(--market-ink);
      font-size: 1.35rem;
      line-height: 1.2;
    }

    .empty-state p {
      margin: 0.45rem 0 1rem;
      line-height: 1.5;
    }

    .error-state strong {
      color: #b83272;
    }

    @media (max-width: 1180px) {
      .store-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 900px) {
      .stores-hero,
      .stores-layout,
      .store-grid {
        grid-template-columns: 1fr;
      }

      .stores-hero {
        min-height: auto;
      }

      .hero-illustration {
        display: none;
      }

      .filter-panel {
        position: static;
        order: -1;
      }

      .store-search {
        grid-template-columns: 1fr;
      }

      .section-head {
        align-items: flex-start;
        flex-direction: column;
      }

      .empty-state {
        grid-template-columns: 1fr;
        text-align: center;
      }
    }
  `],
})
export class BusinessStoresComponent implements OnInit {
  private readonly listingService = inject(ListingService);

  readonly listings = signal<PublicListing[]>([]);
  readonly stores = signal<StorefrontPreview[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly hasMore = signal(false);
  readonly errorMsg = signal('');
  readonly nextCursor = signal<string | null>(null);

  searchTerm = '';
  selectedCategoryId = 'ALL';
  city = '';
  county = '';
  verifiedOnly = true;
  hasActiveListingsOnly = true;

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
        this.stores.set(this.buildStorefronts(this.listings()));
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
        this.stores.set(this.buildStorefronts(this.listings()));
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
    this.city = '';
    this.county = '';
    this.verifiedOnly = true;
    this.hasActiveListingsOnly = true;
    this.runSearch();
  }

  hasActiveSearch(): boolean {
    return Boolean(
      this.searchTerm.trim()
      || this.selectedCategoryId !== 'ALL'
      || this.city.trim()
      || this.county.trim()
      || !this.verifiedOnly
      || !this.hasActiveListingsOnly,
    );
  }

  initials(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean).slice(0, 2);
    const initials = words.map(word => word[0]?.toUpperCase()).join('');
    return initials || 'MS';
  }

  locationLabel(store: StorefrontPreview): string {
    const city = store.city.trim();
    const county = store.county.trim();
    if (city && county) {
      return `${city}, ${county}`;
    }
    return city || county || 'Local storefront';
  }

  private buildStorefronts(listings: PublicListing[]): StorefrontPreview[] {
    const storefronts = new Map<string, StorefrontPreview>();
    for (const listing of listings.filter(item => item.sellerType === 'BUSINESS')) {
      const name = listing.sellerDisplayName?.trim() || 'Verified business';
      const city = listing.publicCity || '';
      const county = listing.publicRegion || '';
      const key = `${name.toLowerCase()}|${city.toLowerCase()}|${county.toLowerCase()}`;
      const current = storefronts.get(key);
      const categorySet = new Set(current?.categories || []);
      categorySet.add(listing.categoryName);
      const coverImageUrl = current?.coverImageUrl || this.imageUrl(listing);
      storefronts.set(key, {
        key,
        name,
        city,
        county,
        activeListings: (current?.activeListings || 0) + 1,
        categories: [...categorySet],
        coverImageUrl,
        coverAlt: coverImageUrl
          ? `${name} storefront preview`
          : 'Soft pastel verified storefront preview',
        logoUrl: current?.logoUrl || listing.sellerAvatarUrl || null,
      });
    }
    return [...storefronts.values()].sort((left, right) => left.name.localeCompare(right.name));
  }

  private imageUrl(listing: PublicListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  private searchParams(cursor: string | null = null) {
    return {
      q: this.searchTerm,
      categoryId: this.selectedCategoryId === 'ALL' ? null : this.selectedCategoryId,
      condition: null,
      minPrice: null,
      maxPrice: null,
      city: this.city,
      county: this.county,
      sort: null,
      cursor,
    };
  }
}
