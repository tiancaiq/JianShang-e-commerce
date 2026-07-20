import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChatService } from '../../core/services/chat.service';
import {
  AGENT_CLIENT_MESSAGE_ID_FACTORY,
  AGENT_CUSTOMER_SERVICE_ENABLED,
} from './agent-customer-service.capability';
import {
  AgentAnswerAction,
  AgentMessage,
  AgentSession,
} from './agent-customer-service.model';
import {
  AgentContractError,
  AgentCustomerService,
} from './agent-customer-service.service';
import { AgentListingSelection, toAgentListingSelection } from './agent-listing-context.model';
import { AgentListingContextPickerComponent } from './agent-listing-context-picker.component';

interface PendingQuestion {
  clientMessageId: string;
  body: string;
}

@Component({
  selector: 'app-agent-customer-service-thread',
  standalone: true,
  imports: [AgentListingContextPickerComponent, DatePipe, FormsModule, RouterLink],
  template: `
    @if (enabled) {
    <section
      class="agent-thread"
      [class.compact]="compact"
      aria-label="Marketplace AI assistant">
      <header class="agent-header">
        <div class="agent-mark" aria-hidden="true">AI</div>
        <div class="agent-heading">
          <span>Marketplace help</span>
          <h2>{{ session()?.subjectListing?.title || 'Ask about a listing' }}</h2>
        </div>
        <span class="ai-label">AI assistant</span>
      </header>

      @if (session(); as activeSession) {
        <section class="listing-context">
          @if (activeSession.subjectListing.thumbnailUrl) {
            <img
              [src]="thumbnailUrl(activeSession.subjectListing.thumbnailUrl)"
              [alt]="activeSession.subjectListing.title" />
          }
          <div>
            <strong>{{ activeSession.subjectListing.title }}</strong>
            <span>{{ activeSession.subjectListing.transactionNotice }}</span>
          </div>
          <a [routerLink]="['/listings', activeSession.subjectListing.id]">View listing</a>
        </section>
      }

      @if (loading()) {
        <div class="agent-state" role="status" aria-live="polite">
          <span class="loading-pulse" aria-hidden="true"></span>
          <strong>Opening listing help...</strong>
          <span>{{ selectedListing()?.title || 'Current listing' }} is being checked.</span>
        </div>
      } @else if (!session()) {
        <app-agent-listing-context-picker
          [disabled]="loading()"
          (listingSelected)="openListingSession($event)" />
        @if (listingSelectionError()) {
          <section class="context-error" role="alert">
            <div>
              <strong>{{ listingUnavailable() ? 'This listing is unavailable' : 'Listing help could not open' }}</strong>
              <span>{{ listingSelectionError() }}</span>
            </div>
            @if (selectedListing() && !listingUnavailable()) {
              <button type="button" (click)="openListingSession()">Retry</button>
            }
          </section>
        }
      } @else {
        <section class="message-list" aria-live="polite">
          @if (!messages().length && !pendingQuestion()) {
            <div class="agent-state">
              <strong>What would you like to know?</strong>
              <span>Ask about information shown on this listing. Seller-only questions will be handed back to the seller.</span>
            </div>
          }

          @for (message of messages(); track message.id) {
            <article
              class="agent-message"
              [class.user-message]="message.role === 'USER'"
              [class.assistant-message]="message.role === 'ASSISTANT'">
              <span class="message-author">
                {{ message.role === 'USER' ? 'You' : 'AI assistant' }}
                · {{ message.createdAt | date: 'shortTime' }}
              </span>
              <p>{{ message.body }}</p>

              @if (message.role === 'ASSISTANT' && message.sources.length) {
                <section class="sources" aria-label="Answer sources">
                  <strong>Sources</strong>
                  <ul>
                    @for (source of message.sources; track source.sourceType + source.sourceId + source.sourceVersion) {
                      <li>
                        <span>{{ source.label }}</span>
                        <small>{{ source.sourceType }} · {{ source.sourceId }} · v{{ source.sourceVersion }}</small>
                      </li>
                    }
                  </ul>
                </section>
              }

              @if (message.role === 'ASSISTANT' && message.actions.length) {
                <div class="answer-actions">
                  @for (action of message.actions; track action.type) {
                    <button type="button" (click)="runAction(action)" [disabled]="actionLoading()">
                      {{ actionLabel(action) }}
                    </button>
                  }
                </div>
              }

              @if (message.resolutionType === 'CONTACT_SELLER') {
                <span class="handoff-note">This needs the seller’s answer. Nothing has been sent automatically.</span>
              }
            </article>
          }

          @if (sending()) {
            <article class="agent-message user-message pending-message">
              <span class="message-author">You · sending</span>
              <p>{{ pendingQuestion()?.body }}</p>
            </article>
            <div class="thinking-state" role="status">Checking approved listing sources…</div>
          }
        </section>

        @if (errorMessage()) {
          <section class="outage-state" role="alert">
            <div>
              <strong>{{ listingUnavailable() ? 'This listing is unavailable' : 'Listing help is temporarily unavailable' }}</strong>
              <span>{{ errorMessage() }}</span>
            </div>
            @if (pendingQuestion() && !listingUnavailable()) {
              <button type="button" (click)="retryQuestion()" [disabled]="sending()">Retry question</button>
            } @else {
              <a routerLink="/marketplace">Browse marketplace</a>
            }
          </section>
        }

        @if (session()?.status === 'READ_ONLY' || session()?.status === 'CLOSED' || listingUnavailable()) {
          <footer class="read-only">
            <span>This assistant thread is read-only.</span>
            <a routerLink="/marketplace">Find another listing</a>
          </footer>
        } @else {
          <form class="composer" (ngSubmit)="sendQuestion()">
            <textarea
              name="agentQuestion"
              rows="2"
              maxlength="8000"
              [ngModel]="draft()"
              (ngModelChange)="draft.set($event)"
              placeholder="Ask about this listing"></textarea>
            <div>
              <span>{{ draft().length }}/8000</span>
              <button type="submit" [disabled]="sending() || !draft().trim()">Ask</button>
            </div>
          </form>
        }
      }
    </section>
    }
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
      min-height: 0;
      height: 100%;
    }

    .agent-thread {
      display: flex;
      flex-direction: column;
      min-width: 0;
      min-height: 560px;
      height: 100%;
      overflow: hidden;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-ink);
    }

    .agent-thread.compact {
      min-height: 0;
      border: 0;
      border-radius: 0;
    }

    .agent-header {
      display: grid;
      grid-template-columns: 44px minmax(0, 1fr) max-content;
      gap: 0.7rem;
      align-items: center;
      flex: 0 0 auto;
      border-bottom: 1px solid var(--market-line);
      background: #fff8fc;
      padding: 0.78rem 0.9rem;
    }

    .agent-mark {
      width: 44px;
      height: 44px;
      display: grid;
      place-items: center;
      border-radius: 8px;
      background: linear-gradient(135deg, #f7b84b, #ffd0e4);
      color: var(--market-accent-dark);
      font-size: 0.8rem;
      font-weight: 950;
    }

    .agent-heading {
      min-width: 0;
    }

    .agent-heading span,
    .ai-label {
      color: var(--market-accent-dark);
      font-size: 0.68rem;
      font-weight: 950;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    h2,
    p {
      margin: 0;
    }

    h2 {
      overflow: hidden;
      margin-top: 0.12rem;
      font-size: 0.98rem;
      font-weight: 950;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .ai-label {
      border: 1px solid rgba(190, 47, 118, 0.18);
      border-radius: 999px;
      background: #fff;
      padding: 0.28rem 0.5rem;
      white-space: nowrap;
    }

    .listing-context {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) max-content;
      gap: 0.7rem;
      align-items: center;
      flex: 0 0 auto;
      border-bottom: 1px solid var(--market-line);
      background: #fffdf8;
      padding: 0.7rem 0.9rem;
    }

    .listing-context img {
      width: 42px;
      height: 42px;
      border-radius: 7px;
      object-fit: cover;
    }

    .listing-context div {
      display: grid;
      gap: 0.15rem;
      min-width: 0;
    }

    .listing-context strong {
      overflow: hidden;
      font-size: 0.82rem;
      font-weight: 950;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .listing-context span {
      color: #754f19;
      font-size: 0.7rem;
      font-weight: 800;
      line-height: 1.35;
    }

    a {
      color: var(--market-accent-dark);
      font-size: 0.76rem;
      font-weight: 900;
      text-decoration: none;
    }

    .agent-state {
      width: min(420px, calc(100% - 2rem));
      display: grid;
      gap: 0.65rem;
      align-self: center;
      margin: auto;
      box-sizing: border-box;
      text-align: left;
    }

    .agent-state strong {
      font-size: 1rem;
      font-weight: 950;
    }

    .agent-state span {
      color: var(--market-muted);
      font-size: 0.8rem;
      font-weight: 750;
      line-height: 1.45;
    }

    .composer textarea {
      box-sizing: border-box;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      padding: 0.62rem 0.7rem;
    }

    .composer textarea:focus {
      outline: 2px solid rgba(190, 47, 118, 0.18);
      border-color: rgba(190, 47, 118, 0.45);
    }

    .composer > div,
    .answer-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.6rem;
    }

    button {
      min-height: 36px;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-size: 0.76rem;
      font-weight: 900;
      padding: 0 0.8rem;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.58;
    }

    .context-error {
      width: min(520px, calc(100% - 2rem));
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      align-self: center;
      margin: -0.65rem auto 1rem;
      box-sizing: border-box;
      border: 1px solid rgba(199, 69, 94, 0.18);
      border-radius: 8px;
      background: #fff8f8;
      padding: 0.68rem 0.85rem;
    }

    .context-error div {
      display: grid;
      gap: 0.15rem;
      min-width: 0;
    }

    .context-error strong {
      font-size: 0.78rem;
      font-weight: 950;
    }

    .context-error span {
      color: var(--market-muted);
      font-size: 0.7rem;
      font-weight: 800;
      overflow-wrap: anywhere;
    }

    .message-list {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      gap: 0.75rem;
      overflow-y: auto;
      min-height: 0;
      padding: 0.9rem;
    }

    .agent-state {
      place-items: center;
      margin: auto;
      text-align: center;
    }

    .loading-pulse {
      width: 34px;
      height: 34px;
      border: 3px solid #f4d8e8;
      border-top-color: var(--market-accent-dark);
      border-radius: 999px;
      animation: spin 0.9s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .agent-message {
      width: fit-content;
      max-width: min(620px, 84%);
      align-self: flex-start;
    }

    .agent-message.user-message {
      align-self: flex-end;
    }

    .message-author {
      display: block;
      margin-bottom: 0.22rem;
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 850;
    }

    .user-message .message-author {
      text-align: right;
    }

    .agent-message > p {
      border: 1px solid var(--market-line);
      border-radius: 16px 16px 16px 6px;
      background: #fff8fc;
      overflow-wrap: anywhere;
      padding: 0.68rem 0.78rem;
      white-space: pre-wrap;
      line-height: 1.45;
      font-weight: 750;
    }

    .user-message > p {
      border-color: rgba(190, 47, 118, 0.28);
      border-radius: 16px 16px 6px 16px;
      background: var(--market-accent-dark);
      color: #fff;
    }

    .pending-message {
      opacity: 0.68;
    }

    .thinking-state {
      align-self: flex-start;
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 850;
    }

    .sources {
      margin-top: 0.4rem;
      border-left: 3px solid #f7b84b;
      background: #fffdf8;
      padding: 0.55rem 0.65rem;
    }

    .sources > strong {
      color: #754f19;
      font-size: 0.68rem;
      font-weight: 950;
      text-transform: uppercase;
    }

    .sources ul {
      display: grid;
      gap: 0.35rem;
      margin: 0.35rem 0 0;
      padding: 0;
      list-style: none;
    }

    .sources li {
      display: grid;
      gap: 0.08rem;
    }

    .sources li span {
      font-size: 0.74rem;
      font-weight: 900;
    }

    .sources li small {
      color: var(--market-muted);
      font-size: 0.62rem;
      overflow-wrap: anywhere;
    }

    .answer-actions {
      justify-content: flex-start;
      margin-top: 0.5rem;
      flex-wrap: wrap;
    }

    .handoff-note {
      display: block;
      margin-top: 0.45rem;
      color: #754f19;
      font-size: 0.7rem;
      font-weight: 850;
    }

    .outage-state,
    .read-only {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      flex: 0 0 auto;
      border-top: 1px solid rgba(199, 69, 94, 0.18);
      background: #fff8f8;
      padding: 0.68rem 0.85rem;
    }

    .outage-state div {
      display: grid;
      gap: 0.16rem;
    }

    .outage-state strong {
      font-size: 0.78rem;
      font-weight: 950;
    }

    .outage-state span,
    .read-only span {
      color: var(--market-muted);
      font-size: 0.7rem;
      font-weight: 800;
    }

    .read-only {
      border-color: var(--market-line);
      background: #fffdf8;
    }

    .composer {
      display: grid;
      gap: 0.42rem;
      flex: 0 0 auto;
      border-top: 1px solid var(--market-line);
      background: #fff8fc;
      padding: 0.7rem 0.85rem;
    }

    .composer textarea {
      width: 100%;
      min-height: 48px;
      max-height: 130px;
      resize: vertical;
      line-height: 1.4;
    }

    .composer > div span {
      color: var(--market-muted);
      font-size: 0.68rem;
      font-weight: 800;
    }

    @media (max-width: 640px) {
      .agent-header {
        grid-template-columns: 40px minmax(0, 1fr);
      }

      .ai-label {
        grid-column: 2;
        width: max-content;
      }

      .listing-context {
        grid-template-columns: minmax(0, 1fr) max-content;
      }

      .listing-context img {
        display: none;
      }

      .agent-message {
        max-width: 92%;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-pulse {
        animation: none;
      }
    }
  `],
})
export class AgentCustomerServiceThreadComponent implements OnInit {
  @Input() sessionId: string | null = null;
  @Input() initialListing: AgentListingSelection | null = null;
  @Input() compact = false;
  @Output() readonly closeRequested = new EventEmitter<void>();

  readonly enabled = inject(AGENT_CUSTOMER_SERVICE_ENABLED);
  private readonly idFactory = inject(AGENT_CLIENT_MESSAGE_ID_FACTORY);
  private readonly agentService = inject(AgentCustomerService);
  private readonly chatService = inject(ChatService);
  private readonly router = inject(Router);

  session = signal<AgentSession | null>(null);
  messages = signal<AgentMessage[]>([]);
  selectedListing = signal<AgentListingSelection | null>(null);
  draft = signal('');
  loading = signal(false);
  sending = signal(false);
  actionLoading = signal(false);
  errorMessage = signal('');
  listingSelectionError = signal('');
  listingUnavailable = signal(false);
  pendingQuestion = signal<PendingQuestion | null>(null);

  ngOnInit(): void {
    if (!this.enabled) {
      return;
    }
    if (this.sessionId) {
      this.loadSession(this.sessionId);
      return;
    }
    if (this.initialListing) {
      this.openListingSession(this.initialListing);
    }
  }

  /** Starts only a listing-bound session; no model request occurs until the user asks a question. */
  openListingSession(selection?: AgentListingSelection): void {
    if (!this.enabled || this.loading()) {
      return;
    }
    const candidate = selection || this.selectedListing();
    const normalized = toAgentListingSelection(
      candidate?.listingId,
      candidate?.title,
    );
    if (!normalized) {
      this.listingSelectionError.set('Choose an available public listing.');
      return;
    }
    this.selectedListing.set(normalized);
    this.listingSelectionError.set('');
    this.errorMessage.set('');
    this.listingUnavailable.set(false);
    this.loading.set(true);
    this.agentService.createOrResumeSession(normalized.listingId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: session => {
          this.session.set(session);
          this.loadMessages(session.id);
        },
        error: error => this.handleSessionError(error),
      });
  }

  sendQuestion(): void {
    const body = this.draft().trim();
    if (!body || !this.session() || this.sending() || this.isReadOnly()) {
      return;
    }
    const question = { clientMessageId: this.idFactory(), body };
    this.pendingQuestion.set(question);
    this.draft.set('');
    this.executeQuestion(question);
  }

  retryQuestion(): void {
    const question = this.pendingQuestion();
    if (!question || this.sending()) {
      return;
    }
    this.executeQuestion(question);
  }

  /** Executes only the three server-validated route-semantic actions. */
  runAction(action: AgentAnswerAction): void {
    if (this.actionLoading()) {
      return;
    }
    if (action.type === 'BROWSE_MARKETPLACE') {
      this.closeRequested.emit();
      void this.router.navigate(['/marketplace']);
      return;
    }
    if (action.type === 'VIEW_LISTING') {
      this.closeRequested.emit();
      void this.router.navigate(['/listings', action.listingId]);
      return;
    }

    this.actionLoading.set(true);
    this.chatService.startListingConversation(action.listingId)
      .pipe(finalize(() => this.actionLoading.set(false)))
      .subscribe({
        next: conversation => {
          this.closeRequested.emit();
          void this.router.navigate(['/account/messages', conversation.id]);
        },
        error: () => this.errorMessage.set(
          'The seller conversation could not be opened. Try again from the listing.',
        ),
      });
  }

  actionLabel(action: AgentAnswerAction): string {
    switch (action.type) {
      case 'MESSAGE_SELLER':
        return 'Message seller';
      case 'VIEW_LISTING':
        return 'View listing';
      case 'BROWSE_MARKETPLACE':
        return 'Browse marketplace';
    }
  }

  thumbnailUrl(value: string): string {
    return value.startsWith('/api/') ? `${environment.apiGatewayUrl}${value}` : value;
  }

  private loadSession(sessionId: string): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.agentService.getSession(sessionId)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: session => {
          this.session.set(session);
          this.loadMessages(session.id);
        },
        error: error => this.handleSessionError(error),
      });
  }

  private loadMessages(sessionId: string): void {
    this.agentService.getMessages(sessionId, null, 50).subscribe({
      next: page => this.messages.set(page.data),
      error: error => this.handleDependencyError(error),
    });
  }

  private executeQuestion(question: PendingQuestion): void {
    const session = this.session();
    if (!session) {
      return;
    }
    this.sending.set(true);
    this.errorMessage.set('');
    this.agentService.sendMessage(session.id, question.clientMessageId, question.body)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: response => {
          this.messages.update(messages => [
            ...withoutMessage(messages, response.userMessage.id),
            response.userMessage,
            response.assistantMessage,
          ]);
          this.pendingQuestion.set(null);
        },
        error: error => this.handleQuestionError(error),
      });
  }

  private handleSessionError(error: unknown): void {
    this.session.set(null);
    this.listingUnavailable.set(error instanceof HttpErrorResponse && error.status === 404);
    this.listingSelectionError.set(
      this.listingUnavailable()
        ? 'This listing is not available for AI assistance.'
        : safeErrorMessage(error),
    );
  }

  private handleQuestionError(error: unknown): void {
    const errorCode = agentErrorCode(error);
    if (
      error instanceof HttpErrorResponse
      && (error.status === 404 || errorCode === 'AGENT_SESSION_READ_ONLY')
    ) {
      this.listingUnavailable.set(true);
      this.session.update(session => session ? { ...session, status: 'READ_ONLY' } : session);
      this.errorMessage.set('The listing or assistant session is no longer available.');
      return;
    }
    if (errorCode === 'AGENT_MESSAGE_IN_PROGRESS') {
      this.errorMessage.set('This question is still being processed. Try again shortly.');
      return;
    }
    if (errorCode === 'AGENT_REQUEST_CONFLICT' || errorCode === 'AGENT_RETRY_EXHAUSTED') {
      this.pendingQuestion.set(null);
      this.errorMessage.set('This question cannot be retried. Ask it again as a new message.');
      return;
    }
    this.handleDependencyError(error);
  }

  private handleDependencyError(error: unknown): void {
    this.errorMessage.set(safeErrorMessage(error));
  }

  private isReadOnly(): boolean {
    return this.session()?.status !== 'OPEN' || this.listingUnavailable();
  }
}

function safeErrorMessage(error: unknown): string {
  if (error instanceof AgentContractError) {
    return 'The assistant returned an invalid response. Try again later.';
  }
  return 'Your marketplace and seller messages still work. Try listing help again shortly.';
}

function agentErrorCode(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const envelope = error.error;
  if (typeof envelope !== 'object' || envelope === null || Array.isArray(envelope)) {
    return null;
  }
  const body = (envelope as Record<string, unknown>)['error'];
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return null;
  }
  const code = (body as Record<string, unknown>)['code'];
  return typeof code === 'string' ? code : null;
}

function withoutMessage(messages: AgentMessage[], messageId: string): AgentMessage[] {
  return messages.filter(message => message.id !== messageId);
}
