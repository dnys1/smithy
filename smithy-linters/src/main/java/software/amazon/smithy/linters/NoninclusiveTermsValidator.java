/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.linters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.knowledge.TextIndex;
import software.amazon.smithy.model.knowledge.TextInstance;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidationUtils;
import software.amazon.smithy.model.validation.ValidatorService;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.StringUtils;

/**
 * <p>Validates that all shape names, and values does not contain non-inclusive terms.
 * *
 * <p>See AbstractModelTextValidator for scan implementation details.
 */
public final class NoninclusiveTermsValidator extends AbstractValidator {
    static final Map<String, List<String>> BUILT_IN_NONINCLUSIVE_TERMS = MapUtils.of(
            "master", ListUtils.of("primary", "parent", "main"),
            "slave", ListUtils.of("secondary", "replica", "clone", "child"),
            "blacklist", ListUtils.of("denyList"),
            "whitelist", ListUtils.of("allowList")
        );

    public static final class Provider extends ValidatorService.Provider {
        public Provider() {
            super(NoninclusiveTermsValidator.class, node -> {
                NodeMapper mapper = new NodeMapper();
                return new NoninclusiveTermsValidator(
                        mapper.deserialize(node, NoninclusiveTermsValidator.Config.class));
            });
        }
    }

    /**
     * NoninclusiveTermsValidator configuration.
     */
    public static final class Config {
        private Map<String, List<String>> noninclusiveTerms = MapUtils.of();
        private boolean appendDefaults = false;

        public Map<String, List<String>> getNonInclusiveTerms() {
            return noninclusiveTerms;
        }

        public void setNoninclusiveTerms(Map<String, List<String>> terms) {
            this.noninclusiveTerms = terms;
        }

        public boolean isAppendDefaults() {
            return appendDefaults;
        }

        public void setAppendDefaults(boolean appendDefaults) {
            this.appendDefaults = appendDefaults;
        }
    }

    final Map<String, List<String>> termsMap;

    private NoninclusiveTermsValidator(Config config) {
        if (config.isAppendDefaults()) {
            termsMap = new HashMap<>(BUILT_IN_NONINCLUSIVE_TERMS);
            termsMap.putAll(config.getNonInclusiveTerms());
        } else if (!config.getNonInclusiveTerms().isEmpty()) {
            termsMap = MapUtils.copyOf(config.getNonInclusiveTerms());
        } else {
            termsMap = new HashMap<>(BUILT_IN_NONINCLUSIVE_TERMS);
        }

        //Prevent empty string from being a replacement term.
        //It has no value and would mess up the find and replace logic.
        if (termsMap.containsKey("")) {
            throw new IllegalArgumentException("Empty string is not a valid non-inclusive term");
        }
    }

    /**
     * Runs a full text scan on a given model and stores the resulting TextOccurrences objects.
     *
     * Namespaces are checked against a global set per model.
     *
     * @param model Model to validate.
     * @return a list of ValidationEvents found by the implementer of getValidationEvents per the
     *          TextOccurrences provided by this traversal.
     */
    @Override
    public List<ValidationEvent> validate(Model model) {
        TextIndex textIndex = TextIndex.of(model);
        List<ValidationEvent> validationEvents = new ArrayList<>();
        for (TextInstance text : textIndex.getTextInstances()) {
            getValidationEvents(text, validationEvent -> {
                validationEvents.add(validationEvent);
            });
        }
        return validationEvents;
    }

    /**
     * This is a somewhat abstracted interface for validating text instances in a model
     * by pushing ValidationEvents into a provided consumer.
*
     * @param occurrence text occurrence found in the body of the model
     * @param validationEventConsumer consumer to push ValidationEvents into
     */
    private void getValidationEvents(TextInstance instance,
                                       Consumer<ValidationEvent> validationEventConsumer) {
        for (Map.Entry<String, List<String>> termEntry : termsMap.entrySet()) {
            final String termLower = termEntry.getKey().toLowerCase();
            final int startIndex = instance.getText().toLowerCase().indexOf(termLower);
            if (startIndex != -1) {
                final String matchedText = instance.getText().substring(startIndex, startIndex + termLower.length());
                switch (instance.getLocationType()) {
                    case NAMESPACE:
                        validationEventConsumer.accept(ValidationEvent.builder()
                                .sourceLocation(SourceLocation.none())
                                .id(this.getClass().getSimpleName().replaceFirst("Validator$", ""))
                                .severity(Severity.WARNING)
                                .message(formatNonInclusiveTermsValidationMessage(termEntry, matchedText, instance))
                                .build());
                        break;
                    case APPLIED_TRAIT:
                        validationEventConsumer.accept(warning(instance.getShape(),
                                instance.getTrait().getSourceLocation(),
                                formatNonInclusiveTermsValidationMessage(termEntry, matchedText, instance)));
                        break;
                    case SHAPE:
                    default:
                        validationEventConsumer.accept(warning(instance.getShape(),
                                instance.getShape().getSourceLocation(),
                                formatNonInclusiveTermsValidationMessage(termEntry, matchedText, instance)));
                }
            }
        }
    }

    private static String formatNonInclusiveTermsValidationMessage(Map.Entry<String, List<String>> termEntry,
                                                                   String matchedText,
                                                                   TextInstance instance) {
        final List<String> caseCorrectedEntryValue = termEntry.getValue().stream()
            .map(replacement -> Character.isUpperCase(matchedText.charAt(0))
                  ? StringUtils.capitalize(replacement)
                  : StringUtils.uncapitalize(replacement))
            .collect(Collectors.toList());
        String replacementAddendum = termEntry.getValue().size() > 0
                ? String.format(" Consider using one of the following terms instead: %s",
                    ValidationUtils.tickedList(caseCorrectedEntryValue))
                : "";
        switch (instance.getLocationType()) {
            case SHAPE:
                return String.format("%s shape uses a non-inclusive term `%s`.%s",
                        StringUtils.capitalize(instance.getShape().getType().toString()),
                        matchedText, replacementAddendum);
            case NAMESPACE:
                return String.format("%s namespace uses a non-inclusive term `%s`.%s",
                        instance.getText(), matchedText, replacementAddendum);
            case APPLIED_TRAIT:
                if (instance.getTraitPropertyPath().isEmpty()) {
                    return String.format("'%s' trait has a value that contains a non-inclusive term `%s`.%s",
                            Trait.getIdiomaticTraitName(instance.getTrait()), matchedText,
                            replacementAddendum);
                } else {
                    String valuePropertyPathFormatted = formatPropertyPath(instance.getTraitPropertyPath());
                    return String.format("'%s' trait value at path {%s} contains a non-inclusive term `%s`.%s",
                            Trait.getIdiomaticTraitName(instance.getTrait()), valuePropertyPathFormatted,
                            matchedText, replacementAddendum);
                }
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * The complexity here is necessary to format property path indexes as parentKey[index] instead
     * of parentKey.[index]. It's subtle, but programmers are quite used to not seeing the dot separator
     * before an index.  It is not possible to conditionalize the delimiter of a string join, so
     * this logic has to be written
     */
    private static String formatPropertyPath(List<TextInstance.PathElement> propertyPath) {
        Objects.requireNonNull(propertyPath);
        Function<TextInstance.PathElement, String> elementToString = pathElement -> {
            switch (pathElement.getElementType()) {
                case KEY:
                    return pathElement.getKey();
                case ARRAY_INDEX:
                    return "[" + pathElement.getIndex() + "]";
                default:
                    throw new IllegalStateException();
            }
        };

        StringBuilder builder = new StringBuilder();
        ListIterator<TextInstance.PathElement> pathElementIterator = propertyPath.listIterator();
        while (pathElementIterator.hasNext()) {
            boolean hasPrevious = pathElementIterator.hasPrevious();
            TextInstance.PathElement pathElement = pathElementIterator.next();
            if (hasPrevious && pathElement.getElementType() != TextInstance.PathElementType.ARRAY_INDEX) {
                builder.append(".");
            }
            builder.append(elementToString.apply(pathElement));
        }

        return builder.toString();
    }
}
