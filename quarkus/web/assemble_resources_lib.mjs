import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const ENTRY = /^(vendor|app)(?:-[^.]+)?\.(js|css)$/;

// The entry files of the bundle directories, by role: exactly one application script and
// stylesheet, and at most one of each from a vendor bundle the application's modules read.
async function entries(bundles) {
  const found = { app: { js: [], css: [] }, vendor: { js: [], css: [] } };
  for (const bundle of bundles) {
    for (const name of await readdir(bundle)) {
      const match = ENTRY.exec(name);
      if (match) found[match[1]][match[2]].push(name);
    }
  }
  if (found.app.js.length !== 1 || found.app.css.length !== 1) {
    throw new Error(`Expected one application JS and CSS entry, got ${found.app.js} and ${found.app.css}`);
  }
  if (found.vendor.js.length > 1 || found.vendor.css.length > 1) {
    throw new Error(`Expected at most one vendor JS and CSS entry, got ${found.vendor.js} and ${found.vendor.css}`);
  }
  return found;
}

// Only generate the small HTML/metadata tree. rules_pkg maps the original esbuild
// directory into META-INF/resources/_static directly, without another asset copy.
export async function assembleResources(bundles, templatePath, output) {
  const found = await entries(bundles);
  const template = await readFile(templatePath, 'utf8');
  const marker = '<!-- bazel:web-assets -->';
  if (template.split(marker).length !== 2) throw new Error('HTML must contain exactly one web-assets marker');
  // Module scripts run in document order: the vendor bundle fills the registry first.
  const tags = [
    ...[...found.vendor.css, ...found.app.css].map(name => `<link rel="stylesheet" href="/_static/${name}">`),
    ...[...found.vendor.js, ...found.app.js].map(name => `<script type="module" src="/_static/${name}"></script>`),
  ];
  const html = template.replace(marker, tags.join('\n    '));
  await mkdir(path.join(output, 'META-INF/resources'), { recursive: true });
  await mkdir(path.join(output, 'templates'), { recursive: true });
  await writeFile(path.join(output, 'META-INF/resources/index.html'), html);
  await writeFile(path.join(output, 'templates/spa.html'), html);
  await writeFile(path.join(output, 'META-INF/beans.xml'),
    '<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" bean-discovery-mode="none" version="4.0"/>\n');
}
