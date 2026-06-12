# Eclipse JDT LS Web

This fork adds a lightweight browser-oriented Java/Processing language service
built from Eclipse ECJ and TeaVM.

The web module is `org.eclipse.jdt.ls.web`. It can be compiled to WebAssembly
GC and JavaScript. It is not the desktop JDT LS server and it does not include
Maven/Gradle import, debugging, code actions, or a web app. It does provide a
small browser API and LSP-shaped endpoint for diagnostics, completion, hover,
and signature help.

## Components

- `org.eclipse.jdt.ls.web`
  - Java source for the browser/WASM linter.
  - Public package: `org.eclipse.jdt.ls.web`.
  - Internal TeaVM build glue: `org.eclipse.jdt.ls.web.internal.teavm`.
  - Internal resource selection: `org.eclipse.jdt.ls.web.internal.resources`.
  - Generated WASM output: `org.eclipse.jdt.ls.web/target/generated/wasm/teavm/`.
  - Generated JS output: `org.eclipse.jdt.ls.web/target/generated/js/teavm/`.

- `third_party/teavm`
  - Vendored patched TeaVM compiler/runtime sources required to build the
    WASM/JS artifacts.

## What Is Implemented

- ECJ-backed diagnostics for Java files.
- In-memory folder source model, so files can resolve each other.
- Basic LSP-shaped handling for initialize, open/change/close, watched file
  changes, configuration changes, completion, hover, signature help, and
  publish diagnostics.
- Direct JavaScript APIs: `lint`, `lintProcessing`, `complete`, `hover`,
  `signatureHelp`, and `handle`.
- Browser-bundled JDK API signatures generated from the build JDK's `ct.sym`,
  covering Java/JDK standard library signatures available for Java 17.
- Browser-bundled TeaVM javac classpath and Processing core metadata for code
  assist. These are compile-time/code-assist signatures, not runtime
  implementations.
- Processing Java mode for `.pde` sketches:
  - provide an entrypoint PDE file
  - provide any additional PDE files
  - the web service runs Processing's original preprocessor
  - preprocessor errors are returned as diagnostics
  - Processing core APIs such as `PApplet`, `PFont`, `PImage`, `PVector`,
    `createFont`, `text`, `textFont`, `textAlign`, `size`, and `fill` are
    resolved from packed metadata

## Build TeaVM

The web module expects the patched TeaVM artifacts to be available in the local
Maven repository. From the vendored TeaVM tree:

```sh
cd third_party/teavm
./gradlew :core:publishToMavenLocal :classlib:publishToMavenLocal :jso:core:publishToMavenLocal :jso:apis:publishToMavenLocal :tools:maven:plugin:publishToMavenLocal
```

The web module also requires a TeaVM javac distribution directory passed as
`-Dteavm.javac.dist=...`. That directory must contain:

```text
compile-classlib-teavm.bin
runtime-classlib-teavm.bin
processing-core-teavm.jar
```

## Build The Web Linter

From the repository root:

```sh
./mvnw -pl org.eclipse.jdt.ls.web \
  -DskipTests=false \
  -Dteavm.javac.dist=/home/cloudtron/teavm-javac/dist/teavm-javac \
  test
```

For a build without the test verifier:

```sh
./mvnw -pl org.eclipse.jdt.ls.web \
  -DskipTests \
  -Dteavm.javac.dist=/home/cloudtron/teavm-javac/dist/teavm-javac \
  process-classes
```

Outputs:

```text
org.eclipse.jdt.ls.web/target/generated/package.json
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.ts
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.js
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.cjs
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.d.ts
org.eclipse.jdt.ls.web/target/generated/wasm/teavm/classes.wasm
org.eclipse.jdt.ls.web/target/generated/wasm/teavm/classes.wasm-runtime.js
org.eclipse.jdt.ls.web/target/generated/js/teavm/classes.js
```

The generated directory is also the npm package root:

```sh
npm pack org.eclipse.jdt.ls.web/target/generated
```

## API

Use the target-agnostic browser loader when possible:

```ts
import { load } from "eclipse-jdt-ls-web";

const jdtls = await load();
const diagnosticsJson = jdtls.lint("file:///Example.java", "class Example {}");
const completionsJson = jdtls.complete(
  "file:///Sketch.pde",
  "void setup() { textF }",
  0,
  20
);
const hoverJson = jdtls.hover("file:///Example.java", "class Example {}", 0, 6);
const signatureJson = jdtls.signatureHelp(
  "file:///Sketch.pde",
  "void setup() { createFont( }",
  0,
  26
);
console.log(jdtls.target, diagnosticsJson, completionsJson, hoverJson, signatureJson);
```

For direct browser use without an npm bundler:

```html
<script type="module">
  import { load } from "./web-jdt-ls.js";

  const jdtls = await load();
  console.log(jdtls.target);
</script>
```

`load()` tries the WASM GC build first. If the browser cannot load it,
including when WebAssembly GC or JSPI-related support is missing, the loader
falls back to the JavaScript build and returns the same `lint`,
`lintProcessing`, `complete`, `hover`, `signatureHelp`, and `handle` functions.
The returned `target` is `"wasm"` or `"js"`; if fallback was used,
`fallbackError` contains the original WASM loading failure.

By default, the loader expects this generated layout to be served from one
directory:

```text
web-jdt-ls.js
wasm/teavm/classes.wasm
wasm/teavm/classes.wasm-runtime.js
js/teavm/classes.js
```

Custom paths can be supplied with `baseUrl`, `wasmRuntimeUrl`, `wasmUrl`, or
`jsUrl`.

The TeaVM module exports:

```java
org.eclipse.jdt.ls.web.WebJdtLs.lint(String uri, String source)
org.eclipse.jdt.ls.web.WebJdtLs.lintProcessing(String entrypointUri, String entrypointSource, String additionalPdesJson)
org.eclipse.jdt.ls.web.WebJdtLs.complete(String uri, String source, int line, int character)
org.eclipse.jdt.ls.web.WebJdtLs.hover(String uri, String source, int line, int character)
org.eclipse.jdt.ls.web.WebJdtLs.signatureHelp(String uri, String source, int line, int character)
org.eclipse.jdt.ls.web.WebJdtLs.handle(String payload)
```

`lint` returns a JSON array of diagnostics for one Java source.

`complete`, `hover`, and `signatureHelp` return JSON strings shaped like LSP
results. Method completions insert only the method name, not parentheses.

`lintProcessing` expects `additionalPdesJson` in this shape:

```json
{
  "sources": [
    { "uri": "file:///Sketch/OtherTab.pde", "text": "..." }
  ]
}
```

`lintProcessing` returns diagnostics with an added `uri` field so callers can
place diagnostics in the original PDE tab. The entrypoint and each additional
PDE source are mapped back to their own URI and line space.

`handle` accepts LSP-shaped JSON payloads. The Processing-specific method is:

```json
{
  "jsonrpc": "2.0",
  "method": "java/webJdtLs/processingSketch",
  "params": {
    "entrypointUri": "file:///Sketch/Sketch.pde",
    "entrypointText": "void setup() { size(100, 100); }",
    "sources": [
      { "uri": "file:///Sketch/OtherTab.pde", "text": "..." }
    ]
  }
}
```

The Processing handler returns one `textDocument/publishDiagnostics` message per
original PDE URI, including empty diagnostic arrays for tabs that should be
cleared.

## Limits

This is a lightweight linter. It does not provide full desktop JDT LS behavior
or full JDT project modeling. There is no Maven or Gradle support, no external
server, and no editor-specific integration in this repository.
