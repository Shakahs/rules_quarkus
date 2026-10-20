import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

// The page-global registry the dev vendor bundle fills and the dev application bundle's shims
// read: one entry per npm specifier the application imports.
export const REGISTRY = '__quarkusWebVendor';

// Import and re-export statements naming a module (`import x from "m"`, `export * from "m"`,
// across lines), side-effect imports (`import "m"`) and dynamic imports (`import("m")`).
const FROM = /(?:^|[\n;])\s*(?:import|export)\s[^'";]*?\bfrom\s*(['"])([^'"\n]+)\1/g;
const BARE_IMPORT = /(?:^|[\n;])\s*import\s*(['"])([^'"\n]+)\1/g;
const DYNAMIC = /\bimport\(\s*(['"])([^'"\n]+)\1\s*\)/g;

function isPackage(specifier) {
  return !specifier.startsWith('.') && !specifier.startsWith('/') && !/^[a-z][a-z0-9+.-]*:/i.test(specifier);
}

async function scriptFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true, recursive: true })) {
    if (entry.isFile() && /\.m?js$/.test(entry.name)) files.push(path.join(entry.parentPath, entry.name));
  }
  return files.sort();
}

// The npm specifiers the application's own modules import, sorted, without `external` ones.
export async function packageSpecifiers(directory, external = []) {
  const excluded = new Set(external);
  const specifiers = new Set();
  for (const file of await scriptFiles(directory)) {
    const source = await readFile(file, 'utf8');
    for (const pattern of [FROM, BARE_IMPORT, DYNAMIC]) {
      for (const match of source.matchAll(pattern)) {
        const specifier = match[2];
        if (isPackage(specifier) && !excluded.has(specifier)) specifiers.add(specifier);
      }
    }
  }
  return [...specifiers].sort();
}

// The vendor bundle's entry: every specifier's module namespace, published on the registry as
// an ES module record (`__esModule`) whose properties read through to the namespace, so a
// CommonJS package keeps the named exports esbuild's interop gives it and the default export
// stays `default`.
export function vendorEntry(specifiers) {
  const imports = specifiers.map((specifier, index) => `import * as m${index} from ${JSON.stringify(specifier)};`);
  const modules = specifiers.map((specifier, index) => `  [${JSON.stringify(specifier)}, m${index}],`);
  return [
    ...imports,
    `const registry = (globalThis[${JSON.stringify(REGISTRY)}] ??= {});`,
    'for (const [specifier, namespace] of [',
    ...modules,
    ']) {',
    '  const record = { __esModule: true };',
    '  for (const name of Object.keys(namespace)) {',
    '    Object.defineProperty(record, name, { get: () => namespace[name], enumerable: true });',
    '  }',
    '  registry[specifier] = record;',
    '}',
    '',
  ].join('\n');
}
