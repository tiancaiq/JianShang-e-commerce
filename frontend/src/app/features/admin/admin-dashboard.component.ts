import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminDashboardSummary } from '../../core/models/admin.model';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
import { AdminService } from '../../core/services/admin.service';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="admin-dashboard">
      <header class="page-header">
        <div>
          <p>Admin MVP</p>
          <h2>Review workspace</h2>
        </div>
      </header>

      @if (loading()) {
        <div class="state-panel">Loading admin dashboard...</div>
      } @else if (errorMsg()) {
        <div class="state-panel error">{{ errorMsg() }}</div>
      } @else if (summary(); as data) {
        <div class="metric-grid" aria-label="Admin work summary">
          @if (adminService.hasPermission(permissions.USER_READ)) {
            <a class="metric" routerLink="/admin/users">
              <span>User administration</span>
              <strong>Open</strong>
            </a>
          }
          @if (adminService.hasPermission(permissions.BUSINESS_READ)) {
            <a class="metric" routerLink="/admin/businesses">
              <span>Business administration</span>
              <strong>Open</strong>
            </a>
          }
          @if (adminService.hasPermission(permissions.BUSINESS_APPLICATION_READ)) {
            <a class="metric" routerLink="/admin/business-applications">
              <span>Business applications</span>
              <strong>{{ data.pendingBusinessApplications }}</strong>
            </a>
          }
          @if (adminService.hasPermission(permissions.LISTING_MODERATION_READ)) {
            <a class="metric" routerLink="/admin/listings/moderation">
              <span>Listing reviews</span>
              <strong>{{ data.pendingListingReviews }}</strong>
            </a>
            <a class="metric" routerLink="/admin/listings/moderation">
              <span>Assigned to me</span>
              <strong>{{ data.assignedToMeListingReviews }}</strong>
            </a>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .admin-dashboard {
      display: grid;
      gap: 1rem;
    }

    .page-header {
      display: flex;
      align-items: end;
      justify-content: space-between;
    }

    .page-header p {
      margin: 0 0 0.25rem;
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 700;
      text-transform: uppercase;
    }

    .page-header h2 {
      margin: 0;
      font-size: 1.5rem;
      letter-spacing: 0;
    }

    .metric-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.875rem;
    }

    .metric,
    .state-panel {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: rgba(18, 19, 23, 0.88);
      padding: 1rem;
    }

    .metric {
      color: var(--color-text-primary);
      text-decoration: none;
    }

    .metric span {
      display: block;
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .metric strong {
      display: block;
      margin-top: 0.5rem;
      font-size: 2rem;
      letter-spacing: 0;
    }

    .state-panel {
      color: var(--color-text-secondary);
    }

    .state-panel.error {
      color: var(--color-danger);
    }

    @media (max-width: 760px) {
      .metric-grid {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class AdminDashboardComponent implements OnInit {
  readonly adminService = inject(AdminService);
  readonly permissions = ADMIN_PERMISSIONS;

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly summary = signal<AdminDashboardSummary | null>(null);

  ngOnInit(): void {
    this.adminService.getDashboardSummary().subscribe({
      next: summary => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: () => {
        this.errorMsg.set('Admin dashboard could not be loaded.');
        this.loading.set(false);
      },
    });
  }
}
