import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Category } from '../../core/models/listing.model';
import { generateClientMessageId } from './agent-customer-service.capability';
import {
  ListingProposalApplicationCommand,
  ListingProposalApplicationState,
  ListingProposalCategoryCandidate,
  ListingProposalContent,
  ListingProposalResponse,
  ListingProposalReviewChoice,
  ListingProposalReviewMedia,
  ListingProposalSuggestedText,
  ListingProposalUnknownField,
} from './agent-listing-proposal.model';
import {
  AgentListingProposalService,
  ListingProposalContractError,
} from './agent-listing-proposal.service';

interface ReviewField {
  choice: ListingProposalReviewChoice;
  value: string;
}

const UNKNOWN_FIELD_LABELS: Record<ListingProposalUnknownField, string> = {
  TITLE: 'Title',
  DESCRIPTION: 'Description',
  CATEGORY: 'Category',
  SELLER_IDENTITY: 'Seller identity',
  PRICE: 'Price',
  EXACT_LOCATION: 'Exact location',
  QUANTITY: 'Quantity',
  CONDITION: 'Condition',
  NEGOTIABILITY: 'Negotiability',
  POLICY_CLAIMS: 'Policy claims',
  CONTACT_DATA: 'Contact data',
  BRAND: 'Brand',
  MODEL: 'Model',
  AUTHENTICITY: 'Authenticity',
  SAFETY: 'Safety',
};

@Component({
  selector: 'app-agent-listing-proposal-review',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="proposal-workbench" aria-labelledby="proposal-workbench-title">
      <header class="workbench-header">
        <div>
          <span class="eyebrow">Optional AI draft assistant</span>
          <h2 id="proposal-workbench-title">Build suggestions from your listing photos</h2>
          <p>
            Choose up to four eligible images. Suggestions stay in this review area and never
            update your listing until you review and confirm selected fields. They never submit
            or publish it.
          </p>
        </div>
        <span class="proposal-only">Proposal only</span>
      </header>

      @if (!proposal()) {
        <fieldset class="media-selector" [disabled]="loading()">
          <legend>1. Choose listing images</legend>
          <p class="section-note">Only uploaded JPEG, PNG, and WebP images can be analyzed.</p>

          @if (eligibleMedia().length === 0) {
            <div class="empty-state">
              Add and save an eligible listing image before asking for suggestions.
            </div>
          } @else {
            <div class="image-grid">
              @for (media of eligibleMedia(); track media.mediaId) {
                <label class="image-option" [class.selected]="isSelected(media.mediaId)">
                  <input
                    type="checkbox"
                    [checked]="isSelected(media.mediaId)"
                    [disabled]="!isSelected(media.mediaId) && selectedMediaIds().length >= 4"
                    (change)="toggleMedia(media.mediaId)"
                  />
                  <img [src]="media.imageUrl" [alt]="media.altText" />
                  <span>{{ isSelected(media.mediaId) ? 'Selected' : 'Select image' }}</span>
                </label>
              }
            </div>
          }

          <p class="selection-count" aria-live="polite">
            {{ selectedMediaIds().length }} of 4 images selected
          </p>
        </fieldset>

        @if (errorMessage()) {
          <div class="outage-panel" role="alert">
            <strong>Suggestions are unavailable</strong>
            <span>{{ errorMessage() }}</span>
            <span>Your normal listing editor is still available above.</span>
          </div>
        }

        <div class="start-actions">
          <button
            type="button"
            class="primary-action"
            [disabled]="selectedMediaIds().length < 1 || loading()"
            (click)="createOrResume()"
          >
            {{ loading() ? 'Preparing suggestions…' : (errorMessage() ? 'Retry suggestions' : 'Create or resume review') }}
          </button>
        </div>
      } @else if (proposal()?.status === 'READY') {
        @if (versionChanged()) {
          <div class="version-warning" role="alert">
            <strong>Listing version changed</strong>
            <span>
              These suggestions used version {{ proposal()?.sourceListingVersion }}, while the
              editor is now on version {{ listingVersion() }}. Regenerate before applying.
            </span>
          </div>
        }

        <div class="review-layout">
          <aside class="evidence-rail" aria-labelledby="proposal-evidence-heading">
            <span class="step-marker">2</span>
            <h3 id="proposal-evidence-heading">Evidence trail</h3>
            <p>Each suggestion points back to observations from your selected images.</p>
            <ol>
              @for (evidence of proposalContent()?.evidence || []; track evidence.evidenceId) {
                <li>
                  <span class="evidence-id">{{ evidence.evidenceId }}</span>
                  <span>{{ evidence.observation }}</span>
                </li>
              }
            </ol>
          </aside>

          <div class="suggestion-stack" aria-labelledby="proposal-suggestions-heading">
            <div class="suggestion-heading">
              <span class="step-marker">3</span>
              <div>
                <h3 id="proposal-suggestions-heading">Review each suggestion</h3>
                <p>Keep it, edit a seller-controlled draft value, or discard it.</p>
              </div>
            </div>

            <fieldset class="suggestion-card">
              <legend>Title suggestion</legend>
              @if (proposalContent()?.suggestedTitle; as suggestion) {
                <div class="confidence-row">
                  <span>{{ confidenceLabel(suggestion.confidence) }} confidence</span>
                  <span>{{ percent(suggestion.confidence) }}</span>
                  <span>Evidence {{ suggestion.evidenceIds.join(', ') }}</span>
                </div>
                <div class="choice-row" role="radiogroup" aria-label="Title suggestion decision">
                  @for (choice of reviewChoices; track choice) {
                    <label>
                      <input
                        type="radio"
                        name="proposalTitleChoice"
                        [value]="choice"
                        [ngModel]="titleReview().choice"
                        (ngModelChange)="setTitleChoice($event)"
                      />
                      {{ choiceLabel(choice) }}
                    </label>
                  }
                </div>
                <label class="review-input">
                  <span>Reviewed title</span>
                  <input
                    maxlength="160"
                    [disabled]="titleReview().choice !== 'EDIT'"
                    [ngModel]="titleReview().value"
                    [ngModelOptions]="{ standalone: true }"
                    (ngModelChange)="setTitleValue($event)"
                  />
                </label>
              } @else {
                <p class="unknown-note">The images did not provide enough evidence for a title.</p>
              }
            </fieldset>

            <fieldset class="suggestion-card">
              <legend>Description suggestion</legend>
              @if (proposalContent()?.suggestedDescription; as suggestion) {
                <div class="confidence-row">
                  <span>{{ confidenceLabel(suggestion.confidence) }} confidence</span>
                  <span>{{ percent(suggestion.confidence) }}</span>
                  <span>Evidence {{ suggestion.evidenceIds.join(', ') }}</span>
                </div>
                <div class="choice-row" role="radiogroup" aria-label="Description suggestion decision">
                  @for (choice of reviewChoices; track choice) {
                    <label>
                      <input
                        type="radio"
                        name="proposalDescriptionChoice"
                        [value]="choice"
                        [ngModel]="descriptionReview().choice"
                        (ngModelChange)="setDescriptionChoice($event)"
                      />
                      {{ choiceLabel(choice) }}
                    </label>
                  }
                </div>
                <label class="review-input">
                  <span>Reviewed description</span>
                  <textarea
                    rows="5"
                    maxlength="5000"
                    [disabled]="descriptionReview().choice !== 'EDIT'"
                    [ngModel]="descriptionReview().value"
                    [ngModelOptions]="{ standalone: true }"
                    (ngModelChange)="setDescriptionValue($event)"
                  ></textarea>
                </label>
              } @else {
                <p class="unknown-note">The images did not provide enough evidence for a description.</p>
              }
            </fieldset>

            <fieldset class="suggestion-card">
              <legend>Category suggestion</legend>
              @if ((proposalContent()?.categoryCandidates?.length || 0) > 0) {
                <div class="candidate-list" aria-label="Category candidates">
                  @for (candidate of proposalContent()?.categoryCandidates || []; track candidate.label) {
                    <button type="button" (click)="chooseCategoryCandidate(candidate)">
                      <strong>{{ candidate.label }}</strong>
                      <span>{{ percent(candidate.confidence) }} · {{ candidate.evidenceIds.join(', ') }}</span>
                    </button>
                  }
                </div>
                <div class="choice-row" role="radiogroup" aria-label="Category suggestion decision">
                  @for (choice of reviewChoices; track choice) {
                    <label>
                      <input
                        type="radio"
                        name="proposalCategoryChoice"
                        [value]="choice"
                        [ngModel]="categoryReview().choice"
                        (ngModelChange)="setCategoryChoice($event)"
                      />
                      {{ choiceLabel(choice) }}
                    </label>
                  }
                </div>
                <p class="category-reference">
                  AI label reference: <strong>{{ categoryReview().value || 'No candidate selected' }}</strong>
                </p>
                <label class="review-input">
                  <span>Existing listing category</span>
                  <select
                    aria-describedby="proposal-category-guidance"
                    [disabled]="categoryReview().choice === 'DISCARD'"
                    [ngModel]="selectedCategoryId()"
                    [ngModelOptions]="{ standalone: true }"
                    (ngModelChange)="selectCategoryId($event)"
                  >
                    <option value="">Choose an existing category</option>
                    @for (category of categories(); track category.id) {
                      <option [value]="category.id">{{ category.name }}</option>
                    }
                  </select>
                </label>
                <p id="proposal-category-guidance" class="unknown-note">
                  Candidate labels are never converted to IDs. A category changes only after you
                  explicitly choose one from this Product category list.
                </p>
              } @else {
                <p class="unknown-note">The images did not provide enough evidence for a category.</p>
              }
            </fieldset>
          </div>
        </div>

        <section class="not-inferred" aria-labelledby="proposal-not-inferred-heading">
          <h3 id="proposal-not-inferred-heading">Not inferred from images</h3>
          <p>You must enter and verify these fields yourself.</p>
          <ul>
            @for (field of unsupportedFields(); track field) {
              <li>{{ field }}</li>
            }
          </ul>
        </section>

        @if (actionError()) {
          <div class="outage-panel" role="alert">{{ actionError() }}</div>
        }

        @if (applicationState() === 'APPLIED') {
          <div class="application-outcome success-outcome" role="status">
            <strong>Selected fields applied</strong>
            <span>{{ applicationMessage() }}</span>
            <span>Current listing version: {{ appliedVersion() }}</span>
          </div>
        } @else if (
          confirmationOpen()
          && applicationState() !== 'CONFLICT'
        ) {
          <section
            #confirmationLedger
            class="confirmation-ledger"
            role="region"
            aria-labelledby="proposal-confirmation-heading"
            tabindex="-1"
          >
            <header>
              <div>
                <span class="step-marker">4</span>
                <h3 id="proposal-confirmation-heading">Confirm exact final values</h3>
              </div>
              <span class="version-seal">Source version {{ proposal()?.sourceListingVersion }}</span>
            </header>
            <p>
              Only the fields listed below will replace their current listing values. Product
              will recheck ownership, editable state, category, and this exact version.
            </p>
            <dl>
              @for (field of confirmationFields(); track field.name) {
                <div>
                  <dt>{{ field.label }}</dt>
                  <dd>{{ field.value }}</dd>
                </div>
              }
            </dl>
            <div class="confirmation-warning">
              This saves a new draft version only. It does not submit, publish, or message anyone.
            </div>
            <div class="confirmation-actions">
              <button type="button" class="secondary-action" (click)="backToReview()">Back to review</button>
              <button
                type="button"
                class="primary-action"
                [disabled]="applicationState() === 'APPLYING' || versionChanged() || hasUnsavedEditorChanges()"
                (click)="confirmApplication()"
              >
                {{ applicationState() === 'APPLYING' ? 'Applying…' : 'Confirm and update listing' }}
              </button>
            </div>
          </section>
        } @else {
          @if (applicationState() === 'CONFLICT' || applicationState() === 'FAILED') {
            <div class="application-outcome error-outcome" role="alert">
              <strong>{{ applicationState() === 'CONFLICT' ? 'Listing version conflict' : 'Selected fields were not applied' }}</strong>
              <span>{{ applicationMessage() }}</span>
            </div>
          }
          <div class="review-actions">
            <button
              type="button"
              class="secondary-action"
              [disabled]="dismissing()"
              (click)="dismiss()"
            >
              {{ dismissing() ? 'Dismissing…' : 'Dismiss proposal' }}
            </button>
            <div class="apply-boundary">
              <button
                type="button"
                class="primary-action"
                [disabled]="!canOpenConfirmation()"
                (click)="openConfirmation()"
              >
                Review final values
              </button>
              @if (hasUnsavedEditorChanges()) {
                <span>Save or discard ordinary editor changes before applying proposal fields.</span>
              } @else if (confirmationFields().length === 0) {
                <span>Keep or edit at least one valid suggestion first.</span>
              } @else {
                <span>A separate confirmation is required before Product is updated.</span>
              }
            </div>
          </div>
        }
      } @else {
        <div class="dismissed-state" role="status">
          <strong>{{ proposal()?.status === 'EXPIRED' ? 'Proposal expired' : 'Proposal dismissed' }}</strong>
          <span>No proposal content remains. Your listing was not changed.</span>
          <button type="button" class="secondary-action" (click)="startFresh()">Start a new review</button>
        </div>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .proposal-workbench {
      width: min(100%, 960px);
      display: grid;
      gap: 1.25rem;
      margin: 0 auto;
      padding: 1.25rem;
      overflow: hidden;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-lg);
      background:
        linear-gradient(135deg, rgba(249, 142, 7, 0.08), transparent 38%),
        var(--listing-surface);
      box-shadow: var(--listing-shadow);
      color: var(--listing-text);
    }

    .workbench-header,
    .suggestion-heading,
    .review-actions,
    .start-actions {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    h2,
    h3,
    p {
      margin: 0;
    }

    .workbench-header h2 {
      max-width: 680px;
      margin-top: 0.2rem;
      font-size: clamp(1.25rem, 3vw, 1.75rem);
    }

    .workbench-header p,
    .section-note,
    .evidence-rail p,
    .suggestion-heading p,
    .not-inferred p,
    .apply-boundary span {
      color: var(--listing-muted);
      font-size: 0.875rem;
    }

    .eyebrow {
      color: var(--listing-accent);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .proposal-only,
    .evidence-id,
    .step-marker {
      flex: 0 0 auto;
      border-radius: 999px;
      background: var(--listing-accent-muted);
      color: var(--listing-accent);
      font-size: 0.75rem;
      font-weight: 800;
    }

    .proposal-only {
      padding: 0.35rem 0.65rem;
    }

    fieldset {
      min-width: 0;
      border: 0;
    }

    legend {
      margin-bottom: 0.35rem;
      color: var(--listing-text);
      font-weight: 800;
    }

    .media-selector {
      display: grid;
      gap: 0.75rem;
    }

    .image-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 0.75rem;
    }

    .image-option {
      position: relative;
      min-width: 0;
      display: grid;
      grid-template-rows: 112px auto;
      overflow: hidden;
      border: 2px solid transparent;
      border-radius: var(--radius-md);
      background: var(--listing-field);
      cursor: pointer;
    }

    .image-option.selected {
      border-color: var(--listing-accent);
    }

    .image-option input {
      position: absolute;
      top: 0.5rem;
      left: 0.5rem;
      width: 1.1rem;
      height: 1.1rem;
      accent-color: var(--listing-accent);
    }

    .image-option img {
      width: 100%;
      height: 112px;
      object-fit: cover;
      background: var(--listing-field);
    }

    .image-option span {
      padding: 0.55rem 0.65rem;
      color: var(--listing-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .selection-count {
      color: var(--listing-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .empty-state,
    .outage-panel,
    .version-warning,
    .dismissed-state {
      display: grid;
      gap: 0.25rem;
      padding: 0.8rem;
      border-radius: var(--radius-md);
      font-size: 0.875rem;
    }

    .empty-state,
    .dismissed-state {
      border: 1px dashed var(--listing-border);
      color: var(--listing-muted);
    }

    .outage-panel {
      border: 1px solid rgba(244, 63, 94, 0.24);
      background: rgba(244, 63, 94, 0.07);
      color: var(--listing-muted);
    }

    .outage-panel strong {
      color: var(--color-danger);
    }

    .version-warning {
      border: 1px solid rgba(245, 158, 11, 0.3);
      background: rgba(245, 158, 11, 0.1);
      color: var(--listing-muted);
    }

    .version-warning strong {
      color: var(--color-warning);
    }

    .review-layout {
      min-width: 0;
      display: grid;
      grid-template-columns: minmax(210px, 0.75fr) minmax(0, 2fr);
      gap: 1rem;
    }

    .evidence-rail {
      min-width: 0;
      padding: 1rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      background: var(--listing-field);
    }

    .step-marker {
      width: 1.6rem;
      height: 1.6rem;
      display: inline-grid;
      place-items: center;
      margin-bottom: 0.6rem;
    }

    .evidence-rail ol {
      display: grid;
      gap: 0.6rem;
      margin: 0.8rem 0 0;
      padding: 0;
      list-style: none;
    }

    .evidence-rail li {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 0.5rem;
      align-items: start;
      color: var(--listing-muted);
      font-size: 0.8125rem;
      overflow-wrap: anywhere;
    }

    .evidence-id {
      padding: 0.1rem 0.4rem;
    }

    .suggestion-stack {
      min-width: 0;
      display: grid;
      gap: 0.75rem;
    }

    .suggestion-heading {
      justify-content: flex-start;
    }

    .suggestion-card {
      display: grid;
      gap: 0.75rem;
      padding: 0.9rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      background: var(--listing-field);
    }

    .confidence-row,
    .choice-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.45rem 0.75rem;
      color: var(--listing-muted);
      font-size: 0.75rem;
    }

    .confidence-row span:first-child {
      color: var(--listing-accent);
      font-weight: 800;
    }

    .choice-row label {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      font-weight: 700;
      cursor: pointer;
    }

    .choice-row input {
      accent-color: var(--listing-accent);
    }

    .review-input {
      display: grid;
      gap: 0.35rem;
      color: var(--listing-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .review-input input,
    .review-input textarea,
    .review-input select {
      width: 100%;
      min-width: 0;
      padding: 0.65rem 0.75rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      background: var(--listing-surface);
      color: var(--listing-text);
      resize: vertical;
    }

    .review-input input:focus-visible,
    .review-input textarea:focus-visible,
    .review-input select:focus-visible,
    button:focus-visible,
    .image-option:focus-within {
      outline: 3px solid var(--listing-accent-muted);
      outline-offset: 2px;
    }

    .candidate-list {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .candidate-list button {
      display: grid;
      gap: 0.1rem;
      max-width: 100%;
      padding: 0.5rem 0.65rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      background: var(--listing-surface);
      color: var(--listing-text);
      text-align: left;
      cursor: pointer;
    }

    .candidate-list span,
    .unknown-note {
      color: var(--listing-muted);
      font-size: 0.75rem;
    }

    .category-reference {
      margin: 0;
      color: var(--listing-muted);
    }

    .not-inferred {
      padding-top: 1rem;
      border-top: 1px solid var(--listing-border);
    }

    .not-inferred ul {
      display: flex;
      flex-wrap: wrap;
      gap: 0.45rem;
      margin: 0.7rem 0 0;
      padding: 0;
      list-style: none;
    }

    .not-inferred li {
      padding: 0.3rem 0.55rem;
      border: 1px solid var(--listing-border);
      border-radius: 999px;
      color: var(--listing-muted);
      font-size: 0.75rem;
      font-weight: 700;
    }

    button {
      min-height: 40px;
      border-radius: var(--radius-md);
      font-weight: 800;
    }

    .primary-action,
    .secondary-action {
      padding: 0 0.9rem;
      cursor: pointer;
    }

    .primary-action {
      border: 1px solid transparent;
      background: var(--listing-primary-bg);
      color: var(--listing-primary-text);
    }

    .secondary-action {
      border: 1px solid var(--listing-border);
      background: var(--listing-field);
      color: var(--listing-muted);
    }

    button:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .apply-boundary {
      display: grid;
      justify-items: end;
      gap: 0.25rem;
      text-align: right;
    }

    .confirmation-ledger {
      display: grid;
      gap: 1rem;
      padding: 1rem;
      border: 2px solid var(--listing-primary-bg);
      border-radius: var(--radius-lg);
      background: var(--listing-surface);
    }

    .confirmation-ledger header,
    .confirmation-ledger header > div,
    .confirmation-actions,
    .application-outcome {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    .confirmation-ledger header {
      justify-content: space-between;
    }

    .confirmation-ledger h3,
    .confirmation-ledger p,
    .confirmation-ledger dl {
      margin: 0;
    }

    .version-seal {
      flex: 0 0 auto;
      padding: 0.35rem 0.6rem;
      border: 1px solid var(--listing-border);
      border-radius: 999px;
      color: var(--listing-muted);
      font-size: 0.8rem;
      font-weight: 800;
    }

    .confirmation-ledger dl {
      display: grid;
      gap: 0.65rem;
    }

    .confirmation-ledger dl > div {
      display: grid;
      grid-template-columns: minmax(7rem, 0.3fr) minmax(0, 1fr);
      gap: 0.75rem;
      padding: 0.75rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      background: var(--listing-field);
    }

    .confirmation-ledger dt {
      color: var(--listing-muted);
      font-weight: 800;
    }

    .confirmation-ledger dd {
      min-width: 0;
      margin: 0;
      overflow-wrap: anywhere;
      white-space: pre-wrap;
    }

    .confirmation-warning {
      padding: 0.75rem;
      border-left: 4px solid var(--listing-accent);
      background: var(--listing-accent-muted);
      font-weight: 700;
    }

    .confirmation-actions {
      justify-content: flex-end;
      flex-wrap: wrap;
    }

    .application-outcome {
      align-items: flex-start;
      flex-direction: column;
      padding: 0.85rem;
      border-radius: var(--radius-md);
    }

    .success-outcome {
      border: 1px solid var(--color-success);
      background: color-mix(in srgb, var(--color-success) 8%, var(--listing-surface));
    }

    .error-outcome {
      border: 1px solid var(--color-danger);
      background: color-mix(in srgb, var(--color-danger) 8%, var(--listing-surface));
    }

    @media (max-width: 760px) {
      .proposal-workbench {
        padding: 1rem;
      }

      .workbench-header,
      .review-actions {
        flex-direction: column;
      }

      .review-layout {
        grid-template-columns: minmax(0, 1fr);
      }

      .image-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .apply-boundary {
        width: 100%;
        justify-items: stretch;
        text-align: left;
      }

      .confirmation-ledger header,
      .confirmation-actions {
        align-items: stretch;
        flex-direction: column;
      }

      .confirmation-ledger dl > div {
        grid-template-columns: minmax(0, 1fr);
      }

      .confirmation-actions button {
        width: 100%;
      }

      .review-actions > button,
      .start-actions button {
        width: 100%;
      }
    }

    @media (max-width: 430px) {
      .image-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `],
})
export class AgentListingProposalReviewComponent {
  private readonly proposalService = inject(AgentListingProposalService);

  readonly listingId = input.required<string>();
  readonly listingVersion = input.required<number>();
  readonly listingStatus = input.required<string>();
  readonly media = input.required<ListingProposalReviewMedia[]>();
  readonly categories = input.required<Category[]>();
  readonly hasUnsavedEditorChanges = input(false);
  readonly applicationState = input<ListingProposalApplicationState>('IDLE');
  readonly applicationMessage = input('');
  readonly appliedVersion = input<number | null>(null);
  readonly applyConfirmed = output<ListingProposalApplicationCommand>();
  readonly confirmationLedger = viewChild<ElementRef<HTMLElement>>('confirmationLedger');

  readonly eligibleMedia = computed(() => this.media().filter(item => item.eligible));
  readonly selectedMediaIds = signal<string[]>([]);
  readonly loading = signal(false);
  readonly dismissing = signal(false);
  readonly proposal = signal<ListingProposalResponse | null>(null);
  readonly errorMessage = signal('');
  readonly actionError = signal('');
  readonly titleReview = signal<ReviewField>({ choice: 'KEEP', value: '' });
  readonly descriptionReview = signal<ReviewField>({ choice: 'KEEP', value: '' });
  readonly categoryReview = signal<ReviewField>({ choice: 'KEEP', value: '' });
  readonly selectedCategoryId = signal('');
  readonly confirmationOpen = signal(false);
  readonly versionChanged = computed(() => {
    const proposal = this.proposal();
    return this.applicationState() !== 'APPLIED'
      && proposal?.status === 'READY'
      && proposal.sourceListingVersion !== this.listingVersion();
  });
  readonly proposalContent = computed(() => this.proposal()?.proposal || null);
  readonly reviewChoices = ['KEEP', 'EDIT', 'DISCARD'] as const;
  readonly unsupportedFields = computed(
    () => this.proposalContent()?.unknownFields.map(field => UNKNOWN_FIELD_LABELS[field]) || [],
  );
  readonly confirmationFields = computed(() => {
    const fields: Array<{ name: 'title' | 'description' | 'categoryId'; label: string; value: string }> = [];
    const title = this.reviewedText(this.titleReview(), 160);
    const description = this.reviewedText(this.descriptionReview(), 5_000);
    const category = this.categories().find(item => item.id === this.selectedCategoryId());
    if (title !== null) {
      fields.push({ name: 'title', label: 'Title', value: title });
    }
    if (description !== null) {
      fields.push({ name: 'description', label: 'Description', value: description });
    }
    if (this.categoryReview().choice !== 'DISCARD' && category) {
      fields.push({ name: 'categoryId', label: 'Category', value: category.name });
    }
    return fields;
  });
  /** Moves keyboard focus into the explicit confirmation ledger when it opens. */
  private readonly confirmationFocusEffect = effect(() => {
    if (this.confirmationOpen()) {
      this.confirmationLedger()?.nativeElement.focus();
    }
  });
  /** Keeps authoritative Product failures and conflicts visible instead of masking them with stale confirmation UI. */
  private readonly applicationOutcomeEffect = effect(() => {
    if (['FAILED', 'CONFLICT'].includes(this.applicationState())) {
      this.confirmationOpen.set(false);
    }
  });

  private clientRequestId = generateClientMessageId();
  private dismissIdempotencyKey = generateClientMessageId();
  private requestFingerprint = '';

  isSelected(mediaId: string): boolean {
    return this.selectedMediaIds().includes(mediaId);
  }

  toggleMedia(mediaId: string): void {
    if (!this.eligibleMedia().some(item => item.mediaId === mediaId)) {
      return;
    }
    const selected = this.selectedMediaIds();
    if (selected.includes(mediaId)) {
      this.selectedMediaIds.set(selected.filter(id => id !== mediaId));
    } else if (selected.length < 4) {
      this.selectedMediaIds.set([...selected, mediaId]);
    }
    this.rotateReplayKeysIfRequestChanged();
    this.errorMessage.set('');
  }

  /** Starts or replays proposal generation with the editor-owned listing version and media IDs. */
  createOrResume(): void {
    const mediaIds = [...this.selectedMediaIds()].sort();
    if (mediaIds.length < 1 || mediaIds.length > 4 || !['DRAFT', 'CHANGES_REQUESTED'].includes(this.listingStatus())) {
      return;
    }
    this.rotateReplayKeysIfRequestChanged();
    this.loading.set(true);
    this.errorMessage.set('');
    this.proposalService.createOrResume({
      schemaVersion: 'LISTING_PROPOSAL_V1',
      listingId: this.listingId(),
      expectedListingVersion: this.listingVersion(),
      mediaIds,
      clientRequestId: this.clientRequestId,
    }).subscribe({
      next: proposal => {
        this.loading.set(false);
        this.setProposal(proposal);
      },
      error: error => {
        this.loading.set(false);
        this.errorMessage.set(this.createErrorMessage(error));
      },
    });
  }

  /** Dismisses review content without invoking Product listing mutation. */
  dismiss(): void {
    const proposal = this.proposal();
    if (!proposal || proposal.status !== 'READY') {
      return;
    }
    this.dismissing.set(true);
    this.actionError.set('');
    this.proposalService.dismiss(proposal.proposalId, this.dismissIdempotencyKey).subscribe({
      next: response => {
        this.dismissing.set(false);
        this.setProposal(response);
      },
      error: () => {
        this.dismissing.set(false);
        this.actionError.set('The proposal could not be dismissed. Retry without leaving the editor.');
      },
    });
  }

  startFresh(): void {
    this.proposal.set(null);
    this.selectedMediaIds.set([]);
    this.errorMessage.set('');
    this.actionError.set('');
    this.selectedCategoryId.set('');
    this.confirmationOpen.set(false);
    this.requestFingerprint = '';
    this.clientRequestId = generateClientMessageId();
    this.dismissIdempotencyKey = generateClientMessageId();
  }

  setTitleChoice(choice: ListingProposalReviewChoice): void {
    this.titleReview.update(review => ({ ...review, choice }));
    this.confirmationOpen.set(false);
  }

  setDescriptionChoice(choice: ListingProposalReviewChoice): void {
    this.descriptionReview.update(review => ({ ...review, choice }));
    this.confirmationOpen.set(false);
  }

  setCategoryChoice(choice: ListingProposalReviewChoice): void {
    this.categoryReview.update(review => ({ ...review, choice }));
    if (choice === 'DISCARD') {
      this.selectedCategoryId.set('');
    }
    this.confirmationOpen.set(false);
  }

  setTitleValue(value: string): void {
    this.titleReview.set({ choice: 'EDIT', value });
    this.confirmationOpen.set(false);
  }

  setDescriptionValue(value: string): void {
    this.descriptionReview.set({ choice: 'EDIT', value });
    this.confirmationOpen.set(false);
  }

  chooseCategoryCandidate(candidate: ListingProposalCategoryCandidate): void {
    this.categoryReview.set({ choice: 'KEEP', value: candidate.label });
    this.confirmationOpen.set(false);
  }

  selectCategoryId(categoryId: string): void {
    this.selectedCategoryId.set(
      this.categories().some(category => category.id === categoryId) ? categoryId : '',
    );
    if (this.selectedCategoryId()) {
      this.categoryReview.update(review => ({ ...review, choice: 'EDIT' }));
    }
    this.confirmationOpen.set(false);
  }

  canOpenConfirmation(): boolean {
    return this.confirmationFields().length > 0
      && !this.versionChanged()
      && !this.hasUnsavedEditorChanges()
      && !['APPLYING', 'APPLIED', 'CONFLICT'].includes(this.applicationState());
  }

  openConfirmation(): void {
    if (this.canOpenConfirmation()) {
      this.confirmationOpen.set(true);
    }
  }

  backToReview(): void {
    if (this.applicationState() !== 'APPLYING') {
      this.confirmationOpen.set(false);
    }
  }

  /** Emits only the seller-visible final values after the distinct confirmation action. */
  confirmApplication(): void {
    const proposal = this.proposal();
    if (!proposal || proposal.status !== 'READY' || !this.confirmationOpen() || !this.canOpenConfirmation()) {
      return;
    }
    const fields = Object.fromEntries(
      this.confirmationFields().map(field => [
        field.name,
        field.name === 'categoryId' ? this.selectedCategoryId() : field.value,
      ]),
    );
    this.applyConfirmed.emit({
      proposalId: proposal.proposalId,
      listingId: proposal.listingId,
      sourceListingVersion: proposal.sourceListingVersion,
      fields,
    });
  }

  confidenceLabel(confidence: number): string {
    if (confidence >= 0.8) {
      return 'High';
    }
    if (confidence >= 0.55) {
      return 'Medium';
    }
    return 'Low';
  }

  percent(confidence: number): string {
    return `${Math.round(confidence * 100)}%`;
  }

  choiceLabel(choice: ListingProposalReviewChoice): string {
    return choice.charAt(0) + choice.slice(1).toLowerCase();
  }

  private setProposal(proposal: ListingProposalResponse): void {
    this.proposal.set(proposal);
    this.actionError.set('');
    this.confirmationOpen.set(false);
    this.selectedCategoryId.set('');
    if (proposal.status !== 'READY' || !proposal.proposal) {
      return;
    }
    this.titleReview.set(this.initialReview(proposal.proposal.suggestedTitle));
    this.descriptionReview.set(this.initialReview(proposal.proposal.suggestedDescription));
    this.categoryReview.set({
      choice: proposal.proposal.categoryCandidates.length > 0 ? 'KEEP' : 'DISCARD',
      value: proposal.proposal.categoryCandidates[0]?.label || '',
    });
  }

  private initialReview(suggestion: ListingProposalSuggestedText | null): ReviewField {
    return suggestion
      ? { choice: 'KEEP', value: suggestion.value }
      : { choice: 'DISCARD', value: '' };
  }

  private reviewedText(review: ReviewField, maximum: number): string | null {
    if (review.choice === 'DISCARD') {
      return null;
    }
    const value = review.value.trim();
    return value.length >= 1 && value.length <= maximum ? value : null;
  }

  private rotateReplayKeysIfRequestChanged(): void {
    const fingerprint = JSON.stringify({
      listingId: this.listingId(),
      version: this.listingVersion(),
      mediaIds: [...this.selectedMediaIds()].sort(),
    });
    if (this.requestFingerprint && fingerprint !== this.requestFingerprint) {
      this.clientRequestId = generateClientMessageId();
      this.dismissIdempotencyKey = generateClientMessageId();
    }
    this.requestFingerprint = fingerprint;
  }

  private createErrorMessage(error: unknown): string {
    if (error instanceof ListingProposalContractError) {
      return 'The Agent response was not safe to display. Your listing was not changed.';
    }
    if (!(error instanceof HttpErrorResponse)) {
      return 'The Agent service could not prepare suggestions. Try again later.';
    }
    const code = this.errorCode(error);
    if (error.status === 409 && code === 'AI_PROPOSAL_SOURCE_VERSION_CONFLICT') {
      return 'The listing or selected images changed. Reload the editor before retrying.';
    }
    if (error.status === 409 && code === 'AI_PROPOSAL_IDEMPOTENCY_CONFLICT') {
      return 'This review request no longer matches the selected images. Select the images again.';
    }
    if (error.status === 410 || code === 'AI_PROPOSAL_EXPIRED') {
      return 'The previous proposal expired. Start a new review.';
    }
    return 'The Agent service is unavailable. Continue editing normally or retry later.';
  }

  private errorCode(error: HttpErrorResponse): string | null {
    const body = error.error as { error?: { code?: unknown } } | null;
    return typeof body?.error?.code === 'string' ? body.error.code : null;
  }
}
