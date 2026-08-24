import {expect,Page,test} from '@playwright/test';

const ADMIN='01KG0VADMIN000000000000001';
const TARGET='01KG0VTARGET00000000000001';
const ASSIGNMENT='01KG0VASSIGN00000000000001';
const APPROVAL='01KG0VAPPR0V0000000000001';
const permissions=['admin.governance.read','admin.governance.roles.read','admin.governance.roles.manage','admin.governance.elevation.manage','admin.governance.approval.read','admin.governance.approval.request','admin.governance.approval.review'];

test('governance reader sees bounded dashboard evidence',async({page})=>{
 await mockAdmin(page,permissions);
 await page.route('**/api/v1/admin/governance',r=>r.fulfill({json:{admins:4,pendingApprovals:2,temporaryElevations:1,recentActions:[event('ADMIN_ELEVATION_GRANTED','ACTIVE')],highRiskActionSummary:{PENDING:2}}}));
 await page.goto('/admin/governance');
 await expect(page.getByRole('heading',{name:'Governance',exact:true})).toBeVisible();
 await expect(page.getByText('4',{exact:true})).toBeVisible();
 await expect(page.getByText('Admin Elevation Granted')).toBeVisible();
 await expect(page.locator('body')).not.toContainText('password');
});

test('temporary-elevation filter follows shortcut, tab, and browser history navigation',async({page})=>{
 await mockAdmin(page,permissions);
 await page.route('**/api/v1/admin/governance',r=>r.fulfill({json:{admins:4,pendingApprovals:0,temporaryElevations:1,recentActions:[],highRiskActionSummary:{}}}));
 await page.route('**/api/v1/admin/governance/roles',r=>r.fulfill({json:roles()}));
 const requestedFilters:(string|null)[]=[];
 await page.route('**/api/v1/admin/governance/admins?**',r=>{requestedFilters.push(new URL(r.request().url()).searchParams.get('hasTemporaryElevation'));return r.fulfill({json:{items:[],page:0,size:50,totalElements:0,totalPages:0,sort:'name,asc'}});});
 await page.goto('/admin/governance');
 await page.getByRole('link',{name:/Temporary elevations/}).click();
 await expect.poll(()=>requestedFilters.at(-1)).toBe('true');
 await expect(page.getByRole('combobox',{name:'Admin role'})).toBeVisible();
 await expect(page.getByRole('combobox',{name:'Admin state'})).toBeVisible();
 await expect(page.getByRole('combobox',{name:'Temporary elevation'})).toHaveValue('true');
 await page.getByRole('link',{name:'Admins',exact:true}).click();
 await expect(page).toHaveURL(/\/admin\/governance\/admins$/);
 await expect(page.getByRole('combobox',{name:'Temporary elevation'})).toHaveValue('');
 await expect.poll(()=>requestedFilters.at(-1)).toBe(null);
 await page.goBack();
 await expect(page).toHaveURL(/hasTemporaryElevation=true/);
 await expect(page.getByRole('combobox',{name:'Temporary elevation'})).toHaveValue('true');
 await expect.poll(()=>requestedFilters.at(-1)).toBe('true');
 await page.goForward();
 await expect(page).toHaveURL(/\/admin\/governance\/admins$/);
 await expect(page.getByRole('combobox',{name:'Temporary elevation'})).toHaveValue('');
 await expect.poll(()=>requestedFilters.at(-1)).toBe(null);
});

test('approval status follows query removal and browser history',async({page})=>{
 await mockAdmin(page,permissions);
 const requestedStatuses:(string|null)[]=[];
 await page.route('**/api/v1/admin/governance/approvals?**',r=>{requestedStatuses.push(new URL(r.request().url()).searchParams.get('status'));return r.fulfill({json:{items:[],page:0,size:50,totalElements:0,totalPages:0,sort:'createdAt,desc'}});});
 await page.goto('/admin/governance/approvals?status=FAILED');
 await expect(page.getByRole('combobox',{name:'Approval status'})).toHaveValue('FAILED');
 await expect.poll(()=>requestedStatuses.at(-1)).toBe('FAILED');
 await page.getByRole('link',{name:'Approvals',exact:true}).click();
 await expect(page).toHaveURL(/\/admin\/governance\/approvals$/);
 await expect(page.getByRole('combobox',{name:'Approval status'})).toHaveValue('PENDING');
 await expect(page.getByRole('combobox',{name:'Approval risk'})).toBeVisible();
 await expect.poll(()=>requestedStatuses.at(-1)).toBe('PENDING');
 await page.goBack();
 await expect(page.getByRole('combobox',{name:'Approval status'})).toHaveValue('FAILED');
 await page.goForward();
 await expect(page.getByRole('combobox',{name:'Approval status'})).toHaveValue('PENDING');
});

test('temporary elevation requires dry run and sends no client actor',async({page})=>{
 await mockAdmin(page,permissions);
 await page.route('**/api/v1/admin/governance/roles',r=>r.fulfill({json:roles()}));
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}`,r=>r.fulfill({json:adminDetail()}));
 let submitted=false;
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}/roles/dry-run`,async r=>{
  const body=JSON.parse(r.request().postData()||'{}');expect(body.role).toBe('OPERATIONS_ADMIN');expect(body.actorId).toBeUndefined();expect(body.grantedBy).toBeUndefined();
  await r.fulfill({json:{adminUserId:TARGET,role:'OPERATIONS_ADMIN',changeType:'GRANT',currentRoles:['SUPPORT_ADMIN'],proposedRoles:['SUPPORT_ADMIN'],permissionsAdded:[],permissionsRemoved:[],effectiveAt:body.effectiveAt,expiresAt:body.expiresAt,warnings:['Permissions disappear automatically when the assignment expires.'],protectedAdminImpact:false,lastSuperAdminImpact:false,approvalRequired:false,requiredApprovals:0,governanceVersion:3}});
 });
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}/roles`,async r=>{submitted=true;await r.fulfill({status:201,json:{outcome:'ROLE_GRANTED',assignment:{...assignment(),role:'OPERATIONS_ADMIN',status:'SCHEDULED'},approval:null,replayed:false}});});
 await page.goto(`/admin/governance/admins/${TARGET}`);
 await page.getByLabel('Role').selectOption('OPERATIONS_ADMIN');
 await page.getByLabel('Effective').fill('2026-08-24T18:00');await page.getByLabel('Expires').fill('2026-08-24T20:00');await page.getByLabel('Reason').fill('Production incident INC-123');
 await page.getByRole('button',{name:'Dry run'}).click();
 await expect(page.getByText(/Permissions disappear automatically/)).toBeVisible();
 await page.getByRole('button',{name:'Confirm governed change'}).click();
 await expect.poll(()=>submitted).toBe(true);
});

test('requester cannot approve their own sensitive action',async({page})=>{
 await mockAdmin(page,permissions);
 await page.route(`**/api/v1/admin/governance/approvals/${APPROVAL}`,r=>r.fulfill({json:approvalDetail(false)}));
 await page.goto(`/admin/governance/approvals/${APPROVAL}`);
 await expect(page.getByText('Requesters cannot review their own sensitive actions.')).toBeVisible();
 await expect(page.getByRole('button',{name:'Approve',exact:true})).toBeDisabled();
 await expect(page.getByRole('button',{name:'Execute approved action'})).toBeDisabled();
});

test('independent reviewer records one approval decision',async({page})=>{
 await mockAdmin(page,permissions);
 await page.route(`**/api/v1/admin/governance/approvals/${APPROVAL}`,r=>r.fulfill({json:approvalDetail(true)}));
 let reviewed=0;
 await page.route(`**/api/v1/admin/governance/approvals/${APPROVAL}/approve`,async r=>{reviewed++;expect(r.request().headers()['idempotency-key']).toBeTruthy();await r.fulfill({json:{...approvalDetail(true),approval:{...approvalDetail(true).approval,currentApprovals:1,status:'APPROVED',version:1},availableCapabilities:{...approvalDetail(true).availableCapabilities,canApprove:false,canReject:false,canExecute:true}}});});
 await page.goto(`/admin/governance/approvals/${APPROVAL}`);
 await page.getByLabel('Decision reason').fill('Independently verified the current refund impact');
 await page.getByRole('button',{name:'Approve',exact:true}).click();
 await expect.poll(()=>reviewed).toBe(1);
 await expect(page.getByText('1 / 1 approvals')).toBeVisible();
});

test('last effective super admin cannot be revoked',async({page})=>{
 await mockAdmin(page,permissions);
 const detail={...adminDetail(),effectiveRoles:['SUPER_ADMIN'],activeAssignments:[{...assignment(),role:'SUPER_ADMIN'}],availableCapabilities:{...adminDetail().availableCapabilities,lastSuperAdmin:true}};
 await page.route('**/api/v1/admin/governance/roles',r=>r.fulfill({json:roles()}));
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}`,r=>r.fulfill({json:detail}));
 let revokeRequests=0;
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}/roles/${ASSIGNMENT}/revoke/dry-run`,r=>r.fulfill({status:409,json:{error:{message:'The final effective SUPER_ADMIN assignment cannot be revoked.'}}}));
 await page.route(`**/api/v1/admin/governance/admins/${TARGET}/roles/${ASSIGNMENT}/revoke`,r=>{revokeRequests++;return r.abort();});
 await page.goto(`/admin/governance/admins/${TARGET}`);
 await page.getByLabel('Reason').fill('Attempted unsafe removal');
 await page.getByRole('button',{name:'Review revoke'}).click();
 await expect(page.getByRole('alert')).toContainText('final effective SUPER_ADMIN');
 expect(revokeRequests).toBe(0);
});

test('dual-approved super-admin grant executes once with distinct decisions preserved',async({page})=>{
 await mockAdmin(page,permissions);
 const approved=approvalDetail(true);
 approved.approval={...approved.approval,actionType:'ADMIN_ROLE_GRANT_SUPER_ADMIN',riskLevel:'CRITICAL',requiredApprovals:2,currentApprovals:2,status:'APPROVED',version:2};
 approved.safeActionSummary='Grant SUPER_ADMIN to Support Admin';
 approved.decisions=[
  {decisionId:'decision-a',approverAdminId:'01KG0VREVIEWER000000000001',decision:'APPROVE',reason:'First independent review',createdAt:'2026-08-23T01:00:00Z'},
  {decisionId:'decision-b',approverAdminId:'01KG0VREVIEWER000000000002',decision:'APPROVE',reason:'Second independent review',createdAt:'2026-08-23T02:00:00Z'}
 ];
 approved.availableCapabilities={...approved.availableCapabilities,canApprove:false,canReject:false,canExecute:true};
 let executions=0;
 await page.route(`**/api/v1/admin/governance/approvals/${APPROVAL}`,r=>r.fulfill({json:approved}));
 await page.route(`**/api/v1/admin/governance/approvals/${APPROVAL}/execute`,r=>{executions++;return r.fulfill({json:{...approved,approval:{...approved.approval,status:'EXECUTED',version:3},availableCapabilities:{...approved.availableCapabilities,canExecute:false},executionReference:ASSIGNMENT,executorAdminId:ADMIN,executedAt:'2026-08-23T03:00:00Z'}});});
 await page.goto(`/admin/governance/approvals/${APPROVAL}`);
 await expect(page.getByText('2 / 2 approvals')).toBeVisible();
 await expect(page.getByText('First independent review')).toBeVisible();
 await expect(page.getByText('Second independent review')).toBeVisible();
 await page.getByRole('button',{name:'Execute approved action'}).click();
 await expect.poll(()=>executions).toBe(1);
 await expect(page.getByText(`Execution reference: ${ASSIGNMENT}`)).toBeVisible();
});

async function mockAdmin(page:Page,adminPermissions:string[]){await page.route('**/api/v1/auth/session',r=>r.fulfill({json:{authenticated:true,user:{subject:ADMIN,email:'governance@msb.local',displayName:'Governance Admin',roles:['GOVERNANCE_ADMIN']},csrf:{headerName:'X-XSRF-TOKEN',parameterName:'_csrf',token:'csrf'}}}));await page.route('**/api/v1/users/me',r=>r.fulfill({status:503,json:{error:{message:'Use session fallback.'}}}));await page.route('**/api/v1/admin/me',r=>r.fulfill({json:{data:{userId:ADMIN,role:'GOVERNANCE_ADMIN',roles:['GOVERNANCE_ADMIN'],permissions:adminPermissions,accountState:'ACTIVE'}}}));}
function roles(){return[{role:'SUPPORT_ADMIN',description:'Support',assignable:true,permissions:['admin.support.read']},{role:'OPERATIONS_ADMIN',description:'Operations',assignable:true,permissions:['admin.system.read']},{role:'SUPER_ADMIN',description:'Super admin',assignable:true,permissions:permissions}];}
function assignment(){return{assignmentId:ASSIGNMENT,adminUserId:TARGET,role:'SUPPORT_ADMIN',status:'ACTIVE',effectiveAt:'2026-08-20T00:00:00Z',expiresAt:null,grantedByAdminId:ADMIN,reason:'Support coverage',createdAt:'2026-08-20T00:00:00Z',revokedAt:null,revokedByAdminId:null,revocationReason:null,version:0};}
function adminDetail(){return{adminUserId:TARGET,safeDisplayName:'Support Admin',marketplaceAccountState:'ACTIVE',adminState:'ADMIN_ENABLED',effectiveRoles:['SUPPORT_ADMIN'],effectivePermissions:['admin.support.read'],activeAssignments:[assignment()],historicalAssignments:[],temporaryElevations:[],recentAdminActivity:[],governanceTimeline:[],availableCapabilities:{canManageRoles:true,canManageElevation:true,protectedAdmin:false,lastSuperAdmin:false,self:false,readOnly:false,readOnlyReason:null},governanceVersion:3};}
function summary(){return{approvalId:APPROVAL,actionType:'LARGE_REFUND',riskLevel:'HIGH',targetType:'PAYMENT',targetId:'01KG0VPAYMENT0000000000001',safeTargetLabel:'Large refund for payment',requesterAdminId:ADMIN,requiredApprovals:1,currentApprovals:0,status:'PENDING',createdAt:'2026-08-23T00:00:00Z',expiresAt:'2026-08-23T04:00:00Z',version:0};}
function approvalDetail(canReview:boolean){return{approval:summary(),safeActionSummary:'Refund 2500 USD for payment',safeActionPayload:{amount:2500,currency:'USD'},policy:{actionType:'LARGE_REFUND',riskLevel:'HIGH',approvalMode:'SINGLE_APPROVAL',requiredApprovals:1,requesterMayApprove:false,approvalExpiresAfterMinutes:240,requiredRequesterPermission:'admin.refund.execute',requiredApproverPermission:'admin.refund.execute',thresholdValue:1000,thresholdCurrency:'USD',enabled:true,version:0},decisions:[],timeline:[event('SENSITIVE_ACTION_REQUESTED','PENDING')],availableCapabilities:{canApprove:canReview,canReject:canReview,canExecute:false,canCancel:!canReview,selfApprovalProhibited:!canReview,expired:false,readOnly:false,readOnlyReason:canReview?null:'Requesters cannot review their own sensitive actions.'},executionReference:null,executionFailureCode:null,executionFailureSummary:null,executorAdminId:null,executedAt:null};}
function event(eventType:string,outcome:string){return{eventId:'01KG0VEVENT000000000000001',eventType,actorAdminId:ADMIN,subjectAdminId:TARGET,approvalRequestId:null,targetType:'ADMIN_ROLE_ASSIGNMENT',targetId:ASSIGNMENT,reason:'Reviewed governance change',outcome,safeMetadata:{},correlationId:'governance-correlation',createdAt:'2026-08-23T00:00:00Z'};}
