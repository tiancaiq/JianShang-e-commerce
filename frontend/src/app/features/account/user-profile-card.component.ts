import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { environment } from '../../../environments/environment';

export interface ProfileCardUser {
  displayName?: string | null;
  email?: string | null;
  avatarUrl?: string | null;
}

@Component({
  selector: 'app-user-profile-card',
  standalone: true,
  imports: [RouterLink],
  template: `
    <aside class="profile-card" aria-label="Marketplace profile summary">
      <div class="avatar-ring">
        @if (avatarUrl(); as avatar) {
          <img [src]="avatar" [alt]="displayName() + ' avatar'" />
        } @else {
          <span>{{ initials() }}</span>
        }
      </div>

      <h2>{{ displayName() }}</h2>
      <p class="handle">{{ handle() }}</p>
      <span class="seller-badge">Marketplace seller</span>

      <dl class="stats" aria-label="Marketplace profile stats">
        <div>
          <dt>Listings</dt>
          <dd>--</dd>
        </div>
        <div>
          <dt>Trades</dt>
          <dd>Soon</dd>
        </div>
        <div>
          <dt>Rating</dt>
          <dd>New</dd>
        </div>
      </dl>

      @if (showSellAction) {
        <a routerLink="/account/listings/new" class="sell-action">+ Sell an Item</a>
      }
    </aside>
  `,
  styles: [`
    :host {
      display: block;
    }

    .profile-card {
      width: 100%;
      display: grid;
      justify-items: center;
      gap: 0.45rem;
      padding: 1.25rem 0.75rem 0.85rem;
      border: 1px solid rgba(246, 190, 222, 0.9);
      border-radius: 8px;
      background:
        radial-gradient(circle at 20% 8%, rgba(255, 255, 255, 0.98) 0 18%, transparent 19%),
        linear-gradient(180deg, rgba(255, 248, 252, 0.98), rgba(255, 238, 247, 0.92));
      box-shadow: 0 16px 38px rgba(187, 99, 151, 0.16);
      color: var(--market-ink);
    }

    .avatar-ring {
      width: 6.4rem;
      height: 6.4rem;
      display: grid;
      place-items: center;
      overflow: hidden;
      border-radius: 999px;
      background:
        linear-gradient(#fff, #fff) padding-box,
        linear-gradient(135deg, #ff91c6, #9d7df2) border-box;
      border: 3px solid transparent;
      box-shadow: 0 12px 26px rgba(190, 58, 131, 0.16);
    }

    .avatar-ring img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .avatar-ring span {
      width: 100%;
      height: 100%;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, #ffc1de, #a88df1);
      color: #fff;
      font-size: 2rem;
      font-weight: 900;
    }

    h2 {
      margin: 0.3rem 0 0;
      color: var(--market-ink);
      font-size: 1.2rem;
      letter-spacing: 0;
    }

    .handle {
      margin: 0;
      color: var(--market-muted);
      font-size: 0.82rem;
    }

    .seller-badge {
      display: inline-flex;
      align-items: center;
      min-height: 28px;
      padding: 0 0.7rem;
      border-radius: 8px;
      background: rgba(157, 125, 242, 0.13);
      color: #7f5dd9;
      font-size: 0.78rem;
      font-weight: 850;
    }

    .stats {
      width: calc(100% + 1.5rem);
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      margin: 0.65rem -0.75rem 0;
      border-top: 1px solid rgba(246, 190, 222, 0.7);
      border-bottom: 1px solid rgba(246, 190, 222, 0.7);
    }

    .stats div {
      display: grid;
      gap: 0.15rem;
      justify-items: center;
      padding: 0.65rem 0.3rem;
    }

    .stats div + div {
      border-left: 1px solid rgba(246, 190, 222, 0.7);
    }

    dt {
      color: var(--market-muted);
      font-size: 0.72rem;
    }

    dd {
      margin: 0;
      color: #6d4c96;
      font-size: 0.82rem;
      font-weight: 900;
    }

    .sell-action {
      width: 100%;
      min-height: 44px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      margin-top: 0.4rem;
      border-radius: 8px;
      background: linear-gradient(135deg, #ff75b4, #f15f9e);
      color: #fff;
      font-weight: 900;
      text-decoration: none;
      box-shadow: 0 14px 28px rgba(241, 95, 158, 0.2);
    }
  `],
})
export class UserProfileCardComponent {
  @Input() user: ProfileCardUser | null = null;
  @Input() showSellAction = true;

  displayName(): string {
    return this.user?.displayName?.trim()
      || this.user?.email?.split('@')[0]
      || 'Marketplace user';
  }

  handle(): string {
    const emailName = this.user?.email?.split('@')[0]?.trim();
    const source = emailName || this.displayName();
    return `@${source.toLowerCase().replace(/[^a-z0-9]+/g, '').slice(0, 18) || 'marketplace'}`;
  }

  avatarUrl(): string | null {
    const value = this.user?.avatarUrl?.trim();
    if (!value) {
      return null;
    }
    if (value.startsWith('/api/')) {
      return `${environment.apiGatewayUrl}${value}`;
    }
    return value;
  }

  initials(): string {
    const name = this.displayName().trim();
    if (!name) {
      return 'M';
    }
    const parts = name.split(/\s+/).filter(Boolean);
    const first = parts[0]?.[0] || 'M';
    const second = parts.length > 1 ? parts[1]?.[0] : '';
    return `${first}${second}`.toUpperCase();
  }
}
