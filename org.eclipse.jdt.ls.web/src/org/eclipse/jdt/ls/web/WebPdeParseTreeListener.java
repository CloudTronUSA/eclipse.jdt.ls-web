package org.eclipse.jdt.ls.web;

import java.util.Optional;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;

import processing.mode.java.preproc.PdeParseTreeListener;
import processing.mode.java.preproc.ProcessingParser;
import processing.mode.java.preproc.RewriteResultBuilder;

final class WebPdeParseTreeListener extends PdeParseTreeListener {

	private final String sketchName;
	private final String indent1;
	private final String indent2;
	private final String indent3;

	WebPdeParseTreeListener(TokenStream tokens, String sketchName, int tabSize, Optional<String> packageName) {
		super(tokens, sketchName, tabSize, packageName);
		this.sketchName = sketchName;
		this.indent1 = spaces(tabSize);
		this.indent2 = indent1 + indent1;
		this.indent3 = indent2 + indent1;
	}

	@Override
	public void exitMethodCall(ProcessingParser.MethodCallContext ctx) {
		String methodName = ctx.getChild(0).getText();
		boolean impliedThis = ctx.getParent().getChildCount() == 1;
		boolean usesThis = impliedThis;
		if (!impliedThis) {
			String target = ctx.getParent().getChild(0).getText();
			usesThis = "this".equals(target) || "super".equals(target);
		}
		if (!usesThis) {
			return;
		}
		if ("size".equals(methodName) || "fullScreen".equals(methodName)) {
			handleSizeCall(ctx);
		} else if ("pixelDensity".equals(methodName)) {
			handlePixelDensityCall(ctx);
		} else if ("noSmooth".equals(methodName)) {
			handleNoSmoothCall(ctx);
		} else if ("smooth".equals(methodName)) {
			handleSmoothCall(ctx);
		}
	}

	@Override
	protected void handleSizeCall(ParserRuleContext ctx) {
	}

	@Override
	protected void writeExtraFieldsAndMethods(PrintWriterWithEditGen classBodyWriter,
			RewriteResultBuilder resultBuilder) {
	}

	@Override
	protected void writeMain(PrintWriterWithEditGen footerWriter, RewriteResultBuilder resultBuilder) {
		footerWriter.addEmptyLine();
		footerWriter.addCodeLine(indent1 + "static public void main(String[] passedArgs) {");
		footerWriter.addCodeLine(indent2 + "String[] appletArgs = new String[] { \"" + sketchName + "\" };");
		footerWriter.addCodeLine(indent2 + "if (passedArgs != null) {");
		footerWriter.addCodeLine(indent3 + "PApplet.main(concat(appletArgs, passedArgs));");
		footerWriter.addCodeLine(indent2 + "} else {");
		footerWriter.addCodeLine(indent3 + "PApplet.main(appletArgs);");
		footerWriter.addCodeLine(indent2 + "}");
		footerWriter.addCodeLine(indent1 + "}");
	}

	private static String spaces(int count) {
		StringBuilder spaces = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			spaces.append(' ');
		}
		return spaces.toString();
	}
}
