import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, finalize } from 'rxjs';
import {
  AdminAnalyticsOverview,
  AnalyticsBreakdownItem,
  AnalyticsMetric,
  AnalyticsRangePreset,
  AnalyticsSection,
  AnalyticsSectionStatus,
  AnalyticsTrend,
  AnalyticsTrendMetric,
} from '../../core/models/admin-analytics.model';
import { AdminAnalyticsService } from '../../core/services/admin-analytics.service';
import { analyticsDrillDownTarget, AnalyticsDrillDownTarget } from './admin-analytics-drill-down';
import { analyticsComparisonSummary, formatAnalyticsValue } from './admin-analytics-format';
import {
  ADMIN_ANALYTICS_NOW,
  analyticsGranularity,
  defaultAnalyticsCustomDates,
  ResolvedAnalyticsRange,
  resolveAnalyticsRange,
} from './admin-analytics-range';
import { AdminAnalyticsTrendComponent } from './admin-analytics-trend.component';

interface AnalyticsSectionView {
  key: string;
  label: string;
  description: string;
  value: AnalyticsSection;
}

@Component({
  selector: 'app-admin-analytics',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, AdminAnalyticsTrendComponent],
  template: `
    <section class="analytics-page">
      <header class="analytics-heading">
        <div>
          <p class="eyebrow">Marketplace signal desk</p>
          <h1>Operational analytics</h1>
          <p>Read cross-marketplace workload and health, then continue in the workflow that owns each record.</p>
        </div>
        <div class="generation" aria-live="polite">
          <span>Snapshot generated</span>
          <strong>{{ overview()?.generatedAt ? (overview()!.generatedAt | date:'medium':'UTC') : 'Waiting for data' }}</strong>
          <small>UTC · manual refresh</small>
        </div>
      </header>

      <section class="control-deck" aria-label="Analytics time controls">
        <label>Time range
          <select name="analyticsRange" [(ngModel)]="rangePreset" (ngModelChange)="selectPreset($event)">
            <option value="TODAY">Today</option>
            <option value="LAST_7_DAYS">Last 7 days</option>
            <option value="LAST_30_DAYS">Last 30 days</option>
            <option value="LAST_90_DAYS">Last 90 days</option>
            <option value="CUSTOM">Custom</option>
          </select>
        </label>
        @if (rangePreset === 'CUSTOM') {
          <label>From date (UTC)<input type="date" name="customFrom" [(ngModel)]="customFrom" [max]="maxCustomDate"></label>
          <label>Through date (UTC)<input type="date" name="customThrough" [(ngModel)]="customThrough" [max]="maxCustomDate"></label>
          <button type="button" class="secondary" (click)="refresh()">Apply custom range</button>
        }
        <label class="compare-control">
          <input type="checkbox" name="compare" [(ngModel)]="compare" (ngModelChange)="comparisonChanged()">
          <span>Compare with preceding period</span>
        </label>
        <button type="button" class="refresh" (click)="refresh()" [disabled]="overviewLoading()">Refresh snapshot</button>
      </section>
      @if (rangePreset === 'CUSTOM') {
        <p class="range-help">The through date is included. Past dates end at the next UTC midnight; today ends at the current UTC instant.</p>
      }
      @if (rangeError()) { <p class="page-notice error" role="alert">{{ rangeError() }}</p> }

      @if (activeRange(); as range) {
        <div class="range-cut" aria-label="Selected half-open analytics range">
          <span>Authoritative data window</span>
          <code>[ {{ range.from | date:'medium':'UTC' }} → {{ range.to | date:'medium':'UTC' }} )</code>
          <strong>UTC · end exclusive</strong>
        </div>
      }

      @if (overviewLoading()) {
        <div class="page-state" role="status">Assembling bounded marketplace summaries…</div>
      } @else if (overviewError()) {
        <div class="page-state error" role="alert">
          <strong>Analytics snapshot unavailable</strong>
          <span>{{ overviewError() }}</span>
          <button type="button" (click)="refresh()">Try again</button>
        </div>
      } @else if (overview()) {
        <div class="status-rail" aria-label="Analytics source availability">
          @for (status of statuses; track status) {
            <div [attr.data-status]="status"><span></span><strong>{{ statusCount(status) }}</strong><small>{{ statusLabel(status) }}</small></div>
          }
        </div>

        <section class="overview-ledger" aria-label="Marketplace operational summaries">
          @for (section of sectionEntries(); track section.key) {
            <article class="analytics-section" [attr.data-status]="section.value.status">
              <header>
                <div><p>{{ statusLabel(section.value.status) }}</p><h2>{{ section.label }}</h2><span>{{ section.description }}</span></div>
                @if (section.value.dataAsOf) { <time [attr.datetime]="section.value.dataAsOf">As of {{ section.value.dataAsOf | date:'short':'UTC' }} UTC</time> }
              </header>
              @if (section.value.safeMessage) {
                <p class="section-message" [attr.data-status]="section.value.status">{{ section.value.safeMessage }}</p>
              }
              @if (section.value.metrics.length) {
                <div class="metric-grid">
                  @for (metric of section.value.metrics; track metric.key) {
                    @if (drillDown(metric.drillDownKey); as target) {
                      <a class="metric" [routerLink]="target.commands" [queryParams]="target.queryParams"
                         [attr.aria-label]="metricAriaLabel(metric)">
                        <span>{{ metricLabel(metric) }}</span><strong>{{ format(metric.currentValue, metric.unit) }}</strong>
                        @if (compare) { <small>{{ comparison(metric) }}</small> }
                        <em>Open workflow <span aria-hidden="true">→</span></em>
                      </a>
                    } @else {
                      <div class="metric">
                        <span>{{ metricLabel(metric) }}</span><strong>{{ format(metric.currentValue, metric.unit) }}</strong>
                        @if (compare) { <small>{{ comparison(metric) }}</small> }
                      </div>
                    }
                  }
                </div>
              }
              @if (!section.value.metrics.length && !section.value.breakdowns.length) {
                <p class="empty">{{ emptyMessage(section.value.status) }}</p>
              }
              @for (breakdown of section.value.breakdowns; track breakdown.key) {
                <div class="breakdown-shell">
                  <table>
                    <caption>{{ breakdown.label }}</caption>
                    <thead><tr>
                      <th scope="col">Category</th>
                      <th scope="col">{{ breakdown.primaryLabel }}</th>
                      @if (breakdown.secondaryLabel) { <th scope="col">{{ breakdown.secondaryLabel }}</th> }
                    </tr></thead>
                    <tbody>
                      @for (item of breakdown.items; track item.key) {
                        <tr>
                          <th scope="row">
                            @if (drillDown(item.drillDownKey); as target) {
                              <a [routerLink]="target.commands" [queryParams]="target.queryParams">{{ item.label }}</a>
                            } @else { {{ item.label }} }
                          </th>
                          <td>{{ format(item.value, breakdown.unit) }}</td>
                          @if (breakdown.secondaryLabel) { <td>{{ secondary(item, breakdown.unit) }}</td> }
                        </tr>
                      } @empty {
                        <tr><td [attr.colspan]="breakdown.secondaryLabel ? 3 : 2">No breakdown data for this range.</td></tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </article>
          }
        </section>
      }

      <section class="trend-controls" aria-label="Trend selection">
        <label>Trend series
          <select name="trendMetric" [(ngModel)]="trendMetric" (ngModelChange)="selectTrend($event)">
            @for (option of trendOptions; track option.value) { <option [value]="option.value">{{ option.label }}</option> }
          </select>
        </label>
        <span>Charts repeat every critical value in the table below the plot.</span>
      </section>
      <app-admin-analytics-trend [trend]="trend()" [loading]="trendLoading()" [error]="trendError()" />
    </section>
  `,
  styles: [`
    :host{display:block}.analytics-page{display:grid;gap:1rem;color:var(--color-text-primary)}.analytics-heading{display:flex;align-items:end;justify-content:space-between;gap:2rem;padding:1.25rem 0;border-bottom:1px solid var(--color-border)}.analytics-heading h1,.analytics-heading p{margin:0}.analytics-heading h1{margin:.2rem 0 .45rem;font:850 clamp(2rem,5vw,3.7rem)/.95 var(--font-display);letter-spacing:-.035em}.analytics-heading>div:first-child>p:last-child{max-width:68ch;color:var(--color-text-secondary)}.eyebrow{color:var(--color-info)!important;font-size:.7rem;font-weight:900;letter-spacing:.14em;text-transform:uppercase}.generation{display:grid;min-width:220px;padding-left:1rem;border-left:4px solid var(--color-info)}.generation span,.generation small{color:var(--color-text-muted);font-size:.7rem}.generation strong{margin:.25rem 0;font-size:.86rem}.control-deck{display:flex;align-items:end;gap:.7rem;flex-wrap:wrap;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.72rem;font-weight:800}select,input,button{min-height:42px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.55rem .7rem;font:inherit}.compare-control{display:flex;align-items:center;gap:.5rem;min-height:42px;padding:.3rem .55rem;border:1px solid var(--color-border);border-radius:var(--radius-md)}.compare-control input{min-height:auto;width:1rem;height:1rem}.refresh{margin-left:auto;border-color:var(--color-info);background:rgba(56,189,248,.12);color:var(--color-info);font-weight:850}.secondary{font-weight:800}.range-help{margin:-.45rem 0 0;color:var(--color-text-muted);font-size:.74rem}.range-cut{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:1rem;padding:.75rem 1rem;border:1px solid var(--color-border);border-left:4px solid var(--color-info);background:linear-gradient(90deg,rgba(56,189,248,.08),transparent)}.range-cut span,.range-cut strong{color:var(--color-text-muted);font-size:.67rem;text-transform:uppercase;letter-spacing:.07em}.range-cut code{overflow-wrap:anywhere;color:var(--color-text-primary);font:750 .76rem ui-monospace,monospace}.range-cut strong{text-align:right}.status-rail{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--color-border);border-radius:var(--radius-md);overflow:hidden}.status-rail div{display:grid;grid-template-columns:8px auto 1fr;align-items:center;gap:.45rem;padding:.7rem;border-right:1px solid var(--color-border);background:var(--color-bg-secondary)}.status-rail div:last-child{border-right:0}.status-rail div>span{width:8px;height:8px;border-radius:50%;background:var(--color-success)}.status-rail div[data-status=DEGRADED]>span{background:var(--color-warning)}.status-rail div[data-status=UNAVAILABLE]>span{background:var(--color-danger)}.status-rail div[data-status=RESTRICTED]>span{background:var(--color-text-muted)}.status-rail small{color:var(--color-text-muted);font-size:.67rem;text-transform:uppercase}.overview-ledger{display:grid;gap:.85rem}.analytics-section{display:grid;gap:.85rem;padding:1rem;border:1px solid var(--color-border);border-left:5px solid var(--color-success);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}.analytics-section[data-status=DEGRADED]{border-left-color:var(--color-warning)}.analytics-section[data-status=UNAVAILABLE]{border-left-color:var(--color-danger)}.analytics-section[data-status=RESTRICTED]{border-left-color:var(--color-text-muted)}.analytics-section>header{display:flex;justify-content:space-between;gap:1rem;align-items:start}.analytics-section h2,.analytics-section p{margin:0}.analytics-section h2{margin:.15rem 0;font:850 1.2rem var(--font-display)}.analytics-section header p{color:var(--color-text-muted);font-size:.64rem;font-weight:900;text-transform:uppercase;letter-spacing:.09em}.analytics-section header span,.analytics-section time{color:var(--color-text-muted);font-size:.72rem}.analytics-section time{white-space:nowrap}.section-message,.page-notice,.page-state{padding:.8rem 1rem;border-left:4px solid var(--color-info);background:rgba(56,189,248,.08);color:var(--color-text-secondary)}.section-message[data-status=DEGRADED]{border-color:var(--color-warning)}.section-message[data-status=UNAVAILABLE],.page-notice.error,.page-state.error{border-color:var(--color-danger)}.section-message[data-status=RESTRICTED]{border-color:var(--color-text-muted)}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:.55rem}.metric{display:grid;min-height:116px;padding:.8rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:inherit;text-decoration:none}.metric>span{color:var(--color-text-secondary);font-size:.72rem;font-weight:750}.metric strong{margin-top:.45rem;font-size:1.65rem;font-variant-numeric:tabular-nums}.metric small{margin-top:.25rem;color:var(--color-text-muted);font-size:.67rem}.metric em{align-self:end;margin-top:.5rem;color:var(--color-info);font-size:.67rem;font-style:normal;font-weight:850}.metric[href]:hover,.metric[href]:focus-visible{border-color:var(--color-info);background:rgba(56,189,248,.055)}.metric[href]:focus-visible{outline:2px solid var(--color-info);outline-offset:2px}.breakdown-shell{overflow:auto;border:1px solid var(--color-border);border-radius:var(--radius-md)}table{width:100%;border-collapse:collapse}caption{padding:.7rem;text-align:left;font-weight:850}th,td{padding:.65rem .75rem;text-align:left;border-top:1px solid var(--color-border);font-size:.75rem}thead th{color:var(--color-text-muted);font-size:.64rem;text-transform:uppercase;letter-spacing:.06em}tbody th{font-weight:750}td{font-variant-numeric:tabular-nums}td:last-child,th:last-child{text-align:right}table a{color:var(--color-info)}.empty{color:var(--color-text-muted)}.trend-controls{display:flex;justify-content:space-between;align-items:end;gap:1rem;padding-top:.5rem}.trend-controls span{color:var(--color-text-muted);font-size:.72rem}.page-state{display:grid;gap:.4rem}.page-state button{width:max-content}.error{color:var(--color-danger)}button{cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:950px){.metric-grid{grid-template-columns:repeat(2,1fr)}.range-cut{grid-template-columns:1fr}.range-cut strong{text-align:left}.status-rail{grid-template-columns:1fr 1fr}}@media(max-width:650px){.analytics-heading,.analytics-section>header,.trend-controls{align-items:start;flex-direction:column}.generation{width:100%;box-sizing:border-box}.control-deck{align-items:stretch;flex-direction:column}.refresh{margin-left:0}.metric-grid,.status-rail{grid-template-columns:1fr}.status-rail div{border-right:0;border-bottom:1px solid var(--color-border)}.status-rail div:last-child{border-bottom:0}.analytics-section{padding:.8rem}.analytics-section time{white-space:normal}}
  `],
})
export class AdminAnalyticsComponent implements OnInit, OnDestroy {
  private readonly api = inject(AdminAnalyticsService);
  private readonly now = inject(ADMIN_ANALYTICS_NOW);
  private overviewRequest?: Subscription;
  private trendRequest?: Subscription;

  readonly statuses: AnalyticsSectionStatus[] = ['AVAILABLE', 'DEGRADED', 'UNAVAILABLE', 'RESTRICTED'];
  readonly trendOptions: { value: AnalyticsTrendMetric; label: string }[] = [
    { value: 'REPORTS_SUBMITTED', label: 'Reports submitted' },
    { value: 'ORDERS_CREATED', label: 'Orders created' },
    { value: 'DISPUTES_OPENED', label: 'Disputes opened' },
    { value: 'REFUNDS_SUCCEEDED', label: 'Refunds succeeded' },
    { value: 'SUPPORT_TICKETS_CREATED', label: 'Support tickets created' },
    { value: 'LISTINGS_CREATED', label: 'Listings created' },
  ];
  readonly overview = signal<AdminAnalyticsOverview | null>(null);
  readonly trend = signal<AnalyticsTrend | null>(null);
  readonly activeRange = signal<ResolvedAnalyticsRange | null>(null);
  readonly overviewLoading = signal(false);
  readonly trendLoading = signal(false);
  readonly overviewError = signal('');
  readonly trendError = signal('');
  readonly rangeError = signal('');
  readonly sectionEntries = computed<AnalyticsSectionView[]>(() => {
    const value = this.overview();
    if (!value) return [];
    return [
      { key: 'marketplace', label: 'Marketplace overview', description: 'Account, business, listing, and order activity.', value: value.marketplace },
      { key: 'moderation', label: 'Moderation workload', description: 'Business verification and listing review backlogs.', value: value.moderation },
      { key: 'trustAndSafety', label: 'Trust & Safety', description: 'Reports, investigations, enforcement, and appeal review.', value: value.trustAndSafety },
      { key: 'commerce', label: 'Commerce outcomes', description: 'Orders, disputes, payments, and authoritative refund states.', value: value.commerce },
      { key: 'support', label: 'Support operations', description: 'Customer workload and response timing.', value: value.support },
      { key: 'catalog', label: 'Catalog quality', description: 'Listing lifecycle and bounded category signals.', value: value.catalog },
      { key: 'operations', label: 'System operations', description: 'Health, durable work, reconciliation, inventory, and search.', value: value.operations },
      { key: 'governance', label: 'Admin governance', description: 'Permission-aware approvals and temporary authority signals.', value: value.governance },
    ];
  });

  rangePreset: AnalyticsRangePreset = 'LAST_30_DAYS';
  compare = true;
  trendMetric: AnalyticsTrendMetric = 'REPORTS_SUBMITTED';
  customFrom: string;
  customThrough: string;
  readonly maxCustomDate: string;

  constructor() {
    const current = this.now();
    const defaults = defaultAnalyticsCustomDates(current);
    this.customFrom = defaults.from;
    this.customThrough = defaults.through;
    this.maxCustomDate = current.toISOString().slice(0, 10);
  }

  ngOnInit(): void { this.refresh(); }

  ngOnDestroy(): void {
    this.overviewRequest?.unsubscribe();
    this.trendRequest?.unsubscribe();
  }

  selectPreset(value: AnalyticsRangePreset): void {
    this.rangePreset = value;
    if (value !== 'CUSTOM') this.refresh();
  }

  comparisonChanged(): void { this.refresh(); }

  selectTrend(value: AnalyticsTrendMetric): void {
    this.trendMetric = value;
    const range = this.activeRange();
    if (range) this.loadTrend(range);
  }

  refresh(): void {
    const resolution = resolveAnalyticsRange(
      this.rangePreset, this.customFrom, this.customThrough, this.now(),
    );
    this.rangeError.set(resolution.error);
    if (!resolution.range) return;
    this.activeRange.set(resolution.range);
    this.loadOverview(resolution.range);
    this.loadTrend(resolution.range);
  }

  drillDown(key: string | null | undefined): AnalyticsDrillDownTarget | null {
    return analyticsDrillDownTarget(key);
  }

  format(value: number | null | undefined, unit: string): string {
    return formatAnalyticsValue(value, unit);
  }

  comparison(metric: AnalyticsMetric): string { return analyticsComparisonSummary(metric, this.compare); }
  metricLabel(metric: AnalyticsMetric): string {
    return metric.drillDownKey === 'COMPLETED_ORDERS' ? 'Fully delivered orders' : metric.label;
  }
  metricAriaLabel(metric: AnalyticsMetric): string {
    return `${this.metricLabel(metric)}: ${this.format(metric.currentValue, metric.unit)}. ${this.comparison(metric)}. Open owning workflow.`;
  }
  secondary(item: AnalyticsBreakdownItem, unit: string): string {
    return item.secondaryValue === undefined || item.secondaryValue === null ? '—' : this.format(item.secondaryValue, unit);
  }
  statusCount(status: AnalyticsSectionStatus): number {
    return this.sectionEntries().filter(section => section.value.status === status).length;
  }
  statusLabel(status: AnalyticsSectionStatus): string {
    return status.toLowerCase().replace(/^./, value => value.toUpperCase());
  }
  emptyMessage(status: AnalyticsSectionStatus): string {
    if (status === 'RESTRICTED') return 'This section is outside your current analytics visibility.';
    if (status === 'UNAVAILABLE') return 'This source is temporarily unavailable.';
    return 'No metrics were returned for this range.';
  }

  private loadOverview(range: ResolvedAnalyticsRange): void {
    this.overviewRequest?.unsubscribe();
    this.overview.set(null);
    this.overviewLoading.set(true);
    this.overviewError.set('');
    this.overviewRequest = this.api.overview({
      range: 'CUSTOM',
      from: range.from,
      to: range.to,
      timezone: range.timezone,
      compare: this.compare,
    }).pipe(finalize(() => this.overviewLoading.set(false))).subscribe({
      next: value => this.overview.set(value),
      error: () => this.overviewError.set('The bounded analytics read could not be completed. No operational data was changed.'),
    });
  }

  private loadTrend(range: ResolvedAnalyticsRange): void {
    this.trendRequest?.unsubscribe();
    this.trend.set(null);
    this.trendLoading.set(true);
    this.trendError.set('');
    this.trendRequest = this.api.trend({
      range: 'CUSTOM',
      metric: this.trendMetric,
      from: range.from,
      to: range.to,
      timezone: range.timezone,
      granularity: analyticsGranularity(range),
    }).pipe(finalize(() => this.trendLoading.set(false))).subscribe({
      next: value => this.trend.set(value),
      error: () => this.trendError.set('Trend data is temporarily unavailable. The summary above remains usable.'),
    });
  }
}
