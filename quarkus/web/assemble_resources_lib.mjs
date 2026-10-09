import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Only generate the small HTML/metadata tree. rules_pkg maps the original esbuild
// directory into META-INF/resources/_static directly, without another asset copy.
export async function assembleResources(bundle, templatePath, output) {
  const files = await readdir(bundle);
  const scripts = files.filter(name => /^app-[^.]+\.js$/.test(name));
  const styles = files.filter(name => /^app-[^.]+\.css$/.test(name));
  if (scripts.length !== 1 || styles.length !== 1) {
    throw new Error(`Expected one application JS and CSS entry, got ${scripts} and ${styles}`);
  }
  const template = await readFile(templatePath, 'utf8');
  const marker = '<!-- bazel:web-assets -->';
  if (template.split(marker).length !== 2) throw new Error('HTML must contain exactly one web-assets marker');
  const html = template.replace(marker,
    `<link rel="stylesheet" href="/_static/${styles[0]}">\n    <script type="module" src="/_static/${scripts[0]}"></script>`);
  await mkdir(path.join(output, 'META-INF/resources'), { recursive: true });
  await mkdir(path.join(output, 'templates'), { recursive: true });
  await writeFile(path.join(output, 'META-INF/resources/index.html'), html);
  await writeFile(path.join(output, 'templates/spa.html'), html);
  await writeFile(path.join(output, 'META-INF/beans.xml'),
    '<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" bean-discovery-mode="none" version="4.0"/>\n');
}
