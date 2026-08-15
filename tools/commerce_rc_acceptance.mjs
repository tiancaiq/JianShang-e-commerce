import { execFileSync } from 'node:child_process';

const ORDER = 'http://127.0.0.1:8081';
const AUTH = 'http://127.0.0.1:8085';
const NOTIFICATION = 'http://127.0.0.1:8083';
const PAYMENT = 'http://127.0.0.1:8084';
const KEYCLOAK = 'http://127.0.0.1:8181';
const GATEWAY = 'http://127.0.0.1:9000';
const HARBOR_BUSINESS = '01KZCARTB00000000000000002';
const SHEN_BUSINESS = '01KXQBUSI00000000000000001';
const HARBOR_LISTING = '01KZ3CF59Z42DG2M0AZ8FQ1729';
const SHEN_LISTING = '01KXQMEH9KBPH5S7DPBFM0VBJ5';
const RUN_ID = Date.now().toString(36);
const required = [
  'LOCAL_DEMO_BUYER_PASSWORD', 'LOCAL_DEMO_BUYER_B_PASSWORD',
  'LOCAL_DEMO_HARBOR_PASSWORD', 'LOCAL_DEMO_SHEN_PASSWORD',
  'KEYCLOAK_MARKETPLACE_CLIENT_SECRET', 'PAYMENT_INTERNAL_SERVICE_TOKEN',
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Set ${name} before running the Commerce RC acceptance gate.`);
}

const actors = {
  buyerA: await token('trade.buyer@msb.local', process.env.LOCAL_DEMO_BUYER_PASSWORD),
  buyerB: await token('trade.seller@msb.local', process.env.LOCAL_DEMO_BUYER_B_PASSWORD),
  sellerA: await token('harbor.seller@msb.local', process.env.LOCAL_DEMO_HARBOR_PASSWORD),
  sellerB: await token('shen.ban2@mycnmipss.org', process.env.LOCAL_DEMO_SHEN_PASSWORD),
};

await authorizationBaseline();
const journeyA = await fulfillmentReturnJourney();
const journeyB = await cancellationJourney();
await assertAccounting(journeyA, journeyB);

console.log(JSON.stringify({
  gate: 'V2_COMMERCE_RC_01',
  journeyA,
  journeyB,
  authorizationMatrix: 'passed',
  accounting: 'passed',
  restartRecovery: 'passed',
}, null, 2));

async function fulfillmentReturnJourney() {
  const completedPurchase = await purchase([HARBOR_LISTING, SHEN_LISTING], `journey-a-${RUN_ID}`);
  assert(completedPurchase.order.totalAmount === 8.5, `Journey A total was ${completedPurchase.order.totalAmount}, expected 8.5.`);
  assert(completedPurchase.order.groups.length === 2, 'Journey A did not create exactly two business groups.');
  const harbor = completedPurchase.order.groups.find(group => group.businessId === HARBOR_BUSINESS);
  const shen = completedPurchase.order.groups.find(group => group.businessId === SHEN_BUSINESS);
  assert(harbor && shen, 'Journey A business-group split is incorrect.');

  docker('stop', 'msb-cart-runtime-notification');
  await fulfill(HARBOR_BUSINESS, harbor.businessOrderId, actors.sellerA, `harbor-a-${RUN_ID}`);
  await fulfill(SHEN_BUSINESS, shen.businessOrderId, actors.sellerB, `shen-a-${RUN_ID}`);
  docker('start', 'msb-cart-runtime-notification');
  await waitHealthy('http://127.0.0.1:8083/actuator/health');

  let returnView = await api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}/groups/${harbor.businessOrderId}/return`, {
    token: actors.buyerA,
  });
  const requested = await api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}/groups/${harbor.businessOrderId}/returns`, {
    method: 'POST', token: actors.buyerA,
    headers: versioned(returnView.version, `rc-a-return-request-${RUN_ID}`),
    body: { reasonCode: 'NOT_AS_EXPECTED', comment: 'Commerce RC Journey A' },
  });
  const inTransit = await api(`${ORDER}/api/v1/businesses/${HARBOR_BUSINESS}/orders/${harbor.businessOrderId}/returns/${requested.returnId}/authorize`, {
    method: 'POST', token: actors.sellerA,
    headers: versioned(requested.version, `rc-a-return-authorize-${RUN_ID}`), body: null,
  });
  assert(inTransit.status === 'RETURN_IN_TRANSIT', 'Journey A return did not enter transit.');

  docker('stop', 'msb-cart-runtime-payment');
  await api(`${ORDER}/api/v1/businesses/${HARBOR_BUSINESS}/orders/${harbor.businessOrderId}/returns/${requested.returnId}/receive`, {
    method: 'POST', token: actors.sellerA,
    headers: versioned(inTransit.version, `rc-a-return-receive-${RUN_ID}`),
    body: { inventoryDisposition: 'RESTOCK_SELLABLE' },
  });
  docker('start', 'msb-cart-runtime-payment');
  await waitHealthy('http://127.0.0.1:8084/actuator/health');
  returnView = await poll(async () => api(
    `${ORDER}/api/v1/orders/${completedPurchase.order.orderId}/groups/${harbor.businessOrderId}/return`,
    { token: actors.buyerA },
  ), value => value.status === 'RETURN_COMPLETED', 'Journey A return completion');
  assert(Number(returnView.refundAmount) === 7.5, 'Journey A refund was not the Harbor group subtotal.');

  const sibling = await api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}`, { token: actors.buyerA });
  const siblingGroup = sibling.groups.find(group => group.businessOrderId === shen.businessOrderId);
  assert(siblingGroup?.status === 'DELIVERED', 'Journey A sibling group changed during the Harbor return.');
  const siblingReturn = await api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}/groups/${shen.businessOrderId}/return`, {
    token: actors.buyerA,
  });
  assert(siblingReturn.returnId === null, 'Journey A unexpectedly created a sibling return.');

  await pollNotifications(completedPurchase.order.orderId, 12);
  await assertNotFound(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}`, actors.buyerB, 'Buyer B order isolation');
  await assertNotFound(
    `${ORDER}/api/v1/businesses/${SHEN_BUSINESS}/orders/${harbor.businessOrderId}`,
    actors.sellerB,
    'cross-business seller isolation',
  );
  return {
    checkoutId: completedPurchase.checkout.id,
    orderId: completedPurchase.order.orderId,
    returnedBusinessOrderId: harbor.businessOrderId,
    siblingBusinessOrderId: shen.businessOrderId,
    returnId: returnView.returnId,
    refundAmount: Number(returnView.refundAmount),
  };
}

async function cancellationJourney() {
  const completedPurchase = await purchase([HARBOR_LISTING], `journey-b-${RUN_ID}`);
  docker('stop', 'msb-cart-runtime-inventory');
  docker('stop', 'msb-cart-runtime-payment');
  await api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}/cancellation-requests`, {
    method: 'POST', token: actors.buyerA,
    headers: versioned(completedPurchase.order.version, `rc-b-cancel-${RUN_ID}`),
  });
  docker('start', 'msb-cart-runtime-inventory');
  docker('start', 'msb-cart-runtime-payment');
  await waitHealthy('http://127.0.0.1:8082/actuator/health');
  await waitHealthy('http://127.0.0.1:8084/actuator/health');
  const cancelled = await poll(
    () => api(`${ORDER}/api/v1/orders/${completedPurchase.order.orderId}`, { token: actors.buyerA }),
    value => value.status === 'CANCELLED' && value.cancellation?.requestStatus === 'COMPLETED'
      && value.cancellation?.inventoryStatus === 'SUCCEEDED' && value.cancellation?.refund?.status === 'SUCCEEDED',
    'Journey B compensation',
  );
  assert(Number(cancelled.cancellation.refund.amount) === 7.5, 'Journey B cancellation refund was not exact.');
  const group = cancelled.groups[0];
  const sellerView = await api(
    `${ORDER}/api/v1/businesses/${HARBOR_BUSINESS}/orders/${group.businessOrderId}`,
    { token: actors.sellerA },
  );
  assert(sellerView.cancellationStatus === 'CANCELLED', 'Journey B seller view is not cancelled.');
  return {
    checkoutId: completedPurchase.checkout.id,
    orderId: completedPurchase.order.orderId,
    businessOrderId: group.businessOrderId,
    cancellationRequestId: cancelled.cancellation.requestId,
    refundAmount: Number(cancelled.cancellation.refund.amount),
  };
}

async function purchase(listingIds, keyPrefix) {
  let cart = await api(`${ORDER}/api/v1/cart`, { token: actors.buyerA });
  if (cart.items.length) {
    cart = await api(`${ORDER}/api/v1/cart`, {
      method: 'DELETE', token: actors.buyerA, headers: versioned(cart.version, `${keyPrefix}-clear`),
    });
  }
  for (const [index, listingId] of listingIds.entries()) {
    cart = await api(`${ORDER}/api/v1/cart/items`, {
      method: 'POST', token: actors.buyerA,
      headers: versioned(cart.version, `${keyPrefix}-add-${index}`),
      body: { listingId, quantity: 1 },
    });
  }
  const validation = await api(`${ORDER}/api/v1/cart/validate`, { method: 'POST', token: actors.buyerA, body: null });
  assert(validation.checkoutReady, `${keyPrefix} cart was not checkout-ready.`);
  const addressId = await buyerAddressId();
  const checkout = await api(`${ORDER}/api/v1/checkouts`, {
    method: 'POST', token: actors.buyerA, headers: { 'Idempotency-Key': `${keyPrefix}-checkout` },
    body: { cartVersion: cart.version, addressId }, expected: [201],
  });
  const intent = await api(`${ORDER}/api/v1/checkouts/${checkout.id}/payment-intent`, {
    method: 'POST', token: actors.buyerA, headers: { 'Idempotency-Key': `${keyPrefix}-payment` }, body: null,
  });

  docker('stop', 'msb-cart-runtime-order');
  const buyerId = sql(`SELECT buyer_id FROM payment_service_checkout_runtime.payment_intents WHERE id='${intent.id}'`);
  await api(`${PAYMENT}/api/v1/internal/payment-intents/${intent.id}/complete-demo`, {
    method: 'POST', headers: { 'X-Internal-Service-Token': process.env.PAYMENT_INTERNAL_SERVICE_TOKEN },
    body: { buyerId, actionReference: `fake_action_${intent.id.toLowerCase()}` },
  });
  docker('start', 'msb-cart-runtime-order');
  await waitHealthy('http://127.0.0.1:8081/actuator/health');
  const resolution = await poll(
    () => api(`${ORDER}/api/v1/checkouts/${checkout.id}/confirmed-order`, { token: actors.buyerA }),
    value => value.confirmed && value.orderId,
    `${keyPrefix} order confirmation after restart`,
  );
  const order = await api(`${ORDER}/api/v1/orders/${resolution.orderId}`, { token: actors.buyerA });
  return { checkout, intent, order };
}

async function fulfill(businessId, groupId, actorToken, prefix) {
  let detail = await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}`, { token: actorToken });
  await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}/accept`, {
    method: 'POST', token: actorToken, headers: versioned(detail.version, `${prefix}-accept`), body: null,
  });
  detail = await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}`, { token: actorToken });
  await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}/processing`, {
    method: 'POST', token: actorToken, headers: versioned(detail.version, `${prefix}-processing`), body: null,
  });
  detail = await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}`, { token: actorToken });
  await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}/shipments`, {
    method: 'POST', token: actorToken, headers: versioned(detail.version, `${prefix}-shipment`),
    body: { carrierDisplayName: 'Demo Carrier', serviceDisplayName: 'Ground', trackingNumber: `RC-${groupId}`, shippedAt: new Date().toISOString() },
  });
  detail = await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}`, { token: actorToken });
  await api(`${ORDER}/api/v1/businesses/${businessId}/orders/${groupId}/delivery-demo`, {
    method: 'POST', token: actorToken, headers: versioned(detail.version, `${prefix}-delivery`), body: null,
  });
}

async function buyerAddressId() {
  const response = await api(`${AUTH}/api/v1/users/me/addresses`, { token: actors.buyerA });
  const addresses = response.data ?? response;
  if (addresses.length) return addresses.find(address => address.isDefault)?.id ?? addresses[0].id;
  const created = await api(`${AUTH}/api/v1/users/me/addresses`, {
    method: 'POST', token: actors.buyerA,
    body: { label: 'Commerce RC', recipientName: 'Commerce Buyer', phone: '+19495550123', line1: '100 Demo Avenue', line2: null, city: 'Irvine', region: 'CA', postalCode: '92612', countryCode: 'US' },
    expected: [201],
  });
  return (created.data ?? created).id;
}

async function authorizationBaseline() {
  await assertStatus(`${GATEWAY}/api/v1/cart`, {}, [401, 302], 'guest cart');
  const buyerBCart = await api(`${ORDER}/api/v1/cart`, { token: actors.buyerB });
  assert(buyerBCart.items.length === 0, 'Buyer B cart is not isolated from Buyer A.');
  await assertStatus(`${ORDER}/api/v1/businesses/${HARBOR_BUSINESS}/orders`, { token: actors.buyerB }, [403, 404], 'unauthorized business queue');
}

async function assertAccounting(a, b) {
  assert(Number(sql(`SELECT COUNT(*) FROM order_service_checkout_runtime.business_orders WHERE order_id='${a.orderId}'`)) === 2, 'Journey A group cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_returns WHERE order_id='${a.orderId}'`)) === 1, 'Journey A return cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM order_service_checkout_runtime.business_order_return_shipments WHERE return_id='${a.returnId}'`)) === 1, 'Journey A return shipment cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM inventory_service.inventory_return_restocks WHERE return_id='${a.returnId}'`)) === 1, 'Journey A restock cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_return_refunds WHERE return_id='${a.returnId}' AND amount=7.5000`)) === 1, 'Journey A refund cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM inventory_service.inventory_cancellation_restocks WHERE order_id='${b.orderId}'`)) === 1, 'Journey B cancellation restock cardinality failed.');
  assert(Number(sql(`SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_refunds WHERE order_id='${b.orderId}' AND amount=7.5000`)) === 1, 'Journey B refund cardinality failed.');
  assert(Number(sql(`SELECT on_hand-reserved FROM inventory_service.inventory_items WHERE listing_id='${HARBOR_LISTING}'`)) === 12, 'Harbor final inventory did not reconcile to 12.');
  assert(Number(sql(`SELECT on_hand-reserved FROM inventory_service.inventory_items WHERE listing_id='${SHEN_LISTING}'`)) === 7, 'Shen final inventory did not reconcile to 7.');
  const overRefunded = Number(sql(`SELECT COUNT(*) FROM payment_service_checkout_runtime.payment_intents pi WHERE COALESCE((SELECT SUM(pr.amount) FROM payment_service_checkout_runtime.payment_refunds pr WHERE pr.payment_intent_id=pi.id),0)+COALESCE((SELECT SUM(rr.amount) FROM payment_service_checkout_runtime.payment_return_refunds rr WHERE rr.payment_intent_id=pi.id),0)>pi.amount`));
  assert(overRefunded === 0, 'At least one payment intent is over-refunded.');
}

async function pollNotifications(orderId, minimum) {
  await poll(async () => {
    const page = await api(`${NOTIFICATION}/api/v1/notifications?limit=50`, { token: actors.buyerA });
    const items = page.items ?? page.data?.items ?? [];
    return items.filter(item => item.safeRoute?.includes(orderId)).length;
  }, count => count >= minimum, 'notification recovery after restart');
}

async function token(username, password) {
  const body = new URLSearchParams({ grant_type: 'password', client_id: 'msb-marketplace', client_secret: process.env.KEYCLOAK_MARKETPLACE_CLIENT_SECRET, username, password });
  const response = await fetch(`${KEYCLOAK}/realms/msb-local/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
  if (!response.ok) throw new Error(`Token request failed for ${username} (${response.status}).`);
  return (await response.json()).access_token;
}

function versioned(version, key) { return { 'If-Match': String(version), 'Idempotency-Key': key }; }

async function api(url, options = {}) {
  const headers = { ...(options.headers ?? {}) };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  let body;
  if (Object.hasOwn(options, 'body')) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  const response = await fetch(url, { method: options.method ?? 'GET', headers, body });
  const expected = options.expected ?? [200];
  if (!expected.includes(response.status)) {
    const safeBody = (await response.text()).slice(0, 240);
    throw new Error(`${options.method ?? 'GET'} ${new URL(url).pathname} failed (${response.status}): ${safeBody}`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function assertNotFound(url, actorToken, name) { await assertStatus(url, { token: actorToken }, [403, 404], name); }
async function assertStatus(url, options, expected, name) {
  const headers = options.token ? { Authorization: `Bearer ${options.token}` } : {};
  const response = await fetch(url, { redirect: 'manual', headers });
  await response.body?.cancel();
  assert(expected.includes(response.status), `${name} returned ${response.status}, expected ${expected.join('/')}.`);
}

async function poll(action, accepted, name, attempts = 40) {
  let value;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try { value = await action(); if (accepted(value)) return value; } catch (error) { if (attempt === attempts - 1) throw error; }
    await delay(500);
  }
  throw new Error(`${name} did not converge; last value=${JSON.stringify(value)}`);
}

function docker(action, container) { execFileSync('docker', [action, container], { stdio: 'ignore' }); }
async function waitHealthy(url) { await poll(async () => (await fetch(url)).status, status => status === 200, `health ${url}`, 60); }
function sql(query) {
  return execFileSync('docker', ['exec', 'msb-demo-mysql', 'sh', '-lc', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "$1"', '--', query], { encoding: 'utf8' }).trim();
}
function assert(condition, message) { if (!condition) throw new Error(message); }
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
