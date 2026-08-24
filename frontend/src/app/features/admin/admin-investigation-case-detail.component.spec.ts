import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { InvestigationCaseDetail } from '../../core/models/investigation-case.model';
import { InvestigationCaseService } from '../../core/services/investigation-case.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminInvestigationCaseDetailComponent } from './admin-investigation-case-detail.component';

describe('AdminInvestigationCaseDetailComponent', () => {
  let fixture:ComponentFixture<AdminInvestigationCaseDetailComponent>;
  beforeEach(async()=>{
    const cases=jasmine.createSpyObj<InvestigationCaseService>('InvestigationCaseService',['detail']);
    cases.detail.and.returnValue(of(detail()));
    await TestBed.configureTestingModule({imports:[AdminInvestigationCaseDetailComponent],providers:[
      provideZonelessChangeDetection(),provideRouter([]),
      {provide:ActivatedRoute,useValue:{snapshot:{paramMap:{get:()=> '01C00000000000000000000001'}}}},
      {provide:InvestigationCaseService,useValue:cases},
      {provide:ToastService,useValue:jasmine.createSpyObj<ToastService>('ToastService',['success'])},
    ]}).compileComponents();
    fixture=TestBed.createComponent(AdminInvestigationCaseDetailComponent);fixture.detectChanges();
  });

  it('renders private notes, read-only enforcement context, and timeline without mutation controls for another admin',()=>{
    expect(fixture.nativeElement.textContent).toContain('Admin-only · append-only');
    expect(fixture.nativeElement.textContent).toContain('actions resulting from this case are identified separately');
    expect(fixture.nativeElement.textContent).toContain('Case created');
    expect(fixture.nativeElement.querySelector('textarea[name="note"]')).toBeNull();
  });

  it('uses staff-friendly evidence labels and localized timestamps for current target state',()=>{
    const text=fixture.nativeElement.textContent as string;
    const timestamp=fixture.nativeElement.querySelector('dd[title="2026-08-16T00:02:00Z"]') as HTMLElement|null;
    expect(text).toContain('Business ID');
    expect(text).toContain('Captured at');
    expect(text).not.toContain('Businessid');
    expect(timestamp).not.toBeNull();
    expect(timestamp?.textContent).not.toContain('2026-08-16T00:02:00Z');
  });

  it('renders independent validated impact and withholds execution without target permission',()=>{
    const item=detail();item.status='READY_FOR_ACTION';item.availableAdminCapabilities.isCaseReadyForAction=true;
    item.enforcementProposals=[{proposalId:'01P00000000000000000000001',targetType:'LISTING',targetId:item.primaryTarget.targetId,
      safeTargetLabel:'Walnut radio',actionType:'SUSPEND',scopes:['LISTING_PUBLIC_VISIBILITY','LISTING_PURCHASABILITY'],
      reasonCode:'COUNTERFEIT',reason:'Confirmed counterfeit evidence',effectiveAt:null,expiresAt:null,
      expectedTargetVersion:2,status:'VALIDATED',createdByAdminId:'01A',createdAt:item.createdAt,updatedAt:item.updatedAt,
      version:1,dryRunValidatedAt:item.updatedAt,dryRunTargetVersion:2,dryRunResult:{},resultingEnforcementActionId:null,
      executionErrorCode:null,executionErrorSummary:null,correlationId:'corr',availableAdminCapabilities:{canEdit:false,
        canCancel:false,canDryRun:false,canExecute:false,canRetry:false,hasRequiredTargetPermission:false}}];
    fixture.componentInstance.value.set(item);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Enforcement Plan');
    expect(fixture.nativeElement.textContent).toContain('Hides the listing from public discovery.');
    expect(fixture.nativeElement.textContent).toContain('lacks the target enforcement permission');
    expect(fixture.nativeElement.textContent).not.toContain('Review & execute');
  });
});

function detail():InvestigationCaseDetail{return {
  caseId:'01C00000000000000000000001',title:'Counterfeit reports — Listing',status:'UNDER_INVESTIGATION',severity:'HIGH',
  primaryTarget:{targetType:'LISTING',targetId:'01L00000000000000000000001',safeTargetLabel:'Walnut radio',relationshipType:'PRIMARY',linkedAt:'2026-08-16T00:00:00Z',currentState:{status:'ACTIVE',businessId:'01B00000000000000000000001',capturedAt:'2026-08-16T00:02:00Z'},currentEnforcement:[],adminDetailPath:'/admin/listings/moderation?query=01L'},
  linkedTargets:[],linkedReports:[{reportId:'01R00000000000000000000001',reasonCode:'COUNTERFEIT',severity:'HIGH',status:'LINKED_TO_CASE',safeTargetLabel:'Walnut radio',description:'Looks counterfeit',targetSnapshot:{title:'Walnut radio'},createdAt:'2026-08-16T00:00:00Z',version:4}],
  assignedAdmin:{userId:'01A00000000000000000000002',safeDisplayName:'Other Admin'},notes:[{noteId:'01N',body:'Internal finding',authorAdminId:'01A',authorDisplayName:'Admin One',createdAt:'2026-08-16T00:01:00Z',correlationId:'corr'}],evidence:[],
  enforcementProposals:[],resultingEnforcement:[],
  caseTimeline:[{eventId:'01E',occurredAt:'2026-08-16T00:00:00Z',eventType:'CASE_CREATED',actorType:'PLATFORM_ADMIN',actorId:'01A',actorDisplayName:'Admin One',source:'HUMAN_ADMIN',previousState:null,newState:'OPEN',reasonCode:null,reason:null,correlationId:'corr',requestId:'01Q',safeMetadata:{}}],
  conclusionCode:null,conclusionReason:null,readyForActionAt:null,closedAt:null,createdAt:'2026-08-16T00:00:00Z',updatedAt:'2026-08-16T00:01:00Z',version:3,
  availableAdminCapabilities:{canRead:true,canClaim:false,canRelease:false,canStartInvestigation:false,canLinkReport:false,canUnlinkReport:false,canLinkTarget:false,canUnlinkTarget:false,canAddNote:false,canAddEvidence:false,canChangeSeverity:false,canMarkReadyForAction:false,canCloseNoAction:false,canCreateEnforcementProposal:false,canCloseActioned:false,isCaseReadyForAction:false,isAssignedToMe:false,isAssignedToOther:true,isClosed:false,isReadOnly:true,readOnlyReason:'This case is assigned to another admin.'},
}}
