// The dev loop's application bundle: the first-party modules alone. Every npm specifier they
// import resolves to a shim reading the module from the registry the dev vendor bundle fills
// (vendor_modules_lib.mjs), so this build never walks node_modules, and an edit re-bundles
// only the application. A shim is CommonJS: esbuild's interop then gives a namespace, named or
// default import of it exactly what the registry's ES module record holds.
const REGISTRY = '__quarkusWebVendor';
const NAMESPACE = 'quarkus-web-vendor';

const vendorModules = {
  name: 'quarkus-web-vendor',
  setup(build) {
    const external = new Set(build.initialOptions.external ?? []);
    build.onResolve({ filter: /^[^./]/ }, args => {
      if (args.kind === 'entry-point' || external.has(args.path) || /^[a-z][a-z0-9+.-]*:/i.test(args.path)) {
        return undefined;
      }
      return { path: args.path, namespace: NAMESPACE };
    });
    build.onLoad({ filter: /.*/, namespace: NAMESPACE }, args => {
      const specifier = JSON.stringify(args.path);
      return {
        loader: 'js',
        contents: [
          `const record = globalThis[${JSON.stringify(REGISTRY)}]?.[${specifier}];`,
          `if (record === undefined) throw new Error("The dev vendor bundle does not provide " + ${specifier});`,
          'module.exports = record;',
        ].join('\n'),
      };
    });
  },
};

export default {
  // Stable names: the dev server registers the bundle's files when the application starts and
  // serves their current contents from then on, so an edit must not rename them.
  entryNames: 'app',
  assetNames: 'assets/[name]-[hash]',
  publicPath: '/_static/',
  loader: { '.wasm': 'file', '.svg': 'file', '.woff2': 'file', '.png': 'file' },
  plugins: [vendorModules],
};
