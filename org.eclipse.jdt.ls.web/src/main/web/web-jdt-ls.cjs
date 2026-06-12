"use strict";

const DEFAULTS = {
	preferWasm: true,
	baseUrl: "./",
	wasmRuntimePath: "wasm/teavm/classes.wasm-runtime.js",
	wasmPath: "wasm/teavm/classes.wasm",
	jsPath: "js/teavm/classes.js"
};

let cached;

function load(options) {
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

function normalizeOptions(options) {
	const baseUrl = options?.baseUrl ?? DEFAULTS.baseUrl;
	return {
		preferWasm: options?.preferWasm !== false,
		wasmRuntimeUrl: options?.wasmRuntimeUrl ?? joinUrl(baseUrl, options?.wasmRuntimePath ?? DEFAULTS.wasmRuntimePath),
		wasmUrl: options?.wasmUrl ?? joinUrl(baseUrl, options?.wasmPath ?? DEFAULTS.wasmPath),
		jsUrl: options?.jsUrl ?? joinUrl(baseUrl, options?.jsPath ?? DEFAULTS.jsPath),
		wasmLoadOptions: options?.wasmLoadOptions
	};
}

function loadWasm(config) {
	if (!globalThis.WebAssembly) {
		return Promise.reject(new Error("WebAssembly is not available"));
	}
	return loadScript(config.wasmRuntimeUrl)
		.then(() => {
			const wasmGC = globalThis.TeaVM?.wasmGC;
			if (typeof wasmGC?.load !== "function") {
				throw new Error("TeaVM WebAssembly GC runtime did not initialize");
			}
			return wasmGC.load(config.wasmUrl, config.wasmLoadOptions);
		})
		.then(teavm => createApi("wasm", teavm.exports, teavm));
}

function loadJs(config) {
	return loadScript(config.jsUrl).then(() => createApi("js", globalThis, globalThis));
}

function createApi(target, exports, raw) {
	const missing = ["lint", "lintProcessing", "complete", "hover", "signatureHelp", "handle"].filter(name => typeof exports[name] !== "function");
	if (missing.length > 0) {
		throw new Error("JDT LS web " + target + " build is missing export(s): " + missing.join(", "));
	}
	return {
		target,
		raw,
		lint: exports.lint,
		lintProcessing: exports.lintProcessing,
		complete: exports.complete,
		hover: exports.hover,
		signatureHelp: exports.signatureHelp,
		handle: exports.handle
	};
}

function loadScript(url) {
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

	if (globalThis.document?.createElement) {
		return new Promise((resolve, reject) => {
			const script = globalThis.document.createElement("script");
			script.async = true;
			script.src = url;
			script.onload = () => resolve();
			script.onerror = () => reject(new Error("Failed to load " + url));
			globalThis.document.head.appendChild(script);
		});
	}
	return Promise.reject(new Error("No browser script loader is available"));
}

function joinUrl(baseUrl, path) {
	if (/^[a-z][a-z0-9+.-]*:/i.test(path) || path.indexOf("//") === 0) {
		return path;
	}
	return String(baseUrl).replace(/\/?$/, "/") + path.replace(/^\//, "");
}

module.exports = { load };
