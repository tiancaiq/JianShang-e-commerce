import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminDashboardSummary } from '../../core/models/admin.model';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
import { AdminService } from '../../core/services/admin.service';
import { SupportService } from '../../core/services/support.service';

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
          @if (adminService.hasPermission(permissions.SUPPORT_READ)) {
            <a class="metric support-metric" routerLink="/admin/support">
              <span>Support inbox</span>
              @switch (supportSummaryState()) {
                @case ('ready') {
                  <strong>{{ unassignedSupportTickets() }}</strong>
                  <small>Unassigned tickets</small>
                }
                @case ('unavailable') {
                  <strong aria-hidden="true">—</strong>
                  <small>Count temporarily unavailable</small>
                }
                @default {
                  <strong aria-hidden="true">…</strong>
                  <small>Loading unassigned tickets</small>
                }
              }
              <span class="metric-action">Open inbox <span aria-hidden="true">→</span></span>
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

    .support-metric {
      position: relative;
      overflow: hidden;
      padding-left: 1.25rem;
    }

    .support-metric::before {
      position: absolute;
      inset: 0 auto 0 0;
      width: 0.25rem;
      background: var(--color-info);
      content: '';
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

    .metric small {
      display: block;
      margin-top: 0.125rem;
      color: var(--color-text-secondary);
      font-size: 0.75rem;
    }

    .metric .metric-action {
      display: block;
      margin-top: 0.75rem;
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
    }

    .metric:hover,
    .metric:focus-visible {
      border-color: var(--color-info);
    }

    .metric:focus-visible {
      outline: 2px solid var(--color-info);
      outline-offset: 2px;
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
  private readonly supportService = inject(SupportService);
  readonly permissions = ADMIN_PERMISSIONS;

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly summary = signal<AdminDashboardSummary | null>(null);
  readonly unassignedSupportTickets = signal(0);
  readonly supportSummaryState = signal<'loading' | 'ready' | 'unavailable'>('loading');

  ngOnInit(): void {
    if (this.adminService.hasPermission(this.permissions.SUPPORT_READ)) {
      this.loadSupportSummary();
    }

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

  private loadSupportSummary(): void {
    this.supportService.search({
      assignment: 'UNASSIGNED',
      page: 0,
      size: 1,
      sort: 'updatedAt,desc',
    }).subscribe({
      next: page => {
        this.unassignedSupportTickets.set(page.totalElements);
        this.supportSummaryState.set('ready');
      },
      error: () => this.supportSummaryState.set('unavailable'),
    });
  }
}
