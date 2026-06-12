package org.eclipse.jdt.ls.web.internal.teavm;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;

final class ProcessingPreprocessorTransformer implements ClassHolderTransformer {

	private static final String PDE_PARSE_TREE_LISTENER = "processing.mode.java.preproc.PdeParseTreeListener";
	private static final String PARSER_RULE_CONTEXT = "org.antlr.v4.runtime.ParserRuleContext";
	private static final String PRINT_WRITER_WITH_EDIT_GEN =
			"processing.mode.java.preproc.PdeParseTreeListener$PrintWriterWithEditGen";
	private static final String REWRITE_RESULT_BUILDER = "processing.mode.java.preproc.RewriteResultBuilder";

	@Override
	public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
		if (!PDE_PARSE_TREE_LISTENER.equals(cls.getName())) {
			return;
		}
		stub(cls, context, new MethodDescriptor("handleSizeCall", ValueType.object(PARSER_RULE_CONTEXT), ValueType.VOID));
		stub(cls, context, writerMethod("writeExtraFieldsAndMethods"));
		stub(cls, context, writerMethod("writeMain"));
	}

	private static MethodDescriptor writerMethod(String name) {
		return new MethodDescriptor(name, ValueType.object(PRINT_WRITER_WITH_EDIT_GEN),
				ValueType.object(REWRITE_RESULT_BUILDER), ValueType.VOID);
	}

	private static void stub(ClassHolder cls, ClassHolderTransformerContext context, MethodDescriptor descriptor) {
		MethodHolder method = cls.getMethod(descriptor);
		if (method == null) {
			return;
		}
		ProgramEmitter.create(method, context.getHierarchy()).exit();
	}
}
