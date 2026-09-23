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
      <div class="favorites-page-art" aria-hidden="true"></div>

      <header class="liked-hero">
        <div class="liked-hero-copy">
          <p class="eyebrow">Saved collection</p>
          <h1>Liked Listings</h1>
          <p>Keep the things that caught your eye. They’ll stay here while they are active and public.</p>
          <a class="primary-link" routerLink="/marketplace">Browse marketplace <span aria-hidden="true">→</span></a>
        </div>
        <p class="hero-motto" aria-hidden="true">Treasures worth<br />returning to</p>
      </header>

      @if (loading()) {
        <div class="state-surface loading-surface">
          <app-brand-loading-screen
            title="Loading liked listings"
            detail="Gathering the marketplace finds you liked."
          />
        </div>
      } @else if (errorMsg()) {
        <div class="state-panel error-state" role="alert">
          <span class="state-sigil" aria-hidden="true">✦</span>
          <strong>Liked listings could not be loaded.</strong>
          <p>{{ errorMsg() }}</p>
          <button type="button" (click)="loadLikedListings()">Try again</button>
        </div>
      } @else if (likedListings().length === 0) {
        <div class="state-panel">
          <span class="state-sigil" aria-hidden="true">✦</span>
          <p class="eyebrow">Your collection</p>
          <strong>Nothing saved yet</strong>
          <p>Like listings while browsing and they’ll appear here.</p>
          <a class="primary-link" routerLink="/marketplace">Browse marketplace <span aria-hidden="true">→</span></a>
        </div>
      } @else {
        <section
          class="collection-surface"
          [class.single-collection]="likedListings().length === 1"
          [class.compact-collection]="likedListings().length > 1 && likedListings().length <= 3"
          aria-labelledby="saved-pieces-title"
        >
          <header class="collection-heading">
            <div>
              <p class="eyebrow">Private collection</p>
              <h2 id="saved-pieces-title">Your saved pieces</h2>
            </div>
            <p class="saved-count">
              <strong>{{ likedListings().length }}</strong>
              {{ likedListings().length === 1 ? 'saved piece' : 'saved pieces' }}
            </p>
          </header>

          <div class="collection-layout">
            <div class="liked-grid" aria-label="Liked listings">
              @for (listing of likedListings(); track listing.id) {
                <div class="favorite-item">
                  <span class="favorite-mark" aria-hidden="true">♥</span>
                  <app-marketplace-product-card
                    [product]="productFor(listing)"
                    [detailLink]="['/listings', listing.id]"
                  />
                </div>
              }
            </div>

            @if (likedListings().length === 1) {
              <aside class="collection-note" aria-label="Saved collection summary">
                <span class="crystal-sigil" aria-hidden="true">✦</span>
                <p class="eyebrow">Your collection</p>
                <h3>One saved treasure</h3>
                <p>Keep exploring the marketplace and save anything you want to revisit.</p>
                <a class="primary-link" routerLink="/marketplace">Browse marketplace <span aria-hidden="true">→</span></a>
                <p class="note-motto" aria-hidden="true">Good things are always worth coming back to.</p>
              </aside>
            }
          </div>
        </section>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      min-height: calc(100vh - 66px);
    }

    .liked-page {
      --favorites-midnight: #18233f;
      --favorites-sapphire: #496f9f;
      --favorites-plum: #47243d;
      --favorites-rose: #c62d6b;
      --favorites-pearl: #fff8f8;
      position: relative;
      z-index: 1;
      display: grid;
      gap: 1.15rem;
      width: min(100%, 1500px);
      margin: 0 auto;
      padding: clamp(0.75rem, 1.6vw, 1.4rem) 0 3.5rem;
      isolation: isolate;
    }

    .favorites-page-art {
      position: fixed;
      z-index: -2;
      inset: 66px 0 0;
      background:
        linear-gradient(90deg, rgba(27, 39, 70, 0.05), rgba(255, 247, 249, 0.2) 27%, rgba(255, 249, 248, 0.3) 70%, rgba(35, 48, 82, 0.08)),
        url('/assets/brand/anime/favorites-moonlit-environment-v1.webp') center top / cover no-repeat;
      background-color: #e8dce7;
      pointer-events: none;
    }

    .liked-hero {
      min-height: clamp(300px, 24vw, 370px);
      position: relative;
      display: flex;
      overflow: hidden;
      align-items: center;
      padding: clamp(1.6rem, 4vw, 4rem);
      border: 1px solid rgba(255, 255, 255, 0.58);
      border-radius: 30px 8px 30px 8px;
      background:
        linear-gradient(90deg, rgba(255, 248, 249, 0.6) 0%, rgba(255, 246, 248, 0.34) 31%, rgba(255, 241, 246, 0.06) 53%, transparent 68%),
        url('/assets/brand/anime/favorites-snow-queen-hero-v1.webp') center / cover no-repeat;
      box-shadow: 0 24px 58px rgba(45, 43, 74, 0.16);
      animation: favorites-arrive 680ms cubic-bezier(.18,.8,.22,1) both;
    }

    .liked-hero::after {
      position: absolute;
      inset: 0;
      border-radius: inherit;
      box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.34);
      content: '';
      pointer-events: none;
    }

    .liked-hero-copy {
      width: min(46%, 590px);
      position: relative;
      z-index: 1;
      isolation: isolate;
    }

    .liked-hero-copy::before {
      position: absolute;
      z-index: -1;
      inset: -2rem -4.5rem -2rem -2rem;
      border-radius: 28px;
      background: radial-gradient(ellipse at left, rgba(255, 249, 249, 0.48), rgba(255, 246, 248, 0.2) 54%, transparent 78%);
      content: '';
      pointer-events: none;
    }

    .eyebrow {
      margin: 0;
      color: var(--favorites-rose);
      font-family: var(--font-market-utility);
      font-size: 0.72rem;
      font-weight: 800;
      letter-spacing: 0.14em;
      text-transform: uppercase;
    }

    h1 {
      margin: 0.3rem 0 0.7rem;
      color: var(--favorites-plum);
      font-family: var(--font-market-display);
      font-size: clamp(3.2rem, 5vw, 5.6rem);
      font-weight: 520;
      line-height: 0.95;
    }

    .liked-hero-copy > p:not(.eyebrow) {
      max-width: 510px;
      margin: 0;
      color: #715365;
      font-size: clamp(0.94rem, 1.05vw, 1.08rem);
      line-height: 1.65;
    }

    a {
      color: inherit;
      text-decoration: none;
    }

    .primary-link,
    .state-panel button {
      min-height: 46px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.7rem;
      margin-top: 1.15rem;
      padding: 0 1.25rem;
      border: 1px solid rgba(255, 255, 255, 0.44);
      border-radius: 14px 5px 14px 5px;
      background: linear-gradient(135deg, #a92358, #dc3f7e);
      color: #fff;
      font: inherit;
      font-weight: 780;
      white-space: nowrap;
      box-shadow: 0 14px 28px rgba(137, 35, 77, 0.2);
      cursor: pointer;
      transition: transform 180ms ease, box-shadow 180ms ease;
    }

    .primary-link:hover,
    .state-panel button:hover {
      box-shadow: 0 18px 34px rgba(137, 35, 77, 0.27);
      transform: translateY(-2px);
    }

    .primary-link:focus-visible,
    .state-panel button:focus-visible {
      outline: 3px solid rgba(72, 110, 158, 0.42);
      outline-offset: 3px;
    }

    .hero-motto {
      position: absolute;
      z-index: 1;
      top: 2rem;
      right: 2.2rem;
      margin: 0;
      color: rgba(244, 234, 246, 0.88);
      font-family: var(--font-market-display);
      font-size: 0.72rem;
      line-height: 1.75;
      letter-spacing: 0.16em;
      text-align: center;
      text-transform: uppercase;
      text-shadow: 0 2px 12px rgba(13, 23, 49, 0.7);
    }

    .collection-surface,
    .state-panel,
    .state-surface {
      border: 1px solid rgba(255, 255, 255, 0.5);
      border-radius: 28px 8px 28px 8px;
      background: rgba(255, 248, 249, 0.27);
      box-shadow: 0 18px 42px rgba(49, 42, 70, 0.11);
      backdrop-filter: blur(7px) saturate(1.05);
      animation: favorites-arrive 680ms 80ms cubic-bezier(.18,.8,.22,1) both;
    }

    .collection-surface {
      margin-inline: clamp(0.25rem, 2.4vw, 2.25rem);
      padding: clamp(1rem, 2vw, 1.65rem);
    }

    .collection-heading {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.25rem 0.35rem 1.1rem;
      border-bottom: 1px solid rgba(128, 109, 142, 0.16);
    }

    .collection-heading h2 {
      margin: 0.15rem 0 0;
      color: var(--favorites-plum);
      font-family: var(--font-market-display);
      font-size: clamp(2rem, 3.3vw, 3.35rem);
      font-weight: 530;
      line-height: 1;
    }

    .saved-count {
      margin: 0;
      color: #745e6d;
      font-size: 0.82rem;
      white-space: nowrap;
    }

    .saved-count strong {
      color: var(--favorites-rose);
      font-family: var(--font-market-display);
      font-size: 1.4rem;
      font-weight: 600;
    }

    .collection-layout {
      display: block;
      padding-top: 1.15rem;
    }

    .single-collection .collection-layout {
      display: grid;
      grid-template-columns: minmax(280px, 455px) minmax(310px, 450px);
      justify-content: space-between;
      gap: clamp(1.5rem, 7vw, 7rem);
    }

    .liked-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(245px, 310px));
      justify-content: center;
      gap: 1.15rem;
    }

    .single-collection .liked-grid {
      grid-template-columns: minmax(0, 1fr);
      justify-content: stretch;
    }

    .favorite-item {
      min-width: 0;
      position: relative;
    }

    .favorite-mark {
      width: 40px;
      height: 40px;
      position: absolute;
      z-index: 4;
      top: 0.85rem;
      right: 0.85rem;
      display: grid;
      place-items: center;
      border: 1px solid rgba(255, 255, 255, 0.78);
      border-radius: 50%;
      background: rgba(255, 249, 250, 0.72);
      color: #d82e70;
      font-size: 1.15rem;
      box-shadow: 0 10px 22px rgba(74, 38, 67, 0.2);
      pointer-events: none;
    }

    .collection-note {
      min-height: 100%;
      position: relative;
      display: flex;
      overflow: hidden;
      flex-direction: column;
      justify-content: center;
      align-items: flex-start;
      padding: clamp(1.5rem, 3vw, 2.5rem);
      border: 1px solid rgba(255, 255, 255, 0.62);
      border-radius: 26px 7px 26px 7px;
      background:
        linear-gradient(145deg, rgba(255, 249, 249, 0.42), rgba(236, 224, 243, 0.2)),
        url('/assets/brand/anime/favorites-moonlit-environment-v1.webp') 88% center / cover no-repeat;
      box-shadow: 0 16px 34px rgba(48, 44, 75, 0.12);
      backdrop-filter: blur(5px) saturate(1.05);
    }

    .collection-note::after {
      position: absolute;
      right: -3rem;
      bottom: -4rem;
      width: 13rem;
      height: 13rem;
      border: 1px solid rgba(73, 111, 159, 0.16);
      border-radius: 50%;
      content: '';
      box-shadow: 0 0 60px rgba(73, 111, 159, 0.18);
      pointer-events: none;
    }

    .crystal-sigil {
      position: absolute;
      top: 1.2rem;
      right: 1.35rem;
      color: var(--favorites-sapphire);
      font-size: 2.2rem;
      text-shadow: 0 0 20px rgba(92, 138, 190, 0.5);
    }

    .collection-note h3 {
      margin: 0.25rem 0 0.65rem;
      color: var(--favorites-plum);
      font-family: var(--font-market-display);
      font-size: clamp(2rem, 3vw, 3.2rem);
      font-weight: 530;
      line-height: 1;
    }

    .collection-note > p:not(.eyebrow):not(.note-motto) {
      max-width: 330px;
      margin: 0;
      color: #6f5969;
      line-height: 1.6;
    }

    .note-motto {
      max-width: 250px;
      margin: auto 0 0;
      padding-top: 2.6rem;
      color: #78647d;
      font-family: var(--font-market-display);
      font-size: 0.86rem;
      font-style: italic;
      line-height: 1.55;
    }

    .state-panel {
      min-height: 390px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 0.6rem;
      margin-inline: clamp(0.25rem, 5vw, 6rem);
      padding: clamp(1.5rem, 4vw, 3rem);
      text-align: center;
      background:
        radial-gradient(circle at center, rgba(255, 250, 250, 0.64), rgba(255, 246, 249, 0.42) 58%, rgba(229, 219, 238, 0.28)),
        url('/assets/brand/anime/favorites-moonlit-environment-v1.webp') center / cover no-repeat;
    }

    .state-panel strong {
      color: var(--favorites-plum);
      font-family: var(--font-market-display);
      font-size: clamp(2rem, 4vw, 3.6rem);
      font-weight: 530;
    }

    .state-panel > p:not(.eyebrow) {
      margin: 0;
      color: #715b6b;
      line-height: 1.55;
    }

    .state-sigil {
      color: var(--favorites-sapphire);
      font-size: 2.5rem;
      text-shadow: 0 0 24px rgba(73, 111, 159, 0.44);
    }

    .loading-surface {
      min-height: 300px;
      display: grid;
      place-items: center;
      margin-inline: clamp(0.25rem, 3vw, 3rem);
      padding: 1.5rem;
    }

    .error-state {
      border-color: rgba(193, 56, 103, 0.32);
    }

    @keyframes favorites-arrive {
      from { opacity: 0; transform: translateY(14px); }
      to { opacity: 1; transform: translateY(0); }
    }

    @media (max-width: 980px) {
      .liked-hero-copy { width: min(55%, 520px); }
      .hero-motto { display: none; }
      .single-collection .collection-layout {
        grid-template-columns: minmax(260px, 1fr) minmax(270px, 0.85fr);
        gap: 1.25rem;
      }
    }

    @media (max-width: 760px) {
      .liked-page { padding-top: 0.65rem; }
      .favorites-page-art {
        background-image:
          linear-gradient(180deg, rgba(255, 245, 249, 0.08), rgba(255, 248, 249, 0.38)),
          url('/assets/brand/anime/favorites-moonlit-environment-v1.webp');
        background-position: 58% top;
      }

      .liked-hero {
        min-height: 520px;
        align-items: end;
        padding: 1.3rem;
        background:
          linear-gradient(180deg, transparent 24%, rgba(255, 246, 248, 0.12) 44%, rgba(255, 248, 249, 0.62) 68%, rgba(255, 249, 249, 0.76) 100%),
          url('/assets/brand/anime/favorites-snow-queen-hero-v1.webp') 71% top / auto 67% no-repeat,
          rgba(33, 46, 79, 0.22);
      }

      .liked-hero-copy { width: 100%; }
      .liked-hero-copy::before {
        inset: -3.2rem -1.3rem -1.3rem;
        background: linear-gradient(180deg, transparent, rgba(255, 248, 249, 0.34) 34%, rgba(255, 249, 249, 0.56) 100%);
      }
      h1 { font-size: clamp(3.25rem, 14vw, 4.75rem); }
      .primary-link { width: 100%; }
      .collection-surface { margin-inline: 0; padding: 0.85rem; }
      .collection-heading { align-items: flex-start; flex-direction: column; }
      .single-collection .collection-layout { display: block; }
      .liked-grid { grid-template-columns: minmax(0, 1fr); }
      .collection-note { min-height: 320px; margin-top: 1rem; }
      .state-panel { min-height: 360px; margin-inline: 0; }
    }

    @media (prefers-reduced-motion: reduce) {
      .liked-hero,
      .collection-surface,
      .state-panel,
      .state-surface { animation: none; }
      .primary-link,
      .state-panel button { transition: none; }
      .primary-link:hover,
      .state-panel button:hover { transform: none; }
    }

    @media (prefers-reduced-transparency: reduce) {
      .collection-surface,
      .state-panel,
      .state-surface {
        background-color: #fff8f8;
        backdrop-filter: none;
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
