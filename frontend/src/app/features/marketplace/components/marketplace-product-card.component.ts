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
          <span>{{ product.categoryName }}</span>
        }
      </div>

      <div class="product-body">
        <div class="product-pill-row" aria-label="Listing quick facts">
          <span class="condition-pill" [class.hot]="product.badge === 'HOT'" [class.sale]="product.badge === 'SALE'">
            {{ conditionPillLabel() }}
          </span>
          <span class="stat-pill" [attr.aria-label]="product.favoriteCount + ' likes'">♡ {{ product.favoriteCount }}</span>
          <span class="stat-pill" [attr.aria-label]="product.visitCount + ' views'">👁 {{ product.visitCount }}</span>
        </div>

        <div class="product-location-row">
          <span class="local-badge">Local</span>
          <span class="location-name" [attr.title]="product.locationLabel">📍 {{ product.locationLabel }}</span>
        </div>

        <p class="category-name">{{ product.categoryName }}</p>
        <h3>{{ product.title }}</h3>
        <p class="seller-name">by {{ product.sellerName }}</p>

        <div class="product-foot">
          <strong>{{ product.priceAmount | number: '1.2-2' }} {{ product.currency }}</strong>
          <span>Details</span>
        </div>

        <p class="trade-note">Off-platform trade</p>
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
  `],
})
export class MarketplaceProductCardComponent {
  @Input({ required: true }) product!: MarketplaceUiProduct;
  @Input() detailLink: string | unknown[] = '/marketplace';

  conditionPillLabel(): string {
    const allowed = new Set(['New', 'Like New', 'Open Box', 'Good']);
    return allowed.has(this.product.conditionLabel) ? this.product.conditionLabel : 'Good';
  }

  placeholderGradient(): string {
    const gradients = [
      'linear-gradient(135deg, #ffd5ea, #eee6ff)',
      'linear-gradient(135deg, #ffe3f4, #dfeaff)',
      'linear-gradient(135deg, #f8d8ff, #fff2c7)',
      'linear-gradient(135deg, #ffd7e8, #ddfff4)',
    ];
    return gradients[this.product.id.length % gradients.length];
  }
}
