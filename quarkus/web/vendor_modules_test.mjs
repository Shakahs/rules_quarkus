import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { pathToFileURL } from 'node:url';
import { packageSpecifiers, REGISTRY, vendorEntry } from './vendor_modules_lib.mjs';

async function directory(t) {
  const root = await mkdtemp(path.join(process.env.TEST_TMPDIR ?? os.tmpdir(), 'vendor-modules-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}

test('finds every form of package import in the application modules', async t => {
  const root = await directory(t);
  await mkdir(path.join(root, 'scalajs'), { recursive: true });
  await writeFile(path.join(root, 'index.js'), [
    "import './scalajs/main.js';",
    "import '@lion/ui/define/lion-collapsible.js';",
    "import { setupAgTestIds } from 'ag-grid-community';",
    'import {',
    '  first,',
    '  second,',
    "} from 'multi-line';",
    "export { third } from 're-exported';",
    'export const notAnImport = "string-literal";',
  ].join('\n'));
  await writeFile(path.join(root, 'scalajs', 'main.js'), [
    'import * as $i_react from "react";',
    'import * as $i_internal from "./internal-1.js";',
    'import * as $i_external from "perf_hooks";',
    'const lazy = () => import("@devolutions/iron-remote-desktop");',
    'const remote = () => import("https://example.com/module.js");',
  ].join('\n'));
  await writeFile(path.join(root, 'scalajs', 'styles.css'), '@import "not-a-module";');
  assert.deepEqual(await packageSpecifiers(root, ['perf_hooks']), [
    '@devolutions/iron-remote-desktop',
    '@lion/ui/define/lion-collapsible.js',
    'ag-grid-community',
    'multi-line',
    're-exported',
    'react',
  ]);
});

test('publishes CommonJS and ES module packages as ES module records', async t => {
  const root = await directory(t);
  const commonjs = path.join(root, 'node_modules', 'commonjs-package');
  const esm = path.join(root, 'node_modules', 'esm-package');
  await mkdir(commonjs, { recursive: true });
  await mkdir(esm, { recursive: true });
  await writeFile(path.join(commonjs, 'package.json'), '{"name":"commonjs-package","main":"index.js"}');
  await writeFile(path.join(commonjs, 'index.js'), 'exports.named = "cjs-named";');
  await writeFile(path.join(esm, 'package.json'), '{"name":"esm-package","type":"module","main":"index.js"}');
  await writeFile(path.join(esm, 'index.js'), 'export let counter = 1; export const bump = () => { counter++; }; export default "esm-default";');
  const entry = path.join(root, 'entry.mjs');
  await writeFile(entry, vendorEntry(['commonjs-package', 'esm-package']));
  t.after(() => { delete globalThis[REGISTRY]; });
  await import(pathToFileURL(entry).href);

  const cjs = globalThis[REGISTRY]['commonjs-package'];
  assert.equal(cjs.__esModule, true);
  assert.equal(cjs.named, 'cjs-named');
  assert.equal(cjs.default.named, 'cjs-named');
  const mod = globalThis[REGISTRY]['esm-package'];
  assert.equal(mod.__esModule, true);
  assert.equal(mod.default, 'esm-default');
  mod.bump();
  assert.equal(mod.counter, 2, 'records read through to live bindings');
});
