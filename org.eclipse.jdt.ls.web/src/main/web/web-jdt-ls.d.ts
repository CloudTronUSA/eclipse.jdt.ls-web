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

export function load(options?: WebJdtLsLoadOptions): Promise<WebJdtLsApi>;
