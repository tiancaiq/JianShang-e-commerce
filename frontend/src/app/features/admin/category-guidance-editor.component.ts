import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Category } from '../../core/models/listing.model';
import { CategoryGuidanceSource } from '../../core/models/category-guidance.model';
import { CategoryGuidanceService } from '../../core/services/category-guidance.service';
import { ListingService } from '../../core/services/listing.service';

@Component({
  selector: 'app-category-guidance-editor',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="guidance-page">
      <header>
        <div>
          <p class="eyebrow">AI knowledge authority</p>
          <h2>Category guidance</h2>
          <p>Publish human-authored, public buying guidance. This form never generates content with AI.</p>
        </div>
        @if (current()) {
          <span class="status" [class.retired]="current()?.lifecycle === 'INVALIDATED'">
            {{ current()?.lifecycle }} · v{{ current()?.sourceVersion }}
          </span>
        }
      </header>

      <div class="selector-row">
        <label>
          Category
          <select [(ngModel)]="categoryId" (ngModelChange)="loadCurrent()">
            @for (category of categories(); track category.id) {
              <option [value]="category.id">{{ category.name }}</option>
            }
          </select>
        </label>
        <label>
          Language
          <input
            [(ngModel)]="language"
            (change)="loadCurrent()"
            maxlength="35"
            autocomplete="off"
            aria-describedby="language-help">
          <small id="language-help">Use a language tag such as en or en-us.</small>
        </label>
      </div>

      @if (loading()) {
        <p class="notice">Loading current guidance…</p>
      }
      @if (message()) {
        <p class="notice" [class.error]="error()">{{ message() }}</p>
      }

      <div class="editor">
        <label>
          Guidance title
          <input [(ngModel)]="title" maxlength="180" placeholder="Buying used electronics">
          <small>{{ title.trim().length }}/180</small>
        </label>
        <label>
          Guidance body
          <textarea
            [(ngModel)]="body"
            maxlength="12000"
            rows="14"
            placeholder="Write plain-text buying guidance for this category."></textarea>
          <small>{{ body.trim().length }}/12000 · plain text only</small>
        </label>
      </div>

      <div class="actions">
        <button type="button" class="primary" [disabled]="!canPublish()" (click)="publish()">
          {{ saving() ? 'Saving…' : current()?.lifecycle === 'ACTIVE' ? 'Publish new version' : 'Publish guidance' }}
        </button>
        <button
          type="button"
          class="danger"
          [disabled]="saving() || current()?.lifecycle !== 'ACTIVE'"
          (click)="retire()">
          Retire current guidance
        </button>
        <button type="button" [disabled]="saving()" (click)="loadCurrent()">Reload</button>
      </div>

      @if (current()?.contentHash) {
        <dl>
          <div><dt>Source version</dt><dd>{{ current()?.sourceVersion }}</dd></div>
          <div><dt>Content hash</dt><dd class="hash">{{ current()?.contentHash }}</dd></div>
          <div><dt>Effective from</dt><dd>{{ current()?.effectiveFrom }}</dd></div>
        </dl>
      }
    </section>
  `,
  styles: [`
    .guidance-page {
      display: grid;
      gap: 1.25rem;
      max-width: 900px;
    }

    header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    h2 { margin: 0.15rem 0 0.35rem; }
    header p { margin: 0; color: var(--color-text-secondary); }
    .eyebrow {
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .status {
      border: 1px solid rgba(34, 197, 94, 0.45);
      border-radius: 999px;
      color: #86efac;
      padding: 0.35rem 0.65rem;
      white-space: nowrap;
    }

    .status.retired {
      border-color: var(--color-border);
      color: var(--color-text-muted);
    }

    .selector-row {
      display: grid;
      grid-template-columns: 1fr minmax(180px, 0.35fr);
      gap: 1rem;
    }

    .editor {
      display: grid;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
    }

    label { display: grid; gap: 0.4rem; color: var(--color-text-secondary); font-weight: 700; }
    input, select, textarea {
      width: 100%;
      box-sizing: border-box;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      font: inherit;
      padding: 0.7rem 0.75rem;
    }

    textarea { resize: vertical; line-height: 1.55; }
    small { color: var(--color-text-muted); font-weight: 500; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.65rem; }
    button {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-secondary);
      color: var(--color-text-primary);
      cursor: pointer;
      font: inherit;
      font-weight: 750;
      padding: 0.65rem 0.9rem;
    }

    button.primary { border-color: var(--color-info); background: var(--color-info); color: #06131b; }
    button.danger { color: var(--color-danger); }
    button:disabled { cursor: not-allowed; opacity: 0.45; }
    .notice { margin: 0; color: var(--color-text-secondary); }
    .notice.error { color: var(--color-danger); }
    dl {
      display: grid;
      gap: 0.65rem;
      margin: 0;
      padding: 1rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    dl div { display: grid; grid-template-columns: 150px 1fr; gap: 1rem; }
    dt { color: var(--color-text-muted); }
    dd { margin: 0; }
    .hash { overflow-wrap: anywhere; font-family: monospace; }

    @media (max-width: 680px) {
      header { display: grid; }
      .selector-row { grid-template-columns: 1fr; }
      dl div { grid-template-columns: 1fr; gap: 0.2rem; }
    }
  `],
})
export class CategoryGuidanceEditorComponent implements OnInit {
  private readonly listingService = inject(ListingService);
  private readonly guidanceService = inject(CategoryGuidanceService);

  categories = signal<Category[]>([]);
  current = signal<CategoryGuidanceSource | null>(null);
  loading = signal(false);
  saving = signal(false);
  message = signal('');
  error = signal(false);

  categoryId = '';
  language = 'en';
  title = '';
  body = '';

  ngOnInit(): void {
    this.listingService.getCategories().subscribe({
      next: categories => {
        this.categories.set(categories);
        this.categoryId = categories[0]?.id ?? '';
        if (this.categoryId) {
          this.loadCurrent();
        }
      },
      error: () => this.showMessage('Categories could not be loaded.', true),
    });
  }

  canPublish(): boolean {
    return !this.saving()
      && !!this.categoryId
      && /^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$/.test(this.language.trim())
      && this.title.trim().length >= 1
      && this.title.trim().length <= 180
      && this.body.trim().length >= 1
      && this.body.trim().length <= 12000;
  }

  loadCurrent(): void {
    const categoryId = this.categoryId;
    const language = this.language.trim().toLowerCase();
    if (!categoryId || !language) {
      return;
    }
    this.loading.set(true);
    this.message.set('');
    this.guidanceService.current(categoryId, language).subscribe({
      next: source => {
        this.current.set(source);
        this.title = source.content?.title ?? '';
        this.body = source.content?.body ?? '';
        this.loading.set(false);
      },
      error: (response: HttpErrorResponse) => {
        this.loading.set(false);
        if (response.status === 404) {
          this.current.set(null);
          this.title = '';
          this.body = '';
          this.showMessage('No guidance has been published for this category and language.', false);
          return;
        }
        this.showMessage('Current guidance could not be loaded.', true);
      },
    });
  }

  publish(): void {
    if (!this.canPublish()
      || !window.confirm('Publish this text as a new public category-guidance version?')) {
      return;
    }
    this.saving.set(true);
    this.guidanceService.publish(
      this.categoryId,
      this.language.trim().toLowerCase(),
      this.current()?.sourceVersion ?? '0',
      { title: this.title.trim(), body: this.body.trim() },
    ).subscribe({
      next: source => {
        this.current.set(source);
        this.saving.set(false);
        this.showMessage(`Published version ${source.sourceVersion}.`, false);
      },
      error: (response: HttpErrorResponse) => this.handleSaveError(response),
    });
  }

  retire(): void {
    const source = this.current();
    if (!source || source.lifecycle !== 'ACTIVE'
      || !window.confirm('Retire this guidance? Agent retrieval will invalidate this source version.')) {
      return;
    }
    this.saving.set(true);
    this.guidanceService.retire(
      this.categoryId,
      this.language.trim().toLowerCase(),
      source.sourceVersion,
    ).subscribe({
      next: retired => {
        this.current.set(retired);
        this.saving.set(false);
        this.showMessage(`Retired with tombstone version ${retired.sourceVersion}.`, false);
      },
      error: (response: HttpErrorResponse) => this.handleSaveError(response),
    });
  }

  private handleSaveError(response: HttpErrorResponse): void {
    this.saving.set(false);
    if (response.status === 409) {
      this.loadCurrent();
      this.showMessage('This guidance changed in another request. Reloading the current version.', true);
      return;
    }
    this.showMessage('Category guidance could not be saved.', true);
  }

  private showMessage(message: string, error: boolean): void {
    this.message.set(message);
    this.error.set(error);
  }
}
