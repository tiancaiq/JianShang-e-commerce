import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AGENT_CUSTOMER_SERVICE_ENABLED,
  AGENT_DISCOVERY_ENABLED,
} from './agent-customer-service.capability';
import { AgentCustomerServiceThreadComponent } from './agent-customer-service-thread.component';
import { toAgentListingSelection } from './agent-listing-context.model';
import { AgentMarketplaceDiscoveryComponent } from './agent-marketplace-discovery.component';

@Component({
  selector: 'app-agent-customer-service-page',
  standalone: true,
  imports: [
    AgentCustomerServiceThreadComponent,
    AgentMarketplaceDiscoveryComponent,
    RouterLink,
  ],
  template: `
    <section class="agent-page">
      <header>
        <div>
          <span>Account messages</span>
          <h1>
            {{ discoveryEnabled && !showListingHelp()
              ? 'Marketplace discovery'
              : 'Listing assistant' }}
          </h1>
          <p>
            {{ discoveryEnabled && !showListingHelp()
              ? 'Find public listings conversationally, then decide what to view.'
              : 'Ask grounded questions about one public listing.' }}
          </p>
        </div>
        <a routerLink="/account/messages">Buyer and seller messages</a>
      </header>
      @if (discoveryEnabled && !showListingHelp()) {
        <app-agent-marketplace-discovery />
        @if (customerServiceEnabled) {
          <aside class="listing-help">
            <div>
              <strong>Already viewing an item?</strong>
              <span>Ask listing-specific questions in the separate listing assistant.</span>
            </div>
            <button type="button" (click)="showListingHelp.set(true)">Ask about a listing</button>
          </aside>
        }
      } @else if (customerServiceEnabled) {
        @if (discoveryEnabled && !initialListing && !sessionId) {
          <button type="button" class="back-to-discovery" (click)="showListingHelp.set(false)">
            Back to marketplace discovery
          </button>
        }
        <app-agent-customer-service-thread
          [sessionId]="sessionId"
          [initialListing]="initialListing" />
      }
    </section>
  `,
  styles: [`
    .agent-page {
      display: grid;
      gap: 1rem;
      min-height: calc(100vh - 170px);
      color: var(--market-ink);
    }

    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
      padding: 1rem;
    }

    header span {
      color: var(--market-accent-dark);
      font-size: 0.7rem;
      font-weight: 950;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    h1,
    p {
      margin: 0;
    }

    h1 {
      margin-top: 0.15rem;
      font-size: 1.45rem;
      font-weight: 950;
    }

    p {
      margin-top: 0.2rem;
      color: var(--market-muted);
      font-size: 0.82rem;
      font-weight: 750;
    }

    a {
      color: var(--market-accent-dark);
      font-weight: 900;
      text-decoration: none;
    }

    .listing-help {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      border: 1px solid var(--market-line);
      border-radius: 10px;
      background: white;
      padding: 0.9rem 1rem;
    }

    .listing-help div {
      display: grid;
      gap: 0.2rem;
    }

    .listing-help span {
      color: var(--market-muted);
      font-size: 0.82rem;
    }

    .listing-help button,
    .back-to-discovery {
      min-height: 2.65rem;
      border: 1px solid var(--market-accent-dark);
      border-radius: 8px;
      background: white;
      color: var(--market-accent-dark);
      padding: 0.6rem 0.85rem;
      font: inherit;
      font-weight: 850;
      cursor: pointer;
    }

    .back-to-discovery {
      justify-self: start;
    }

    button:focus-visible {
      outline: 3px solid color-mix(in srgb, var(--market-accent) 55%, white);
      outline-offset: 2px;
    }

    @media (max-width: 640px) {
      header {
        align-items: flex-start;
        flex-direction: column;
      }

      .listing-help {
        align-items: stretch;
        flex-direction: column;
      }
    }
  `],
})
export class AgentCustomerServicePageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly rawListingId = this.route.snapshot.queryParamMap.get('listingId');
  private readonly rawListingTitle = this.route.snapshot.queryParamMap.get('title');
  readonly sessionId = this.route.snapshot.paramMap.get('sessionId');
  readonly initialListing = toAgentListingSelection(
    this.rawListingId,
    this.rawListingTitle,
  );
  readonly discoveryEnabled = inject(AGENT_DISCOVERY_ENABLED);
  readonly customerServiceEnabled = inject(AGENT_CUSTOMER_SERVICE_ENABLED);
  readonly showListingHelp = signal(
    !this.discoveryEnabled || this.initialListing !== null || this.sessionId !== null,
  );

  ngOnInit(): void {
    if (this.rawListingId === null && this.rawListingTitle === null) {
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { listingId: null, title: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
