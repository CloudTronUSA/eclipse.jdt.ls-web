package org.eclipse.jdt.ls.web.internal.resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

public final class EcjResourceSupplier implements ResourceSupplier {

	private static final String INDEX_PREFIX = "org/eclipse/jdt/ls/web/internal/resources/";

	@Override
	public String[] supplyResources(ResourceSupplierContext context) {
		String[] unicodeDirectories = {
				"unicode",
				"unicode6",
				"unicode6_2",
				"unicode7",
				"unicode8",
				"unicode10",
				"unicode11",
				"unicode12_1"
		};
		List<String> resources = new ArrayList<>();
		String prefix = "org/eclipse/jdt/internal/compiler/parser/";
		for (int i = 1; i <= 24; i++) {
			resources.add(prefix + "parser" + i + ".rsc");
		}
		resources.add(prefix + "readableNames.props");
		resources.add("org/eclipse/jdt/internal/compiler/problem/messages.properties");
		resources.add("org/eclipse/jdt/internal/compiler/messages.properties");
		resources.add("org/eclipse/jdt/internal/compiler/batch/messages.properties");
		for (String directory : unicodeDirectories) {
			resources.add(prefix + directory + "/start0.rsc");
			resources.add(prefix + directory + "/start1.rsc");
			resources.add(prefix + directory + "/start2.rsc");
			resources.add(prefix + directory + "/part0.rsc");
			resources.add(prefix + directory + "/part1.rsc");
			resources.add(prefix + directory + "/part2.rsc");
			resources.add(prefix + directory + "/part14.rsc");
		}
		resources.add(prefix + "unicode13/start0.rsc");
		resources.add(prefix + "unicode13/start1.rsc");
		resources.add(prefix + "unicode13/start2.rsc");
		resources.add(prefix + "unicode13/start3.rsc");
		resources.add(prefix + "unicode13/part0.rsc");
		resources.add(prefix + "unicode13/part1.rsc");
		resources.add(prefix + "unicode13/part2.rsc");
		resources.add(prefix + "unicode13/part3.rsc");
		resources.add(prefix + "unicode13/part14.rsc");
		addLineSeparatedResources(resources, "jdk-signature.resources");
		addLineSeparatedResources(resources, "teavm-javac-classpath.resources");
		addProcessingCoreResources(resources);
		return resources.toArray(new String[0]);
	}

	private static void addProcessingCoreResources(List<String> resources) {
		addLineSeparatedResources(resources, "processing-core.resources");
	}

	private static void addLineSeparatedResources(List<String> resources, String resourceName) {
		resources.add(INDEX_PREFIX + resourceName);
		InputStream input = EcjResourceSupplier.class.getResourceAsStream(resourceName);
		if (input == null) {
			return;
		}
		try {
			String content = new String(readAll(input), "UTF-8");
			int start = 0;
			while (start < content.length()) {
				int end = content.indexOf('\n', start);
				if (end < 0) {
					end = content.length();
				}
				String resource = content.substring(start, end).trim();
				if (!resource.isEmpty()) {
					resources.add(resource);
				}
				start = end + 1;
			}
		} catch (IOException ignored) {
		}
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
