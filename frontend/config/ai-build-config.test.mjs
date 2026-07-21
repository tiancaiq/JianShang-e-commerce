import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = path => readFileSync(resolve(frontendRoot, path), 'utf8');

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

test('demo-ai enables only listing customer-service Agent UI', () => {
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
  assert.match(demo, /aiDiscovery:\s*false/);
  assert.doesNotMatch(demo, /https?:\/\//);
});

test('demo-ai-discovery is the explicit Discovery canary opt-in', () => {
  const angular = JSON.parse(read('angular.json'));
  const replacement =
    angular.projects.frontend.architect.build.configurations['demo-ai-discovery']
      .fileReplacements;

  assert.deepEqual(replacement, [
    {
      replace: 'src/environments/environment.ts',
      with: 'src/environments/environment.demo-ai-discovery.ts',
    },
  ]);
  assert.equal(
    angular.projects.frontend.architect.serve.configurations['demo-ai-discovery']
      .buildTarget,
    'frontend:build:demo-ai-discovery',
  );
  const demo = read('src/environments/environment.demo-ai-discovery.ts');
  assert.match(demo, /production:\s*true/);
  assert.match(demo, /cart:\s*false/);
  assert.match(demo, /buyerAddresses:\s*false/);
  assert.match(demo, /categoryGuidance:\s*false/);
  assert.match(demo, /sellerInventory:\s*false/);
  assert.match(demo, /businessOrders:\s*false/);
  assert.match(demo, /buyerCheckout:\s*false/);
  assert.match(demo, /aiAssistant:\s*true/);
  assert.match(demo, /aiDiscovery:\s*true/);
  assert.match(demo, /notifications:\s*false/);
  assert.doesNotMatch(demo, /https?:\/\//);
});

test('container builds remain production unless explicitly overridden', () => {
  const dockerfile = read('Dockerfile');

  assert.match(
    dockerfile,
    /ARG FRONTEND_BUILD_CONFIGURATION=production/,
  );
  assert.match(
    dockerfile,
    /npm run build -- --configuration "\$FRONTEND_BUILD_CONFIGURATION"/,
  );
});
