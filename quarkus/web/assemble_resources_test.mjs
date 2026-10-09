import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { assembleResources } from './assemble_resources_lib.mjs';

async function fixture(t) {
  const root = await mkdtemp(path.join(process.env.TEST_TMPDIR ?? os.tmpdir(), 'web-assets-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const bundle = path.join(root, 'bundle');
  const template = path.join(root, 'index.html');
  const output = path.join(root, 'resources');
  await mkdir(path.join(bundle, 'chunks'), { recursive: true });
  await writeFile(path.join(bundle, 'app-JSHASH.js'), 'import "./chunks/lazy-HASH.js";');
  await writeFile(path.join(bundle, 'app-CSSHASH.css'), '.min-h-screen{min-height:100vh}');
  await writeFile(path.join(bundle, 'chunks/lazy-HASH.js'), 'export const lazy = true;');
  await writeFile(template, '<html><!-- bazel:web-assets --><body class="min-h-screen"></body></html>');
  return { bundle, template, output };
}

test('generates matching static and SPA shells without copying the bundle', async t => {
  const { bundle, template, output } = await fixture(t);
  await assembleResources(bundle, template, output);
  const html = await readFile(path.join(output, 'META-INF/resources/index.html'), 'utf8');
  assert.equal(html, await readFile(path.join(output, 'templates/spa.html'), 'utf8'));
  assert.match(html, /href="\/_static\/app-CSSHASH.css"/);
  assert.match(html, /src="\/_static\/app-JSHASH.js"/);
  assert.match(html, /class="min-h-screen"/);
  assert.equal(html.includes('bazel:web-assets'), false);
  assert.deepEqual(await readdir(path.join(output, 'META-INF/resources')), ['index.html']);
  assert.match(await readFile(path.join(output, 'META-INF/beans.xml'), 'utf8'), /bean-discovery-mode="none"/);
  assert.equal(await readFile(path.join(bundle, 'chunks/lazy-HASH.js'), 'utf8'), 'export const lazy = true;');
});

test('replaces old hashes when the bundle changes', async t => {
  const { bundle, template, output } = await fixture(t);
  await assembleResources(bundle, template, output);
  await rm(path.join(bundle, 'app-JSHASH.js'));
  await writeFile(path.join(bundle, 'app-NEWHASH.js'), 'export const updated = true;');
  await assembleResources(bundle, template, output);
  const html = await readFile(path.join(output, 'templates/spa.html'), 'utf8');
  assert.match(html, /app-NEWHASH.js/);
  assert.equal(html.includes('app-JSHASH.js'), false);
});

for (const marker of ['', '<!-- bazel:web-assets --><!-- bazel:web-assets -->']) {
  test('rejects a missing or repeated HTML marker: ' + marker.length, async t => {
    const { bundle, template, output } = await fixture(t);
    await writeFile(template, marker);
    await assert.rejects(assembleResources(bundle, template, output), /exactly one web-assets marker/);
  });
}

for (const entry of ['app-SECOND.js', 'app-SECOND.css']) {
  test('rejects ambiguous bundle entries: ' + entry, async t => {
    const { bundle, template, output } = await fixture(t);
    await writeFile(path.join(bundle, entry), '');
    await assert.rejects(assembleResources(bundle, template, output), /one application JS and CSS entry/);
  });
}
