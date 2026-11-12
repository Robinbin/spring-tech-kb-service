package com.springtechkb.knowledge.internal;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.SourceLicense;

/** Enforces the D1 approved-source allowlist and H-9 body-content restrictions. */
@Component
public class SourceAdmissionPolicy {

    private static final Set<String> OFFICIAL_HOSTS =
            Set.of("spring.io", "docs.spring.io", "github.com");

    public void validate(SourceAdmission admission, boolean ingestBody) {
        URI uri = admission.uri().normalize();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Approved sources must use a clean HTTPS URL");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!OFFICIAL_HOSTS.contains(host)) {
            throw new IllegalArgumentException("Source host is not on the approved allowlist: " + host);
        }
        if (host.equals("github.com") && !isSpringProjectsRepository(uri.getPath())) {
            throw new IllegalArgumentException("Only spring-projects GitHub repositories are approved");
        }

        if (!admission.sourceType().isAllowed()) {
            throw new IllegalArgumentException(
                    "AI-generated material must use the separately reviewed-content workflow");
        }

        if (!admission.sourceType().requiresBodyIngestion()) {
            if (ingestBody) {
                throw new IllegalArgumentException("Third-party blog body text is not accepted (H-9)");
            }
            if (admission.license() != SourceLicense.CC_BY_SA
                    || blank(admission.licenseUrl())
                    || blank(admission.approvedBy())
                    || blank(admission.approvalReference())) {
                throw new IllegalArgumentException(
                        "Blog metadata requires per-article licence evidence, reviewer, and approval reference");
            }
            return;
        }
        if (admission.license() != SourceLicense.APACHE_2
                && admission.license() != SourceLicense.OFFICIAL_DOCS) {
            throw new IllegalArgumentException("Official Spring sources require Apache-2.0 evidence");
        }
        if (blank(admission.licenseUrl())
                || blank(admission.approvedBy())
                || blank(admission.approvalReference())) {
            throw new IllegalArgumentException(
                    "Approved source records require licence evidence, reviewer, and approval reference");
        }
    }

    private boolean isSpringProjectsRepository(String path) {
        if (path == null) return false;
        String[] parts = path.split("/");
        return parts.length >= 3 && parts[1].equals("spring-projects");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
