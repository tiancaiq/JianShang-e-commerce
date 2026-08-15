import { CommonModule } from '@angular/common';
import {
  AfterViewChecked,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, Subscription } from 'rxjs';
import { environment } from '../../../environments/environment';
import { generateClientMessageId } from './agent-customer-service.capability';
import {
  MarketplaceAgentV2HistoryMessage,
  MarketplaceAgentV2ListingAttachment,
  MarketplaceAgentV2Message,
  MarketplaceAgentV2StreamEvent,
} from './agent-marketplace-v2.model';
import { AgentMarketplaceV2Service } from './agent-marketplace-v2.service';

interface V2ViewMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  clientMessageId: string | null;
  retryUserMessageId: string | null;
  attachments: MarketplaceAgentV2ListingAttachment[];
  refinement: MarketplaceAgentV2Message['refinement'];
  pendingInteraction: MarketplaceAgentV2Message['pendingInteraction'];
  partial: boolean;
}

interface V2TurnEvidence {
  modelCalls: number;
  action: string;
  policy: string;
  executedTool: string;
  eventOrder: string;
}

/** Customer-facing V2 conversation surface with an optional standalone evaluation shell. */
@Component({
  selector: 'app-agent-marketplace-v2-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <main class="v2-shell" [class.embedded]="embedded" [class.compact]="compact">
      <header>
        <div *ngIf="embedded; else evaluationHeading">
          <p class="eyebrow">AI customer service</p><h2>Marketplace assistant</h2>
        </div>
        <ng-template #evaluationHeading>
          <div><p class="eyebrow">Development evaluation</p><h1>Marketplace Agent V2</h1></div>
        </ng-template>
        <button type="button" (click)="newConversation()" [disabled]="working()">New conversation</button>
      </header>

      <p class="status" aria-live="polite">{{ status() }}</p>
      <section #conversationScroll class="conversation" tabindex="0"
        (scroll)="trackConversationScroll()"
        [attr.aria-label]="embedded ? 'Marketplace assistant conversation' : 'Marketplace Agent V2 conversation'">
        <article *ngFor="let message of messages()" [class.user]="message.role === 'USER'">
          <strong>{{ message.role === 'USER' ? 'You' : 'Marketplace assistant' }}</strong>
          <p class="message-text">{{ message.body }}</p>
          <div class="attachments" *ngIf="message.attachments.length">
            <h3 *ngIf="hasRelated(message)">Closest matches</h3>
            <a class="listing-card" *ngFor="let item of primaryAttachments(message)"
              [href]="'/listings/' + item.listingId">
              <span class="listing-thumbnail-frame" aria-hidden="true">
                <span class="listing-thumbnail-placeholder">Photo</span>
                <img *ngIf="item.thumbnailUrl" class="listing-thumbnail"
                  [src]="thumbnailUrl(item.thumbnailUrl)" alt="" loading="lazy" decoding="async"
                  (error)="hideThumbnail($event)">
              </span>
              <span class="listing-title">{{ item.title }}</span>
              <b>{{ item.priceAmount | currency:item.currency }}</b>
            </a>
            <h3 *ngIf="hasRelated(message)">Related alternatives</h3>
            <a class="listing-card" *ngFor="let item of relatedAttachments(message)"
              [href]="'/listings/' + item.listingId">
              <span class="listing-thumbnail-frame" aria-hidden="true">
                <span class="listing-thumbnail-placeholder">Photo</span>
                <img *ngIf="item.thumbnailUrl" class="listing-thumbnail"
                  [src]="thumbnailUrl(item.thumbnailUrl)" alt="" loading="lazy" decoding="async"
                  (error)="hideThumbnail($event)">
              </span>
              <span class="listing-title">{{ item.title }}</span>
              <b>{{ item.priceAmount | currency:item.currency }}</b>
            </a>
          </div>
          <div class="follow-up" *ngIf="waitingPending(message); else suggestedActions">
            <button type="button" (click)="respondToPending(true)" [disabled]="working()">Yes</button>
            <button type="button" (click)="respondToPending(false)" [disabled]="working()">No</button>
          </div>
          <ng-template #suggestedActions>
            <div class="follow-up" *ngIf="message.refinement as refinement">
              <button *ngFor="let option of refinement.options" type="button"
                (click)="useRefinement(option)" [disabled]="working()">
                {{ option.value }} <span aria-hidden="true">({{ option.count }})</span>
              </button>
            </div>
          </ng-template>
          <p *ngIf="message.partial" class="partial">Response stopped or interrupted.</p>
          <button *ngIf="message.retryUserMessageId" type="button"
            (click)="retry(message)" [disabled]="working()">Retry response</button>
        </article>
        <article *ngIf="working()" class="assistant-live">
          <strong>Marketplace assistant</strong>
          <p *ngIf="activity()">{{ activity() }}</p>
          <p class="message-text">{{ streamedText() }}<span class="cursor" aria-hidden="true">▍</span></p>
        </article>
      </section>

      <ng-container *ngIf="!embedded">
        <details *ngIf="evidence() as item" class="evidence">
          <summary>Latest safe evaluation evidence</summary>
          <dl>
            <dt>Model calls</dt><dd>{{ item.modelCalls }}</dd>
            <dt>Action</dt><dd>{{ item.action }}</dd>
            <dt>Policy</dt><dd>{{ item.policy }}</dd>
            <dt>Executed tool</dt><dd>{{ item.executedTool }}</dd>
            <dt>SSE order</dt><dd>{{ item.eventOrder }}</dd>
          </dl>
        </details>
      </ng-container>

      <form (ngSubmit)="send()">
        <label for="v2-message">Message</label>
        <textarea id="v2-message" name="message" [(ngModel)]="draft"
          [disabled]="working() || !sessionId()" maxlength="8000"></textarea>
        <div>
          <button type="submit" [disabled]="working() || !sessionId() || !draft.trim()">Send</button>
          <button *ngIf="working()" type="button" (click)="stop()">Stop generation</button>
        </div>
      </form>
    </main>
  `,
  styles: [`
    :host{display:block;width:100%;height:100%;min-width:0;min-height:0;background:#f7f5fb;color:#30243f}.v2-shell{max-width:920px;margin:auto;padding:28px}.v2-shell.embedded{box-sizing:border-box;height:100%;min-width:0;min-height:0;max-width:none;padding:18px;background:transparent;display:flex;flex-direction:column;overflow:hidden}.v2-shell.embedded>header,.v2-shell.embedded>.status,.v2-shell.embedded>form{flex:0 0 auto}.v2-shell.embedded .conversation{display:flex;flex:1 1 auto;flex-direction:column;min-width:0;min-height:0;overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain;margin:8px 0;padding-right:4px;scroll-behavior:smooth;scrollbar-color:rgba(109,63,130,.35) transparent;scrollbar-width:thin}.v2-shell.compact{padding:8px}.v2-shell.compact form{margin-top:8px}.v2-shell.compact textarea{min-height:72px}
    header{display:flex;flex:0 0 auto;justify-content:space-between;gap:20px;align-items:center;min-width:0}.eyebrow{margin:0;color:#7a4a91;font-weight:700}
    h1,h2{margin:.2rem 0}.status{min-height:1.5rem;color:#624f70}.conversation{display:grid;gap:14px;margin:20px 0}.conversation:focus-visible{outline:2px solid #b983cf;outline-offset:-2px}
    article{box-sizing:border-box;min-width:0;max-width:78%;padding:16px;border:1px solid #decbe8;border-radius:18px;background:white;overflow-wrap:anywhere}.user{align-self:flex-end;justify-self:end;background:#6d3f82;color:white}
    .message-text{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;margin:.5rem 0}.attachments{display:grid;min-width:0;gap:8px}.attachments h3{font-size:.9rem;margin:8px 0 0;color:#6d5879}.listing-card{box-sizing:border-box;display:grid;width:100%;min-width:0;max-width:100%;grid-template-columns:76px minmax(0,1fr) auto;align-items:center;gap:12px;overflow:hidden;padding:8px;border:1px solid #eadff0;border-radius:12px;color:inherit;text-decoration:none}.listing-card:hover{border-color:#b983cf;background:#fcf9fe}.listing-card:focus-visible{outline:3px solid #d37ab9;outline-offset:2px}.listing-thumbnail-frame{position:relative;display:grid;place-items:center;width:76px;aspect-ratio:1;overflow:hidden;border-radius:9px;background:#f0e9f4;color:#705d7a;font-size:.72rem;font-weight:700;text-transform:uppercase;letter-spacing:.04em}.listing-thumbnail{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.listing-title{min-width:0;overflow-wrap:anywhere;line-height:1.35}.listing-card b{white-space:nowrap}
    .follow-up{margin-top:12px;padding-top:10px;border-top:1px solid #eadff0}.follow-up button{padding:7px 11px}
    .partial{font-size:.9rem;color:#8a4a63}.evidence{background:#fff;padding:12px;border-radius:12px}.evidence dl{display:grid;grid-template-columns:140px 1fr;gap:6px}.evidence dt{font-weight:700}.evidence dd{margin:0}
    form{display:grid;flex:0 0 auto;min-width:0;gap:8px;margin-top:20px}textarea{box-sizing:border-box;width:100%;min-width:0;max-width:100%;min-height:100px;max-height:180px;padding:12px;border:1px solid #bca8c8;border-radius:12px;font:inherit}button{padding:10px 15px;border-radius:999px;border:1px solid #8d52a4;background:white;color:#5d2e72;font-weight:700;margin-right:8px}.cursor{animation:blink 1s steps(1) infinite}@keyframes blink{50%{opacity:0}}@media(prefers-reduced-motion:reduce){.cursor{animation:none}.v2-shell.embedded .conversation{scroll-behavior:auto}}@media(max-width:640px){.v2-shell{padding:16px}header{flex-wrap:wrap}article{max-width:92%}.listing-card{grid-template-columns:64px minmax(0,1fr)}.listing-thumbnail-frame{width:64px}.listing-card b{grid-column:2}}
  `],
})
export class AgentMarketplaceV2PageComponent implements OnInit, AfterViewChecked, OnDestroy {
  @Input() embedded = false;
  @Input() compact = false;
  readonly sessionId = signal<string | null>(null);
  readonly messages = signal<V2ViewMessage[]>([]);
  readonly working = signal(false);
  readonly status = signal('Loading marketplace assistant…');
  readonly activity = signal('');
  readonly streamedText = signal('');
  readonly evidence = signal<V2TurnEvidence | null>(null);
  draft = '';
  private stream: Subscription | null = null;
  private activeClientMessageId: string | null = null;
  private pendingInteractionBeingAnswered: { id: string; accepted: boolean } | null = null;
  private eventOrder: string[] = [];
  private autoFollow = true;
  private scrollPending = false;
  private forceNewestOnNextRender = false;

  @ViewChild('conversationScroll')
  private conversationScroll?: ElementRef<HTMLElement>;

  constructor(private readonly agent: AgentMarketplaceV2Service) {}

  async ngOnInit(): Promise<void> { await this.open(false); }
  ngAfterViewChecked(): void {
    if (!this.scrollPending) return;
    this.scrollPending = false;
    const forceNewest = this.forceNewestOnNextRender;
    this.forceNewestOnNextRender = false;
    const element = this.conversationScroll?.nativeElement;
    if (element && (forceNewest || this.autoFollow)) {
      element.scrollTop = element.scrollHeight;
    }
  }
  ngOnDestroy(): void { this.stream?.unsubscribe(); }

  async newConversation(): Promise<void> { await this.open(true); }

  primaryAttachments(message: V2ViewMessage): MarketplaceAgentV2ListingAttachment[] {
    return message.attachments.filter(item => item.matchQuality !== 'RELATED');
  }

  relatedAttachments(message: V2ViewMessage): MarketplaceAgentV2ListingAttachment[] {
    return message.attachments.filter(item => item.matchQuality === 'RELATED');
  }

  hasRelated(message: V2ViewMessage): boolean {
    return message.attachments.some(item => item.matchQuality === 'RELATED');
  }

  waitingPending(message: V2ViewMessage): boolean {
    return message.pendingInteraction?.status === 'WAITING'
      && message.pendingInteraction.type !== 'ANSWER_FIELD';
  }

  /** Resolves Product-owned public media through the same Gateway origin as listing pages. */
  thumbnailUrl(value: string): string {
    return `${environment.apiGatewayUrl}${value}`;
  }

  /** Reveals the stable card placeholder when approved media is unavailable to the browser. */
  hideThumbnail(event: Event): void {
    if (event.target instanceof HTMLImageElement) event.target.hidden = true;
  }

  async send(): Promise<void> {
    const sessionId = this.sessionId();
    const body = this.draft.trim();
    if (!sessionId || !body || this.working()) return;
    const clientMessageId = generateClientMessageId();
    const answer = this.confirmationAnswer(body);
    const pending = [...this.messages()].reverse().find(item => this.waitingPending(item));
    this.pendingInteractionBeingAnswered = answer === null || !pending?.pendingInteraction
      ? null : { id: pending.pendingInteraction.id, accepted: answer };
    this.activeClientMessageId = clientMessageId;
    this.beginStream();
    this.stream = this.agent.streamMessage(sessionId, clientMessageId, body).subscribe({
      next: event => this.handle(event, false),
      error: () => this.finishWithError(),
    });
  }

  async stop(): Promise<void> {
    const sessionId = this.sessionId();
    const clientMessageId = this.activeClientMessageId;
    if (!sessionId || !clientMessageId) return;
    this.stream?.unsubscribe();
    this.stream = null;
    try {
      const result = await firstValueFrom(this.agent.stopMessage(sessionId, clientMessageId));
      this.status.set(result.outcome === 'NOT_COMMITTED'
        ? 'Stopped before your message was saved.' : 'Response stopped.');
      await this.loadHistory(sessionId);
    } catch { this.status.set('Stop status could not be confirmed. Refresh before retrying.'); }
    this.working.set(false);
    this.activeClientMessageId = null;
    this.pendingInteractionBeingAnswered = null;
  }

  retry(message: V2ViewMessage): void {
    const sessionId = this.sessionId();
    const userId = message.retryUserMessageId;
    const clientId = this.clientIdFor(userId);
    if (!sessionId || !userId || !clientId || this.working()) return;
    this.activeClientMessageId = clientId;
    this.beginStream();
    this.stream = this.agent.retryResponse(sessionId, userId, clientId).subscribe({
      next: event => this.handle(event, true),
      error: () => this.finishWithError(),
    });
  }

  useRefinement(
    option: NonNullable<MarketplaceAgentV2Message['refinement']>['options'][number],
  ): void {
    // Convert display labels into explicit commands while keeping legacy subtype history usable.
    if (this.working()) return;
    if (option.facet === 'MATCH_SCOPE') {
      this.draft = 'Show only exact matches for the current search.';
    } else if (option.facet === 'MAXIMUM_PRICE') {
      this.draft = `Show current results ${option.value.toLocaleLowerCase()}.`;
    } else if (option.facet === 'CONDITION') {
      this.draft = `Show current results in ${option.value.toLocaleLowerCase()}.`;
    } else if (option.facet === 'LOCATION') {
      this.draft = `Show current results ${option.value.toLocaleLowerCase()}.`;
    } else if (option.facet === 'PRICE_BAND') {
      this.draft = `Show current results in the ${option.value} price range.`;
    } else {
      this.draft = `Show current results for the ${option.value} subtype.`;
    }
  }

  respondToPending(accepted: boolean): void {
    if (this.working()) return;
    this.draft = accepted ? 'yes' : 'no';
    void this.send();
  }

  private async open(newConversation: boolean): Promise<void> {
    this.stream?.unsubscribe();
    this.working.set(true);
    this.status.set('Loading marketplace assistant…');
    try {
      const session = await firstValueFrom(this.agent.createSession(newConversation));
      this.sessionId.set(session.sessionId);
      await this.loadHistory(session.sessionId);
      this.status.set(newConversation
        ? 'New marketplace conversation ready.' : 'Marketplace assistant ready.');
    } catch { this.status.set('Marketplace assistant is unavailable.'); }
    this.working.set(false);
  }

  private async loadHistory(sessionId: string): Promise<void> {
    const history = await firstValueFrom(this.agent.listMessages(sessionId));
    this.messages.set(history.data.map(item => this.historyMessage(item)));
    this.followNewestContent(true);
  }

  private beginStream(): void {
    this.working.set(true); this.status.set('Sending…'); this.activity.set('');
    this.streamedText.set(''); this.eventOrder = []; this.evidence.set(null);
    this.followNewestContent(true);
  }

  private handle(event: MarketplaceAgentV2StreamEvent, retry: boolean): void {
    this.eventOrder.push(event.type);
    if (event.type === 'message_started') {
      if (!retry && !this.messages().some(item => item.id === event.userMessage.id)) {
        this.messages.update(items => [...items, {
          id: event.userMessage.id, role: 'USER', body: event.userMessage.body,
          clientMessageId: this.activeClientMessageId, retryUserMessageId: null,
          attachments: [], refinement: null, pendingInteraction: null, partial: false,
        }]);
        this.draft = '';
      }
      const answered = this.pendingInteractionBeingAnswered;
      if (answered !== null) {
        this.messages.update(items => items.map(item =>
          item.pendingInteraction?.id === answered.id
            ? { ...item, pendingInteraction: {
              ...item.pendingInteraction,
              status: answered.accepted ? 'CONSUMED' : 'CANCELLED',
            } }
            : item,
        ));
        this.pendingInteractionBeingAnswered = null;
      }
      this.status.set('Request accepted.');
    } else if (event.type === 'activity') {
      this.activity.set(event.label);
    } else if (event.type === 'text_delta') {
      this.streamedText.update(value => value + event.delta);
    } else if (event.type === 'error') {
      this.status.set(event.message);
      this.stream = null;
      void this.reconcileTerminalError();
    } else if (event.type === 'done') {
      const message = event.response.message;
      this.messages.update(items => [...items.filter(item => item.id !== event.messageId), {
        id: event.messageId, role: 'ASSISTANT', body: message.content,
        clientMessageId: null, retryUserMessageId: null,
        attachments: message.attachments, refinement: message.refinement,
        pendingInteraction: message.pendingInteraction, partial: false,
      }]);
      const activities = message.toolActivity;
      this.evidence.set({
        modelCalls: event.response.decisionCount,
        action: activities.length ? `Tool proposal: ${activities[0].tool}` : 'Direct answer',
        policy: activities.length
          ? activities.map(item => `${item.status}:${item.reason}`).join(', ')
          : 'No tool proposed',
        executedTool: activities.filter(item => item.status === 'SUCCEEDED').map(item => item.tool).join(', ') || 'None',
        eventOrder: this.eventOrder.join(' → '),
      });
      this.status.set('Complete.'); this.working.set(false);
      this.activeClientMessageId = null; this.stream = null;
    }
    this.followNewestContent();
  }

  /** Stops automatic following when the customer intentionally reads older messages. */
  trackConversationScroll(): void {
    const element = this.conversationScroll?.nativeElement;
    if (element) {
      this.autoFollow = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
    }
  }

  /** Marks the next rendered view for a single newest-content scroll. */
  private followNewestContent(force = false): void {
    if (force) {
      this.autoFollow = true;
      this.forceNewestOnNextRender = true;
    }
    if (!force && !this.autoFollow) return;
    this.scrollPending = true;
  }

  private finishWithError(): void {
    this.status.set('The assistant response was interrupted. Refresh to reconcile before retrying.');
    this.stream = null;
    void this.reconcileTerminalError();
  }

  /** Restores the authoritative failed assistant row without resending the USER turn. */
  private async reconcileTerminalError(): Promise<void> {
    const sessionId = this.sessionId();
    const clientMessageId = this.activeClientMessageId;
    this.working.set(false);
    this.activeClientMessageId = null;
    this.pendingInteractionBeingAnswered = null;
    if (!sessionId) return;
    try {
      await this.loadHistory(sessionId);
      if (clientMessageId !== null
          && this.messages().some(item => item.role === 'USER'
            && item.clientMessageId === clientMessageId)) {
        this.draft = '';
      }
      this.status.set('The response was interrupted. The saved result is ready to retry.');
    } catch {
      this.status.set('The assistant response was interrupted. Refresh to reconcile before retrying.');
    }
  }

  private historyMessage(item: MarketplaceAgentV2HistoryMessage): V2ViewMessage {
    return {
      id: item.id, role: item.role, body: item.body,
      clientMessageId: item.clientMessageId,
      retryUserMessageId: item.responseRetryUserMessageId,
      attachments: item.message?.attachments ?? [],
      refinement: item.message?.refinement ?? null,
      pendingInteraction: item.message?.pendingInteraction ?? null,
      partial: item.retryable,
    };
  }

  private clientIdFor(userMessageId: string | null): string | null {
    return this.messages().find(item => item.id === userMessageId)?.clientMessageId ?? null;
  }

  private confirmationAnswer(value: string): boolean | null {
    const normalized = value.trim().toLocaleLowerCase().replace(/[.!?]+$/, '');
    if (['yes', 'y', 'yes please', 'sure', 'okay', 'ok'].includes(normalized)) return true;
    if (['no', 'n', 'no thanks', 'no thank you'].includes(normalized)) return false;
    return null;
  }
}
