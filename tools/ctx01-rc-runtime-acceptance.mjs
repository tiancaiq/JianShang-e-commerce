import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';

// Local-only CTX-01 API/SSE acceptance with Agent-owned MySQL and history checks;
// this is not authenticated browser evidence. Never print credentials or prompts.
const KC = 'http://127.0.0.1:8181';
const AUTH = 'http://127.0.0.1:8085';
const control = process.argv.includes('--control');
const AGENT = control ? 'http://127.0.0.1:8086' : 'http://127.0.0.1:18086';
const ORDER = 'http://127.0.0.1:8081';
const REALM = 'msb-local';
const required = [
  'KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID',
  'KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET',
  'KEYCLOAK_MARKETPLACE_CLIENT_SECRET',
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing local fixture setting ${name}`);
}

const runId = Date.now().toString(36);
const longOnly = process.argv.includes('--long-only');
const cartOnly = process.argv.includes('--cart-only');
const confirmationOnly = process.argv.includes('--confirmation-only');
const stopRetryOnly = process.argv.includes('--stop-retry-only');
const refineOnly = process.argv.includes('--refine-matrix');
const correctionOnly = process.argv.includes('--refine-corrections');
const refine01aOnly = process.argv.includes('--refine-01a');
const refine01bOnly = process.argv.includes('--refine-01b');
const refine01bAttributesOnly = process.argv.includes('--refine-01b-attributes');
const refine01bMonitorOnly = process.argv.includes('--refine-01b-monitor');
const refine01bAbcOnly = process.argv.includes('--refine-01b-abc');
const refine01b1SmokeOnly = process.argv.includes('--refine-01b1-smoke');
const refine01cOnly = process.argv.includes('--refine-01c');
const refine01dOnly = process.argv.includes('--refine-01d');
const refine01d1Only = process.argv.includes('--refine-01d1');
const refine01eOnly = process.argv.includes('--refine-01e');
const refine01eGroup = process.argv.find(arg => arg.startsWith('--refine-01e-group='))?.split('=')[1];
const contextFinalMatrixOnly = process.argv.includes('--context-final-matrix');
const contextFinalGroup = process.argv.find(arg => arg.startsWith('--context-final-group='))?.split('=')[1];
const contextFinalOnly = process.argv.find(arg => arg.startsWith('--context-final-only='))?.split('=')[1];
const created = [];
const results = [];
let adminToken;

try {
  adminToken = await keycloakToken({
    grant_type: 'client_credentials',
    client_id: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID,
    client_secret: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET,
  });
  const a = await createBuyer('a');
  const b = await createBuyer('b');
  const authA = await appApi(`${AUTH}/api/v1/users/me`, a.token);
  const authB = await appApi(`${AUTH}/api/v1/users/me`, b.token);
  results.push({ scenario: 'fixture identity', status: 'CHECKED',
    aStatus: authA.data?.status ?? null, bStatus: authB.data?.status ?? null,
    aIdPresent: Boolean(authA.data?.id), bIdPresent: Boolean(authB.data?.id),
    envelopeKeys: Object.keys(authA),
  });
  if (refine01eOnly) {
    await runRefine01EAcceptance(a.token, b.token, authA.data.id);
  } else if (contextFinalGroup) {
    await runContextFinalGroup(contextFinalGroup, a.token, b.token, authA.data.id);
  } else if (contextFinalMatrixOnly) {
    await runContextFinalMatrix(a.token);
  } else if (refine01d1Only) {
    await runRefine01D1Acceptance(a.token);
  } else if (refine01dOnly) {
    await runRefine01DAcceptance(a.token);
  } else if (refine01cOnly) {
    await runRefine01CAcceptance(a.token);
  } else if (refine01bAbcOnly) {
    await runRefine01BABC(a.token);
  } else if (refine01b1SmokeOnly) {
    await runRefine01B1Smoke(a.token);
  } else if (refine01aOnly) {
    await runRefine01AAcceptance(a.token, b.token);
  } else if (refine01bOnly || refine01bAttributesOnly || refine01bMonitorOnly) {
    await runRefine01BAcceptance(a.token, refine01bAttributesOnly || refine01bMonitorOnly);
  } else if (correctionOnly) {
    await runCorrectionMatrix(a.token);
  } else if (refineOnly) {
    await runRefinementMatrix(a.token);
  } else if (stopRetryOnly) {
    await runStopRetry(a.token);
  } else if (confirmationOnly) {
    const session = await createSession(a.token);
    const discovery = await sendStream('H: confirmation grounding', a.token, session,
      'Find me mechanical keyboards.');
    if (discovery?.message?.attachments?.length) {
      const prepared = await sendStream('H: prepare exact confirmation', a.token, session,
        'Before you search for a different keyboard, confirm with me first.');
      if (prepared?.message?.pendingInteraction?.type) {
        const firstYes = await sendStream('H: consume confirmation', a.token, session, 'Yes.');
        const repeatedYes = await sendStream('H: repeated yes', a.token, session, 'Yes.');
        results.push({ scenario: 'H: confirmation safety',
          status: firstYes?.message && repeatedYes?.message ? 'CHECKED' : 'INCONCLUSIVE',
          firstYesTools: firstYes?.message?.toolActivity?.map(item => item.tool) ?? [],
          repeatedYesTools: repeatedYes?.message?.toolActivity?.map(item => item.tool) ?? [],
          repeatedYesPendingType: repeatedYes?.message?.pendingInteraction?.type ?? null,
          authoritativeConfirmationStates: sql(
            `SELECT state, COUNT(*), SUM(consumed_at IS NOT NULL) FROM agent.agent_confirmations WHERE session_id=${q(session)} GROUP BY state`,
          ).split('\n').filter(Boolean),
        });
      } else {
        results.push({ scenario: 'H: confirmation safety', status: 'NOT_RUN',
          reason: 'No durable pending interaction was prepared by live model.' });
      }
    }
  } else if (cartOnly) {
    const cartSession = await createSession(a.token);
    await runCartFlow(a.token, cartSession);
  } else {
  const sessionA = await createSession(a.token);
  const sessionB = await createSession(b.token);

  // Real provider + real Product through the running CTX-01 host process.
  const discovery = await sendStream('A: discovery', a.token, sessionA, 'Find me mechanical keyboards.');
  if (!longOnly) {
  if (discovery?.message?.attachments?.length >= 2) {
    await sendStream('B: second listing', a.token, sessionA,
      'Tell me more about the second one.');
  } else {
    results.push({ scenario: 'B: second listing', status: 'NOT_RUN',
      reason: 'The preceding real search did not yield two validated cards.' });
  }
  await sendStream('G: orders read', a.token, sessionA, 'Show my orders.');
  await sendStream('O: greeting', b.token, sessionB, 'Hi.');
  await probeIsolation(b.token, sessionA);
  const historyA = await appApi(`${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionA}/messages`, a.token);
  const historyB = await appApi(`${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionB}/messages`, b.token);
  results.push({ scenario: 'K: actor isolation', status: 'CHECKED',
    aMessages: historyA.data?.length ?? null,
    bMessages: historyB.data?.length ?? null,
    crossSessionDenied: results.some(r => r.scenario === 'K: cross-session denial' && r.status === 'PASS'),
  });
  const detourSession = await createSession(a.token);
  const mouse = await sendStream('I: mouse discovery', a.token, detourSession,
    'Find me a wireless mouse.');
  await sendStream('I: out of scope', a.token, detourSession,
    "What's the capital of France?");
  if (mouse?.message?.attachments?.length >= 2) {
    await sendStream('I: marketplace return', a.token, detourSession,
      'Back to the mouse — tell me about the second one.');
  } else {
    results.push({ scenario: 'I: marketplace return', status: 'NOT_RUN',
      reason: 'The preceding mouse search did not produce two validated cards.' });
  }
  const refinementSession = await createSession(a.token);
  await sendStream('C: laptops', a.token, refinementSession, 'Show me laptops.');
  await sendStream('C: price refinement', a.token, refinementSession,
    'Only under $1000.');
  await sendStream('C: RAM refinement', a.token, refinementSession,
    'At least 16 GB RAM.');
  const cart = await appApi(`${ORDER}/api/v1/cart`, a.token);
  results.push({ scenario: 'F: authoritative cart read', status: 'CHECKED',
    itemCount: (cart.data ?? cart).itemCount ?? null });
  const orders = await appApi(`${ORDER}/api/v1/orders?limit=5`, a.token);
  results.push({ scenario: 'G: authoritative orders read', status: 'CHECKED',
    orderCount: (orders.data ?? orders).items?.length ?? null });
  }
  if (!control && discovery?.message?.attachments?.length >= 2) {
    const longSession = await createSession(a.token);
    const cards = discovery.message.attachments.slice(0, 2);
    seedLongSession(authA.data.id, longSession, cards);
    const firstPage = await appApi(
      `${AGENT}/api/v1/agent/marketplace-v2/sessions/${longSession}/messages?limit=100`,
      a.token,
    );
    const longTurn = await sendStream('J: >100 second listing', a.token, longSession,
      'Tell me about the second one.', { longSession: true });
    results.push({ scenario: 'J: latest-context and forward-history check',
      status: firstPage.hasMore === true && firstPage.data?.[0]?.body === 'ctx01-rc-filler-0' &&
        longTurn?.message?.attachments?.[0]?.listingId === cards[1].listingId ? 'PASS' : 'FAIL',
      seededMessageCount: 113, forwardHistoryHasMore: firstPage.hasMore,
      forwardHistoryFirstIsOldest: firstPage.data?.[0]?.body === 'ctx01-rc-filler-0',
      expectedSecondTitle: cards[1].title,
      selectedTitle: longTurn?.message?.attachments?.[0]?.title ?? null,
    });
  } else {
    results.push({ scenario: 'J: >100 second listing', status: 'NOT_RUN',
      reason: 'No two validated cards were available for a safe reference fixture.' });
  }
  }
} catch (error) {
  results.push({ scenario: 'harness', status: 'ERROR', reason: String(error.message).slice(0, 220) });
} finally {
  // Long live matrices can outlast the admin token; refresh before disabling fixtures.
  if (created.length) {
    try {
      adminToken = await keycloakToken({
        grant_type: 'client_credentials',
        client_id: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID,
        client_secret: process.env.KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET,
      });
    } catch {
      results.push({ scenario: 'fixture cleanup token', status: 'ERROR',
        reason: 'Fresh admin credential unavailable for disposable-account cleanup.' });
    }
  }
  for (const userId of created) {
    try {
      await admin(`/admin/realms/${REALM}/users/${userId}`, {
        method: 'PUT', body: { enabled: false }, expected: [204],
      });
    } catch {
      results.push({ scenario: 'fixture cleanup', status: 'ERROR',
        reason: 'A newly created disposable account could not be disabled.' });
    }
  }
  const displayed = contextFinalGroup || contextFinalMatrixOnly || refineOnly || correctionOnly || refine01aOnly || refine01bOnly || refine01bAttributesOnly || refine01bMonitorOnly || refine01bAbcOnly || refine01b1SmokeOnly || refine01cOnly || refine01dOnly || refine01d1Only || refine01eOnly ? results.map(item => ({
    scenario: item.scenario, status: item.status,
    eventTypes: item.eventTypes, eventErrorCode: item.eventErrorCode,
    textDeltaCount: item.textDeltaCount,
    decisionCount: item.decisionCount,
    tools: item.toolActivity?.map(tool => `${tool.tool}:${tool.status}:${tool.reason}`),
    attachmentCount: item.attachmentCount,
    listingIds: item.listingIds,
    pendingType: item.pendingType,
    permissionAsked: /would you like|do you want|confirm|permission|proceed/i.test(item.visibleText ?? ''),
    visibleText: contextFinalGroup ? item.visibleText?.slice(0, 240) :
      item.toolActivity?.length ? undefined : item.visibleText?.slice(0, 240),
    persistedUserCount: item.persistedUserCount,
    persistedAssistantCount: item.persistedAssistantCount,
    latestSearch: item.latestSearch,
    searchObservations: item.searchObservations,
    crossSessionStatus: item.crossSessionStatus,
    flow: item.flow, repeat: item.repeat, turn: item.turn,
    executedSearchCount: item.executedSearchCount,
    appliedQuery: item.appliedQuery, appliedMaximumPrice: item.appliedMaximumPrice,
    appliedCurrency: item.appliedCurrency, passedRuns: item.passedRuns,
    baselineQuery: item.baselineQuery, baselineMaximumPrice: item.baselineMaximumPrice,
    rejectionMismatches: item.rejectionMismatches,
    appliedCondition: item.appliedCondition,
    reason: item.reason,
  })) : results.map(item => ({
    scenario: item.scenario, status: item.status,
    eventTypes: item.eventTypes, eventErrorCode: item.eventErrorCode,
    decisionCount: item.decisionCount,
    tools: item.toolActivity?.map(tool => `${tool.tool}:${tool.status}:${tool.reason}`),
    attachmentCount: item.attachmentCount,
    persistedUserCount: item.persistedUserCount,
    persistedAssistantCount: item.persistedAssistantCount,
    reason: item.reason,
  }));
  const concise = refine01dOnly || refine01d1Only ? displayed : refine01cOnly ? displayed.filter(item =>
    item.scenario === 'fixture identity' || item.scenario?.includes(' check-') ||
    item.scenario?.includes(' summary')) : displayed;
  console.log(JSON.stringify({ runId, accountsCreated: created.length,
    accountsDisabled: created.length - results.filter(r => r.scenario === 'fixture cleanup').length,
    results: concise }, null, 2));
  if (results.some(item => item.status === 'FAIL' || item.status === 'ERROR')) {
    process.exitCode = 1;
  }
}

async function runRefinementMatrix(token) {
  const cases = [
    ['core-1', 'Show me laptops.', 'Only under $1000.', 'At least 16GB RAM.'],
    ['core-2', 'Show me laptops.', 'Only under $1000.', 'At least 16GB RAM.'],
    ['core-3', 'Show me laptops.', 'Only under $1000.', 'At least 16GB RAM.'],
    ['under-variant', 'Show me laptops.', 'Under $1000.', 'At least 16GB RAM.'],
    ['price-correction', 'Show me laptops.', 'Only under $1000.', 'Actually make it under $1500.'],
    ['ram-correction', 'Show me laptops.', 'At least 16GB RAM.', 'Actually 32GB.'],
    ['condition-price', 'Show me laptops.', 'Only new ones.', 'Under $1000.'],
  ];
  for (const [label, ...turns] of cases) {
    const session = await createSession(token);
    for (let index = 0; index < turns.length; index++) {
      const response = await sendStream(`${label}: turn-${index + 1}`,
        token, session, turns[index]);
      if (!response) break;
    }
  }
}

async function runCorrectionMatrix(token) {
  const cases = [
    ['price-correction-direct', 'Show me laptops under $1000.', 'Actually make it under $1500.'],
    ['ram-correction-direct', 'Show me laptops with at least 16GB RAM.', 'Actually 32GB.'],
    ['condition-price-direct', 'Show me new laptops.', 'Under $1000.'],
  ];
  for (const [label, ...turns] of cases) {
    const session = await createSession(token);
    for (let index = 0; index < turns.length; index++) {
      const response = await sendStream(`${label}: turn-${index + 1}`,
        token, session, turns[index]);
      if (!response) break;
    }
  }
}

async function runRefine01AAcceptance(tokenA, tokenB) {
  const cases = [
    ['A: price', tokenA, ['Show me laptops under $1000.', 'Actually make it under $1500.']],
    ['B: condition-price', tokenA, ['Show me new laptops.', 'Under $1000.']],
    ['C: RAM query', tokenA, ['Show me laptops with at least 16GB RAM.', 'Actually 32GB.']],
    ['D: keyboard chain', tokenA, ['Show me keyboards.', 'Wireless only.', 'Under $100.', 'No RGB.']],
    ['E: new product', tokenA, ['Show me laptops under $1000.', 'Show me monitors.']],
    ['F: monitor correction', tokenA, ['Show me monitors under $300.', 'Actually under $400.']],
    ['G: location', tokenA, ['Show me desks near Los Angeles under $300.', 'Actually under $500.']],
    ['I: actor B', tokenB, ['Show me keyboards.']],
  ];
  let actorASession = null;
  let actorBSession = null;
  for (const [label, token, turns] of cases) {
    const session = await createSession(token);
    if (label === 'A: price') actorASession = session;
    if (label === 'I: actor B') actorBSession = session;
    for (let index = 0; index < turns.length; index++) {
      const response = await sendStream(`${label}: turn-${index + 1}`,
        token, session, turns[index]);
      const observations = safeSearchObservations(session);
      results.push({ scenario: `${label}: snapshot-${index + 1}`,
        status: response ? 'CHECKED' : 'INTERRUPTED',
        latestSearch: observations.at(-1) ?? null,
        searchObservations: observations.length,
      });
      if (!response) break;
    }
  }
  if (actorASession !== null && actorBSession !== null) {
    const cross = await fetch(
      `${AGENT}/api/v1/agent/marketplace-v2/sessions/${actorASession}/messages`,
      { headers: { Authorization: `Bearer ${tokenB}` }, signal: AbortSignal.timeout(10_000) },
    );
    results.push({ scenario: 'I: cross-actor denial',
      status: cross.ok ? 'FAIL' : 'PASS', crossSessionStatus: cross.status });
    await cross.body?.cancel();
    const bSearches = safeSearchObservations(actorBSession);
    results.push({ scenario: 'I: B isolated snapshot', status: 'CHECKED',
      latestSearch: bSearches.at(-1) ?? null,
      searchObservations: bSearches.length });
  }
}

async function runRefine01BAcceptance(token, attributesOnly = false) {
  const repeated = [
    ['core', ['Show me laptops.', 'Under $1000.', 'At least 16GB RAM.']],
    ['price-correction', ['Show me laptops under $1000.', 'Actually make it under $1500.']],
    ['condition-price', ['Show me new laptops.', 'Under $1000.']],
    ['ram-correction', ['Show me laptops with at least 16GB RAM.', 'Actually 32GB.']],
  ];
  const cases = [
    ...[1, 2].flatMap(repeat => repeated.map(([name, turns]) => [`${name}-${repeat}`, turns])),
    ['keyboard-chain', ['Show me keyboards.', 'Wireless only.', 'Under $100.', 'No RGB.']],
    ['monitor-chain', ['Show me monitors.', '27 inch.', 'Under $300.', '32 inch.']],
    ['new-product-reset', ['Show me laptops under $1000.', 'Show me monitors.']],
    ['ambiguous', ['Show me laptops.', 'Make it better.']],
    ['explicit-permission', ['Show me lamps under $25.',
      'Ask me before searching for lamps under $30.']],
  ];
  for (const [label, turns] of (attributesOnly
    ? cases.filter(([label]) => refine01bMonitorOnly
      ? label === 'monitor-chain'
      : ['keyboard-chain', 'monitor-chain'].includes(label))
    : cases)) {
    const session = await createSession(token);
    for (let index = 0; index < turns.length; index++) {
      const response = await sendStream(`${label}: turn-${index + 1}`,
        token, session, turns[index]);
      const observations = safeSearchObservations(session);
      results.push({ scenario: `${label}: snapshot-${index + 1}`,
        status: response ? 'CHECKED' : 'INTERRUPTED',
        latestSearch: observations.at(-1) ?? null,
        searchObservations: observations.length,
      });
      if (!response) break;
    }
  }
}

async function runRefine01BABC(token) {
  // Only the three user-requested monitor flows; each repetition owns a new session.
  const flows = [
    ['A', [
      ['Show me 27 inch monitors.', 27, null],
      ['Under $300.', 27, 300],
    ]],
    ['B', [
      ['Show me monitors under $300.', null, 300],
      ['27 inch.', 27, 300],
    ]],
    ['C', [
      ['Show me 27 inch monitors under $300.', 27, 300],
      ['Actually 32 inch.', 32, 300],
    ]],
  ];
  for (const [flow, turns] of flows) {
    let passedRuns = 0;
    for (let repeat = 1; repeat <= 5; repeat++) {
      const session = await createSession(token);
      let completed = true;
      for (let index = 0; index < turns.length; index++) {
        const [input, expectedSize, expectedMaximum] = turns[index];
        const before = safeSearchObservations(session);
        const scenario = `ABC ${flow}-${repeat} turn-${index + 1}`;
        const terminal = await sendStream(scenario, token, session, input);
        const turnResult = results.at(-1);
        const executed = safeSearchObservations(session).slice(before.length)
          .filter(item => item.status === 'SUCCEEDED' && item.query);
        const applied = executed.at(-1) ?? null;
        const query = applied?.query ?? '';
        const hasMonitor = /\bmonitors?\b/i.test(query);
        const sizes = [...query.matchAll(/\b(\d{1,3})[\s-]*inch(?:es)?\b/gi)]
          .map(match => Number(match[1]));
        const sizeMatches = expectedSize === null
          ? sizes.length === 0 : sizes.length === 1 && sizes[0] === expectedSize;
        const priceMatches = expectedMaximum === null
          ? applied?.maximumPrice == null
          : Number(applied?.maximumPrice) === expectedMaximum && applied?.currency === 'USD';
        const passed = Boolean(terminal && turnResult?.status === 'PASS'
          && executed.length === 1 && hasMonitor && sizeMatches && priceMatches
          && !turnResult.pendingType);
        results.push({ scenario: `ABC ${flow}-${repeat} check-${index + 1}`,
          flow, repeat, turn: index + 1, status: passed ? 'PASS' : 'FAIL',
          executedSearchCount: executed.length, appliedQuery: applied?.query ?? null,
          appliedMaximumPrice: applied?.maximumPrice ?? null,
          appliedCurrency: applied?.currency ?? null,
          reason: passed ? null : !terminal ? 'NO_TERMINAL_RESPONSE'
            : executed.length !== 1 ? 'NO_SINGLE_EXECUTED_SEARCH'
            : !hasMonitor || !sizeMatches ? 'QUERY_MISMATCH'
            : !priceMatches ? 'PRICE_MISMATCH' : 'PENDING_OR_PERSISTENCE_MISMATCH',
        });
        if (!passed) { completed = false; break; }
      }
      if (completed) passedRuns++;
    }
    results.push({ scenario: `ABC ${flow} summary`, flow,
      status: passedRuns === 5 ? 'PASS' : 'FAIL', passedRuns });
  }
}

async function runContextFinalMatrix(token) {
  // Every check compares a new Agent-owned search observation for this turn.
  const flows = [
    ['matrix-1', ['monitor', '27 inch', 'under $300', 'actually 32 inch'],
      [/\bmonitors?\b/i, /\bmonitors?\b.*\b27[\s-]*inch\b|\b27[\s-]*inch\b.*\bmonitors?\b/i,
        /\bmonitors?\b.*\b27[\s-]*inch\b|\b27[\s-]*inch\b.*\bmonitors?\b/i,
        /\bmonitors?\b.*\b32[\s-]*inch\b|\b32[\s-]*inch\b.*\bmonitors?\b/i],
      [null, null, 300, 300], [/\b27[\s-]*inch\b/i]],
    ['matrix-2', ['monitor under $300', '27 inch'],
      [/\bmonitors?\b/i, /\bmonitors?\b.*\b27[\s-]*inch\b|\b27[\s-]*inch\b.*\bmonitors?\b/i],
      [300, 300], []],
    ['matrix-3', ['RC Delivered Return Fixture Harbor Cart Supply',
      'Search just RC Delivered Return Fixture'],
      [/\bRC Delivered Return Fixture\b/i, /^RC Delivered Return Fixture$/i],
      [null, null], [/\bHarbor\b|\bCart\b|\bSupply\b/i]],
    ['matrix-4', ['Dell monitor under $300', 'Search just monitor'],
      [/\bDell\b.*\bmonitor\b/i, /^monitor$/i], [300, 300], [/\bDell\b/i]],
  ];
  for (const [flow, turns, queries, prices, forbidden] of flows) {
    if (contextFinalOnly && contextFinalOnly !== flow) continue;
    let passedRuns = 0;
    const repeatLimit = process.argv.includes('--context-final-once') ? 1 : 5;
    for (let repeat = 1; repeat <= repeatLimit; repeat++) {
      const session = await createSession(token);
      let allPassed = true;
      for (let index = 0; index < turns.length; index++) {
        const before = safeSearchObservations(session);
        const terminal = await sendStream(`${flow}-${repeat} turn-${index + 1}`,
          token, session, turns[index]);
        const turn = results.at(-1);
        const newObservations = safeSearchObservations(session).slice(before.length);
        const executed = newObservations.filter(item => item.status === 'SUCCEEDED' && item.query);
        const applied = executed.at(-1) ?? null;
        const query = applied?.query ?? '';
        const forbiddenPresent = index === turns.length - 1 && forbidden.some(re => re.test(query));
        const priceMatches = prices[index] === null ? applied?.maximumPrice == null :
          Number(applied?.maximumPrice) === prices[index] && applied?.currency === 'USD';
        const passed = Boolean(terminal && turn?.status === 'PASS' && turn.decisionCount <= 5 &&
          executed.length === 1 && queries[index].test(query) && priceMatches &&
          !forbiddenPresent && !turn.pendingType &&
          !turn.toolActivity?.some(item => item.tool === 'request_confirmation'));
        results.push({ scenario: `${flow}-${repeat} check-${index + 1}`, flow, repeat,
          turn: index + 1, status: passed ? 'PASS' : 'FAIL',
          executedSearchCount: executed.length, appliedQuery: applied?.query ?? null,
          appliedMaximumPrice: applied?.maximumPrice ?? null,
          appliedCurrency: applied?.currency ?? null, appliedCondition: applied?.condition ?? null,
          decisionCount: turn?.decisionCount ?? null, pendingType: turn?.pendingType ?? null,
          reason: passed ? null : 'EXECUTED_SEARCH_OR_CONTEXT_MISMATCH' });
        if (!passed) { allPassed = false; break; }
      }
      if (allPassed) passedRuns++;
    }
    results.push({ scenario: `${flow} summary`, flow,
      status: passedRuns === repeatLimit ? 'PASS' : 'FAIL', passedRuns });
  }
}

async function runRefine01EAcceptance(tokenA, tokenB, actorA) {
  if (refine01eGroup && !['bare', 'refinement', 'long', 'isolation', 'controls']
    .includes(refine01eGroup)) {
    throw new Error('Unknown REFINE-FIX-01E acceptance group');
  }
  async function check(label, token, session, input, expectedQuery, options = {}) {
    const before = safeSearchObservations(session);
    await sendStream(label, token, session, input, { longSession: options.longSession });
    const turn = results.at(-1);
    const searches = safeSearchObservations(session).slice(before.length)
      .filter(item => item.status === 'SUCCEEDED' && item.query);
    const applied = searches.at(-1);
    const passed = turn?.status === 'PASS' && turn.decisionCount <= 5 &&
      !/official marketplace document/i.test(turn.visibleText ?? '') &&
      (expectedQuery === null ? searches.length === 0 :
        searches.length === 1 && expectedQuery.test(applied?.query ?? '')) &&
      (options.maximumPrice === undefined ||
        Number(applied?.maximumPrice) === options.maximumPrice) &&
      (options.forbidden === undefined || !options.forbidden.test(applied?.query ?? ''));
    results.push({ scenario: `${label} check`, status: passed ? 'PASS' : 'FAIL',
      executedSearchCount: searches.length, appliedQuery: applied?.query ?? null,
      appliedMaximumPrice: applied?.maximumPrice ?? null,
      decisionCount: turn?.decisionCount ?? null,
      reason: passed ? null : 'BARE_QUERY_OR_REFINEMENT_MISMATCH' });
    return passed;
  }

  if (!refine01eGroup || refine01eGroup === 'bare') for (const [label, input, query] of [
    ['bare keyboard', 'red Logitech keyboard', /red.*Logitech.*keyboard/i],
    ['bare monitor', '27 inch monitor', /27[\s-]*inch.*monitor/i],
    ['bare mouse', 'wireless mouse', /wireless.*mouse/i],
    ['bare fixture', 'RC Delivered Return Fixture', /^RC Delivered Return Fixture$/i],
  ]) {
    await check(label, tokenA, await createSession(tokenA), input, query);
  }

  if (!refine01eGroup || refine01eGroup === 'refinement') for (const [label, turns] of [
    ['laptop refinement', [
      ['laptop', /laptop/i, {}],
      ['under $1000', /laptop/i, { maximumPrice: 1000 }],
      ['16GB RAM', /laptop.*16\s*GB|16\s*GB.*laptop/i, { maximumPrice: 1000 }],
    ]],
    ['keyboard refinement', [
      ['keyboard under $100', /keyboard/i, { maximumPrice: 100 }],
      ['wireless only', /keyboard.*wireless|wireless.*keyboard/i, { maximumPrice: 100 }],
      ['NO RGB', /no\s*rgb/i, { maximumPrice: 100 }],
    ]],
    ['query replacement', [
      ['Dell monitor under $300', /Dell.*monitor/i, { maximumPrice: 300 }],
      ['Search just monitor', /^monitor$/i,
        { maximumPrice: 300, forbidden: /Dell/i }],
    ]],
  ]) {
    const session = await createSession(tokenA);
    for (const [index, [input, query, options]] of turns.entries()) {
      if (!await check(`${label} turn-${index + 1}`, tokenA, session,
        input, query, options)) break;
    }
  }

  if (!refine01eGroup || refine01eGroup === 'long') {
  const longSession = await createSession(tokenA);
  seedLongFiller(actorA, longSession);
  await check('long bare Dell', tokenA, longSession, 'Dell monitor',
    /Dell.*monitor/i, { longSession: true });
  await check('long remove Dell', tokenA, longSession, 'Search just monitor',
    /^monitor$/i, { longSession: true, forbidden: /Dell/i });
  results.push({ scenario: 'long row count', status:
    Number(sql(`SELECT COUNT(*) FROM agent.agent_messages WHERE session_id=${q(longSession)}`)) > 100
      ? 'PASS' : 'FAIL' });
  }

  if (!refine01eGroup || refine01eGroup === 'isolation') {
  const sessionA = await createSession(tokenA);
  await check('isolation A', tokenA, sessionA,
    'RC Delivered Return Fixture Harbor Cart Supply', /RC Delivered Return Fixture/i);
  const sessionB = await createSession(tokenB);
  await check('isolation B', tokenB, sessionB, 'Dell monitor',
    /Dell.*monitor/i, { forbidden: /Harbor|Cart|Supply/i });
  const separateA = await createSession(tokenA);
  await check('isolation same actor other session', tokenA, separateA,
    'wireless mouse', /wireless.*mouse/i, { forbidden: /Harbor|Cart|Supply/i });
  await probeIsolation(tokenB, sessionA);
  }

  if (!refine01eGroup || refine01eGroup === 'controls') {
  for (const [label, input] of [
    ['help returns', 'How do returns work?'],
    ['help cart', 'How do I add an item to my cart?'],
    ['help verified', 'What does verified business mean?'],
    ['out of scope capital', "What's the capital of France?"],
    ['out of scope joke', 'Tell me a joke.'],
    ['ambiguous returns', 'returns'],
    ['ambiguous seller', 'seller'],
    ['ambiguous shipping', 'shipping'],
  ]) {
    await check(label, tokenA, await createSession(tokenA), input, null);
  }
  }
}

async function runContextFinalGroup(group, tokenA, tokenB, actorA) {
  const flows = {
    natural: [
      ['A1-A4', ['Show me laptops.', 'something under a grand',
        'nah 1500 is fine', '16 gigs minimum', 'new only']],
      ['A5', ['Search for white wireless keyboard', 'dont care about color anymore']],
      ['B2', ['Search for RC Delivered Return Fixture Harbor Cart Supply',
        'forget Harbor Cart Supply, just RC Delivered Return Fixture']],
      ['B3', ['Search for red Logitech keyboard under $100', 'just red keyboard now']],
      ['B4', ['Search for Dell monitor under $300', 'same filters, just monitor']],
      ['C2', ['Show me monitors.', 'make it 27 inch', 'below 300', 'wait, 32 actually']],
      ['C3', ['Show me keyboards under $100.', 'wireless', 'no rgb tho']],
      ['D1', ['Show me laptops under $1000.', 'actually scratch that, show me keyboards']],
      ['D2', ['Show me monitors under $300.', 'same budget, show me keyboards']],
    ],
    references: [
      ['E1-E2', ['Find me mechanical keyboards.', 'the second one', 'tell me more about #2']],
      ['E3', ['Search for RC Delivered Return Fixture', 'the second one']],
      ['E4-exact', ['Show me 3 mechanical keyboards.', 'the fifth one']],
      ['E4-variant', ['Find me mechanical keyboards.', 'the sixth one']],
      ['E5', ['Find me mechanical keyboards.', 'Show me 2 mechanical keyboards.',
        'the third one']],
      ['F1', ['Show me mouse pads.', 'Tell me about the second one.', 'Add it to my cart.']],
      ['F2', ['Show me mouse pads.', 'Tell me about the second one.',
        'Show me keyboards.', 'Add it to my cart.']],
      ['G1', ['Find me wireless mice.', "What's the capital of France?",
        'back to the mouse, second one']],
      ['G2', ['Show me keyboards under $100.', "What's 2+2?", 'Tell me a joke.',
        'anyway, wireless only']],
      ['G-valid-ordinal', ['Find me mechanical keyboards.', "What's the capital of France?",
        'back to the keyboard, second one']],
      ['H1', ['Show me monitors.', 'make it better']],
      ['H2', ['Show me monitors.', 'cheaper']],
      ['H3', ['Find me mechanical keyboards.', 'the other one']],
      ['H4', ['Find me mechanical keyboards.', 'same one but better']],
    ],
    noisy: [
      ['I1', ['show me laptps']],
      ['I2', ['Show me laptops.', 'undr 1000']],
      ['I3', ['moniter 27 inch']],
      ['I4', ['Search for RC Delivered Return Fixture Harbor Cart Supply',
        'search jus RC Delivered Return Fixture']],
      ['J1', ['Show me monitors.', 'UNDER $300!!!']],
      ['J2', ['Show me keyboards under $100.', 'NO RGB']],
      ['J3', ['Show me monitors.', '27 inch', 'actually... 32 inch']],
      ['K1', ['Show me monitors.', 'under $300', 'under $300']],
      ['K2', ['Show me keyboards.', 'wireless only', 'wireless only']],
      ['K3', ['Show me monitors.', '32 inch', 'actually 32 inch']],
      ['L1', ['Show me monitors.', 'under $300', 'actually under $500']],
      ['L2', ['Show me new monitors.', 'used is okay actually']],
      ['L3', ['Show me wireless keyboards.', 'wired is fine too']],
      ['M1', ['Search for red wireless Logitech keyboard',
        'Search just wireless keyboard']],
      ['M2', ['Search for RC Delivered Return Fixture Harbor Cart Supply',
        "uh yeah so actually can you just search for the RC Delivered Return Fixture now, don't include the store name"]],
      ['M3', ['27 inch—actually 32 inch monitor']],
      ['M4', ['Show me monitors.', 'under $300, actually no under $500']],
    ],
    persistence: [
      ['N1', ['Dell monitor under $300', 'Search just monitor']],
      ['N2', ['monitor under $300', '27 inch']],
    ],
  };
  if (flows[group]) {
    for (const [flow, turns] of flows[group]) {
      if (contextFinalOnly && contextFinalOnly !== flow) continue;
      console.error(`PROGRESS ${group} ${flow}`);
      await probeFlow(flow, tokenA, turns, group === 'persistence');
    }
    return;
  }
  if (group === 'long') {
    for (const [flow, first, second] of [
      ['O-attribute', 'monitor under $300', '27 inch'],
      ['O-replacement', 'Dell monitor under $300', 'Search just monitor'],
    ]) {
      const session = await createSession(tokenA);
      seedLongFiller(actorA, session);
      await probeTurn(`${flow}-baseline`, tokenA, session, first, true);
      await probeTurn(`${flow}-refinement`, tokenA, session, second, true);
      results.push({ scenario: `${flow} row-count`, status: 'CHECKED',
        reason: `rows=${sql(`SELECT COUNT(*) FROM agent.agent_messages WHERE session_id=${q(session)}`)}` });
    }
    return;
  }
  if (group === 'isolation') {
    const sessionA = await createSession(tokenA);
    await probeTurn('P A', tokenA, sessionA, 'Dell monitor under $300');
    const sessionB = await createSession(tokenB);
    const sessionA2 = await createSession(tokenA);
    await probeTurn('P B', tokenB, sessionB, 'monitor');
    await probeTurn('P same actor other session', tokenA, sessionA2, 'monitor');
    await probeIsolation(tokenB, sessionA);
    return;
  }
  throw new Error(`Unknown context-final group ${group}`);
}

async function probeFlow(flow, token, turns, reload = false) {
  const session = await createSession(token);
  for (let index = 0; index < turns.length; index++) {
    if (reload && index === 1) {
      await appApi(`${AGENT}/api/v1/agent/marketplace-v2/sessions/${session}/messages`, token);
    }
    await probeTurn(`${flow} turn-${index + 1}`, token, session, turns[index]);
  }
}

async function probeTurn(scenario, token, session, input, longSession = false) {
  const before = safeSearchObservations(session);
  await sendStream(scenario, token, session, input, { longSession });
  const turn = results.at(-1);
  const newObservations = safeSearchObservations(session).slice(before.length);
  const searches = newObservations.filter(item => item.status === 'SUCCEEDED' && item.query);
  results.push({ scenario: `${scenario} evidence`, status: turn?.status === 'PASS' ? 'CHECKED' : 'FAIL',
    executedSearchCount: searches.length,
    appliedQuery: searches.at(-1)?.query ?? null,
    appliedMaximumPrice: searches.at(-1)?.maximumPrice ?? null,
    appliedCurrency: searches.at(-1)?.currency ?? null,
    appliedCondition: searches.at(-1)?.condition ?? null,
    decisionCount: turn?.decisionCount ?? null,
    attachmentCount: turn?.attachmentCount ?? null,
    listingIds: turn?.listingIds ?? [],
    pendingType: turn?.pendingType ?? null,
    reason: newObservations.filter(item => item.status !== 'SUCCEEDED')
      .map(item => `${item.status}:${item.reason ?? ''}:${item.refinementMismatch ?? ''}`).join('|') || null });
}

function seedLongFiller(actorId, sessionId) {
  for (const value of [actorId, sessionId]) {
    if (!/^[0-9A-HJKMNP-TV-Z]{26}$/.test(value)) throw new Error('Invalid fixture ID');
  }
  const base = Date.now() - 600_000;
  for (let start = 0; start < 110; start += 20) {
    const rows = Array.from({ length: Math.min(20, 110 - start) }, (_, offset) => {
      const index = start + offset;
      const user = index % 2 === 0;
      const when = new Date(base + index * 1000).toISOString().replace('T', ' ').slice(0, 23);
      return `(${q(ulid())},${q(sessionId)},${q(actorId)},${q(user ? 'USER' : 'ASSISTANT')},` +
        `${q(`ctx01-final-filler-${index}`)},${user ? 'NULL' : q('ANSWERED')},` +
        `${user ? 'NULL' : q('[]')},${user ? 'NULL' : q('[]')},${q(when)})`;
    });
    sql('INSERT INTO agent.agent_messages ' +
      '(message_id,session_id,actor_user_id,role,body,resolution_type,sources_json,actions_json,created_at) VALUES ' +
      rows.join(','));
  }
}

async function runRefine01B1Smoke(token) {
  const flows = [
    ['laptop', 'Show me laptops under $1000.', 'At least 16GB RAM.',
      /\blaptops?\b/i, /\b16\s*gb\b/i, 1000, null],
    ['keyboard', 'Show me keyboards under $100.', 'Wireless only.',
      /\bkeyboards?\b/i, /\bwireless\b/i, 100, null],
    ['condition', 'Show me new monitors under $300.', '27 inch.',
      /\bmonitors?\b/i, /\b27[\s-]*inch\b/i, 300, 'NEW'],
  ];
  for (const [flow, first, second, core, attribute, maximum, condition] of flows) {
    const session = await createSession(token);
    const baseline = await sendStream(`smoke ${flow} baseline`, token, session, first);
    const before = safeSearchObservations(session);
    if (!baseline || before.length !== 1 || before[0].status !== 'SUCCEEDED') {
      results.push({ scenario: `smoke ${flow} check`, status: 'FAIL',
        reason: 'NO_EXECUTED_BASELINE' });
      continue;
    }
    const terminal = await sendStream(`smoke ${flow} refinement`, token, session, second);
    const turn = results.at(-1);
    const executed = safeSearchObservations(session).slice(before.length)
      .filter(item => item.status === 'SUCCEEDED' && item.query);
    const applied = executed.at(-1) ?? null;
    const pass = Boolean(terminal && turn?.status === 'PASS' && executed.length === 1
      && core.test(applied?.query ?? '') && attribute.test(applied?.query ?? '')
      && Number(applied?.maximumPrice) === maximum && applied?.currency === 'USD'
      && applied?.condition === condition && !turn.pendingType);
    results.push({ scenario: `smoke ${flow} check`, status: pass ? 'PASS' : 'FAIL',
      executedSearchCount: executed.length, appliedQuery: applied?.query ?? null,
      appliedMaximumPrice: applied?.maximumPrice ?? null,
      appliedCurrency: applied?.currency ?? null,
      appliedCondition: applied?.condition ?? null,
      reason: pass ? null : 'EXECUTED_SEARCH_OR_PERSISTENCE_MISMATCH' });
  }
}

async function runRefine01D1Acceptance(token) {
  const flows = [
    ['replacement', 5, 'Search for RC Delivered Return Fixture Harbor Cart Supply',
      'Search just RC Delivered Return Fixture',
      /^RC Delivered Return Fixture$/i, null],
    ['brand-smoke', 1, 'Search for red Logitech keyboard',
      'Search just red keyboard', /^red keyboard$/i, null],
    ['price-smoke', 1, 'Search for Dell monitor under $300',
      'Search just monitor', /^monitor$/i, 300],
  ];
  for (const [flow, runs, first, second, expectedQuery, expectedMaximum] of flows) {
    if (process.argv.includes('--refine-01d1-price') && flow !== 'price-smoke') continue;
    const targetRuns = flow === 'price-smoke' && process.argv.includes('--refine-01d1-price-5')
      ? 5 : runs;
    let passedRuns = 0;
    for (let repeat = 1; repeat <= targetRuns; repeat++) {
      const session = await createSession(token);
      const baseline = await sendStream(`01D1 ${flow}-${repeat} baseline`, token, session, first);
      const baselineResult = results.at(-1);
      const baselineSearches = safeSearchObservations(session)
        .filter(item => item.status === 'SUCCEEDED' && item.query);
      if (!baseline || baselineResult?.status !== 'PASS' || baselineSearches.length !== 1) {
        results.push({ scenario: `01D1 ${flow}-${repeat} check`, flow, repeat,
          status: 'FAIL', reason: 'NO_EXECUTED_BASELINE',
          executedSearchCount: baselineSearches.length });
        continue;
      }
      const terminal = await sendStream(`01D1 ${flow}-${repeat} replacement`,
        token, session, second);
      const turn = results.at(-1);
      const newObservations = safeSearchObservations(session).slice(baselineSearches.length);
      const searches = newObservations
        .filter(item => item.status === 'SUCCEEDED' && item.query);
      const applied = searches.at(-1) ?? null;
      const pass = Boolean(terminal && turn?.status === 'PASS'
        && turn.decisionCount <= 5 && searches.length === 1
        && expectedQuery.test(applied?.query ?? '')
        && (expectedMaximum === null || (
          Number(applied?.maximumPrice) === expectedMaximum
          && applied?.currency === 'USD'
        ))
        && !turn.pendingType
        && !turn.toolActivity?.some(item => item.tool === 'request_confirmation'));
      if (pass) passedRuns++;
      results.push({ scenario: `01D1 ${flow}-${repeat} check`, flow, repeat,
        status: pass ? 'PASS' : 'FAIL', executedSearchCount: searches.length,
        appliedQuery: applied?.query ?? null,
        appliedMaximumPrice: applied?.maximumPrice ?? null,
        appliedCurrency: applied?.currency ?? null,
        baselineQuery: baselineSearches[0]?.query ?? null,
        baselineMaximumPrice: baselineSearches[0]?.maximumPrice ?? null,
        rejectionMismatches: newObservations.filter(item => item.status === 'REJECTED')
          .map(item => item.refinementMismatch),
        decisionCount: turn?.decisionCount ?? null, pendingType: turn?.pendingType ?? null,
        reason: pass ? null : 'REPLACEMENT_OR_PERSISTENCE_MISMATCH' });
    }
    results.push({ scenario: `01D1 ${flow} summary`, flow,
      status: passedRuns === targetRuns ? 'PASS' : 'FAIL', passedRuns });
  }
}

async function runRefine01DAcceptance(token) {
  const flows = [
    ['fixture', ['Search for RC Delivered Return Fixture Harbor Cart Supply',
      'RC Delivered Return Fixture'], /\bHarbor\b/i, /\bRC Delivered Return Fixture\b/i,
      /\bHarbor\b|\bCart\b|\bSupply\b/i, null],
    ['brand', ['red Logitech keyboard', 'red keyboard'], /\bLogitech\b/i,
      /\bred keyboard\b/i, /\bLogitech\b/i, null],
    ['price', ['Dell monitor under $300', 'monitor'], /\bDell\b/i,
      /^monitor$/i, /\bDell\b/i, 300],
    ['ordinary', ['monitor', '27 inch', 'under $300', 'actually 32 inch'],
      /\bmonitor\b/i, /\b32[\s-]*inch\b.*\bmonitor\b|\bmonitor\b.*\b32[\s-]*inch\b/i,
      /\b27[\s-]*inch\b/i, 300],
  ];
  for (const [flow, turns, initialTerm, finalQuery, excluded, finalMaximum] of flows) {
    if (process.argv.includes('--refine-01d-price') && flow !== 'price') continue;
    if (process.argv.includes('--refine-01d-affected') &&
        !['fixture', 'price'].includes(flow)) continue;
    const session = await createSession(token);
    let completed = true;
    for (let index = 0; index < turns.length; index++) {
      const before = safeSearchObservations(session);
      const terminal = await sendStream(`01D ${flow} turn-${index + 1}`,
        token, session, turns[index]);
      const turnResult = results.at(-1);
      const newObservations = safeSearchObservations(session).slice(before.length);
      const executed = newObservations
        .filter(item => item.status === 'SUCCEEDED' && item.query);
      const applied = executed.at(-1) ?? null;
      const query = applied?.query ?? '';
      const final = index === turns.length - 1;
      const validQuery = index === 0 ? initialTerm.test(query) :
        (!final || (finalQuery.test(query) && !excluded.test(query)));
      const validPrice = !final || finalMaximum === null ||
        (Number(applied?.maximumPrice) === finalMaximum && applied?.currency === 'USD');
      const passed = Boolean(terminal && turnResult?.status === 'PASS'
        && turnResult.decisionCount <= 5 && executed.length === 1
        && validQuery && validPrice && !turnResult.pendingType
        && !turnResult.toolActivity?.some(item => item.tool === 'request_confirmation'));
      results.push({ scenario: `01D ${flow} check-${index + 1}`,
        flow, turn: index + 1, status: passed ? 'PASS' : 'FAIL',
        executedSearchCount: executed.length, appliedQuery: applied?.query ?? null,
        appliedMaximumPrice: applied?.maximumPrice ?? null,
        appliedCurrency: applied?.currency ?? null, decisionCount: turnResult.decisionCount,
        pendingType: turnResult.pendingType,
        searchObservations: newObservations.filter(item => item.status !== 'SUCCEEDED')
          .map(item => ({ status: item.status, reason: item.reason,
            refinementMismatch: item.refinementMismatch })),
        reason: passed ? null : 'LIVE_REPLACEMENT_OR_SEARCH_MISMATCH' });
      if (!passed) { completed = false; break; }
    }
    results.push({ scenario: `01D ${flow} summary`, flow,
      status: completed ? 'PASS' : 'FAIL' });
  }
}

async function runRefine01CAcceptance(token) {
  // Fresh sessions and one authoritative search per unambiguous turn.
  const flows = [
    ['laptop-ram', ['Show me laptops.', 'Under $1000.', 'At least 16GB RAM.'],
      /\blaptops?\b/i, /\b16\s*gb\b/i, 1000],
    ['price-correction', ['Show me laptops under $1000.',
      'Actually make it under $1500.'], /\blaptops?\b/i, null, 1500],
    ['keyboard-chain', ['Show me keyboards.', 'Wireless only.',
      'Under $100.', 'No RGB.'], /\bkeyboards?\b/i, /\bno\s+rgb\b/i, 100],
    ['monitor-chain', ['Show me monitors.', '27 inch.',
      'Under $300.', 'Actually 32 inch.'], /\bmonitors?\b/i,
      /\b32[\s-]*inch\b/i, 300],
  ];
  for (const [flow, turns, core, finalAttribute, finalMaximum] of flows) {
    let passedRuns = 0;
    for (let repeat = 1; repeat <= 2; repeat++) {
      const session = await createSession(token);
      let completed = true;
      for (let index = 0; index < turns.length; index++) {
        const before = safeSearchObservations(session);
        const terminal = await sendStream(
          `01C ${flow}-${repeat} turn-${index + 1}`, token, session, turns[index]);
        const turnResult = results.at(-1);
        const executed = safeSearchObservations(session).slice(before.length)
          .filter(item => item.status === 'SUCCEEDED' && item.query);
        const applied = executed.at(-1) ?? null;
        const final = index === turns.length - 1;
        const query = applied?.query ?? '';
        const passed = Boolean(terminal && turnResult?.status === 'PASS'
          && turnResult.decisionCount <= 5 && executed.length === 1
          && core.test(query) && !turnResult.pendingType
          && !/would you like|do you want|permission|proceed/i.test(
            turnResult.visibleText ?? '')
          && (!final || (finalAttribute?.test(query) !== false
            && Number(applied?.maximumPrice) === finalMaximum
            && applied?.currency === 'USD')));
        results.push({ scenario: `01C ${flow}-${repeat} check-${index + 1}`,
          flow, repeat, turn: index + 1, status: passed ? 'PASS' : 'FAIL',
          executedSearchCount: executed.length, appliedQuery: applied?.query ?? null,
          appliedMaximumPrice: applied?.maximumPrice ?? null,
          appliedCurrency: applied?.currency ?? null,
          reason: passed ? null : 'TERMINAL_SEARCH_OR_PERSISTENCE_MISMATCH' });
        if (!passed) { completed = false; break; }
      }
      if (completed) passedRuns++;
    }
    results.push({ scenario: `01C ${flow} summary`, flow,
      status: passedRuns === 2 ? 'PASS' : 'FAIL', passedRuns });
  }
}

function safeSearchObservations(sessionId) {
  // Read only Agent-owned actions for this disposable session, then print a
  // narrow allowlist. Never print raw JSON, provider output, or credentials.
  const rows = sql(
    `SELECT HEX(actions_json) FROM agent.agent_messages WHERE session_id=${q(sessionId)} ` +
    `AND role='ASSISTANT' AND actions_json IS NOT NULL ORDER BY created_at,message_id`,
  );
  return rows.split('\n').filter(Boolean).flatMap(hex => {
    const actions = JSON.parse(Buffer.from(hex.trim(), 'hex').toString('utf8'));
    return actions.filter(action => action.type === 'MARKETPLACE_AGENT_V2_OBSERVATION' &&
      action.tool === 'search_listings').map(action => ({
        status: action.status, reason: action.reason,
      refinementMismatch: action.refinementRepair?.mismatch ?? null,
        ...(action.appliedSearch ? {
          query: action.appliedSearch.query,
          categoryName: action.appliedSearch.categoryName ?? null,
          condition: action.appliedSearch.condition ?? null,
          minimumPrice: action.appliedSearch.minimumPrice ?? null,
          maximumPrice: action.appliedSearch.maximumPrice ?? null,
          currency: action.appliedSearch.currency ?? null,
          city: action.appliedSearch.city ?? null,
          county: action.appliedSearch.county ?? null,
          resultsDisplayed: action.appliedSearch.resultsDisplayed,
          fresh: Date.parse(action.appliedSearch.expiresAt) > Date.now(),
        } : {}),
      }));
  });
}

async function runCartFlow(token, sessionId) {
  const productSearch = await fetch(
    'http://127.0.0.1:8091/api/v1/public/stores/listings/search?q=RC%20Delivered%20Return%20Fixture&limit=10',
    { signal: AbortSignal.timeout(10_000) },
  );
  if (!productSearch.ok) throw new Error('Dedicated Product fixture search unavailable');
  const matches = (await productSearch.json()).data ?? [];
  const fixture = matches.find(item => item.title === 'RC Delivered Return Fixture');
  if (!fixture?.id) throw new Error('Dedicated Product fixture listing missing');
  const detailResponse = await fetch(
    `http://127.0.0.1:8091/api/v1/public/listings/${fixture.id}`,
    { signal: AbortSignal.timeout(10_000) },
  );
  if (!detailResponse.ok) throw new Error('Dedicated Product fixture detail unavailable');
  const detail = await detailResponse.json();
  if (detail.sellerType !== 'BUSINESS' || detail.businessVerified !== true) {
    throw new Error('Dedicated Product fixture is not verified business inventory');
  }
  const search = await sendStream('F: fixture discovery', token, sessionId,
    'Find the RC Delivered Return Fixture listing.');
  const index = search?.message?.attachments?.findIndex(item => item.listingId === fixture.id) ?? -1;
  if (index < 0) {
    results.push({ scenario: 'F: cart mutation sequence', status: 'NOT_RUN',
      reason: 'Agent did not display the dedicated verified business fixture.' });
    return;
  }
  const ordinal = ['first', 'second', 'third', 'fourth', 'fifth'][index];
  await sendStream('F: add', token, sessionId, `Add the ${ordinal} one to my cart.`);
  const afterAddRaw = await appApi(`${ORDER}/api/v1/cart`, token);
  const afterAdd = afterAddRaw.data ?? afterAddRaw;
  const addedItem = afterAdd?.items?.find(item => item.listingId === fixture.id);
  results.push({ scenario: 'F: cart after add', status: addedItem?.quantity === 1 ? 'PASS' : 'FAIL',
    itemCount: afterAdd?.itemCount ?? null, quantity: addedItem?.quantity ?? null,
    expectedListingPresent: Boolean(addedItem) });
  if (!addedItem) return;
  await sendStream('F: quantity', token, sessionId, 'Make it quantity 2.');
  const afterQuantityRaw = await appApi(`${ORDER}/api/v1/cart`, token);
  const afterQuantity = afterQuantityRaw.data ?? afterQuantityRaw;
  const updatedItem = afterQuantity?.items?.find(item => item.listingId === fixture.id);
  results.push({ scenario: 'F: cart after quantity', status: updatedItem?.quantity === 2 ? 'PASS' : 'FAIL',
    itemCount: afterQuantity?.itemCount ?? null, quantity: updatedItem?.quantity ?? null });
  await sendStream('F: remove', token, sessionId, 'Remove it.');
  const afterRemoveRaw = await appApi(`${ORDER}/api/v1/cart`, token);
  const afterRemove = afterRemoveRaw.data ?? afterRemoveRaw;
  results.push({ scenario: 'F: cart after remove',
    status: !afterRemove?.items?.some(item => item.listingId === fixture.id) ? 'PASS' : 'FAIL',
    itemCount: afterRemove?.itemCount ?? null,
    fixturePresent: Boolean(afterRemove?.items?.some(item => item.listingId === fixture.id)) });
}

async function runStopRetry(token) {
  const sessionId = await createSession(token);
  const clientMessageId = ulid();
  const url = `${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionId}/messages`;
  const stream = await fetch(`${url}/stream`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json' },
    body: JSON.stringify({ clientMessageId, body: 'Find me mechanical keyboards.' }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!stream.ok) throw new Error(`Stop fixture stream rejected (${stream.status})`);
  const reader = stream.body.getReader();
  const decoder = new TextDecoder();
  let firstEvent = null;
  let pending = '';
  for (let index = 0; index < 20 && !firstEvent; index++) {
    const chunk = await reader.read();
    if (chunk.done) break;
    pending += decoder.decode(chunk.value);
    const blockEnd = pending.indexOf('\n\n');
    if (blockEnd < 0) continue;
    const data = pending.slice(0, blockEnd).split(/\r?\n/).find(line => line.startsWith('data: '));
    if (data) firstEvent = JSON.parse(data.slice(6));
  }
  const stopResponse = await fetch(`${url}/${clientMessageId}/stop`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(30_000),
  });
  const stopped = stopResponse.ok ? await stopResponse.json() : null;
  await reader.cancel().catch(() => {});
  const history = await appApi(url, token);
  const user = history.data.find(item => item.clientMessageId === clientMessageId);
  const retryableAssistant = history.data.find(item => item.role === 'ASSISTANT' &&
    item.responseRetryUserMessageId === user?.id && item.retryable);
  results.push({ scenario: 'M: stop', status: stopped?.outcome === 'STOPPED' && user ? 'PASS' : 'INCONCLUSIVE',
    firstEventType: firstEvent?.type ?? null,
    stopHttpStatus: stopResponse.status, outcome: stopped?.outcome ?? null,
    committedUserCount: history.data.filter(item => item.clientMessageId === clientMessageId).length,
    retryable: Boolean(user?.retryable || retryableAssistant) });
  if (stopped?.outcome !== 'STOPPED' || !user || !(user.retryable || retryableAssistant)) {
    results.push({ scenario: 'N: response-only retry', status: 'NOT_RUN',
      reason: 'Stop did not leave a retryable committed user row.' });
    return;
  }
  const retry = await fetch(`${url}/${user.id}/response-retry/stream`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json' },
    body: JSON.stringify({ clientMessageId }), signal: AbortSignal.timeout(120_000),
  });
  const raw = await retry.text();
  const events = raw.split(/\r?\n\r?\n/).flatMap(block => {
    const data = block.split(/\r?\n/).find(line => line.startsWith('data: '));
    return data ? [JSON.parse(data.slice(6))] : [];
  });
  const done = events.find(item => item.type === 'done');
  const after = await appApi(url, token);
  results.push({ scenario: 'N: response-only retry',
    status: done && after.data.filter(item => item.clientMessageId === clientMessageId).length === 1 ? 'PASS' : 'FAIL',
    retryHttpStatus: retry.status,
    eventTypes: events.map(item => item.type).filter((type, index, all) =>
      type !== 'text_delta' || index === 0 || all[index - 1] !== 'text_delta'),
    userCount: after.data.filter(item => item.clientMessageId === clientMessageId).length,
    assistantCount: after.data.filter(item => item.role === 'ASSISTANT').length,
    retryableCount: after.data.filter(item => item.retryable).length,
  });
  await sendStream('M: subsequent turn after stop/retry', token, sessionId,
    'Tell me about the second one.');
}

async function createBuyer(label) {
  const email = `ctx01-rc-${runId}-${label}@msb.local`;
  const password = randomBytes(32).toString('base64url') + '!7aA';
  await admin(`/admin/realms/${REALM}/users`, {
    method: 'POST', expected: [201], body: {
      username: email, email, enabled: true, emailVerified: true,
      firstName: 'CTX01', lastName: `RC-${label}`,
      attributes: { displayName: [`CTX01 RC ${label}`] },
      credentials: [{ type: 'password', value: password, temporary: false }],
    },
  });
  const users = await admin(`/admin/realms/${REALM}/users?username=${encodeURIComponent(email)}&exact=true`);
  const user = users.find(item => item.username === email);
  if (!user?.id) throw new Error(`Created fixture ${label} cannot be resolved`);
  created.push(user.id);
  const roles = await admin(`/admin/realms/${REALM}/users/${user.id}/role-mappings/realm/composite`);
  if (!roles.some(item => item.name === 'BUYER') ||
      roles.some(item => ['ADMIN', 'BUSINESS_USER', 'INDIVIDUAL_SELLER'].includes(item.name))) {
    throw new Error(`Fixture ${label} did not receive buyer-only roles`);
  }
  const token = await keycloakToken({
    grant_type: 'password', client_id: 'msb-marketplace',
    client_secret: process.env.KEYCLOAK_MARKETPLACE_CLIENT_SECRET,
    username: email, password,
  });
  return { token };
}

async function keycloakToken(fields) {
  const response = await fetch(`${KC}/realms/${REALM}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields),
  });
  if (!response.ok) throw new Error(`Local Keycloak token request failed (${response.status})`);
  const token = (await response.json()).access_token;
  if (!token) throw new Error('Local Keycloak token missing');
  return token;
}

async function admin(path, options = {}) {
  const response = await fetch(`${KC}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${adminToken}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!(options.expected ?? [200]).includes(response.status)) {
    await response.body?.cancel();
    throw new Error(`Local fixture admin ${options.method ?? 'GET'} failed (${response.status})`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function appApi(url, token, options = {}) {
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: { Authorization: `Bearer ${token}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: AbortSignal.timeout(120_000),
  });
  if (!(options.expected ?? [200]).includes(response.status)) {
    let code = null;
    try { code = (await response.json()).error?.code ?? null; }
    catch { await response.body?.cancel(); }
    throw new Error(`Local API ${new URL(url).pathname} failed (${response.status}, ${code ?? 'NO_CODE'})`);
  }
  return response.json();
}

async function createSession(token) {
  const value = await appApi(`${AGENT}/api/v1/agent/marketplace-v2/sessions`, token, {
    method: 'POST', expected: [200],
    body: { sessionType: 'MARKETPLACE_AGENT_V2', newConversation: true },
  });
  if (!/^[0-9A-HJKMNP-TV-Z]{26}$/.test(value.sessionId)) {
    throw new Error('New V2 session ID invalid');
  }
  return value.sessionId;
}

async function sendStream(scenario, token, sessionId, body, options = {}) {
  const clientMessageId = ulid();
  const response = await fetch(`${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionId}/messages/stream`, {
    method: 'POST', headers: {
      Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
    },
    body: JSON.stringify({ clientMessageId, body }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok) {
    await response.body?.cancel();
    results.push({ scenario, status: 'ERROR', httpStatus: response.status });
    return null;
  }
  const raw = await response.text();
  const events = raw.split(/\r?\n\r?\n/).flatMap(block => {
    const data = block.split(/\r?\n/).find(line => line.startsWith('data: '));
    return data ? [JSON.parse(data.slice(6))] : [];
  });
  const done = events.find(item => item.type === 'done');
  const errorEvent = events.find(item => item.type === 'error');
  const terminal = done?.response;
  const history = options.longSession ? null : await appApi(
    `${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionId}/messages`, token);
  const userRows = history?.data.filter(item => item.clientMessageId === clientMessageId) ?? [];
  const assistantRows = history?.data.filter(item => item.id === terminal?.assistantMessageId) ?? [];
  const dbCounts = options.longSession && terminal?.assistantMessageId ? sql(
    `SELECT (SELECT COUNT(*) FROM agent.agent_invocations WHERE session_id=${q(sessionId)} AND client_message_id=${q(clientMessageId)} AND user_message_id IS NOT NULL),` +
    `(SELECT COUNT(*) FROM agent.agent_messages WHERE session_id=${q(sessionId)} AND message_id=${q(terminal?.assistantMessageId ?? '')})`,
  ).split('\t').map(Number) : options.longSession ? [0, 0] : null;
  const persistedUserCount = dbCounts?.[0] ?? userRows.length;
  const persistedAssistantCount = dbCounts?.[1] ?? assistantRows.length;
  const result = {
    scenario, input: body,
    status: done && persistedUserCount === 1 && persistedAssistantCount === 1 ? 'PASS' : 'FAIL',
    eventTypes: events.map(item => item.type).filter((type, index, all) =>
      type !== 'text_delta' || index === 0 || all[index - 1] !== 'text_delta'),
    textDeltaCount: events.filter(item => item.type === 'text_delta').length,
    eventErrorCode: errorEvent?.code ?? null,
    decisionCount: terminal?.decisionCount ?? null,
    toolActivity: terminal?.message?.toolActivity?.map(item => ({
      tool: item.tool, status: item.status, reason: item.reason,
    })) ?? [],
    attachmentCount: terminal?.message?.attachments?.length ?? 0,
    listingIds: terminal?.message?.attachments?.map(item => item.listingId).slice(0, 5) ?? [],
    listingTitles: terminal?.message?.attachments?.map(item => item.title).slice(0, 5) ?? [],
    visibleText: terminal?.message?.content?.slice(0, 500) ?? null,
    persistedUserCount, persistedAssistantCount,
    persistedRetryableCount: history?.data.filter(item => item.retryable).length ?? null,
    pendingType: terminal?.message?.pendingInteraction?.type ?? null,
  };
  results.push(result);
  return terminal;
}

function seedLongSession(actorId, sessionId, cards) {
  for (const value of [actorId, sessionId]) {
    if (!/^[0-9A-HJKMNP-TV-Z]{26}$/.test(value)) {
      throw new Error('Invalid actor-owned long-session fixture ID');
    }
  }
  const base = Date.now() - 600_000;
  const rows = Array.from({ length: 110 }, (_, index) => {
    const user = index % 2 === 0;
    const when = new Date(base + index * 1000).toISOString().replace('T', ' ').slice(0, 23);
    return `(${q(ulid())},${q(sessionId)},${q(actorId)},${q(user ? 'USER' : 'ASSISTANT')},` +
      `${q(`ctx01-rc-filler-${index}`)},${user ? 'NULL' : q('ANSWERED')},` +
      `${user ? 'NULL' : q('[]')},${user ? 'NULL' : q('[]')},${q(when)})`;
  });
  const sourceWhen = new Date(base + 110_000).toISOString().replace('T', ' ').slice(0, 23);
  rows.push(`(${q(ulid())},${q(sessionId)},${q(actorId)},'ASSISTANT',` +
    `${q('Here are two current keyboards.')},'ANSWERED',${q(JSON.stringify(cards))},` +
    `${q(JSON.stringify([{ type: 'MARKETPLACE_AGENT_V2_OBSERVATION',
      tool: 'search_listings', status: 'SUCCEEDED', reason: 'RESULTS_AVAILABLE',
      observedAt: new Date(base + 110_000).toISOString() }]))},${q(sourceWhen)})`);
  const futureWhen = new Date(Date.now() + 3_600_000).toISOString().replace('T', ' ').slice(0, 23);
  rows.push(`(${q(ulid())},${q(sessionId)},${q(actorId)},'USER',` +
    `${q('ctx01-rc-future-user')},NULL,NULL,NULL,${q(futureWhen)})`);
  rows.push(`(${q(ulid())},${q(sessionId)},${q(actorId)},'ASSISTANT',` +
    `${q('ctx01-rc-future-results')},'ANSWERED',${q(JSON.stringify(cards.slice(0, 1)))},` +
    `${q('[]')},${q(new Date(Date.now() + 3_601_000).toISOString().replace('T', ' ').slice(0, 23))})`);
  for (let index = 0; index < rows.length; index += 20) {
    sql('INSERT INTO agent.agent_messages ' +
      '(message_id,session_id,actor_user_id,role,body,resolution_type,sources_json,actions_json,created_at) VALUES ' +
      rows.slice(index, index + 20).join(','));
  }
  const count = Number(sql(`SELECT COUNT(*) FROM agent.agent_messages WHERE session_id=${q(sessionId)}`));
  if (count !== 113) throw new Error(`Long-session fixture count invalid (${count})`);
}

function q(value) {
  return `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4)`;
}

function sql(query) {
  const encoded = Buffer.from(query).toString('base64');
  return execFileSync('docker', ['exec', 'msb-demo-mysql', 'sh', '-lc',
    'echo "$1" | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N',
    '--', encoded], { encoding: 'utf8' }).trim();
}

async function probeIsolation(token, sessionId) {
  const url = `${AGENT}/api/v1/agent/marketplace-v2/sessions/${sessionId}/messages`;
  const historyResponse = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(10_000),
  });
  const historyStatus = historyResponse.status;
  await historyResponse.body?.cancel();
  const response = await fetch(`${url}/stream`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json' },
    body: JSON.stringify({ clientMessageId: ulid(), body: 'Tell me about the second one.' }),
    signal: AbortSignal.timeout(30_000),
  });
  const raw = await response.text();
  const eventTypes = raw.split(/\r?\n\r?\n/).flatMap(block => {
    const data = block.split(/\r?\n/).find(line => line.startsWith('data: '));
    return data ? [JSON.parse(data.slice(6)).type] : [];
  });
  results.push({ scenario: 'K: cross-session denial',
    status: historyStatus !== 200 && !eventTypes.includes('done') &&
      !eventTypes.includes('message_started') ? 'PASS' : 'FAIL',
    historyHttpStatus: historyStatus, streamHttpStatus: response.status,
    streamEventTypes: eventTypes,
  });
}

function ulid() {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  function encode(value, length) {
    let result = '';
    for (let i = 0; i < length; i++) {
      result = alphabet[Number(value & 31n)] + result;
      value >>= 5n;
    }
    return result;
  }
  return encode(BigInt(Date.now()), 10) + encode(BigInt(`0x${randomBytes(10).toString('hex')}`), 16);
}
