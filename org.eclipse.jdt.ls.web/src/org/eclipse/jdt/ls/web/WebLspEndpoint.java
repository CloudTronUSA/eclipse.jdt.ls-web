package org.eclipse.jdt.ls.web;

final class WebLspEndpoint {

	private WebLspEndpoint() {
	}

	static String handle(String payload, EcjDiagnosticsEngine engine) {
		String method = JsonSupport.stringField(payload, "method");
		if ("initialize".equals(method)) {
			return "{\"jsonrpc\":\"2.0\",\"id\":" + JsonSupport.idField(payload)
					+ ",\"result\":{\"capabilities\":{\"textDocumentSync\":{\"openClose\":true,\"change\":1},"
					+ "\"completionProvider\":{\"resolveProvider\":false,\"triggerCharacters\":[\".\",\"@\"]},"
					+ "\"hoverProvider\":true,"
					+ "\"signatureHelpProvider\":{\"triggerCharacters\":[\"(\",\",\"],\"retriggerCharacters\":[\",\"]}}}}";
		}
		if ("initialized".equals(method) || "exit".equals(method)) {
			return "";
		}
		if ("textDocument/didClose".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			return engine.close(uri);
		}
		if ("textDocument/didOpen".equals(method) || "textDocument/didChange".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			String text = JsonSupport.lastStringField(payload, "text");
			return engine.publishDiagnostics(uri, text);
		}
		if ("textDocument/completion".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			int line = JsonSupport.intField(payload, "line");
			int character = JsonSupport.intField(payload, "character");
			return "{\"jsonrpc\":\"2.0\",\"id\":" + JsonSupport.idField(payload) + ",\"result\":"
					+ engine.completion(uri, line, character) + "}";
		}
		if ("textDocument/hover".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			int line = JsonSupport.intField(payload, "line");
			int character = JsonSupport.intField(payload, "character");
			return "{\"jsonrpc\":\"2.0\",\"id\":" + JsonSupport.idField(payload) + ",\"result\":"
					+ engine.hover(uri, line, character) + "}";
		}
		if ("textDocument/signatureHelp".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			int line = JsonSupport.intField(payload, "line");
			int character = JsonSupport.intField(payload, "character");
			return "{\"jsonrpc\":\"2.0\",\"id\":" + JsonSupport.idField(payload) + ",\"result\":"
					+ engine.signatureHelp(uri, line, character) + "}";
		}
		if ("java/browserJdtLs/workspaceSources".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			String text = JsonSupport.lastStringField(payload, "text");
			return engine.updateWorkspaceSource(uri, text);
		}
		if ("java/browserJdtLs/removeWorkspaceSource".equals(method)) {
			String uri = JsonSupport.stringField(payload, "uri");
			return engine.removeWorkspaceSource(uri);
		}
		if ("java/browserJdtLs/renameWorkspaceSource".equals(method)) {
			String oldUri = JsonSupport.stringField(payload, "oldUri");
			String newUri = JsonSupport.stringField(payload, "newUri");
			String text = JsonSupport.lastStringField(payload, "text");
			return engine.renameWorkspaceSource(oldUri, newUri, text);
		}
		if ("workspace/didChangeWatchedFiles".equals(method)) {
			return engine.changeWatchedFiles(JsonSupport.objectsInArrayField(payload, "changes"));
		}
		if ("java/webJdtLs/processingSketch".equals(method)) {
			String uri = JsonSupport.stringField(payload, "entrypointUri");
			String text = JsonSupport.stringField(payload, "entrypointText");
			return engine.publishProcessingDiagnostics(uri, text, JsonSupport.objectsInArrayField(payload, "sources"));
		}
		if ("workspace/didChangeConfiguration".equals(method)) {
			return engine.configure(payload);
		}
		if ("shutdown".equals(method)) {
			return "{\"jsonrpc\":\"2.0\",\"id\":" + JsonSupport.idField(payload) + ",\"result\":null}";
		}
		String id = JsonSupport.idField(payload);
		if ("null".equals(id)) {
			return "";
		}
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"Unsupported method\"}}";
	}
}
