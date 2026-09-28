// RC-FIXTURE-01 identity setup uses the same Keycloak Admin service account
// already used by Gateway registration. It only touches named disposable users.
const BASE = 'http://127.0.0.1:8181';
const REALM = 'msb-local';
const required = [
  'KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID', 'KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET',
  'LOCAL_DEMO_BUYER_PASSWORD', 'LOCAL_DEMO_BUYER_B_PASSWORD', 'LOCAL_DEMO_HARBOR_PASSWORD',
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing disposable runtime variable ${name}.`);
}

const tokenResponse = await fetch(`${BASE}/realms/${REALM}/protocol/openid-connect/token`, {
  method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'client_credentials',
    client_id: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID,
    client_secret: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET }),
});
if (!tokenResponse.ok) throw new Error(`Local fixture service-account login failed (${tokenResponse.status}).`);
const adminToken = (await tokenResponse.json()).access_token;
if (!adminToken) throw new Error('Local fixture service-account token was absent.');

const customerA = await ensureBuyer('rc.customer.a@msb.local', 'Customer A',
  process.env.LOCAL_DEMO_BUYER_PASSWORD);
const customerB = await ensureBuyer('rc.customer.b@msb.local', 'Customer B',
  process.env.LOCAL_DEMO_BUYER_B_PASSWORD);
const seller = await exactUser('harbor.seller@msb.local');
if (!seller) throw new Error('The approved Harbor business-seller fixture is absent.');
await resetPassword(seller.id, process.env.LOCAL_DEMO_HARBOR_PASSWORD);
const sellerRoles = await realmRoles(seller.id);
if (!sellerRoles.includes('BUSINESS_USER')) {
  throw new Error('Harbor fixture seller lacks the business role.');
}
if (new Set([customerA.id, customerB.id, seller.id]).size !== 3) {
  throw new Error('Disposable customers and business seller must be distinct.');
}
console.log(JSON.stringify({ customerA: 'ready', customerB: 'ready',
  distinctCustomerSubjects: true, buyerOnlyRoles: true, seller: 'ready' }));

async function ensureBuyer(email, label, password) {
  let user = await exactUser(email);
  if (!user) {
    const [firstName, lastName] = label.split(' ');
    await admin(`/admin/realms/${REALM}/users`, {
      method: 'POST', expected: [201], body: { username: email, email, enabled: true,
        emailVerified: true, firstName, lastName,
        attributes: { displayName: [`RC ${label}`] },
        credentials: [{ type: 'password', value: password, temporary: false }] },
    });
    user = await exactUser(email);
  } else {
    await resetPassword(user.id, password);
  }
  if (!user?.enabled || user.email !== email) {
    throw new Error(`Disposable ${label} identity is disabled or mismatched.`);
  }
  const assignedRoles = await realmRoles(user.id);
  if (!assignedRoles.includes('BUYER') ||
      assignedRoles.some(role => ['ADMIN', 'BUSINESS_USER', 'INDIVIDUAL_SELLER'].includes(role))) {
    throw new Error(`Disposable ${label} has unexpected realm roles.`);
  }
  return user;
}

async function exactUser(username) {
  const users = await admin(`/admin/realms/${REALM}/users?username=${encodeURIComponent(username)}&exact=true`);
  const matches = users.filter(user => user.username === username);
  if (matches.length > 1) throw new Error(`Duplicate disposable identity ${username}.`);
  return matches[0] ?? null;
}

async function resetPassword(id, password) {
  await admin(`/admin/realms/${REALM}/users/${id}/reset-password`, {
    method: 'PUT', expected: [204],
    body: { type: 'password', value: password, temporary: false },
  });
}

async function realmRoles(id) {
  const mapped = await admin(`/admin/realms/${REALM}/users/${id}/role-mappings/realm/composite`);
  return mapped.map(role => role.name);
}

async function admin(path, options = {}) {
  const headers = { Authorization: `Bearer ${adminToken}` };
  if (options.body) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${BASE}${path}`, {
    method: options.method ?? 'GET', headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!(options.expected ?? [200]).includes(response.status)) {
    await response.body?.cancel();
    throw new Error(`Keycloak fixture ${options.method ?? 'GET'} ${path.split('?')[0]} failed (${response.status}).`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}
