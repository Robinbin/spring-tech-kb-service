package com.springtechkb.generation.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.springtechkb.generation.internal.GenerationDtos.*;
import static org.assertj.core.api.Assertions.*;

class ProjectArchiveBuilderTest {
    private final ProjectArchiveBuilder builder = new ProjectArchiveBuilder(
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void emitsBoundedBuildableFixedTemplateAndNonExecutableReference() throws Exception {
        Output example = new Output(UUID.randomUUID(), Kind.EXAMPLE, "Example", "Explanation",
                "Runtime.getRuntime().exec(\"untrusted\");", "java", true, "AI_GENERATED", List.of(),
                "model", "code-example:v2", null, null, Instant.now(), null);
        Output project = project(new ProjectRequest("com.example", "safe-demo", "Safe Demo", example.id()));
        byte[] zip = builder.build(project, example);
        Map<String, String> files = read(zip);
        assertThat(files).containsKeys("pom.xml", "src/main/java/com/example/Application.java",
                "src/test/java/com/example/ApplicationTest.java", "README.md", "LICENSE.txt",
                "PROVENANCE.json", "reference/generated-example.txt");
        assertThat(files.keySet()).noneMatch(path -> path.contains("..") || path.startsWith("/") || path.contains("\\"));
        assertThat(files.get("pom.xml")).contains("<version>4.1.2</version>", "<java.version>21</java.version>");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                files.get("pom.xml").getBytes(StandardCharsets.UTF_8)));
        assertThat(document.getDocumentElement().getNodeName()).isEqualTo("project");
        assertThat(files.get("reference/generated-example.txt")).contains("Runtime.getRuntime()");
        assertThat(files.get("src/main/java/com/example/Application.java")).doesNotContain("Runtime.getRuntime()");
        assertThat(files.get("PROVENANCE.json")).contains("AI_GENERATED", project.id().toString());
        assertThat(zip.length).isLessThan(256_000);
        assertThat(document.getElementsByTagName("relativePath").getLength()).isEqualTo(1);
        assertThat(files.get("pom.xml")).doesNotContain("SNAPSHOT</version></parent>", "<repositories>", "<systemPath>");
        compileJavaSources(files);
    }

    @Test
    void rejectsTraversalXmlInjectionAndJavaKeywordCoordinates() {
        for (ProjectRequest request : List.of(
                new ProjectRequest("../evil", "safe", "name", null),
                new ProjectRequest("com.example", "../../evil", "name", null),
                new ProjectRequest("com.class", "safe", "name", null),
                new ProjectRequest("com.example", "safe", "<name>", null))) {
            assertThatThrownBy(() -> builder.build(project(request), null)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void enforcesArchiveByteLimitRatherThanTrustingSmallCompressedSize() {
        Output oversizedReference = new Output(UUID.randomUUID(), Kind.EXAMPLE, "Example", "x".repeat(256_001),
                "", "java", true, "AI_GENERATED", List.of(), "model", "v1", null, null, Instant.now(), null);
        assertThatThrownBy(() -> builder.build(
                project(new ProjectRequest("com.example", "safe-demo", "Demo", oversizedReference.id())),
                oversizedReference)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
    }

    private static void compileJavaSources(Map<String, String> files) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Tests must run on Java 21+ JDK, not a JRE").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             var memory = new ForwardingJavaFileManager<>(standard) {
                 @Override
                 public JavaFileObject getJavaFileForOutput(Location location, String name,
                         JavaFileObject.Kind kind, FileObject sibling) {
                     return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                         @Override public OutputStream openOutputStream() { return new ByteArrayOutputStream(); }
                     };
                 }
             }) {
            List<JavaFileObject> sources = files.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(".java"))
                    .<JavaFileObject>map(entry -> new SimpleJavaFileObject(
                            URI.create("string:///" + entry.getKey()), JavaFileObject.Kind.SOURCE) {
                        @Override public CharSequence getCharContent(boolean ignoreErrors) { return entry.getValue(); }
                    }).toList();
            boolean compiled = compiler.getTask(null, memory, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", System.getProperty("java.class.path")),
                    null, sources).call();
            assertThat(compiled).as("Archive source/test Java 21 compilation: %s", diagnostics.getDiagnostics()).isTrue();
        }
    }

    private static Output project(ProjectRequest request) {
        return new Output(UUID.randomUUID(), Kind.PROJECT, request.name(), "body", "", "java", true,
                "AI_GENERATED", List.of(), "local-template", "project-template:v1", null, "4.1.2",
                Instant.parse("2026-01-01T00:00:00Z"), request);
    }

    static Map<String, String> read(byte[] zip) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                files.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
