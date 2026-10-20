import { assembleResources } from './assemble_resources_lib.mjs';
import path from 'node:path';

// js_run_binary starts in the bin tree; Make-expanded paths are execroot-relative.
const input = value => path.resolve(process.env.JS_BINARY__EXECROOT, value);
const [templateArg, outputArg, ...bundleArgs] = process.argv.slice(2);
await assembleResources(bundleArgs.map(input), input(templateArg), input(outputArg));
