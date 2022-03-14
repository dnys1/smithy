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

package software.amazon.smithy.model.knowledge;

import java.util.Deque;
import java.util.List;
import java.util.Objects;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.Trait;

public final class TextInstance {
    private final TextLocation locationType;
    private final String text;
    private final Shape shape;
    private final Trait trait;
    private final List<String> traitPropertyPath;

    private TextInstance(final TextLocation locationType,
            final String text,
            final Shape shape,
            final Trait trait,
            final Deque<String> traitPropertyPath) {
        this.locationType = locationType;
        this.text = text;
        this.shape = shape;
        this.trait = trait;
        this.traitPropertyPath = traitPropertyPath != null
            ? List.copyOf(traitPropertyPath)
            : null;
    }

    public static TextInstance createNamespaceText(String namespace) {
        Objects.requireNonNull(namespace, "Namespace must be specified");
        return new TextInstance(TextLocation.NAMESPACE, namespace, null, null, null);
    }

    public static TextInstance createShapeInstance(Shape shape) {
        Objects.requireNonNull(shape, "Shape must be specified");
        return new TextInstance(TextLocation.SHAPE, shape.getId().getMember().orElseGet(() -> shape.getId().getName()),
                        shape, null, null);
    }

    public static TextInstance createTraitInstance(String text, Shape shape, Trait trait, Deque<String> traitPath) {
        Objects.requireNonNull(trait, "'trait' must be specified");
        Objects.requireNonNull(shape, "'shape' must be specified");
        Objects.requireNonNull(text, "'text' must be specified");
        return new TextInstance(TextLocation.APPLIED_TRAIT, text, shape, trait, traitPath);
    }

    public TextLocation getLocationType() {
        return locationType;
    }

    public String getText() {
        return text;
    }

    public Shape getShape() {
        return shape;
    }

    public Trait getTrait() {
        return trait;
    }

    public List<String> getTraitPropertyPath() {
        return traitPropertyPath;
    }

    public enum TextLocation {
        SHAPE,
        APPLIED_TRAIT,
        NAMESPACE
    }
}
