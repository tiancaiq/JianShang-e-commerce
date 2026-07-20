import { execFileSync } from 'node:child_process';

const SELLER = {
  email: 'trade.seller@msb.local',
  password: 'TradeSeller!2026',
  firstName: 'Mira',
  lastName: 'Chen',
};

const BUYER = {
  email: 'trade.buyer@msb.local',
  password: 'TradeBuyer!2026',
  firstName: 'Jon',
  lastName: 'Bell',
};

export default async function globalSetup(): Promise<void> {
  const subjects = await ensureKeycloakDemoUsers();
  resetTradeDemoData(subjects);
}

// Keeps the browser test repeatable even when an existing Keycloak volume predates the realm seed users.
async function ensureKeycloakDemoUsers(): Promise<Map<string, string>> {
  const baseUrl = process.env['E2E_KEYCLOAK_URL'] || 'http://localhost:8181';
  const token = await keycloakAdminToken(baseUrl);
  const subjects = new Map<string, string>();
  for (const user of [SELLER, BUYER]) {
    let matches = await fetch(
      `${baseUrl}/admin/realms/msb-local/users?username=${encodeURIComponent(user.email)}&exact=true`,
      { headers: { Authorization: `Bearer ${token}` } },
    ).then(response => checkedJson<Array<{ id: string }>>(response));

    if (matches.length === 0) {
      await checked(fetch(`${baseUrl}/admin/realms/msb-local/users`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username: user.email,
          email: user.email,
          emailVerified: true,
          enabled: true,
          firstName: user.firstName,
          lastName: user.lastName,
          attributes: { displayName: [`${user.firstName} ${user.lastName}`] },
        }),
      }));
      matches = await fetch(
        `${baseUrl}/admin/realms/msb-local/users?username=${encodeURIComponent(user.email)}&exact=true`,
        { headers: { Authorization: `Bearer ${token}` } },
      ).then(response => checkedJson<Array<{ id: string }>>(response));
    }

    const subject = matches[0]?.id;
    if (!subject) {
      throw new Error(`Keycloak did not return the created demo user ${user.email}.`);
    }
    subjects.set(user.email, subject);
    await checked(fetch(`${baseUrl}/admin/realms/msb-local/users/${subject}/reset-password`, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ type: 'password', value: user.password, temporary: false }),
    }));
  }
  return subjects;
}

async function keycloakAdminToken(baseUrl: string): Promise<string> {
  const configuredPassword = process.env['E2E_KEYCLOAK_ADMIN_PASSWORD'];
  const candidates = configuredPassword
    ? [configuredPassword]
    : ['admin', 'demo-change-me-admin', 'local-dev-only-change-me'];
  for (const password of candidates) {
    const form = new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: process.env['E2E_KEYCLOAK_ADMIN_USER'] || 'admin',
      password,
    });
    const response = await fetch(`${baseUrl}/realms/master/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    if (response.ok) {
      const body = await response.json() as { access_token: string };
      return body.access_token;
    }
  }
  throw new Error('Could not authenticate to local Keycloak. Set E2E_KEYCLOAK_ADMIN_PASSWORD.');
}

// Resets only the fixed demo listing and its chat rows; no user-created marketplace data is touched.
function resetTradeDemoData(subjects: Map<string, string>): void {
  const configuredContainer = process.env['E2E_MYSQL_CONTAINER'];
  const containers = configuredContainer ? [configuredContainer] : ['mysql', 'msb-demo-mysql'];
  const sellerSubject = sqlValue(subjects.get(SELLER.email));
  const buyerSubject = sqlValue(subjects.get(BUYER.email));
  let lastError: unknown;
  for (const container of containers) {
    const password = process.env['E2E_MYSQL_PASSWORD']
      || (container === 'msb-demo-mysql' ? 'demo-change-me-mysql' : 'mysql');
    try {
      execFileSync('docker', [
        'exec',
        container,
        'mysql',
        '-uroot',
        `-p${password}`,
        '--default-character-set=utf8mb4',
        'chat',
        '-e',
        `
          UPDATE identity.users
          SET keycloak_sub = '${sellerSubject}', updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000001';
          UPDATE identity.users
          SET keycloak_sub = '${buyerSubject}', updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000002';
          DELETE m FROM chat.messages m
          JOIN chat.conversations c ON c.id = m.conversation_id
          WHERE c.subject_listing_id = '01D00000000000000000000101';
          DELETE cp FROM chat.conversation_participants cp
          JOIN chat.conversations c ON c.id = cp.conversation_id
          WHERE c.subject_listing_id = '01D00000000000000000000101';
          DELETE FROM chat.listing_trade_completions
          WHERE listing_id = '01D00000000000000000000101';
          DELETE FROM chat.conversations
          WHERE subject_listing_id = '01D00000000000000000000101';
          UPDATE catalog.listings
          SET status = 'ACTIVE',
              moderation_status = 'APPROVED',
              publication_source = 'ADMIN_REVIEW',
              published_at = CURRENT_TIMESTAMP(6),
              version = version + 1,
              updated_at = CURRENT_TIMESTAMP(6)
          WHERE id = '01D00000000000000000000101';
        `,
      ], { stdio: 'pipe' });
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error(
    `Could not reset the trade demo in MySQL. Set E2E_MYSQL_CONTAINER and E2E_MYSQL_PASSWORD. ${String(lastError)}`,
  );
}

function sqlValue(value: string | undefined): string {
  if (!value || !/^[0-9a-f-]{36}$/i.test(value)) {
    throw new Error(`Unexpected Keycloak subject: ${String(value)}`);
  }
  return value;
}

async function checked(responsePromise: Promise<Response>): Promise<void> {
  const response = await responsePromise;
  if (!response.ok) {
    throw new Error(`Keycloak request failed with HTTP ${response.status}: ${await response.text()}`);
  }
}

async function checkedJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Keycloak request failed with HTTP ${response.status}: ${await response.text()}`);
  }
  return await response.json() as T;
}
