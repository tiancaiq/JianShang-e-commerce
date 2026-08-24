import { expect, Page, test } from '@playwright/test';

const CATEGORY = '01KCATALOG0000000000000001';
const ADMIN = '01KCATADMIN000000000000001';

test('catalog admin previews required fields and category disable without hidden cascades', async ({ page }) => {
  await catalogAdmin(page);
  let detail = categoryDetail();
  let requiredPreviewBody: any = null;
  let statusPreviewBody: any = null;
  let catalogMutations = 0;
  let unrelatedMutations = 0;

  await page.route('**/api/v1/admin/catalog/categories**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/admin/catalog/categories' && request.method() === 'GET') {
      return route.fulfill({ json: overview(detail.category) });
    }
    if (url.pathname === `/api/v1/admin/catalog/categories/${CATEGORY}` && request.method() === 'GET') {
      return route.fulfill({ json: detail });
    }
    if (url.pathname.endsWith('/attributes/create/dry-run')) {
      requiredPreviewBody = JSON.parse(request.postData() || '{}');
      return route.fulfill({ json: impact('ATTRIBUTE_CREATE', 3) });
    }
    if (url.pathname.endsWith('/attributes') && request.method() === 'POST') {
      catalogMutations++;
      detail = {
        ...detail,
        category: { ...detail.category, ruleVersion: 2, version: 1 },
        attributes: [{
          id: '01KATTRIBUTE000000000000001', key: 'model_name', label: 'Model name', description: null,
          dataType: 'TEXT', required: true, searchable: false, filterable: false,
          allowedValuesJson: null, validationJson: '{"minLength":0,"maxLength":1000}',
          displayOrder: 0, status: 'ACTIVE', version: 0, options: [],
        }],
      };
      return route.fulfill({ json: detail });
    }
    if (url.pathname.endsWith('/status/dry-run')) {
      statusPreviewBody = JSON.parse(request.postData() || '{}');
      return route.fulfill({ json: impact('CATEGORY_STATUS', 0) });
    }
    if (url.pathname.endsWith('/status') && request.method() === 'POST') {
      catalogMutations++;
      detail = { ...detail, category: { ...detail.category, status: 'DISABLED', version: 2, ruleVersion: 3 } };
      return route.fulfill({ json: detail });
    }
    return route.abort();
  });

  await page.goto('/admin/catalog');
  await expect(page.getByRole('heading', { name: 'Catalog control room' })).toBeVisible();
  await page.getByRole('link', { name: 'Open →' }).click();
  await expect(page.getByText('Active listings stay active')).toBeVisible();

  await page.getByText('Add governed attribute').click();
  await page.getByLabel('Stable key').fill('model_name');
  await page.getByLabel('Label').fill('Model name');
  await page.getByLabel('Required at submission').check();
  await page.getByRole('button', { name: 'Publish new attribute rule' }).click();
  await expect(page.getByRole('heading', { name: 'Review Attribute Create' })).toBeVisible();
  await expect(page.getByText('3', { exact: true }).last()).toBeVisible();
  expect(requiredPreviewBody.required).toBe(true);
  await page.getByRole('button', { name: 'Confirm reviewed change' }).click();
  await expect(page.getByRole('heading', { name: 'Model name' })).toBeVisible();

  await page.getByLabel('Target status').selectOption('DISABLED');
  await page.getByRole('button', { name: 'Dry-run status change' }).click();
  await expect(page.getByText('Existing active listings are not removed.')).toBeVisible();
  expect(statusPreviewBody.status).toBe('DISABLED');
  await page.getByRole('button', { name: 'Confirm reviewed change' }).click();
  await expect(page.locator('.identity .status')).toHaveText('Disabled');

  expect(catalogMutations).toBe(2);
  expect(unrelatedMutations).toBe(0);
});

test('high-impact category disable creates approval and preserves category state',async({page})=>{
 await catalogAdmin(page);
 const detail=categoryDetail();
 let mutationRequests=0;
 await page.route('**/api/v1/admin/catalog/categories**',async route=>{
  const request=route.request();const url=new URL(request.url());
  if(url.pathname===`/api/v1/admin/catalog/categories/${CATEGORY}`&&request.method()==='GET')return route.fulfill({json:detail});
  if(url.pathname.endsWith('/status/dry-run'))return route.fulfill({json:{...impact('CATEGORY_STATUS',0),counts:{...impact('CATEGORY_STATUS',0).counts,activeListings:1500}}});
  if(url.pathname.endsWith('/status')&&request.method()==='POST'){mutationRequests++;return route.fulfill({status:202,json:{outcome:'PENDING_APPROVAL',approval:{approvalId:'01KCATAPPROVAL0000000000001',actionType:'HIGH_IMPACT_CATALOG_CATEGORY_DISABLE',riskLevel:'HIGH',targetType:'CATALOG_CATEGORY',targetId:CATEGORY,requiredApprovals:1,currentApprovals:0,status:'PENDING',createdAt:'2026-08-23T00:00:00Z',expiresAt:'2026-08-23T04:00:00Z',version:0},approvalRequired:true,message:'Independent approval is required before disabling this category.'}});}
  return route.abort();
 });
 await page.goto(`/admin/catalog/categories/${CATEGORY}`);
 await page.getByLabel('Target status').selectOption('DISABLED');
 await page.getByRole('button',{name:'Dry-run status change'}).click();
 await expect(page.getByText('1500',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Confirm reviewed change'}).click();
 await expect.poll(()=>mutationRequests).toBe(1);
 await expect(page.getByRole('link',{name:/Open approval/})).toBeVisible();
 await expect(page.locator('.identity .status')).toHaveText('Active');
});

async function catalogAdmin(page: Page) {
  await page.route('**/api/v1/auth/session', route => route.fulfill({ json: {
    authenticated: true,
    user: { subject: ADMIN, email: 'catalog.admin@msb.local', displayName: 'Catalog Admin', roles: ['CATALOG_ADMIN'] },
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' },
  } }));
  await page.route('**/api/v1/users/me', route => route.fulfill({ status: 503, json: {
    error: { message: 'Use session fallback.' },
  } }));
  await page.route('**/api/v1/admin/me', route => route.fulfill({ json: { data: {
    userId: ADMIN, role: 'CATALOG_ADMIN', roles: ['CATALOG_ADMIN'],
    permissions: ['admin.catalog.read', 'admin.catalog.category.manage', 'admin.catalog.attribute.manage', 'admin.catalog.policy.manage'],
    accountState: 'ACTIVE',
  } } }));
}

function overview(category: any) {
  return { categories: [category], activeCategories: category.status === 'ACTIVE' ? 1 : 0,
    disabledCategories: category.status === 'DISABLED' ? 1 : 0, deprecatedCategories: 0,
    activeAttributes: 0, activeGuidance: 0 };
}

function categoryDetail() {
  return {
    category: {
      id: CATEGORY, parentId: null, name: 'Electronics', slug: 'electronics', description: 'Devices and accessories',
      status: 'ACTIVE', displayOrder: 1, sellerEligibility: 'BOTH', listingCreationAllowed: true,
      listingSubmissionAllowed: true, replacementCategoryId: null, ruleVersion: 1, version: 0,
      counts: { activeListings: 5, draftListings: 3, pendingListings: 2, historicalListings: 4, childCategories: 0 },
    },
    breadcrumb: [{ id: CATEGORY, name: 'Electronics', slug: 'electronics' }], children: [], attributes: [], sellerGuidance: [],
    ruleVersions: [{ id: 'rule-1', versionNumber: 1, reason: 'Bootstrap catalog policy', createdByAdminId: ADMIN, createdAt: '2026-08-20T00:00:00Z' }],
    auditTimeline: [],
    availableAdminCapabilities: {
      canRead: true, canManageCategory: true, canManageAttribute: true, canManagePolicy: true,
      canManageGuidance: true, canDisableCategory: true, canPublishRules: true, readOnly: false, readOnlyReason: null,
    },
  };
}

function impact(changeType: string, missing: number) {
  return {
    categoryId: CATEGORY, changeType, currentValue: 'ACTIVE', proposedValue: 'DISABLED',
    counts: { activeListings: 5, draftListings: 3, pendingListings: 2, historicalListings: 4, childCategories: 0 },
    listingsMissingRequiredAttribute: missing, exact: true,
    effects: ['New listing eligibility or validation will use the proposed configuration after confirmation.'],
    guaranteedNonEffects: ['Existing active listings are not removed.', 'Pending moderation is not rewritten.', 'Orders are not changed.', 'No seller or enforcement action is created.'],
    categoryVersion: 0, ruleVersion: 1,
  };
}
