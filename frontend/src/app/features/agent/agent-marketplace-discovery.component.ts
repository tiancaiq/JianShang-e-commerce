import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  ElementRef,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';
import { AGENT_CLIENT_MESSAGE_ID_FACTORY } from './agent-customer-service.capability';
import {
  DiscoveryHistoryMessage,
  DiscoveryRecommendation,
  DiscoverySession,
  DiscoveryTurnResult,
} from './agent-marketplace-discovery.model';
import {
  AgentMarketplaceDiscoveryService,
  DiscoveryAuthenticationRequiredError,
  DiscoveryContractError,
} from './agent-marketplace-discovery.service';

interface DiscoveryDisplayMessage {
  key: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  result: DiscoveryTurnResult | null;
  createdAt: string;
}

@Component({
  selector: 'app-agent-marketplace-discovery',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink],
  template: `
    <section class="discovery" aria-labelledby="discovery-title">
      <header class="discovery-hero">
        <div class="compass-mark" aria-hidden="true">
          <span></span>
        </div>
        <div>
          <span class="eyebrow">Conversational marketplace search</span>
          <h2 id="discovery-title">Describe what you are trying to find</h2>
          <p>
            Discovery searches current public individual listings. It can ask
            follow-up questions, but it cannot buy, message, reserve, or verify an item.
          </p>
        </div>
      </header>

      @if (!session() && !messages().length) {
        <div class="examples" aria-label="Example discovery prompts">
          <span>Try an example</span>
          <button type="button" (click)="useExample('A desk chair under $100 in Irvine')">
            Desk chair under $100
          </button>
          <button type="button" (click)="useExample('Comfort products for better sleep in Orange County')">
            Comfort products for sleep
          </button>
          <button type="button" (click)="useExample('A used bicycle for city riding')">
            Used city bicycle
          </button>
        </div>
      }

      @if (session() || messages().length) {
        <div class="session-bar">
          <div>
            <strong>{{ session()?.status === 'CLOSED' ? 'Closed search' : 'Current search' }}</strong>
            <span>Preference version {{ session()?.preferenceVersion ?? 0 }}</span>
          </div>
          <button
            type="button"
            class="secondary"
            [disabled]="loading() || sending()"
            (click)="requestNewSearch()">
            New search
          </button>
        </div>
      }

      @if (confirmNewSearch()) {
        <section class="confirmation" aria-labelledby="new-search-title">
          <div>
            <strong id="new-search-title">Start a new search?</strong>
            <span>The current history stays stored, but this page will open a clean session.</span>
          </div>
          <div>
            <button type="button" class="secondary" (click)="confirmNewSearch.set(false)">Keep current</button>
            <button type="button" (click)="startNewSearch()">Start new search</button>
          </div>
        </section>
      }

      <div
        #liveRegion
        class="live-region"
        role="status"
        tabindex="-1"
        aria-live="polite"
        aria-atomic="true">
        {{ liveStatus() }}
      </div>

      @if (authRequired()) {
        <section class="state-card error-card">
          <strong>Sign in again to continue.</strong>
          <span>Your typed search has not been sent.</span>
          <a routerLink="/login" [queryParams]="{ returnUrl: '/account/messages/agent' }">Sign in</a>
        </section>
      } @else if (errorMessage()) {
        <section class="state-card error-card">
          <strong>{{ errorMessage() }}</strong>
          <span>{{ errorGuidance() }}</span>
          @if (canRetryRead()) {
            <button type="button" class="secondary" (click)="refreshHistory()">Retry loading history</button>
          }
        </section>
      }

      @if (loading()) {
        <section class="state-card">
          <strong>Loading your discovery session…</strong>
          <span>No listing action is being taken.</span>
        </section>
      }

      @if (messages().length) {
        <section class="history" aria-label="Marketplace discovery history">
          @for (message of messages(); track message.key) {
            @if (message.role === 'USER') {
              <article class="message user-message">
                <span>You · {{ message.createdAt | date: 'shortTime' }}</span>
                <p>{{ message.body }}</p>
              </article>
            } @else if (message.result; as result) {
              <article class="message assistant-message" [attr.data-outcome]="result.outcome">
                <div class="answer-heading">
                  <span>Marketplace discovery</span>
                  <small>{{ outcomeLabel(result.outcome) }}</small>
                </div>
                <p>{{ result.message }}</p>

                @if (result.outcome === 'ASK_CLARIFY') {
                  <div class="clarification" aria-label="Follow-up questions">
                    @for (question of result.questions; track question) {
                      <button type="button" (click)="useExample(question)">{{ question }}</button>
                    }
                  </div>
                }

                @if (result.outcome === 'RECOMMEND') {
                  <div class="recommendation-grid" aria-label="Recommended listings">
                    @for (item of result.recommendations; track item.listingId) {
                      <article class="listing-card">
                        <div class="listing-card-top">
                          <span>{{ item.categoryName }}</span>
                          <strong>{{ item.priceAmount | currency: item.currency : 'symbol' : '1.0-2' }}</strong>
                        </div>
                        <h3>{{ item.title }}</h3>
                        <dl>
                          <div>
                            <dt>Condition</dt>
                            <dd>{{ conditionLabel(item.condition) }}</dd>
                          </div>
                          @if (locationLabel(item)) {
                            <div>
                              <dt>Public area</dt>
                              <dd>{{ locationLabel(item) }}</dd>
                            </div>
                          }
                        </dl>
                        <p class="match-reason">{{ item.matchReason }}</p>
                        <div class="provenance">
                          <span aria-hidden="true">✓</span>
                          <span>
                            Detail checked {{ item.provenance.checkedAt | date: 'medium' }}
                          </span>
                        </div>
                        <a [routerLink]="['/listings', item.listingId]">View listing</a>
                      </article>
                    }
                  </div>
                }

              </article>
            }
          }
        </section>

        @if (nextCursor()) {
          <button
            type="button"
            class="load-more secondary"
            [disabled]="loadingMore()"
            (click)="loadMoreHistory()">
            {{ loadingMore() ? 'Loading…' : 'Load more history' }}
          </button>
        }
      }

      <form class="composer" (ngSubmit)="submitPrompt()">
        <label for="discovery-prompt">
          {{ session() ? 'Refine this search' : 'What are you looking for?' }}
        </label>
        <textarea
          #promptInput
          id="discovery-prompt"
          name="discoveryPrompt"
          rows="4"
          maxlength="8000"
          [ngModel]="prompt()"
          (ngModelChange)="prompt.set($event)"
          [disabled]="sending() || loading()"
          placeholder="Example: A compact desk chair under $100 in Irvine"></textarea>
        <div class="composer-footer">
          <span>{{ prompt().length }}/8000</span>
          <button type="submit" [disabled]="sending() || loading() || !prompt().trim()">
            {{ sending() ? 'Searching…' : session() ? 'Send refinement' : 'Start discovery' }}
          </button>
        </div>
      </form>
    </section>
  `,
  styles: [`
    .discovery {
      display: grid;
      gap: 1rem;
      min-width: 0;
    }

    .discovery-hero {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 1rem;
      align-items: center;
      border: 1px solid color-mix(in srgb, var(--market-accent) 28%, var(--market-line));
      border-radius: 14px;
      background:
        linear-gradient(120deg, rgba(255, 255, 255, 0.98), rgba(244, 249, 247, 0.96));
      padding: clamp(1rem, 3vw, 1.65rem);
    }

    .compass-mark {
      display: grid;
      place-items: center;
      width: 3.25rem;
      height: 3.25rem;
      border: 2px solid var(--market-accent-dark);
      border-radius: 50%;
      background: white;
      transform: rotate(12deg);
    }

    .compass-mark span {
      width: 0;
      height: 0;
      border-right: 0.45rem solid transparent;
      border-bottom: 1.25rem solid var(--market-accent-dark);
      border-left: 0.45rem solid transparent;
    }

    .eyebrow {
      color: var(--market-accent-dark);
      font-size: 0.72rem;
      font-weight: 900;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    h2,
    h3,
    p {
      margin: 0;
    }

    h2 {
      margin-top: 0.15rem;
      font-size: clamp(1.5rem, 4vw, 2.25rem);
      line-height: 1.05;
    }

    .discovery-hero p {
      max-width: 68ch;
      margin-top: 0.5rem;
      color: var(--market-muted);
      line-height: 1.55;
    }

    .examples,
    .clarification {
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      align-items: center;
    }

    .examples > span {
      color: var(--market-muted);
      font-size: 0.8rem;
      font-weight: 800;
    }

    button,
    a {
      min-height: 2.65rem;
      border-radius: 8px;
      font: inherit;
      font-weight: 850;
    }

    button {
      border: 1px solid var(--market-accent-dark);
      background: var(--market-accent-dark);
      color: white;
      padding: 0.65rem 0.9rem;
      cursor: pointer;
    }

    button.secondary,
    .examples button,
    .clarification button {
      background: white;
      color: var(--market-accent-dark);
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }

    button:focus-visible,
    a:focus-visible,
    textarea:focus-visible {
      outline: 3px solid color-mix(in srgb, var(--market-accent) 55%, white);
      outline-offset: 2px;
    }

    .session-bar,
    .confirmation,
    .state-card {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: center;
      border: 1px solid var(--market-line);
      border-radius: 10px;
      background: white;
      padding: 0.85rem 1rem;
    }

    .session-bar div,
    .confirmation > div:first-child,
    .state-card {
      display: grid;
      gap: 0.2rem;
    }

    .session-bar span,
    .confirmation span,
    .state-card span {
      color: var(--market-muted);
      font-size: 0.84rem;
    }

    .confirmation > div:last-child {
      display: flex;
      gap: 0.55rem;
    }

    .error-card {
      border-color: #bd5a4a;
      background: #fff8f6;
    }

    .error-card a {
      display: inline-flex;
      align-items: center;
      width: max-content;
      color: var(--market-accent-dark);
    }

    .live-region {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }

    .history {
      display: grid;
      gap: 0.85rem;
    }

    .message {
      max-width: min(92%, 62rem);
      border-radius: 12px;
      padding: 0.9rem 1rem;
    }

    .message > span,
    .answer-heading {
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 800;
    }

    .message > p {
      margin-top: 0.35rem;
      line-height: 1.55;
    }

    .user-message {
      justify-self: end;
      background: var(--market-accent-dark);
      color: white;
    }

    .user-message > span {
      color: rgba(255, 255, 255, 0.76);
    }

    .assistant-message {
      justify-self: start;
      width: min(100%, 62rem);
      border: 1px solid var(--market-line);
      background: white;
    }

    .answer-heading {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
    }

    .recommendation-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr));
      gap: 0.75rem;
      margin-top: 0.9rem;
    }


    .listing-card {
      display: grid;
      gap: 0.65rem;
      min-width: 0;
      border: 1px solid var(--market-line);
      border-radius: 10px;
      background: #fbfdfc;
      padding: 0.85rem;
    }

    .listing-card-top {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      color: var(--market-muted);
      font-size: 0.75rem;
    }

    .listing-card-top strong {
      color: var(--market-ink);
    }

    .listing-card h3 {
      font-size: 1rem;
      overflow-wrap: anywhere;
    }

    dl {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem 1.25rem;
      margin: 0;
    }

    dt {
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 850;
      text-transform: uppercase;
    }

    dd {
      margin: 0.12rem 0 0;
      font-size: 0.84rem;
      font-weight: 750;
    }

    .match-reason {
      color: var(--market-muted);
      font-size: 0.84rem;
      line-height: 1.45;
    }

    .provenance {
      display: flex;
      gap: 0.35rem;
      align-items: flex-start;
      color: #356c59;
      font-size: 0.72rem;
      font-weight: 750;
    }

    .listing-card a {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      margin-top: auto;
      background: var(--market-accent-dark);
      color: white;
      padding: 0.55rem 0.75rem;
      text-decoration: none;
    }

    .load-more {
      justify-self: start;
    }

    .composer {
      display: grid;
      gap: 0.5rem;
      position: sticky;
      bottom: 0.5rem;
      z-index: 2;
      border: 1px solid var(--market-line);
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.97);
      box-shadow: 0 12px 28px rgba(29, 52, 43, 0.1);
      padding: 0.85rem;
    }

    .composer label {
      font-weight: 900;
    }

    textarea {
      box-sizing: border-box;
      width: 100%;
      resize: vertical;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      padding: 0.75rem;
      color: var(--market-ink);
      font: inherit;
      line-height: 1.45;
    }

    .composer-footer {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: center;
    }

    .composer-footer span {
      color: var(--market-muted);
      font-size: 0.75rem;
    }

    @media (max-width: 640px) {
      .discovery-hero {
        grid-template-columns: 1fr;
      }

      .compass-mark {
        width: 2.7rem;
        height: 2.7rem;
      }

      .session-bar,
      .confirmation {
        align-items: stretch;
        flex-direction: column;
      }

      .confirmation > div:last-child,
      .confirmation button {
        width: 100%;
      }

      .message {
        max-width: 100%;
      }

      .composer-footer {
        align-items: stretch;
        flex-direction: column;
      }

      .composer-footer button {
        width: 100%;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        scroll-behavior: auto !important;
      }
    }
  `],
})
export class AgentMarketplaceDiscoveryComponent {
  private readonly service = inject(AgentMarketplaceDiscoveryService);
  private readonly idFactory = inject(AGENT_CLIENT_MESSAGE_ID_FACTORY);

  @ViewChild('promptInput') private promptInput?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('liveRegion') private liveRegion?: ElementRef<HTMLElement>;

  readonly prompt = signal('');
  readonly session = signal<DiscoverySession | null>(null);
  readonly messages = signal<DiscoveryDisplayMessage[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly sending = signal(false);
  readonly confirmNewSearch = signal(false);
  readonly authRequired = signal(false);
  readonly errorMessage = signal('');
  readonly errorGuidance = signal('');
  readonly canRetryRead = signal(false);
  readonly liveStatus = signal('');

  /** Copies an example or follow-up into the composer without issuing a request. */
  useExample(value: string): void {
    this.prompt.set(value);
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  /** Starts/resumes only after submit, then sends the exact explicit prompt once. */
  submitPrompt(): void {
    const body = this.prompt().trim();
    if (!body || body.length > 8_000 || hasUnsafeControls(body)
      || this.sending() || this.loading()) {
      if (body && (body.length > 8_000 || hasUnsafeControls(body))) {
        this.showError(
          'This search cannot be sent.',
          'Remove control characters and keep the prompt within 8,000 characters.',
        );
      }
      return;
    }
    this.clearError();
    const current = this.session();
    if (current) {
      this.send(current, body);
      return;
    }

    this.loading.set(true);
    this.liveStatus.set('Opening your marketplace discovery session.');
    this.service.createOrResumeSession(false).pipe(
      switchMap(session => {
        this.session.set(session);
        return this.service.getMessages(session.id, null, 50);
      }),
      finalize(() => this.loading.set(false)),
    ).subscribe({
      next: page => {
        this.applyHistory(page.data, page.nextCursor);
        const opened = this.session();
        if (opened) {
          this.send(opened, body);
        }
      },
      error: error => this.handleReadError(error),
    });
  }

  /** Requires an explicit second action before replacing a session with history. */
  requestNewSearch(): void {
    if (this.messages().length) {
      this.confirmNewSearch.set(true);
      queueMicrotask(() => this.liveRegion?.nativeElement.focus());
      return;
    }
    this.startNewSearch();
  }

  /** Confirms and creates a clean server-owned search session without carrying history. */
  startNewSearch(): void {
    if (this.loading() || this.sending()) {
      return;
    }
    this.confirmNewSearch.set(false);
    this.clearError();
    this.loading.set(true);
    this.liveStatus.set('Starting a new discovery session.');
    this.service.createOrResumeSession(true)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: session => {
          this.session.set(session);
          this.messages.set([]);
          this.nextCursor.set(null);
          this.prompt.set('');
          this.liveStatus.set('New discovery session ready.');
          queueMicrotask(() => this.promptInput?.nativeElement.focus());
        },
        error: error => this.handleReadError(error),
      });
  }

  /** Reloads authoritative session/version state and its first stored history page. */
  refreshHistory(preserveNotice = false): void {
    const current = this.session();
    if (!current || this.loading()) {
      return;
    }
    if (!preserveNotice) {
      this.clearError();
    }
    this.loading.set(true);
    this.service.getSession(current.id).pipe(
      switchMap(session => {
        this.session.set(session);
        return this.service.getMessages(session.id, null, 50);
      }),
      finalize(() => this.loading.set(false)),
    ).subscribe({
      next: page => {
        this.applyHistory(page.data, page.nextCursor);
        this.liveStatus.set(
          preserveNotice
            ? 'History refreshed. Explicitly resubmit when ready.'
            : 'Discovery history refreshed.',
        );
      },
      error: error => this.handleReadError(error),
    });
  }

  /** Appends an explicitly requested older cursor page to rendered history. */
  loadMoreHistory(): void {
    const current = this.session();
    const cursor = this.nextCursor();
    if (!current || !cursor || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.service.getMessages(current.id, cursor, 50)
      .pipe(finalize(() => this.loadingMore.set(false)))
      .subscribe({
        next: page => {
          const earlier = page.data.map(toDisplayMessage);
          this.messages.update(messages => [...messages, ...earlier]);
          this.nextCursor.set(page.nextCursor);
          this.liveStatus.set('More discovery history loaded.');
        },
        error: error => this.handleReadError(error),
      });
  }

  outcomeLabel(outcome: DiscoveryTurnResult['outcome']): string {
    return {
      ASK_CLARIFY: 'Follow-up',
      RECOMMEND: 'Recommendations',
      NO_RESULTS: 'No current matches',
      REFUSE: 'Cannot assist',
      HANDOFF: 'Search unavailable',
    }[outcome];
  }

  conditionLabel(condition: DiscoveryRecommendation['condition']): string {
    return condition.replaceAll('_', ' ').toLowerCase()
      .replace(/^\w/, letter => letter.toUpperCase());
  }

  locationLabel(item: DiscoveryRecommendation): string {
    return [item.publicCity, item.publicRegion].filter(Boolean).join(', ');
  }

  /** Sends the prompt once and advances preference version only from success. */
  private send(session: DiscoverySession, body: string): void {
    const clientMessageId = this.idFactory();
    this.sending.set(true);
    this.liveStatus.set('Searching current public listings.');
    this.service.sendMessage(
      session.id,
      clientMessageId,
      session.preferenceVersion,
      body,
    ).pipe(finalize(() => this.sending.set(false))).subscribe({
      next: response => {
        this.session.update(current => current ? {
          ...current,
          preferenceState: response.result.preferenceState,
          preferenceVersion: response.preferenceVersion,
          updatedAt: response.userMessage.createdAt,
        } : current);
        this.messages.update(messages => [
          ...messages,
          {
            key: response.userMessage.id,
            role: 'USER',
            body: response.userMessage.body,
            result: null,
            createdAt: response.userMessage.createdAt,
          },
          {
            key: `result-${response.userMessage.id}`,
            role: 'ASSISTANT',
            body: response.result.message,
            result: response.result,
            createdAt: response.userMessage.createdAt,
          },
        ]);
        this.prompt.set('');
        this.liveStatus.set(this.outcomeLabel(response.result.outcome));
        queueMicrotask(() => this.liveRegion?.nativeElement.focus());
      },
      error: error => this.handleSendError(error),
    });
  }

  /** Refreshes a version conflict without replaying the submitted prompt. */
  private refreshAfterConflict(): void {
    this.showError(
      'This search changed in another request.',
      'History was refreshed. Review it, then explicitly send your refinement again.',
    );
    this.canRetryRead.set(false);
    this.refreshHistory(true);
  }

  private applyHistory(
    history: DiscoveryHistoryMessage[],
    nextCursor: string | null,
  ): void {
    this.messages.set(history.map(toDisplayMessage));
    this.nextCursor.set(nextCursor);
  }

  private handleSendError(error: unknown): void {
    if (isAuthenticationError(error)) {
      this.authRequired.set(true);
      this.liveStatus.set('Authentication required. Your search was not sent.');
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.refreshAfterConflict();
      return;
    }
    if (error instanceof DiscoveryContractError) {
      this.showError(
        'The discovery response was rejected.',
        'No unsafe or incomplete result was displayed. You may explicitly try again.',
      );
      return;
    }
    this.showError(
      'The search result is uncertain.',
      'Your text was kept. It was not automatically sent again; retry only when you choose.',
    );
  }

  private handleReadError(error: unknown): void {
    if (isAuthenticationError(error)) {
      this.authRequired.set(true);
      this.liveStatus.set('Authentication required.');
      return;
    }
    if (error instanceof DiscoveryContractError) {
      this.showError(
        'Stored discovery history was rejected.',
        'The response did not match the safe marketplace contract.',
      );
      return;
    }
    this.showError(
      'Marketplace discovery is temporarily unavailable.',
      'Normal marketplace browsing still works. Retry loading when ready.',
      true,
    );
  }

  private showError(message: string, guidance: string, retryRead = false): void {
    this.errorMessage.set(message);
    this.errorGuidance.set(guidance);
    this.canRetryRead.set(retryRead);
    this.liveStatus.set(message);
  }

  private clearError(): void {
    this.authRequired.set(false);
    this.errorMessage.set('');
    this.errorGuidance.set('');
    this.canRetryRead.set(false);
  }
}

function toDisplayMessage(message: DiscoveryHistoryMessage): DiscoveryDisplayMessage {
  return {
    key: message.id,
    role: message.role,
    body: message.body,
    result: message.result,
    createdAt: message.createdAt,
  };
}

function isAuthenticationError(error: unknown): boolean {
  return error instanceof DiscoveryAuthenticationRequiredError
    || (error instanceof HttpErrorResponse && error.status === 401);
}

function hasUnsafeControls(value: string): boolean {
  return /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value);
}
