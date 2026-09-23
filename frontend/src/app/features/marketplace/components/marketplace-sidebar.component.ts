import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Category } from '../../../core/models/listing.model';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-marketplace-sidebar',
  standalone: true,
  template: `
    <aside class="category-rail" aria-label="Marketplace categories">
      <div class="rail-title">
        <span>Explore</span>
        <strong>Collections</strong>
      </div>
      <button type="button" [class.active]="selectedCategoryId === 'ALL'" (click)="categorySelected.emit('ALL')">
        <span>All</span>
        <strong>All categories</strong>
      </button>
      @for (category of categories; track category.id) {
        <button type="button" [class.active]="selectedCategoryId === category.id" (click)="categorySelected.emit(category.id)">
          <span>{{ categoryShortLabel(category.name) }}</span>
          <strong>{{ category.name }}</strong>
        </button>
      }
      @if (aiAssistantEnabled) {
        <div class="help-card" aria-label="Marketplace support">
          <img src="/assets/brand/anime/assistant-avatar.webp" alt="MSB marketplace shopping guide" />
          <strong>Need help?</strong>
          <span>Support chat is coming soon.</span>
          <button type="button" disabled>Coming soon</button>
        </div>
      }
    </aside>
  `,
  styles: [`
    .category-rail {
      position: sticky;
      top: 96px;
      display: grid;
      gap: 0.55rem;
      align-self: start;
      max-height: calc(100dvh - 112px);
      overflow-y: auto;
      overscroll-behavior: contain;
      padding: 0.85rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 24px;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 18px 44px rgba(159, 91, 144, 0.12);
      scrollbar-color: rgba(244, 114, 182, 0.48) transparent;
      scrollbar-width: thin;
      z-index: 4;
    }

    .category-rail::-webkit-scrollbar {
      width: 8px;
    }

    .category-rail::-webkit-scrollbar-track {
      background: transparent;
    }

    .category-rail::-webkit-scrollbar-thumb {
      border: 2px solid rgba(255, 255, 255, 0.92);
      border-radius: 999px;
      background: rgba(244, 114, 182, 0.42);
    }

    .rail-title {
      display: grid;
      gap: 0.1rem;
      padding: 0.2rem 0.25rem 0.5rem;
    }

    .rail-title span,
    .help-card span,
    button span {
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 900;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    .rail-title strong,
    .help-card strong,
    button strong {
      color: var(--market-ink);
      font-weight: 950;
      line-height: 1.12;
    }

    button {
      width: 100%;
      min-height: 48px;
      display: grid;
      gap: 0.08rem;
      justify-items: start;
      border: 1px solid transparent;
      border-radius: 16px;
      background: transparent;
      color: var(--market-muted);
      cursor: pointer;
      font: inherit;
      text-align: left;
      padding: 0.55rem 0.7rem;
      transition: transform 220ms ease, background 220ms ease, border-color 220ms ease;
    }

    button.active,
    button:hover {
      transform: translateY(-1px);
      border-color: rgba(244, 114, 182, 0.28);
      background: linear-gradient(135deg, #fff0f7, #ede7ff);
    }

    button.active span,
    button:hover span {
      color: var(--market-accent-dark);
    }

    .help-card {
      display: grid;
      justify-items: center;
      gap: 0.42rem;
      margin-top: 0.35rem;
      padding: 1rem 0.75rem;
      border: 1px solid rgba(244, 114, 182, 0.24);
      border-radius: 18px;
      background:
        radial-gradient(circle at 18% 18%, rgba(255, 180, 217, 0.58), transparent 24%),
        radial-gradient(circle at 82% 82%, rgba(196, 181, 253, 0.52), transparent 28%),
        linear-gradient(155deg, #fff3fa 0%, #f7edff 54%, #ffe6f4 100%);
      color: var(--market-ink);
      text-align: center;
      box-shadow: 0 16px 32px rgba(190, 58, 131, 0.13);
      overflow: hidden;
      position: relative;
    }

    .help-card::before,
    .help-card::after {
      content: '';
      position: absolute;
      width: 7px;
      height: 7px;
      border-radius: 999px;
      background: rgba(236, 72, 153, 0.28);
      box-shadow: 22px 14px 0 rgba(167, 139, 250, 0.24), -16px 38px 0 rgba(244, 114, 182, 0.2);
    }

    .help-card::before {
      left: 1rem;
      top: 1rem;
    }

    .help-card::after {
      right: 1.1rem;
      bottom: 1.45rem;
    }

    .help-card img {
      width: 84px;
      height: 84px;
      display: block;
      object-fit: cover;
      border: 3px solid rgba(255, 255, 255, 0.86);
      border-radius: 24px;
      position: relative;
      z-index: 1;
      box-shadow: 0 12px 22px rgba(190, 58, 131, 0.16);
    }

    .help-card strong {
      position: relative;
      z-index: 1;
      color: #5a2f64;
      font-size: 0.94rem;
      line-height: 1.1;
    }

    .help-card span {
      position: relative;
      z-index: 1;
      max-width: 12ch;
      color: #8a668e;
      font-size: 0.7rem;
      letter-spacing: 0;
      line-height: 1.25;
      text-transform: none;
    }

    .help-card button {
      position: relative;
      z-index: 1;
      min-height: 30px;
      width: auto;
      justify-items: center;
      border: 1px solid rgba(244, 114, 182, 0.24);
      border-radius: 999px;
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(250, 232, 255, 0.84));
      color: rgba(176, 71, 137, 0.72);
      cursor: not-allowed;
      font-size: 0.72rem;
      padding: 0 0.85rem;
      box-shadow: 0 8px 18px rgba(190, 58, 131, 0.12);
      transform: none;
    }

    .help-card button:hover {
      transform: none;
      border-color: rgba(244, 114, 182, 0.24);
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(250, 232, 255, 0.84));
    }

    @media (max-width: 980px) {
      .category-rail {
        top: 84px;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        max-height: calc(100dvh - 96px);
      }

      .rail-title,
      .help-card {
        grid-column: 1 / -1;
      }
    }

    @media (max-width: 640px) {
      .category-rail {
        position: static;
        grid-template-columns: 1fr;
        max-height: none;
        overflow: visible;
      }
    }

    .category-rail {
      top: 90px;
      gap: 0.35rem;
      padding: 0.65rem;
      border-color: var(--market-line);
      border-radius: var(--market-radius-md);
      background: var(--market-surface);
      box-shadow: var(--market-shadow-sm);
      scrollbar-color: var(--market-line-strong) transparent;
    }

    .rail-title span,
    .help-card span,
    button span {
      color: var(--market-muted);
      font-weight: 750;
      letter-spacing: 0.06em;
    }

    .rail-title strong,
    .help-card strong,
    button strong {
      color: var(--market-ink);
      font-weight: 760;
    }

    button {
      min-height: 44px;
      border-radius: var(--market-radius-sm);
      padding: 0.5rem 0.65rem;
      transition: background 140ms ease, border-color 140ms ease;
    }

    button.active,
    button:hover {
      border-color: var(--market-line);
      background: var(--market-accent-soft);
      transform: none;
    }

    button.active span,
    button:hover span {
      color: var(--market-accent-dark);
    }

    @media (max-width: 980px) {
      .category-rail {
        position: static;
        width: 100%;
        min-width: 0;
        display: flex;
        align-items: center;
        max-height: none;
        overflow-x: auto;
        overflow-y: hidden;
        padding: 0.5rem;
        scroll-snap-type: x proximity;
      }

      .rail-title {
        min-width: 104px;
        padding: 0.25rem 0.5rem;
      }

      .category-rail > button {
        width: auto;
        min-width: max-content;
        flex: 0 0 auto;
        display: inline-flex;
        align-items: center;
        scroll-snap-align: start;
      }

      .category-rail > button span {
        display: none;
      }

      .help-card {
        display: none;
      }
    }

    /* Horizontal collection ribbon used beneath the illustrated hero. */
    .category-rail {
      position: static;
      width: 100%;
      max-height: none;
      display: flex;
      align-items: stretch;
      gap: .35rem;
      overflow-x: auto;
      overflow-y: hidden;
      padding: .62rem;
      border: 1px solid rgba(207,188,195,.75);
      border-radius: 24px 7px 24px 7px;
      background: rgba(255,253,249,.91);
      box-shadow: 0 18px 42px rgba(76,46,63,.12);
      backdrop-filter: blur(18px);
      scroll-snap-type: x proximity;
    }

    .rail-title {
      min-width: 118px;
      flex: 0 0 118px;
      align-content: center;
      padding: .3rem .7rem;
      border-right: 1px solid var(--market-line);
    }

    .rail-title span { color: var(--market-accent-dark); font-size: .62rem; letter-spacing: .14em; }
    .rail-title strong { font-family: var(--font-market-display); font-size: 1.02rem; font-weight: 620; }

    .category-rail > button {
      width: auto;
      min-width: 112px;
      flex: 1 0 112px;
      display: grid;
      grid-template-columns: 38px minmax(0, 1fr);
      align-items: center;
      gap: .55rem;
      min-height: 58px;
      padding: .42rem .62rem;
      border-radius: var(--market-radius-control);
      scroll-snap-align: start;
    }

    .category-rail > button span {
      width: 38px;
      height: 38px;
      display: grid;
      place-items: center;
      border: 1px solid rgba(143,106,127,.2);
      border-radius: 50% 50% 50% 16%;
      background: #f5edef;
      color: var(--market-accent-dark);
      font-family: var(--font-market-display);
      font-size: .69rem;
      font-weight: 620;
      letter-spacing: .04em;
    }

    .category-rail > button strong {
      overflow: hidden;
      color: var(--market-muted);
      font-size: .77rem;
      font-weight: 650;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .category-rail > button.active,
    .category-rail > button:hover {
      border-color: rgba(186,91,120,.24);
      background: linear-gradient(135deg, #f8e9ed, #eee9f0);
    }

    .category-rail > button.active span,
    .category-rail > button:hover span {
      border-color: rgba(186,91,120,.35);
      background: #fffaf8;
    }

    .category-rail > button.active strong,
    .category-rail > button:hover strong { color: var(--market-accent-dark); }
    .help-card { display: none; }

    @media (max-width: 640px) {
      .category-rail {
        position: static;
        display: flex;
        grid-template-columns: none;
        max-height: none;
        overflow-x: auto;
        overflow-y: hidden;
        padding: .48rem;
      }

      .rail-title { min-width: 94px; flex-basis: 94px; padding-inline: .45rem; }
      .category-rail > button { min-width: 102px; flex-basis: 102px; }
    }
  `],
})
export class MarketplaceSidebarComponent {
  @Input() categories: Category[] = [];
  @Input() selectedCategoryId = 'ALL';
  @Output() categorySelected = new EventEmitter<string>();
  readonly aiAssistantEnabled = environment.features.aiAssistant;

  categoryShortLabel(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean);
    return words[0]?.slice(0, 3) || 'New';
  }
}
