export type SupportCategory = 'ACCOUNT_HELP'|'ORDER_HELP'|'PAYMENT_HELP'|'REFUND_HELP'|'SELLER_HELP'|'BUSINESS_VERIFICATION'|'LISTING_HELP'|'TECHNICAL_ISSUE'|'OTHER';
export type SupportStatus = 'OPEN'|'ASSIGNED'|'UNDER_REVIEW'|'WAITING_FOR_USER'|'RESOLVED';
export type SupportPriority = 'LOW'|'MEDIUM'|'HIGH'|'URGENT';
export type SupportAssignment = 'UNASSIGNED'|'ASSIGNED_TO_ME'|'ASSIGNED'|'ALL';
export type SupportTargetType = 'USER'|'ORDER'|'BUSINESS'|'LISTING'|'PAYMENT'|'REFUND'|'DISPUTE'|'TRUST_AND_SAFETY_REPORT'|'INVESTIGATION_CASE';
export type SupportDestinationType = 'DISPUTE'|'TRUST_AND_SAFETY'|'FINANCE'|'ORDER_OPERATIONS';
export type SupportResolutionCode = 'INFORMATION_PROVIDED'|'ISSUE_RESOLVED'|'USER_GUIDANCE_PROVIDED'|'DUPLICATE'|'ESCALATED_TO_SPECIALIZED_WORKFLOW'|'NO_ACTION_REQUIRED'|'OTHER';

export interface SupportUserSummary { ticketId:string;category:SupportCategory;subject:string;status:SupportStatus;createdAt:string;updatedAt:string; }
export interface SupportUserPage { items:SupportUserSummary[];page:number;size:number;totalElements:number;totalPages:number; }
export interface SupportMessage { messageId:string;author:string;body:string;createdAt:string; }
export interface SupportUserLink { targetType:SupportTargetType;targetId:string;safeLabel:string; }
export interface SupportUserEvent { eventId:string;occurredAt:string;eventType:string;safeLabel:string; }
export interface SupportUserDetail { ticketId:string;category:SupportCategory;subject:string;description:string;status:SupportStatus;createdAt:string;updatedAt:string;version:number;linkedReferences:SupportUserLink[];messages:SupportMessage[];resolutionCode:string|null;resolutionReason:string|null;resolvedAt:string|null;history:SupportUserEvent[]; }

export interface AdminSupportSummary { ticketId:string;requesterUserId:string;safeRequesterLabel:string;category:SupportCategory;subject:string;priority:SupportPriority;status:SupportStatus;assignedAdminId:string|null;linkedOrderId:string|null;createdAt:string;updatedAt:string;version:number; }
export interface AdminSupportPage { items:AdminSupportSummary[];page:number;size:number;totalElements:number;totalPages:number;sort:string; }
export interface SupportRequester { userId:string;safeDisplayName:string;accountState:string;piiAvailable:boolean;email:string|null; }
export interface AdminSupportMessage { messageId:string;authorType:string;authorUserId:string;authorDisplayName:string|null;body:string;createdAt:string; }
export interface SupportInternalNote { noteId:string;authorAdminId:string;authorDisplayName:string;body:string;createdAt:string; }
export interface AdminSupportLink { targetType:SupportTargetType;targetId:string;relationType:string;safeLabel:string;adminPath:string|null;linkedByType:string;linkedBy:string;linkedAt:string; }
export interface SupportEscalation { escalationId:string;destinationType:SupportDestinationType;destinationId:string;adminPath:string|null;createdByAdminId:string;reason:string;createdAt:string; }
export interface SupportTimeline { eventId:string;occurredAt:string;eventType:string;actorType:string;actorId:string|null;actorDisplayName:string|null;source:string;previousState:string|null;newState:string|null;reasonCode:string|null;reason:string|null;correlationId:string|null;requestId:string|null;safeMetadata:Record<string,string>; }
export interface SupportCapabilities { canRead:boolean;canClaim:boolean;canRelease:boolean;canRespond:boolean;canRequestInformation:boolean;canAddNote:boolean;canChangePriority:boolean;canLink:boolean;canUnlink:boolean;canResolve:boolean;canEscalate:boolean;isAssignedToMe:boolean;isAssignedToOther:boolean;isWaitingForUser:boolean;isFinal:boolean;isReadOnly:boolean;readOnlyReason:string|null; }
export interface AdminSupportDetail { summary:AdminSupportSummary;requester:SupportRequester;description:string;messages:AdminSupportMessage[];internalNotes:SupportInternalNote[];linkedEntities:AdminSupportLink[];escalations:SupportEscalation[];linkedContext:Record<string,unknown>;timeline:SupportTimeline[];resolutionCode:string|null;resolutionReason:string|null;resolvedAt:string|null;availableAdminCapabilities:SupportCapabilities; }

export interface CreateSupportTicketRequest { category:SupportCategory;subject:string;description:string;linkedOrderId?:string|null;linkedBusinessId?:string|null;linkedListingId?:string|null; }
