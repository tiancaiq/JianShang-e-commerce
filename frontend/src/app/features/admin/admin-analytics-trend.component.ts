import { DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { AnalyticsTrend } from '../../core/models/admin-analytics.model';
import { analyticsUnitLabel, formatAnalyticsValue } from './admin-analytics-format';

@Component({
  selector: 'app-admin-analytics-trend',
  standalone: true,
  imports: [DatePipe],
  template: `
    <section class="trend-panel" aria-labelledby="analytics-trend-heading">
      <header>
        <div>
          <p class="eyebrow">Market pulse</p>
          <h2 id="analytics-trend-heading">{{ trend()?.label || 'Operational trend' }}</h2>
        </div>
        @if (trend(); as series) {
          <span class="granularity">{{ series.granularity.toLowerCase() }} buckets · UTC</span>
        }
      </header>

      @if (loading()) {
        <div class="state" role="status">Loading trend points…</div>
      } @else if (error()) {
        <div class="state error" role="alert">{{ error() }}</div>
      } @else if (trend(); as series) {
        @if (series.safeMessage) {
          <p class="notice" [attr.data-status]="series.status">{{ series.safeMessage }}</p>
        }
        @if (series.points.length) {
          <div class="chart-summary">
            <span>Total in range <strong>{{ format(total(), series.unit) }}</strong></span>
            <span>Highest bucket <strong>{{ format(maximum(), series.unit) }}</strong></span>
          </div>
          <div class="plot" aria-hidden="true">
            <svg viewBox="0 0 760 220" preserveAspectRatio="none" focusable="false" aria-hidden="true">
              <line x1="30" y1="30" x2="740" y2="30" />
              <line x1="30" y1="110" x2="740" y2="110" />
              <line x1="30" y1="190" x2="740" y2="190" />
              <polyline [attr.points]="polylinePoints()" />
            </svg>
          </div>
          <div class="table-shell">
            <table>
              <caption>{{ series.label }} — complete accessible data</caption>
              <thead><tr><th scope="col">Bucket start (UTC)</th><th scope="col">Bucket end (exclusive, UTC)</th><th scope="col">{{ unitLabel(series.unit) }}</th></tr></thead>
              <tbody>
                @for (point of series.points; track point.bucketStart) {
                  <tr>
                    <td>{{ point.bucketStart | date:'medium':'UTC' }}</td>
                    <td>{{ point.bucketEnd | date:'medium':'UTC' }}</td>
                    <td>{{ format(point.value, series.unit) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          @if (series.status === 'AVAILABLE' || series.status === 'DEGRADED') {
            <div class="state">No activity was recorded for this trend in the selected range.</div>
          } @else {
            <div class="state">Trend values are unavailable from this source.</div>
          }
        }
      }
    </section>
  `,
  styles: [`
    :host{display:block}.trend-panel{display:grid;gap:1rem;padding:1.1rem;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}header,.chart-summary{display:flex;align-items:end;justify-content:space-between;gap:1rem}h2,p{margin:0}h2{margin-top:.2rem;font:800 1.35rem var(--font-display)}.eyebrow{color:var(--color-info);font-size:.68rem;font-weight:900;letter-spacing:.13em;text-transform:uppercase}.granularity{color:var(--color-text-muted);font:700 .7rem ui-monospace,monospace;text-transform:uppercase}.chart-summary{justify-content:flex-start;align-items:center;flex-wrap:wrap;color:var(--color-text-muted);font-size:.76rem}.chart-summary span{display:flex;gap:.4rem;align-items:baseline}.chart-summary strong{color:var(--color-text-primary);font-size:1rem}.plot{height:220px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:linear-gradient(180deg,rgba(56,189,248,.055),transparent);overflow:hidden}.plot svg{width:100%;height:100%}.plot line{stroke:var(--color-border);stroke-width:1;vector-effect:non-scaling-stroke}.plot polyline{fill:none;stroke:var(--color-info);stroke-width:3;stroke-linecap:round;stroke-linejoin:round;vector-effect:non-scaling-stroke}.table-shell{max-height:310px;overflow:auto;border:1px solid var(--color-border);border-radius:var(--radius-md)}table{width:100%;border-collapse:collapse}caption{padding:.7rem;text-align:left;color:var(--color-text-secondary);font-weight:800}th,td{padding:.65rem .75rem;text-align:left;border-top:1px solid var(--color-border);font-size:.76rem}th{position:sticky;top:0;background:var(--color-bg-secondary);color:var(--color-text-muted);font-size:.66rem;letter-spacing:.05em;text-transform:uppercase}td:last-child{font-variant-numeric:tabular-nums;font-weight:800}.notice,.state{padding:.85rem;border-left:4px solid var(--color-info);background:rgba(56,189,248,.08);color:var(--color-text-secondary)}.notice[data-status=DEGRADED]{border-color:var(--color-warning)}.notice[data-status=UNAVAILABLE],.notice[data-status=RESTRICTED],.state.error{border-color:var(--color-danger)}@media(max-width:620px){header{align-items:start;flex-direction:column}.plot{height:170px}.table-shell{max-height:260px}}
  `],
})
export class AdminAnalyticsTrendComponent {
  readonly trend = input<AnalyticsTrend | null>(null);
  readonly loading = input(false);
  readonly error = input('');

  readonly total = computed(() => this.trend()?.points.reduce((sum, point) => sum + point.value, 0) || 0);
  readonly maximum = computed(() => Math.max(0, ...(this.trend()?.points.map(point => point.value) || [])));
  readonly polylinePoints = computed(() => {
    const points = this.trend()?.points || [];
    if (!points.length) return '';
    const maximum = Math.max(1, ...points.map(point => point.value));
    const availableWidth = 710;
    return points.map((point, index) => {
      const x = points.length === 1 ? 385 : 30 + index * availableWidth / (points.length - 1);
      const y = 190 - Math.max(0, point.value) / maximum * 160;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');
  });

  format(value: number, unit: string): string { return formatAnalyticsValue(value, unit); }
  unitLabel(unit: string): string { return analyticsUnitLabel(unit); }
}
