import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const nginx = await readFile(new URL('../nginx.conf', import.meta.url), 'utf8');

test('Discovery proxy is narrowly unbuffered above the gateway timeout', () => {
  const block = nginx.match(
    /location \^~ \/api\/v1\/agent\/discovery\/ \{([\s\S]*?)\n    \}/,
  )?.[1];

  assert.ok(block, 'missing narrow Discovery proxy location');
  assert.match(block, /proxy_http_version 1\.1;/);
  assert.match(block, /proxy_buffering off;/);
  assert.match(block, /proxy_cache off;/);
  assert.match(block, /proxy_read_timeout 40s;/);
  assert.doesNotMatch(
    nginx.match(/location \/api\/ \{([\s\S]*?)\n    \}/)?.[1] ?? '',
    /proxy_buffering off;/,
  );
});
