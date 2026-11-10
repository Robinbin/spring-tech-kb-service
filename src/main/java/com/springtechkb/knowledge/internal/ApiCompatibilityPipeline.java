package com.springtechkb.knowledge.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.Modifier;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.JApiClass;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.KnowledgeSource;

/** Extracts released Java APIs from Git tags and records source/binary compatibility changes. */
@Service
public class ApiCompatibilityPipeline {

    private final JdbcTemplate jdbc;
    private final SourceAdmissionPolicy admissionPolicy;

    public ApiCompatibilityPipeline(JdbcTemplate jdbc, SourceAdmissionPolicy admissionPolicy) {
        this.jdbc = jdbc;
        this.admissionPolicy = admissionPolicy;
    }

    @Transactional
    public PipelineResult scan(
            Path repositoryPath,
            String previousTag,
            String currentTag,
            Path previousJar,
            Path currentJar,
            SourceAdmission admission) {
        admissionPolicy.validate(admission, false);
        if (!"github.com".equalsIgnoreCase(admission.uri().getHost())
                || (admission.sourceType() != KnowledgeSource.SPRING_GITHUB
                        && admission.sourceType() != KnowledgeSource.SPRING_RELEASE_NOTES)) {
            throw new IllegalArgumentException("API release diffs require an approved spring-projects GitHub source");
        }
        if (previousTag == null || previousTag.isBlank()
                || currentTag == null || currentTag.isBlank()
                || previousTag.equals(currentTag)) {
            throw new IllegalArgumentException("Two distinct release tags are required");
        }

        try (Git git = Git.open(repositoryPath.toFile()); RevWalk revWalk = new RevWalk(git.getRepository())) {
            Map<String, ApiSymbol> oldSymbols = symbolsAtTag(git, revWalk, previousTag);
            Map<String, ApiSymbol> newSymbols = symbolsAtTag(git, revWalk, currentTag);
            var oldCommit = revWalk.parseCommit(git.getRepository().resolve(previousTag + "^{commit}"));
            var newCommit = revWalk.parseCommit(git.getRepository().resolve(currentTag + "^{commit}"));
            if (!revWalk.isMergedInto(oldCommit, newCommit)) {
                throw new IllegalArgumentException("Previous release tag must be an ancestor of current tag");
            }
            List<SymbolChange> changes = compareSymbols(oldSymbols, newSymbols);
            List<BinaryIncompatibility> binaryChanges =
                    compareBinaryCompatibility(previousJar, currentJar, previousTag, currentTag);
            UUID sourceId = upsertSource(admission, currentTag);
            persistChanges(sourceId, currentTag, changes);
            return new PipelineResult(previousTag, currentTag, changes, binaryChanges);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect release repository at " + repositoryPath, exception);
        }
    }

    private Map<String, ApiSymbol> symbolsAtTag(Git git, RevWalk revWalk, String tag) throws IOException {
        var objectId = git.getRepository().resolve(tag + "^{commit}");
        if (objectId == null) throw new IllegalArgumentException("Release tag does not resolve to a commit: " + tag);
        RevCommit commit = revWalk.parseCommit(objectId);
        Map<String, ApiSymbol> symbols = new LinkedHashMap<>();
        try (TreeWalk tree = new TreeWalk(git.getRepository())) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (!path.endsWith(".java")
                        || !(path.startsWith("src/main/java/") || path.contains("/src/main/java/"))) continue;
                ObjectLoader loader = git.getRepository().open(tree.getObjectId(0));
                String source = new String(loader.getBytes(), StandardCharsets.UTF_8);
                try {
                    extractSymbols(StaticJavaParser.parse(source), symbols);
                } catch (ParseProblemException exception) {
                    throw new IllegalStateException(
                            "Could not parse Java source at " + path + " for tag " + tag, exception);
                }
            }
        }
        return symbols;
    }

    private void extractSymbols(CompilationUnit unit, Map<String, ApiSymbol> symbols) {
        unit.getTypes().forEach(type -> extractType(type, packageName(unit), "", symbols));
    }

    private void extractType(
            TypeDeclaration<?> type, String packageName, String enclosingType, Map<String, ApiSymbol> symbols) {
        if (!isPublicOrProtected(type)) return;
        String simpleName = enclosingType.isBlank()
                ? type.getNameAsString()
                : enclosingType + "." + type.getNameAsString();
        String className = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
        String typeParameters = type instanceof ClassOrInterfaceDeclaration declaration
                ? declaration.getTypeParameters().toString() + declaration.getExtendedTypes()
                        + declaration.getImplementedTypes()
                : "";
        addSymbol(symbols, className, typeKind(type), type.getNameAsString(),
                type.getNameAsString() + typeParameters, javadoc(type));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                extractType(nested, packageName, simpleName, symbols);
            } else if (member instanceof MethodDeclaration method && isPublicOrProtected(method)) {
                String signature = method.getDeclarationAsString(true, false, true);
                addSymbol(symbols, className, "METHOD", method.getNameAsString(), signature, javadoc(method));
            } else if (member instanceof ConstructorDeclaration constructor && isPublicOrProtected(constructor)) {
                String signature = constructor.getDeclarationAsString(true, false, true);
                addSymbol(symbols, className, "CONSTRUCTOR", constructor.getNameAsString(), signature,
                        javadoc(constructor));
            } else if (member instanceof FieldDeclaration field && isPublicOrProtected(field)) {
                field.getVariables().forEach(variable -> addSymbol(
                        symbols, className, "FIELD", variable.getNameAsString(),
                        field.getModifiers() + " " + variable.getType() + " " + variable.getNameAsString(),
                        javadoc(field)));
            }
        }
    }

    private boolean isPublicOrProtected(NodeWithModifiers<?> node) {
        return node.getModifiers().stream().anyMatch(modifier ->
                modifier.getKeyword() == Modifier.Keyword.PUBLIC
                        || modifier.getKeyword() == Modifier.Keyword.PROTECTED);
    }

    private String packageName(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
    }

    private String typeKind(TypeDeclaration<?> type) {
        if (type.isAnnotationDeclaration()) return "ANNOTATION";
        if (type.isEnumDeclaration()) return "ENUM";
        if (type.isRecordDeclaration()) return "RECORD";
        if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) return "INTERFACE";
        return "CLASS";
    }

    private String javadoc(com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<?> node) {
        return node.getJavadocComment().map(comment -> comment.parse().toText()).orElse(null);
    }

    private void addSymbol(
            Map<String, ApiSymbol> symbols,
            String className,
            String kind,
            String name,
            String signature,
            String javadoc) {
        String identity = className + "|" + kind + "|" + name + "|" + parameterPart(signature);
        symbols.put(identity, new ApiSymbol(className, kind, name, signature, javadoc));
    }

    private String parameterPart(String signature) {
        int open = signature.indexOf('(');
        int close = signature.indexOf(')', open + 1);
        return open < 0 || close < 0 ? "" : signature.substring(open, close + 1);
    }

    private List<SymbolChange> compareSymbols(Map<String, ApiSymbol> oldSymbols, Map<String, ApiSymbol> newSymbols) {
        List<SymbolChange> changes = new ArrayList<>();
        newSymbols.forEach((identity, current) -> {
            ApiSymbol previous = oldSymbols.get(identity);
            if (previous == null) {
                changes.add(new SymbolChange(current, "ADDED", null));
            } else if (!previous.signature().equals(current.signature())
                    || !java.util.Objects.equals(previous.javadoc(), current.javadoc())) {
                changes.add(new SymbolChange(current, "MODIFIED", previous.signature()));
            } else {
                changes.add(new SymbolChange(current, "UNCHANGED", null));
            }
        });
        oldSymbols.forEach((identity, previous) -> {
            if (!newSymbols.containsKey(identity)) {
                changes.add(new SymbolChange(previous, "REMOVED", previous.signature()));
            }
        });
        return List.copyOf(changes);
    }

    private List<BinaryIncompatibility> compareBinaryCompatibility(
            Path previousJar, Path currentJar, String previousTag, String currentTag) {
        if (previousJar == null || currentJar == null
                || !java.nio.file.Files.isRegularFile(previousJar)
                || !java.nio.file.Files.isRegularFile(currentJar)) {
            throw new IllegalArgumentException("Both release JAR files must exist for japicmp comparison");
        }
        JarArchiveComparatorOptions options = new JarArchiveComparatorOptions();
        JarArchiveComparator comparator = new JarArchiveComparator(options);
        List<JApiClass> classes = comparator.compare(
                List.of(new JApiCmpArchive(previousJar.toFile(), previousTag)),
                List.of(new JApiCmpArchive(currentJar.toFile(), currentTag)));
        List<BinaryIncompatibility> incompatible = new ArrayList<>();
        for (JApiClass apiClass : classes) {
            List<String> changes = new ArrayList<>();
            apiClass.getCompatibilityChanges().stream()
                    .filter(change -> !change.isBinaryCompatible())
                    .map(change -> change.getType().name())
                    .forEach(changes::add);
            apiClass.getMethods().forEach(method -> method.getCompatibilityChanges().stream()
                    .filter(change -> !change.isBinaryCompatible())
                    .map(change -> "METHOD " + method.getName() + ": " + change.getType().name())
                    .forEach(changes::add));
            apiClass.getConstructors().forEach(constructor -> constructor.getCompatibilityChanges().stream()
                    .filter(change -> !change.isBinaryCompatible())
                    .map(change -> "CONSTRUCTOR " + constructor.getName() + ": " + change.getType().name())
                    .forEach(changes::add));
            apiClass.getFields().forEach(field -> field.getCompatibilityChanges().stream()
                    .filter(change -> !change.isBinaryCompatible())
                    .map(change -> "FIELD " + field.getName() + ": " + change.getType().name())
                    .forEach(changes::add));
            if (!changes.isEmpty()) {
                incompatible.add(new BinaryIncompatibility(apiClass.getFullyQualifiedName(), List.copyOf(changes)));
            }
        }
        return List.copyOf(incompatible);
    }

    private UUID upsertSource(SourceAdmission admission, String tag) {
        String baseUrl = admission.uri().toString().replaceAll("/+$", "");
        String url = baseUrl + "/tree/" + tag;
        return jdbc.queryForObject(
                """
                INSERT INTO knowledge_source
                    (title, url, license_type, author, spring_module, version, is_approved,
                     license_url, approved_by, approval_reference)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                ON CONFLICT (url) DO UPDATE SET
                    title = EXCLUDED.title, version = EXCLUDED.version, is_approved = TRUE,
                    license_url = EXCLUDED.license_url, approved_by = EXCLUDED.approved_by,
                    approval_reference = EXCLUDED.approval_reference
                RETURNING id
                """,
                UUID.class, admission.title() + " " + tag, url, admission.license().databaseValue(),
                admission.author(), admission.springModule(), tag, admission.licenseUrl(), admission.approvedBy(),
                admission.approvalReference());
    }

    private void persistChanges(UUID sourceId, String version, List<SymbolChange> changes) {
        for (SymbolChange change : changes) {
            jdbc.update(
                    """
                    INSERT INTO knowledge_api_symbol
                        (source_id, class_name, symbol_kind, signature, javadoc, spring_version,
                         change_type, previous_signature)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source_id, spring_version, class_name, symbol_kind, signature) DO UPDATE SET
                        javadoc = EXCLUDED.javadoc, change_type = EXCLUDED.change_type,
                        previous_signature = EXCLUDED.previous_signature
                    """,
                    sourceId, change.symbol().className(), change.symbol().kind(), change.symbol().signature(),
                    change.symbol().javadoc(), version, change.changeType(), change.previousSignature());
        }
    }

    public record ApiSymbol(String className, String kind, String name, String signature, String javadoc) {}
    public record SymbolChange(ApiSymbol symbol, String changeType, String previousSignature) {}
    public record BinaryIncompatibility(String className, List<String> changes) {}
    public record PipelineResult(
            String previousTag,
            String currentTag,
            List<SymbolChange> sourceChanges,
            List<BinaryIncompatibility> binaryIncompatibilities) {}
}
