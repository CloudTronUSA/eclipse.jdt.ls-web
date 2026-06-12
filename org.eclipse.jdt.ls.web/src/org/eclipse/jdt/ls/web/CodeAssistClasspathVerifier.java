package org.eclipse.jdt.ls.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.classfmt.FieldInfo;
import org.eclipse.jdt.internal.compiler.classfmt.MethodInfo;
import org.eclipse.jdt.internal.compiler.env.IBinaryField;
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;
import org.eclipse.jdt.ls.web.internal.resources.EcjResourceSupplier;

public final class CodeAssistClasspathVerifier {

	private static final int ACC_PUBLIC = 0x0001;
	private static final int ACC_PROTECTED = 0x0004;
	private static final String INDEX =
			"org/eclipse/jdt/ls/web/internal/resources/teavm-javac-classpath.resources";

	private CodeAssistClasspathVerifier() {
	}

	public static void main(String[] args) throws Exception {
		List<String> resources = resources();
		if (resources.isEmpty()) {
			throw new IllegalStateException("Missing code-assist classpath resource index: " + INDEX);
		}
		int checkedClasses = 0;
		int checkedMembers = 0;
		int skippedClasses = 0;
		List<String> failures = new ArrayList<>();
		EcjCompletionEngine engine = new EcjCompletionEngine();
		for (String resource : resources) {
			ClassFileReader reader = reader(resource);
			if (reader == null || reader.isAnonymous() || reader.isLocal() || !isExposed(reader.getModifiers())) {
				skippedClasses++;
				continue;
			}
			String typeName = javaTypeName(reader.getName());
			if (!isJavaTypeName(typeName)) {
				skippedClasses++;
				continue;
			}
			Set<String> expected = exposedMembers(reader);
			if (expected.isEmpty()) {
				skippedClasses++;
				continue;
			}
			String source = "class __CodeAssistCheck { void check(" + typeName + " value) { value. } }";
			int cursor = source.indexOf("value. }") + "value.".length();
			String completion = engine.complete("file:///__CodeAssistCheck.java", source, 0, cursor,
					Collections.emptyMap());
			checkedClasses++;
			for (String member : expected) {
				checkedMembers++;
				if (!completion.contains("\"label\":\"" + member + "\"")) {
					failures.add(typeName + "#" + member);
					if (failures.size() >= 50) {
						break;
					}
				}
			}
			if (failures.size() >= 50) {
				break;
			}
		}
		if (!failures.isEmpty()) {
			throw new IllegalStateException("Code assist missed exposed bundled members: " + failures);
		}
		List<String> signatureFailures = processingSignatureFailures(engine);
		if (!signatureFailures.isEmpty()) {
			throw new IllegalStateException("Code assist missed Processing signatures: " + signatureFailures);
		}
		List<String> supplierFailures = resourceSupplierFailures();
		if (!supplierFailures.isEmpty()) {
			throw new IllegalStateException("TeaVM resource supplier missed code-assist resources: " + supplierFailures);
		}
		System.out.println("Verified code assist for " + checkedClasses + " exposed classes and "
				+ checkedMembers + " exposed members from " + resources.size()
				+ " bundled TeaVM javac resources; skipped " + skippedClasses + " non-exposed/unsupported classes.");
	}

	private static List<String> resources() throws IOException {
		InputStream input = CodeAssistClasspathVerifier.class.getClassLoader().getResourceAsStream(INDEX);
		if (input == null) {
			return Collections.emptyList();
		}
		String content = new String(readAll(input), "UTF-8");
		List<String> resources = new ArrayList<>();
		int start = 0;
		while (start < content.length()) {
			int end = content.indexOf('\n', start);
			if (end < 0) {
				end = content.length();
			}
			String resource = content.substring(start, end).trim();
			if (!resource.isEmpty() && resource.endsWith(".class")) {
				resources.add(resource);
			}
			start = end + 1;
		}
		return resources;
	}

	private static ClassFileReader reader(String resource) throws IOException {
		InputStream input = CodeAssistClasspathVerifier.class.getClassLoader().getResourceAsStream(resource);
		if (input == null) {
			return null;
		}
		try {
			return ClassFileReader.read(readAll(input), resource, true);
		} catch (ClassFormatException ex) {
			return null;
		}
	}

	private static Set<String> exposedMembers(ClassFileReader reader) {
		Set<String> members = new LinkedHashSet<>();
		IBinaryField[] fields = reader.getFields();
		if (fields != null) {
			for (IBinaryField field : fields) {
				if (field instanceof FieldInfo fieldInfo && !fieldInfo.isSynthetic()
						&& isExposed(fieldInfo.getModifiers())) {
					members.add(new String(field.getName()));
				}
			}
		}
		IBinaryMethod[] methods = reader.getMethods();
		if (methods != null) {
			for (IBinaryMethod method : methods) {
				if (method instanceof MethodInfo methodInfo && !methodInfo.isSynthetic()
						&& isExposed(methodInfo.getModifiers()) && !method.isClinit()
						&& !methodInfo.isConstructor()) {
					members.add(new String(method.getSelector()));
				}
			}
		}
		return members;
	}

	private static List<String> processingSignatureFailures(EcjCompletionEngine engine) throws IOException {
		List<String> failures = new ArrayList<>();
		ClassFileReader pApplet = reader("processing/core/PApplet.class");
		if (pApplet == null) {
			failures.add("processing.core.PApplet");
			return failures;
		}
		Set<String> methodNames = exposedMethodNames(pApplet);
		for (String methodName : methodNames) {
			String signature = signatureHelp(engine, methodName + "(");
			if (!signature.contains("\"label\":")) {
				failures.add("PApplet#" + methodName);
				if (failures.size() >= 50) {
					return failures;
				}
			}
		}
		String twoArgumentColor = signatureHelp(engine, "color(1, 2");
		if (twoArgumentColor.contains("\"label\":\"int color(int gray)\"")
				|| twoArgumentColor.contains("\"label\":\"int color(float arg0)\"")) {
			failures.add("PApplet#color overload filtering");
		}
		if (!signatureHelp(engine, "text(").contains("\"label\":\"void text(")) {
			failures.add("PApplet#text signature");
		}
		if (!signatureHelp(engine, "createFont(").contains("\"label\":\"PFont createFont(")) {
			failures.add("PApplet#createFont signature");
		}
		return failures;
	}

	private static Set<String> exposedMethodNames(ClassFileReader reader) {
		Set<String> methods = new LinkedHashSet<>();
		IBinaryMethod[] binaryMethods = reader.getMethods();
		if (binaryMethods != null) {
			for (IBinaryMethod method : binaryMethods) {
				if (method instanceof MethodInfo methodInfo && !methodInfo.isSynthetic()
						&& isExposed(methodInfo.getModifiers()) && !method.isClinit()
						&& !methodInfo.isConstructor()) {
					methods.add(new String(method.getSelector()));
				}
			}
		}
		return methods;
	}

	private static String signatureHelp(EcjCompletionEngine engine, String call) {
		String source = "void setup() { " + call + " }";
		int cursor = source.indexOf(call) + call.length();
		return engine.signatureHelp("file:///Sketch.pde", source, 0, cursor, Collections.emptyMap());
	}

	private static List<String> resourceSupplierFailures() {
		Set<String> supplied = new LinkedHashSet<>();
		Collections.addAll(supplied, new EcjResourceSupplier().supplyResources(null));
		List<String> failures = new ArrayList<>();
		requireSupplied(supplied, "org/eclipse/jdt/ls/web/internal/resources/jdk-signature.resources", failures);
		requireSupplied(supplied, "org/eclipse/jdt/ls/web/internal/resources/teavm-javac-classpath.resources", failures);
		requireSupplied(supplied, "org/eclipse/jdt/ls/web/internal/resources/processing-core.resources", failures);
		requireSupplied(supplied, "processing/core/PApplet.class", failures);
		requireSupplied(supplied, "processing/core/PFont.class", failures);
		return failures;
	}

	private static void requireSupplied(Set<String> supplied, String resource, List<String> failures) {
		if (!supplied.contains(resource)) {
			failures.add(resource);
		}
	}

	private static boolean isExposed(int modifiers) {
		return (modifiers & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
	}

	private static String javaTypeName(char[] internalName) {
		return new String(internalName).replace('/', '.').replace('$', '.');
	}

	private static boolean isJavaTypeName(String value) {
		int start = 0;
		for (int i = 0; i <= value.length(); i++) {
			if (i == value.length() || value.charAt(i) == '.') {
				if (!isJavaIdentifier(value.substring(start, i))) {
					return false;
				}
				start = i + 1;
			}
		}
		return true;
	}

	private static boolean isJavaIdentifier(String value) {
		if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
			return false;
		}
		for (int i = 1; i < value.length(); i++) {
			if (!Character.isJavaIdentifierPart(value.charAt(i))) {
				return false;
			}
		}
		return true;
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
}
