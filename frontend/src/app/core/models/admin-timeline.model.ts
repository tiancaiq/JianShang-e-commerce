export type AdminTimelineSource = 'HUMAN_ADMIN' | 'SYSTEM';

export interface AdminTimelineEntry {
  eventId: string | null;
  occurredAt: string;
  eventType: string;
  actorType: string;
  actorId: string | null;
  actorDisplay: string | null;
  source: AdminTimelineSource;
  targetType: string;
  targetId: string;
  moderationCaseId: string | null;
  previousState: string | null;
  newState: string | null;
  reason: string | null;
  correlationId: string | null;
  metadata: Record<string, string>;
}
