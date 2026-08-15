import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminUserSearchPage, UserEnforcementScope } from '../../core/models/admin-user.model';
import { AdminUserService } from '../../core/services/admin-user.service';

@Component({
  selector: 'app-admin-user-list',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="user-list">
      <header class="page-heading">
        <div><p>Trust &amp; safety</p><h2>User administration</h2></div>
        @if (page(); as result) { <span>{{ result.totalElements }} accounts</span> }
      </header>

      <form class="filters" (ngSubmit)="applyFilters()" aria-label="User search filters">
        <label class="search">Search
          <input [(ngModel)]="query" name="query" placeholder="User ID or display name" />
        </label>
        <label>Enforcement
          <select [(ngModel)]="enforcementState" name="enforcementState">
            <option value="">All states</option><option value="CLEAR">Clear</option>
            <option value="RESTRICTED">Restricted</option><option value="SUSPENDED">Suspended</option>
            <option value="BANNED">Banned</option>
          </select>
        </label>
        <label>Scope
          <select [(ngModel)]="scope" name="scope">
            <option value="">All scopes</option><option value="USER_BUYING">Buying</option>
            <option value="USER_SELLING">Selling</option>
          </select>
        </label>
        <label>Sort
          <select [(ngModel)]="sort" name="sort">
            <option value="createdAt,desc">Newest accounts</option>
            <option value="createdAt,asc">Oldest accounts</option>
            <option value="updatedAt,desc">Recently updated</option>
            <option value="userId,asc">User ID</option>
          </select>
        </label>
        <button type="submit">Apply</button>
      </form>

      @if (loading()) {
        <div class="state">Loading users…</div>
      } @else if (error()) {
        <div class="state error" role="alert">{{ error() }} <button type="button" (click)="load()">Retry</button></div>
      } @else if (page(); as result) {
        @if (result.items.length === 0) {
          <div class="state">No users match these filters.</div>
        } @else {
          <div class="table-wrap">
            <table>
              <thead><tr><th>User</th><th>Email</th><th>Seller</th><th>Businesses</th><th>Enforcement</th><th>Active scopes</th><th>Created</th></tr></thead>
              <tbody>
                @for (user of result.items; track user.userId) {
                  <tr>
                    <td><a [routerLink]="['/admin/users', user.userId]">{{ user.safeDisplayName }}</a><small>{{ user.userId }}</small></td>
                    <td>{{ user.safeEmail || 'Not provided' }} @if (user.emailMasked) { <small>masked</small> }</td>
                    <td>{{ user.individualSellerStatus }}</td><td>{{ user.businessMembershipCount }}</td>
                    <td><span class="state-pill" [class.clear]="!user.strongestActiveAction">{{ user.strongestActiveAction || 'CLEAR' }}</span></td>
                    <td>{{ user.activeScopes.length ? scopeLabels(user.activeScopes) : '—' }}</td>
                    <td>{{ formatDate(user.createdAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <nav class="pagination" aria-label="User list pagination">
            <button type="button" [disabled]="result.page === 0" (click)="move(-1)">Previous</button>
            <span>Page {{ result.page + 1 }} of {{ result.totalPages || 1 }}</span>
            <button type="button" [disabled]="result.page + 1 >= result.totalPages" (click)="move(1)">Next</button>
          </nav>
        }
      }
    </section>
  `,
  styles: [`
    .user-list{display:grid;gap:1rem}.page-heading{display:flex;align-items:end;justify-content:space-between;gap:1rem}.page-heading p{margin:0 0 .2rem;color:var(--color-info);font-size:.75rem;font-weight:800;text-transform:uppercase;letter-spacing:.08em}.page-heading h2{margin:0;font-size:1.65rem}.page-heading>span{color:var(--color-text-muted)}
    .filters{display:grid;grid-template-columns:2fr repeat(3,minmax(130px,1fr)) auto;gap:.75rem;align-items:end;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-secondary)}label{display:grid;gap:.35rem;color:var(--color-text-muted);font-size:.75rem;font-weight:750}input,select{min-height:42px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.55rem .7rem;font:inherit}button{min-height:42px;border:1px solid var(--color-info);border-radius:var(--radius-md);background:rgba(56,189,248,.12);color:var(--color-info);padding:.55rem .85rem;font:inherit;font-weight:800;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}
    .state,.table-wrap{border:1px solid var(--color-border);border-radius:var(--radius-md);background:rgba(18,19,23,.88)}.state{padding:1.2rem;color:var(--color-text-secondary)}.state.error{color:var(--color-danger)}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:900px}th,td{padding:.8rem;text-align:left;border-bottom:1px solid var(--color-border);vertical-align:top}th{color:var(--color-text-muted);font-size:.72rem;text-transform:uppercase;letter-spacing:.05em}td{font-size:.84rem;color:var(--color-text-secondary)}td a{color:var(--color-text-primary);font-weight:800;text-decoration:none}small{display:block;margin-top:.2rem;color:var(--color-text-muted);font-size:.7rem}.state-pill{display:inline-flex;border-radius:999px;background:rgba(251,146,60,.12);color:#fdba74;padding:.2rem .5rem;font-size:.7rem;font-weight:850}.state-pill.clear{background:rgba(74,222,128,.1);color:#86efac}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:.75rem;color:var(--color-text-muted);font-size:.8rem}
    @media(max-width:900px){.filters{grid-template-columns:1fr 1fr}.filters .search{grid-column:1/-1}}@media(max-width:600px){.filters{grid-template-columns:1fr}.filters .search{grid-column:auto}.page-heading{align-items:start;flex-direction:column}.pagination{justify-content:space-between}}
  `],
})
export class AdminUserListComponent implements OnInit {
  private readonly service = inject(AdminUserService);
  readonly page = signal<AdminUserSearchPage | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  query = '';
  enforcementState = '';
  scope: UserEnforcementScope | '' = '';
  sort = 'createdAt,desc';
  private currentPage = 0;

  ngOnInit(): void { this.load(); }
  applyFilters(): void { this.currentPage = 0; this.load(); }
  move(delta: number): void { this.currentPage += delta; this.load(); }
  load(): void {
    this.loading.set(true); this.error.set('');
    this.service.search({ q: this.query.trim(), enforcementState: this.enforcementState, scope: this.scope,
      page: this.currentPage, size: 25, sort: this.sort }).subscribe({
      next: response => { this.page.set(response.data); this.loading.set(false); },
      error: response => { this.error.set(response.status === 403 ? 'You do not have permission to read users.' :
        'User search could not be loaded.'); this.loading.set(false); },
    });
  }
  formatDate(value: string): string { return new Date(value).toLocaleString(); }
  scopeLabels(scopes: UserEnforcementScope[]): string { return scopes.map(scope => scope.replace('USER_', '')).join(', '); }
}
