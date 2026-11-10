package com.springtechkb.generation.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static com.springtechkb.generation.internal.GenerationDtos.*;

/** Builds only fixed local files; generated snippets remain non-executable reference material. */
@Component
class ProjectArchiveBuilder {
    private static final Set<String> JAVA_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");
    private final JsonMapper json;

    ProjectArchiveBuilder(JsonMapper json) { this.json = json; }

    byte[] build(Output output, Output example) {
        ProjectRequest project = output.project();
        String group = project.groupId();
        if (!group.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                || group.length() > 120
                || java.util.Arrays.stream(group.split("\\.")).anyMatch(JAVA_RESERVED::contains)
                || !project.artifactId().matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
                || project.artifactId().length() > 64
                || !project.name().matches("[\\p{L}\\p{N} ._-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project coordinates");
        }
        String packagePath = group.replace('.', '/');
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.2</version><relativePath/></parent>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>0.0.1-SNAPSHOT</version>
                  <name>%s</name>
                  <properties><java.version>21</java.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId><version>4.1.2</version></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><version>4.1.2</version><scope>test</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><version>4.1.2</version></plugin></plugins></build>
                </project>
                """.formatted(group, project.artifactId(), HtmlEscaper.escape(project.name())));
        files.put("src/main/java/" + packagePath + "/Application.java", """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
                }

                @RestController
                class HelloController {
                    @GetMapping("/hello")
                    String hello() { return "Hello, Spring"; }
                }
                """.formatted(group));
        files.put("src/test/java/" + packagePath + "/ApplicationTest.java", """
                package %s;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;
                import static org.assertj.core.api.Assertions.assertThat;

                @SpringBootTest
                class ApplicationTest {
                    @Test void helloReturnsGreeting() {
                        assertThat(new HelloController().hello()).isEqualTo("Hello, Spring");
                    }
                }
                """.formatted(group));
        files.put("README.md", """
                # %s

                AI_GENERATED local scaffold: Java 21, Spring Boot 4.1.2, Maven 3.9+.
                Review all generated material before use. This ZIP was assembled locally;
                no commands were executed and no remote code was fetched during generation.

                To test locally: `mvn test`. To run locally: `mvn spring-boot:run`.
                The example endpoint is `GET http://localhost:8080/hello`.
                These commands download Maven dependencies when run by you.

                Source licenses and provenance are in LICENSE.txt and PROVENANCE.json.
                Optional generated-example.txt is documentation only, deliberately outside source paths.
                No secrets, Maven wrapper binary, external scripts or executable generated snippets are included.
                """.formatted(project.name()));
        files.put("LICENSE.txt", """
                Local scaffold authored by Spring Tech KB is provided under the MIT License:
                Copyright (c) 2026 Spring Tech KB contributors.
                Permission is hereby granted, free of charge, to any person obtaining a copy of this software
                and associated documentation files (the "Software"), to deal in the Software without restriction,
                including without limitation the rights to use, copy, modify, merge, publish, distribute,
                sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
                is furnished to do so, subject to the following conditions:
                The above copyright notice and this permission notice shall be included in all copies
                or substantial portions of the Software.
                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
                INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
                PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
                FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
                ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

                This license covers only the fixed local scaffold, not upstream dependencies or excerpts.
                Spring Boot dependencies are Apache-2.0; https://www.apache.org/licenses/LICENSE-2.0
                Optional source-grounded generated references may be subject to the original source licenses
                listed below and in PROVENANCE.json. No license to upstream material is superseded here.
                """ + output.sources().stream().map(source -> "\n" + source.title() + "\n" + source.url()
                + "\nLicense: " + source.license() + " " + source.licenseUrl() + "\nAuthor: " + source.author() + "\n")
                .collect(java.util.stream.Collectors.joining()));
        files.put("PROVENANCE.json", json.writeValueAsString(Map.of(
                "generationId", output.id(), "origin", output.origin(), "aiGenerated", true,
                "modelId", output.modelId(), "promptVersion", output.promptVersion(),
                "sources", output.sources(), "createdAt", output.createdAt(),
                "javaVersion", "21", "springBootVersion", "4.1.2")));
        if (example != null) {
            files.put("reference/generated-example.txt", "AI_GENERATED — review before use.\n\n"
                    + example.title() + "\n\n" + example.content() + "\n\n" + example.code());
        }
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            long total = 0;
            for (var entry : files.entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("/") || path.contains("\\") || path.contains("..") || path.contains(":")) {
                    throw new IllegalArgumentException("Unsafe archive entry");
                }
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                total += content.length;
                if (total > 256_000) throw new IllegalArgumentException("Project archive exceeds size limit");
                ZipEntry member = new ZipEntry(path);
                member.setTime(0);
                zip.putNextEntry(member);
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to assemble project archive", exception);
        }
    }
}
