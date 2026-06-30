import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ListingCondition, ListingSellerType, PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';

type ListingSort = 'newest' | 'price_asc' | 'price_desc';

@Component({
  selector: 'app-marketplace-home',
  standalone: true,
  imports: [DecimalPipe, FormsModule, RouterLink],
  template: `
    <section class="marketplace-home">
      <section class="hero-section" aria-labelledby="marketplace-title">
        <div class="hero-copy">
          <p class="eyebrow">MSB cute market</p>
          <h1 id="marketplace-title">Find sweet local treasures.</h1>
          <p class="summary">Browse approved individual and business listings with clear seller labels and public pickup areas.</p>

          <form class="hero-search" role="search" (submit)="$event.preventDefault()">
            <label>
              <span>Search marketplace</span>
              <input
                name="search"
                type="search"
                [(ngModel)]="searchTerm"
                placeholder="Search figures, books, bikes, decor..."
              />
            </label>
          </form>

          <div class="hero-actions">
            <a href="#listings" class="primary-link">Browse Listings</a>
            <a routerLink="/account/listings/new" class="secondary-link">List an Item</a>
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
              <span>Business sellers are reviewed</span>
            </div>
          </div>
        </div>
      </section>

      <section id="listings" class="browse-section" aria-labelledby="browse-title">
        <aside class="category-rail" aria-label="Categories">
          <div class="rail-title">Categories</div>
          <button type="button" [class.active]="selectedCategory === 'ALL'" (click)="selectCategory('ALL')">
            All items
          </button>
          @for (category of categoryOptions(); track category) {
            <button type="button" [class.active]="selectedCategory === category" (click)="selectCategory(category)">
              {{ category }}
            </button>
          }
        </aside>

        <div class="listing-area">
          <div class="browse-panel">
            <div class="browse-header">
              <div>
                <p class="eyebrow">Fresh finds</p>
                <h2 id="browse-title">Public Listings</h2>
              </div>
              <span>{{ filteredListings().length }} shown</span>
            </div>

            <form class="filter-row" aria-label="Marketplace filters" (submit)="$event.preventDefault()">
              <label>
                <span>Seller</span>
                <select name="sellerType" [(ngModel)]="selectedSellerType">
                  <option value="ALL">All sellers</option>
                  <option value="INDIVIDUAL">Individual</option>
                  <option value="BUSINESS">Business</option>
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

              <label>
                <span>Sort</span>
                <select name="sortMode" [(ngModel)]="sortMode">
                  <option value="newest">Newest</option>
                  <option value="price_asc">Price low to high</option>
                  <option value="price_desc">Price high to low</option>
                </select>
              </label>
            </form>

            @if (loading()) {
              <div class="empty-list">Loading approved listings...</div>
            } @else if (errorMsg()) {
              <div class="empty-list">{{ errorMsg() }}</div>
            } @else if (listings().length === 0) {
              <div class="empty-list">No approved listings yet.</div>
            } @else if (filteredListings().length === 0) {
              <div class="empty-list">No listings match these filters.</div>
            } @else {
              <div class="listing-grid">
                @for (listing of filteredListings(); track listing.id) {
                  <a class="listing-card" [routerLink]="['/listings', listing.id]">
                    <div class="listing-image">
                      @if (listing.images[0]?.url || listing.images[0]?.uploadUrl) {
                        <img [src]="imageUrl(listing)" [alt]="listing.images[0]?.altText || listing.title" />
                      } @else {
                        <span>{{ listing.categoryName }}</span>
                      }
                      <strong class="seller-badge" [class.business]="listing.sellerType === 'BUSINESS'">
                        {{ listing.sellerType === 'INDIVIDUAL' ? 'Individual' : 'Business' }}
                      </strong>
                    </div>
                    <div class="listing-body">
                      <div class="listing-meta">
                        <span>{{ listing.categoryName }}</span>
                        <span>{{ conditionLabel(listing.condition) }}</span>
                      </div>
                      <h3>{{ listing.title }}</h3>
                      <p>{{ locationLabel(listing) }}</p>
                      <div class="listing-price">
                        {{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}
                      </div>
                      <div class="card-foot">
                        <span>{{ listing.sellerType === 'INDIVIDUAL' ? 'Off-platform trade' : 'Reviewed business' }}</span>
                        <strong>Details</strong>
                      </div>
                    </div>
                  </a>
                }
              </div>
            }
          </div>
        </div>
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
      max-width: 660px;
    }

    .hero-search label,
    .filter-row label {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .hero-search span,
    .filter-row span {
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

    input:focus,
    select:focus {
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
      grid-template-columns: 220px minmax(0, 1fr);
      gap: 1rem;
      align-items: start;
    }

    .category-rail {
      position: sticky;
      top: 92px;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      padding: 0.75rem;
    }

    .rail-title {
      padding: 0.35rem 0.65rem;
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 900;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }

    .category-rail button {
      min-height: 38px;
      border: 0;
      border-radius: 8px;
      background: transparent;
      color: var(--market-muted);
      cursor: pointer;
      font-weight: 800;
      text-align: left;
      padding: 0 0.65rem;
    }

    .listing-area {
      min-width: 0;
    }

    .browse-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1rem;
    }

    .browse-header {
      display: flex;
      justify-content: space-between;
      align-items: end;
      gap: 1rem;
      padding-bottom: 0.75rem;
      border-bottom: 1px solid var(--market-line);
    }

    .browse-header h2 {
      font-size: 1.5rem;
    }

    .browse-header > span {
      color: var(--market-muted);
      font-size: 0.9rem;
      font-weight: 850;
    }

    .filter-row {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
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

      .category-rail {
        position: static;
        flex-direction: row;
        overflow-x: auto;
      }

      .rail-title {
        display: none;
      }

      .category-rail button {
        flex: 0 0 auto;
        white-space: nowrap;
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
      .filter-row,
      .listing-grid {
        grid-template-columns: 1fr;
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

  listings = signal<PublicListing[]>([]);
  loading = signal(false);
  errorMsg = signal('');

  searchTerm = '';
  selectedCategory = 'ALL';
  selectedSellerType: ListingSellerType | 'ALL' = 'ALL';
  selectedCondition: ListingCondition | 'ALL' = 'ALL';
  sortMode: ListingSort = 'newest';

  ngOnInit(): void {
    this.loading.set(true);
    this.listingService.getPublicListings().subscribe({
      next: listings => {
        this.listings.set(listings);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(this.publicListingLoadMessage(error));
        this.loading.set(false);
      },
    });
  }

  categoryOptions(): string[] {
    return [...new Set(this.listings().map(listing => listing.categoryName).filter(Boolean))].sort((a, b) => a.localeCompare(b));
  }

  selectCategory(category: string): void {
    this.selectedCategory = category;
  }

  featuredListing(): PublicListing | null {
    return this.listings()[0] || null;
  }

  filteredListings(): PublicListing[] {
    const term = this.searchTerm.trim().toLowerCase();
    const filtered = this.listings().filter(listing => {
      const searchable = [
        listing.title,
        listing.description,
        listing.categoryName,
        listing.publicCity || '',
        listing.publicRegion || '',
      ].join(' ').toLowerCase();

      return (!term || searchable.includes(term))
        && (this.selectedCategory === 'ALL' || listing.categoryName === this.selectedCategory)
        && (this.selectedSellerType === 'ALL' || listing.sellerType === this.selectedSellerType)
        && (this.selectedCondition === 'ALL' || listing.condition === this.selectedCondition);
    });

    return [...filtered].sort((left, right) => {
      if (this.sortMode === 'price_asc') {
        return Number(left.priceAmount) - Number(right.priceAmount);
      }
      if (this.sortMode === 'price_desc') {
        return Number(right.priceAmount) - Number(left.priceAmount);
      }
      return right.publishedAt.localeCompare(left.publishedAt) || right.id.localeCompare(left.id);
    });
  }

  locationLabel(listing: PublicListing): string {
    return [listing.publicCity, listing.publicRegion].filter(Boolean).join(', ') || 'Location not set';
  }

  conditionLabel(condition: ListingCondition): string {
    return condition.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
  }

  imageUrl(listing: PublicListing): string {
    const image = listing.images[0];
    return this.listingService.mediaUrl(image?.url || image?.uploadUrl);
  }

  private publicListingLoadMessage(error: { status?: number }): string {
    return error.status
      ? `Approved listings could not be loaded. HTTP ${error.status}.`
      : 'Approved listings could not be loaded.';
  }
}
