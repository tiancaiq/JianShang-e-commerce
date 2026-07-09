import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ListingCondition } from '../../../core/models/listing.model';

@Component({
  selector: 'app-marketplace-filter-panel',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="filter-panel" aria-label="Filter marketplace listings">
      <div class="filter-heading">
        <div>
          <span>Filter by</span>
          <strong>Cute finds</strong>
        </div>
        @if (hasActiveSearch) {
          <button type="button" class="clear-button" (click)="clearFilters.emit()">Clear</button>
        }
      </div>

      <form class="filter-stack" (submit)="applyFilters.emit(); $event.preventDefault()">
        <fieldset>
          <legend>Condition</legend>
          <label>
            <span>Item condition</span>
            <select name="condition" [ngModel]="selectedCondition" (ngModelChange)="selectedConditionChange.emit($event)">
              <option value="ALL">Any condition</option>
              <option value="NEW">New</option>
              <option value="OPEN_BOX">Open box</option>
              <option value="LIKE_NEW">Like new</option>
              <option value="GOOD">Good</option>
              <option value="FAIR">Fair</option>
              <option value="FOR_PARTS">For parts</option>
            </select>
          </label>
        </fieldset>

        <fieldset>
          <legend>Price range</legend>
          <div class="price-fields">
            <label>
              <span>Min price</span>
              <input name="minPrice" type="number" min="0" inputmode="decimal" [ngModel]="minPrice" (ngModelChange)="minPriceChange.emit($event)" />
            </label>

            <label>
              <span>Max price</span>
              <input name="maxPrice" type="number" min="0" inputmode="decimal" [ngModel]="maxPrice" (ngModelChange)="maxPriceChange.emit($event)" />
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend>Location</legend>
          <label>
            <span>City</span>
            <input name="city" type="search" [ngModel]="city" (ngModelChange)="cityChange.emit($event)" placeholder="Irvine" />
          </label>

          <label>
            <span>County</span>
            <input name="county" type="search" [ngModel]="county" (ngModelChange)="countyChange.emit($event)" placeholder="Orange County" />
          </label>
        </fieldset>

        <button type="submit" class="apply-button">Apply filters</button>
      </form>
    </section>
  `,
  styles: [`
    .filter-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 24px;
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 18px 44px rgba(159, 91, 144, 0.13);
    }

    .filter-heading {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding-bottom: 0.85rem;
      border-bottom: 1px solid var(--market-line);
    }

    .filter-heading div {
      display: grid;
      gap: 0.15rem;
    }

    .filter-heading span,
    legend,
    label span {
      color: var(--market-muted);
      font-size: 0.76rem;
      font-weight: 900;
      letter-spacing: 0.04em;
      text-transform: uppercase;
    }

    .filter-heading strong {
      color: var(--market-ink);
      font-size: 1.1rem;
      font-weight: 950;
    }

    .filter-stack {
      display: grid;
      gap: 1rem;
    }

    fieldset {
      display: grid;
      gap: 0.65rem;
      margin: 0;
      padding: 0;
      border: 0;
    }

    label {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    input,
    select {
      width: 100%;
      min-height: 44px;
      border: 1px solid var(--market-line);
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.94);
      color: var(--market-ink);
      padding: 0 0.9rem;
      font: inherit;
      font-weight: 750;
      outline: none;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
      transition: border-color 220ms ease, box-shadow 220ms ease;
    }

    input:focus,
    select:focus,
    button:focus {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 4px rgba(244, 114, 182, 0.16);
    }

    button {
      min-height: 44px;
      border: 1px solid var(--market-line);
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.92);
      color: var(--market-accent-dark);
      cursor: pointer;
      font: inherit;
      font-weight: 950;
      padding: 0 1rem;
      transition: transform 220ms ease, box-shadow 220ms ease;
    }

    button:hover {
      transform: translateY(-1px);
    }

    .clear-button {
      min-height: 34px;
      padding: 0 0.75rem;
      font-size: 0.8rem;
    }

    .apply-button {
      border: 0;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      box-shadow: 0 14px 28px rgba(190, 58, 131, 0.2);
    }

    .price-fields {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.75rem;
    }

    @media (max-width: 640px) {
      .price-fields {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class MarketplaceFilterPanelComponent {
  @Input() hasActiveSearch = false;
  @Input() selectedCondition: ListingCondition | 'ALL' = 'ALL';
  @Input() minPrice: string | number = '';
  @Input() maxPrice: string | number = '';
  @Input() city = '';
  @Input() county = '';

  @Output() selectedConditionChange = new EventEmitter<ListingCondition | 'ALL'>();
  @Output() minPriceChange = new EventEmitter<string | number>();
  @Output() maxPriceChange = new EventEmitter<string | number>();
  @Output() cityChange = new EventEmitter<string>();
  @Output() countyChange = new EventEmitter<string>();
  @Output() applyFilters = new EventEmitter<void>();
  @Output() clearFilters = new EventEmitter<void>();
}
