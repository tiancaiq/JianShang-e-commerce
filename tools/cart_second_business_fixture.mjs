import { createHash } from 'node:crypto';

const AUTH_URL = 'http://localhost:8085';
const PRODUCT_URL = 'http://localhost:8091';
const INVENTORY_URL = 'http://localhost:8082';
const KEYCLOAK_URL = 'http://localhost:8181';
const BUSINESS_ID = '01KZCARTB00000000000000002';
const STORE_ID = '01KZCARTB00000000000000003';
const SKU = 'MSB-CART-B-FIXTURE';
const TITLE = 'Harbor Cart Fixture Tote';
const FIXTURE_STOCK = 12;
const FIRST_BUSINESS_ID = '01KXQBUSI00000000000000001';
const FIRST_LISTING_ID = '01KXQMEH9KBPH5S7DPBFM0VBJ5';
const FIRST_FIXTURE_STOCK = 8;
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=',
  'base64',
);

const fixtureToken = process.env.COMMERCE_INTERNAL_SERVICE_TOKEN;
const sellerEmail = 'harbor.seller@msb.local';
const sellerPassword = process.env.LOCAL_DEMO_HARBOR_PASSWORD;
const firstSellerEmail = 'shen.ban2@mycnmipss.org';
const firstSellerPassword = process.env.LOCAL_DEMO_SHEN_PASSWORD;

async function main() {
  if (!fixtureToken || !sellerPassword || !firstSellerPassword) {
    throw new Error('Set COMMERCE_INTERNAL_SERVICE_TOKEN, LOCAL_DEMO_HARBOR_PASSWORD, and LOCAL_DEMO_SHEN_PASSWORD before restoring commerce fixtures.');
  }
  const authFixture = await json(`${AUTH_URL}/api/v1/internal/demo-fixtures/cart/second-business`, {
    method: 'POST',
    headers: { 'X-Internal-Service-Token': fixtureToken },
  });
  const accessToken = await sellerAccessToken(sellerEmail, sellerPassword);
  const authorization = { Authorization: `Bearer ${accessToken}` };
  const firstAccessToken = await sellerAccessToken(firstSellerEmail, firstSellerPassword);
  const firstAuthorization = { Authorization: `Bearer ${firstAccessToken}` };

  let listing = await findFixtureListing(authorization);
  if (!listing) {
    listing = await json(`${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items`, {
      method: 'POST',
      headers: jsonHeaders(authorization),
      body: JSON.stringify(listingRequest()),
    }, [201]);
  }
  if (listing.businessId !== BUSINESS_ID || listing.storeId !== STORE_ID || listing.sellerType !== 'BUSINESS') {
    throw new Error('The fixture SKU is bound to an unexpected business or store.');
  }
  if (listing.status === 'REMOVED') {
    throw new Error('The deterministic fixture listing was removed and must be restored by an approved fixture migration.');
  }

  if (listing.status === 'ACTIVE' && !matchesListingContract(listing)) {
    listing = await lifecycle(listing, 'pause', authorization);
  }
  if (listing.status !== 'ACTIVE' && !matchesListingContract(listing)) {
    listing = await json(
      `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}`,
      {
        method: 'PATCH',
        headers: jsonHeaders({ ...authorization, 'If-Match': String(listing.version) }),
        body: JSON.stringify(listingRequest()),
      },
    );
  }

  listing = await ensureConfirmedImage(listing, authorization);
  if (listing.status === 'DRAFT') {
    listing = await lifecycle(listing, 'publish', authorization);
  } else if (listing.status === 'PAUSED') {
    listing = await lifecycle(listing, 'relist', authorization);
  }
  if (listing.status !== 'ACTIVE') {
    throw new Error(`Fixture listing did not become active; status=${listing.status}.`);
  }

  const inventory = await ensureInventory(BUSINESS_ID, listing.id, FIXTURE_STOCK, authorization, 'business-b');
  const firstInventory = await ensureInventory(
    FIRST_BUSINESS_ID, FIRST_LISTING_ID, FIRST_FIXTURE_STOCK, firstAuthorization, 'business-a',
  );
  const crossBusinessStatus = await statusOnly(
    `${PRODUCT_URL}/api/v1/businesses/${FIRST_BUSINESS_ID}/store/items/${listing.id}`,
    { headers: authorization },
  );
  const reverseBusinessStatus = await statusOnly(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${FIRST_LISTING_ID}`,
    { headers: authorization },
  );
  if (![403, 404].includes(crossBusinessStatus) || ![403, 404].includes(reverseBusinessStatus)) {
    throw new Error('Cross-business listing management was not hidden from the second fixture owner.');
  }

  const publicListing = await json(`${PRODUCT_URL}/api/v1/public/listings/${listing.id}`);
  console.log(JSON.stringify({
    fixture: {
      businessId: authFixture.businessId,
      storeId: authFixture.storeId,
      storeName: authFixture.storeName,
      storeSlug: authFixture.storeSlug,
      verified: authFixture.verified,
      publicCity: authFixture.publicCity,
      publicRegion: authFixture.publicRegion,
      ownerUserId: authFixture.ownerUserId,
      listingId: listing.id,
      sku: listing.sku,
      listingStatus: listing.status,
      imageCount: listing.images.filter(image => image.uploadStatus === 'UPLOADED').length,
      price: listing.priceAmount,
      currency: listing.currency,
      inventoryAvailable: inventory.available,
      firstBusinessListingId: FIRST_LISTING_ID,
      firstBusinessInventoryAvailable: firstInventory.available,
      reusableFixture: true,
      evidenceOrdersModified: false,
    },
    isolation: {
      businessAId: FIRST_BUSINESS_ID,
      businessBId: BUSINESS_ID,
      storeBId: STORE_ID,
      ownerBCannotManageBusinessA: reverseBusinessStatus,
      ownerBCannotUseBusinessAToManageB: crossBusinessStatus,
      publicStoreSlug: publicListing.storeSlug,
    },
  }, null, 2));
}

async function sellerAccessToken(username, password) {
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: 'msb-marketplace',
    client_secret: 'local-dev-only-change-me-marketplace',
    username,
    password,
  });
  const token = await json(`${KEYCLOAK_URL}/realms/msb-local/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!token.access_token) {
    throw new Error('Keycloak did not return a seller access token.');
  }
  return token.access_token;
}

async function findFixtureListing(authorization) {
  const page = await json(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/search?q=${encodeURIComponent(TITLE)}&limit=50`,
    { headers: authorization },
  );
  return page.data.find(item => item.sku === SKU) || null;
}

function listingRequest() {
  return {
    sellerType: 'BUSINESS',
    businessId: BUSINESS_ID,
    categoryId: '01K00000000000000000000001',
    title: TITLE,
    description: 'Deterministic local-demo tote for multi-business cart acceptance.',
    condition: 'NEW',
    conditionNotes: 'New local-demo fixture item.',
    price: { amount: 7.5, currency: 'USD' },
    negotiable: false,
    location: { city: 'Costa Mesa', region: 'CA' },
    sku: SKU,
    quantity: FIXTURE_STOCK,
  };
}

function matchesListingContract(listing) {
  return listing.title === TITLE
    && listing.sku === SKU
    && Number(listing.priceAmount) === 7.5
    && listing.currency === 'USD'
    && listing.quantity === FIXTURE_STOCK
    && listing.publicCity === 'Costa Mesa'
    && listing.publicRegion === 'CA';
}

async function lifecycle(listing, action, authorization) {
  return json(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/${action}`,
    {
      method: 'POST',
      headers: { ...authorization, 'If-Match': String(listing.version) },
    },
  );
}

async function ensureConfirmedImage(listing, authorization) {
  if (listing.images.some(image => image.uploadStatus === 'UPLOADED')) {
    return listing;
  }
  const checksum = createHash('sha256').update(PNG).digest('hex');
  const media = await json(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/media/upload-request`,
    {
      method: 'POST',
      headers: jsonHeaders(authorization),
      body: JSON.stringify({
        contentType: 'image/png',
        fileName: 'harbor-cart-fixture.png',
        sizeBytes: PNG.length,
        checksumSha256: checksum,
      }),
    },
    [201],
  );
  const uploadPath = new URL(media.uploadUrl, PRODUCT_URL).pathname;
  await empty(`${PRODUCT_URL}${uploadPath}`, {
    method: 'PUT',
    headers: { ...authorization, 'Content-Type': 'image/png' },
    body: PNG,
  }, [204]);
  await json(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/media/${media.id}/confirm`,
    {
      method: 'POST',
      headers: jsonHeaders(authorization),
      body: JSON.stringify({ sizeBytes: PNG.length, checksumSha256: checksum }),
    },
  );
  await json(
    `${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/images`,
    {
      method: 'PUT',
      headers: jsonHeaders(authorization),
      body: JSON.stringify({ images: [{ mediaId: media.id, altText: 'Harbor cart fixture tote' }] }),
    },
  );
  return json(`${PRODUCT_URL}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}`, {
    headers: authorization,
  });
}

async function ensureInventory(businessId, listingId, expectedStock, authorization, fixtureName) {
  const url = `${INVENTORY_URL}/api/v1/businesses/${businessId}/inventory/${listingId}`;
  const response = await fetch(url, { headers: authorization });
  if (response.status === 404) {
    const created = await json(`${url}/initialize`, {
      method: 'POST',
      headers: jsonHeaders({ ...authorization, 'Idempotency-Key': `commerce-fixture-${fixtureName}-inventory-v1` }),
      body: JSON.stringify({ onHand: expectedStock, note: 'Deterministic commerce RC fixture.' }),
    }, [201]);
    return created.data;
  }
  const current = await checkedJson(response);
  if (current.data.reserved !== 0) {
    throw new Error(`Fixture ${listingId} has ${current.data.reserved} reserved units; finish or expire active checkouts before resetting the reusable baseline.`);
  }
  if (current.data.onHand === expectedStock) {
    return current.data;
  }
  const adjusted = await json(`${url}/adjustments`, {
    method: 'POST',
    headers: jsonHeaders({
      ...authorization,
      'If-Match': String(current.data.version),
      'Idempotency-Key': `commerce-fixture-${fixtureName}-stock-v${current.data.version}-to-${expectedStock}`,
    }),
    body: JSON.stringify({
      operation: 'SET',
      quantity: expectedStock,
      reason: 'STOCK_COUNT_CORRECTION',
      note: 'Restore deterministic commerce RC stock.',
    }),
  });
  return adjusted.data;
}

function jsonHeaders(headers = {}) {
  return { ...headers, 'Content-Type': 'application/json' };
}

async function statusOnly(url, options) {
  const response = await fetch(url, options);
  await response.body?.cancel();
  return response.status;
}

async function json(url, options = {}, expected = [200]) {
  const response = await fetch(url, options);
  if (!expected.includes(response.status)) {
    const body = await response.text();
    throw new Error(`${options.method || 'GET'} ${new URL(url).pathname} failed (${response.status}): ${body.slice(0, 300)}`);
  }
  return checkedJson(response);
}

async function empty(url, options = {}, expected = [200]) {
  const response = await fetch(url, options);
  if (!expected.includes(response.status)) {
    const body = await response.text();
    throw new Error(`${options.method || 'GET'} ${new URL(url).pathname} failed (${response.status}): ${body.slice(0, 300)}`);
  }
}

async function checkedJson(response) {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Request failed (${response.status}): ${text.slice(0, 300)}`);
  }
  return JSON.parse(text);
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
