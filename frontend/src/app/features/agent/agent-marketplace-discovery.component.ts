import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, finalize, switchMap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AGENT_CLIENT_MESSAGE_ID_FACTORY } from './agent-customer-service.capability';
import {
  DiscoveryHistoryMessage,
  DiscoveryExclusionReason,
  DiscoveryRecommendation,
  DiscoveryProgressStage,
  SendDiscoveryMessageResponse,
  DiscoverySession,
  DiscoveryTurnResult,
} from './agent-marketplace-discovery.model';
import {
  AgentMarketplaceDiscoveryService,
  DiscoveryAuthenticationRequiredError,
  DiscoveryContractError,
  DiscoveryStreamFailureError,
} from './agent-marketplace-discovery.service';

interface DiscoveryDisplayMessage {
  key: string;
  role: 'USER' | 'ASSISTANT';
  body: string;
  result: DiscoveryTurnResult | null;
  createdAt: string;
  retryUserMessageId: string | null;
  activitySummary: string | null;
}

interface SelectedListingContext {
  listingId: string;
  title: string;
  rank: number;
  intent: 'ASK' | 'COMPARE';
}

interface PendingAmbiguousSend {
  sessionId: string;
  clientMessageId: string;
  draft: string;
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
          <span class="eyebrow">Marketplace assistant</span>
          <h2 id="discovery-title">Marketplace assistant</h2>
          <p>
            Help with listings, marketplace questions, and buyer or seller next steps.
          </p>
        </div>
      </header>

      <div #conversationScroll class="conversation-scroll" (scroll)="trackConversationScroll()">
      @if (historyReady() && session() && !messages().length) {
        <article class="message assistant-message greeting-message">
          <div class="answer-heading">
            <span>Marketplace assistant</span>
            <small>Ready</small>
          </div>
          <h3>How can I help?</h3>
          <p>
            Ask a marketplace question, get help with a buyer or seller process,
            or tell me what you would like to find and compare.
          </p>
        </article>
      }

      @if (historyReady() && session() && !messages().length) {
        <div class="examples" aria-label="Example marketplace assistant prompts">
          <span>Try a message</span>
          <button type="button" (click)="useExample('Used bicycle near Irvine')">
            Used bicycle near Irvine
          </button>
          <button type="button" (click)="useExample('Beginner camera under $500')">
            Beginner camera under $500
          </button>
          <button type="button" (click)="useExample('Desk lamp for a small room')">
            Desk lamp for a small room
          </button>
          <button type="button" (click)="useExample('What can you do?')">
            What can you do?
          </button>
        </div>
      }

      @if (session() || messages().length) {
        <div class="session-bar">
          <div>
            <strong>{{ session()?.status === 'CLOSED' ? 'Closed conversation' : 'Current conversation' }}</strong>
            <span>Preference version {{ session()?.preferenceVersion ?? 0 }}</span>
          </div>
          <button
            type="button"
            class="secondary"
            [disabled]="loading() || sending()"
            (click)="requestNewSearch()">
            New conversation
          </button>
        </div>
      }

      @if (confirmNewSearch()) {
        <section class="confirmation" aria-labelledby="new-search-title">
          <div>
            <strong id="new-search-title">Start a new conversation?</strong>
            <span>The current history stays stored, but this page will open a clean session.</span>
          </div>
          <div>
            <button type="button" class="secondary" (click)="confirmNewSearch.set(false)">Keep current</button>
            <button type="button" (click)="startNewSearch()">Start new conversation</button>
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

      @if (messages().length || ((sending() || streamInterrupted()) && streamStages().length)) {
        <section class="history" aria-label="Marketplace assistant history">
          @for (message of messages(); track message.key) {
            @if (message.role === 'USER') {
              <article class="message user-message">
                <span>You · {{ message.createdAt | date: 'shortTime' }}</span>
                <p>{{ message.body }}</p>
              </article>
            } @else if (message.result; as result) {
              <article class="message assistant-message" [attr.data-outcome]="result.outcome">
                <div class="answer-heading">
                  <span>Marketplace assistant</span>
                  @if (showOutcomeLabel(result)) {
                    <small>{{ outcomeLabel(result.outcome) }}</small>
                  }
                </div>
                @if (message.activitySummary) {
                  <p class="completed-activity-summary">{{ message.activitySummary }}</p>
                }
                @if (result.searchOutcome?.reason === 'CATEGORY_UNAVAILABLE') {
                  <small class="availability-label">No current listings</small>
                }
                <p class="canonical-answer">{{ result.message }}</p>
                @if (message.retryUserMessageId) {
                  <button
                    type="button"
                    class="secondary retry-response"
                    [disabled]="sending() || loading()"
                    (click)="retryResponse(message)">
                    Retry response
                  </button>
                }

                @if (result.outcome === 'ASK_CLARIFY' || result.outcome === 'CLARIFY') {
                  <div class="clarification" aria-label="Follow-up questions">
                    @for (question of result.questions; track question) {
                      <button type="button" (click)="useExample(question)">{{ question }}</button>
                    }
                  </div>
                }

                @if ((result.outcome === 'SEARCH' || result.outcome === 'RECOMMEND' || result.outcome === 'COMPARE' || result.outcome === 'DETAIL')
                    && result.recommendations.length
                    && (!result.searchOutcome || result.searchOutcome.reason === 'RESULTS_AVAILABLE')) {
                  <div
                    class="recommendation-grid"
                    [attr.aria-label]="result.outcome === 'COMPARE'
                      ? 'Compared listings'
                      : result.outcome === 'DETAIL'
                        ? 'Selected listing details'
                        : 'Recommended listings'">
                    @for (item of result.recommendations; track item.listingId; let rank = $index) {
                      <article class="listing-card">
                        <div class="listing-image" aria-hidden="true">
                          @if (item.thumbnailUrl) {
                            <img [src]="thumbnailUrl(item.thumbnailUrl)" alt="">
                          } @else {
                            <span>Item</span>
                          }
                        </div>
                        <div class="listing-card-top">
                          <span>{{ item.categoryName }} · {{ sellerTypeLabel(item) }}</span>
                          <strong>{{ item.priceAmount | currency: item.currency : 'symbol' : '1.2-2' }}</strong>
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
                        <div class="listing-actions">
                          <a [routerLink]="['/listings', item.listingId]">View listing</a>
                          <button
                            type="button"
                            class="compare-button"
                            (click)="compareRecommendation(item, rank + 1)">
                            Compare
                          </button>
                          <button
                            type="button"
                            class="ask-button"
                            (click)="askAboutRecommendation(item, rank + 1)">
                            Ask about this
                          </button>
                          @if (isExcluded(item.listingId)) {
                            <span class="excluded-label">Excluded from this search</span>
                          } @else {
                            <button
                              type="button"
                              class="exclude-button"
                              [disabled]="excluding(item.listingId)"
                              (click)="excludeRecommendation(item.listingId, 'NOT_RELEVANT')">
                              {{ excluding(item.listingId) ? 'Excluding…' : 'Not interested' }}
                            </button>
                          }
                        </div>
                      </article>
                    }
                  </div>
                }

              </article>
            }
          }

          @if ((sending() || streamInterrupted()) && streamStages().length) {
            @if (streamAccepted() && !retryingResponse()) {
              <article class="message user-message pending-user-message">
                <span>You · now</span>
                <p>{{ submittedBody() }}</p>
              </article>
            }
            <article class="message assistant-message streaming-message" aria-label="Marketplace assistant activity">
              <div class="answer-heading">
                <span>Marketplace assistant</span>
                <small>{{ streamInterrupted()
                  ? (streamStopped() ? 'Stopped' : 'Interrupted')
                  : (streamedAnswer() ? 'Streaming' : 'Working...') }}</small>
              </div>
              @if (currentStreamStage(); as currentStage) {
                @if (currentStage !== 'MESSAGE_ACCEPTED') {
                <div class="current-activity">
                  <span class="trail-marker" aria-hidden="true"></span>
                  <strong>{{ activityLabel(currentStage) }}</strong>
                </div>
                }
              }
              @if (streamStages().length > 1) {
              <details class="activity-details" open>
                <summary>Activity trail · {{ streamStages().length }} steps</summary>
                <ol class="search-trail" aria-label="Marketplace activity">
                  @for (stage of streamStages(); track stage) {
                    <li [class.complete]="stage !== currentStreamStage()" [class.current]="stage === currentStreamStage()">
                      <span class="trail-marker" aria-hidden="true"></span>
                      <span>{{ activityLabel(stage) }}</span>
                    </li>
                  }
                </ol>
              </details>
              }
              @if (streamedAnswer()) {
                <div class="streamed-answer"><span>{{ streamedAnswer() }}</span>@if (sending()) {<span class="answer-cursor" aria-hidden="true"></span>}</div>
              }
              @if (streamInterrupted()) {
                <div class="stream-interrupted">
                  <strong>{{ streamStopped()
                    ? (streamedAnswer() ? 'Generation stopped.' : 'Response stopped.')
                    : 'Response interrupted.' }}</strong>
                  <span>
                    {{ streamStopped()
                      ? (streamedAnswer()
                        ? 'The partial answer was saved. Refresh history to use Retry response without resending your message.'
                        : 'Your message was saved, but no answer text was generated. Refresh history to use Retry response without resending it.')
                      : 'A marketplace service interrupted this response. Refreshing history will show the saved terminal result and Retry response without resending your message.' }}
                  </span>
                  <button type="button" class="secondary" (click)="refreshHistory(true, true)">Refresh history</button>
                </div>
              }
            </article>
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

      </div>

      <form class="composer" (ngSubmit)="submitPrompt()">
        @if (selectedListing(); as selected) {
          <div class="selected-context">
            <span>{{ selected.intent === 'COMPARE' ? 'Comparing' : 'Asking about' }}</span>
            <strong>{{ selected.title }}</strong>
            <button
              type="button"
              class="context-clear"
              aria-label="Remove selected listing context"
              (click)="clearSelectedContext()">
              Clear
            </button>
          </div>
        }
        <label for="discovery-prompt">
          {{ selectedListing() ? 'What would you like to know?' : 'Message the marketplace assistant' }}
        </label>
        <textarea
          #promptInput
          id="discovery-prompt"
          name="discoveryPrompt"
          rows="4"
          maxlength="8000"
          [ngModel]="prompt()"
          (ngModelChange)="prompt.set($event)"
          [disabled]="loading() || sending() || !historyReady()"
          placeholder="Describe what you need, refine results, or compare options"></textarea>
        <div class="composer-footer">
          <span>{{ prompt().length }}/8000</span>
          @if (sending()) {
            <button type="button" class="secondary" (click)="stopGeneration()">Stop generation</button>
          } @else {
          <button type="submit" [disabled]="loading() || !historyReady() || !prompt().trim()">
            {{ sending() ? 'Working…' : 'Send' }}
          </button>
          }
        </div>
      </form>
    </section>
  `,
  styles: [`
    .discovery {
      display: flex;
      flex-direction: column;
      min-width: 0;
      min-height: 0;
      height: 100%;
      overflow: hidden;
      border: 1px solid rgba(212, 161, 222, 0.65);
      border-radius: 22px;
      background:
        radial-gradient(circle at 14% 10%, rgba(255, 184, 218, 0.22), transparent 28%),
        linear-gradient(180deg, #fffafd 0%, #fff6fb 100%);
      box-shadow: 0 18px 44px rgba(113, 63, 131, 0.12);
    }

    .discovery-hero {
      position: sticky;
      top: 0;
      z-index: 2;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      flex: 0 0 auto;
      gap: 0.8rem;
      align-items: center;
      border-bottom: 1px solid var(--market-line);
      background: rgba(255, 255, 255, 0.86);
      padding: 0.7rem 0.85rem;
    }

    .compass-mark {
      display: grid;
      place-items: center;
      width: 2.75rem;
      height: 2.75rem;
      border: 0;
      border-radius: 999px;
      background: linear-gradient(135deg, #ff8ac8, #9b6df3);
      box-shadow: 0 0 0 4px #fff, 0 10px 22px rgba(190, 47, 118, 0.2);
    }

    .compass-mark span {
      width: 0;
      height: 0;
      border-right: 0.45rem solid transparent;
      border-bottom: 1.25rem solid #fff;
      border-left: 0.45rem solid transparent;
    }

    .eyebrow {
      color: #c13686;
      font-size: 0.68rem;
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
      margin-top: 0.08rem;
      color: #352145;
      font-size: clamp(1rem, 2vw, 1.2rem);
      line-height: 1.15;
    }

    .discovery-hero p {
      max-width: 68ch;
      margin-top: 0.18rem;
      color: #6f5e82;
      font-size: 0.82rem;
      font-weight: 750;
      line-height: 1.42;
    }

    .examples,
    .clarification {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }

    .examples {
      padding: 0 0.25rem 0.35rem;
    }

    .examples > span {
      color: #705d82;
      font-size: 0.78rem;
      font-weight: 800;
    }

    button,
    a {
      min-height: 2.35rem;
      border-radius: 999px;
      font: inherit;
      font-weight: 850;
    }

    button {
      border: 1px solid #dc4f9f;
      background: linear-gradient(135deg, #f052a7, #9b67ec);
      color: white;
      padding: 0.55rem 0.85rem;
      cursor: pointer;
    }

    button.secondary,
    .examples button,
    .clarification button {
      border-color: rgba(220, 79, 159, 0.48);
      background: rgba(255, 255, 255, 0.86);
      color: #c13686;
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
      flex: 0 0 auto;
      border: 1px solid rgba(212, 161, 222, 0.65);
      border-radius: 16px;
      background: rgba(255, 255, 255, 0.9);
      padding: 0.75rem 0.9rem;
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

    .conversation-scroll {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      gap: 0.85rem;
      min-height: 0;
      overflow-y: auto;
      padding: 0.9rem 1rem 1.15rem;
      scrollbar-color: rgba(190, 47, 118, 0.28) transparent;
      scrollbar-width: thin;
    }

    .conversation-scroll::-webkit-scrollbar {
      width: 8px;
    }

    .conversation-scroll::-webkit-scrollbar-track {
      background: transparent;
    }

    .conversation-scroll::-webkit-scrollbar-thumb {
      border: 2px solid #fffafd;
      border-radius: 999px;
      background: rgba(190, 47, 118, 0.26);
    }

    .history {
      display: flex;
      flex: 0 0 auto;
      flex-direction: column;
      gap: 0.85rem;
      align-content: start;
      min-height: 0;
      max-height: none;
      overflow: visible;
      padding: 0;
    }

    .message {
      max-width: min(86%, 42rem);
      border-radius: 18px;
      padding: 0.8rem 0.95rem;
    }

    .message > span,
    .answer-heading {
      color: #735f86;
      font-size: 0.73rem;
      font-weight: 800;
    }

    .pending-user-message {
      opacity: 0.94;
    }

    .streaming-message {
      min-height: 7rem;
    }

    .current-activity {
      display: grid;
      grid-template-columns: 1rem minmax(0, 1fr);
      gap: 0.55rem;
      align-items: center;
      margin-top: 0.7rem;
      color: #442b55;
      font-size: 0.86rem;
    }

    .current-activity .trail-marker {
      border-color: #d83e94;
      background: #ffd7e9;
      animation: activity-pulse 1.2s ease-in-out infinite;
    }

    .activity-details {
      margin-top: 0.5rem;
      color: #756683;
      font-size: 0.75rem;
    }

    .activity-details summary {
      width: max-content;
      cursor: pointer;
      font-weight: 780;
    }

    .activity-details summary:focus-visible {
      outline: 3px solid color-mix(in srgb, var(--market-accent) 55%, white);
      outline-offset: 2px;
    }

    .search-trail {
      display: grid;
      gap: 0;
      margin: 0.8rem 0 0;
      padding: 0;
      list-style: none;
    }

    .search-trail li {
      position: relative;
      display: grid;
      grid-template-columns: 1rem minmax(0, 1fr);
      gap: 0.55rem;
      min-height: 1.7rem;
      color: #756683;
      font-size: 0.78rem;
      font-weight: 760;
    }

    .search-trail li:not(:last-child)::after {
      content: '';
      position: absolute;
      left: 0.34rem;
      top: 0.72rem;
      bottom: -0.1rem;
      width: 1px;
      background: rgba(193, 54, 134, 0.25);
    }

    .trail-marker {
      z-index: 1;
      width: 0.7rem;
      height: 0.7rem;
      margin-top: 0.12rem;
      border: 2px solid #c999d4;
      border-radius: 999px;
      background: #fff;
    }

    .search-trail li.complete .trail-marker {
      border-color: #6f4cb5;
      background: #6f4cb5;
      box-shadow: inset 0 0 0 2px #fff;
    }

    .search-trail li.current {
      color: #442b55;
    }

    .search-trail li.current .trail-marker {
      border-color: #d83e94;
      background: #ffd7e9;
      animation: activity-pulse 1.2s ease-in-out infinite;
    }

    .streamed-answer {
      margin-top: 0.55rem !important;
      white-space: pre-wrap;
    }

    .canonical-answer {
      white-space: pre-wrap;
    }

    .completed-activity-summary {
      margin: 0.45rem 0 0 !important;
      color: #756683 !important;
      font-size: 0.76rem;
      font-weight: 760;
    }

    .availability-label {
      display: inline-flex;
      width: fit-content;
      margin-top: 0.35rem;
      border-radius: 999px;
      padding: 0.2rem 0.55rem;
      background: color-mix(in srgb, var(--surface-muted) 82%, transparent);
      color: var(--text-muted);
      font-weight: 700;
    }

    .answer-cursor {
      display: inline-block;
      width: 0.12rem;
      height: 1em;
      margin-left: 0.16rem;
      vertical-align: -0.12em;
      border-radius: 999px;
      background: #c13686;
      animation: answer-cursor 0.9s steps(1, end) infinite;
    }

    @keyframes activity-pulse {
      50% { box-shadow: 0 0 0 0.28rem rgba(216, 62, 148, 0.13); }
    }

    @keyframes answer-cursor {
      50% { opacity: 0.15; }
    }

    .message > p {
      margin-top: 0.32rem;
      color: inherit;
      line-height: 1.52;
    }

    .user-message {
      align-self: flex-end;
      border: 1px solid rgba(236, 79, 163, 0.42);
      border-bottom-right-radius: 6px;
      background: linear-gradient(135deg, #ec4fa3, #a765ef);
      color: white;
      box-shadow: 0 12px 24px rgba(190, 47, 118, 0.18);
    }

    .user-message > span {
      color: rgba(255, 255, 255, 0.76);
    }

    .assistant-message {
      position: relative;
      align-self: flex-start;
      width: min(100%, 42rem);
      margin-left: 2.75rem;
      border: 1px solid rgba(212, 161, 222, 0.65);
      border-bottom-left-radius: 6px;
      background: rgba(255, 255, 255, 0.96);
      color: #352145;
      box-shadow: 0 12px 26px rgba(113, 63, 131, 0.08);
    }

    .assistant-message::before {
      content: 'AI';
      position: absolute;
      left: -2.75rem;
      top: 0;
      display: grid;
      place-items: center;
      width: 2rem;
      height: 2rem;
      border-radius: 999px;
      background: linear-gradient(135deg, #ffc0dd, #a877f2);
      color: #9b2470;
      font-size: 0.72rem;
      font-weight: 950;
      box-shadow: 0 0 0 3px #fff;
    }

    .greeting-message {
      margin-top: 0.35rem;
      box-shadow: inset 4px 0 0 #ff9dcb, 0 12px 26px rgba(113, 63, 131, 0.08);
    }

    .greeting-message h3 {
      margin-top: 0.35rem;
      color: #352145;
      font-size: clamp(1.35rem, 3vw, 2rem);
      line-height: 1.1;
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
      border: 1px solid rgba(212, 161, 222, 0.65);
      border-radius: 16px;
      background: linear-gradient(180deg, #fff 0%, #fff8fc 100%);
      padding: 0.75rem;
      box-shadow: 0 10px 24px rgba(113, 63, 131, 0.08);
    }

    .listing-image {
      height: 6.4rem;
      display: grid;
      place-items: center;
      overflow: hidden;
      border-radius: 14px;
      background: linear-gradient(135deg, #ffe5f2, #eee3ff);
      color: #765f87;
      font-size: 0.78rem;
      font-weight: 850;
    }

    .listing-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .listing-card-top {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      color: #725e84;
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
      color: #725e84;
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

    .listing-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      flex-wrap: wrap;
    }

    .exclude-button {
      min-height: 2.3rem;
      border: 1px solid var(--market-line);
      border-radius: 7px;
      background: white;
      color: var(--market-muted);
      padding: 0.45rem 0.65rem;
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    .ask-button,
    .compare-button {
      min-height: 2.3rem;
      border: 1px solid var(--market-accent-dark);
      background: white;
      color: var(--market-accent-dark);
      padding: 0.45rem 0.65rem;
    }

    .excluded-label {
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 800;
    }

    .load-more {
      justify-self: start;
    }

    .composer {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.45rem 0.65rem;
      flex: 0 0 auto;
      margin-top: auto;
      position: sticky;
      bottom: 0;
      z-index: 1;
      border-top: 1px solid rgba(212, 161, 222, 0.7);
      background: rgba(255, 248, 252, 0.96);
      box-shadow: 0 -10px 26px rgba(113, 63, 131, 0.08);
      padding: 0.7rem 0.85rem 0.75rem;
    }

    .selected-context {
      grid-column: 1 / -1;
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      gap: 0.5rem;
      align-items: center;
      border: 1px solid color-mix(in srgb, var(--market-accent) 45%, var(--market-line));
      border-radius: 14px;
      background: #fff4fa;
      padding: 0.5rem 0.65rem;
    }

    .selected-context span {
      color: var(--market-muted);
      font-size: 0.72rem;
      font-weight: 800;
    }

    .selected-context strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .context-clear {
      min-height: 2rem;
      border-color: transparent;
      background: transparent;
      color: var(--market-accent-dark);
      padding: 0.25rem 0.4rem;
    }

    .composer label {
      grid-column: 1 / -1;
      color: #352145;
      font-size: 0.82rem;
      font-weight: 900;
    }

    textarea {
      box-sizing: border-box;
      width: 100%;
      min-height: 56px;
      max-height: 150px;
      resize: vertical;
      border: 1px solid rgba(212, 161, 222, 0.78);
      border-radius: 18px;
      padding: 0.75rem;
      color: var(--market-ink);
      background: #fff;
      font: inherit;
      line-height: 1.45;
    }

    .composer-footer {
      display: flex;
      justify-content: space-between;
      gap: 0.6rem;
      align-items: end;
      align-self: stretch;
      flex-direction: column-reverse;
    }

    .composer-footer span {
      color: #78658b;
      font-size: 0.75rem;
    }

    .composer-footer button {
      min-width: 4.2rem;
      min-height: 2.5rem;
      border-radius: 14px;
      padding-inline: 0.9rem;
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

      .assistant-message {
        margin-left: 2.35rem;
      }

      .assistant-message::before {
        left: -2.35rem;
      }

      .composer {
        grid-template-columns: minmax(0, 1fr) auto;
      }

      .composer-footer {
        align-items: end;
        flex-direction: column-reverse;
      }

      .composer-footer button {
        width: auto;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        scroll-behavior: auto !important;
      }

      .search-trail li.current .trail-marker,
      .current-activity .trail-marker,
      .answer-cursor {
        animation: none;
      }
    }
  `],
})
export class AgentMarketplaceDiscoveryComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly service = inject(AgentMarketplaceDiscoveryService);
  private readonly idFactory = inject(AGENT_CLIENT_MESSAGE_ID_FACTORY);
  private pendingAmbiguousSend: PendingAmbiguousSend | null = null;
  private activeSendContext: PendingAmbiguousSend | null = null;
  private receivedCanonicalAnswer = '';
  private activeStream: Subscription | null = null;
  private receivedRecommendations = false;
  private receivedMetadata = false;
  private stoppingGeneration = false;

  @ViewChild('promptInput') private promptInput?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('liveRegion') private liveRegion?: ElementRef<HTMLElement>;
  @ViewChild('conversationScroll') private conversationScroll?: ElementRef<HTMLElement>;
  private autoFollow = true;

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
  readonly excludingListingIds = signal<ReadonlySet<string>>(new Set());
  readonly selectedListing = signal<SelectedListingContext | null>(null);
  readonly streamStages = signal<readonly DiscoveryProgressStage[]>([]);
  readonly currentStreamStage = signal<DiscoveryProgressStage | null>(null);
  readonly streamedAnswer = signal('');
  readonly streamAccepted = signal(false);
  readonly submittedBody = signal('');
  readonly historyReady = signal(false);
  readonly retryingResponse = signal(false);
  readonly streamInterrupted = signal(false);
  readonly streamStopped = signal(false);

  /** Loads the authoritative open session and history before showing Ready. */
  ngOnInit(): void {
    this.loading.set(true);
    this.liveStatus.set('Loading your marketplace discovery session.');
    this.service.createOrResumeSession(false).pipe(
      catchError(error => {
        this.historyReady.set(false);
        this.handleCreateError(error);
        return EMPTY;
      }),
      switchMap(session => {
        this.session.set(session);
        return this.service.getMessages(session.id, null, 50);
      }),
      finalize(() => this.loading.set(false)),
    ).subscribe({
      next: page => {
        this.applyHistory(page.data, page.nextCursor);
        this.historyReady.set(true);
        this.liveStatus.set(
          page.data.length ? 'Discovery history loaded.' : 'New discovery session ready.',
        );
      },
      error: error => {
        this.historyReady.set(false);
        this.handleReadError(error);
      },
    });
  }

  /** Places keyboard focus on the question-first composer when the assistant opens. */
  ngAfterViewInit(): void {
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  /** Aborts any in-flight provider stream when this view is destroyed. */
  ngOnDestroy(): void {
    this.activeStream?.unsubscribe();
  }

  /** Copies an example or follow-up into the composer without issuing a request. */
  useExample(value: string): void {
    this.prompt.set(value);
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  /** Sends the exact explicit prompt once after authoritative history is ready. */
  submitPrompt(): void {
    const typedBody = this.prompt().trim();
    const selected = this.selectedListing();
    const body = selected
      ? selected.intent === 'COMPARE'
        ? typedBody
        : `About "${selected.title}": ${typedBody}`
      : typedBody;
    if (!body || body.length > 8_000 || hasUnsafeControls(body)
      || this.sending() || this.loading() || !this.historyReady()) {
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
    }
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
          this.pendingAmbiguousSend = null;
          this.prompt.set('');
          this.selectedListing.set(null);
          this.historyReady.set(true);
          this.liveStatus.set('New discovery session ready.');
          queueMicrotask(() => this.promptInput?.nativeElement.focus());
        },
        error: error => this.handleCreateError(error),
      });
  }

  /** Reloads authoritative session/version state and its first stored history page. */
  refreshHistory(preserveNotice = false, retireTerminalStream = false): void {
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
        const reconciledPending = this.reconcilePendingAmbiguousSend(page.data);
        if (retireTerminalStream && !reconciledPending) {
          this.resetStreamPresentation();
        }
        if (reconciledPending) {
          this.liveStatus.set('Discovery history confirmed the last message was saved.');
        } else if (this.pendingAmbiguousSend?.sessionId === current.id) {
          this.showError(
            'The search result is still uncertain.',
            'History did not include the last message yet. Your text was kept and was not automatically sent again.',
            true,
          );
        } else {
          this.liveStatus.set(
            preserveNotice
              ? 'History refreshed. Explicitly resubmit when ready.'
              : 'Discovery history refreshed.',
          );
        }
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
          const earlier = page.data.flatMap(toDisplayMessages);
          this.messages.update(messages => [...messages, ...earlier]);
          this.nextCursor.set(page.nextCursor);
          this.liveStatus.set('More discovery history loaded.');
        },
        error: error => this.handleReadError(error),
      });
  }

  outcomeLabel(outcome: DiscoveryTurnResult['outcome']): string {
    return {
      ANSWER: 'Answer',
      CLARIFY: 'Follow-up',
      SEARCH: 'Current listings',
      ACTION_REQUIRED: 'Next step',
      ASK_CLARIFY: 'Follow-up',
      RECOMMEND: 'Recommendations',
      COMPARE: 'Comparison',
      DETAIL: 'Listing details',
      NO_RESULTS: 'No current matches',
      REFUSE: 'Cannot assist',
      REFUSED: 'Cannot assist',
      HANDOFF: 'Support needed',
    }[outcome];
  }

  showOutcomeLabel(result: DiscoveryTurnResult): boolean {
    return result.intent === 'MARKETPLACE_DISCOVERY'
      || result.intent === 'LISTING_QUESTION'
      || result.outcome !== 'ANSWER';
  }

  /** Announces conversational completion without presenting ANSWER as a product badge. */
  completionStatus(result: DiscoveryTurnResult): string {
    return this.showOutcomeLabel(result)
      ? this.outcomeLabel(result.outcome)
      : 'Response ready.';
  }

  isExcluded(listingId: string): boolean {
    return this.session()?.exclusions.some(item => item.listingId === listingId) ?? false;
  }

  excluding(listingId: string): boolean {
    return this.excludingListingIds().has(listingId);
  }

  /** Keeps a trusted recommendation reference in this conversation composer. */
  askAboutRecommendation(item: DiscoveryRecommendation, rank: number): void {
    this.selectedListing.set({
      listingId: item.listingId,
      title: item.title,
      rank,
      intent: 'ASK',
    });
    this.prompt.set('');
    this.liveStatus.set(`${item.title} selected for a grounded follow-up.`);
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  /** Prepares a comparison follow-up without leaving discovery mode. */
  compareRecommendation(item: DiscoveryRecommendation, rank: number): void {
    this.selectedListing.set({
      listingId: item.listingId,
      title: item.title,
      rank,
      intent: 'COMPARE',
    });
    this.prompt.set(`Compare "${item.title}" with the other current recommendations.`);
    this.liveStatus.set(`${item.title} selected for comparison.`);
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  /** Returns the composer to general discovery without issuing a request. */
  clearSelectedContext(): void {
    this.selectedListing.set(null);
    this.liveStatus.set('Listing context cleared.');
    queueMicrotask(() => this.promptInput?.nativeElement.focus());
  }

  thumbnailUrl(value: string): string {
    return `${environment.apiGatewayUrl}${value}`;
  }

  sellerTypeLabel(item: DiscoveryRecommendation): string {
    return item.sellerType === 'INDIVIDUAL' ? 'Individual seller' : '';
  }

  /** Applies one explicit fixed-reason exclusion and never retries uncertain writes. */
  excludeRecommendation(
    listingId: string,
    reasonCode: DiscoveryExclusionReason,
  ): void {
    const current = this.session();
    if (!current || this.excluding(listingId) || this.isExcluded(listingId)) {
      return;
    }
    const idempotencyKey = this.idFactory();
    this.clearError();
    this.excludingListingIds.update(ids => new Set(ids).add(listingId));
    this.service.excludeListing(
      current.id,
      idempotencyKey,
      current.preferenceVersion,
      listingId,
      reasonCode,
    ).pipe(finalize(() => {
      this.excludingListingIds.update(ids => {
        const next = new Set(ids);
        next.delete(listingId);
        return next;
      });
    })).subscribe({
      next: response => {
        this.session.update(session => {
          if (!session) {
            return session;
          }
          const existing = session.exclusions.find(
            item => item.listingId === response.listingId,
          );
          return {
            ...session,
            preferenceVersion: response.preferenceVersion,
            updatedAt: response.updatedAt,
            exclusions: existing ? session.exclusions : [
              ...session.exclusions,
              {
                listingId: response.listingId,
                reasonCode: response.reasonCode,
                excludedAt: response.updatedAt,
              },
            ],
          };
        });
        this.liveStatus.set('Listing excluded from this discovery session.');
        queueMicrotask(() => this.liveRegion?.nativeElement.focus());
      },
      error: error => this.handleExclusionError(error),
    });
  }

  conditionLabel(condition: DiscoveryRecommendation['condition']): string {
    return condition.replaceAll('_', ' ').toLowerCase()
      .replace(/^\w/, letter => letter.toUpperCase());
  }

  locationLabel(item: DiscoveryRecommendation): string {
    return [item.publicCity, item.publicRegion].filter(Boolean).join(', ');
  }

  activityLabel(stage: DiscoveryProgressStage): string {
    return {
      MESSAGE_ACCEPTED: 'Request accepted',
      UNDERSTANDING: 'Understanding your request',
      CHECKING_AVAILABILITY: 'Checking current availability',
      SEARCHING: 'Searching current public listings',
      CHECKING: 'Checking price, availability, and verified matches',
      COMPOSING: 'Preparing your answer',
    }[stage];
  }

  /** Explicitly reruns generation for a committed USER row without resending it. */
  retryResponse(message: DiscoveryDisplayMessage): void {
    const current = this.session();
    const userMessageId = message.retryUserMessageId;
    if (!current || !userMessageId || this.sending() || this.loading()) {
      return;
    }
    this.clearError();
    this.sending.set(true);
    this.retryingResponse.set(true);
    this.resetStreamPresentation();
    this.submittedBody.set('');
    this.liveStatus.set('Retrying the saved response.');
    this.activeStream = this.service.retryResponse(
      current.id,
      userMessageId,
      current.preferenceVersion,
    ).subscribe({
      next: event => {
        if (event.type === 'activity') {
          this.currentStreamStage.set(event.stage);
          this.streamStages.update(stages => stages.includes(event.stage)
            ? stages
            : [...stages, event.stage]);
          if (event.stage === 'MESSAGE_ACCEPTED') {
            this.streamAccepted.set(true);
          }
          this.liveStatus.set(`${this.activityLabel(event.stage)}.`);
          return;
        }
        if (event.type === 'text_delta') {
          this.appendNetworkDelta(event.delta);
          return;
        }
        if (event.type === 'recommendations') {
          this.receivedRecommendations = true;
          return;
        }
        if (event.type === 'metadata') {
          this.receivedMetadata = true;
          return;
        }
        if (event.type === 'error') {
          this.streamInterrupted.set(true);
          this.streamStopped.set(false);
          this.currentStreamStage.set(null);
          return;
        }
        if (event.response.userMessage.id !== userMessageId
          || this.receivedCanonicalAnswer !== event.response.result.message
          || (event.type === 'done'
            && (!this.receivedRecommendations || !this.receivedMetadata))) {
          this.sending.set(false);
          this.retryingResponse.set(false);
          this.handleRetryError(new DiscoveryContractError());
          return;
        }
        this.commitRetryResponse(event.response, userMessageId);
      },
      error: error => {
        this.sending.set(false);
        this.retryingResponse.set(false);
        if (this.receivedCanonicalAnswer) {
          this.streamInterrupted.set(true);
        }
        this.handleRetryError(error);
      },
    });
  }

  /** Sends the prompt once and advances preference version only from success. */
  private send(session: DiscoverySession, body: string, draft = this.prompt().trim()): void {
    const clientMessageId = this.idFactory();
    this.sending.set(true);
    this.retryingResponse.set(false);
    this.stoppingGeneration = false;
    this.resetStreamPresentation();
    this.activeSendContext = { sessionId: session.id, clientMessageId, draft };
    this.submittedBody.set(body);
    this.liveStatus.set('Sending your request.');
    this.activeStream = this.service.streamMessage(
      session.id,
      clientMessageId,
      session.preferenceVersion,
      body,
    ).subscribe({
      next: event => {
        if (event.type === 'activity') {
          this.currentStreamStage.set(event.stage);
          this.streamStages.update(stages => stages.includes(event.stage)
            ? stages
            : [...stages, event.stage]);
          if (event.stage === 'MESSAGE_ACCEPTED') {
            this.streamAccepted.set(true);
            if (this.prompt().trim() === draft) {
              this.prompt.set('');
            }
          }
          this.liveStatus.set(`${this.activityLabel(event.stage)}.`);
          return;
        }
        if (event.type === 'text_delta') {
          this.appendNetworkDelta(event.delta);
          return;
        }
        if (event.type === 'recommendations') {
          this.receivedRecommendations = true;
          return;
        }
        if (event.type === 'metadata') {
          this.receivedMetadata = true;
          return;
        }
        if (event.type === 'error') {
          this.streamInterrupted.set(true);
          this.streamStopped.set(false);
          this.currentStreamStage.set(null);
          return;
        }
        const response = event.response;
        if (this.receivedCanonicalAnswer !== response.result.message
          || (event.type === 'done'
            && (!this.receivedRecommendations || !this.receivedMetadata))) {
          this.sending.set(false);
          this.handleSendError(new DiscoveryContractError(), session, clientMessageId, draft);
          return;
        }
        this.commitSendResponse(response);
      },
      error: error => {
        if (this.stoppingGeneration) {
          return;
        }
        this.sending.set(false);
        if (this.receivedCanonicalAnswer) {
          this.streamInterrupted.set(true);
        }
        this.handleSendError(error, session, clientMessageId, draft);
      },
    });
  }

  /** Appends each network-arriving designated final-answer delta immediately. */
  private appendNetworkDelta(delta: string): void {
    this.receivedCanonicalAnswer += delta;
    this.streamedAnswer.update(answer => answer + delta);
    this.followNewestContent();
  }

  /** Suspends auto-follow when the reader deliberately scrolls away from the end. */
  trackConversationScroll(): void {
    const element = this.conversationScroll?.nativeElement;
    if (element) {
      this.autoFollow = element.scrollHeight - element.scrollTop - element.clientHeight < 48;
    }
  }

  private followNewestContent(): void {
    const element = this.conversationScroll?.nativeElement;
    if (element && this.autoFollow) {
      element.scrollTop = element.scrollHeight;
    }
  }

  private resetStreamPresentation(): void {
    this.activeStream?.unsubscribe();
    this.activeStream = null;
    this.streamStages.set([]);
    this.currentStreamStage.set(null);
    this.streamedAnswer.set('');
    this.streamAccepted.set(false);
    this.streamInterrupted.set(false);
    this.streamStopped.set(false);
    this.receivedCanonicalAnswer = '';
    this.receivedRecommendations = false;
    this.receivedMetadata = false;
    this.autoFollow = true;
  }

  /** Stops the current response without posting or duplicating its USER message. */
  stopGeneration(): void {
    const active = this.activeSendContext;
    if (!this.sending() || !active || this.stoppingGeneration) {
      return;
    }
    this.stoppingGeneration = true;
    this.liveStatus.set('Stopping the response and checking whether your message was saved.');
    this.service.stopMessage(active.sessionId, active.clientMessageId).subscribe({
      next: result => {
        this.activeStream?.unsubscribe();
        this.activeStream = null;
        this.sending.set(false);
        this.retryingResponse.set(false);
        this.stoppingGeneration = false;
        this.activeSendContext = null;
        if (result.outcome === 'NOT_COMMITTED') {
          this.streamInterrupted.set(false);
          this.liveStatus.set('Request stopped. No message was saved; your draft was kept.');
          return;
        }
        this.pendingAmbiguousSend = active;
        if (this.prompt().trim() === active.draft) {
          this.prompt.set('');
        }
        this.streamInterrupted.set(result.outcome !== 'COMPLETED');
        this.streamStopped.set(result.outcome === 'STOPPED');
        this.liveStatus.set(result.outcome === 'STOPPED'
          ? (this.streamedAnswer()
            ? 'Generation stopped. Partial answer saved.'
            : 'Response stopped. Your message was saved.')
          : 'Checking the saved response.');
        this.refreshHistory(true);
      },
      error: () => {
        this.activeStream?.unsubscribe();
        this.activeStream = null;
        this.sending.set(false);
        this.retryingResponse.set(false);
        this.stoppingGeneration = false;
        this.pendingAmbiguousSend = active;
        this.activeSendContext = null;
        this.streamInterrupted.set(true);
        this.streamStopped.set(true);
        this.showError(
          'The stop result is still being confirmed.',
          'Your message was not sent again. Refresh history to check its saved state.',
          true,
        );
      },
    });
  }

  private commitRetryResponse(
    response: SendDiscoveryMessageResponse,
    userMessageId: string,
  ): void {
    this.session.update(session => session ? {
      ...session,
      preferenceState: response.result.preferenceState,
      preferenceVersion: response.preferenceVersion,
      updatedAt: response.userMessage.createdAt,
    } : session);
    this.messages.update(messages => [
      ...messages.map(item => item.retryUserMessageId === userMessageId
        ? { ...item, retryUserMessageId: null }
        : item),
      {
        key: `retry-result-${userMessageId}-${response.preferenceVersion}`,
        role: 'ASSISTANT',
        body: response.result.message,
        result: response.result,
        createdAt: response.userMessage.createdAt,
        retryUserMessageId: null,
        activitySummary: completedActivitySummary(this.streamStages()),
      },
    ]);
    this.restoreSelectedContext();
    this.sending.set(false);
    this.retryingResponse.set(false);
    this.liveStatus.set(this.completionStatus(response.result));
    queueMicrotask(() => this.liveRegion?.nativeElement.focus());
  }

  private commitSendResponse(response: SendDiscoveryMessageResponse): void {
    this.pendingAmbiguousSend = null;
    this.activeSendContext = null;
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
        retryUserMessageId: null,
        activitySummary: null,
      },
      {
        key: `result-${response.userMessage.id}`,
        role: 'ASSISTANT',
        body: response.result.message,
        result: response.result,
        createdAt: response.userMessage.createdAt,
        retryUserMessageId: null,
        activitySummary: completedActivitySummary(this.streamStages()),
      },
    ]);
    this.restoreSelectedContext();
    this.prompt.set('');
    if (response.result.preferenceState.selectedListingId === null) {
      this.selectedListing.set(null);
    }
    this.sending.set(false);
    this.liveStatus.set(this.completionStatus(response.result));
    queueMicrotask(() => this.liveRegion?.nativeElement.focus());
  }

  /** Refreshes authoritative state on conflict but requires an explicit retry. */
  private handleExclusionError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.showError(
        'This search changed before the exclusion was saved.',
        'The current session is being refreshed. Select Not interested again if needed.',
      );
      this.refreshHistory(true);
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 0) {
      this.showError(
        'The exclusion result is uncertain.',
        'It was not automatically sent again. Refresh before explicitly retrying.',
      );
      return;
    }
    if (error instanceof DiscoveryAuthenticationRequiredError
      || (error instanceof HttpErrorResponse && error.status === 401)) {
      this.authRequired.set(true);
      this.showError(
        'Sign in again to continue.',
        'The exclusion was not automatically sent again.',
      );
      return;
    }
    this.showError(
      'This listing could not be excluded.',
      'Nothing was changed. Try again explicitly when the service is available.',
    );
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
    this.messages.set(history.flatMap(toDisplayMessages));
    this.nextCursor.set(nextCursor);
    this.restoreSelectedContext();
  }

  /** Rebuilds selected-listing display state from typed persisted session data. */
  private restoreSelectedContext(): void {
    const listingId = this.session()?.preferenceState.selectedListingId;
    if (!listingId) {
      this.selectedListing.set(null);
      return;
    }
    for (const message of [...this.messages()].reverse()) {
      const recommendations = message.result?.recommendations ?? [];
      const rank = recommendations.findIndex(item => item.listingId === listingId);
      if (rank >= 0) {
        this.selectedListing.set({
          listingId,
          title: recommendations[rank].title,
          rank: rank + 1,
          intent: 'ASK',
        });
        return;
      }
    }
    this.selectedListing.set(null);
  }

  private handleSendError(
    error: unknown,
    session: DiscoverySession,
    clientMessageId: string,
    draft: string,
  ): void {
    if (!this.prompt()) {
      this.prompt.set(draft);
    }
    if (isAuthenticationError(error)) {
      this.authRequired.set(true);
      this.liveStatus.set('Authentication required. Your search was not sent.');
      return;
    }
    if (streamErrorStatus(error) === 409) {
      this.refreshAfterConflict();
      return;
    }
    if (error instanceof DiscoveryContractError) {
      if (this.streamAccepted()) {
        this.pendingAmbiguousSend = { sessionId: session.id, clientMessageId, draft };
        this.showError(
          'The streamed response was rejected.',
          'Refresh history to check the saved message. It was not automatically sent again.',
          true,
        );
        return;
      }
      this.showError(
        'The discovery response was rejected.',
        'No unsafe or incomplete result was displayed. Your text was not automatically sent again.',
      );
      return;
    }
    if (error instanceof DiscoveryStreamFailureError && error.status === 503) {
      this.pendingAmbiguousSend = {
        sessionId: session.id,
        clientMessageId,
        draft,
      };
      this.showError(
        'Marketplace listing search is temporarily unavailable.',
        'Refresh history to check whether this message was saved. Your text was kept and will never be sent again automatically.',
        true,
      );
      this.refreshHistory(true, true);
      return;
    }
    if (this.streamAccepted()) {
      this.pendingAmbiguousSend = { sessionId: session.id, clientMessageId, draft };
      this.showError(
        'The search result is uncertain.',
        'Refresh history to check the saved message. Your text was kept and was not automatically sent again.',
        true,
      );
      return;
    }
    this.showError(
      'The search result is uncertain.',
      'Your text was kept. It was not automatically sent again; retry only when you choose.',
    );
  }

  /** Reconciles a deliberate response retry without ever posting the USER row again. */
  private handleRetryError(error: unknown): void {
    if (isAuthenticationError(error)) {
      this.authRequired.set(true);
      this.showError(
        'Sign in again to retry this response.',
        'The saved message was not posted again.',
      );
      return;
    }
    if (streamErrorStatus(error) === 409) {
      this.showError(
        'This response cannot be retried in its current state.',
        'History is being refreshed; no user message was posted.',
      );
      this.refreshHistory(true, true);
      return;
    }
    if (error instanceof DiscoveryStreamFailureError && error.status === 503) {
      this.showError(
        'Marketplace listing search is still temporarily unavailable.',
        'The saved message was not duplicated. History is being refreshed.',
      );
      this.refreshHistory(true, true);
      return;
    }
    this.showError(
      'The retry result is uncertain.',
      'No user message was posted. Refresh history before choosing another action.',
      true,
    );
  }

  /** Reconciles an uncertain send only with the exact server-stored client ID. */
  private reconcilePendingAmbiguousSend(history: DiscoveryHistoryMessage[]): boolean {
    const pending = this.pendingAmbiguousSend;
    if (!pending) {
      return false;
    }
    const committed = history.some(message => (
      message.role === 'USER' && message.clientMessageId === pending.clientMessageId
    ));
    if (!committed) {
      return false;
    }
    this.pendingAmbiguousSend = null;
    if (this.prompt().trim() === pending.draft) {
      this.prompt.set('');
    }
    this.resetStreamPresentation();
    this.clearError();
    return true;
  }

  private handleCreateError(error: unknown): void {
    if (isAuthenticationError(error)) {
      this.authRequired.set(true);
      this.liveStatus.set('Authentication required. Your search was not sent.');
      return;
    }
    if (error instanceof DiscoveryContractError) {
      this.showError(
        'The discovery session response was rejected.',
        'The response did not match the safe marketplace contract. Your text was kept.',
      );
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      this.showError(
        'Marketplace discovery is not available in this environment.',
        'The Discovery page is enabled, but the Gateway Discovery route is disabled. Listing search was not attempted and your text was kept.',
      );
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 503) {
      this.showError(
        'Marketplace discovery is temporarily unavailable.',
        'The discovery session could not be opened. Listing search was not attempted and your text was kept.',
      );
      return;
    }
    this.showError(
      'The discovery session could not be opened.',
      'Listing search was not attempted. Your text was kept and was not automatically sent again.',
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
      'Marketplace conversation history is temporarily unavailable.',
      'Listing search was not attempted. Normal marketplace browsing still works; retry loading history when ready.',
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

function toDisplayMessages(message: DiscoveryHistoryMessage): DiscoveryDisplayMessage[] {
  const primary: DiscoveryDisplayMessage = {
    key: message.id,
    role: message.role,
    body: message.body,
    result: message.result,
    createdAt: message.createdAt,
    retryUserMessageId: message.responseRetry?.userMessageId ?? null,
    activitySummary: null,
  };
  if (message.role !== 'USER' || !message.responseFailure) {
    return [primary];
  }
  return [
    primary,
    {
      key: `failed-response-${message.id}`,
      role: 'ASSISTANT',
      body: message.responseFailure.message,
      result: message.responseFailure,
      createdAt: message.createdAt,
      retryUserMessageId: message.responseRetry?.userMessageId ?? null,
      activitySummary: null,
    },
  ];
}

function isAuthenticationError(error: unknown): boolean {
  return error instanceof DiscoveryAuthenticationRequiredError
    || streamErrorStatus(error) === 401;
}

function streamErrorStatus(error: unknown): number | null {
  if (error instanceof HttpErrorResponse || error instanceof DiscoveryStreamFailureError) {
    return error.status;
  }
  return null;
}

function hasUnsafeControls(value: string): boolean {
  return /[\u0000-\u0008\u000b-\u001f\u007f]/.test(value);
}

/** Collapses only milestones actually delivered for the completed turn. */
function completedActivitySummary(stages: readonly DiscoveryProgressStage[]): string | null {
  if (stages.includes('SEARCHING') && stages.includes('CHECKING') && stages.includes('COMPOSING')) {
    return 'Searched, verified, and compared current listings';
  }
  if (stages.includes('SEARCHING') && stages.includes('CHECKING')) {
    return 'Searched and verified current listings';
  }
  if (stages.includes('SEARCHING')) {
    return 'Searched current public listings';
  }
  return null;
}
