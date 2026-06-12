# eclipse-jdt-ls-web

Browser-targeted Eclipse JDT LS support built with ECJ and TeaVM. The generated
package exposes diagnostics, completion, hover, and signature help for Java
source text and Processing `.pde` sketches.

## Build Requirements

Install or prepare:

- JDK 21 or newer for compiling this module.
- Maven through the repository `./mvnw` wrapper.
- The patched TeaVM artifacts published to the local Maven repository.
- A TeaVM javac distribution directory passed as `-Dteavm.javac.dist=...`.

Publish the vendored TeaVM artifacts first:

```sh
cd third_party/teavm
./gradlew :core:publishToMavenLocal \
  :classlib:publishToMavenLocal \
  :jso:core:publishToMavenLocal \
  :jso:apis:publishToMavenLocal \
  :tools:maven:plugin:publishToMavenLocal
```

The TeaVM javac dist directory must contain:

```text
compile-classlib-teavm.bin
runtime-classlib-teavm.bin
processing-core-teavm.jar
```

For the local workspace this is typically:

```text
/home/cloudtron/teavm-javac/dist/teavm-javac
```

Build and verify the web package:

```sh
./mvnw -pl org.eclipse.jdt.ls.web \
  -DskipTests=false \
  -Dteavm.javac.dist=/home/cloudtron/teavm-javac/dist/teavm-javac \
  test
```

The `test` phase also runs `CodeAssistClasspathVerifier`, which checks that
completion covers public/protected members from the bundled TeaVM javac
classpath and that Processing signatures are available.

To build without running the verifier:

```sh
./mvnw -pl org.eclipse.jdt.ls.web \
  -DskipTests \
  -Dteavm.javac.dist=/home/cloudtron/teavm-javac/dist/teavm-javac \
  process-classes
```

Generated package output:

```text
org.eclipse.jdt.ls.web/target/generated/package.json
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.ts
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.js
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.cjs
org.eclipse.jdt.ls.web/target/generated/web-jdt-ls.d.ts
org.eclipse.jdt.ls.web/target/generated/wasm/teavm/classes.wasm
org.eclipse.jdt.ls.web/target/generated/wasm/teavm/classes.wasm-runtime.js
org.eclipse.jdt.ls.web/target/generated/js/teavm/classes.js
org.eclipse.jdt.ls.web/target/generated/js/teavm/classes.js.map
```

The generated directory is also the npm package root:

```sh
npm pack org.eclipse.jdt.ls.web/target/generated
```

## Packaged Metadata

Java and Processing code assist metadata is packed into the generated JS/WASM
artifacts. Callers do not need to provide Processing definitions at runtime.

Bundled metadata includes:

- JDK 17 signature resources generated from `ct.sym`.
- TeaVM javac compile/runtime class library resources.
- Processing core resources from `processing-core-teavm.jar`, including
  `PApplet`, `PFont`, `PGraphics`, `PImage`, `PShape`, `PVector`, and
  Processing data/event classes.

Processing fallback stubs are intentionally not used. If Processing completion
is missing, the build is missing packed metadata or the editor is loading an old
generated artifact.

## JavaScript API

Use the target-agnostic loader:

```js
import { load } from "eclipse-jdt-ls-web";

const jdtls = await load();

const diagnosticsJson = jdtls.lint(
  "file:///Example.java",
  "class Example {}"
);

const completionsJson = jdtls.complete(
  "file:///Example.java",
  "class Example { void run() { String value = \"\"; value. } }",
  0,
  54
);

const hoverJson = jdtls.hover(
  "file:///Example.java",
  "class Example { void run() {} }",
  0,
  25
);

const signatureHelpJson = jdtls.signatureHelp(
  "file:///Sketch.pde",
  "void setup() {\n  background(10, );\n}\n",
  1,
  17
);
```

The loaded object has this shape:

```ts
interface WebJdtLsApi {
  target: "wasm" | "js";
  raw: unknown;
  fallbackError?: unknown;
  lint(uri: string, source: string): string;
  lintProcessing(entrypointUri: string, entrypointSource: string, additionalPdesJson: string): string;
  complete(uri: string, source: string, line: number, character: number): string;
  hover(uri: string, source: string, line: number, character: number): string;
  signatureHelp(uri: string, source: string, line: number, character: number): string;
  handle(payload: string): string;
}
```

`complete`, `hover`, and `signatureHelp` return JSON strings shaped like LSP
responses. Method completion items use `CompletionItemKind.Method` and insert
only the method name. For example, `text` inserts `text`, not `text()`.

## Loader Paths

`load()` tries the WebAssembly GC build first and falls back to the JavaScript
build if the browser cannot load the WASM artifact. The returned `target` is
`"wasm"` or `"js"`. If fallback happened, `fallbackError` contains the original
WASM load failure.

By default, serve this generated layout from one directory:

```text
web-jdt-ls.js
wasm/teavm/classes.wasm
wasm/teavm/classes.wasm-runtime.js
js/teavm/classes.js
```

Override paths with `baseUrl`, `wasmRuntimeUrl`, `wasmUrl`, or `jsUrl`.

```js
const jdtls = await load({
  baseUrl: "/assets/eclipse-jdt-ls-web/"
});
```

## Processing API Examples

Processing APIs come from packed Processing metadata:

```js
const sketch = `PFont myFont;

void setup() {
  size(200, 200);
  myFont = createFont("PixelifySans-Regular.ttf", 16);
  fill(255, 0, 0);
  textFont(myFont);
  textAlign(CENTER, CENTER);
  text("hello", width / 2, height / 2);
}`;

console.log(jdtls.complete("file:///Sketch.pde", sketch, 0, 2)); // PFont
console.log(jdtls.complete("file:///Sketch.pde", sketch, 4, 18)); // createFont
console.log(jdtls.signatureHelp("file:///Sketch.pde", sketch, 4, 22));
```

Expected code assist includes `PFont`, `PApplet`, `PVector`, `PImage`,
`createFont`, `text`, `textFont`, `textAlign`, `size`, `fill`, and the rest of
the exposed Processing core API. `PText` is not a Processing class.

## Processing Diagnostics

`lintProcessing` runs Processing's original preprocessor and returns diagnostics
mapped back to the original PDE tab URIs where possible.

```js
const diagnosticsJson = jdtls.lintProcessing(
  "file:///Sketch/Sketch.pde",
  "void setup() { size(100, 100); }",
  JSON.stringify({
    sources: [
      { uri: "file:///Sketch/OtherTab.pde", text: "void helper() {}" }
    ]
  })
);
```

If the Processing preprocessor reports syntax errors, those errors are returned
as diagnostics instead of silently failing.

## LSP `handle(payload)` API

Editor integrations can use `handle()` with JSON-RPC/LSP-shaped messages.

`initialize` advertises:

```json
{
  "textDocumentSync": { "openClose": true, "change": 1 },
  "completionProvider": {
    "resolveProvider": false,
    "triggerCharacters": [".", "@"]
  },
  "hoverProvider": true,
  "signatureHelpProvider": {
    "triggerCharacters": ["(", ","],
    "retriggerCharacters": [","]
  }
}
```

Supported request/notification methods include:

```text
initialize
initialized
shutdown
exit
textDocument/didOpen
textDocument/didChange
textDocument/didClose
textDocument/completion
textDocument/hover
textDocument/signatureHelp
workspace/didChangeWatchedFiles
workspace/didChangeConfiguration
java/browserJdtLs/workspaceSources
java/browserJdtLs/removeWorkspaceSource
java/browserJdtLs/renameWorkspaceSource
java/webJdtLs/processingSketch
```

Example Processing diagnostics request:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "java/webJdtLs/processingSketch",
  "params": {
    "entrypointUri": "file:///Sketch/Sketch.pde",
    "entrypointText": "void setup() { size(100, 100); }",
    "sources": [
      { "uri": "file:///Sketch/OtherTab.pde", "text": "void helper() {}" }
    ]
  }
}
```
