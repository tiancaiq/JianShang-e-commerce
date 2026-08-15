import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = path => readFileSync(resolve(frontendRoot, path), 'utf8');
const featureValues = source => Object.fromEntries(
  [...source.matchAll(/^\s{4}([A-Za-z][A-Za-z0-9]*):\s*(true|false),?$/gm)]
    .map(match => [match[1], match[2] === 'true']),
);

const safeFeatures = (aiAssistant, aiDiscovery) => ({
  cart: false,
  buyerAddresses: false,
  categoryGuidance: false,
  sellerInventory: false,
  businessOrders: false,
  buyerCheckout: false,
  aiAssistant,
  aiDiscovery,
  notifications: false,
  adminSearchMaintenance: false,
});

const cartFeatures = (aiAssistant, aiDiscovery) => ({
  ...safeFeatures(aiAssistant, aiDiscovery),
  cart: true,
});

test('production and development keep the Agent UI disabled', () => {
  const angular = JSON.parse(read('angular.json'));
  const build = angular.projects.frontend.architect.build;

  assert.equal(build.defaultConfiguration, 'production');
  assert.match(read('src/environments/environment.ts'), /aiAssistant:\s*false/);
  assert.match(read('src/environments/environment.ts'), /aiDiscovery:\s*false/);
  assert.match(
    read('src/environments/environment.development.ts'),
    /aiAssistant:\s*false/,
  );
  assert.match(
    read('src/environments/environment.development.ts'),
    /aiDiscovery:\s*false/,
  );
});

test('demo-ai enables the discovery-first Marketplace Assistant capability', () => {
  const angular = JSON.parse(read('angular.json'));
  const replacement =
    angular.projects.frontend.architect.build.configurations['demo-ai']
      .fileReplacements;

  assert.deepEqual(replacement, [
    {
      replace: 'src/environments/environment.ts',
      with: 'src/environments/environment.demo-ai.ts',
    },
  ]);
  assert.equal(
    angular.projects.frontend.architect.serve.configurations['demo-ai']
      .buildTarget,
    'frontend:build:demo-ai',
  );
  const demo = read('src/environments/environment.demo-ai.ts');
  assert.match(demo, /production:\s*true/);
  assert.match(demo, /aiAssistant:\s*true/);
  assert.match(demo, /aiDiscovery:\s*true/);
  assert.deepEqual(featureValues(demo), safeFeatures(true, true));
  assert.doesNotMatch(demo, /https?:\/\//);
});

test('demo-ai-discovery remains a dedicated discovery build opt-in', () => {
  const angular = JSON.parse(read('angular.json'));
  const replacement =
    angular.projects.frontend.architect.build.configurations[
      'demo-ai-discovery'
    ].fileReplacements;

  assert.deepEqual(replacement, [
    {
      replace: 'src/environments/environment.ts',
      with: 'src/environments/environment.demo-ai-discovery.ts',
    },
  ]);
  assert.equal(
    angular.projects.frontend.architect.serve.configurations[
      'demo-ai-discovery'
    ].buildTarget,
    'frontend:build:demo-ai-discovery',
  );
  const discovery = read(
    'src/environments/environment.demo-ai-discovery.ts',
  );
  assert.match(discovery, /production:\s*true/);
  assert.deepEqual(featureValues(discovery), safeFeatures(true, true));
  assert.doesNotMatch(discovery, /https?:\/\//);
  assert.doesNotMatch(
    discovery,
    /OPENAI|API[_-]?KEY|TOKEN|SECRET|PASSWORD|process\.env|window\.__env/i,
  );
});

test('demo-cart-ai-discovery preserves cart while adding only the Agent capabilities', () => {
  const angular = JSON.parse(read('angular.json'));
  const replacement =
    angular.projects.frontend.architect.build.configurations[
      'demo-cart-ai-discovery'
    ].fileReplacements;

  assert.deepEqual(replacement, [
    {
      replace: 'src/environments/environment.ts',
      with: 'src/environments/environment.demo-cart-ai-discovery.ts',
    },
  ]);
  assert.equal(
    angular.projects.frontend.architect.serve.configurations[
      'demo-cart-ai-discovery'
    ].buildTarget,
    'frontend:build:demo-cart-ai-discovery',
  );
  const combined = read(
    'src/environments/environment.demo-cart-ai-discovery.ts',
  );
  const cart = featureValues(read('src/environments/environment.demo-cart.ts'));
  const combinedFeatures = featureValues(combined);

  assert.deepEqual(combinedFeatures, {
    ...cartFeatures(true, true),
    marketplaceAgentV2: true,
  });
  assert.deepEqual(
    Object.fromEntries(
      Object.entries(combinedFeatures).filter(([name]) =>
        name !== 'aiAssistant' && name !== 'aiDiscovery'
          && name !== 'marketplaceAgentV2'
      ),
    ),
    Object.fromEntries(
      Object.entries(cart).filter(([name]) =>
        name !== 'aiAssistant' && name !== 'aiDiscovery'
          && name !== 'marketplaceAgentV2'
      ),
    ),
  );
  assert.doesNotMatch(combined, /https?:\/\//);
  assert.doesNotMatch(
    combined,
    /OPENAI|API[_-]?KEY|TOKEN|SECRET|PASSWORD|process\.env|window\.__env/i,
  );
});

test('container builds remain production unless explicitly overridden', () => {
  const dockerfile = read('Dockerfile');

  assert.match(
    dockerfile,
    /ARG FRONTEND_BUILD_CONFIGURATION=production/,
  );
  assert.match(
    dockerfile,
    /ARG GATEWAY_FEATURE_AGENT_DISCOVERY=false/,
  );
  assert.match(
    dockerfile,
    /demo-ai\|demo-ai-discovery\|demo-cart-ai-discovery/,
  );
  assert.match(
    dockerfile,
    /Discovery frontend builds require GATEWAY_FEATURE_AGENT_DISCOVERY=true\./,
  );
  assert.match(
    dockerfile,
    /npm run build -- --configuration "\$FRONTEND_BUILD_CONFIGURATION"/,
  );
});

test('admin search maintenance has one explicit build and stays off elsewhere', () => {
  const angular = JSON.parse(read('angular.json'));
  const replacement =
    angular.projects.frontend.architect.build.configurations[
      'demo-admin-search-maintenance'
    ].fileReplacements;

  assert.deepEqual(replacement, [
    {
      replace: 'src/environments/environment.ts',
      with: 'src/environments/environment.demo-admin-search-maintenance.ts',
    },
  ]);
  assert.equal(
    angular.projects.frontend.architect.serve.configurations[
      'demo-admin-search-maintenance'
    ].buildTarget,
    'frontend:build:demo-admin-search-maintenance',
  );

  for (const name of [
    'environment.ts',
    'environment.development.ts',
    'environment.demo-ai.ts',
    'environment.demo-ai-discovery.ts',
    'environment.demo-cart.ts',
    'environment.demo-cart-ai-discovery.ts',
  ]) {
    assert.equal(
      featureValues(read(`src/environments/${name}`)).adminSearchMaintenance,
      false,
      `${name} must remain disabled`,
    );
  }

  const maintenance = read(
    'src/environments/environment.demo-admin-search-maintenance.ts',
  );
  assert.deepEqual(featureValues(maintenance), {
    ...cartFeatures(false, false),
    adminSearchMaintenance: true,
  });
  assert.doesNotMatch(
    maintenance,
    /https?:\/\/|OPENAI|API[_-]?KEY|TOKEN|SECRET|PASSWORD|process\.env|window\.__env/i,
  );
});
