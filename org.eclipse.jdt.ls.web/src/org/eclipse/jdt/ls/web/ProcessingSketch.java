package org.eclipse.jdt.ls.web;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import processing.mode.java.preproc.PdePreprocessIssue;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.PreprocessorResult;
import processing.mode.java.preproc.TextTransform;
import processing.mode.java.preproc.TextTransform.OffsetMapper;
import processing.utils.SketchException;

final class ProcessingSketch {

	private static final String SKETCH_CLASS = "Sketch";

	final String uri;
	final String source;
	private final String unifiedSource;
	private final OffsetMapper offsetMapper;
	private final List<PdeTab> tabs;
	private final String[] sourceUris;
	private final List<MappedDiagnostic> preprocessDiagnostics;

	private ProcessingSketch(String uri, String source, String unifiedSource, OffsetMapper offsetMapper, List<PdeTab> tabs,
			String[] sourceUris, List<MappedDiagnostic> preprocessDiagnostics) {
		this.uri = uri;
		this.source = source;
		this.unifiedSource = unifiedSource;
		this.offsetMapper = offsetMapper;
		this.tabs = tabs;
		this.sourceUris = sourceUris;
		this.preprocessDiagnostics = preprocessDiagnostics;
	}

	static ProcessingSketch from(String entrypointUri, String entrypointSource, String[] additionalPdes) {
		List<PdeSource> pdes = new ArrayList<>();
		pdes.add(new PdeSource(entrypointUri, entrypointSource));
		for (String pde : additionalPdes) {
			String uri = JsonSupport.stringField(pde, "uri");
			if (uri.isEmpty()) {
				uri = entrypointUri;
			}
			pdes.add(new PdeSource(uri, JsonSupport.lastStringField(pde, "text")));
		}

		StringBuilder unified = new StringBuilder();
		List<PdeTab> tabs = new ArrayList<>();
		int startLine = 0;
		int startOffset = 0;
		for (PdeSource pde : pdes) {
			tabs.add(new PdeTab(pde.uri, startLine, startOffset, pde.source.length()));
			unified.append(pde.source).append('\n');
			startLine += lineCount(pde.source) + 1;
			startOffset = unified.length();
		}

		StringWriter writer = new StringWriter();
		PreprocessorResult result;
		try {
			result = PdePreprocessor.builderFor(SKETCH_CLASS)
					.setTabSize(2)
					.setParseTreeListenerFactory(WebPdeParseTreeListener::new)
					.build()
					.write(writer, unified.toString());
		} catch (SketchException ex) {
			return new ProcessingSketch(syntheticUri(entrypointUri), "",
					unified.toString(), OffsetMapper.EMPTY_MAPPER, tabs,
					sourceUris(pdes), sketchExceptionDiagnostics(ex, tabs, unified.toString(), entrypointUri));
		}

		TextTransform transform = new TextTransform(unified.toString());
		transform.addAll(result.getEdits());
		List<MappedDiagnostic> preprocessDiagnostics = new ArrayList<>();
		for (PdePreprocessIssue issue : result.getPreprocessIssues()) {
			OriginalPosition position = positionForIssue(issue, tabs, unified.toString(), entrypointUri);
			preprocessDiagnostics.add(new MappedDiagnostic(position.uri,
					new EcjDiagnosticsEngine.DiagnosticData(position.line, position.character,
							position.line, position.character + 1, 1, 0, issue.getMsg())));
		}
		String generatedSource = writer.toString();
		if (generatedSource.isEmpty()) {
			generatedSource = transform.apply();
		}
		return new ProcessingSketch(syntheticUri(entrypointUri), generatedSource,
				unified.toString(), transform.getMapper(), tabs,
				sourceUris(pdes), preprocessDiagnostics);
	}

	boolean hasPreprocessDiagnostics() {
		return !preprocessDiagnostics.isEmpty();
	}

	List<MappedDiagnostic> preprocessDiagnostics() {
		return preprocessDiagnostics;
	}

	private static List<MappedDiagnostic> sketchExceptionDiagnostics(SketchException ex, List<PdeTab> tabs,
			String unifiedSource, String fallbackUri) {
		List<MappedDiagnostic> diagnostics = new ArrayList<>();
		OriginalPosition position;
		if (ex.hasCodeIndex()) {
			int index = Math.max(0, Math.min(ex.getCodeIndex(), tabs.size() - 1));
			PdeTab tab = tabs.isEmpty() ? new PdeTab(fallbackUri, 0, 0, 0) : tabs.get(index);
			position = new OriginalPosition(tab.uri, Math.max(0, ex.getCodeLine()), Math.max(0, ex.getCodeColumn()));
		} else if (ex.hasCodeLine()) {
			position = positionForUnifiedLine(ex.getCodeLine(), Math.max(0, ex.getCodeColumn()), tabs, fallbackUri);
		} else {
			position = positionAtOffset(0, tabs, unifiedSource, fallbackUri);
		}
		diagnostics.add(new MappedDiagnostic(position.uri,
				new EcjDiagnosticsEngine.DiagnosticData(position.line, position.character,
						position.line, position.character + 1, 1, 0, ex.getMessage())));
		return diagnostics;
	}

	private static OriginalPosition positionForIssue(PdePreprocessIssue issue, List<PdeTab> tabs, String unifiedSource,
			String fallbackUri) {
		int line = Math.max(0, issue.getLine() - 1);
		int offset = offset(unifiedSource, line, Math.max(0, issue.getCharPositionInLine()));
		return positionAtOffset(offset, tabs, unifiedSource, fallbackUri);
	}

	private static OriginalPosition positionForUnifiedLine(int unifiedLine, int character, List<PdeTab> tabs,
			String fallbackUri) {
		PdeTab selected = null;
		for (PdeTab tab : tabs) {
			if (unifiedLine >= tab.startLine) {
				selected = tab;
			}
		}
		if (selected == null) {
			return new OriginalPosition(fallbackUri, 0, character);
		}
		return new OriginalPosition(selected.uri, Math.max(0, unifiedLine - selected.startLine), character);
	}

	MemoryCompilationUnit compilationUnit() {
		return new MemoryCompilationUnit(source, SKETCH_CLASS + ".java");
	}

	String[] sourceUris() {
		return sourceUris;
	}

	List<MappedDiagnostic> mapDiagnostics(List<EcjDiagnosticsEngine.DiagnosticData> diagnostics) {
		List<MappedDiagnostic> mapped = new ArrayList<>(diagnostics.size());
		for (EcjDiagnosticsEngine.DiagnosticData diagnostic : diagnostics) {
			OriginalPosition start = generatedPositionToOriginal(diagnostic.startLine, diagnostic.startCharacter);
			OriginalPosition end = generatedPositionToOriginal(diagnostic.endLine, diagnostic.endCharacter);
			if (start == null) {
				continue;
			}
			if (end == null || !start.uri.equals(end.uri) || end.line < start.line) {
				end = new OriginalPosition(start.uri, start.line, start.character + 1);
			}
			mapped.add(new MappedDiagnostic(start.uri,
					new EcjDiagnosticsEngine.DiagnosticData(start.line, start.character,
							end.line, Math.max(end.character, start.character + 1),
							diagnostic.severity, diagnostic.code,
							diagnostic.message)));
		}
		return mapped;
	}

	private OriginalPosition generatedPositionToOriginal(int generatedLine, int generatedCharacter) {
		int generatedOffset = offset(source, generatedLine, generatedCharacter);
		int inputOffset = offsetMapper.getInputOffset(generatedOffset);
		if (inputOffset < 0) {
			return null;
		}
		return positionAtOffset(inputOffset, tabs, unifiedSource, sourceUris.length == 0 ? "" : sourceUris[0]);
	}

	private static OriginalPosition positionAtOffset(int offset, List<PdeTab> tabs, String unifiedSource, String fallbackUri) {
		PdeTab selected = null;
		for (PdeTab tab : tabs) {
			if (offset >= tab.startOffset) {
				selected = tab;
			}
		}
		if (selected == null) {
			return new OriginalPosition(fallbackUri, 0, 0);
		}
		int localOffset = Math.max(0, Math.min(offset - selected.startOffset, selected.length));
		LineCharacter position = lineCharacter(unifiedSource, selected.startOffset + localOffset);
		return new OriginalPosition(selected.uri, Math.max(0, position.line - selected.startLine), position.character);
	}

	private static int offset(String source, int line, int character) {
		int currentLine = 0;
		int currentCharacter = 0;
		for (int index = 0; index < source.length(); index++) {
			if (currentLine == line && currentCharacter == character) {
				return index;
			}
			char c = source.charAt(index);
			if (c == '\n') {
				currentLine++;
				currentCharacter = 0;
			} else {
				currentCharacter++;
			}
		}
		return source.length();
	}

	private static LineCharacter lineCharacter(String source, int offset) {
		int line = 0;
		int character = 0;
		int max = Math.max(0, Math.min(offset, source.length()));
		for (int i = 0; i < max; i++) {
			char c = source.charAt(i);
			if (c == '\n') {
				line++;
				character = 0;
			} else {
				character++;
			}
		}
		return new LineCharacter(line, character);
	}

	private static String syntheticUri(String entrypointUri) {
		if (entrypointUri == null || entrypointUri.isEmpty()) {
			return "memory://Sketch.java";
		}
		int slash = entrypointUri.lastIndexOf('/');
		if (slash < 0) {
			return "memory://Sketch.java";
		}
		return entrypointUri.substring(0, slash + 1) + SKETCH_CLASS + ".java";
	}

	private static String[] sourceUris(List<PdeSource> pdes) {
		List<String> uris = new ArrayList<>();
		for (PdeSource pde : pdes) {
			if (!uris.contains(pde.uri)) {
				uris.add(pde.uri);
			}
		}
		return uris.toArray(new String[0]);
	}

	private static int lineCount(String source) {
		int lines = 0;
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				lines++;
			}
		}
		return lines;
	}

	static final class MappedDiagnostic {
		final String uri;
		final EcjDiagnosticsEngine.DiagnosticData diagnostic;

		MappedDiagnostic(String uri, EcjDiagnosticsEngine.DiagnosticData diagnostic) {
			this.uri = uri;
			this.diagnostic = diagnostic;
		}
	}

	private static final class PdeSource {
		final String uri;
		final String source;

		PdeSource(String uri, String source) {
			this.uri = uri;
			this.source = source == null ? "" : source;
		}
	}

	private static final class PdeTab {
		final String uri;
		final int startLine;
		final int startOffset;
		final int length;

		PdeTab(String uri, int startLine, int startOffset, int length) {
			this.uri = uri;
			this.startLine = startLine;
			this.startOffset = startOffset;
			this.length = length;
		}
	}

	private static final class OriginalPosition {
		final String uri;
		final int line;
		final int character;

		OriginalPosition(String uri, int line, int character) {
			this.uri = uri;
			this.line = line;
			this.character = character;
		}
	}

	private static final class LineCharacter {
		final int line;
		final int character;

		LineCharacter(int line, int character) {
			this.line = line;
			this.character = character;
		}
	}
}
