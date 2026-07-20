export interface AgentListingSelection {
  listingId: string;
  title: string;
}

const LISTING_ID_PATTERN = /^[0-9A-Z]{26}$/;
const TITLE_LIMIT = 180;

/** Normalizes an untrusted route or picker value before it can identify Agent context. */
export function normalizeAgentListingId(value: string | null | undefined): string | null {
  const normalized = value?.trim().toUpperCase() || '';
  return LISTING_ID_PATTERN.test(normalized) ? normalized : null;
}

/** Produces bounded public-title display text without carrying control characters into Agent UI. */
export function safeAgentListingTitle(value: string | null | undefined): string {
  const normalized = (value || '')
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, TITLE_LIMIT);
  return normalized || 'Selected listing';
}

/** Builds the only listing context shape accepted by Agent UI launch boundaries. */
export function toAgentListingSelection(
  listingId: string | null | undefined,
  title: string | null | undefined,
): AgentListingSelection | null {
  const normalizedListingId = normalizeAgentListingId(listingId);
  return normalizedListingId
    ? {
      listingId: normalizedListingId,
      title: safeAgentListingTitle(title),
    }
    : null;
}
