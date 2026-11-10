package com.springtechkb.search.internal;

import com.springtechkb.search.ClassifiedQuery;
import java.util.Optional;

interface IntentClassificationRule {
    Optional<ClassifiedQuery> classify(String normalizedQuestion, String originalQuestion, String inferredDomain);
}
