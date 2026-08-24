import { Browser, expect, Page, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const LISTING_ID='01E00000000000000000000204';
const BUYER={email:'trade.buyer@msb.local',password:'TradeBuyer!2026'};
const CASE_REPORTER={email:'support.admin@msb.local',password:'SupportAdmin!2026'};
const ADMIN_ONE={email:'admin.one@msb.local',password:'AdminOne!2026'};
const ADMIN_TWO={email:'admin.two@msb.local',password:'AdminTwo!2026'};
const RELATED_USER='01D00000000000000000000002';
const RELATED_BUSINESS='01KZCARTB00000000000000002';

test.describe.configure({mode:'serial'});

test('workflows 1-5: reports become one owned investigation and ready-for-action never enforces',async({browser})=>{
  test.setTimeout(240_000);
  const buyer=await authenticatedMarketplacePage(browser,CASE_REPORTER.email,CASE_REPORTER.password);
  const secondReporter=await authenticatedMarketplacePage(browser,ADMIN_TWO.email,ADMIN_TWO.password);
  const adminA=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  const adminB=await authenticatedAdminPage(browser,ADMIN_TWO.email,ADMIN_TWO.password);
  try{
    const reportA=await submitReport(buyer,'SCAM','[E2E] Multiple suspicious claims for investigation.');
    const reportB=await submitReport(secondReporter,'COUNTERFEIT','[E2E] Independent counterfeit allegation.');
    await triageReady(adminA,reportA);await triageReady(adminA,reportB);

    await adminA.goto(`/admin/reports/${reportA}`);
    await adminA.getByLabel('Case title').fill('[E2E] Repeated listing integrity reports');
    await adminA.getByRole('button',{name:'Create investigation case'}).click();
    await expect(adminA).toHaveURL(/\/admin\/cases\//);
    const caseId=adminA.url().split('/').pop()!;
    await expect(adminA.getByText('Original report snapshot')).toBeVisible();
    await expect(adminA.getByText(LISTING_ID).first()).toBeVisible();

    await adminA.getByRole('button',{name:'Claim case'}).click();
    await adminA.getByLabel('Start rationale').fill('A human investigator is beginning evidence review.');
    await adminA.getByRole('button',{name:'Start investigation'}).click();

    const reportBDetail=await adminDetail(adminA,`/api/v1/admin/reports/${reportB}`);
    await adminA.getByLabel('Ready report ID').fill(reportB);
    await adminA.getByLabel('Report version').fill(String(reportBDetail.version));
    await adminA.getByRole('button',{name:'Link report'}).click();
    await expect(adminA.getByText('Linked reports')).toBeVisible();
    await expect(adminA.locator('.report')).toHaveCount(2);

    await adminB.goto(`/admin/cases/${caseId}`);
    await expect(adminB.getByText('This case is assigned to another admin.')).toBeVisible();
    await expect(adminB.getByLabel('Add internal note')).toHaveCount(0);
    await adminA.getByRole('button',{name:'Release'}).click();
    await adminB.reload();await adminB.getByRole('button',{name:'Claim case'}).click();

    await adminB.getByLabel('Add internal note').fill('Two independent reports corroborate a listing integrity concern.');
    await adminB.getByRole('button',{name:'Add note'}).click();
    await adminB.getByLabel('Target type').selectOption('BUSINESS');await adminB.getByLabel('Target ID').fill(RELATED_BUSINESS);await adminB.getByRole('button',{name:'Link related target'}).click();
    await adminB.getByLabel('Target type').selectOption('USER');await adminB.getByLabel('Target ID').fill(RELATED_USER);await adminB.getByRole('button',{name:'Link related target'}).click();
    await adminB.getByLabel('Evidence type').selectOption('REPORT_SNAPSHOT');await adminB.getByLabel('Reference ID').fill(reportA);await adminB.getByLabel('Label').fill('Original allegation snapshot');await adminB.getByRole('button',{name:'Add evidence reference'}).click();
    const severityForm=adminB.locator('.actions form').filter({has:adminB.getByRole('button',{name:'Update severity'})});
    await severityForm.getByLabel('Severity').selectOption('CRITICAL');await severityForm.getByLabel('Reason',{exact:true}).fill('Corroborated allegations require prompt action review.');await severityForm.getByRole('button',{name:'Update severity'}).click();
    await expect(adminB.getByText('Note added',{exact:true})).toBeVisible();await expect(adminB.getByText('Target linked',{exact:true}).first()).toBeVisible();await expect(adminB.getByText('Evidence added',{exact:true})).toBeVisible();

    const before=await activeListingEnforcement(adminB);
    await adminB.getByLabel('Action-review conclusion').fill('Human investigation is complete and should proceed to a separate enforcement decision.');
    await adminB.getByRole('button',{name:'Mark ready for action'}).click();
    await expect(adminB.getByText('Ready for action',{exact:true}).first()).toBeVisible();
    await expect(adminB.getByText('Enforcement Plan')).toBeVisible();
    expect(await activeListingEnforcement(adminB)).toBe(before);
  }finally{await buyer.context().close();await secondReporter.context().close();await adminA.context().close();await adminB.context().close()}
});

test('ADM-REP-04: a validated listing proposal executes once, links its action, and closes actioned',async({browser})=>{
  test.setTimeout(240_000);
  const buyer=await authenticatedMarketplacePage(browser,CASE_REPORTER.email,CASE_REPORTER.password);
  const admin=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  let actionId='';
  try{
    const reportId=await submitReport(buyer,'COUNTERFEIT','[E2E] Case-linked listing enforcement proposal.');
    await triageReady(admin,reportId);
    const report=await adminDetail(admin,`/api/v1/admin/reports/${reportId}`);
    const created=await adminPost(admin,`/api/v1/admin/reports/${reportId}/investigation-case`,{title:'[E2E] Case-linked listing action',severity:'HIGH',expectedReportVersion:report.version});
    const caseId=created.caseId as string;
    await admin.goto(`/admin/cases/${caseId}`);await admin.getByRole('button',{name:'Claim case'}).click();
    await admin.getByLabel('Start rationale').fill('Validate evidence before a separate enforcement action.');await admin.getByRole('button',{name:'Start investigation'}).click();
    await admin.getByLabel('Action-review conclusion').fill('Evidence supports an explicit listing suspension proposal.');await admin.getByRole('button',{name:'Mark ready for action'}).click();
    const before=await activeListingEnforcement(admin);
    await admin.getByLabel('Reason code').fill('COUNTERFEIT');await admin.getByLabel('Detailed reason').fill('Human-reviewed evidence supports temporary listing visibility and purchase restrictions.');
    await admin.getByRole('button',{name:'Save draft proposal'}).click();await expect(admin.getByText('Draft',{exact:true})).toBeVisible();
    await admin.getByRole('button',{name:'Run dry run'}).click();await expect(admin.getByText('Validated',{exact:true})).toBeVisible();
    admin.once('dialog',dialog=>dialog.accept());await admin.getByRole('button',{name:'Review & execute'}).click();
    await expect(admin.getByText('Executed',{exact:true})).toBeVisible();expect(await activeListingEnforcement(admin)).toBe(before+1);
    await expect(admin.getByText('Resulting enforcement')).toBeVisible();actionId=(await admin.locator('.result-link code').innerText()).trim();expect(actionId).not.toBe('');
    expect(await buyer.evaluate(async id=>(await fetch(`/api/v1/public/listings/${id}`)).status,LISTING_ID)).toBe(404);
    await admin.getByLabel('Actioned closure reason').fill('All required proposals executed successfully.');await admin.getByRole('button',{name:'Close case as actioned'}).click();
    await expect(admin.getByText('Closed actioned',{exact:true}).first()).toBeVisible();await expect(admin.getByRole('button',{name:'Save draft proposal'})).toHaveCount(0);
    const linkedActionId=actionId;await revokeListingEnforcement(admin,actionId);actionId='';await admin.reload();await expect(admin.getByText('Closed actioned',{exact:true}).first()).toBeVisible();await expect(admin.getByText(linkedActionId,{exact:true})).toBeVisible();
  }finally{if(actionId)await revokeListingEnforcement(admin,actionId).catch(()=>undefined);await buyer.context().close();await admin.context().close()}
});

test('ADM-REP-04: user, business, and listing proposals preserve partial success and retry safely',async({browser})=>{
  test.setTimeout(300_000);
  const buyer=await authenticatedMarketplacePage(browser,BUYER.email,BUYER.password);
  const admin=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  const actionIds:{user?:string;business?:string;listing?:string}={};
  try{
    const reportId=await submitReport(buyer,'MISLEADING_LISTING','[E2E] Multi-target case enforcement and retry.');await triageReady(admin,reportId);
    const report=await adminDetail(admin,`/api/v1/admin/reports/${reportId}`);
    const created=await adminPost(admin,`/api/v1/admin/reports/${reportId}/investigation-case`,{title:'[E2E] Multi-target partial failure',severity:'CRITICAL',expectedReportVersion:report.version});
    const caseId=created.caseId as string;await admin.goto(`/admin/cases/${caseId}`);await admin.getByRole('button',{name:'Claim case'}).click();
    await admin.getByLabel('Start rationale').fill('Review correlated user, business, and listing behavior.');await admin.getByRole('button',{name:'Start investigation'}).click();
    await admin.getByLabel('Target type').selectOption('BUSINESS');await admin.getByLabel('Target ID').fill(RELATED_BUSINESS);await admin.getByRole('button',{name:'Link related target'}).click();await expect(admin.locator('.line').filter({hasText:RELATED_BUSINESS})).toBeVisible();
    await admin.getByLabel('Target type').selectOption('USER');await admin.getByLabel('Target ID').fill(RELATED_USER);await admin.getByRole('button',{name:'Link related target'}).click();await expect(admin.locator('.line').filter({hasText:RELATED_USER})).toBeVisible();
    await admin.getByLabel('Action-review conclusion').fill('Each linked target requires an independent human-confirmed action.');await admin.getByRole('button',{name:'Mark ready for action'}).click();await expect(admin.getByText('Ready for action',{exact:true}).first()).toBeVisible();

    let detail=await adminDetail(admin,`/api/v1/admin/cases/${caseId}`);const version=(type:string)=>Number([detail.primaryTarget,...detail.linkedTargets].find((target:any)=>target.targetType===type).currentState.version);
    detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals`,proposalBody('USER',RELATED_USER,'RESTRICT',['USER_SELLING'],detail.version,version('USER')));
    detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals`,proposalBody('BUSINESS',RELATED_BUSINESS,'SUSPEND',['BUSINESS_NEW_SALES'],detail.version,version('BUSINESS')));
    detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals`,proposalBody('LISTING',LISTING_ID,'SUSPEND',['LISTING_PUBLIC_VISIBILITY','LISTING_PURCHASABILITY'],detail.version,version('LISTING')));
    for(const targetType of ['USER','BUSINESS','LISTING']){const proposal=detail.enforcementProposals.find((item:any)=>item.targetType===targetType);detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${proposal.proposalId}/dry-run`,{expectedCaseVersion:detail.version,expectedProposalVersion:proposal.version})}
    for(const targetType of ['USER','BUSINESS']){const proposal=detail.enforcementProposals.find((item:any)=>item.targetType===targetType);detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${proposal.proposalId}/execute`,{expectedCaseVersion:detail.version,expectedProposalVersion:proposal.version,idempotencyKey:`e2e-multi-${proposal.proposalId}`});actionIds[targetType.toLowerCase() as 'user'|'business']=detail.enforcementProposals.find((item:any)=>item.proposalId===proposal.proposalId).resultingEnforcementActionId}
    expect((await marketplaceCapabilities(buyer)).sellingAllowed).toBe(false);const businessState=await adminDetail(admin,`/api/v1/admin/businesses/${RELATED_BUSINESS}`);expect(businessState.activeEnforcementActions.some((item:any)=>item.enforcementActionId===actionIds.business)).toBe(true);

    bumpListingVersion();const listingProposal=detail.enforcementProposals.find((item:any)=>item.targetType==='LISTING');
    const failed=await adminPostResponse(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${listingProposal.proposalId}/execute`,{expectedCaseVersion:detail.version,expectedProposalVersion:listingProposal.version,idempotencyKey:`e2e-multi-${listingProposal.proposalId}`});expect(failed.status).toBe(409);
    detail=await adminDetail(admin,`/api/v1/admin/cases/${caseId}`);expect(detail.enforcementProposals.find((item:any)=>item.targetType==='USER').status).toBe('EXECUTED');expect(detail.enforcementProposals.find((item:any)=>item.targetType==='BUSINESS').status).toBe('EXECUTED');expect(detail.enforcementProposals.find((item:any)=>item.targetType==='LISTING').status).toBe('FAILED');
    expect((await adminPostResponse(admin,`/api/v1/admin/cases/${caseId}/close-actioned`,{expectedCaseVersion:detail.version,reason:'Must remain open.'})).status).toBe(409);

    const failedProposal=detail.enforcementProposals.find((item:any)=>item.targetType==='LISTING');const currentListingVersion=Number(detail.primaryTarget.currentState.version);
    detail=await adminPatch(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${failedProposal.proposalId}`,{...proposalBody('LISTING',LISTING_ID,'SUSPEND',['LISTING_PUBLIC_VISIBILITY','LISTING_PURCHASABILITY'],detail.version,currentListingVersion),expectedProposalVersion:failedProposal.version});
    let retry=detail.enforcementProposals.find((item:any)=>item.proposalId===failedProposal.proposalId);detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${retry.proposalId}/dry-run`,{expectedCaseVersion:detail.version,expectedProposalVersion:retry.version});retry=detail.enforcementProposals.find((item:any)=>item.proposalId===retry.proposalId);
    detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/enforcement-proposals/${retry.proposalId}/execute`,{expectedCaseVersion:detail.version,expectedProposalVersion:retry.version,idempotencyKey:`e2e-multi-${retry.proposalId}`});actionIds.listing=detail.enforcementProposals.find((item:any)=>item.proposalId===retry.proposalId).resultingEnforcementActionId;
    expect(await buyer.evaluate(async id=>(await fetch(`/api/v1/public/listings/${id}`)).status,LISTING_ID)).toBe(404);detail=await adminPost(admin,`/api/v1/admin/cases/${caseId}/close-actioned`,{expectedCaseVersion:detail.version,reason:'All independent actions are now resolved.'});expect(detail.status).toBe('CLOSED_ACTIONED');expect(detail.resultingEnforcement).toHaveLength(3);
  }finally{if(actionIds.user)await revokeTargetEnforcement(admin,'users',RELATED_USER,actionIds.user).catch(()=>undefined);if(actionIds.business)await revokeTargetEnforcement(admin,'businesses',RELATED_BUSINESS,actionIds.business).catch(()=>undefined);if(actionIds.listing)await revokeListingEnforcement(admin,actionIds.listing).catch(()=>undefined);await buyer.context().close();await admin.context().close()}
});

test('workflow 6: close-no-action preserves history and locks the case without enforcement',async({browser})=>{
  test.setTimeout(180_000);
  const buyer=await authenticatedMarketplacePage(browser,CASE_REPORTER.email,CASE_REPORTER.password);
  const admin=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  try{
    const reportId=await submitReport(buyer,'PROHIBITED_ITEM','[E2E] Allegation to close after investigation.');
    await triageReady(admin,reportId);
    const report=await adminDetail(admin,`/api/v1/admin/reports/${reportId}`);
    const created=await adminPost(admin,`/api/v1/admin/reports/${reportId}/investigation-case`,{title:'[E2E] Insufficient listing evidence',severity:null,expectedReportVersion:report.version});
    const caseId=created.caseId as string;
    await admin.goto(`/admin/cases/${caseId}`);await admin.getByRole('button',{name:'Claim case'}).click();
    await admin.getByLabel('Start rationale').fill('Reviewing whether the allegation is substantiated.');await admin.getByRole('button',{name:'Start investigation'}).click();
    const before=await activeListingEnforcement(admin);
    const closeForm=admin.locator('.actions form').filter({has:admin.getByRole('button',{name:'Close with no action'})});
    await closeForm.getByLabel('No-action conclusion').selectOption('INSUFFICIENT_EVIDENCE');await closeForm.getByLabel('Reason',{exact:true}).fill('Available evidence does not establish a policy violation.');await closeForm.getByRole('button',{name:'Close with no action'}).click();
    await expect(admin.getByText('Closed no action',{exact:true}).first()).toBeVisible();await expect(admin.getByText('Case created',{exact:true})).toBeVisible();await expect(admin.getByText('Closed no action',{exact:true}).last()).toBeVisible();
    await expect(admin.getByText('This investigation is concluded and read-only.')).toBeVisible();expect(await activeListingEnforcement(admin)).toBe(before);
  }finally{await buyer.context().close();await admin.context().close()}
});

async function authenticatedMarketplacePage(browser:Browser,email:string,password:string){const context=await browser.newContext();const page=await context.newPage();await page.goto('/marketplace');await page.getByRole('button',{name:'Login'}).click();const dialog=page.getByRole('dialog',{name:'Sign in to keep trading local.'});await dialog.getByLabel('Email').fill(email);await dialog.getByLabel('Password').fill(password);await dialog.locator('button[type="submit"]').click();await expect(page.getByRole('button',{name:'Logout'})).toBeVisible();return page}
async function authenticatedAdminPage(browser:Browser,email:string,password:string){const context=await browser.newContext();const page=await context.newPage();await page.goto('/admin/dashboard');await page.getByRole('button',{name:'Continue to sign in'}).click();await page.locator('#username').fill(email);await page.locator('#password').fill(password);await page.locator('#kc-login').click();await page.waitForURL(/\/admin\/dashboard/);return page}
async function submitReport(page:Page,reasonCode:string,description:string){const result=await page.evaluate(async input=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(r=>r.json());const response=await fetch('/api/v1/reports',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify({targetType:'LISTING',targetId:input.listingId,reasonCode:input.reasonCode,description:input.description})});return {status:response.status,body:await response.json()}},{listingId:LISTING_ID,reasonCode,description});expect(result.status).toBe(201);return result.body.data.reportId as string}
async function triageReady(page:Page,reportId:string){let report=await adminDetail(page,`/api/v1/admin/reports/${reportId}`);report=await adminPost(page,`/api/v1/admin/reports/${reportId}/claim`,{expectedVersion:report.version});await adminPost(page,`/api/v1/admin/reports/${reportId}/ready-for-investigation`,{expectedVersion:report.version,reason:'Requires human investigation before any enforcement decision.'})}
async function adminDetail(page:Page,path:string){return page.evaluate(async value=>(await fetch(value,{credentials:'include'}).then(r=>r.json())).data,path)}
async function adminPost(page:Page,path:string,body:Record<string,unknown>){return page.evaluate(async input=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(r=>r.json());const response=await fetch(input.path,{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify(input.body)});const payload=await response.json();if(!response.ok)throw new Error(`${response.status}: ${payload.error?.message}`);return payload.data},{path,body})}
async function adminPostResponse(page:Page,path:string,body:Record<string,unknown>){return page.evaluate(async input=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(r=>r.json());const response=await fetch(input.path,{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify(input.body)});return {status:response.status,body:await response.json()}},{path,body})}
async function adminPatch(page:Page,path:string,body:Record<string,unknown>){return page.evaluate(async input=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(r=>r.json());const response=await fetch(input.path,{method:'PATCH',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify(input.body)});const payload=await response.json();if(!response.ok)throw new Error(`${response.status}: ${payload.error?.message}`);return payload.data},{path,body})}
async function activeListingEnforcement(page:Page){return page.evaluate(async listingId=>{const response=await fetch(`/api/v1/admin/listings/${listingId}/enforcements`,{credentials:'include'});if(!response.ok)return 0;const body=await response.json();return body.activeEnforcementActions?.length||0},LISTING_ID)}
async function revokeListingEnforcement(page:Page,actionId:string){const detail=await page.evaluate(async input=>{const response=await fetch(`/api/v1/admin/listings/${input.listingId}/enforcements`,{credentials:'include'});const body=await response.json();return body.activeEnforcementActions?.find((item:{enforcementActionId:string})=>item.enforcementActionId===input.actionId)}, {listingId:LISTING_ID,actionId});if(!detail)return;await adminPost(page,`/api/v1/admin/listings/${LISTING_ID}/enforcements/${actionId}/revoke`,{expectedEnforcementVersion:detail.version,reasonCode:'E2E_CLEANUP',reason:'Case-linked enforcement behavior verified; revoke the reversible test action.',idempotencyKey:`e2e-case-enforcement-cleanup-${actionId}`,safeMetadata:{origin:'PLAYWRIGHT'}})}
function proposalBody(targetType:string,targetId:string,actionType:string,scopes:string[],expectedCaseVersion:number,expectedTargetVersion:number){return {targetType,targetId,actionType,scopes,reasonCode:'POLICY_VIOLATION',reason:`Human-reviewed ${targetType.toLowerCase()} action for multi-target case verification.`,effectiveAt:null,expiresAt:null,expectedCaseVersion,expectedTargetVersion}}
async function marketplaceCapabilities(page:Page){return page.evaluate(async()=>{const body=await fetch('/api/v1/users/me/marketplace-capabilities',{credentials:'include'}).then(response=>response.json());return body.data as {buyingAllowed:boolean;sellingAllowed:boolean}})}
async function revokeTargetEnforcement(page:Page,kind:'users'|'businesses',targetId:string,actionId:string){const detail=await adminDetail(page,`/api/v1/admin/${kind}/${targetId}`);const action=detail.activeEnforcementActions?.find((item:any)=>item.enforcementActionId===actionId);if(!action)return;await adminPost(page,`/api/v1/admin/${kind}/${targetId}/enforcements/${actionId}/revoke`,{expectedEnforcementVersion:action.version,reasonCode:'E2E_CLEANUP',reason:'Restore the reversible multi-target fixture.',idempotencyKey:`e2e-case-cleanup-${actionId}`,safeMetadata:{origin:'PLAYWRIGHT'}})}
function bumpListingVersion(){const container=process.env['E2E_MYSQL_CONTAINER']||'msb-demo-mysql';const password=process.env['E2E_MYSQL_PASSWORD']||'demo-change-me-mysql';execFileSync('docker',['exec',container,'mysql','-uroot',`-p${password}`,'catalog','-e',`UPDATE listings SET version=version+1, updated_at=CURRENT_TIMESTAMP(6) WHERE id='${LISTING_ID}'`],{stdio:'pipe'})}
