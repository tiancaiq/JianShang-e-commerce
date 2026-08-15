import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

/**
 * Keeps every Agent UI entry point hidden unless an explicit build-time
 * capability opt-in is supplied. The committed production default is false.
 */
export const AGENT_CUSTOMER_SERVICE_ENABLED = new InjectionToken<boolean>(
  'AGENT_CUSTOMER_SERVICE_ENABLED',
  {
    providedIn: 'root',
    factory: () => environment.features.aiAssistant === true,
  },
);

/**
 * Keeps the Marketplace Assistant discovery-first whenever the Agent UI is
 * exposed. Listing-bound help remains an explicit component-level flow.
 */
export const AGENT_DISCOVERY_ENABLED = new InjectionToken<boolean>(
  'AGENT_DISCOVERY_ENABLED',
  {
    providedIn: 'root',
    factory: () => environment.features.aiDiscovery === true
      || environment.features.aiAssistant === true,
  },
);

/** Selects the parallel V2 orchestrator only in explicitly opted-in frontend builds. */
export const AGENT_MARKETPLACE_V2_ENABLED = new InjectionToken<boolean>(
  'AGENT_MARKETPLACE_V2_ENABLED',
  {
    providedIn: 'root',
    factory: () => (environment.features as Record<string, boolean>)['marketplaceAgentV2'] === true,
  },
);

export const AGENT_CLIENT_MESSAGE_ID_FACTORY = new InjectionToken<() => string>(
  'AGENT_CLIENT_MESSAGE_ID_FACTORY',
  {
    providedIn: 'root',
    factory: () => generateClientMessageId,
  },
);

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/**
 * Generates a valid ULID-shaped idempotency identifier without introducing a
 * frontend dependency or accepting actor identity from browser state.
 */
export function generateClientMessageId(): string {
  let timestamp = Date.now();
  let encodedTime = '';
  for (let index = 0; index < 10; index += 1) {
    encodedTime = CROCKFORD[timestamp % 32] + encodedTime;
    timestamp = Math.floor(timestamp / 32);
  }

  const random = new Uint8Array(16);
  globalThis.crypto.getRandomValues(random);
  let encodedRandom = '';
  for (let index = 0; index < 16; index += 1) {
    encodedRandom += CROCKFORD[random[index] & 31];
  }
  return `${encodedTime}${encodedRandom}`;
}
