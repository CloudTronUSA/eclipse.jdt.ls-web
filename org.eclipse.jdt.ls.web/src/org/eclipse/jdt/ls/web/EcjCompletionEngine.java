package org.eclipse.jdt.ls.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.classfmt.FieldInfo;
import org.eclipse.jdt.internal.compiler.classfmt.MethodInfo;
import org.eclipse.jdt.internal.compiler.env.IBinaryField;
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;

final class EcjCompletionEngine {

	private static final int KIND_METHOD = 2;
	private static final int KIND_FIELD = 5;
	private static final int KIND_VARIABLE = 6;
	private static final int KIND_CLASS = 7;
	private static final int KIND_INTERFACE = 8;
	private static final int KIND_MODULE = 9;
	private static final int KIND_ENUM = 13;
	private static final int KIND_KEYWORD = 14;

	private static final String[] KEYWORDS = {
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue",
			"default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float",
			"for", "if", "implements", "import", "instanceof", "int", "interface", "long", "new", "null",
			"package", "private", "protected", "public", "record", "return", "short", "static", "super",
			"switch", "this", "throw", "throws", "true", "try", "var", "void", "while"
	};

	private static final String[] CLASS_RESOURCE_INDEXES = {
			"org/eclipse/jdt/ls/web/internal/resources/jdk-signature.resources",
			"org/eclipse/jdt/ls/web/internal/resources/teavm-javac-classpath.resources",
			"org/eclipse/jdt/ls/web/internal/resources/processing-core.resources"
	};
	private static final Map<String, String> PLATFORM_SOURCES = platformSources();

	String complete(String uri, String source, int line, int character, Map<String, String> workspaceSources) {
		int offset = offset(source, line, character);
		List<CompletionItem> items = completeItems(uri, source, offset, workspaceSources);
		StringBuilder json = new StringBuilder();
		json.append("{\"isIncomplete\":false,\"items\":[");
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			appendItem(json, items.get(i));
		}
		json.append("]}");
		return json.toString();
	}

	String hover(String uri, String source, int line, int character, Map<String, String> workspaceSources) {
		SymbolInfo symbol = symbolAt(uri, source, offset(source, line, character), workspaceSources);
		if (symbol == null) {
			return "null";
		}
		StringBuilder json = new StringBuilder();
		json.append("{\"contents\":{\"kind\":\"markdown\",\"value\":");
		JsonSupport.appendString(json, "```java\n" + symbol.detail + "\n```");
		json.append("}}");
		return json.toString();
	}

	String signatureHelp(String uri, String source, int line, int character, Map<String, String> workspaceSources) {
		CallSite call = CallSite.from(source, offset(source, line, character));
		if (call == null) {
			return "{\"signatures\":[],\"activeSignature\":0,\"activeParameter\":0}";
		}
		ProjectIndex index = ProjectIndex.from(uri, source, workspaceSources);
		List<MemberInfo> methods = methodsForCall(call, uri, source, index);
		methods = applicableMethods(methods, call);
		if (methods.isEmpty()) {
			return "{\"signatures\":[],\"activeSignature\":0,\"activeParameter\":0}";
		}
		StringBuilder json = new StringBuilder();
		json.append("{\"signatures\":[");
		for (int i = 0; i < methods.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			MemberInfo method = methods.get(i);
			json.append("{\"label\":");
			JsonSupport.appendString(json, method.detail);
			json.append(",\"parameters\":[");
			for (int j = 0; j < method.parameters.size(); j++) {
				if (j > 0) {
					json.append(',');
				}
				json.append("{\"label\":");
				JsonSupport.appendString(json, method.parameters.get(j));
				json.append('}');
			}
			json.append("]}");
		}
		json.append("],\"activeSignature\":0,\"activeParameter\":").append(Math.max(0, call.activeParameter)).append('}');
		return json.toString();
	}

	private List<CompletionItem> completeItems(String uri, String source, int offset,
			Map<String, String> workspaceSources) {
		if (isInCommentOrString(source, offset)) {
			return Collections.emptyList();
		}
		CompletionContext context = CompletionContext.from(source, offset);
		ProjectIndex index = ProjectIndex.from(uri, source, workspaceSources);
		List<CompletionItem> items = new ArrayList<>();
		if (context.memberAccess) {
			addMemberItems(items, context, index, source, offset);
		} else {
			addKeywordItems(items, context.prefix);
			addLocalItems(items, context.prefix, source, offset, index);
			addTypeItems(items, context.prefix, index);
			if (isProcessingContext(uri, source)) {
				addProcessingItems(items, context.prefix, index);
			}
		}
		Collections.sort(items);
		return dedupe(items);
	}

	private static void addMemberItems(List<CompletionItem> items, CompletionContext context, ProjectIndex index,
			String source, int offset) {
		String typeName = resolveReceiverType(context.receiver, source, offset, index);
		if (typeName == null || typeName.isEmpty()) {
			return;
		}
		ClassInfo type = index.findType(typeName);
		if (type == null) {
			return;
		}
		for (MemberInfo field : type.fields) {
			if (matches(field.name, context.prefix)) {
				items.add(new CompletionItem(field.name, KIND_FIELD, field.detail, field.name));
			}
		}
		for (MemberInfo field : index.inheritedFields(type)) {
			if (matches(field.name, context.prefix)) {
				items.add(new CompletionItem(field.name, KIND_FIELD, field.detail, field.name));
			}
		}
		for (MemberInfo method : index.allMethods(type)) {
			if (matches(method.name, context.prefix)) {
				items.add(new CompletionItem(method.name, KIND_METHOD, method.detail, method.insertText()));
			}
		}
	}

	private static void addKeywordItems(List<CompletionItem> items, String prefix) {
		for (String keyword : KEYWORDS) {
			if (matches(keyword, prefix)) {
				items.add(new CompletionItem(keyword, KIND_KEYWORD, "keyword", keyword));
			}
		}
	}

	private static void addLocalItems(List<CompletionItem> items, String prefix, String source, int offset,
			ProjectIndex index) {
		for (MemberInfo local : localVariables(source, offset, index).values()) {
			if (matches(local.name, prefix)) {
				items.add(new CompletionItem(local.name, KIND_VARIABLE, local.detail, local.name));
			}
		}
	}

	private static void addTypeItems(List<CompletionItem> items, String prefix, ProjectIndex index) {
		for (ClassInfo type : index.types.values()) {
			if (matches(type.simpleName, prefix)) {
				items.add(new CompletionItem(type.simpleName, type.kind, type.qualifiedName, type.simpleName));
			}
		}
		for (String packageName : index.packages.keySet()) {
			String simpleName = simpleName(packageName);
			if (matches(simpleName, prefix)) {
				items.add(new CompletionItem(simpleName, KIND_MODULE, packageName, simpleName));
			}
		}
	}

	private static void addProcessingItems(List<CompletionItem> items, String prefix, ProjectIndex index) {
		ClassInfo pApplet = index.findType("PApplet");
		if (pApplet == null) {
			return;
		}
		for (MemberInfo field : pApplet.fields) {
			if (matches(field.name, prefix)) {
				items.add(new CompletionItem(field.name, KIND_FIELD, field.detail, field.name));
			}
		}
		for (MemberInfo method : pApplet.methods) {
			if (matches(method.name, prefix)) {
				items.add(new CompletionItem(method.name, KIND_METHOD, method.detail, method.insertText()));
			}
		}
	}

	private static boolean isProcessingContext(String uri, String source) {
		String lowerUri = uri == null ? "" : uri.toLowerCase();
		return lowerUri.endsWith(".pde") || source.indexOf("extends PApplet") >= 0
				|| source.indexOf("extends processing.core.PApplet") >= 0;
	}

	private static String resolveReceiverType(String receiver, String source, int offset, ProjectIndex index) {
		if (receiver == null || receiver.isEmpty()) {
			return "";
		}
		if ("this".equals(receiver) || "super".equals(receiver)) {
			return currentTypeName(source, offset);
		}
		if (receiver.endsWith("\"")) {
			return "String";
		}
		if (isNumericLiteral(receiver)) {
			return receiver.indexOf('.') >= 0 ? "Double" : "Integer";
		}
		int receiverDot = receiver.lastIndexOf('.');
		if (receiverDot > 0 && receiverDot + 1 < receiver.length()) {
			String ownerName = resolveReceiverType(receiver.substring(0, receiverDot), source, offset, index);
			ClassInfo owner = index.findType(ownerName);
			if (owner != null) {
				MemberInfo field = owner.field(receiver.substring(receiverDot + 1));
				if (field != null) {
					return field.type;
				}
			}
		}
		ClassInfo type = index.findType(receiver);
		if (type != null) {
			return type.simpleName;
		}
		Map<String, MemberInfo> locals = localVariables(source, offset, index);
		MemberInfo local = locals.get(receiver);
		if (local != null) {
			return local.type;
		}
		ClassInfo current = index.findType(currentTypeName(source, offset));
		if (current != null) {
			MemberInfo field = current.field(receiver);
			if (field != null) {
				return field.type;
			}
		}
		return "";
	}

	private static SymbolInfo symbolAt(String uri, String source, int offset, Map<String, String> workspaceSources) {
		if (isInCommentOrString(source, offset)) {
			return null;
		}
		Word word = Word.at(source, offset);
		if (word == null) {
			return null;
		}
		ProjectIndex index = ProjectIndex.from(uri, source, workspaceSources);
		String receiver = receiverBefore(source, word.start);
		if (!receiver.isEmpty()) {
			ClassInfo owner = index.findType(resolveReceiverType(receiver, source, word.start, index));
			if (owner == null) {
				return null;
			}
			MemberInfo member = index.member(owner, word.text);
			return member == null ? null : new SymbolInfo(member.detail);
		}
		MemberInfo local = localVariables(source, word.start, index).get(word.text);
		if (local != null) {
			return new SymbolInfo(local.detail);
		}
		ClassInfo type = index.findType(word.text);
		if (type != null) {
			return new SymbolInfo(type.qualifiedName);
		}
		ClassInfo current = index.findType(currentTypeName(source, word.start));
		if (current != null) {
			MemberInfo member = index.member(current, word.text);
			if (member != null) {
				return new SymbolInfo(member.detail);
			}
		}
		if (isProcessingContext(uri, source)) {
			ClassInfo pApplet = index.findType("PApplet");
			MemberInfo member = pApplet == null ? null : index.member(pApplet, word.text);
			if (member != null) {
				return new SymbolInfo(member.detail);
			}
		}
		return null;
	}

	private static List<MemberInfo> methodsForCall(CallSite call, String uri, String source, ProjectIndex index) {
		if (!call.receiver.isEmpty()) {
			ClassInfo owner = index.findType(resolveReceiverType(call.receiver, source, call.openParen, index));
			return owner == null ? Collections.emptyList() : index.methods(owner, call.name);
		}
		ClassInfo type = index.findType(call.name);
		if (type != null) {
			return type.constructors.isEmpty() ? Collections.singletonList(new MemberInfo(type.simpleName,
					type.simpleName, type.simpleName + "()", true)) : type.constructors;
		}
		ClassInfo current = index.findType(currentTypeName(source, call.openParen));
		if (current != null) {
			List<MemberInfo> methods = index.methods(current, call.name);
			if (!methods.isEmpty()) {
				return methods;
			}
		}
		if (isProcessingContext(uri, source)) {
			ClassInfo pApplet = index.findType("PApplet");
			if (pApplet != null) {
				return index.methods(pApplet, call.name);
			}
		}
		return Collections.emptyList();
	}

	private static List<MemberInfo> applicableMethods(List<MemberInfo> methods, CallSite call) {
		if (methods.isEmpty()) {
			return methods;
		}
		List<MemberInfo> countMatches = new ArrayList<>();
		for (MemberInfo method : methods) {
			if (method.parameters.size() >= call.minimumParameterCount) {
				countMatches.add(method);
			}
		}
		if (countMatches.isEmpty()) {
			return Collections.emptyList();
		}
		List<MemberInfo> typeMatches = new ArrayList<>();
		for (MemberInfo method : countMatches) {
			if (argumentTypesMatch(method, call.argumentTypes)) {
				typeMatches.add(method);
			}
		}
		return typeMatches.isEmpty() ? countMatches : typeMatches;
	}

	private static boolean argumentTypesMatch(MemberInfo method, List<String> argumentTypes) {
		if (argumentTypes.isEmpty()) {
			return true;
		}
		if (method.parameters.size() < argumentTypes.size()) {
			return false;
		}
		for (int i = 0; i < argumentTypes.size(); i++) {
			String argumentType = argumentTypes.get(i);
			if (!argumentType.isEmpty() && !isAssignable(argumentType, parameterType(method.parameters.get(i)))) {
				return false;
			}
		}
		return true;
	}

	private static String parameterType(String label) {
		int space = label.lastIndexOf(' ');
		String type = space > 0 ? label.substring(0, space) : label;
		return type.replace("...", "[]");
	}

	private static boolean isAssignable(String argumentType, String parameterType) {
		String argument = simpleName(argumentType);
		String parameter = simpleName(parameterType);
		if (argument.equals(parameter)) {
			return true;
		}
		if ("null".equals(argument)) {
			return !isPrimitive(parameter);
		}
		if ("Object".equals(parameter)) {
			return true;
		}
		if ("String".equals(argument) && "CharSequence".equals(parameter)) {
			return true;
		}
		if ("char".equals(argument)) {
			return "int".equals(parameter) || "long".equals(parameter) || "float".equals(parameter)
					|| "double".equals(parameter);
		}
		if ("int".equals(argument)) {
			return "long".equals(parameter) || "float".equals(parameter) || "double".equals(parameter);
		}
		if ("long".equals(argument)) {
			return "float".equals(parameter) || "double".equals(parameter);
		}
		if ("float".equals(argument)) {
			return "double".equals(parameter);
		}
		return false;
	}

	private static String receiverBefore(String source, int wordStart) {
		int dot = wordStart - 1;
		while (dot >= 0 && Character.isWhitespace(source.charAt(dot))) {
			dot--;
		}
		if (dot < 0 || source.charAt(dot) != '.') {
			return "";
		}
		int receiverEnd = dot;
		while (receiverEnd > 0 && Character.isWhitespace(source.charAt(receiverEnd - 1))) {
			receiverEnd--;
		}
		int receiverStart = receiverEnd;
		while (receiverStart > 0) {
			char c = source.charAt(receiverStart - 1);
			if (!Character.isJavaIdentifierPart(c) && c != '.' && c != '"' && c != '\'') {
				break;
			}
			receiverStart--;
		}
		return source.substring(receiverStart, receiverEnd);
	}

	private static Map<String, MemberInfo> localVariables(String source, int offset, ProjectIndex index) {
		String text = source.substring(0, Math.max(0, Math.min(offset, source.length())));
		Map<String, MemberInfo> locals = new LinkedHashMap<>();
		String[] tokens = tokens(text);
		for (int i = 0; i + 1 < tokens.length; i++) {
			if (i > 0 && ".".equals(tokens[i - 1])) {
				continue;
			}
			TypeCandidate type = typeCandidate(tokens, i, index);
			if (type == null) {
				continue;
			}
			int nameIndex = skipTypeSuffix(tokens, type.nextIndex);
			if (nameIndex >= tokens.length) {
				continue;
			}
			String name = tokens[nameIndex];
			if (!isIdentifier(name) || isKeyword(name)) {
				continue;
			}
			locals.putIfAbsent(name, new MemberInfo(name, type.simpleName, type.displayName + " " + name, false));
		}
		for (int i = 0; i + 3 < tokens.length; i++) {
			if (!"var".equals(tokens[i]) || !"=".equals(tokens[i + 2])) {
				continue;
			}
			String inferred = inferTypeFromInitializer(tokens[i + 3]);
			if (!inferred.isEmpty() && isIdentifier(tokens[i + 1])) {
				locals.put(tokens[i + 1], new MemberInfo(tokens[i + 1], inferred, "var " + tokens[i + 1], false));
			}
		}
		return locals;
	}

	private static String inferTypeFromInitializer(String token) {
		if (token.startsWith("\"")) {
			return "String";
		}
		if (isNumericLiteral(token)) {
			return token.indexOf('.') >= 0 ? "Double" : "Integer";
		}
		return "";
	}

	private static TypeCandidate typeCandidate(String[] tokens, int index, ProjectIndex projectIndex) {
		if (index >= tokens.length) {
			return null;
		}
		String first = tokens[index];
		if ("var".equals(first) || isPrimitive(first)) {
			return new TypeCandidate(normalizeType(first), first, index + 1);
		}
		if (!isIdentifier(first) || isKeyword(first)) {
			return null;
		}
		StringBuilder qualified = new StringBuilder(first);
		String simple = first;
		int next = index + 1;
		while (next + 1 < tokens.length && ".".equals(tokens[next]) && isIdentifier(tokens[next + 1])) {
			simple = tokens[next + 1];
			qualified.append('.').append(simple);
			next += 2;
		}
		String qualifiedName = qualified.toString();
		ClassInfo resolved = projectIndex.findType(qualifiedName);
		if (resolved == null) {
			resolved = projectIndex.findType(simple);
		}
		if (resolved != null) {
			return new TypeCandidate(resolved.qualifiedName, qualifiedName, next);
		}
		if (Character.isUpperCase(simple.charAt(0))) {
			return new TypeCandidate(normalizeType(simple), qualifiedName, next);
		}
		return null;
	}

	private static String currentTypeName(String source, int offset) {
		String text = source.substring(0, Math.max(0, Math.min(offset, source.length())));
		String[] tokens = tokens(text);
		String last = "";
		for (int i = 0; i + 1 < tokens.length; i++) {
			if (isTypeDeclarationKeyword(tokens[i]) && isIdentifier(tokens[i + 1])) {
				last = tokens[i + 1];
			}
		}
		return last;
	}

	private static List<CompletionItem> dedupe(List<CompletionItem> items) {
		List<CompletionItem> deduped = new ArrayList<>(items.size());
		Map<String, Boolean> seen = new HashMap<>();
		for (CompletionItem item : items) {
			String key = item.label + "|" + item.kind + "|" + item.detail;
			if (seen.containsKey(key)) {
				continue;
			}
			seen.put(key, Boolean.TRUE);
			deduped.add(item);
		}
		return deduped;
	}

	private static boolean matches(String value, String prefix) {
		return prefix == null || prefix.isEmpty() || value.startsWith(prefix);
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

	private static boolean isInCommentOrString(String source, int offset) {
		boolean lineComment = false;
		boolean blockComment = false;
		char quote = 0;
		int max = Math.max(0, Math.min(offset, source.length()));
		for (int i = 0; i < max; i++) {
			char c = source.charAt(i);
			char next = i + 1 < max ? source.charAt(i + 1) : 0;
			if (lineComment) {
				if (c == '\n' || c == '\r') {
					lineComment = false;
				}
				continue;
			}
			if (blockComment) {
				if (c == '*' && next == '/') {
					blockComment = false;
					i++;
				}
				continue;
			}
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '/' && next == '/') {
				lineComment = true;
				i++;
			} else if (c == '/' && next == '*') {
				blockComment = true;
				i++;
			} else if (c == '"' || c == '\'') {
				quote = c;
			}
		}
		return lineComment || blockComment || quote != 0;
	}

	private static String[] tokens(String source) {
		List<String> tokens = new ArrayList<>();
		for (int index = 0; index < source.length();) {
			char c = source.charAt(index);
			if (Character.isWhitespace(c)) {
				index++;
				continue;
			}
			if (c == '/' && index + 1 < source.length()) {
				char next = source.charAt(index + 1);
				if (next == '/') {
					index += 2;
					while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') {
						index++;
					}
					continue;
				}
				if (next == '*') {
					int end = source.indexOf("*/", index + 2);
					index = end >= 0 ? end + 2 : source.length();
					continue;
				}
			}
			if (c == '"' || c == '\'') {
				int end = skipQuoted(source, index, c);
				tokens.add(source.substring(index, end));
				index = end;
				continue;
			}
			if (Character.isJavaIdentifierStart(c)) {
				int start = index++;
				while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) {
					index++;
				}
				tokens.add(source.substring(start, index));
				continue;
			}
			if (Character.isDigit(c)) {
				int start = index++;
				while (index < source.length()
						&& (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
					index++;
				}
				tokens.add(source.substring(start, index));
				continue;
			}
			tokens.add(String.valueOf(c));
			index++;
		}
		return tokens.toArray(new String[0]);
	}

	private static int skipQuoted(String source, int index, char quote) {
		index++;
		while (index < source.length()) {
			char c = source.charAt(index++);
			if (c == '\\' && index < source.length()) {
				index++;
				continue;
			}
			if (c == quote) {
				return index;
			}
		}
		return index;
	}

	private static boolean isCandidateType(String token) {
		if ("var".equals(token) || isPrimitive(token)) {
			return true;
		}
		return isIdentifier(token) && Character.isUpperCase(token.charAt(0)) && !isKeyword(token);
	}

	private static int skipTypeSuffix(String[] tokens, int index) {
		int genericDepth = 0;
		while (index < tokens.length) {
			String token = tokens[index];
			if ("<".equals(token)) {
				genericDepth++;
				index++;
				continue;
			}
			if (">".equals(token) && genericDepth > 0) {
				genericDepth--;
				index++;
				continue;
			}
			if (genericDepth > 0 || ".".equals(token) || "?".equals(token) || "extends".equals(token)
					|| "super".equals(token)) {
				index++;
				continue;
			}
			if ("[".equals(token) && index + 1 < tokens.length && "]".equals(tokens[index + 1])) {
				index += 2;
				continue;
			}
			return index;
		}
		return index;
	}

	private static boolean isPrimitive(String token) {
		return "boolean".equals(token) || "byte".equals(token) || "char".equals(token) || "double".equals(token)
				|| "float".equals(token) || "int".equals(token) || "long".equals(token) || "short".equals(token);
	}

	private static boolean isKeyword(String token) {
		return Arrays.asList(KEYWORDS).contains(token);
	}

	private static boolean isTypeDeclarationKeyword(String token) {
		return "class".equals(token) || "interface".equals(token) || "enum".equals(token) || "record".equals(token);
	}

	private static boolean isIdentifier(String token) {
		if (token == null || token.isEmpty() || !Character.isJavaIdentifierStart(token.charAt(0))) {
			return false;
		}
		for (int i = 1; i < token.length(); i++) {
			if (!Character.isJavaIdentifierPart(token.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isJavaTypeName(String value) {
		int start = 0;
		for (int i = 0; i <= value.length(); i++) {
			if (i == value.length() || value.charAt(i) == '.') {
				if (!isIdentifier(value.substring(start, i))) {
					return false;
				}
				start = i + 1;
			}
		}
		return true;
	}

	private static boolean isNumericLiteral(String token) {
		return token != null && !token.isEmpty() && Character.isDigit(token.charAt(0));
	}

	private static String normalizeType(String type) {
		if ("boolean".equals(type)) {
			return "Boolean";
		}
		if ("byte".equals(type)) {
			return "Byte";
		}
		if ("char".equals(type)) {
			return "Character";
		}
		if ("double".equals(type)) {
			return "Double";
		}
		if ("float".equals(type)) {
			return "Float";
		}
		if ("int".equals(type)) {
			return "Integer";
		}
		if ("long".equals(type)) {
			return "Long";
		}
		if ("short".equals(type)) {
			return "Short";
		}
		return simpleName(type);
	}

	private static String simpleName(String qualifiedName) {
		int dot = qualifiedName.lastIndexOf('.');
		return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
	}

	private static String charString(char[] chars) {
		return chars == null ? "" : new String(chars);
	}

	private static String internalName(char[] chars) {
		return charString(chars).replace('/', '.');
	}

	private static String descriptorType(char[] descriptor, int[] index) {
		if (descriptor == null || index[0] >= descriptor.length) {
			return "Object";
		}
		char kind = descriptor[index[0]++];
		switch (kind) {
			case 'B':
				return "byte";
			case 'C':
				return "char";
			case 'D':
				return "double";
			case 'F':
				return "float";
			case 'I':
				return "int";
			case 'J':
				return "long";
			case 'S':
				return "short";
			case 'Z':
				return "boolean";
			case 'V':
				return "void";
			case '[':
				return descriptorType(descriptor, index) + "[]";
			case 'L':
				int start = index[0];
				while (index[0] < descriptor.length && descriptor[index[0]] != ';') {
					index[0]++;
				}
				String name = new String(descriptor, start, Math.max(0, index[0] - start)).replace('/', '.');
				if (index[0] < descriptor.length) {
					index[0]++;
				}
				return simpleName(name);
			case 'T':
				int typeStart = index[0];
				while (index[0] < descriptor.length && descriptor[index[0]] != ';') {
					index[0]++;
				}
				String typeName = new String(descriptor, typeStart, Math.max(0, index[0] - typeStart));
				if (index[0] < descriptor.length) {
					index[0]++;
				}
				return typeName.isEmpty() ? "Object" : typeName;
			default:
				return "Object";
		}
	}

	private static MethodDescriptorInfo parseMethodDescriptor(char[] descriptor) {
		List<String> parameters = new ArrayList<>();
		int[] index = { 0 };
		if (descriptor != null && descriptor.length > 0 && descriptor[index[0]] == '(') {
			index[0]++;
			while (index[0] < descriptor.length && descriptor[index[0]] != ')') {
				parameters.add(descriptorType(descriptor, index));
			}
			if (index[0] < descriptor.length && descriptor[index[0]] == ')') {
				index[0]++;
			}
		}
		String returnType = descriptorType(descriptor, index);
		return new MethodDescriptorInfo(parameters, returnType);
	}

	private static String join(List<String> values) {
		StringBuilder joined = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				joined.append(", ");
			}
			joined.append(values.get(i));
		}
		return joined.toString();
	}

	private static void appendItem(StringBuilder json, CompletionItem item) {
		json.append("{\"label\":");
		JsonSupport.appendString(json, item.label);
		json.append(",\"kind\":").append(item.kind);
		json.append(",\"detail\":");
		JsonSupport.appendString(json, item.detail);
		json.append(",\"insertText\":");
		JsonSupport.appendString(json, item.insertText);
		json.append('}');
	}

	private static Map<String, String> platformSources() {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("java.lang.Object",
				"package java.lang; public class Object { public String toString() { return null; } public boolean equals(Object other) { return false; } public int hashCode() { return 0; } }");
		sources.put("java.lang.String",
				"package java.lang; public final class String implements CharSequence { public int length() { return 0; } public char charAt(int index) { return 0; } public boolean isEmpty() { return false; } public String trim() { return this; } public String substring(int beginIndex) { return this; } public String substring(int beginIndex, int endIndex) { return this; } public boolean contains(CharSequence value) { return false; } public boolean startsWith(String prefix) { return false; } public boolean endsWith(String suffix) { return false; } public int indexOf(String value) { return 0; } public String replace(CharSequence target, CharSequence replacement) { return this; } public String toLowerCase() { return this; } public String toUpperCase() { return this; } public static String valueOf(Object value) { return null; } }");
		sources.put("java.lang.StringBuilder",
				"package java.lang; public final class StringBuilder { public StringBuilder append(Object value) { return this; } public StringBuilder append(String value) { return this; } public StringBuilder append(int value) { return this; } public int length() { return 0; } public char charAt(int index) { return 0; } public String substring(int start) { return null; } public String toString() { return null; } }");
		sources.put("java.lang.Integer",
				"package java.lang; public final class Integer extends Number { public int intValue() { return 0; } public long longValue() { return 0; } public double doubleValue() { return 0; } public static int parseInt(String value) { return 0; } public static Integer valueOf(int value) { return null; } }");
		sources.put("java.lang.Double",
				"package java.lang; public final class Double extends Number { public int intValue() { return 0; } public long longValue() { return 0; } public double doubleValue() { return 0; } public static double parseDouble(String value) { return 0; } public static Double valueOf(double value) { return null; } }");
		sources.put("java.lang.Boolean",
				"package java.lang; public final class Boolean { public boolean booleanValue() { return false; } public static Boolean valueOf(boolean value) { return null; } }");
		sources.put("java.lang.Character",
				"package java.lang; public final class Character { public char charValue() { return 0; } public static boolean isWhitespace(char value) { return false; } public static boolean isJavaIdentifierStart(char value) { return false; } public static boolean isJavaIdentifierPart(char value) { return false; } }");
		sources.put("java.lang.Math",
				"package java.lang; public final class Math { public static final double PI = 0; public static int abs(int value) { return 0; } public static double abs(double value) { return 0; } public static int max(int a, int b) { return 0; } public static double max(double a, double b) { return 0; } public static int min(int a, int b) { return 0; } public static double min(double a, double b) { return 0; } public static double sqrt(double value) { return 0; } public static double pow(double a, double b) { return 0; } public static long round(double value) { return 0; } }");
		sources.put("java.lang.System",
				"package java.lang; public final class System { public static final java.io.PrintStream out = null; public static final java.io.PrintStream err = null; public static long currentTimeMillis() { return 0; } }");
		sources.put("java.lang.AutoCloseable",
				"package java.lang; public interface AutoCloseable { void close() throws Exception; }");
		sources.put("java.io.PrintStream",
				"package java.io; public class PrintStream { public void print(Object value) {} public void print(String value) {} public void println() {} public void println(Object value) {} public void println(String value) {} public void println(int value) {} public void println(boolean value) {} }");
		sources.put("java.io.File",
				"package java.io; public class File { public File(String pathname) {} public String getName() { return null; } public String getPath() { return null; } public String getAbsolutePath() { return null; } public boolean exists() { return false; } public boolean isFile() { return false; } public boolean isDirectory() { return false; } public long length() { return 0; } }");
		sources.put("java.util.Iterator",
				"package java.util; public interface Iterator<E> { boolean hasNext(); E next(); void remove(); }");
		sources.put("java.util.Collection",
				"package java.util; public interface Collection<E> { int size(); boolean isEmpty(); boolean contains(Object value); boolean add(E value); boolean remove(Object value); void clear(); Iterator<E> iterator(); Object[] toArray(); }");
		sources.put("java.util.List",
				"package java.util; public interface List<E> extends Collection<E> { E get(int index); E set(int index, E value); void add(int index, E value); boolean add(E value); E remove(int index); int indexOf(Object value); }");
		sources.put("java.util.ArrayList",
				"package java.util; public class ArrayList<E> implements List<E> { public int size() { return 0; } public boolean isEmpty() { return false; } public boolean add(E value) { return false; } public E get(int index) { return null; } public E set(int index, E value) { return null; } public E remove(int index) { return null; } public void clear() {} }");
		sources.put("java.util.Map",
				"package java.util; public interface Map<K,V> { V get(Object key); V put(K key, V value); V remove(Object key); boolean containsKey(Object key); boolean containsValue(Object value); int size(); boolean isEmpty(); void clear(); Set<K> keySet(); Collection<V> values(); }");
		sources.put("java.util.HashMap",
				"package java.util; public class HashMap<K,V> implements Map<K,V> { public V get(Object key) { return null; } public V put(K key, V value) { return null; } public V remove(Object key) { return null; } public boolean containsKey(Object key) { return false; } public int size() { return 0; } public boolean isEmpty() { return false; } public void clear() {} }");
		sources.put("java.util.Set",
				"package java.util; public interface Set<E> extends Collection<E> { boolean add(E value); boolean contains(Object value); int size(); boolean isEmpty(); }");
		sources.put("java.util.Optional",
				"package java.util; public final class Optional<T> { public static <T> Optional<T> empty() { return null; } public static <T> Optional<T> of(T value) { return null; } public boolean isPresent() { return false; } public boolean isEmpty() { return false; } public T get() { return null; } public T orElse(T other) { return null; } }");
		sources.put("java.util.Scanner",
				"package java.util; public final class Scanner implements java.lang.AutoCloseable { public Scanner(String source) {} public Scanner(java.io.File source) {} public boolean hasNext() { return false; } public boolean hasNextLine() { return false; } public String next() { return null; } public String nextLine() { return null; } public int nextInt() { return 0; } public long nextLong() { return 0; } public float nextFloat() { return 0; } public double nextDouble() { return 0; } public Scanner useDelimiter(String pattern) { return this; } public Scanner useLocale(Locale locale) { return this; } public void close() {} }");
		return sources;
	}

	private static final class CompletionContext {
		final String prefix;
		final boolean memberAccess;
		final String receiver;

		private CompletionContext(String prefix, boolean memberAccess, String receiver) {
			this.prefix = prefix;
			this.memberAccess = memberAccess;
			this.receiver = receiver;
		}

		static CompletionContext from(String source, int offset) {
			int end = Math.max(0, Math.min(offset, source.length()));
			int prefixStart = end;
			while (prefixStart > 0 && Character.isJavaIdentifierPart(source.charAt(prefixStart - 1))) {
				prefixStart--;
			}
			String prefix = source.substring(prefixStart, end);
			int dot = prefixStart - 1;
			while (dot >= 0 && Character.isWhitespace(source.charAt(dot))) {
				dot--;
			}
			if (dot < 0 || source.charAt(dot) != '.') {
				return new CompletionContext(prefix, false, "");
			}
			int receiverEnd = dot;
			while (receiverEnd > 0 && Character.isWhitespace(source.charAt(receiverEnd - 1))) {
				receiverEnd--;
			}
			int receiverStart = receiverEnd;
			while (receiverStart > 0) {
				char c = source.charAt(receiverStart - 1);
				if (!Character.isJavaIdentifierPart(c) && c != '.' && c != '"' && c != '\'') {
					break;
				}
				receiverStart--;
			}
			String receiver = source.substring(receiverStart, receiverEnd);
			return new CompletionContext(prefix, true, receiver);
		}
	}

	private static final class ProjectIndex {
		final Map<String, ClassInfo> types = new LinkedHashMap<>();
		final Map<String, Boolean> packages = new LinkedHashMap<>();

		static ProjectIndex from(String uri, String source, Map<String, String> workspaceSources) {
			ProjectIndex index = new ProjectIndex();
			for (ClassInfo type : BinaryTypesHolder.TYPES.values()) {
				index.add(type);
			}
			for (Map.Entry<String, String> entry : PLATFORM_SOURCES.entrySet()) {
				index.addIfAbsent(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : workspaceSources.entrySet()) {
				index.add(MemoryCompilationUnit.from(entry.getKey(), entry.getValue()), entry.getValue());
			}
			index.add(MemoryCompilationUnit.from(uri, source), source);
			return index;
		}

		ClassInfo findType(String name) {
			if (name == null || name.isEmpty()) {
				return null;
			}
			ClassInfo direct = types.get(name);
			if (direct != null) {
				return direct;
			}
			if (name.indexOf('.') >= 0) {
				return null;
			}
			return types.get(simpleName(name));
		}

		MemberInfo member(ClassInfo type, String name) {
			MemberInfo field = type.field(name);
			if (field != null) {
				return field;
			}
			for (MemberInfo inheritedField : inheritedFields(type)) {
				if (inheritedField.name.equals(name)) {
					return inheritedField;
				}
			}
			List<MemberInfo> matches = methods(type, name);
			return matches.isEmpty() ? null : matches.get(0);
		}

		List<MemberInfo> methods(ClassInfo type, String name) {
			List<MemberInfo> matches = new ArrayList<>();
			for (MemberInfo method : allMethods(type)) {
				if (method.name.equals(name)) {
					matches.add(method);
				}
			}
			return matches;
		}

		List<MemberInfo> allMethods(ClassInfo type) {
			List<MemberInfo> methods = new ArrayList<>(type.methods);
			addInheritedMethods(methods, type, new HashMap<>());
			return methods;
		}

		List<MemberInfo> inheritedFields(ClassInfo type) {
			List<MemberInfo> fields = new ArrayList<>();
			addInheritedFields(fields, type, new HashMap<>());
			return fields;
		}

		private void addInheritedMethods(List<MemberInfo> methods, ClassInfo type, Map<String, Boolean> seen) {
			for (String superType : type.superTypes) {
				ClassInfo resolved = findType(superType);
				if (resolved == null || seen.containsKey(resolved.qualifiedName)) {
					continue;
				}
				seen.put(resolved.qualifiedName, Boolean.TRUE);
				methods.addAll(resolved.methods);
				addInheritedMethods(methods, resolved, seen);
			}
		}

		private void addInheritedFields(List<MemberInfo> fields, ClassInfo type, Map<String, Boolean> seen) {
			for (String superType : type.superTypes) {
				ClassInfo resolved = findType(superType);
				if (resolved == null || seen.containsKey(resolved.qualifiedName)) {
					continue;
				}
				seen.put(resolved.qualifiedName, Boolean.TRUE);
				fields.addAll(resolved.fields);
				addInheritedFields(fields, resolved, seen);
			}
		}

		private void add(String qualifiedName, String source) {
			int dot = qualifiedName.lastIndexOf('.');
			String packageName = dot >= 0 ? qualifiedName.substring(0, dot) : "";
			addPackage(packageName);
			String simpleName = simpleName(qualifiedName);
			ClassInfo parsed = ClassInfo.parse(packageName, simpleName, source);
			add(parsed);
		}

		private void addIfAbsent(String qualifiedName, String source) {
			if (!types.containsKey(qualifiedName)) {
				add(qualifiedName, source);
			}
		}

		private void add(ClassInfo parsed) {
			addPackage(parsed.packageName);
			types.put(parsed.simpleName, parsed);
			types.put(parsed.qualifiedName, parsed);
		}

		private void add(MemoryCompilationUnit unit, String source) {
			String packageName = qualifiedName(unit.getPackageName());
			addPackage(packageName);
			String[] typeNames = unit.topLevelTypeNames();
			if (typeNames.length == 0) {
				typeNames = new String[] { new String(unit.getMainTypeName()) };
			}
			for (String typeName : typeNames) {
				ClassInfo parsed = ClassInfo.parse(packageName, typeName, source);
				add(parsed);
			}
		}

		private void addPackage(String packageName) {
			if (packageName == null || packageName.isEmpty()) {
				return;
			}
			packages.put(packageName, Boolean.TRUE);
			int dot = packageName.lastIndexOf('.');
			while (dot > 0) {
				packageName = packageName.substring(0, dot);
				packages.put(packageName, Boolean.TRUE);
				dot = packageName.lastIndexOf('.');
			}
		}

		private static String qualifiedName(char[][] parts) {
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					result.append('.');
				}
				result.append(parts[i]);
			}
			return result.toString();
		}
	}

	private static final class BinaryTypesHolder {
		static final Map<String, ClassInfo> TYPES = binaryTypes();
	}

	private static Map<String, ClassInfo> binaryTypes() {
		Map<String, ClassInfo> types = new LinkedHashMap<>();
		for (String indexResource : CLASS_RESOURCE_INDEXES) {
			for (String resource : lineSeparatedResource(indexResource)) {
				if (!resource.endsWith(".class") || "module-info.class".equals(resource)) {
					continue;
				}
				try {
					byte[] bytes = classpathBytes(resource);
					if (bytes.length == 0) {
						continue;
					}
					ClassFileReader reader = ClassFileReader.read(bytes, resource, true);
					if (reader.isAnonymous() || reader.isLocal()) {
						continue;
					}
					ClassInfo type = ClassInfo.fromBinary(reader);
					if (!isJavaTypeName(type.qualifiedName)) {
						continue;
					}
					types.put(type.qualifiedName, type);
				} catch (ClassFormatException | IOException ignored) {
				}
			}
		}
		return types;
	}

	private static List<String> lineSeparatedResource(String resourceName) {
		InputStream input = EcjCompletionEngine.class.getClassLoader().getResourceAsStream(resourceName);
		if (input == null) {
			return Collections.emptyList();
		}
		try {
			String content = new String(readAll(input), "UTF-8");
			List<String> lines = new ArrayList<>();
			int start = 0;
			while (start < content.length()) {
				int end = content.indexOf('\n', start);
				if (end < 0) {
					end = content.length();
				}
				String line = content.substring(start, end).trim();
				if (!line.isEmpty()) {
					lines.add(line);
				}
				start = end + 1;
			}
			return lines;
		} catch (IOException ex) {
			return Collections.emptyList();
		}
	}

	private static byte[] classpathBytes(String resourceName) throws IOException {
		InputStream input = EcjCompletionEngine.class.getClassLoader().getResourceAsStream(resourceName);
		return input == null ? new byte[0] : readAll(input);
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

	private static final class ClassInfo {
		private static final int ACC_PRIVATE = 0x0002;
		private static final int ACC_INTERFACE = 0x0200;
		private static final int ACC_ENUM = 0x4000;

		final String packageName;
		final String qualifiedName;
		final String simpleName;
		final int kind;
		final List<MemberInfo> fields = new ArrayList<>();
		final List<MemberInfo> methods = new ArrayList<>();
		final List<MemberInfo> constructors = new ArrayList<>();
		final List<String> superTypes = new ArrayList<>();

		private ClassInfo(String packageName, String simpleName, int kind) {
			this.packageName = packageName == null ? "" : packageName;
			this.simpleName = simpleName;
			this.qualifiedName = this.packageName.isEmpty() ? simpleName : this.packageName + "." + simpleName;
			this.kind = kind;
		}

		static ClassInfo fromBinary(ClassFileReader reader) {
			String rawName = internalName(reader.getName());
			String qualifiedName = rawName.replace('$', '.');
			int dot = rawName.lastIndexOf('.');
			String packageName = dot >= 0 ? rawName.substring(0, dot) : "";
			String simpleName = packageName.isEmpty() ? qualifiedName : qualifiedName.substring(packageName.length() + 1);
			ClassInfo info = new ClassInfo(packageName, simpleName, binaryKind(reader.getModifiers()));
			char[] superclass = reader.getSuperclassName();
			if (superclass != null && superclass.length > 0) {
				info.superTypes.add(simpleName(internalName(superclass)));
			}
			char[][] interfaces = reader.getInterfaceNames();
			if (interfaces != null) {
				for (char[] iface : interfaces) {
					info.superTypes.add(simpleName(internalName(iface)));
				}
			}
			IBinaryField[] fields = reader.getFields();
			if (fields != null) {
				for (IBinaryField field : fields) {
					MemberInfo parsed = binaryField(field);
					if (parsed != null) {
						info.fields.add(parsed);
					}
				}
			}
			IBinaryMethod[] methods = reader.getMethods();
			if (methods != null) {
				for (IBinaryMethod method : methods) {
					MemberInfo parsed = binaryMethod(method, simpleName);
					if (parsed == null) {
						continue;
					}
					if (parsed.name.equals(simpleName)) {
						info.constructors.add(parsed);
					} else {
						info.methods.add(parsed);
					}
				}
			}
			return info;
		}

		static ClassInfo parse(String packageName, String simpleName, String source) {
			ClassInfo info = new ClassInfo(packageName, simpleName, typeKind(source, simpleName));
			parseSuperTypes(source, simpleName, info);
			String body = classBody(source, simpleName);
			parseMembers(body, info);
			return info;
		}

		private static int binaryKind(int modifiers) {
			if ((modifiers & ACC_INTERFACE) != 0) {
				return KIND_INTERFACE;
			}
			if ((modifiers & ACC_ENUM) != 0) {
				return KIND_ENUM;
			}
			return KIND_CLASS;
		}

		private static MemberInfo binaryField(IBinaryField field) {
			if (!(field instanceof FieldInfo fieldInfo) || fieldInfo.isSynthetic()
					|| (fieldInfo.getModifiers() & ACC_PRIVATE) != 0) {
				return null;
			}
			String name = charString(field.getName());
			String type = descriptorType(field.getTypeName(), new int[] { 0 });
			return new MemberInfo(name, type, type + " " + name, false);
		}

		private static MemberInfo binaryMethod(IBinaryMethod method, String ownerSimpleName) {
			if (!(method instanceof MethodInfo methodInfo) || methodInfo.isSynthetic() || method.isClinit()
					|| (methodInfo.getModifiers() & ACC_PRIVATE) != 0) {
				return null;
			}
			String selector = charString(method.getSelector());
			boolean constructor = methodInfo.isConstructor() || "<init>".equals(selector);
			if ("<clinit>".equals(selector)) {
				return null;
			}
			MethodDescriptorInfo descriptor = parseMethodDescriptor(method.getMethodDescriptor());
			String name = constructor ? ownerSimpleName : selector;
			List<String> labels = parameterLabels(descriptor.parameterTypes, methodInfo.getArgumentNames());
			String params = join(labels);
			String detail = constructor ? name + "(" + params + ")"
					: descriptor.returnType + " " + name + "(" + params + ")";
			return new MemberInfo(name, constructor ? ownerSimpleName : descriptor.returnType, detail, true, labels);
		}

		private static List<String> parameterLabels(List<String> parameterTypes, char[][] argumentNames) {
			if (parameterTypes.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> labels = new ArrayList<>(parameterTypes.size());
			for (int i = 0; i < parameterTypes.size(); i++) {
				String name = argumentNames != null && i < argumentNames.length && argumentNames[i] != null
						? charString(argumentNames[i])
						: "arg" + i;
				labels.add(parameterTypes.get(i) + " " + name);
			}
			return labels;
		}

		MemberInfo field(String name) {
			for (MemberInfo field : fields) {
				if (field.name.equals(name)) {
					return field;
				}
			}
			return null;
		}

		MemberInfo member(String name) {
			MemberInfo field = field(name);
			if (field != null) {
				return field;
			}
			List<MemberInfo> matches = methods(name);
			return matches.isEmpty() ? null : matches.get(0);
		}

		List<MemberInfo> methods(String name) {
			List<MemberInfo> matches = new ArrayList<>();
			for (MemberInfo method : methods) {
				if (method.name.equals(name)) {
					matches.add(method);
				}
			}
			return matches;
		}

		private static void parseSuperTypes(String source, String simpleName, ClassInfo info) {
			String[] tokens = tokens(source);
			for (int i = 0; i + 1 < tokens.length; i++) {
				if (!simpleName.equals(tokens[i + 1]) || !isTypeDeclarationKeyword(tokens[i])) {
					continue;
				}
				for (int j = i + 2; j < tokens.length && !"{".equals(tokens[j]); j++) {
					if (("extends".equals(tokens[j]) || "implements".equals(tokens[j])) && j + 1 < tokens.length) {
						j = addSuperTypes(tokens, j + 1, info);
					}
				}
				return;
			}
		}

		private static int addSuperTypes(String[] tokens, int index, ClassInfo info) {
			StringBuilder name = new StringBuilder();
			for (int i = index; i < tokens.length && !"{".equals(tokens[i]); i++) {
				String token = tokens[i];
				if ("implements".equals(token) || "extends".equals(token)) {
					if (name.length() > 0) {
						info.superTypes.add(simpleName(name.toString()));
						name.setLength(0);
					}
					return i - 1;
				}
				if (",".equals(token)) {
					if (name.length() > 0) {
						info.superTypes.add(simpleName(name.toString()));
						name.setLength(0);
					}
					continue;
				}
				if ("<".equals(token)) {
					while (i < tokens.length && !">".equals(tokens[i])) {
						i++;
					}
					continue;
				}
				if (".".equals(token)) {
					name.append('.');
					continue;
				}
				if (isIdentifier(token)) {
					name.append(token);
				}
			}
			if (name.length() > 0) {
				info.superTypes.add(simpleName(name.toString()));
			}
			return tokens.length;
		}

		private static int typeKind(String source, String simpleName) {
			String[] tokens = tokens(source);
			for (int i = 0; i + 1 < tokens.length; i++) {
				if (simpleName.equals(tokens[i + 1])) {
					if ("interface".equals(tokens[i])) {
						return KIND_INTERFACE;
					}
					if ("enum".equals(tokens[i])) {
						return KIND_ENUM;
					}
				}
			}
			return KIND_CLASS;
		}

		private static String classBody(String source, String simpleName) {
			int nameIndex = source.indexOf(simpleName);
			while (nameIndex >= 0) {
				int brace = source.indexOf('{', nameIndex + simpleName.length());
				if (brace < 0) {
					return "";
				}
				int end = matchingBrace(source, brace);
				if (end > brace) {
					return source.substring(brace + 1, end);
				}
				nameIndex = source.indexOf(simpleName, nameIndex + simpleName.length());
			}
			return "";
		}

		private static void parseMembers(String body, ClassInfo info) {
			int depth = 0;
			int segmentStart = 0;
			for (int index = 0; index < body.length(); index++) {
				char c = body.charAt(index);
				if (c == '"' || c == '\'') {
					index = skipQuoted(body, index, c) - 1;
					continue;
				}
				if (c == '{') {
					if (depth == 0) {
						parseCallable(body.substring(segmentStart, index), info);
						segmentStart = index + 1;
					}
					depth++;
					continue;
				}
				if (c == '}') {
					if (depth > 0) {
						depth--;
					}
					if (depth == 0) {
						segmentStart = index + 1;
					}
					continue;
				}
				if (depth == 0 && c == ';') {
					String segment = body.substring(segmentStart, index);
					if (segment.indexOf('(') >= 0) {
						parseCallable(segment, info);
					} else {
						parseField(segment, info);
					}
					segmentStart = index + 1;
				}
			}
		}

		private static void parseCallable(String segment, ClassInfo info) {
			String[] tokens = tokens(segment);
			int paren = -1;
			for (int i = 0; i < tokens.length; i++) {
				if ("(".equals(tokens[i])) {
					paren = i;
					break;
				}
			}
			if (paren < 1 || !isIdentifier(tokens[paren - 1])) {
				return;
			}
			String name = tokens[paren - 1];
			if (info.simpleName.equals(name)) {
				String params = parameters(tokens, paren);
				info.constructors.add(new MemberInfo(name, info.simpleName, name + "(" + params + ")", true,
						parameterLabels(params)));
				return;
			}
			String returnType = paren >= 2 ? normalizeType(tokens[paren - 2]) : "void";
			String params = parameters(tokens, paren);
			info.methods.add(new MemberInfo(name, returnType, returnType + " " + name + "(" + params + ")", true,
					parameterLabels(params)));
		}

		private static String parameters(String[] tokens, int paren) {
			StringBuilder params = new StringBuilder();
			boolean first = true;
			for (int i = paren + 1; i + 1 < tokens.length && !")".equals(tokens[i]); i++) {
				if (isCandidateType(tokens[i]) && isIdentifier(tokens[i + 1])) {
					if (!first) {
						params.append(", ");
					}
					params.append(tokens[i]).append(' ').append(tokens[i + 1]);
					first = false;
					i++;
				}
			}
			return params.toString();
		}

		private static List<String> parameterLabels(String params) {
			if (params.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> labels = new ArrayList<>();
			int start = 0;
			for (int i = 0; i <= params.length(); i++) {
				if (i == params.length() || params.charAt(i) == ',') {
					labels.add(params.substring(start, i).trim());
					start = i + 1;
				}
			}
			return labels;
		}

		private static void parseField(String segment, ClassInfo info) {
			String[] tokens = tokens(segment);
			int typeIndex = -1;
			for (int i = 0; i < tokens.length; i++) {
				if (isCandidateType(tokens[i])) {
					typeIndex = i;
				}
			}
			if (typeIndex < 0) {
				return;
			}
			String type = normalizeType(tokens[typeIndex]);
			for (int i = typeIndex + 1; i < tokens.length; i++) {
				if (isIdentifier(tokens[i]) && !isKeyword(tokens[i])) {
					info.fields.add(new MemberInfo(tokens[i], type, type + " " + tokens[i], false));
					while (i < tokens.length && !",".equals(tokens[i])) {
						i++;
					}
				}
			}
		}

		private static int matchingBrace(String source, int open) {
			int depth = 0;
			for (int index = open; index < source.length(); index++) {
				char c = source.charAt(index);
				if (c == '"' || c == '\'') {
					index = skipQuoted(source, index, c) - 1;
					continue;
				}
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
					if (depth == 0) {
						return index;
					}
				}
			}
			return -1;
		}
	}

	private static final class MemberInfo {
		final String name;
		final String type;
		final String detail;
		final boolean method;
		final List<String> parameters;

		MemberInfo(String name, String type, String detail, boolean method) {
			this(name, type, detail, method, Collections.emptyList());
		}

		MemberInfo(String name, String type, String detail, boolean method, List<String> parameters) {
			this.name = name;
			this.type = type;
			this.detail = detail;
			this.method = method;
			this.parameters = parameters;
		}

		String insertText() {
			return name;
		}
	}

	private static final class TypeCandidate {
		final String simpleName;
		final String displayName;
		final int nextIndex;

		TypeCandidate(String simpleName, String displayName, int nextIndex) {
			this.simpleName = simpleName;
			this.displayName = displayName;
			this.nextIndex = nextIndex;
		}
	}

	private static final class MethodDescriptorInfo {
		final List<String> parameterTypes;
		final String returnType;

		MethodDescriptorInfo(List<String> parameterTypes, String returnType) {
			this.parameterTypes = parameterTypes;
			this.returnType = returnType;
		}
	}

	private static final class SymbolInfo {
		final String detail;

		SymbolInfo(String detail) {
			this.detail = detail;
		}
	}

	private static final class Word {
		final String text;
		final int start;

		Word(String text, int start) {
			this.text = text;
			this.start = start;
		}

		static Word at(String source, int offset) {
			int index = Math.max(0, Math.min(offset, source.length()));
			if (index == source.length() || !Character.isJavaIdentifierPart(source.charAt(index))) {
				index--;
			}
			if (index < 0 || !Character.isJavaIdentifierPart(source.charAt(index))) {
				return null;
			}
			int start = index;
			while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
				start--;
			}
			int end = index + 1;
			while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
				end++;
			}
			return new Word(source.substring(start, end), start);
		}
	}

	private static final class CallSite {
		final String receiver;
		final String name;
		final int openParen;
		final int activeParameter;
		final int minimumParameterCount;
		final List<String> argumentTypes;

		CallSite(String receiver, String name, int openParen, int activeParameter, int minimumParameterCount,
				List<String> argumentTypes) {
			this.receiver = receiver;
			this.name = name;
			this.openParen = openParen;
			this.activeParameter = activeParameter;
			this.minimumParameterCount = minimumParameterCount;
			this.argumentTypes = argumentTypes;
		}

		static CallSite from(String source, int offset) {
			int end = Math.max(0, Math.min(offset, source.length()));
			int depth = 0;
			for (int i = end - 1; i >= 0; i--) {
				char c = source.charAt(i);
				if (c == '"' || c == '\'') {
					continue;
				}
				if (c == ')') {
					depth++;
					continue;
				}
				if (c == '(') {
					if (depth > 0) {
						depth--;
						continue;
					}
					int nameEnd = i;
					while (nameEnd > 0 && Character.isWhitespace(source.charAt(nameEnd - 1))) {
						nameEnd--;
					}
					int nameStart = nameEnd;
					while (nameStart > 0 && Character.isJavaIdentifierPart(source.charAt(nameStart - 1))) {
						nameStart--;
					}
					if (nameStart == nameEnd) {
						return null;
					}
					String name = source.substring(nameStart, nameEnd);
					List<String> arguments = arguments(source, i + 1, end);
					return new CallSite(receiverBefore(source, nameStart), name, i,
							activeParameter(source, i + 1, end), minimumParameterCount(arguments),
							argumentTypes(arguments));
				}
			}
			return null;
		}

		private static int activeParameter(String source, int start, int end) {
			int parameter = 0;
			int depth = 0;
			for (int i = start; i < end; i++) {
				char c = source.charAt(i);
				if (c == '"' || c == '\'') {
					i = skipQuoted(source, i, c) - 1;
					continue;
				}
				if (c == '(' || c == '[' || c == '{') {
					depth++;
				} else if (c == ')' || c == ']' || c == '}') {
					if (depth > 0) {
						depth--;
					}
				} else if (c == ',' && depth == 0) {
					parameter++;
				}
			}
			return parameter;
		}

		private static int minimumParameterCount(List<String> arguments) {
			if (arguments.isEmpty()) {
				return 0;
			}
			if (arguments.size() == 1 && arguments.get(0).trim().isEmpty()) {
				return 0;
			}
			return arguments.size();
		}

		private static List<String> argumentTypes(List<String> arguments) {
			List<String> types = new ArrayList<>();
			for (String argument : arguments) {
				types.add(expressionType(argument));
			}
			return types;
		}

		private static List<String> arguments(String source, int start, int end) {
			List<String> arguments = new ArrayList<>();
			int depth = 0;
			int segmentStart = start;
			for (int i = start; i < end; i++) {
				char c = source.charAt(i);
				if (c == '"' || c == '\'') {
					i = skipQuoted(source, i, c) - 1;
					continue;
				}
				if (c == '(' || c == '[' || c == '{') {
					depth++;
				} else if (c == ')' || c == ']' || c == '}') {
					if (depth > 0) {
						depth--;
					}
				} else if (c == ',' && depth == 0) {
					arguments.add(source.substring(segmentStart, i).trim());
					segmentStart = i + 1;
				}
			}
			if (segmentStart < end || !arguments.isEmpty()) {
				arguments.add(source.substring(segmentStart, end).trim());
			}
			return arguments;
		}

		private static String expressionType(String expression) {
			String value = expression.trim();
			if (value.isEmpty()) {
				return "";
			}
			if (value.startsWith("\"")) {
				return "String";
			}
			if (value.startsWith("'")) {
				return "char";
			}
			if ("true".equals(value) || "false".equals(value)) {
				return "boolean";
			}
			if ("null".equals(value)) {
				return "null";
			}
			if (isNumericLiteral(value)) {
				char last = value.charAt(value.length() - 1);
				if (last == 'f' || last == 'F') {
					return "float";
				}
				if (last == 'l' || last == 'L') {
					return "long";
				}
				return value.indexOf('.') >= 0 ? "float" : "int";
			}
			String[] tokens = tokens(value);
			if (tokens.length >= 2 && "new".equals(tokens[0]) && isIdentifier(tokens[1])) {
				return tokens[1];
			}
			return "";
		}
	}

	private static final class CompletionItem implements Comparable<CompletionItem> {
		final String label;
		final int kind;
		final String detail;
		final String insertText;

		CompletionItem(String label, int kind, String detail, String insertText) {
			this.label = label;
			this.kind = kind;
			this.detail = detail;
			this.insertText = insertText;
		}

		@Override
		public int compareTo(CompletionItem other) {
			int labelCompare = label.compareTo(other.label);
			if (labelCompare != 0) {
				return labelCompare;
			}
			int kindCompare = Integer.compare(kindPriority(kind), kindPriority(other.kind));
			if (kindCompare != 0) {
				return kindCompare;
			}
			return detail.compareTo(other.detail);
		}

		private static int kindPriority(int kind) {
			if (kind == KIND_METHOD) {
				return 0;
			}
			if (kind == KIND_FIELD || kind == KIND_VARIABLE) {
				return 1;
			}
			if (kind == KIND_CLASS || kind == KIND_INTERFACE || kind == KIND_ENUM) {
				return 2;
			}
			if (kind == KIND_MODULE) {
				return 3;
			}
			return 4;
		}
	}
}
