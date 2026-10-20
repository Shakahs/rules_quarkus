import { packageSpecifiers, vendorEntry } from './vendor_modules_lib.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

// js_run_binary starts in the bin tree; Make-expanded paths are execroot-relative.
const input = value => path.resolve(process.env.JS_BINARY__EXECROOT, value);
const [sourcesArg, entryArg, ...external] = process.argv.slice(2);
const entry = input(entryArg);
await mkdir(path.dirname(entry), { recursive: true });
await writeFile(entry, vendorEntry(await packageSpecifiers(input(sourcesArg), external)));
