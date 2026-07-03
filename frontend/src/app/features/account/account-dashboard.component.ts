import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { UserProfileCardComponent } from './user-profile-card.component';

@Component({
  selector: 'app-account-dashboard',
  standalone: true,
  imports: [RouterLink, UserProfileCardComponent],
  template: `
    <section class="account-dashboard">
      <div class="account-top">
        <header class="account-hero">
          <div>
            <p class="eyebrow">Marketplace account</p>
            <h1>{{ greeting() }}</h1>
            <p>{{ authService.user()?.email || 'Manage your marketplace profile and selling workspace.' }}</p>
          </div>
          <a routerLink="/account/profile" class="profile-link">Edit profile</a>
        </header>

        <app-user-profile-card [user]="authService.user()" />
      </div>

      <div class="account-grid" aria-label="Account actions">
        <a routerLink="/account/profile" class="account-card">
          <span class="card-kicker">Profile</span>
          <strong>Account details</strong>
          <span>Update your display name, phone, and avatar URL.</span>
        </a>

        <a routerLink="/account/seller-profile" class="account-card">
          <span class="card-kicker">Selling</span>
          <strong>Seller profile</strong>
          <span>Activate or review your individual seller profile.</span>
        </a>

        <a routerLink="/account/listings" class="account-card">
          <span class="card-kicker">Listings</span>
          <strong>My Listings</strong>
          <span>Review drafts, submitted listings, and approved marketplace items.</span>
        </a>

        <a routerLink="/account/listings/new" class="account-card">
          <span class="card-kicker">Create</span>
          <strong>New listing</strong>
          <span>Start an individual marketplace listing draft.</span>
        </a>

        <div class="account-card disabled-card" aria-disabled="true">
          <span class="card-kicker">Messages</span>
          <strong>Messages</strong>
          <span>Buyer and seller conversations will appear here after chat is added.</span>
        </div>
      </div>
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .account-dashboard {
      display: grid;
      gap: 1.25rem;
      max-width: 1040px;
      margin: 0 auto;
    }

    .account-top {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(240px, 300px);
      gap: 1rem;
      align-items: stretch;
    }

    .account-hero,
    .account-card {
      background: rgba(255, 255, 255, 0.92);
      border: 1px solid var(--market-line);
      border-radius: 8px;
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .account-hero {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: clamp(1rem, 3vw, 1.5rem);
    }

    .eyebrow,
    .card-kicker {
      margin: 0;
      color: var(--market-accent-dark);
      font-size: 0.75rem;
      font-weight: 850;
      letter-spacing: 0;
      text-transform: uppercase;
    }

    h1 {
      margin: 0.35rem 0;
      color: var(--market-ink);
      font-size: clamp(1.55rem, 4vw, 2rem);
      letter-spacing: 0;
    }

    p,
    .account-card span:last-child {
      margin: 0;
      color: var(--market-muted);
      line-height: 1.5;
    }

    .profile-link,
    .account-card {
      color: inherit;
      text-decoration: none;
    }

    .profile-link {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.9rem;
      border-radius: 8px;
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      font-weight: 800;
      white-space: nowrap;
    }

    .account-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 1rem;
    }

    .account-card {
      min-height: 156px;
      display: grid;
      align-content: start;
      gap: 0.45rem;
      padding: 1rem;
      transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
    }

    a.account-card:hover {
      transform: translateY(-2px);
      border-color: rgba(244, 114, 182, 0.72);
      box-shadow: 0 18px 38px rgba(143, 92, 144, 0.14);
    }

    .account-card strong {
      color: var(--market-ink);
      font-size: 1.05rem;
    }

    .disabled-card {
      opacity: 0.68;
      background: rgba(255, 255, 255, 0.62);
    }

    @media (max-width: 640px) {
      .account-top {
        grid-template-columns: 1fr;
      }

      .account-hero {
        display: grid;
      }

      .profile-link {
        width: 100%;
      }
    }
  `],
})
export class AccountDashboardComponent {
  authService = inject(AuthService);

  greeting(): string {
    const user = this.authService.user();
    const name = user?.displayName?.trim() || user?.email?.split('@')[0] || 'Account';
    return `Welcome, ${name}`;
  }
}
