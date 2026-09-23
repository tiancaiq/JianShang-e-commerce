import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { Category, ListingCondition, PublicListing, PublicListingSort } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { BrandLoadingScreenComponent } from '../../shared/components/ui/brand-loading-screen.component';
import { EditorialArtworkComponent } from '../../shared/components/ui/editorial-artwork.component';
import {
  publicListingConditionLabel,
  publicListingLocationLabel,
  publicListingOwnerLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';

type BusinessConditionFilter = ListingCondition | 'ALL';
type BusinessSortFilter = PublicListingSort;

type FeaturedBusinessStore = {
  slug: string;
  name: string;
  category: string;
  location: string;
  coverUrl: string;
  itemCount: number;
  verified: boolean;
};

@Component({
  selector: 'app-business-stores',
  standalone: true,
  imports: [BrandLoadingScreenComponent, EditorialArtworkComponent, FormsModule, RouterLink],
  template: `
    <section class="business-items-page">
      <div class="stores-page-art" aria-hidden="true"></div>

      <header class="business-hero">
        <app-editorial-artwork
          class="hero-artwork"
          src="/assets/brand/anime/stores-fox-boutique-hero-v1.webp"
          alt=""
          [decorative]="true"
          focalPoint="66% center"
          mobileFocalPoint="67% 24%"
          mobileComposition="full"
          overlay="none"
          overlayStrength="0.18"
        />
        <div class="hero-ornament" aria-hidden="true">
          <span>Moonlit market</span>
          <i></i>
          <span>Objects with spirit</span>
        </div>
        <div class="hero-copy">
          <p class="eyebrow">Curated stores</p>
          <h1>Shop <em>verified</em><br /> business items</h1>
          <p>Browse catalog items published by approved business sellers. Discover distinctive collections from trusted independent shops.</p>
          <form class="business-search" role="search" (submit)="runSearch(); $event.preventDefault()">
            <label>
              <span>Search business items</span>
              <input
                name="q"
                type="search"
                [(ngModel)]="searchTerm"
                placeholder="Search products, stores, or categories..."
              />
            </label>
            <button type="submit">Search</button>
          </form>
          <div class="hero-actions">
            <a href="#business-items" class="browse-items-link">Browse business items <span aria-hidden="true">→</span></a>
            <a routerLink="/business/apply" class="hero-store-link">Become a business seller</a>
          </div>
          <div class="hero-trust" aria-label="Store catalog qualities">
            <span><i aria-hidden="true"></i> Approved businesses</span>
            <span><i aria-hidden="true"></i> Clear checkout paths</span>
          </div>
        </div>
        @if (featuredStores()[0]; as spotlight) {
          <aside class="hero-spotlight" aria-label="Featured store spotlight">
            <span>Shop spotlight</span>
            <div class="spotlight-store">
              <div class="spotlight-mark" aria-hidden="true">{{ storeInitials(spotlight.name) }}</div>
              <div>
                <strong>{{ spotlight.name }}</strong>
                <small>{{ spotlight.category }}</small>
              </div>
            </div>
            <p>{{ spotlight.itemCount }} {{ spotlight.itemCount === 1 ? 'piece' : 'pieces' }} in this collection</p>
            <a [routerLink]="['/stores', spotlight.slug]">Explore the boutique <span aria-hidden="true">→</span></a>
          </aside>
        } @else {
          <div class="hero-spotlight hero-spotlight-placeholder" aria-hidden="true">
            <span>Shop spotlight</span>
            <strong>Beautiful stores.<br />Thoughtful collections.</strong>
            <small>Verified business sellers</small>
          </div>
        }
      </header>

      <div class="stores-content-flow">
          @if (featuredStores().length > 0) {
            <section class="featured-stores" aria-labelledby="featured-stores-title">
          <div class="featured-stores-head">
            <div>
              <p class="eyebrow">Boutique directory</p>
              <h2 id="featured-stores-title">Featured stores</h2>
              <p>Meet the approved businesses behind this collection.</p>
            </div>
            <a href="#business-items">Browse every business item <span aria-hidden="true">→</span></a>
          </div>
          <div class="store-grid">
            @for (store of featuredStores(); track store.slug; let index = $index) {
              <article class="store-card">
                <a [routerLink]="['/stores', store.slug]" class="store-card-art" [attr.aria-label]="'Explore ' + store.name">
                  @if (store.coverUrl) {
                    <img [src]="store.coverUrl" [alt]="store.name + ' collection cover'" (error)="useStoreCoverFallback($event)" />
                  } @else {
                    <img src="/assets/brand/anime/stores-fox-boutique-hero-v1.webp" alt="" aria-hidden="true" />
                  }
                  <span class="store-number" aria-hidden="true">0{{ index + 1 }}</span>
                  <span class="store-mark" aria-hidden="true">{{ storeInitials(store.name) }}</span>
                </a>
                <div class="store-card-body">
                  <div class="store-card-kicker">
                    <span>{{ store.verified ? 'Verified boutique' : 'Business store' }}</span>
                    <span>{{ store.itemCount }} {{ store.itemCount === 1 ? 'item' : 'items' }}</span>
                  </div>
                  <h3>{{ store.name }}</h3>
                  <p>{{ store.category }} <span aria-hidden="true">·</span> {{ store.location }}</p>
                  <a [routerLink]="['/stores', store.slug]">Explore boutique <span aria-hidden="true">→</span></a>
                </div>
              </article>
            }
          </div>
            </section>
          }

          <section id="business-items" class="business-layout" aria-labelledby="business-items-title">
        <main class="business-main">
          <div class="items-panel">
            <div class="section-head">
              <div>
                <p class="eyebrow">Approved business items</p>
                <h2 id="business-items-title">Business Items</h2>
              </div>
              <div class="result-actions">
                <span class="result-count">
                  {{ listings().length }} {{ listings().length === 1 ? 'item found' : 'items found' }}
                </span>
                <button
                  type="button"
                  class="mobile-filter-trigger"
                  aria-controls="business-item-filters"
                  [attr.aria-expanded]="filterOpen()"
                  (click)="filterOpen.set(true)"
                >Filters</button>
              </div>
            </div>

            @if (loading()) {
              <app-brand-loading-screen
                label="Loading business items"
                detail="Fetching active products from approved business sellers."
              />
            } @else if (errorMsg()) {
              <div class="empty-state error-state">
                <div>
                  <strong>{{ errorMsg() }}</strong>
                  <p>Refresh the search to try loading business items again.</p>
                  <button type="button" (click)="runSearch()">Retry</button>
                </div>
              </div>
            } @else if (listings().length === 0) {
              <div class="empty-state">
                <img src="/assets/brand/anime/seller-studio-anime.webp" alt="" aria-hidden="true" />
                <div>
                  <strong>No business items found</strong>
                  <p>{{ hasActiveSearch() ? activeFilterSummary() : 'Approved business items will appear here once stores publish them.' }}</p>
                  @if (hasActiveSearch() && suggestedCategoryName()) {
                    <p class="suggestion">Suggested category: {{ suggestedCategoryName() }}</p>
                  }
                  <button type="button" (click)="clearFilters()">Clear filters</button>
                  @if (hasActiveSearch()) {
                    <button type="button" class="secondary-button" (click)="browseAllBusinessItems()">Browse all business items</button>
                  }
                </div>
              </div>
            } @else {
              <div class="item-grid">
                @for (item of listings(); track item.id) {
                  <article class="item-card">
                    <a [routerLink]="['/listings', item.id]" class="item-image-link" [attr.aria-label]="'View ' + item.title">
                      @if (imageUrl(item)) {
                        <img [src]="imageUrl(item)" [alt]="primaryImageAlt(item)" (error)="useListingImageFallback($event)" />
                      } @else {
                        <div class="image-fallback">
                          <span>{{ item.categoryName }}</span>
                        </div>
                      }
                      <span class="business-badge">Business</span>
                    </a>

                    <div class="item-body">
                      <div class="item-meta-row">
                        <span>{{ item.categoryName }}</span>
                        <span>{{ conditionLabel(item.condition) }}</span>
                      </div>

                      <h3>
                        <a [routerLink]="['/listings', item.id]">{{ item.title }}</a>
                      </h3>

                      <p class="seller-line">
                        Sold by
                        @if (item.storeSlug) {
                          <a [routerLink]="['/stores', item.storeSlug]">{{ item.storeName || ownerLabel(item) }}</a>
                        } @else {
                          <span>{{ item.storeName || ownerLabel(item) }}</span>
                        }
                        @if (item.businessVerified) {
                          <span class="verified-label">Verified</span>
                        }
                      </p>
                      <p class="location-line">{{ locationLabel(item) }}</p>

                      <div class="price-row">
                        <strong>{{ formatPrice(item) }}</strong>
                        <span>Business item</span>
                      </div>

                      <a [routerLink]="['/listings', item.id]" class="view-item-link">View item</a>
                    </div>
                  </article>
                }
              </div>
              @if (hasMore()) {
                <div class="load-more-row">
                  <button type="button" (click)="loadMore()" [disabled]="loadingMore()">
                    {{ loadingMore() ? 'Loading...' : 'Load more items' }}
                  </button>
                </div>
              }
            }
          </div>
        </main>

        <aside
          id="business-item-filters"
          class="filter-panel"
          [class.open]="filterOpen()"
          aria-label="Filter business items"
        >
          <div class="filter-heading">
            <div>
              <span>Item filters</span>
              <small>Approved business listings only</small>
            </div>
            <div class="filter-heading-actions">
              @if (hasActiveSearch()) {
                <button type="button" class="clear-button" (click)="clearFilters()">Clear</button>
              }
              <button type="button" class="drawer-close" (click)="filterOpen.set(false)">Close</button>
            </div>
          </div>

          <form class="filter-stack" (submit)="runSearch(); $event.preventDefault()">
            <label>
              <span>Sort by</span>
              <select name="sort" [(ngModel)]="sort">
                <option value="none">Default</option>
                <option value="newest">Newest</option>
                <option value="price_asc">Price low to high</option>
                <option value="price_desc">Price high to low</option>
              </select>
            </label>

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
              <span>Condition</span>
              <select name="condition" [(ngModel)]="selectedCondition">
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
                <input name="minPrice" type="number" min="0" step="0.01" [(ngModel)]="minPrice" />
              </label>
              <label>
                <span>Max price</span>
                <input name="maxPrice" type="number" min="0" step="0.01" [(ngModel)]="maxPrice" />
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

            <div class="filter-actions">
              <button type="submit">Apply</button>
              <button type="button" class="secondary-button" (click)="clearFilters()">Clear filters</button>
            </div>
          </form>
        </aside>
        @if (filterOpen()) {
          <button type="button" class="filter-backdrop" aria-label="Close business item filters" (click)="filterOpen.set(false)"></button>
        }
          </section>
      </div>
    </section>
  `,
  styles: [`
    .business-items-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .business-hero,
    .items-panel,
    .filter-panel,
    .item-card,
    .empty-state {
      border: 1px solid rgba(236, 79, 163, 0.18);
      border-radius: 18px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 14px 34px rgba(132, 77, 160, 0.1);
    }

    .business-hero {
      min-height: 250px;
      display: flex;
      align-items: center;
      padding: clamp(1.25rem, 3vw, 2rem);
      background:
        radial-gradient(circle at 84% 18%, rgba(236, 79, 163, 0.16), transparent 34%),
        linear-gradient(135deg, rgba(255, 245, 251, 0.98), rgba(242, 247, 255, 0.94));
    }

    .hero-copy {
      width: min(100%, 840px);
      display: flex;
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

    .business-hero p:not(.eyebrow) {
      max-width: 640px;
      margin-top: 0.8rem;
      color: var(--market-muted);
      font-weight: 800;
      line-height: 1.55;
    }

    .business-search {
      max-width: 820px;
      margin-top: 1.15rem;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: 0.75rem;
      align-items: end;
    }

    .business-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 300px;
      gap: 1rem;
      align-items: start;
    }

    .business-main {
      min-width: 0;
    }

    .items-panel,
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

    .result-actions,
    .filter-heading-actions {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .mobile-filter-trigger,
    .drawer-close,
    .filter-backdrop {
      display: none;
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

    .price-fields {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.6rem;
    }

    input,
    select {
      width: 100%;
      min-height: 46px;
      border: 1px solid rgba(139, 92, 246, 0.22);
      border-radius: 12px;
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
    .view-item-link {
      min-height: 46px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(236, 79, 163, 0.3);
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.94);
      color: #9d3fb9;
      padding: 0 1rem;
      cursor: pointer;
      font-weight: 900;
      text-decoration: none;
      white-space: nowrap;
    }

    .business-search button,
    .hero-store-link,
    .filter-actions button:first-child,
    .view-item-link,
    .load-more-row button {
      border: 0;
      background: linear-gradient(135deg, #ec4fa3, #8b5cf6);
      color: #fff;
      box-shadow: 0 10px 22px rgba(139, 92, 246, 0.2);
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
    .view-item-link:focus,
    .item-image-link:focus,
    .item-card h3 a:focus {
      border-color: #ec4fa3;
      box-shadow: 0 0 0 3px rgba(236, 79, 163, 0.18);
      outline: none;
    }

    .filter-actions {
      display: grid;
      grid-template-columns: 1fr;
      gap: 0.65rem;
    }

    .item-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.9rem;
    }

    .item-card {
      overflow: hidden;
      display: flex;
      min-width: 0;
      flex-direction: column;
      transition: transform 160ms ease, box-shadow 160ms ease;
    }

    .item-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 18px 38px rgba(132, 77, 160, 0.15);
    }

    .item-image-link {
      position: relative;
      display: block;
      aspect-ratio: 1 / 1;
      overflow: hidden;
      background: linear-gradient(135deg, rgba(255, 219, 239, 0.92), rgba(228, 218, 255, 0.92));
      color: inherit;
      text-decoration: none;
    }

    .item-image-link img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      object-position: center;
      transition: transform 180ms ease;
    }

    .item-card:hover .item-image-link img {
      transform: scale(1.03);
    }

    .image-fallback {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      color: #9d3fb9;
      font-weight: 900;
      text-align: center;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      padding: 1rem;
    }

    .business-badge {
      position: absolute;
      top: 0.7rem;
      left: 0.7rem;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.92);
      color: #149475;
      padding: 0.32rem 0.58rem;
      font-size: 0.72rem;
      font-weight: 950;
      box-shadow: 0 8px 18px rgba(69, 38, 86, 0.12);
    }

    .item-body {
      display: flex;
      flex: 1;
      min-width: 0;
      flex-direction: column;
      gap: 0.55rem;
      padding: 0.85rem;
    }

    .item-meta-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      min-height: 26px;
    }

    .item-meta-row span {
      border-radius: 999px;
      background: rgba(236, 79, 163, 0.1);
      color: #c43d91;
      padding: 0.26rem 0.5rem;
      font-size: 0.7rem;
      font-weight: 900;
    }

    .item-meta-row span + span {
      background: rgba(139, 92, 246, 0.1);
      color: #7653c9;
    }

    .item-card h3 {
      min-height: 2.6em;
      color: var(--market-ink);
      font-size: 1rem;
      line-height: 1.3;
      letter-spacing: 0;
    }

    .item-card h3 a {
      color: inherit;
      text-decoration: none;
      overflow-wrap: anywhere;
    }

    .seller-line,
    .location-line {
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 820;
      line-height: 1.35;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .seller-line a {
      color: var(--market-accent-dark);
      text-underline-offset: 2px;
    }

    .verified-label {
      margin-left: 0.35rem;
      color: #14785f;
      font-size: 0.7rem;
      font-weight: 950;
      text-transform: uppercase;
    }

    .price-row {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.6rem;
      margin-top: auto;
    }

    .price-row strong {
      color: var(--market-ink);
      font-size: 1.18rem;
      font-weight: 950;
      white-space: nowrap;
    }

    .price-row span {
      color: #149475;
      font-size: 0.78rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .view-item-link {
      width: 100%;
    }

    .load-more-row {
      display: flex;
      justify-content: center;
      padding-top: 1rem;
    }

    .load-more-row button {
      min-width: 180px;
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

    @media (max-width: 1260px) {
      .item-grid {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }

    @media (max-width: 980px) {
      .business-layout {
        grid-template-columns: 1fr;
      }

      .filter-panel {
        position: static;
        order: -1;
      }

      .item-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 720px) {
      .business-search,
      .item-grid,
      .price-fields {
        grid-template-columns: 1fr;
      }

      .business-hero {
        min-height: auto;
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

    .business-items-page {
      gap: 1.5rem;
    }

    .business-hero,
    .items-panel,
    .filter-panel,
    .item-card,
    .empty-state {
      border-color: var(--market-line);
      border-radius: var(--market-radius-md);
      background: var(--market-surface);
      box-shadow: var(--market-shadow-sm);
    }

    .business-hero {
      min-height: 220px;
      padding: clamp(1.5rem, 4vw, 3rem);
      border-radius: var(--market-radius-lg);
      background:
        linear-gradient(115deg, #fff 0 68%, var(--market-accent-soft) 68% 100%);
    }

    .eyebrow {
      color: var(--market-accent-dark);
      font-weight: 800;
      letter-spacing: 0.1em;
    }

    h1 {
      color: var(--market-ink);
      font-size: clamp(2.4rem, 4.5vw, 4rem);
      font-weight: 820;
      letter-spacing: -0.05em;
    }

    .business-hero p:not(.eyebrow) {
      color: var(--market-muted);
      font-weight: 500;
    }

    input,
    select {
      border-color: var(--market-line-strong);
      border-radius: var(--market-radius-sm);
      background: #fbfcfc;
      font-weight: 600;
    }

    button,
    .hero-store-link,
    .view-item-link {
      border-color: var(--market-line-strong);
      border-radius: var(--market-radius-sm);
      color: var(--market-accent-dark);
      font-weight: 750;
    }

    .business-search button,
    .hero-store-link,
    .filter-actions button:first-child,
    .view-item-link,
    .load-more-row button {
      background: var(--market-accent);
      color: #fff;
      box-shadow: none;
    }

    .business-layout {
      grid-template-columns: minmax(0, 1fr) 300px;
      gap: 1.25rem;
    }

    .items-panel {
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      box-shadow: none;
    }

    .section-head,
    .filter-heading {
      border-color: var(--market-line);
    }

    .item-grid {
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 1rem;
    }

    .item-card:hover {
      box-shadow: var(--market-shadow-md);
    }

    .item-image-link {
      aspect-ratio: 4 / 3;
      background: linear-gradient(135deg, #dcefeb, #eef4f2);
    }

    .business-badge {
      background: rgba(13, 124, 117, 0.92);
      color: #fff;
      box-shadow: none;
    }

    .item-meta-row span,
    .item-meta-row span + span {
      background: var(--market-surface-subtle);
      color: var(--market-muted);
      font-weight: 750;
    }

    .price-row strong {
      color: var(--market-ink);
      font-variant-numeric: tabular-nums;
    }

    .empty-state {
      grid-template-columns: minmax(0, 440px);
      justify-content: center;
      text-align: center;
    }

    @media (max-width: 980px) {
      .business-layout {
        grid-template-columns: 1fr;
      }

      .filter-panel {
        position: fixed;
        inset: 118px 0 0 auto;
        z-index: 61;
        width: min(360px, calc(100vw - 24px));
        max-height: calc(100dvh - 118px);
        display: none;
        overflow-y: auto;
        order: initial;
        border-radius: var(--market-radius-md) 0 0 0;
        box-shadow: var(--market-shadow-lg);
      }

      .filter-panel.open {
        display: flex;
      }

      .filter-heading {
        position: sticky;
        top: 0;
        z-index: 1;
        background: var(--market-surface);
      }

      .mobile-filter-trigger,
      .drawer-close {
        min-height: 40px;
        display: inline-flex;
      }

      .filter-backdrop {
        position: fixed;
        inset: 118px 0 0;
        z-index: 60;
        display: block;
        width: 100%;
        min-height: 0;
        padding: 0;
        border: 0;
        border-radius: 0;
        background: rgba(20, 47, 50, 0.36);
      }
    }

    @media (max-width: 720px) {
      .business-search {
        grid-template-columns: 1fr;
      }
      .business-hero {
        background: linear-gradient(155deg, #fff 0 76%, var(--market-accent-soft) 76% 100%);
      }
    }

    /* Storefront variant: the same market language with a calmer merchant emphasis. */
    .business-hero {
      display: grid;
      grid-template-columns: minmax(0, 1.15fr) minmax(300px, .85fr);
      gap: 1.5rem;
      overflow: hidden;
      min-height: 310px;
      border-radius: 30px 12px 30px 12px;
      background:
        radial-gradient(circle at 8% 16%, rgba(184, 223, 244, .46) 0 5rem, transparent 5.1rem),
        linear-gradient(118deg, #fffdfb 0 64%, #e1f3ee 64% 100%);
    }

    .hero-copy { position: relative; z-index: 2; }

    .business-hero-art {
      position: relative;
      min-height: 250px;
      align-self: stretch;
    }

    .business-hero-art img {
      position: absolute;
      inset: -12% -12% -12% -4%;
      width: 118%;
      height: 124%;
      object-fit: contain;
      filter: drop-shadow(0 16px 20px rgba(78, 66, 89, .09));
    }

    .business-hero-art span {
      position: absolute;
      right: 5%;
      bottom: 10%;
      min-height: 34px;
      display: inline-flex;
      align-items: center;
      border: 1px solid rgba(78, 66, 89, .12);
      border-radius: 999px;
      background: rgba(255, 253, 251, .94);
      color: var(--market-mint);
      padding: 0 .85rem;
      box-shadow: var(--market-shadow-md);
      font-size: .76rem;
      font-weight: 850;
      transform: rotate(2deg);
    }

    .item-card {
      border-radius: 18px 7px 18px 7px;
      box-shadow: 0 7px 20px rgba(78, 66, 89, .07);
    }

    .item-image-link { margin: .45rem .45rem 0; border-radius: 13px 4px 13px 4px; }
    .business-badge { background: rgba(55, 125, 107, .94); }
    .price-row strong { color: var(--market-mint); }

    .empty-state > img {
      width: min(230px, 100%);
      max-height: 170px;
      object-fit: contain;
    }

    .empty-state:not(.error-state) {
      grid-template-columns: minmax(150px, 230px) minmax(0, 420px);
      gap: 1.5rem;
      text-align: left;
      background: linear-gradient(145deg, #fffdfb, #eef8f5);
    }

    @media (max-width: 900px) {
      .business-hero { grid-template-columns: 1fr; }
      .business-hero-art { min-height: 220px; }
      .business-hero-art img { inset: -18% 5% -12% auto; width: min(100%, 440px); }
    }

    @media (max-width: 720px) {
      .business-hero { min-height: auto; padding: 1.1rem; border-radius: 24px 9px 24px 9px; }
      .business-hero-art { min-height: 170px; }
      .business-hero-art span { display: none; }
      .empty-state:not(.error-state) { grid-template-columns: 1fr; text-align: center; }
    }

  `],
})
export class BusinessStoresComponent implements OnInit {
  private readonly listingService = inject(ListingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly listings = signal<PublicListing[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly hasMore = signal(false);
  readonly errorMsg = signal('');
  readonly filterOpen = signal(false);
  readonly nextCursor = signal<string | null>(null);
  readonly featuredStores = computed<FeaturedBusinessStore[]>(() => {
    const stores = new Map<string, FeaturedBusinessStore>();

    for (const listing of this.listings()) {
      if (!listing.storeSlug) {
        continue;
      }
      const current = stores.get(listing.storeSlug);
      if (current) {
        current.itemCount += 1;
        if (!current.coverUrl) {
          current.coverUrl = this.imageUrl(listing);
        }
        continue;
      }
      stores.set(listing.storeSlug, {
        slug: listing.storeSlug,
        name: listing.storeName || this.ownerLabel(listing),
        category: listing.categoryName || 'Curated collection',
        location: this.locationLabel(listing),
        coverUrl: this.imageUrl(listing),
        itemCount: 1,
        verified: Boolean(listing.businessVerified),
      });
    }

    return [...stores.values()].slice(0, 3);
  });

  searchTerm = '';
  selectedCategoryId = 'ALL';
  selectedCondition: BusinessConditionFilter = 'ALL';
  sort: BusinessSortFilter = 'none';
  minPrice: number | null = null;
  maxPrice: number | null = null;
  city = '';
  county = '';
  private lastSearchKey: string | null = null;

  ngOnInit(): void {
    this.listingService.getCategories().subscribe({
      next: categories => this.categories.set(categories),
      error: () => this.categories.set([]),
    });
    this.route.queryParamMap.subscribe(params => {
      this.applyQueryParams(params);
      const key = this.searchStateKey();
      if (key !== this.lastSearchKey) {
        this.fetchFirstPage();
      }
    });
  }

  runSearch(): void {
    this.filterOpen.set(false);
    this.fetchFirstPage();
    this.syncUrlSearchState();
  }

  private fetchFirstPage(): void {
    this.lastSearchKey = this.searchStateKey();
    this.loading.set(true);
    this.loadingMore.set(false);
    this.errorMsg.set('');
    this.nextCursor.set(null);
    this.listingService.searchBusinessStoreListings(this.searchParams()).subscribe({
      next: response => {
        this.listings.set(response.data.filter(item => item.sellerType === 'BUSINESS'));
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(error.status
          ? `Business items could not be loaded. HTTP ${error.status}.`
          : 'Business items could not be loaded.');
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
        this.listings.set([
          ...this.listings(),
          ...response.data.filter(item => item.sellerType === 'BUSINESS'),
        ]);
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loadingMore.set(false);
      },
      error: error => {
        this.errorMsg.set(error.status
          ? `Business items could not be loaded. HTTP ${error.status}.`
          : 'Business items could not be loaded.');
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
    this.sort = 'none';
    this.minPrice = null;
    this.maxPrice = null;
    this.city = '';
    this.county = '';
    this.runSearch();
  }

  browseAllBusinessItems(): void {
    this.clearFilters();
  }

  hasActiveSearch(): boolean {
    return Boolean(
      this.searchTerm.trim()
      || this.selectedCategoryId !== 'ALL'
      || this.selectedCondition !== 'ALL'
      || this.sort !== 'none'
      || this.minPrice !== null
      || this.maxPrice !== null
      || this.city.trim()
      || this.county.trim(),
    );
  }

  activeFilterSummary(): string {
    const parts: string[] = [];
    if (this.searchTerm.trim()) {
      parts.push(`keyword "${this.searchTerm.trim()}"`);
    }
    if (this.selectedCategoryId !== 'ALL') {
      parts.push(`category ${this.suggestedCategoryName() || this.selectedCategoryId}`);
    }
    if (this.selectedCondition !== 'ALL') {
      parts.push(`condition ${this.conditionLabel(this.selectedCondition)}`);
    }
    if (this.minPrice !== null || this.maxPrice !== null) {
      parts.push(`price ${this.minPrice ?? '0'} to ${this.maxPrice ?? 'any'}`);
    }
    if (this.city.trim()) {
      parts.push(`city ${this.city.trim()}`);
    }
    if (this.county.trim()) {
      parts.push(`county ${this.county.trim()}`);
    }
    if (this.sort !== 'none') {
      parts.push(`sort ${this.sortLabel(this.sort)}`);
    }
    return parts.length ? `Active filters: ${parts.join(', ')}.` : 'No active filters.';
  }

  suggestedCategoryName(): string {
    return this.categoryOptions().find(category => category.id === this.selectedCategoryId)?.name || '';
  }

  imageUrl(listing: PublicListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  primaryImageAlt(listing: PublicListing): string {
    return listing.images[0]?.altText || listing.images[0]?.originalFileName || listing.title;
  }

  ownerLabel(listing: PublicListing): string {
    return publicListingOwnerLabel(listing);
  }

  locationLabel(listing: PublicListing): string {
    return publicListingLocationLabel(listing, 'Ships from business');
  }

  conditionLabel(condition: ListingCondition): string {
    return publicListingConditionLabel(condition);
  }

  formatPrice(listing: PublicListing): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: listing.currency || 'USD',
    }).format(listing.priceAmount);
  }

  storeInitials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map(part => part[0])
      .join('')
      .toUpperCase();
  }

  useStoreCoverFallback(event: Event): void {
    const image = event.currentTarget as HTMLImageElement;
    image.onerror = null;
    image.src = '/assets/brand/anime/stores-fox-boutique-hero-v1.webp';
  }

  useListingImageFallback(event: Event): void {
    const image = event.currentTarget as HTMLImageElement;
    image.onerror = null;
    image.src = '/assets/brand/anime/shopping-bag-fallback.svg';
  }

  private searchParams(cursor: string | null = null) {
    return {
      q: this.searchTerm,
      categoryId: this.selectedCategoryId === 'ALL' ? null : this.selectedCategoryId,
      condition: this.selectedCondition === 'ALL' ? null : this.selectedCondition,
      minPrice: this.minPrice,
      maxPrice: this.maxPrice,
      city: this.city,
      county: this.county,
      sort: this.sort === 'none' ? null : this.sort,
      cursor,
    };
  }

  private applyQueryParams(params: ParamMap): void {
    this.searchTerm = params.get('q') || '';
    this.selectedCategoryId = params.get('categoryId') || 'ALL';
    this.selectedCondition = this.validCondition(params.get('condition'));
    this.sort = this.validSort(params.get('sort'));
    this.minPrice = this.optionalNumber(params.get('minPrice'));
    this.maxPrice = this.optionalNumber(params.get('maxPrice'));
    this.city = params.get('city') || '';
    this.county = params.get('county') || '';
  }

  private syncUrlSearchState(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.queryParamsFromState(),
      replaceUrl: false,
    });
  }

  private queryParamsFromState(): Record<string, string | null> {
    return {
      q: this.searchTerm.trim() || null,
      categoryId: this.selectedCategoryId === 'ALL' ? null : this.selectedCategoryId,
      condition: this.selectedCondition === 'ALL' ? null : this.selectedCondition,
      minPrice: this.minPrice === null ? null : String(this.minPrice),
      maxPrice: this.maxPrice === null ? null : String(this.maxPrice),
      city: this.city.trim() || null,
      county: this.county.trim() || null,
      sort: this.sort === 'none' ? null : this.sort,
    };
  }

  private searchStateKey(): string {
    return JSON.stringify(this.queryParamsFromState());
  }

  private optionalNumber(value: string | null): number | null {
    if (!value?.trim()) {
      return null;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  private validCondition(value: string | null): BusinessConditionFilter {
    return ['NEW', 'OPEN_BOX', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS'].includes(value || '')
      ? value as ListingCondition
      : 'ALL';
  }

  private validSort(value: string | null): BusinessSortFilter {
    return ['newest', 'price_asc', 'price_desc'].includes(value || '')
      ? value as PublicListingSort
      : 'none';
  }

  private sortLabel(sort: BusinessSortFilter): string {
    if (sort === 'price_asc') {
      return 'price low to high';
    }
    if (sort === 'price_desc') {
      return 'price high to low';
    }
    return 'newest';
  }
}
