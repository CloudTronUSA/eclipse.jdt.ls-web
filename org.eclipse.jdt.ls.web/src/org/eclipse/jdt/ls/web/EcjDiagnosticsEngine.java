package org.eclipse.jdt.ls.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

public final class EcjDiagnosticsEngine {

	private final Map<String, String> documents = new LinkedHashMap<>();
	private final Map<String, String> workspaceSources = new LinkedHashMap<>();
	private WebCompilerConfiguration compilerConfiguration = WebCompilerConfiguration.DEFAULT;

	public String lint(String uri, String source) {
		List<DiagnosticData> diagnostics = diagnose(uri, source, Collections.emptyMap());
		StringBuilder json = new StringBuilder();
		json.append('[');
		for (int i = 0; i < diagnostics.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			appendDiagnostic(json, diagnostics.get(i));
		}
		json.append(']');
		return json.toString();
	}

	public String complete(String uri, String source, int line, int character) {
		return new EcjCompletionEngine().complete(uri, source, line, character, allSourcesWith(uri, source));
	}

	public String hover(String uri, String source, int line, int character) {
		return new EcjCompletionEngine().hover(uri, source, line, character, allSourcesWith(uri, source));
	}

	public String signatureHelp(String uri, String source, int line, int character) {
		return new EcjCompletionEngine().signatureHelp(uri, source, line, character, allSourcesWith(uri, source));
	}

	public String lintProcessing(String entrypointUri, String entrypointSource, String[] additionalPdes) {
		ProcessingSketch sketch;
		List<ProcessingSketch.MappedDiagnostic> diagnostics;
		try {
			sketch = ProcessingSketch.from(entrypointUri, entrypointSource, additionalPdes);
			diagnostics = sketch.hasPreprocessDiagnostics()
					? sketch.preprocessDiagnostics()
					: sketch.mapDiagnostics(diagnoseCompilerOnly(sketch.compilationUnit(),
							sketch.source, Collections.emptyMap()));
		} catch (Throwable ex) {
			diagnostics = Collections.singletonList(new ProcessingSketch.MappedDiagnostic(entrypointUri,
					processingFailureDiagnostic(entrypointSource, ex)));
		}
		StringBuilder json = new StringBuilder();
		json.append('[');
		for (int i = 0; i < diagnostics.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			appendMappedDiagnostic(json, diagnostics.get(i));
		}
		json.append(']');
		return json.toString();
	}

	public String publishProcessingDiagnostics(String entrypointUri, String entrypointSource, String[] additionalPdes) {
		ProcessingSketch sketch;
		List<ProcessingSketch.MappedDiagnostic> diagnostics;
		String[] sourceUris;
		try {
			sketch = ProcessingSketch.from(entrypointUri, entrypointSource, additionalPdes);
			diagnostics = sketch.hasPreprocessDiagnostics()
					? sketch.preprocessDiagnostics()
					: sketch.mapDiagnostics(diagnoseCompilerOnly(sketch.compilationUnit(),
							sketch.source, Collections.emptyMap()));
			sourceUris = sketch.sourceUris();
		} catch (Throwable ex) {
			diagnostics = Collections.singletonList(new ProcessingSketch.MappedDiagnostic(entrypointUri,
					processingFailureDiagnostic(entrypointSource, ex)));
			sourceUris = processingSourceUris(entrypointUri, additionalPdes);
		}
		Map<String, List<DiagnosticData>> byUri = new LinkedHashMap<>();
		for (String uri : sourceUris) {
			byUri.put(uri, new ArrayList<>());
		}
		for (ProcessingSketch.MappedDiagnostic mapped : diagnostics) {
			List<DiagnosticData> uriDiagnostics = byUri.get(mapped.uri);
			if (uriDiagnostics == null) {
				uriDiagnostics = new ArrayList<>();
				byUri.put(mapped.uri, uriDiagnostics);
			}
			uriDiagnostics.add(mapped.diagnostic);
		}
		StringBuilder json = new StringBuilder();
		json.append('[');
		int index = 0;
		for (Map.Entry<String, List<DiagnosticData>> entry : byUri.entrySet()) {
			if (index++ > 0) {
				json.append(',');
			}
			appendPublishDiagnostics(json, entry.getKey(), entry.getValue());
		}
		json.append(']');
		return json.toString();
	}

	private static String[] processingSourceUris(String entrypointUri, String[] additionalPdes) {
		List<String> uris = new ArrayList<>();
		if (entrypointUri != null && !entrypointUri.isEmpty()) {
			uris.add(entrypointUri);
		}
		for (String pde : additionalPdes) {
			String uri = JsonSupport.stringField(pde, "uri");
			if (uri.isEmpty()) {
				uri = entrypointUri;
			}
			if (uri != null && !uri.isEmpty() && !uris.contains(uri)) {
				uris.add(uri);
			}
		}
		return uris.toArray(new String[0]);
	}

	private static DiagnosticData processingFailureDiagnostic(String source, Throwable ex) {
		String message = ex.getMessage();
		if (message == null || message.isEmpty()) {
			message = ex.getClass().getSimpleName();
		}
		int line = 0;
		int character = 0;
		int endCharacter = 1;
		if (source != null && !source.isEmpty()) {
			int newline = source.indexOf('\n');
			endCharacter = Math.max(1, newline >= 0 ? newline : source.length());
		}
		endCharacter = Math.max(character + 1, endCharacter);
		return new DiagnosticData(line, character, line, endCharacter, 1, 0,
				"Processing preprocessing failed: " + message);
	}

	public String publishDiagnostics(String uri, String source) {
		documents.put(uri, source);
		return publishAllDiagnostics();
	}

	public String completion(String uri, int line, int character) {
		String source = documents.get(uri);
		if (source == null) {
			source = workspaceSources.get(uri);
		}
		if (source == null) {
			source = "";
		}
		return new EcjCompletionEngine().complete(uri, source, line, character, allSources());
	}

	public String hover(String uri, int line, int character) {
		String source = documents.get(uri);
		if (source == null) {
			source = workspaceSources.get(uri);
		}
		if (source == null) {
			source = "";
		}
		return new EcjCompletionEngine().hover(uri, source, line, character, allSources());
	}

	public String signatureHelp(String uri, int line, int character) {
		String source = documents.get(uri);
		if (source == null) {
			source = workspaceSources.get(uri);
		}
		if (source == null) {
			source = "";
		}
		return new EcjCompletionEngine().signatureHelp(uri, source, line, character, allSources());
	}

	public String publishDiagnostics(String uri) {
		String source = documents.get(uri);
		if (source == null) {
			source = "";
		}
		return publishDiagnostics(uri, diagnose(uri, source, allSources()));
	}

	private String publishAllDiagnostics() {
		StringBuilder json = new StringBuilder();
		json.append('[');
		int index = 0;
		Map<String, String> allSources = allSources();
		for (Map.Entry<String, String> entry : allSources.entrySet()) {
			if (index++ > 0) {
				json.append(',');
			}
			appendPublishDiagnostics(json, entry.getKey(), diagnose(entry.getKey(), entry.getValue(), allSources));
		}
		json.append(']');
		return json.toString();
	}

	private String publishDiagnostics(String uri, List<DiagnosticData> diagnostics) {
		StringBuilder json = new StringBuilder();
		appendPublishDiagnostics(json, uri, diagnostics);
		return json.toString();
	}

	private void appendPublishDiagnostics(StringBuilder json, String uri, List<DiagnosticData> diagnostics) {
		json.append("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":");
		JsonSupport.appendString(json, uri);
		json.append(",\"diagnostics\":");
		appendDiagnostics(json, diagnostics);
		json.append("}}");
	}

	public String close(String uri) {
		documents.remove(uri);
		StringBuilder json = new StringBuilder();
		json.append('[');
		Map<String, String> allSources = allSources();
		String restoredSource = allSources.get(uri);
		if (restoredSource == null) {
			appendPublishDiagnostics(json, uri, Collections.emptyList());
		} else {
			appendPublishDiagnostics(json, uri, diagnose(uri, restoredSource, allSources));
		}
		for (Map.Entry<String, String> entry : allSources.entrySet()) {
			if (entry.getKey().equals(uri)) {
				continue;
			}
			json.append(',');
			appendPublishDiagnostics(json, entry.getKey(), diagnose(entry.getKey(), entry.getValue(), allSources));
		}
		json.append(']');
		return json.toString();
	}

	public String updateWorkspaceSource(String uri, String source) {
		workspaceSources.put(uri, source);
		return publishAllDiagnostics();
	}

	public String removeWorkspaceSource(String uri) {
		workspaceSources.remove(uri);
		documents.remove(uri);
		StringBuilder json = new StringBuilder();
		json.append('[');
		appendPublishDiagnostics(json, uri, Collections.emptyList());
		Map<String, String> allSources = allSources();
		for (Map.Entry<String, String> entry : allSources.entrySet()) {
			json.append(',');
			appendPublishDiagnostics(json, entry.getKey(), diagnose(entry.getKey(), entry.getValue(), allSources));
		}
		json.append(']');
		return json.toString();
	}

	public String renameWorkspaceSource(String oldUri, String newUri, String source) {
		workspaceSources.remove(oldUri);
		documents.remove(oldUri);
		workspaceSources.put(newUri, source);
		StringBuilder json = new StringBuilder();
		json.append('[');
		appendPublishDiagnostics(json, oldUri, Collections.emptyList());
		Map<String, String> allSources = allSources();
		for (Map.Entry<String, String> entry : allSources.entrySet()) {
			json.append(',');
			appendPublishDiagnostics(json, entry.getKey(), diagnose(entry.getKey(), entry.getValue(), allSources));
		}
		json.append(']');
		return json.toString();
	}

	public String changeWatchedFiles(String[] changes) {
		StringBuilder json = new StringBuilder();
		json.append('[');
		int index = 0;
		for (String change : changes) {
			String uri = JsonSupport.stringField(change, "uri");
			int type = JsonSupport.intField(change, "type");
			if (uri.isEmpty()) {
				continue;
			}
			if (type == 3) {
				workspaceSources.remove(uri);
				documents.remove(uri);
				if (index++ > 0) {
					json.append(',');
				}
				appendPublishDiagnostics(json, uri, Collections.emptyList());
			} else if (type == 1 || type == 2) {
				String text = JsonSupport.lastStringField(change, "text");
				if (!text.isEmpty() || change.indexOf("\"text\"") >= 0) {
					workspaceSources.put(uri, text);
				}
			}
		}
		Map<String, String> allSources = allSources();
		for (Map.Entry<String, String> entry : allSources.entrySet()) {
			if (index++ > 0) {
				json.append(',');
			}
			appendPublishDiagnostics(json, entry.getKey(), diagnose(entry.getKey(), entry.getValue(), allSources));
		}
		json.append(']');
		return json.toString();
	}

	public String configure(String payload) {
		compilerConfiguration = compilerConfiguration.withSettings(payload);
		return publishAllDiagnostics();
	}

	private Map<String, String> allSources() {
		Map<String, String> allSources = new LinkedHashMap<>(workspaceSources);
		allSources.putAll(documents);
		return allSources;
	}

	private Map<String, String> allSourcesWith(String uri, String source) {
		Map<String, String> allSources = allSources();
		allSources.put(uri, source);
		return allSources;
	}

	private void appendDiagnostics(StringBuilder json, List<DiagnosticData> diagnostics) {
		json.append('[');
		for (int i = 0; i < diagnostics.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			appendDiagnostic(json, diagnostics.get(i));
		}
		json.append(']');
	}

	private List<DiagnosticData> diagnose(String uri, String source, Map<String, String> workspaceSources) {
		MemoryCompilationUnit unit = MemoryCompilationUnit.from(uri, source);
		return diagnose(unit, source, workspaceSources);
	}

	private List<DiagnosticData> diagnose(MemoryCompilationUnit unit, String source, Map<String, String> workspaceSources) {
		Map<String, MemoryCompilationUnit> workspaceUnits = workspaceUnits(workspaceSources);
		try {
			List<CategorizedProblem> compilerProblems = new EcjCompilerDiagnostics().diagnose(unit, workspaceUnits,
					compilerConfiguration);
			List<DiagnosticData> compilerDiagnostics = toDiagnostics(source, compilerProblems);
			if (!compilerDiagnostics.isEmpty()) {
				if (hasError(compilerProblems)) {
					compilerDiagnostics.addAll(filterSupplementalDiagnostics(
							parseSupplementalDiagnostics(unit, source, workspaceUnits),
							compilerDiagnostics));
				}
				return compilerDiagnostics;
			}
		} catch (Throwable ignored) {
		}
		return parseSupplementalDiagnostics(unit, source, workspaceUnits);
	}

	private List<DiagnosticData> diagnoseCompilerOnly(MemoryCompilationUnit unit, String source,
			Map<String, String> workspaceSources) {
		try {
			return toDiagnostics(source, new EcjCompilerDiagnostics().diagnose(unit, workspaceUnits(workspaceSources),
					compilerConfiguration));
		} catch (Throwable ignored) {
			return parseSupplementalDiagnostics(unit, source, workspaceUnits(workspaceSources));
		}
	}

	private Map<String, MemoryCompilationUnit> workspaceUnits(Map<String, String> workspaceSources) {
		Map<String, MemoryCompilationUnit> units = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : workspaceSources.entrySet()) {
			units.put(entry.getKey(), MemoryCompilationUnit.from(entry.getKey(), entry.getValue()));
		}
		return units;
	}

	private List<DiagnosticData> parseSupplementalDiagnostics(MemoryCompilationUnit unit, String source,
			Map<String, MemoryCompilationUnit> workspaceUnits) {
		CompilerOptions options = compilerConfiguration.compilerOptions();

		CompilationResult result = new CompilationResult(unit, 0, 1, 100);
		ProblemReporter reporter = new ProblemReporter(
				org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies.proceedWithAllProblems(),
				options,
				new WebProblemFactory());
		Parser parser = new Parser(reporter, false);
		parser.reportOnlyOneSyntaxError = false;
		CompilationUnitDeclaration parsedUnit = parser.parse(unit, result);

		List<CategorizedProblem> problems = new ArrayList<>();
		collect(result, problems);
		Collections.sort(problems, Comparator.comparingInt(CategorizedProblem::getSourceStart));
		List<DiagnosticData> diagnostics = toDiagnostics(source, problems);
		if (parsedUnit != null) {
			diagnostics.addAll(new EcjSemanticDiagnostics(source, typeNames(workspaceUnits)).diagnose(parsedUnit));
		}
		return diagnostics;
	}

	private static List<String> typeNames(Map<String, MemoryCompilationUnit> workspaceUnits) {
		List<String> typeNames = new ArrayList<>(workspaceUnits.size());
		for (MemoryCompilationUnit unit : workspaceUnits.values()) {
			String[] topLevelTypeNames = unit.topLevelTypeNames();
			if (topLevelTypeNames.length == 0) {
				typeNames.add(new String(unit.getMainTypeName()));
			} else {
				typeNames.addAll(Arrays.asList(topLevelTypeNames));
			}
		}
		return typeNames;
	}

	private static void collect(CompilationResult result, List<CategorizedProblem> problems) {
		CategorizedProblem[] reported = result.getAllProblems();
		if (reported != null) {
			problems.addAll(Arrays.asList(reported));
		}
	}

	private static boolean hasError(List<CategorizedProblem> problems) {
		for (CategorizedProblem problem : problems) {
			if (problem.isError()) {
				return true;
			}
		}
		return false;
	}

	private static List<DiagnosticData> filterSupplementalDiagnostics(List<DiagnosticData> supplemental,
			List<DiagnosticData> compilerDiagnostics) {
		List<DiagnosticData> filtered = new ArrayList<>();
		for (DiagnosticData diagnostic : supplemental) {
			if (diagnostic.code == 16777233 || containsEquivalent(compilerDiagnostics, diagnostic)) {
				continue;
			}
			filtered.add(diagnostic);
		}
		return filtered;
	}

	private static boolean containsEquivalent(List<DiagnosticData> diagnostics, DiagnosticData candidate) {
		for (DiagnosticData diagnostic : diagnostics) {
			if (diagnostic.startLine == candidate.startLine
					&& diagnostic.startCharacter == candidate.startCharacter
					&& diagnostic.endLine == candidate.endLine
					&& diagnostic.endCharacter == candidate.endCharacter
					&& diagnostic.severity == candidate.severity
					&& diagnostic.message.equals(candidate.message)) {
				return true;
			}
		}
		return false;
	}

	private static DiagnosticData toDiagnostic(String source, CategorizedProblem problem) {
		int start = Math.max(0, problem.getSourceStart());
		int end = Math.max(start, problem.getSourceEnd() + 1);
		Position startPosition = position(source, start);
		Position endPosition = position(source, end);
		return new DiagnosticData(
				startPosition.line,
				startPosition.character,
				endPosition.line,
				endPosition.character,
				problem.isError() ? 1 : problem.isWarning() ? 2 : 3,
				problem.getID() & IProblem.IgnoreCategoriesMask,
				problem.getMessage());
	}

	private static List<DiagnosticData> toDiagnostics(String source, List<CategorizedProblem> problems) {
		List<DiagnosticData> diagnostics = new ArrayList<>(problems.size());
		for (CategorizedProblem problem : problems) {
			diagnostics.add(toDiagnostic(source, problem));
		}
		return diagnostics;
	}

	private static Position position(String source, int offset) {
		int line = 0;
		int character = 0;
		int max = Math.min(offset, source.length());
		for (int i = 0; i < max; i++) {
			char c = source.charAt(i);
			if (c == '\n') {
				line++;
				character = 0;
			} else {
				character++;
			}
		}
		return new Position(line, character);
	}

	private static void appendDiagnostic(StringBuilder json, DiagnosticData diagnostic) {
		json.append("{\"range\":{\"start\":{\"line\":").append(diagnostic.startLine)
				.append(",\"character\":").append(diagnostic.startCharacter)
				.append("},\"end\":{\"line\":").append(diagnostic.endLine)
				.append(",\"character\":").append(diagnostic.endCharacter)
				.append("}},\"severity\":").append(diagnostic.severity)
				.append(",\"source\":\"Java\",\"code\":").append(diagnostic.code)
				.append(",\"message\":");
		JsonSupport.appendString(json, diagnostic.message);
		json.append('}');
	}

	private static void appendMappedDiagnostic(StringBuilder json, ProcessingSketch.MappedDiagnostic mapped) {
		json.append("{\"uri\":");
		JsonSupport.appendString(json, mapped.uri);
		json.append(",\"range\":{\"start\":{\"line\":").append(mapped.diagnostic.startLine)
				.append(",\"character\":").append(mapped.diagnostic.startCharacter)
				.append("},\"end\":{\"line\":").append(mapped.diagnostic.endLine)
				.append(",\"character\":").append(mapped.diagnostic.endCharacter)
				.append("}},\"severity\":").append(mapped.diagnostic.severity)
				.append(",\"source\":\"Java\",\"code\":").append(mapped.diagnostic.code)
				.append(",\"message\":");
		JsonSupport.appendString(json, mapped.diagnostic.message);
		json.append('}');
	}

	static final class WebProblemFactory implements IProblemFactory {
		private static final int IGNORE_CATEGORIES_MASK = IProblem.IgnoreCategoriesMask;
		private static final String MESSAGES_RESOURCE = "org/eclipse/jdt/internal/compiler/problem/messages.properties";
		private static final Map<Integer, String> TEMPLATES = loadTemplates();

		@Override
		public CategorizedProblem createProblem(char[] originatingFileName, int problemId, String[] problemArguments,
				String[] messageArguments, int severity, int startPosition, int endPosition, int lineNumber,
				int columnNumber) {
			return createProblem(originatingFileName, problemId, problemArguments, 0, messageArguments, severity,
					startPosition, endPosition, lineNumber, columnNumber);
		}

		@Override
		public CategorizedProblem createProblem(char[] originatingFileName, int problemId, String[] problemArguments,
				int elaborationId, String[] messageArguments, int severity, int startPosition, int endPosition,
				int lineNumber, int columnNumber) {
			return new DefaultProblem(originatingFileName, getLocalizedMessage(problemId, elaborationId, messageArguments),
					problemId, problemArguments, severity, startPosition, endPosition, lineNumber, columnNumber);
		}

		@Override
		public Locale getLocale() {
			return Locale.ENGLISH;
		}

		@Override
		public String getLocalizedMessage(int problemId, String[] problemArguments) {
			return getLocalizedMessage(problemId, 0, problemArguments);
		}

		@Override
		public String getLocalizedMessage(int problemId, int elaborationId, String[] problemArguments) {
			int maskedProblemId = problemId & IGNORE_CATEGORIES_MASK;
			String template = TEMPLATES.get(Integer.valueOf(maskedProblemId));
			if (template == null) {
				return fallbackMessage(problemId, problemArguments);
			}
			if (elaborationId != 0) {
				String elaboration = TEMPLATES.get(Integer.valueOf(elaborationId));
				if (elaboration != null) {
					template = replace(template, "{0}", elaboration);
				}
			}
			return bind(template.replace("''", "'"), problemArguments, maskedProblemId);
		}

		private static Map<Integer, String> loadTemplates() {
			Map<Integer, String> templates = new HashMap<>();
			InputStream input = EcjDiagnosticsEngine.class.getClassLoader().getResourceAsStream(MESSAGES_RESOURCE);
			if (input == null) {
				return templates;
			}
			try {
				String content = new String(readAll(input), "ISO-8859-1");
				int lineStart = 0;
				while (lineStart <= content.length()) {
					int lineEnd = content.indexOf('\n', lineStart);
					if (lineEnd < 0) {
						lineEnd = content.length();
					}
					parseLine(templates, content.substring(lineStart, lineEnd));
					lineStart = lineEnd + 1;
				}
			} catch (IOException ignored) {
			}
			return templates;
		}

		private static byte[] readAll(InputStream input) throws IOException {
			try {
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				while (true) {
					int read = input.read(buffer);
					if (read < 0) {
						return output.toByteArray();
					}
					output.write(buffer, 0, read);
				}
			} finally {
				input.close();
			}
		}

		private static void parseLine(Map<Integer, String> templates, String line) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
				return;
			}
			int separator = separator(trimmed);
			if (separator <= 0) {
				return;
			}
			try {
				int key = Integer.parseInt(trimmed.substring(0, separator).trim());
				templates.put(Integer.valueOf(key), unescape(trimmed.substring(separator + 1).trim()));
			} catch (NumberFormatException ignored) {
			}
		}

		private static int separator(String line) {
			int equals = line.indexOf('=');
			int colon = line.indexOf(':');
			if (equals < 0) {
				return colon;
			}
			if (colon < 0) {
				return equals;
			}
			return Math.min(equals, colon);
		}

		private static String unescape(String value) {
			StringBuilder result = new StringBuilder(value.length());
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (c != '\\' || i + 1 >= value.length()) {
					result.append(c);
					continue;
				}
				char escaped = value.charAt(++i);
				switch (escaped) {
					case 'n':
						result.append('\n');
						break;
					case 'r':
						result.append('\r');
						break;
					case 't':
						result.append('\t');
						break;
					case 'u':
						if (i + 4 < value.length()) {
							result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
							i += 4;
						}
						break;
					default:
						result.append(escaped);
				}
			}
			return result.toString();
		}

		private static String bind(String template, String[] arguments, int problemId) {
			if (arguments == null || arguments.length == 0) {
				return template;
			}
			StringBuilder result = new StringBuilder(template.length() + arguments.length * 20);
			int index = 0;
			while (index < template.length()) {
				int start = template.indexOf('{', index);
				if (start < 0) {
					result.append(template.substring(index));
					return result.toString();
				}
				result.append(template.substring(index, start));
				int end = template.indexOf('}', start + 1);
				if (end < 0) {
					result.append(template.substring(start));
					return result.toString();
				}
				try {
					int argumentIndex = Integer.parseInt(template.substring(start + 1, end));
					result.append(arguments[argumentIndex]);
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
					return "Cannot bind message for problem " + problemId + " with arguments " + argumentList(arguments)
							+ ": " + template;
				}
				index = end + 1;
			}
			return result.toString();
		}

		private static String replace(String value, String target, String replacement) {
			int index = value.indexOf(target);
			if (index < 0) {
				return value;
			}
			return value.substring(0, index) + replacement + value.substring(index + target.length());
		}

		private static String fallbackMessage(int problemId, String[] problemArguments) {
			StringBuilder message = new StringBuilder();
			if ((problemId & IProblem.Syntax) != 0) {
				message.append("Syntax error");
			} else {
				message.append("Java problem ").append(problemId & IGNORE_CATEGORIES_MASK);
			}
			if (problemArguments != null && problemArguments.length > 0) {
				message.append(": ").append(argumentList(problemArguments));
			}
			return message.toString();
		}

		private static String argumentList(String[] arguments) {
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < arguments.length; i++) {
				if (i > 0) {
					result.append(", ");
				}
				result.append(arguments[i]);
			}
			return result.toString();
		}
	}

	static final class DiagnosticData {
		final int startLine;
		final int startCharacter;
		final int endLine;
		final int endCharacter;
		final int severity;
		final int code;
		final String message;

		DiagnosticData(int startLine, int startCharacter, int endLine, int endCharacter, int severity, int code,
				String message) {
			this.startLine = startLine;
			this.startCharacter = startCharacter;
			this.endLine = endLine;
			this.endCharacter = endCharacter;
			this.severity = severity;
			this.code = code;
			this.message = message;
		}
	}

	static final class Position {
		final int line;
		final int character;

		Position(int line, int character) {
			this.line = line;
			this.character = character;
		}
	}
}
