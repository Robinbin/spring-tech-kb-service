package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.SourceLicense;

class ApiCompatibilityPipelineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsTagChangesAndReportsBinaryIncompatibility() throws Exception {
        Path repositoryPath = temporaryDirectory.resolve("repository");
        UUID sourceId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(sourceId);
        SourceAdmissionPolicy policy = new SourceAdmissionPolicy();
        ApiCompatibilityPipeline pipeline = new ApiCompatibilityPipeline(jdbc, policy);

        try (Git git = Git.init().setDirectory(repositoryPath.toFile()).call()) {
            writeApi(repositoryPath, "public String name() { return \"old\"; }");
            commitAndTag(git, "v1");
            Path oldJar = compileJar("old", "public String name() { return \"old\"; }");

            writeApi(repositoryPath, "public Integer name() { return 2; }");
            commitAndTag(git, "v2");
            Path newJar = compileJar("new", "public Integer name() { return 2; }");

            var result = pipeline.scan(
                    repositoryPath, "v1", "v2", oldJar, newJar,
                    new SourceAdmission(
                            URI.create("https://github.com/spring-projects/spring-framework"),
                            KnowledgeSource.SPRING_GITHUB,
                            SourceLicense.APACHE_2,
                            "Spring Framework source",
                            "Spring Team",
                            "core",
                            "v2",
                            "https://www.apache.org/licenses/LICENSE-2.0",
                            "test-reviewer",
                            "review-record"));

            assertThat(result.sourceChanges())
                    .anySatisfy(change -> {
                        assertThat(change.changeType()).isEqualTo("MODIFIED");
                        assertThat(change.symbol().name()).isEqualTo("name");
                    });
            assertThat(result.binaryIncompatibilities())
                    .anySatisfy(change -> assertThat(change.className()).isEqualTo("sample.Api"));
        }
    }

    private void writeApi(Path repository, String method) throws IOException {
        Path source = repository.resolve("src/main/java/sample/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package sample; public class Api { " + method + " }",
                StandardCharsets.UTF_8);
    }

    private void commitAndTag(Git git, String tag) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(tag).setAuthor("Test", "test@example.com").call();
        git.tag().setName(tag).call();
    }

    private Path compileJar(String version, String method) throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("compile-" + version);
        Path source = sourceRoot.resolve("src/sample/Api.java");
        Path classes = sourceRoot.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(
                source,
                "package sample; public class Api { " + method + " }",
                StandardCharsets.UTF_8);
        int compileExit = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString());
        if (compileExit != 0) throw new IllegalStateException("Could not compile API fixture " + version);

        Path jar = temporaryDirectory.resolve(version + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(classes)) {
            for (Path classFile : paths.filter(Files::isRegularFile).toList()) {
                String entryName = classes.relativize(classFile).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(entryName));
                Files.copy(classFile, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
