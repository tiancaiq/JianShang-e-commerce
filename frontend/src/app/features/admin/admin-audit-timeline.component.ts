import { DatePipe, KeyValuePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { AdminTimelineEntry } from '../../core/models/admin-timeline.model';

@Component({
  selector: 'app-admin-audit-timeline',
  standalone: true,
  imports: [DatePipe, KeyValuePipe],
  template: `
    <section class="audit-panel" [attr.aria-label]="title()">
      <h2>{{ title() }}</h2>
      @if (loading()) {
        <p class="empty">Loading audit history...</p>
      } @else if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      } @else if (entries().length === 0) {
        <p class="empty">No audit events are available.</p>
      } @else {
        <ol class="timeline">
          @for (entry of entries(); track entry.eventId || entry.eventType + entry.occurredAt) {
            <li>
              <div class="event-header">
                <strong>{{ eventLabel(entry.eventType) }}</strong>
                <time [attr.datetime]="entry.occurredAt">{{ entry.occurredAt | date: 'medium' }}</time>
              </div>
              <p class="actor">
                {{ entry.actorDisplay || entry.actorId || 'System' }}
                <span>{{ entry.actorType }} / {{ entry.source }}</span>
              </p>
              @if (entry.previousState || entry.newState) {
                <p class="transition">
                  {{ entry.previousState || 'None' }} to {{ entry.newState || 'None' }}
                </p>
              }
              @if (entry.reason) {
                <p class="reason">{{ entry.reason }}</p>
              }
              <dl>
                @if (entry.eventId) {
                  <div><dt>Event ID</dt><dd>{{ entry.eventId }}</dd></div>
                }
                @if (entry.moderationCaseId) {
                  <div><dt>Case ID</dt><dd>{{ entry.moderationCaseId }}</dd></div>
                }
                @if (entry.correlationId) {
                  <div><dt>Correlation ID</dt><dd>{{ entry.correlationId }}</dd></div>
                }
                @for (item of entry.metadata | keyvalue; track item.key) {
                  <div><dt>{{ metadataLabel(item.key) }}</dt><dd>{{ item.value }}</dd></div>
                }
              </dl>
            </li>
          }
        </ol>
      }
    </section>
  `,
  styles: [`
    .audit-panel {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: rgba(18, 19, 23, 0.88);
      padding: 1rem;
    }

    h2 {
      margin: 0 0 0.9rem;
      color: var(--color-text-primary);
      font-size: 1rem;
    }

    .timeline {
      display: grid;
      gap: 0;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .timeline li {
      position: relative;
      padding: 0 0 1rem 1.25rem;
      border-left: 1px solid var(--color-border);
    }

    .timeline li::before {
      position: absolute;
      top: 0.25rem;
      left: -0.35rem;
      width: 0.625rem;
      height: 0.625rem;
      border-radius: 50%;
      background: var(--color-info);
      content: '';
    }

    .timeline li:last-child { padding-bottom: 0; }

    .event-header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
    }

    strong { color: var(--color-text-primary); }
    time, .actor span, dt { color: var(--color-text-muted); font-size: 0.75rem; }
    p { margin: 0.3rem 0 0; color: var(--color-text-secondary); }
    .actor span { margin-left: 0.4rem; }
    .transition { color: var(--color-info); font-weight: 700; }
    .reason { color: var(--color-text-primary); }
    .empty { margin: 0; }
    .error { margin: 0; color: var(--color-danger); }

    dl {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem 1rem;
      margin: 0.5rem 0 0;
    }

    dl div { min-width: 0; }
    dt { font-weight: 700; }
    dd { margin: 0.1rem 0 0; color: var(--color-text-secondary); overflow-wrap: anywhere; }
  `],
})
export class AdminAuditTimelineComponent {
  readonly entries = input.required<AdminTimelineEntry[]>();
  readonly loading = input(false);
  readonly error = input('');
  readonly title = input('Audit timeline');

  eventLabel(value: string): string {
    const label = value.toLowerCase().replace(/_/g, ' ');
    return label.charAt(0).toUpperCase() + label.slice(1);
  }

  metadataLabel(value: string): string {
    return value.replace(/([a-z])([A-Z])/g, '$1 $2');
  }
}
