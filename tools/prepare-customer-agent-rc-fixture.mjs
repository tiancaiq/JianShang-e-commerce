import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

// RC-FIXTURE-01 uses only existing customer/seller application contracts.
// It deliberately stops before creating a return request or Agent confirmation.
const AUTH = 'http://127.0.0.1:8085';
const PRODUCT = 'http://127.0.0.1:8091';
const INVENTORY = 'http://127.0.0.1:8082';
const ORDER = 'http://127.0.0.1:8081';
const KEYCLOAK = 'http://127.0.0.1:8181';
const BUSINESS_ID = '01KZCARTB00000000000000002';
const STORE_ID = '01KZCARTB00000000000000003';
const SKU = 'MSB-CUSTOMER-AGENT-RC-RETURN';
const TITLE = 'RC Delivered Return Fixture';
const STOCK = 6;
const PRICE = 12.5;
const FRESH = process.argv.includes('--fresh');
const LISTING_ONLY = process.argv.includes('--listing-only');
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=',
  'base64',
);
const required = [
  'LOCAL_DEMO_BUYER_PASSWORD', 'LOCAL_DEMO_BUYER_B_PASSWORD',
  'LOCAL_DEMO_HARBOR_PASSWORD', 'KEYCLOAK_MARKETPLACE_CLIENT_SECRET',
  'COMMERCE_INTERNAL_SERVICE_TOKEN',
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing disposable runtime variable ${name}.`);
}

const actors = {
  buyerA: await token('rc.customer.a@msb.local', process.env.LOCAL_DEMO_BUYER_PASSWORD),
  buyerB: await token('rc.customer.b@msb.local', process.env.LOCAL_DEMO_BUYER_B_PASSWORD),
  seller: await token('harbor.seller@msb.local', process.env.LOCAL_DEMO_HARBOR_PASSWORD),
};
const subjects = Object.fromEntries(Object.entries(actors).map(([name, accessToken]) => [name, subject(accessToken)]));
assert(new Set(Object.values(subjects)).size === 3, 'Fixture actors must have distinct subjects.');
assert(roles(actors.buyerA).includes('BUYER'), 'Customer A is not a normal buyer.');
assert(roles(actors.buyerB).includes('BUYER'), 'Customer B is not a normal buyer.');
assert(!roles(actors.buyerB).some(role => role === 'ADMIN' || role === 'BUSINESS_USER'),
  'Customer B has privileged business/Admin authority.');
assert(roles(actors.seller).includes('BUSINESS_USER'), 'Harbor fixture seller lacks business authority.');

await api(`${AUTH}/api/v1/users/me`, { token: actors.buyerA });
await api(`${AUTH}/api/v1/users/me`, { token: actors.buyerB });
// The existing Auth-owned local fixture endpoint binds Harbor membership to
// the actual Keycloak subject; it is test-only and service-token protected.
await api(`${AUTH}/api/v1/internal/demo-fixtures/cart/second-business`, {
  method: 'POST', headers: {
    'X-Internal-Service-Token': process.env.COMMERCE_INTERNAL_SERVICE_TOKEN,
    'X-Local-Demo-Owner-Subject': subjects.seller,
  },
});
const store = await api(`${AUTH}/api/v1/businesses/${BUSINESS_ID}/store`, { token: actors.seller });
assert((store.data ?? store).id === STORE_ID || (store.data ?? store).storeId === STORE_ID,
  'Harbor seller is not bound to the expected fixture store.');

const listing = await ensureListing();
const stock = await ensureInventory(listing.id);
assert(stock.available >= 1 && stock.reserved === 0, 'Dedicated listing lacks available Inventory stock.');
const publicListing = await api(`${PRODUCT}/api/v1/public/listings/${listing.id}`);
assert(publicListing.id === listing.id && publicListing.sellerType === 'BUSINESS'
  && publicListing.businessVerified === true,
  'Dedicated listing is not publicly purchasable.');
if (LISTING_ONLY) {
  console.log(JSON.stringify({ milestone: 'RC-FIXTURE-01-LISTING',
    listingId: listing.id, sellerType: publicListing.sellerType,
    businessVerified: publicListing.businessVerified,
    availableInventory: stock.available, orderCreated: false }));
  process.exit(0);
}

let fixture = FRESH ? null : await reusableDeliveredOrder(listing.id);
const reused = Boolean(fixture);
if (!fixture) fixture = await createDeliveredOrder(listing.id);
const { order, group, returnView } = fixture;

assert(order.status === 'CONFIRMED' && order.paymentStatus === 'SUCCEEDED',
  'The fixture order is not confirmed and paid.');
assert(group.status === 'DELIVERED' && group.shipment?.status === 'DELIVERED',
  'The fixture business group and shipment are not delivered.');
assert(group.items.length === 1 && group.items[0].listingId === listing.id && group.items[0].quantity === 1,
  'The fixture order item snapshot does not match the dedicated listing.');
assert(returnView.eligible === true && returnView.returnId === null && returnView.status === null,
  'The delivered group has no clean, unused return eligibility.');
const accounting = readOnlyAccounting(order.orderId, group.businessOrderId);
assert(accounting.orderCount === 1 && accounting.intentCount === 1
  && accounting.successfulAttemptCount === 1 && accounting.businessGroupCount === 1
  && accounting.returnCount === 0 && accounting.provider === 'FAKE_LOCAL_DEMO_V1'
  && accounting.intentStatus === 'SUCCEEDED',
  'Read-only accounting evidence does not show exactly one mock-paid order and zero returns.');

const buyerBOrderStatus = await status(`${ORDER}/api/v1/orders/${order.orderId}`, actors.buyerB);
const buyerBReturnStatus = await status(
  `${ORDER}/api/v1/orders/${order.orderId}/groups/${group.businessOrderId}/return`, actors.buyerB,
);
assert([403, 404].includes(buyerBOrderStatus) && [403, 404].includes(buyerBReturnStatus),
  `Customer B order/return isolation precheck returned ${buyerBOrderStatus}/${buyerBReturnStatus}.`);
const buyerBCart = await api(`${ORDER}/api/v1/cart`, { token: actors.buyerB });
assert(!buyerBCart.items.some(item => item.listingId === listing.id),
  'Customer B cart contains Customer A fixture item.');
const buyerACart = await api(`${ORDER}/api/v1/cart`, { token: actors.buyerA });

console.log(JSON.stringify({
  milestone: 'RC-FIXTURE-01',
  reusedEligibleOrder: reused,
  customerA: { authenticated: true, actorDistinctFromB: true, cartItems: buyerACart.items.length },
  customerB: { authenticated: true, cartItems: buyerBCart.items.length,
    customerAOrderReadStatus: buyerBOrderStatus, customerAReturnReadStatus: buyerBReturnStatus },
  business: { businessId: BUSINESS_ID, storeId: STORE_ID },
  listing: { listingId: listing.id, title: TITLE, status: listing.status, sellerType: listing.sellerType },
  inventory: { onHand: stock.onHand, reserved: stock.reserved, available: stock.available },
  checkout: { checkoutId: accounting.checkoutId, paymentIntentId: accounting.paymentIntentId,
    provider: accounting.provider, mockPaymentSucceeded: true,
    orderCount: accounting.orderCount, successfulPaymentAttemptCount: accounting.successfulAttemptCount },
  order: { orderId: order.orderId, status: order.status, paymentStatus: order.paymentStatus,
    businessOrderId: group.businessOrderId, fulfillmentStatus: group.status,
    shipmentStatus: group.shipment.status, itemQuantity: group.items[0].quantity },
  return: { eligible: returnView.eligible, returnId: returnView.returnId,
    status: returnView.status, windowExpiresAt: returnView.windowExpiresAt },
  returnSubmittedBySetup: false,
}, null, 2));

async function ensureListing() {
  const page = await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/search?q=${encodeURIComponent(TITLE)}&limit=50`,
    { token: actors.seller });
  let listing = page.data.find(item => item.sku === SKU);
  if (!listing) {
    listing = await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items`, {
      method: 'POST', token: actors.seller, expected: [201], body: {
        sellerType: 'BUSINESS', businessId: BUSINESS_ID, categoryId: '01K00000000000000000000001',
        title: TITLE, description: 'Disposable customer-agent delivered return acceptance fixture.',
        condition: 'NEW', conditionNotes: 'New local-demo fixture item.',
        price: { amount: PRICE, currency: 'USD' }, negotiable: false,
        location: { city: 'Costa Mesa', region: 'CA' }, sku: SKU, quantity: STOCK,
      },
    });
  }
  assert(listing.businessId === BUSINESS_ID && listing.storeId === STORE_ID && listing.sellerType === 'BUSINESS',
    'Dedicated SKU is bound to an unexpected owner.');
  assert(listing.title === TITLE && Number(listing.priceAmount) === PRICE && listing.currency === 'USD',
    'Dedicated SKU has an unexpected listing contract.');
  assert(listing.status !== 'REMOVED', 'Dedicated fixture listing was removed; manual review is required.');
  if (!listing.images?.some(image => image.uploadStatus === 'UPLOADED')) {
    const checksumSha256 = createHash('sha256').update(PNG).digest('hex');
    const media = await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/media/upload-request`, {
      method: 'POST', token: actors.seller, expected: [201],
      body: { contentType: 'image/png', fileName: 'rc-delivered-return-fixture.png',
        sizeBytes: PNG.length, checksumSha256 },
    });
    await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/media/${media.id}/confirm`, {
      method: 'POST', token: actors.seller, body: { sizeBytes: PNG.length, checksumSha256 },
    });
    await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/images`, {
      method: 'PUT', token: actors.seller,
      body: { images: [{ mediaId: media.id, altText: TITLE }] },
    });
    listing = await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}`,
      { token: actors.seller });
  }
  if (listing.status === 'DRAFT' || listing.status === 'PAUSED') {
    listing = await api(`${PRODUCT}/api/v1/businesses/${BUSINESS_ID}/store/items/${listing.id}/${listing.status === 'DRAFT' ? 'publish' : 'relist'}`,
      { method: 'POST', token: actors.seller, headers: { 'If-Match': String(listing.version) } });
  }
  assert(listing.status === 'ACTIVE', 'Dedicated listing did not become active.');
  return listing;
}

async function ensureInventory(listingId) {
  const url = `${INVENTORY}/api/v1/businesses/${BUSINESS_ID}/inventory/${listingId}`;
  const response = await fetch(url, { headers: { Authorization: `Bearer ${actors.seller}` } });
  let stock;
  if (response.status === 404) {
    await response.body?.cancel();
    const created = await api(`${url}/initialize`, {
      method: 'POST', token: actors.seller, expected: [201],
      headers: { 'Idempotency-Key': `customer-agent-rc-inventory-${listingId}` },
      body: { onHand: STOCK, note: 'Dedicated delivered-return acceptance fixture.' },
    });
    stock = created.data;
  } else {
    stock = (await checked(response, url)).data;
    assert(stock.reserved === 0, 'Fixture Inventory has active reservations; refusing to overwrite stock.');
    if (stock.available < 2) {
      const adjusted = await api(`${url}/adjustments`, {
        method: 'POST', token: actors.seller,
        headers: versioned(stock.version, `customer-agent-rc-restock-${listingId}-v${stock.version}`),
        body: { operation: 'SET', quantity: STOCK, reason: 'STOCK_COUNT_CORRECTION',
          note: 'Replenish dedicated delivered-return acceptance fixture.' },
      });
      stock = adjusted.data;
    }
  }
  return stock;
}

async function reusableDeliveredOrder(listingId) {
  let cursor;
  for (let pageNumber = 0; pageNumber < 10; pageNumber += 1) {
    const url = new URL(`${ORDER}/api/v1/orders`);
    url.searchParams.set('limit', '50');
    if (cursor) url.searchParams.set('cursor', cursor);
    const page = await api(url.toString(), { token: actors.buyerA });
    for (const summary of page.items) {
      if (!summary.groups.some(group => group.businessId === BUSINESS_ID)) continue;
      const order = await api(`${ORDER}/api/v1/orders/${summary.orderId}`, { token: actors.buyerA });
      const group = order.groups.find(candidate => candidate.businessId === BUSINESS_ID
        && candidate.items.some(item => item.listingId === listingId));
      if (!group) continue;
      assert(group.status === 'DELIVERED',
        'The latest dedicated fixture order is unfinished; inspect or recover it before creating another.');
      const returnView = await api(`${ORDER}/api/v1/orders/${order.orderId}/groups/${group.businessOrderId}/return`,
        { token: actors.buyerA });
      if (returnView.eligible && !returnView.returnId) {
        return { order, group, returnView, checkoutId: null, paymentIntentId: null };
      }
      // A consumed or expired latest generation requires a new purchase. Do
      // not silently fall back to an older fixture that could confuse "latest".
      return null;
    }
    if (!page.page.hasMore) break;
    cursor = page.page.nextCursor;
  }
  return null;
}

async function createDeliveredOrder(listingId) {
  const run = `customer-agent-rc-${Date.now().toString(36)}`;
  let cart = await api(`${ORDER}/api/v1/cart`, { token: actors.buyerA });
  if (cart.items.length) {
    cart = await api(`${ORDER}/api/v1/cart`, {
      method: 'DELETE', token: actors.buyerA, headers: versioned(cart.version, `${run}-clear`),
    });
  }
  cart = await api(`${ORDER}/api/v1/cart/items`, {
    method: 'POST', token: actors.buyerA, headers: versioned(cart.version, `${run}-add`),
    body: { listingId, quantity: 1 },
  });
  assert(cart.items.length === 1 && cart.items[0].listingId === listingId,
    'Customer A cart does not contain exactly the dedicated fixture item.');
  const validation = await api(`${ORDER}/api/v1/cart/validate`, {
    method: 'POST', token: actors.buyerA, body: null,
  });
  assert(validation.checkoutReady, 'Dedicated cart is not checkout-ready.');
  const addressId = await buyerAddressId();
  const checkout = await api(`${ORDER}/api/v1/checkouts`, {
    method: 'POST', token: actors.buyerA, expected: [201],
    headers: { 'Idempotency-Key': `${run}-checkout` },
    body: { cartVersion: cart.version, addressId },
  });
  const intent = await api(`${ORDER}/api/v1/checkouts/${checkout.id}/payment-intent`, {
    method: 'POST', token: actors.buyerA, headers: { 'Idempotency-Key': `${run}-payment` }, body: null,
  });
  await api(`${ORDER}/api/v1/checkouts/${checkout.id}/complete-demo-payment`, {
    method: 'POST', token: actors.buyerA, body: null,
  });
  const resolution = await poll(async () => api(`${ORDER}/api/v1/checkouts/${checkout.id}/confirmed-order`,
    { token: actors.buyerA, expected: [200, 202] }), value => value.confirmed && value.orderId,
  'confirmed order');
  const orderBeforeFulfillment = await api(`${ORDER}/api/v1/orders/${resolution.orderId}`,
    { token: actors.buyerA });
  assert(orderBeforeFulfillment.groups.length === 1,
    'Dedicated checkout created more than one business group.');
  const groupId = orderBeforeFulfillment.groups[0].businessOrderId;
  await fulfill(groupId, run);
  const order = await api(`${ORDER}/api/v1/orders/${resolution.orderId}`, { token: actors.buyerA });
  const group = order.groups.find(candidate => candidate.businessOrderId === groupId);
  const returnView = await api(`${ORDER}/api/v1/orders/${order.orderId}/groups/${groupId}/return`,
    { token: actors.buyerA });
  return { order, group, returnView, checkoutId: checkout.id, paymentIntentId: intent.id };
}

async function fulfill(groupId, run) {
  const url = `${ORDER}/api/v1/businesses/${BUSINESS_ID}/orders/${groupId}`;
  let detail = await api(url, { token: actors.seller });
  assert(detail.status === 'PENDING_ACCEPTANCE', 'New fixture group is not pending seller acceptance.');
  await api(`${url}/accept`, { method: 'POST', token: actors.seller,
    headers: versioned(detail.version, `${run}-accept`), body: null });
  detail = await api(url, { token: actors.seller });
  await api(`${url}/processing`, { method: 'POST', token: actors.seller,
    headers: versioned(detail.version, `${run}-processing`), body: null });
  detail = await api(url, { token: actors.seller });
  await api(`${url}/shipments`, { method: 'POST', token: actors.seller,
    headers: versioned(detail.version, `${run}-shipment`),
    body: { carrierDisplayName: 'Demo Carrier', serviceDisplayName: 'Ground',
      trackingNumber: `RC-${groupId}`, shippedAt: new Date().toISOString() } });
  detail = await api(url, { token: actors.seller });
  assert(detail.status === 'SHIPPED', 'The fixture shipment did not advance the group to SHIPPED.');
  await api(`${url}/delivery-demo`, { method: 'POST', token: actors.seller,
    headers: versioned(detail.version, `${run}-delivery`), body: null });
}

async function buyerAddressId() {
  const response = await api(`${AUTH}/api/v1/users/me/addresses`, { token: actors.buyerA });
  const addresses = response.data ?? response;
  if (addresses.length) return addresses.find(address => address.isDefault)?.id ?? addresses[0].id;
  const created = await api(`${AUTH}/api/v1/users/me/addresses`, {
    method: 'POST', token: actors.buyerA, expected: [201],
    body: { label: 'Customer Agent RC', recipientName: 'RC Buyer', phone: '+19495550123',
      line1: '100 Demo Avenue', line2: null, city: 'Irvine', region: 'CA',
      postalCode: '92612', countryCode: 'US' },
  });
  return (created.data ?? created).id;
}

async function token(username, password) {
  const body = new URLSearchParams({ grant_type: 'password', client_id: 'msb-marketplace',
    client_secret: process.env.KEYCLOAK_MARKETPLACE_CLIENT_SECRET, username, password });
  const response = await fetch(`${KEYCLOAK}/realms/msb-local/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
  });
  assert(response.ok, `Disposable identity ${username} could not authenticate (${response.status}).`);
  return (await response.json()).access_token;
}

function claims(accessToken) {
  return JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString('utf8'));
}
function subject(accessToken) { return claims(accessToken).sub; }
function roles(accessToken) { return claims(accessToken).realm_access?.roles ?? []; }
function versioned(version, key) { return { 'If-Match': String(version), 'Idempotency-Key': key }; }

// Acceptance-only SQL reads verify cross-service effect counts. All domain
// state is created above through the owning application APIs.
function readOnlyAccounting(orderId, groupId) {
  const idPattern = /^[0-9A-HJKMNP-TV-Z]{26}$/;
  assert(idPattern.test(orderId) && idPattern.test(groupId), 'Invalid fixture accounting reference.');
  const query = `SELECT o.checkout_id,o.payment_intent_id,pi.provider,pi.status,` +
    `(SELECT COUNT(*) FROM order_service_checkout_runtime.orders x WHERE x.checkout_id=o.checkout_id),` +
    `(SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_intents x WHERE x.checkout_id=o.checkout_id),` +
    `(SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_attempts x WHERE x.payment_intent_id=o.payment_intent_id AND x.outcome='SUCCEEDED'),` +
    `(SELECT COUNT(*) FROM order_service_checkout_runtime.business_orders x WHERE x.order_id=o.id),` +
    `(SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns x WHERE x.business_order_id='${groupId}') ` +
    `FROM order_service_checkout_runtime.orders o ` +
    `JOIN payment_service_checkout_runtime.payment_intents pi ON pi.id=o.payment_intent_id ` +
    `WHERE o.id='${orderId}'`;
  const encoded = Buffer.from(query).toString('base64');
  const output = execFileSync('docker', ['exec', 'msb-demo-mysql', 'sh', '-lc',
    'echo "$1" | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N', '--', encoded],
  { encoding: 'utf8' }).trim();
  const columns = output.split('\t');
  assert(columns.length === 9, 'Read-only accounting query returned an unexpected shape.');
  return {
    checkoutId: columns[0], paymentIntentId: columns[1], provider: columns[2], intentStatus: columns[3],
    orderCount: Number(columns[4]), intentCount: Number(columns[5]),
    successfulAttemptCount: Number(columns[6]), businessGroupCount: Number(columns[7]),
    returnCount: Number(columns[8]),
  };
}

async function api(url, options = {}) {
  const headers = { ...(options.headers ?? {}) };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  const hasBody = Object.hasOwn(options, 'body');
  if (hasBody) headers['Content-Type'] = 'application/json';
  const response = await fetch(url, {
    method: options.method ?? 'GET', headers, body: hasBody ? JSON.stringify(options.body) : undefined,
  });
  if (!(options.expected ?? [200]).includes(response.status)) {
    await response.body?.cancel();
    throw new Error(`${options.method ?? 'GET'} ${new URL(url).pathname} failed (${response.status}).`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function checked(response, url) {
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error(`GET ${new URL(url).pathname} failed (${response.status}).`);
  }
  return response.json();
}

async function status(url, accessToken) {
  const response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  await response.body?.cancel();
  return response.status;
}

async function poll(action, accepted, name) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const value = await action();
    if (accepted(value)) return value;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`${name} did not converge.`);
}

function assert(condition, message) { if (!condition) throw new Error(message); }
