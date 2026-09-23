import { DecimalPipe } from '@angular/common';
import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MarketplaceUiProduct } from './marketplace-ui.model';

@Component({
  selector: 'app-marketplace-product-card',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <a class="product-card listing-card" [routerLink]="detailLink" [attr.aria-label]="'View ' + product.title">
      <div class="product-image listing-image" [style.--placeholder]="placeholderGradient()">
        @if (product.imageUrl) {
          <img [src]="product.imageUrl" [alt]="product.imageAlt" />
        } @else {
          <span class="no-image-state">
            <img src="/assets/brand/anime/shopping-bag-fallback.svg" alt="" aria-hidden="true" />
            <span>No image · {{ product.categoryName }}</span>
          </span>
        }
        <span class="seller-type-badge">Individual</span>
      </div>

      <div class="product-body">
        <div class="product-pill-row" aria-label="Listing category and condition">
          <span class="category-name">{{ product.categoryName }}</span>
          <span class="condition-pill">{{ conditionPillLabel() }}</span>
        </div>
        <h3>{{ product.title }}</h3>
        <p class="seller-name">by {{ product.sellerName }}</p>
        <p class="location-name" [attr.title]="product.locationLabel">{{ product.locationLabel }}</p>

        <div class="product-foot">
          <strong>{{ product.priceAmount | number: '1.2-2' }} {{ product.currency }}</strong>
          <span>View listing</span>
        </div>

        <div class="engagement-summary" aria-label="Listing engagement">
          <span>{{ product.favoriteCount }} likes</span>
          <span>{{ product.visitCount }} views</span>
        </div>

        <p class="trade-note">Payment arranged with seller</p>
      </div>
    </a>
  `,
  styles: [`
    .product-card {
      min-width: 0;
      height: 100%;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 20px;
      background: rgba(255, 255, 255, 0.96);
      color: inherit;
      text-decoration: none;
      box-shadow: 0 16px 34px rgba(143, 92, 144, 0.11);
      transition: transform 220ms ease, box-shadow 220ms ease, border-color 220ms ease;
    }

    .product-card:hover {
      border-color: rgba(244, 114, 182, 0.48);
      box-shadow: 0 22px 60px rgba(159, 91, 144, 0.22);
      transform: translateY(-4px);
    }

    .product-image {
      position: relative;
      display: grid;
      place-items: center;
      aspect-ratio: 16 / 11;
      flex: 0 0 auto;
      overflow: hidden;
      margin: 0.55rem 0.55rem 0;
      border-radius: 18px;
      background: var(--placeholder, linear-gradient(135deg, #ffe5f0, #efe8ff));
      color: var(--market-muted);
      font-weight: 950;
      text-align: center;
    }

    .product-image img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
      transition: transform 260ms ease;
    }

    .product-card:hover .product-image img {
      transform: scale(1.035);
    }

    .product-image > span:first-child {
      padding: 1rem;
    }

    .product-body {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 0.52rem;
      padding: 0.9rem;
    }

    .product-pill-row {
      display: flex;
      flex-wrap: nowrap;
      gap: 0.38rem;
      align-items: center;
      min-width: 0;
    }

    .condition-pill,
    .stat-pill,
    .local-badge {
      min-height: 26px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(255, 255, 255, 0.86);
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.9);
      font-size: 0.7rem;
      font-weight: 950;
      line-height: 1;
      padding: 0 0.62rem;
      box-shadow: 0 6px 14px rgba(143, 92, 144, 0.12);
    }

    .condition-pill {
      max-width: min(46%, 6.5rem);
      color: var(--market-purple, #8b6fe8);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .condition-pill.hot {
      color: #e56c37;
    }

    .condition-pill.sale {
      color: #d93886;
    }

    .stat-pill {
      color: var(--market-accent-dark);
      flex: 0 0 auto;
    }

    .local-badge {
      flex: 0 0 auto;
      color: var(--market-success, #38a895);
    }

    .product-location-row {
      min-width: 0;
      display: flex;
      align-items: center;
      gap: 0.42rem;
    }

    .location-name {
      min-width: 0;
      display: block;
      overflow: hidden;
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 850;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .product-foot {
      display: flex;
      justify-content: space-between;
      gap: 0.75rem;
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 850;
    }

    .category-name {
      min-width: 0;
      overflow: hidden;
      color: var(--market-accent-dark);
      font-size: 0.76rem;
      font-weight: 900;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    h3 {
      margin: 0;
      color: var(--market-ink);
      font-size: 1rem;
      line-height: 1.2;
      letter-spacing: 0;
      overflow-wrap: anywhere;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    p {
      margin: 0;
      color: var(--market-muted);
      font-size: 0.84rem;
      font-weight: 750;
    }

    .seller-name {
      color: var(--market-lavender);
      font-weight: 900;
      margin-top: auto;
    }

    .product-foot {
      align-items: center;
      padding-top: 0.45rem;
      border-top: 1px solid var(--market-line);
    }

    .product-foot strong {
      color: var(--market-ink);
      font-size: 1.05rem;
      font-weight: 950;
    }

    .product-foot span {
      min-height: 30px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.7rem;
      border-radius: 999px;
      background: linear-gradient(135deg, #ff8fc4, #9c83ef);
      color: #fff;
      white-space: nowrap;
    }

    .trade-note {
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 850;
    }

    /* Shared individual-listing card system. */
    .product-card {
      border-color: var(--market-line);
      border-radius: var(--market-radius-md);
      background: var(--market-surface);
      box-shadow: var(--market-shadow-sm);
      transition: transform 160ms ease, box-shadow 160ms ease, border-color 160ms ease;
    }

    .product-card:hover {
      border-color: var(--market-line-strong);
      box-shadow: var(--market-shadow-md);
      transform: translateY(-2px);
    }

    .product-image {
      aspect-ratio: 4 / 3;
      margin: 0;
      border-radius: 0;
      background: var(--placeholder, linear-gradient(135deg, #e5eeeb, #f4f7f6));
      color: var(--market-muted);
      font-weight: 700;
    }

    .seller-type-badge {
      position: absolute;
      top: 0.7rem;
      left: 0.7rem;
      min-height: 28px;
      display: inline-flex;
      align-items: center;
      border: 1px solid rgba(255, 255, 255, 0.72);
      border-radius: 999px;
      background: rgba(20, 47, 50, 0.9);
      color: #fff;
      font-size: 0.72rem;
      font-weight: 800;
      padding: 0 0.65rem;
      backdrop-filter: blur(8px);
    }

    .product-body {
      gap: 0.42rem;
      padding: 0.9rem;
    }

    .product-pill-row {
      flex-wrap: wrap;
      justify-content: space-between;
    }

    .category-name,
    .condition-pill {
      min-height: auto;
      max-width: 55%;
      padding: 0;
      border: 0;
      border-radius: 0;
      background: transparent;
      box-shadow: none;
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 750;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .condition-pill {
      max-width: 42%;
      color: var(--market-accent-dark);
    }

    h3 {
      min-height: 2.5em;
      color: var(--market-ink);
      font-size: 1rem;
      font-weight: 760;
      line-height: 1.25;
    }

    .seller-name {
      margin-top: 0;
      color: var(--market-ink);
      font-weight: 650;
    }

    .location-name {
      min-width: 0;
      overflow: hidden;
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 600;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .product-foot {
      align-items: center;
      margin-top: 0.2rem;
      padding-top: 0.7rem;
      border-top-color: var(--market-line);
    }

    .product-foot strong {
      color: var(--market-ink);
      font-size: 1.1rem;
      font-variant-numeric: tabular-nums;
      font-weight: 820;
    }

    .product-foot span {
      min-height: auto;
      padding: 0;
      border-radius: 0;
      background: transparent;
      color: var(--market-accent-dark);
      font-weight: 750;
    }

    .engagement-summary {
      display: flex;
      gap: 0.75rem;
      color: var(--market-muted);
      font-size: 0.72rem;
    }

    .trade-note {
      margin-top: 0.25rem;
      color: var(--market-coral);
      font-size: 0.72rem;
      font-weight: 750;
    }

    /* Paper-tag cards: expressive enough for browsing, restrained around product facts. */
    .product-card {
      border-color: rgba(233,184,205,.82);
      border-radius: 22px 7px 22px 7px;
      background: rgba(255,255,255,.97);
      box-shadow: 0 10px 28px rgba(208,75,126,.08);
    }

    .product-card:hover {
      border-color: rgba(233,79,138,.46);
      box-shadow: 0 20px 42px rgba(208,75,126,.14);
      transform: translateY(-4px);
    }

    .product-image {
      aspect-ratio: 5 / 4;
      margin: .55rem .55rem 0;
      border-radius: 17px 5px 17px 5px;
      background: var(--placeholder, linear-gradient(135deg, #fff0f6, #fce8f5));
    }

    .no-image-state {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      align-content: center;
      gap: .1rem;
      padding: .4rem;
    }

    .no-image-state img {
      width: 58%;
      height: 70%;
      object-fit: contain;
      opacity: .86;
    }

    .no-image-state span {
      color: var(--market-muted);
      font-size: .7rem;
      font-weight: 750;
    }

    .seller-type-badge {
      border-color: rgba(255,255,255,.66);
      background: rgba(217,46,114,.94);
      font-size: .66rem;
      letter-spacing: .05em;
      text-transform: uppercase;
    }

    .product-foot strong {
      color: var(--market-accent-dark);
      font-family: var(--font-market-display);
      font-size: 1.28rem;
      font-weight: 650;
    }

    .product-foot span {
      color: var(--market-accent-dark);
    }

    .product-body { padding: .95rem 1rem 1rem; }
    .category-name, .condition-pill { font-family: var(--font-market-utility); letter-spacing: .08em; }
    h3 { min-height: 2.55em; font-size: 1.02rem; font-weight: 690; line-height: 1.28; }
    .seller-name { color: #7d5268; }
    .trade-note { color: #a25072; font-weight: 650; }

    @media (prefers-reduced-motion: reduce) {
      .product-card,
      .product-image img { transition: none; }
      .product-card:hover,
      .product-card:hover .product-image img { transform: none; }
    }
  `],
})
export class MarketplaceProductCardComponent {
  @Input({ required: true }) product!: MarketplaceUiProduct;
  @Input() detailLink: string | unknown[] = '/marketplace';

  conditionPillLabel(): string {
    return this.product.conditionLabel || 'Condition not provided';
  }

  placeholderGradient(): string {
    const gradients = [
      'linear-gradient(135deg, #eadde1, #f5efed)',
      'linear-gradient(135deg, #e7e4ec, #f7f1ee)',
      'linear-gradient(135deg, #e5ece9, #f3ecec)',
      'linear-gradient(135deg, #eee1e5, #e9e7ef)',
    ];
    return gradients[this.product.id.length % gradients.length];
  }
}
