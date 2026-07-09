import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { BrandLoadingScreenComponent } from '../../shared/components/ui/brand-loading-screen.component';
import {
  publicListingConditionLabel,
  publicListingLocationLabel,
  publicListingOwnerLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';
import { MarketplaceProductCardComponent } from '../marketplace/components/marketplace-product-card.component';
import { MarketplaceUiProduct } from '../marketplace/components/marketplace-ui.model';

@Component({
  selector: 'app-liked-listings',
  standalone: true,
  imports: [BrandLoadingScreenComponent, MarketplaceProductCardComponent, RouterLink],
  template: `
    <section class="liked-page">
      <header class="liked-header">
        <div>
          <p class="eyebrow">Saved by likes</p>
          <h1>Liked Listings</h1>
          <p>Listings you liked stay here while they are still active and public.</p>
        </div>
        <a routerLink="/marketplace">Browse marketplace</a>
      </header>

      @if (loading()) {
        <app-brand-loading-screen
          title="Loading liked listings"
          detail="Gathering the marketplace finds you liked."
        />
      } @else if (errorMsg()) {
        <div class="state-panel error-state">
          <strong>Liked listings could not be loaded.</strong>
          <p>{{ errorMsg() }}</p>
        </div>
      } @else if (likedListings().length === 0) {
        <div class="state-panel">
          <strong>No liked listings yet</strong>
          <p>Tap Like on an active listing and it will appear here.</p>
          <a routerLink="/marketplace">Find listings</a>
        </div>
      } @else {
        <div class="liked-grid" aria-label="Liked listings">
          @for (listing of likedListings(); track listing.id) {
            <app-marketplace-product-card
              [product]="productFor(listing)"
              [detailLink]="['/listings', listing.id]"
            />
          }
        </div>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .liked-page {
      display: grid;
      gap: 1.2rem;
      max-width: 1180px;
      margin: 0 auto;
    }

    .liked-header,
    .state-panel {
      border: 1px solid var(--market-line);
      border-radius: 20px;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 16px 34px rgba(143, 92, 144, 0.1);
    }

    .liked-header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: flex-start;
      padding: clamp(1rem, 3vw, 1.5rem);
    }

    .eyebrow {
      margin: 0;
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 900;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    h1 {
      margin: 0.25rem 0;
      color: var(--market-ink);
      font-family: var(--font-display);
      font-size: clamp(1.8rem, 5vw, 2.8rem);
      letter-spacing: 0;
    }

    p {
      margin: 0;
      color: var(--market-muted);
      line-height: 1.5;
      font-weight: 750;
    }

    a {
      color: inherit;
      text-decoration: none;
    }

    .liked-header > a,
    .state-panel a {
      min-height: 42px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 1rem;
      border: 1px solid var(--market-line);
      border-radius: 12px;
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      font-weight: 900;
      white-space: nowrap;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.16);
    }

    .liked-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
      gap: 1rem;
    }

    .state-panel {
      min-height: 260px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 0.65rem;
      padding: 1.25rem;
      text-align: center;
    }

    .state-panel strong {
      color: var(--market-ink);
      font-size: 1.2rem;
      font-weight: 950;
    }

    .error-state {
      border-color: rgba(214, 68, 119, 0.34);
      background: rgba(255, 245, 249, 0.94);
    }

    @media (max-width: 680px) {
      .liked-header {
        display: grid;
      }

      .liked-header > a {
        width: 100%;
      }
    }
  `],
})
export class LikedListingsComponent implements OnInit {
  private readonly listingService = inject(ListingService);

  likedListings = signal<PublicListing[]>([]);
  loading = signal(false);
  errorMsg = signal('');

  ngOnInit(): void {
    this.loadLikedListings();
  }

  loadLikedListings(): void {
    this.loading.set(true);
    this.errorMsg.set('');
    this.listingService.getMyLikedListings().subscribe({
      next: listings => {
        this.likedListings.set(listings);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(`HTTP ${error?.status || 'error'}.`);
        this.loading.set(false);
      },
    });
  }

  productFor(listing: PublicListing): MarketplaceUiProduct {
    const firstImage = listing.images[0];
    return {
      id: listing.id,
      title: listing.title,
      sellerName: publicListingOwnerLabel(listing),
      priceAmount: listing.priceAmount,
      currency: listing.currency,
      categoryName: listing.categoryName,
      conditionLabel: publicListingConditionLabel(listing.condition),
      locationLabel: publicListingLocationLabel(listing),
      imageUrl: firstImage ? publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url)) : null,
      imageAlt: firstImage?.altText || listing.title,
      badge: this.productBadge(listing.id),
      favoriteCount: listing.likeCount || 0,
      visitCount: listing.visitCount || 0,
    };
  }

  private productBadge(id: string): 'NEW' | 'HOT' | 'SALE' {
    const badges = ['NEW', 'HOT', 'SALE'] as const;
    return badges[id.length % badges.length];
  }
}
