import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-admin-access-denied',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="access-denied">
      <div class="panel">
        <p class="eyebrow">Admin access</p>
        <h1>You need platform admin access.</h1>
        <p class="body">
          You are signed in, but this account is not authorized for the admin portal.
        </p>
        <div class="actions">
          <a routerLink="/" class="secondary">Marketplace</a>
          <button type="button" class="secondary" (click)="logout()">Logout</button>
          <a routerLink="/login" [queryParams]="{ client: 'admin-portal', returnUrl: '/admin/dashboard' }" class="primary">
            Sign in as admin
          </a>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .access-denied {
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      background: var(--color-bg-primary);
    }

    .panel {
      width: min(100%, 440px);
      padding: 2rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    .eyebrow {
      margin: 0 0 0.75rem;
      color: var(--color-info);
      font-weight: 800;
      text-transform: uppercase;
      font-size: 0.75rem;
    }

    h1 {
      margin: 0 0 0.75rem;
      font-size: 1.5rem;
      letter-spacing: 0;
      color: var(--color-text-primary);
    }

    .body {
      margin: 0;
      color: var(--color-text-secondary);
      line-height: 1.55;
    }

    .actions {
      display: flex;
      gap: 0.75rem;
      margin-top: 1.5rem;
      flex-wrap: wrap;
    }

    a,
    button {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0.6rem 0.9rem;
      border-radius: var(--radius-md);
      background: transparent;
      font-weight: 750;
      font: inherit;
      text-decoration: none;
      cursor: pointer;
    }

    .primary {
      background: var(--color-info);
      color: #041016;
    }

    .secondary {
      border: 1px solid var(--color-border);
      color: var(--color-text-primary);
    }
  `],
})
export class AdminAccessDeniedComponent {
  private authService = inject(AuthService);

  logout(): void {
    this.authService.logout('admin-portal');
  }
}
