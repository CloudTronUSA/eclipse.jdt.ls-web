package processing.utils;

public class SketchException extends Exception {
	private final int codeIndex;
	private final int codeLine;
	private final int codeColumn;

	public SketchException(String message) {
		this(message, -1, -1, -1);
	}

	public SketchException(String message, int codeIndex, int codeLine, int codeColumn) {
		super(message);
		this.codeIndex = codeIndex;
		this.codeLine = codeLine;
		this.codeColumn = codeColumn;
	}

	public int getCodeIndex() {
		return codeIndex;
	}

	public boolean hasCodeIndex() {
		return codeIndex >= 0;
	}

	public int getCodeLine() {
		return codeLine;
	}

	public boolean hasCodeLine() {
		return codeLine >= 0;
	}

	public int getCodeColumn() {
		return codeColumn;
	}
}
