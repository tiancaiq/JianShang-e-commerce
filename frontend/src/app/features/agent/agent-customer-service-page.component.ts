import { Component, OnInit, inject } from '@angular/core';
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
            {{ marketplaceDiscoveryEnabled ? 'Marketplace assistant' : 'Listing assistant' }}
          </h1>
          <p>
            {{ marketplaceDiscoveryEnabled
              ? 'Describe your need, compare current listings, and keep refining in one conversation.'
              : 'Ask grounded questions about one public listing.' }}
          </p>
        </div>
        <a routerLink="/account/messages">Buyer and seller messages</a>
      </header>
      @if (marketplaceDiscoveryEnabled) {
        <app-agent-marketplace-discovery />
      } @else if (customerServiceEnabled) {
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

    @media (max-width: 640px) {
      header {
        align-items: flex-start;
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
  readonly marketplaceDiscoveryEnabled = this.discoveryEnabled || this.customerServiceEnabled;

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
