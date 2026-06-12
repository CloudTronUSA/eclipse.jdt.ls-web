export interface WebJdtLsApi {
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

export interface WebJdtLsLoadOptions {
	preferWasm?: boolean;
	baseUrl?: string;
	wasmRuntimePath?: string;
	wasmPath?: string;
	jsPath?: string;
	wasmRuntimeUrl?: string;
	wasmUrl?: string;
	jsUrl?: string;
	wasmLoadOptions?: unknown;
}

interface NormalizedOptions {
	preferWasm: boolean;
	wasmRuntimeUrl: string;
	wasmUrl: string;
	jsUrl: string;
	wasmLoadOptions?: unknown;
}

interface TeaVMWasmInstance {
	exports: Record<string, unknown>;
	instance: WebAssembly.Instance;
	module: WebAssembly.Module;
}

interface TeaVMGlobal {
	TeaVM?: {
		wasmGC?: {
			load(src: string, options?: unknown): Promise<TeaVMWasmInstance>;
		};
	};
	lint?: unknown;
	lintProcessing?: unknown;
	complete?: unknown;
	hover?: unknown;
	signatureHelp?: unknown;
	handle?: unknown;
	document?: Document;
}

declare const importScripts: ((...urls: string[]) => void) | undefined;

const DEFAULTS = {
	preferWasm: true,
	baseUrl: new URL("./", import.meta.url).href,
	wasmRuntimePath: "wasm/teavm/classes.wasm-runtime.js",
	wasmPath: "wasm/teavm/classes.wasm",
	jsPath: "js/teavm/classes.js"
};

let cached: Promise<WebJdtLsApi> | undefined;

export function load(options?: WebJdtLsLoadOptions): Promise<WebJdtLsApi> {
	if (cached && options == null) {
		return cached;
	}

	const config = normalizeOptions(options);
	const result = config.preferWasm
		? loadWasm(config).catch(error => loadJs(config).then(api => {
			api.fallbackError = error;
			return api;
		}))
		: loadJs(config);

	if (options == null) {
		cached = result;
	}
	return result;
}

function normalizeOptions(options?: WebJdtLsLoadOptions): NormalizedOptions {
	const baseUrl = options?.baseUrl ?? DEFAULTS.baseUrl;
	return {
		preferWasm: options?.preferWasm !== false,
		wasmRuntimeUrl: options?.wasmRuntimeUrl ?? joinUrl(baseUrl, options?.wasmRuntimePath ?? DEFAULTS.wasmRuntimePath),
		wasmUrl: options?.wasmUrl ?? joinUrl(baseUrl, options?.wasmPath ?? DEFAULTS.wasmPath),
		jsUrl: options?.jsUrl ?? joinUrl(baseUrl, options?.jsPath ?? DEFAULTS.jsPath),
		wasmLoadOptions: options?.wasmLoadOptions
	};
}

function loadWasm(config: NormalizedOptions): Promise<WebJdtLsApi> {
	const root = globalThis as TeaVMGlobal;
	if (!globalThis.WebAssembly) {
		return Promise.reject(new Error("WebAssembly is not available"));
	}
	return loadScript(config.wasmRuntimeUrl)
		.then(() => {
			const wasmGC = root.TeaVM?.wasmGC;
			if (typeof wasmGC?.load !== "function") {
				throw new Error("TeaVM WebAssembly GC runtime did not initialize");
			}
			return wasmGC.load(config.wasmUrl, config.wasmLoadOptions);
		})
		.then(teavm => createApi("wasm", teavm.exports, teavm));
}

function loadJs(config: NormalizedOptions): Promise<WebJdtLsApi> {
	return loadScript(config.jsUrl).then(() => createApi("js", globalThis as Record<string, unknown>, globalThis));
}

function createApi(target: "wasm" | "js", exports: Record<string, unknown>, raw: unknown): WebJdtLsApi {
	const missing = ["lint", "lintProcessing", "complete", "hover", "signatureHelp", "handle"]
		.filter(name => typeof exports[name] !== "function");
	if (missing.length > 0) {
		throw new Error("JDT LS web " + target + " build is missing export(s): " + missing.join(", "));
	}
	return {
		target,
		raw,
		lint: exports.lint as WebJdtLsApi["lint"],
		lintProcessing: exports.lintProcessing as WebJdtLsApi["lintProcessing"],
		complete: exports.complete as WebJdtLsApi["complete"],
		hover: exports.hover as WebJdtLsApi["hover"],
		signatureHelp: exports.signatureHelp as WebJdtLsApi["signatureHelp"],
		handle: exports.handle as WebJdtLsApi["handle"]
	};
}

function loadScript(url: string): Promise<void> {
	if (typeof importScripts === "function") {
		return new Promise((resolve, reject) => {
			try {
				importScripts(url);
				resolve();
			} catch (error) {
				reject(error);
			}
		});
	}

	const root = globalThis as TeaVMGlobal;
	if (root.document?.createElement) {
		return new Promise((resolve, reject) => {
			const script = root.document!.createElement("script");
			script.async = true;
			script.src = url;
			script.onload = () => resolve();
			script.onerror = () => reject(new Error("Failed to load " + url));
			root.document!.head.appendChild(script);
		});
	}
	return Promise.reject(new Error("No browser script loader is available"));
}

function joinUrl(baseUrl: string, path: string): string {
	if (/^[a-z][a-z0-9+.-]*:/i.test(path) || path.indexOf("//") === 0) {
		return path;
	}
	return String(baseUrl).replace(/\/?$/, "/") + path.replace(/^\//, "");
}
