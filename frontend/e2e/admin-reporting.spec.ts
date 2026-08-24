import { Browser, expect, Page, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';

const LISTING_ID='01E00000000000000000000207';
const LISTING_TITLE='Reporting workflow fixture';
const EDITED_LISTING_TITLE='Reporting workflow fixture with revised details';
const BUYER={email:'trade.buyer@msb.local',password:'TradeBuyer!2026'};
const SECOND_REPORTER={email:'support.admin@msb.local',password:'SupportAdmin!2026'};
const ADMIN_ONE={email:'admin.one@msb.local',password:'AdminOne!2026'};
const ADMIN_TWO={email:'admin.two@msb.local',password:'AdminTwo!2026'};

test('listing allegation moves from marketplace submission through owned dismissal without enforcement',async({browser})=>{
  test.setTimeout(180_000);
  const buyer=await authenticatedMarketplacePage(browser,SECOND_REPORTER.email,SECOND_REPORTER.password);
  const admin=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  let listingWasEdited=false;
  try{
    await buyer.goto(`/listings/${LISTING_ID}`);await expect(buyer.getByRole('heading',{name:LISTING_TITLE})).toBeVisible();
    await buyer.getByRole('button',{name:'Report listing'}).click();
    await buyer.getByLabel('What best describes the concern?').selectOption('MISLEADING_LISTING');
    await buyer.getByLabel('Explanation').fill('[E2E] The condition description does not match the item shown.');
    await buyer.getByRole('button',{name:'Submit report'}).click();
    await expect(buyer.getByText('Your report has been submitted for review.')).toBeVisible();

    updateListingFixtureTitle(EDITED_LISTING_TITLE);
    listingWasEdited=true;

    await admin.goto('/admin/reports');const row=admin.getByRole('row').filter({hasText:LISTING_TITLE});await expect(row).toBeVisible();
    await row.getByRole('button',{name:'Claim'}).click();await expect(admin).toHaveURL(/\/admin\/reports\//);
    await expect(admin.getByRole('heading',{name:LISTING_TITLE})).toBeVisible();
    await expect(admin.getByText('Reported snapshot')).toBeVisible();await expect(admin.getByText('Current state')).toBeVisible();
    await expect(admin.locator('.evidence-grid article').nth(0)).toContainText(LISTING_TITLE);
    await expect(admin.locator('.evidence-grid article').nth(1)).toContainText(EDITED_LISTING_TITLE);
    await admin.getByLabel('Dismissal reason').selectOption('NO_VIOLATION_FOUND');
    await admin.getByLabel('Explanation').fill('No policy violation was established from the submitted context.');
    await admin.getByRole('button',{name:'Dismiss report'}).click();
    await expect(admin.getByText('Dismissed',{exact:true})).toBeVisible();
    await expect(admin.getByText('Report submitted',{exact:true})).toBeVisible();await expect(admin.getByText('Report claimed',{exact:true})).toBeVisible();await expect(admin.getByText('Report dismissed',{exact:true})).toBeVisible();
    expect(await activeListingEnforcement(admin)).toBe(0);
  }finally{if(listingWasEdited)updateListingFixtureTitle(LISTING_TITLE);await buyer.context().close();await admin.context().close()}
});

test('duplicate cooldown and two-admin ownership remain authoritative',async({browser})=>{
  test.setTimeout(180_000);
  const buyer=await authenticatedMarketplacePage(browser,BUYER.email,BUYER.password);
  const adminA=await authenticatedAdminPage(browser,ADMIN_ONE.email,ADMIN_ONE.password);
  const adminB=await authenticatedAdminPage(browser,ADMIN_TWO.email,ADMIN_TWO.password);
  try{
    const reportId=await submitReportApi(buyer,'SPAM','[E2E] Repeated promotional content.');
    const duplicate=await submitReportApiResponse(buyer,'SPAM','[E2E] Repeated promotional content.');expect(duplicate.status).toBe(409);
    await adminA.goto(`/admin/reports/${reportId}`);await adminA.getByRole('button',{name:'Claim'}).click();
    await adminB.goto(`/admin/reports/${reportId}`);await expect(adminB.getByText('This report is assigned to another admin.')).toBeVisible();
    expect(await triageStatus(adminB,reportId)).toBe(409);
    await adminA.getByRole('button',{name:'Release'}).click();await adminB.reload();await adminB.getByRole('button',{name:'Claim'}).click();
    await expect(adminB.getByText('You own this report and may triage it.')).toBeVisible();
    await adminB.getByLabel('Severity').selectOption('HIGH');await adminB.getByRole('textbox',{name:'Reason',exact:true}).fill('Repeated behavior requires higher review priority.');const severityResponse=adminB.waitForResponse(response=>response.url().endsWith(`/api/v1/admin/reports/${reportId}/severity`));await adminB.getByRole('button',{name:'Update severity'}).click();expect((await severityResponse).status()).toBe(200);
    await expect(adminB.locator('.summary')).toContainText('HIGH');
    await adminB.getByLabel('Investigation rationale').fill('Additional human investigation is required before any decision.');await adminB.getByRole('button',{name:'Mark ready for investigation'}).click();
    await expect(adminB.getByText('Ready for investigation',{exact:true})).toBeVisible();
    await expect(adminB.getByText('Report severity changed',{exact:true})).toBeVisible();await expect(adminB.getByText('Report marked ready for investigation',{exact:true})).toBeVisible();
    expect(await activeListingEnforcement(adminB)).toBe(0);
  }finally{await buyer.context().close();await adminA.context().close();await adminB.context().close()}
});

async function authenticatedMarketplacePage(browser:Browser,email:string,password:string){const context=await browser.newContext();const page=await context.newPage();await page.goto('/marketplace');await page.getByRole('button',{name:'Login'}).click();const dialog=page.getByRole('dialog',{name:'Sign in to keep trading local.'});await dialog.getByLabel('Email').fill(email);await dialog.getByLabel('Password').fill(password);await dialog.locator('button[type="submit"]').click();await expect(page.getByRole('button',{name:'Logout'})).toBeVisible();return page}
async function authenticatedAdminPage(browser:Browser,email:string,password:string){const context=await browser.newContext();const page=await context.newPage();await page.goto('/admin/dashboard');await page.getByRole('button',{name:'Continue to sign in'}).click();await page.locator('#username').fill(email);await page.locator('#password').fill(password);await page.locator('#kc-login').click();await page.waitForURL(/\/admin\/dashboard/);return page}
async function submitReportApi(page:Page,reasonCode:string,description:string){const response=await submitReportApiResponse(page,reasonCode,description);expect(response.status).toBe(201);return (response.body as {data:{reportId:string}}).data.reportId}
async function submitReportApiResponse(page:Page,reasonCode:string,description:string){return page.evaluate(async input=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(value=>value.json());const response=await fetch('/api/v1/reports',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify({targetType:'LISTING',targetId:input.listingId,reasonCode:input.reasonCode,description:input.description})});return {status:response.status,body:await response.json()};},{listingId:LISTING_ID,reasonCode,description})}
async function triageStatus(page:Page,reportId:string){return page.evaluate(async id=>{const session=await fetch('/api/v1/auth/session',{credentials:'include'}).then(value=>value.json());const detail=await fetch(`/api/v1/admin/reports/${id}`,{credentials:'include'}).then(value=>value.json());return fetch(`/api/v1/admin/reports/${id}/dismiss`,{method:'POST',credentials:'include',headers:{'Content-Type':'application/json',[session.csrf.headerName]:session.csrf.token},body:JSON.stringify({expectedVersion:detail.data.version,reasonCode:'INVALID_REPORT',reason:'Ownership must be enforced.'})}).then(value=>value.status)},reportId)}
async function activeListingEnforcement(page:Page){return page.evaluate(async listingId=>{const response=await fetch(`/api/v1/admin/listings/${listingId}/enforcements`,{credentials:'include'});if(!response.ok)return 0;const body=await response.json();return body.activeEnforcementActions?.length||0},LISTING_ID)}
function updateListingFixtureTitle(title:string){const container=process.env['E2E_MYSQL_CONTAINER']||'msb-demo-mysql';const password=process.env['E2E_MYSQL_PASSWORD']||'demo-change-me-mysql';const escaped=title.replaceAll("'","''");execFileSync('docker',['exec',container,'mysql','-uroot',`-p${password}`,'catalog','-e',`UPDATE listings SET title = '${escaped}', version = version + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = '${LISTING_ID}'`],{stdio:'pipe'})}
