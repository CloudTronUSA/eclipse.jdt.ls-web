import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;

public final class ExtractTeaVmJavacClasslib {

	private static final String INDEX_RESOURCE =
			"org/eclipse/jdt/ls/web/internal/resources/teavm-javac-classpath.resources";

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			throw new IllegalArgumentException("Usage: ExtractTeaVmJavacClasslib <outputDirectory> <teavmJavacDist>");
		}
		Path outputDirectory = Path.of(args[0]);
		if (args[1].isBlank() || args[1].contains("${")) {
			throw new IOException("Missing required Maven property: -Dteavm.javac.dist=/path/to/teavm-javac/dist/teavm-javac");
		}
		Path dist = Path.of(args[1]);
		if (!Files.isDirectory(dist)) {
			throw new IOException("TeaVM javac dist not found: " + dist);
		}
		requireFile(dist.resolve("compile-classlib-teavm.bin"));
		requireFile(dist.resolve("runtime-classlib-teavm.bin"));
		requireFile(dist.resolve("processing-core-teavm.jar"));

		Map<String, byte[]> classes = new TreeMap<>();
		readArchive(dist.resolve("compile-classlib-teavm.bin"), classes);
		readArchive(dist.resolve("runtime-classlib-teavm.bin"), classes);
		readJar(dist.resolve("processing-core-teavm.jar"), classes);
		if (classes.isEmpty()) {
			throw new IOException("No class resources found in TeaVM javac dist: " + dist);
		}

		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			Path output = outputDirectory.resolve(entry.getKey());
			Files.createDirectories(output.getParent());
			try (OutputStream outputStream = Files.newOutputStream(output)) {
				outputStream.write(entry.getValue());
			}
		}

		Path index = outputDirectory.resolve(INDEX_RESOURCE);
		Files.createDirectories(index.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(index, StandardCharsets.UTF_8)) {
			for (String resourceName : classes.keySet()) {
				writer.write(resourceName);
				writer.newLine();
			}
		}
		System.out.println("Extracted " + classes.size() + " TeaVM javac classpath resources from " + dist);
	}

	private static void requireFile(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			throw new IOException("Required TeaVM javac dist file not found: " + path);
		}
	}

	private static void readArchive(Path path, Map<String, byte[]> classes) throws IOException {
		if (!Files.isRegularFile(path)) {
			return;
		}
		try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
			while (true) {
				String name;
				try {
					int nameLength = input.readUnsignedShort();
					byte[] nameBytes = new byte[nameLength];
					input.readFully(nameBytes);
					name = new String(nameBytes, StandardCharsets.UTF_8);
				} catch (EOFException ex) {
					return;
				}
				byte[] data = new byte[input.readInt()];
				input.readFully(data);
				if (isClassResource(name)) {
					classes.put(name, data);
				}
			}
		}
	}

	private static void readJar(Path path, Map<String, byte[]> classes) throws IOException {
		if (!Files.isRegularFile(path)) {
			return;
		}
		try (JarFile jar = new JarFile(path.toFile())) {
			var entries = jar.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				String name = entry.getName();
				if (!isClassResource(name)) {
					continue;
				}
				try (InputStream input = jar.getInputStream(entry)) {
					classes.put(name, input.readAllBytes());
				}
			}
		}
	}

	private static boolean isClassResource(String name) {
		return name.endsWith(".class") && !"module-info.class".equals(name);
	}
}
