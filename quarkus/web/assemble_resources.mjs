import { cp, mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// js_run_binary starts in the bin tree; Make-expanded paths are execroot-relative.
const input = value => path.resolve(process.env.JS_BINARY__EXECROOT, value);
const [bundleArg, templateArg, outputArg] = process.argv.slice(2);
const bundle = input(bundleArg);
const output = input(outputArg);
const files = await readdir(bundle);
const scripts = files.filter(name => /^app-[^.]+\.js$/.test(name));
const styles = files.filter(name => /^app-[^.]+\.css$/.test(name));
if (scripts.length !== 1 || styles.length !== 1) {
  throw new Error(`Expected one application JS and CSS entry, got ${scripts} and ${styles}`);
}
const template = await readFile(input(templateArg), 'utf8');
const marker = '<!-- bazel:web-assets -->';
if (template.split(marker).length !== 2) throw new Error('HTML must contain exactly one web-assets marker');
const html = template.replace(marker,
  `<link rel="stylesheet" href="/_static/${styles[0]}">\n    <script type="module" src="/_static/${scripts[0]}"></script>`);
await mkdir(path.join(output, 'META-INF/resources'), { recursive: true });
await mkdir(path.join(output, 'templates'), { recursive: true });
await cp(bundle, path.join(output, 'META-INF/resources/_static'), { recursive: true });
await writeFile(path.join(output, 'META-INF/resources/index.html'), html);
await writeFile(path.join(output, 'templates/spa.html'), html);
// Mark this resource archive for Quarkus indexing, including Qute templates.
await writeFile(path.join(output, 'META-INF/beans.xml'),
  '<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" bean-discovery-mode="none" version="4.0"/>\n');
