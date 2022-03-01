/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Helper class for generating code.
 *
 * <p>A CodeWriter can be used to write basically any kind of code, including
 * whitespace sensitive and brace-based.
 *
 * <p>The following example generates some Python code:
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.write("def Foo(str):")
 *       .indent()
 *       .write("print str");
 * String code = writer.toString();
 * }</pre>
 *
 * <h2>Code interpolation</h2>
 *
 * <p>The {@link #write}, {@link #openBlock}, and {@link #closeBlock} methods
 * take a code expression and a variadic list of arguments that are
 * interpolated into the expression. Consider the following call to
 * {@code write}:
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.write("Hello, $L", "there!");
 * String code = writer.toString();
 * }</pre>
 *
 * <p>In the above example, {@code $L} is interpolated and replaced with the
 * relative argument {@code there!}.
 *
 * <p>A CodeWriter supports three kinds of interpolations: relative,
 * positional, and named. Each of these kinds of interpolations pass a value
 * to a <em>formatter</em>.</p>
 *
 * <h3>Formatters</h3>
 *
 * <p>Formatters are named functions that accept an object as input, accepts a
 * string that contains the current indentation (it can be ignored if not useful),
 * and returns a string as output. The {@code CodeWriter} registers two built-in
 * formatters:
 *
 * <ul>
 *     <li>{@code L} (literal): Outputs a literal value of an {@code Object} using
 *     the following implementation: (1) A null value is formatted as "".
 *     (2) An empty {@code Optional} value is formatted as "". (3) A non-empty
 *     {@code Optional} value is recursively formatted using the value inside
 *     of the {@code Optional}. (3) All other valeus are formatted using the
 *     result of calling {@link String#valueOf}.</li>
 *
 *     <li>{@code C} (call): Runs a {@link Runnable} or {@link Consumer} argument
 *     that is expected to write to the same writer. Any text written to the CodeWriter
 *     inside of the Runnable is used as the value of the argument. Note that a
 *     single trailing newline is removed from the captured text. If a Runnable is
 *     provided, it is required to have a reference to the CodeWriter. A Consumer
 *     is provided a reference to the CodeWriter as a single argument.
 *
 *     <pre>{@code
 *     CodeWriter writer = new CodeWriter();
 *     writer.write("Hello, $C.", () -> writer.write("there"));
 *     assert(writer.toString().equals("Hello, there.\n"));
 *     }</pre></li>
 *
 *     <li>{@code S} (string): Adds double quotes around the result of formatting a
 *     value first using the default literal "L" implementation described
 *     above and then wrapping the value in an escaped string safe for use in
 *     Java according to https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.10.6.
 *     This formatter can be overridden if needed to support other
 *     programming languages.</li>
 * </ul>
 *
 * <p>Custom formatters can be registered using {@link #putFormatter}. Custom
 * formatters can be used only within the state they were added. Because states
 * inherit the formatters of parent states, adding a formatter to the root state
 * of the CodeWriter allows the formatter to be used in any state.
 *
 * <p>The identifier given to a formatter must match one of the following
 * characters:
 *
 * <pre>
 *    "!" / "#" / "%" / "&amp;" / "*" / "+" / "," / "-" / "." / "/" / ";"
 *  / "=" / "?" / "@" / "A" / "B" / "C" / "D" / "E" / "F" / "G" / "H"
 *  / "I" / "J" / "K" / "L" / "M" / "N" / "O" / "P" / "Q" / "R" / "S"
 *  / "T" / "U" / "V" / "W" / "X" / "Y" / "Z" / "^" / "_" / "`" / "~"
 * </pre>
 *
 * <h3>Relative parameters</h3>
 *
 * <p>Placeholders in the form of "$" followed by a formatter name are treated
 * as relative parameters. The first instance of a relative parameter
 * interpolates the first positional argument, the second the second, etc.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.write("$L $L $L", "a", "b", "c");
 * System.out.println(writer.toString());
 * // Outputs: "a b c"
 * }</pre>
 *
 * <p>All relative arguments must be used as part of an expression and
 * relative interpolation cannot be mixed with positional variables.
 *
 * <h3>Positional parameters</h3>
 *
 * <p>Placeholders in the form of "$" followed by a positive number,
 * followed by a formatter name are treated as positional parameters. The
 * number refers to the 1-based index of the argument to interpolate.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.write("$1L $2L $3L, $3L $2L $1L", "a", "b", "c");
 * System.out.println(writer.toString());
 * // Outputs: "a b c c b a"
 * }</pre>
 *
 * <p>All positional arguments must be used as part of an expression
 * and relative interpolation cannot be mixed with positional variables.
 *
 * <h3>Named parameters</h3>
 *
 * <p>Named parameters are parameters that take a value from the context of
 * the current state. They take the following form {@code $<variable>:<formatter>},
 * where {@code <variable>} is a string that starts with a lowercase letter,
 * followed by any number of {@code [A-Za-z0-9_#$.]} characters, and
 * {@code <formatter>} is the name of a formatter.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.putContext("foo", "a");
 * writer.putContext("baz.bar", "b");
 * writer.write("$foo:L $baz.bar:L");
 * System.out.println(writer.toString());
 * // Outputs: "a b"
 * }</pre>
 *
 * <h3>Escaping interpolation</h3>
 *
 * <p>You can escape the "$" character using two "$$".
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter().write("$$L");
 * System.out.println(writer.toString());
 * // Outputs: "$L"
 * }</pre>
 *
 * <h3>Custom expression characters</h3>
 *
 * <p>The character used to start a code block expression can be customized
 * to make it easier to write code that makes heavy use of {@code $}. The
 * default character used to start an expression is, {@code $}, but this can
 * be changed for the current state of the CodeWriter by calling
 * {@link #setExpressionStart(char)}. A custom start character can be escaped
 * using two start characters in a row. For example, given a custom start
 * character of {@code #}, {@code #} can be escaped using {@code ##}.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.setExpressionStart('#');
 * writer.write("#L ##L $L", "hi");
 * System.out.println(writer.toString());
 * // Outputs: "hi #L $L"
 * }</pre>
 *
 * <em>The start character cannot be set to ' ' or '\n'.</em>
 *
 * <h2>Opening and closing blocks</h2>
 *
 * <p>{@code CodeWriter} provides a short cut for opening code blocks that
 * have an opening an closing delimiter (for example, "{" and "}") and that
 * require indentation inside of the delimiters. Calling {@link #openBlock}
 * and providing the opening statement will write and format a line followed
 * by indenting one level. Calling {@link #closeBlock} will first dedent and
 * then print a formatted statement.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter()
 *       .openBlock("if ($L) {", someValue)
 *       .write("System.out.println($S);", "Hello!")
 *       .closeBlock("}");
 * }</pre>
 *
 * <p>The above example outputs (assuming someValue is equal to "foo"):
 *
 * <pre>{@code
 * if (foo) {
 *     System.out.println("Hello!");
 * }
 * }</pre>
 *
 * <h2>Pushing and popping state</h2>
 *
 * <p>The CodeWriter can maintain a stack of transformation states, including
 * the text used to indent, a prefix to add before each line, newline character,
 * the number of times to indent, a map of context values, whether or not
 * whitespace is trimmed from the end of newlines, whether or not the automatic
 * insertion of newlines is disabled, the character used to start code
 * expressions (defaults to {@code $}), and formatters. State can be pushed onto
 * the stack using {@link #pushState} which copies the current state. Mutations
 * can then be made to the top-most state of the CodeWriter and do not affect
 * previous states. The previous transformation state of the CodeWriter can later
 * be restored using {@link #popState}.
 *
 * <p>The CodeWriter is stateful, and a prefix can be added before each line.
 * This is useful for doing things like create Javadoc strings:
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer
 *       .pushState()
 *       .write("/**")
 *       .setNewlinePrefix(" * ")
 *       .write("This is some docs.")
 *       .write("And more docs.\n\n\n")
 *       .write("Foo.")
 *       .popState()
 *       .write(" *\/");
 * }</pre>
 *
 * <p>The above example outputs:
 *
 * <pre>{@code
 * /**
 *  * This is some docs.
 *  * And more docs.
 *  *
 *  * Foo.
 *  *\/
 *
 *   ^ Minus this escape character
 * }</pre>
 *
 * <p>The CodeWriter maintains some global state that is not affected by
 * {@link #pushState} and {@link #popState}:
 *
 * <ul>
 *     <li>The number of successive blank lines to trim.</li>
 *     <li>Whether or not a trailing newline is inserted or removed from
 *     the result of converting the {@code CodeWriter} to a string.</li>
 * </ul>
 *
 * <h2>Limiting blank lines</h2>
 *
 * <p>Many coding standards recommend limiting the number of successive blank
 * lines. This can be handled automatically by {@code CodeWriter} by calling
 * {@link #trimBlankLines}. The removal of blank lines is handled when the
 * {@code CodeWriter} is converted to a string. Lines that consist solely
 * of spaces or tabs are considered blank. If the number of blank lines
 * exceeds the allowed threshold, they are omitted from the result.
 *
 * <h2>Trimming trailing spaces</h2>
 *
 * <p>Trailing spaces can be automatically trimmed from each line by calling
 * {@link #trimTrailingSpaces}.
 *
 * <p>In the following example:
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * String result = writer.trimTrailingSpaces().write("hello  ").toString();
 * }</pre>
 *
 * <p>The value of {@code result} contains {@code "hello"}
 *
 * <h2>Extending CodeWriter</h2>
 *
 * <p>{@code CodeWriter} can be extended to add functionality for specific
 * programming languages. For example, Java specific code generator could
 * be implemented that makes it easier to write Javadocs.
 *
 * <pre>{@code
 * class JavaCodeWriter extends CodeWriter {
 *     public JavaCodeWriter javadoc(Runnable runnable) {
 *         pushState()
 *         write("/**")
 *         setNewlinePrefix(" * ")
 *         runnable.run();
 *         popState()
 *         write(" *\/");
 *         return this;
 *     }
 * }
 *
 * JavaCodeWriter writer = new JavaCodeWriter();
 * writer.javadoc(() -> {
 *     writer.write("This is an example.");
 * });
 * }</pre>
 *
 * <h2>Code sections</h2>
 *
 * <p>Named sections can be marked in the code writer that can be intercepted
 * and modified by <em>section interceptors</em>. This gives the
 * {@code CodeWriter} a lightweight extension system for augmenting generated
 * code.
 *
 * <p>A section of code can be captured using a block state or an inline
 * section. Section names must match the following regular expression:
 * <code>^[a-z]+[a-zA-Z0-9_.#$]*$</code>.
 *
 * <h3>Block states</h3>
 *
 * <p>A block section is created by passing a string to {@link #pushState}.
 * This string gives the state a name and captures all of the output written
 * inside of this state to an internal buffer. This buffer is then passed to
 * each registered interceptor for that name. These interceptors can choose
 * to use the default contents of the section or emit entirely different
 * content. Interceptors are expected to make calls to the {@code CodeWriter}
 * in order to emit content. Interceptors need to have a reference to the
 * {@code CodeWriter} as one is not provided to them when they are invoked.
 * Interceptors are invoked in the order in which they are added to the
 * {@code CodeBuilder}.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.onSection("example", text -> writer.write("Intercepted: " + text));
 * writer.pushState("example");
 * writer.write("Original contents");
 * writer.popState();
 * System.out.println(writer.toString());
 * // Outputs: "Intercepted: Original contents\n"
 * }</pre>
 *
 * <h3>Inline sections</h3>
 *
 * An inline section is created using a special {@code CodeWriter} interpolation
 * format that appends "@" followed by the section name. Inline sections are
 * function just like block sections, but they can appear inline inside of
 * other content passed in calls to {@link CodeWriter#write}. An inline section
 * that makes no calls to {@link CodeWriter#write} expands to an empty string.
 *
 * <p>Inline sections are created in a format string inside of braced arguments
 * after the formatter. For example, <code>${L@foo}</code> is an inline section
 * that uses the literal "L" value of a relative argument as the default value
 * of the section and allows interceptors registered for the "foo" section to
 * make calls to the {@code CodeWriter} to modify the section.
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.onSection("example", text -> writer.write("Intercepted: " + text));
 * writer.write("Leading text...${L@example}...Trailing text...", "foo");
 * System.out.println(writer.toString());
 * // Outputs: "Leading text...Intercepted: foo...Trailing text...\n"
 * }</pre>
 *
 * Inline sections are useful for composing sets or lists from any code with access to {@code CodeWriter}:
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.onSection("example", text -> writer.write(text + "1, "));
 * writer.onSection("example", text -> writer.write(text + "2, "));
 * writer.onSection("example", text -> writer.write(text + "3"));
 * writer.write("[${L@example}]", "");
 * System.out.println(writer.toString());
 * // Outputs: "[1, 2, 3]\n"
 * }</pre>
 *
 * <h3>Inline block alignment</h3>
 *
 * <p>The long-form interpolation syntax allows for
 * <em>inline block alignment</em>, which means that any newline emitted by
 * the interpolation is automatically aligned with the column of where the
 * interpolation occurs. Inline block indentation is defined by preceding
 * the closing '}' character with '|' (e.g., <code>${L|}</code>):
 *
 * <pre>{@code
 * CodeWriter writer = new CodeWriter();
 * writer.write("$L: ${L|}", "Names", "Bob\nKaren\nLuis");
 * System.out.println(writer.toString());
 * // Outputs: "Names: Bob\n       Karen\n       Luis\n"
 * }</pre>
 *
 * <p>Alignment occurs either statically or dynamically based on the characters
 * that come before interpolation. If all of the characters in the literal
 * template that come before interpolation are spaces and tabs, then those
 * characters are used when indenting newlines. Otherwise, the number of
 * characters written as the template result that come before interpolation
 * are used when indenting (this takes into account any interpolation that
 * may precede block interpolation).
 *
 * <p>Block interpolation is particularly used when using text blocks in Java
 * because it allows templates to more closely match their end result.
 *
 * <pre>{@code
 * // Assume handleNull, handleA, and handleB are Runnable.
 * writer.write("""
 *     if (foo == null) {
 *         ${C|}
 *     } else if (foo == "a") {
 *         ${C|}
 *     } else if (foo == "b") {
 *         ${C|}
 *     }
 *     """,
 *     handleNull,
 *     handleA,
 *     handleB);
 * }</pre>
 */
public abstract class AbstractCodeWriter<T extends AbstractCodeWriter<T>> {

    private static final Pattern LINES = Pattern.compile("\\r?\\n");
    private static final Map<Character, BiFunction<Object, String, String>> DEFAULT_FORMATTERS = MapUtils.of(
            'L', (s, i) -> formatLiteral(s),
            'S', (s, i) -> StringUtils.escapeJavaString(formatLiteral(s), i));

    private final Deque<State> states = new ArrayDeque<>();
    private State currentState;
    private boolean trailingNewline = true;
    private int trimBlankLines = -1;

    /**
     * Tracks when indentation is needed following a newline.
     *
     * <p>This is initially set to true to account for the case when a
     * code writer is initialized with indentation but hasn't written
     * anything yet.
     */
    private boolean needsIndentation = true;

    /**
     * Creates a new CodeWriter that uses "\n" for a newline, four spaces
     * for indentation, does not strip trailing whitespace, does not flatten
     * multiple successive blank lines into a single blank line, and adds no
     * trailing new line.
     */
    public AbstractCodeWriter() {
        states.push(new State());
        currentState = states.getFirst();
        currentState.builder = new StringBuilder();
    }

    /**
     * Copies settings from the given CodeWriter into this CodeWriter.
     *
     * <p>The settings of the {@code other} CodeWriter will overwrite
     * both global and state-based settings of this CodeWriter. Formatters of
     * the {@code other} CodeWriter will be merged with the formatters of this
     * CodeWriter, and in the case of conflicts, the formatters of the
     * {@code other} will take precedence.
     *
     * <p>Stateful settings of the {@code other} CodeWriter are copied into
     * the <em>current</em> state of this CodeWriter. Only the settings of
     * the top-most state is copied. Other states, and the contents of the
     * top-most state are not copied.
     *
     * <pre>{@code
     * CodeWriter a = new CodeWriter();
     * a.setExpressionStart('#');
     *
     * CodeWriter b = new CodeWriter();
     * b.copySettingsFrom(a);
     *
     * assert(b.getExpressionStart() == '#');
     * }</pre>
     *
     * @param other CodeWriter to copy settings from.
     */
    public void copySettingsFrom(AbstractCodeWriter<T> other) {
        // Copy global settings.
        trailingNewline = other.trailingNewline;
        trimBlankLines = other.trimBlankLines;

        // Copy the current state settings of other into the current state.
        currentState.copyStateFrom(other.currentState);
    }

    /**
     * Provides the default functionality for formatting literal values.
     *
     * <p>This formatter is registered by default as the literal "L" formatter,
     * and is called in the default string "S" formatter before escaping any
     * characters in the string.
     *
     * <ul>
     *     <li>{@code null}: Formatted as an empty string.</li>
     *     <li>Empty {@code Optional}: Formatted as an empty string.</li>
     *     <li>{@code Optional} with value: Formatted as the formatted value in the optional.</li>
     *     <li>Everything else: Formatted as the result of {@link String#valueOf}.</li>
     * </ul>
     *
     * @param value Value to format.
     * @return Returns the formatted value.
     */
    public static String formatLiteral(Object value) {
        if (value == null) {
            return "";
        } else if (value instanceof Optional) {
            Optional<?> optional = (Optional<?>) value;
            return optional.isPresent() ? formatLiteral(optional.get()) : "";
        } else {
            return String.valueOf(value);
        }
    }

    /**
     * Adds a custom formatter expression to the current state of the
     * {@code CodeWriter}.
     *
     * <p>The provided {@code identifier} string must match the following ABNF:
     *
     * <pre>
     * %x21-23    ; ( '!' - '#' )
     * / %x25-2F  ; ( '%' - '/' )
     * / %x3A-60  ; ( ':' - '`' )
     * / %x7B-7E  ; ( '{' - '~' )
     * </pre>
     *
     * @param identifier Formatter identifier to associate with this formatter.
     * @param formatFunction Formatter function that formats the given object as a String.
     *                       The formatter is give the value to format as an object
     *                       (use .toString to access the string contents) and the
     *                       current indentation string of the CodeWriter.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T putFormatter(char identifier, BiFunction<Object, String, String> formatFunction) {
        this.currentState.formatters.get().putFormatter(identifier, formatFunction);
        return (T) this;
    }

    /**
     * Sets the character used to start expressions in the current state when calling
     * {@link #write}, {@link #writeInline}, {@link #openBlock}, etc.
     *
     * <p>By default, {@code $} is used to start expressions (for example
     * {@code $L}. However, some programming languages frequently give
     * syntactic meaning to {@code $}, making this an inconvenient syntactic
     * character for the CodeWriter. In these cases, the character used to
     * start a CodeWriter expression can be changed. Just like {@code $}, the
     * custom start character can be escaped using two subsequent start
     * characters (e.g., {@code $$}).
     *
     * @param expressionStart Character to use to start expressions.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T setExpressionStart(char expressionStart) {
        if (expressionStart == ' ' || expressionStart == '\n') {
            throw new IllegalArgumentException("expressionStart must not be set to " + expressionStart);
        }

        currentState.expressionStart = expressionStart;
        return (T) this;
    }

    /**
     * Get the expression start character of the <em>current</em> state.
     *
     * <p>This value should not be cached and reused across pushed and popped
     * states. This value is "$" by default, but it can be changed using
     * {@link #setExpressionStart(char)}.
     *
     * @return Returns the expression start char of the current state.
     */
    public char getExpressionStart() {
        return currentState.expressionStart;
    }

    /**
     * Gets the contents of the generated code.
     *
     * <p>The result will have an appended newline if the CodeWriter is
     * configured to always append a newline. A newline is only appended
     * in these cases if the result does not already end with a newline.
     *
     * @return Returns the generated code.
     */
    @Override
    public String toString() {
        String result = currentState.toString();

        // Trim excessive blank lines.
        if (trimBlankLines > -1) {
            StringBuilder builder = new StringBuilder(result.length());
            String[] lines = LINES.split(result);
            int blankCount = 0;

            for (String line : lines) {
                if (!StringUtils.isBlank(line)) {
                    builder.append(line).append(currentState.newline);
                    blankCount = 0;
                } else if (blankCount++ < trimBlankLines) {
                    builder.append(line).append(currentState.newline);
                }
            }

            result = builder.toString();
        }

        if (result.isEmpty()) {
            return trailingNewline ? currentState.newline : "";
        }

        // This accounts for cases where the only write on the CodeWriter was
        // an inline write, but the write ended with spaces.
        if (currentState.trimTrailingSpaces) {
            result = StringUtils.stripEnd(result, " ");
        }

        if (trailingNewline) {
            // Add a trailing newline if needed.
            return result.endsWith(currentState.newline) ? result : result + currentState.newline;
        } else if (result.endsWith(currentState.newline)) {
            // Strip the trailing newline if present.
            return result.substring(0, result.length() - currentState.newline.length());
        } else {
            return result;
        }
    }

    /**
     * Copies and pushes the current state to the state stack.
     *
     * <p>This method is used to prepare for a corresponding {@link #popState}
     * operation later. It stores the current state of the CodeWriter into a
     * stack and keeps it active. After pushing, mutations can be made to the
     * state of the CodeWriter without affecting the previous state on the
     * stack. Changes to the state of the CodeWriter can be undone by using
     * {@link #popState()}, which Returns self state to the state
     * it was in before calling {@code pushState}.
     *
     * @return Returns the code writer.
     */
    @SuppressWarnings("unchecked")
    public final T pushState() {
        currentState = new State(currentState);
        states.push(currentState);
        return (T) this;
    }

    /**
     * Copies and pushes the current state to the state stack using a named
     * state that can be intercepted by functions registered with
     * {@link #onSection}.
     *
     * <p>The text written while in this state is buffered and passed to each
     * state interceptor. If no text is written by the section or an
     * interceptor, nothing is changed on the {@code CodeWriter}. This
     * behavior allows for placeholder sections to be added into
     * {@code CodeWriter} generators in order to provide extension points
     * that can be otherwise empty.
     *
     * @param sectionName Name of the section to set on the state.
     * @return Returns the code writer.
     */
    public T pushState(String sectionName) {
        return pushState(CodeSection.forName(sectionName));
    }

    /**
     * Pushes a strongly typed section extension point.
     *
     * <p>Interceptors can be registered to intercept this specific type
     * of CodeSection using a {@link CodeInterceptor} and providing a
     * class for which {@code section} is an instance.
     *
     * @param section The section value to push.
     * @return Returns self.
     * @see #onSection(CodeInterceptor)
     */
    @SuppressWarnings("unchecked")
    public T pushState(CodeSection section) {
        pushState();
        currentState.makeInterceptableCodeSection(section);
        return (T) this;
    }

    /**
     * Creates a section that contains no content used to allow {@link CodeInterceptor}s
     * to inject content at specific locations.
     *
     * @param section The code section to register that can be intercepted by type.
     * @return Returns self.
     */
    public T injectSection(CodeSection section) {
        return pushState(section).popState();
    }

    /**
     * Gets the debug path to the current state so that errors encountered while
     * using CodeWriter are easier to track down.
     *
     * <p>For example, if state "Foo" is entered, and then an unnamed state is
     * entered, the return value is "ROOT/Foo/UNNAMED".
     *
     * @return Returns a "/" separated path to the current state.
     */
    private String getStateDebugPath() {
        StringJoiner result = new StringJoiner("/");
        Iterator<State> iterator = states.descendingIterator();
        while (iterator.hasNext()) {
            result.add(iterator.next().getSectionName());
        }
        return result.toString();
    }

    /**
     * Gets debug information about the current state of the CodeWriter, including
     * the path to the current state as returned by {@link #getStateDebugPath()},
     * and up to the last two lines of text written to the CodeWriter.
     *
     * <p>This debug information is used in most exceptions thrown by CodeWriter to
     * provide additional context when something goes wrong. It can also be used
     * by subclasses and collaborators to aid in debugging codegen issues.
     *
     * @return Returns debug info as a string.
     * @see #getDebugInfo(int)
     */
    public final CodeWriterDebugInfo getDebugInfo() {
        return getDebugInfo(2);
    }

    /**
     * Gets debug information about the current state of the CodeWriter.
     *
     * <p>This method can be overridden in order to add more metadata to the created
     * debug info object.
     *
     * @param numberOfContextLines Include the last N lines in the output. Set to 0 to omit lines.
     * @return Returns debug info as a string.
     * @see #getDebugInfo
     */
    public CodeWriterDebugInfo getDebugInfo(int numberOfContextLines) {
        // Implementer's note: a snapshot of CodeWriter is used rather than just
        // querying data from CodeWriter from within CodeWriterDebugInfo each time
        // toString is called on it. This ensures that debug info is immutable, at
        // least in terms of the state path and the most recent lines written.
        CodeWriterDebugInfo info = new CodeWriterDebugInfo();
        info.putMetadata("path", getStateDebugPath());

        if (numberOfContextLines < 0) {
            throw new IllegalArgumentException("Cannot get fewer than 0 lines");
        } else if (numberOfContextLines > 0) {
            StringBuilder lastLines = new StringBuilder();
            // Get the last N lines of text written.
            String str = toString();
            if (!str.isEmpty()) {
                String[] lines = str.split("\r?\n", 0);
                int startPosition = Math.max(0, lines.length - numberOfContextLines);
                for (int i = startPosition; i < lines.length; i++) {
                    lastLines.append(lines[i]).append("\\n");
                }
            }
            info.putMetadata("near", lastLines.toString());
        }

        return info;
    }

    /**
     * Pushes an anonymous named state that is always passed through the given
     * filter function before being written to the writer.
     *
     * @param filter Function that maps over the entire section when popped.
     * @return Returns the code writer.
     */
    @SuppressWarnings("unchecked")
    public T pushFilteredState(Function<String, String> filter) {
        String sectionName = "__filtered_state_" + states.size() + 1;
        pushState(sectionName);
        onSection(sectionName, content -> writeWithNoFormatting(filter.apply(content.toString())));
        return (T) this;
    }

    /**
     * Pops the current CodeWriter state from the state stack.
     *
     * <p>This method is used to reverse a previous {@link #pushState}
     * operation. It configures the current CodeWriter state to what it was
     * before the last preceding {@code pushState} call.
     *
     * @return Returns self.
     * @throws IllegalStateException if there a no states to pop.
     */
    @SuppressWarnings("unchecked")
    public T popState() {
        if (states.size() == 1) {
            throw new IllegalStateException("Cannot pop CodeWriter state because at the root state");
        }

        State popped = states.pop();
        currentState = states.getFirst();
        CodeSection sectionValue = popped.sectionValue;

        if (sectionValue != null) {
            // Get the contents of the current state as a string so it can be filtered.
            String result = removeTrailingNewline(popped.toString());

            // Don't attempt to intercept anonymous sections.
            if (!(sectionValue instanceof AnonymousCodeSection)) {
                for (CodeInterceptor<CodeSection, T> interceptor : popped.interceptors.peek().get(sectionValue)) {
                    result = interceptSection(popped, interceptor, result);
                }
            }

            if (popped.isInline) {
                // Inline sections need to be written back to the popped state, not the parent state.
                // They also can't use write because other changes to the builder since capturing
                // the result string will alter the result.
                StringBuilder builder = popped.getBuilder();
                builder.setLength(0);
                builder.append(result);
            } else if (!result.isEmpty()) {
                writeWithNoFormatting(result);
            }
        }

        return (T) this;
    }

    // This method exists because inlining in popSection is impossible due to needing to mutate a result variable.
    @SuppressWarnings("unchecked")
    private String interceptSection(State popped, CodeInterceptor<CodeSection, T> interceptor, String previous) {
        return expandSection(new AnonymousCodeSection("__" + popped.getSectionName()), previous, interceptorToCall -> {
            interceptor.write((T) this, previous, popped.sectionValue);
        });
    }

    /**
     * Registers a function that intercepts the contents of a section and
     * the current context map and writes to the {@code CodeWriter} with the
     * updated contents.
     *
     * <p>These section interceptors provide a simple hook system for CodeWriters
     * that add extension points when generating code. The function has the
     * ability to completely ignore the original contents of the section, to
     * prepend text to it, and append text to it. Intercepting functions are
     * expected to have a reference to the {@code CodeWriter} and to mutate it
     * when they are invoked. Each interceptor is invoked in their own
     * isolated pushed/popped states.
     *
     * <p>Interceptors are registered on the current state of the
     * {@code CodeWriter}. When the state to which an interceptor is registered
     * is popped, the interceptor is no longer in scope.
     *
     * <p>The text provided to the intercepting function does not contain
     * a trailing new line. A trailing new line will be injected automatically
     * when the results of intercepting the contents are written to the
     * {@code CodeWriter}. A result is only written if the interceptors write
     * a non-null, non-empty string, allowing for empty placeholders to be
     * added that don't affect the resulting layout of the code.
     *
     * <pre>{@code
     * CodeWriter writer = new CodeWriter();
     *
     * // Prepend text to a section named "foo".
     * writer.onSectionPrepend("foo", () -> writer.write("A"));
     *
     * // Write text to a section, and ensure that the original
     * // text is written too.
     * writer.onSection("foo", text -> {
     *     // Write before the original text.
     *     writer.write("A");
     *     // Write the original text of the section.
     *     writer.writeWithNoFormatting(text);
     *     // Write more text to the section.
     *     writer.write("C");
     * });
     *
     * // Create the section, write to it, then close the section.
     * writer.pushState("foo").write("B").popState();
     *
     * assert(writer.toString().equals("A\nB\nC\n"));
     * }</pre>
     *
     * @param sectionName The name of the section to intercept.
     * @param interceptor The function to intercept with.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T onSection(String sectionName, Consumer<Object> interceptor) {
        currentState.interceptors.get()
                .putInterceptor(CodeInterceptor.forName(sectionName, (w, p) -> interceptor.accept(p)));
        return (T) this;
    }

    /**
     * Intercepts a section of code emitted for a strongly typed {@link CodeSection}.
     *
     * @param interceptor A consumer that takes the writer and strongly typed section.
     * @param <S> The type of section being intercepted.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public <S extends CodeSection> T onSection(CodeInterceptor<S, T> interceptor) {
        currentState.interceptors.get().putInterceptor(interceptor);
        return (T) this;
    }

    /**
     * Disables the automatic appending of newlines in the current state.
     *
     * <p>Methods like {@link #write}, {@link #openBlock}, and {@link #closeBlock}
     * will not automatically append newlines when a state has this flag set.
     *
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T disableNewlines() {
        currentState.disableNewline = true;
        return (T) this;
    }

    /**
     * Enables the automatic appending of newlines in the current state.
     *
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T enableNewlines() {
        currentState.disableNewline = false;
        return (T) this;
    }

    /**
     * Sets the character used to represent newlines in the current state
     * ("\n" is the default).
     *
     * <p>When the provided string is empty (""), then newlines are disabled
     * in the current state. This is exactly equivalent to calling
     * {@link #disableNewlines()}, and does not actually change the newline
     * character of the current state.
     *
     * <p>Setting the newline character to a non-empty string implicitly
     * enables newlines in the current state.
     *
     * @param newline Newline character to use.
     * @return Returns self.
     */
    public final T setNewline(String newline) {
        if (newline.isEmpty()) {
            return disableNewlines();
        } else {
            currentState.newline = newline;
            return enableNewlines();
        }
    }

    /**
     * Sets the character used to represent newlines in the current state
     * ("\n" is the default).
     *
     * <p>This call also enables newlines in the current state by calling
     * {@link #enableNewlines()}.
     *
     * @param newline Newline character to use.
     * @return Returns self.
     */
    public final T setNewline(char newline) {
        return setNewline(String.valueOf(newline));
    }

    /**
     * Gets the character used to represent newlines in the current state.
     *
     * @return Returns the newline string.
     */
    public String getNewline() {
        return currentState.newline;
    }

    /**
     * Sets the text used for indentation (defaults to four spaces).
     *
     * @param indentText Indentation text.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T setIndentText(String indentText) {
        currentState.indent(0, indentText);
        return (T) this;
    }

    /**
     * Gets the text used for indentation (defaults to four spaces).
     *
     * @return Returns the indentation string.
     */
    public final String getIndentText() {
        return currentState.indentText;
    }

    /**
     * Enables the trimming of trailing spaces on a line.
     *
     * @return Returns self.
     */
    public final T trimTrailingSpaces() {
        return trimTrailingSpaces(true);
    }

    /**
     * Configures if trailing spaces on a line are removed.
     *
     * @param trimTrailingSpaces Set to true to trim trailing spaces.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T trimTrailingSpaces(boolean trimTrailingSpaces) {
        currentState.trimTrailingSpaces = trimTrailingSpaces;
        return (T) this;
    }

    /**
     * Returns true if the trailing spaces in the current state are trimmed.
     *
     * @return Returns the trailing spaces setting of the current state.
     */
    public boolean getTrimTrailingSpaces() {
        return currentState.trimTrailingSpaces;
    }

    /**
     * Ensures that no more than one blank line occurs in succession.
     *
     * @return Returns self.
     */
    public final T trimBlankLines() {
        return trimBlankLines(1);
    }

    /**
     * Ensures that no more than the given number of newlines can occur
     * in succession, removing consecutive newlines that exceed the given
     * threshold.
     *
     * @param trimBlankLines Number of allowed consecutive newlines. Set to
     *  -1 to perform no trimming. Set to 0 to allow no blank lines. Set to
     *  1 or more to allow for no more than N consecutive blank lines.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T trimBlankLines(int trimBlankLines) {
        this.trimBlankLines = trimBlankLines;
        return (T) this;
    }

    /**
     * Returns the number of allowed consecutive newlines that are not
     * trimmed by the CodeWriter when written to a string.
     *
     * @return Returns the number of allowed consecutive newlines. -1 means
     *   that no newlines are trimmed. 0 allows no blank lines. 1 or more
     *   allows for no more than N consecutive blank lines.
     */
    public int getTrimBlankLines() {
        return trimBlankLines;
    }

    /**
     * Configures the CodeWriter to always append a newline at the end of
     * the text if one is not already present.
     *
     * <p>This setting is not captured as part of push/popState.
     *
     * @return Returns self.
     */
    public final T insertTrailingNewline() {
        return insertTrailingNewline(true);
    }

    /**
     * Configures the CodeWriter to always append a newline at the end of
     * the text if one is not already present.
     *
     * <p>This setting is not captured as part of push/popState.
     *
     * @param trailingNewline True if a newline is added.
     *
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T insertTrailingNewline(boolean trailingNewline) {
        this.trailingNewline = trailingNewline;
        return (T) this;
    }

    /**
     * Checks if the CodeWriter inserts a trailing newline (if necessary) when
     * converted to a string.
     *
     * @return The newline behavior (true to insert a trailing newline).
     */
    public boolean getInsertTrailingNewline() {
        return trailingNewline;
    }

    /**
     * Sets a prefix to prepend to every line after a new line is added
     * (except for an inserted trailing newline).
     *
     * @param newlinePrefix Newline prefix to use.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T setNewlinePrefix(String newlinePrefix) {
        currentState.newlinePrefix = newlinePrefix;
        return (T) this;
    }

    /**
     * Gets the prefix to prepend to every line after a new line is added
     * (except for an inserted trailing newline).
     *
     * @return Returns the newline prefix string.
     */
    public String getNewlinePrefix() {
        return currentState.newlinePrefix;
    }

    /**
     * Indents all text one level.
     *
     * @return Returns self.
     */
    public final T indent() {
        return indent(1);
    }

    /**
     * Indents all text a specific number of levels.
     *
     * @param levels Number of levels to indent.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T indent(int levels) {
        currentState.indent(levels, null);
        return (T) this;
    }

    /**
     * Gets the indentation level of the current state.
     *
     * @return Returns the indentation level of the current state.
     */
    public int getIndentLevel() {
        return currentState.indentation;
    }

    /**
     * Removes one level of indentation from all lines.
     *
     * @return Returns self.
     */
    public final T dedent() {
        return dedent(1);
    }

    /**
     * Removes a specific number of indentations from all lines.
     *
     * <p>Set to -1 to dedent back to 0 (root).
     *
     * @param levels Number of levels to remove.
     * @return Returns self.
     * @throws IllegalStateException when trying to dedent too far.
     */
    @SuppressWarnings("unchecked")
    public final T dedent(int levels) {
        int adjusted = levels == -1 ? Integer.MIN_VALUE : -1 * levels;
        currentState.indent(adjusted, null);
        return (T) this;
    }

    /**
     * Opens a block of syntax by writing text, a newline, then indenting.
     *
     * <pre>
     * {@code
     * String result = new CodeWriter()
     *         .openBlock("public final class $L {", "Foo")
     *             .openBlock("public void main(String[] args) {")
     *                 .write("System.out.println(args[0]);")
     *             .closeBlock("}")
     *         .closeBlock("}")
     *         .toString();
     * }
     * </pre>
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param args String arguments to use for formatting.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, Object... args) {
        return write(textBeforeNewline, args).indent();
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * <pre>{@code
     * CodeWriter writer = new CodeWriter();
     * writer.openBlock("public final class $L {", "}", "Foo", () -> {
     *     writer.openBlock("public void main(String[] args) {", "}", () -> {
     *         writer.write("System.out.println(args[0]);");
     *     })
     * });
     * }</pre>
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param arg1 First positional argument to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline, Object arg1, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{arg1}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param arg1 First positional argument to substitute into {@code textBeforeNewline}.
     * @param arg2 Second positional argument to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline,
            Object arg1, Object arg2, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{arg1, arg2}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param arg1 First positional argument to substitute into {@code textBeforeNewline}.
     * @param arg2 Second positional argument to substitute into {@code textBeforeNewline}.
     * @param arg3 Third positional argument to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline,
            Object arg1, Object arg2, Object arg3, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{arg1, arg2, arg3}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param arg1 First positional argument to substitute into {@code textBeforeNewline}.
     * @param arg2 Second positional argument to substitute into {@code textBeforeNewline}.
     * @param arg3 Third positional argument to substitute into {@code textBeforeNewline}.
     * @param arg4 Fourth positional argument to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline,
            Object arg1, Object arg2, Object arg3, Object arg4, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{arg1, arg2, arg3, arg4}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param arg1 First positional argument to substitute into {@code textBeforeNewline}.
     * @param arg2 Second positional argument to substitute into {@code textBeforeNewline}.
     * @param arg3 Third positional argument to substitute into {@code textBeforeNewline}.
     * @param arg4 Fourth positional argument to substitute into {@code textBeforeNewline}.
     * @param arg5 Fifth positional argument to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    public final T openBlock(String textBeforeNewline, String textAfterNewline,
            Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Runnable f) {
        return openBlock(textBeforeNewline, textAfterNewline, new Object[]{arg1, arg2, arg3, arg4, arg5}, f);
    }

    /**
     * Opens a block of syntax by writing {@code textBeforeNewline}, a newline, then
     * indenting, then executes the given {@code Runnable}, then closes the block of
     * syntax by writing a newline, dedenting, then writing {@code textAfterNewline}.
     *
     * @param textBeforeNewline Text to write before writing a newline and indenting.
     * @param textAfterNewline Text to write after writing a newline and indenting.
     * @param args Arguments to substitute into {@code textBeforeNewline}.
     * @param f Runnable function to execute inside of the block.
     * @return Returns the {@code CodeWriter}.
     */
    @SuppressWarnings("unchecked")
    public final T openBlock(String textBeforeNewline, String textAfterNewline, Object[] args, Runnable f) {
        write(textBeforeNewline, args).indent();
        f.run();
        closeBlock(textAfterNewline);
        return (T) this;
    }

    /**
     * Closes a block of syntax by writing a newline, dedenting, then writing text.
     *
     * @param textAfterNewline Text to write after writing a newline and dedenting.
     * @param args String arguments to use for formatting.
     * @return Returns the {@code CodeWriter}.
     */
    public final T closeBlock(String textAfterNewline, Object... args) {
        return dedent().write(textAfterNewline, args);
    }

    /**
     * Writes text to the CodeWriter and appends a newline.
     *
     * <p>The provided text does not use any kind of expression formatting.
     *
     * <p>Indentation and the newline prefix is only prepended if the writer's
     * cursor is at the beginning of a newline.
     *
     * @param content Content to write.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T writeWithNoFormatting(Object content) {
        currentState.writeLine(content.toString());
        return (T) this;
    }

    /**
     * Writes inline text to the CodeWriter with no formatting.
     *
     * <p>The provided text does not use any kind of expression formatting.
     * Indentation and the newline prefix is only prepended if the writer's
     * cursor is at the beginning of a newline.
     *
     * @param content Inline content to write.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T writeInlineWithNoFormatting(Object content) {
        currentState.write(content.toString());
        return (T) this;
    }

    /**
     * Creates a formatted string using formatter expressions and variadic
     * arguments.
     *
     * <p>Important: if the formatters that are executed while formatting the
     * given {@code content} string mutate the CodeWriter, it could leave the
     * CodeWriter in an inconsistent state. For example, some CodeWriter
     * implementations manage imports and dependencies automatically based on
     * code that is referenced by formatters. If such an expression is used
     * with this format method but the returned String is never written to the
     * CodeWriter, then the CodeWriter might be mutated to track dependencies
     * that aren't actually necessary.
     *
     * <pre>{@code
     * CodeWriter writer = new CodeWriter();
     * String name = "Person";
     * String formatted = writer.format("Hello, $L", name);
     * assert(formatted.equals("Hello, Person"));
     * }</pre>
     *
     * @param content Content to format.
     * @param args String arguments to use for formatting.
     * @return Returns the formatted string.
     * @see #write
     * @see #putFormatter
     */
    public final String format(Object content, Object... args) {
        StringBuilder result = new StringBuilder();
        CodeFormatter.run(result, this, Objects.requireNonNull(content).toString(), args);
        return result.toString();
    }

    /**
     * A simple helper method that makes it easier to invoke the built-in {@code C}
     * (call) formatter using a {@link Consumer} where {@code T} is the specific type
     * of {@link AbstractCodeWriter}.
     *
     * <p>Instead of having to type this:
     *
     * <pre>{@code
     * writer.write("$C", (Consumer<MyWriter>) (w) -> w.write("Hi"));
     * }</pre>
     *
     * <p>You can write:
     *
     * <pre>{@code
     * writer.write("$C", writer.call(w -> w.write("Hi"));
     * }</pre>
     *
     * @param consumer The consumer to call.
     * @return Returns the consumer as-is, but cast as the appropriate Java type.
     */
    public Consumer<T> call(Consumer<T> consumer) {
        return consumer;
    }

    /**
     * Writes text to the CodeWriter and appends a newline.
     *
     * <p>The provided text is automatically formatted using variadic
     * arguments.
     *
     * <p>Indentation and the newline prefix is only prepended if the writer's
     * cursor is at the beginning of a newline.
     *
     * @param content Content to write.
     * @param args String arguments to use for formatting.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T write(Object content, Object... args) {
        String value = format(content, args);
        currentState.writeLine(value);
        return (T) this;
    }

    /**
     * Writes text to the CodeWriter without appending a newline.
     *
     * <p>The provided text is automatically formatted using variadic
     * arguments.
     *
     * <p>Indentation and the newline prefix is only prepended if the writer's
     * cursor is at the beginning of a newline.
     *
     * <p>If newlines are present in the given string, each of those lines will receive proper indentation.
     *
     * @param content Content to write.
     * @param args String arguments to use for formatting.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T writeInline(Object content, Object... args) {
        String value = format(content, args);
        currentState.write(value);
        return (T) this;
    }

    /**
     * Optionally writes text to the CodeWriter and appends a newline
     * if a value is present.
     *
     * <p>If the provided {@code content} value is {@code null}, nothing is
     * written. If the provided {@code content} value is an empty
     * {@code Optional}, nothing is written. If the result of calling
     * {@code toString} on {@code content} results in an empty string,
     * nothing is written. Finally, if the value is a non-empty string,
     * the content is written to the {@code CodeWriter} at the current
     * level of indentation, and a newline is appended.
     *
     * @param content Content to write if present.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T writeOptional(Object content) {
        if (content == null) {
            return (T) this;
        } else if (content instanceof Optional) {
            return writeOptional(((Optional<?>) content).orElse(null));
        } else {
            String value = content.toString();
            return !value.isEmpty() ? write(value) : (T) this;
        }
    }

    /**
     * Remove the most recent text written to the CodeWriter if and only
     * if the last written text is exactly equal to the given expanded
     * content string.
     *
     * <p>This can be useful, for example, for use cases like removing
     * trailing commas from lists of values.
     *
     * <p>For example, the following will remove ", there." from the
     * end of the CodeWriter:
     *
     * <pre>{@code
     * CodeWriter writer = new CodeWriter();
     * writer.writeInline("Hello, there.");
     * writer.unwrite(", there.");
     * assert(writer.toString().equals("Hello\n"));
     * }</pre>
     *
     * <p>However, the following call to unwrite will do nothing because
     * the last text written to the CodeWriter does not match:
     *
     * <pre>{@code
     * CodeWriter writer = new CodeWriter();
     * writer.writeInline("Hello.");
     * writer.unwrite("there.");
     * assert(writer.toString().equals("Hello.\n"));
     * }</pre>
     *
     * @param content Content to write.
     * @param args String arguments to use for formatting.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T unwrite(Object content, Object... args) {
        String value = format(content, args);
        int currentLength = currentState.builder.length();

        if (currentState.builder.lastIndexOf(value) == currentLength - value.length()) {
            currentState.builder.setLength(currentLength - value.length());
        }

        return (T) this;
    }

    /**
     * Adds a named key-value pair to the context of the current state.
     *
     * <p>These context values can be referenced by named interpolated
     * parameters.
     *
     * @param key Key to add to the context.
     * @param value Value to associate with the key.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T putContext(String key, Object value) {
        currentState.context.get().put(key, value);
        return (T) this;
    }

    /**
     * Adds a map of named key-value pair to the context of the current state.
     *
     * <p>These context values can be referenced by named interpolated
     * parameters.
     *
     * @param mappings Key value pairs to add.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public final T putContext(Map<String, Object> mappings) {
        mappings.forEach(this::putContext);
        return (T) this;
    }

    /**
     * Removes a named key-value pair from the context of the current state.
     *
     * @param key Key to add to remove from the current context.
     * @return Returns self.
     */
    @SuppressWarnings("unchecked")
    public T removeContext(String key) {
        if (currentState.context.peek().containsKey(key)) {
            currentState.context.get().remove(key);
        }
        return (T) this;
    }

    /**
     * Gets a named contextual key-value pair from the current state.
     *
     * @param key Key to retrieve.
     * @return Returns the associated value or null if not present.
     */
    public Object getContext(String key) {
        return currentState.context.peek().get(key);
    }

    /**
     * Gets a named context key-value pair from the current state and
     * casts the value to the given type.
     *
     * @param key Key to retrieve.
     * @param type The type of value expected.
     * @return Returns the associated value or null if not present.
     * @throws ClassCastException if the stored value is not null and does not match {@code type}.
     */
    @SuppressWarnings("unchecked")
    public <C> C getContext(String key, Class<C> type) {
        Object value = getContext(key);
        if (value == null) {
            return null;
        } else if (type.isInstance(value)) {
            return (C) value;
        } else {
            throw new ClassCastException(String.format(
                    "Expected context value '%s' to be an instance of %s, but found %s %s",
                    key, type.getName(), value.getClass().getName(), getDebugInfo()));
        }
    }

    String expandSection(CodeSection section, String previousContent, Consumer<String> consumer) {
        StringBuilder buffer = new StringBuilder();
        pushState(section);
        currentState.makeSectionInline(buffer);
        consumer.accept(previousContent);
        popState();
        return buffer.toString();
    }

    // Used only by CodeFormatter to apply formatters.
    @SuppressWarnings("unchecked")
    String applyFormatter(char identifier, Object value) {
        BiFunction<Object, String, String> f = currentState.formatters.peek().getFormatter(identifier);
        if (f != null) {
            return f.apply(value, getIndentText());
        } else if (identifier == 'C') {
            // The default C formatter is evaluated dynamically to prevent cyclic references.
            CodeSection section = new AnonymousCodeSection("__C_formatter_" + states.size());
            if (value instanceof Runnable) {
                Runnable runnable = (Runnable) value;
                return expandSection(section, "", ignore -> runnable.run());
            } else if (value instanceof Consumer) {
                Consumer<T> consumer = (Consumer<T>) value;
                return expandSection(section, "", ignore -> consumer.accept((T) this));
            } else {
                throw new ClassCastException(String.format(
                        "Expected value for 'C' formatter to be an instance of %s or %s, but found %s %s",
                        Runnable.class.getName(), Consumer.class.getName(),
                        value.getClass().getName(), getDebugInfo()));
            }
        } else {
            // Return null if no formatter was found.
            return null;
        }
    }

    private final class State {
        private final boolean isRoot;
        private String indentText = "    ";
        private String leadingIndentString = "";
        private String newlinePrefix = "";
        private int indentation;
        private boolean trimTrailingSpaces;
        private boolean disableNewline;
        private String newline = "\n";
        private char expressionStart = '$';

        private CodeSection sectionValue;
        private CopyOnWriteRef<Map<String, Object>> context;
        private CopyOnWriteRef<CodeWriterFormatterContainer> formatters;
        private CopyOnWriteRef<CodeInterceptorContainer<T>> interceptors;

        /** This StringBuilder, if null, will only be created lazily when needed. */
        private StringBuilder builder;

        /**
         * Inline states are created when formatting text. They aren't written
         * directly to the CodeWriter, but rather captured as part of the
         * process of expanding a template argument.
         */
        private boolean isInline;

        State() {
            isRoot = true;
            CodeWriterFormatterContainer formatterContainer = new CodeWriterFormatterContainer();
            DEFAULT_FORMATTERS.forEach(formatterContainer::putFormatter);
            this.formatters = CopyOnWriteRef.fromOwned(formatterContainer);
            this.context = CopyOnWriteRef.fromOwned(new HashMap<>());
            this.interceptors = CopyOnWriteRef.fromOwned(new CodeInterceptorContainer<>());
        }

        @SuppressWarnings("CopyConstructorMissesField")
        State(State copy) {
            isRoot = false;
            copyStateFrom(copy);
            this.builder = copy.builder;
        }

        private void copyStateFrom(State copy) {
            this.newline = copy.newline;
            this.expressionStart = copy.expressionStart;
            this.context = copy.context;
            this.indentText = copy.indentText;
            this.leadingIndentString = copy.leadingIndentString;
            this.indentation = copy.indentation;
            this.newlinePrefix = copy.newlinePrefix;
            this.trimTrailingSpaces = copy.trimTrailingSpaces;
            this.disableNewline = copy.disableNewline;
            this.context = CopyOnWriteRef.fromBorrowed(copy.context.peek(), HashMap::new);
            this.formatters = CopyOnWriteRef.fromBorrowed(copy.formatters.peek(), CodeWriterFormatterContainer::new);
            this.interceptors = CopyOnWriteRef.fromBorrowed(copy.interceptors.peek(), CodeInterceptorContainer::new);
        }

        @Override
        public String toString() {
            return builder == null ? "" : builder.toString();
        }

        StringBuilder getBuilder() {
            if (builder == null) {
                builder = new StringBuilder();
            }
            return builder;
        }

        void write(String contents) {
            int position = 0;
            int nextNewline = contents.indexOf(newline);

            while (nextNewline > -1) {
                for (; position < nextNewline; position++) {
                    append(contents.charAt(position));
                }
                writeNewline();
                position += newline.length();
                nextNewline = contents.indexOf(newline, position);
            }

            // Write anything remaining in the string after the last newline.
            for (; position < contents.length(); position++) {
                append(contents.charAt(position));
            }
        }

        private void append(char c) {
            checkIndentationBeforeWriting();
            getBuilder().append(c);
        }

        private void checkIndentationBeforeWriting() {
            if (needsIndentation) {
                getBuilder().append(leadingIndentString).append(newlinePrefix);
                needsIndentation = false;
            }
        }

        private void writeNewline() {
            checkIndentationBeforeWriting();
            // Trim spaces before each newline. This only mutates the builder
            // if space trimming is enabled.
            trimSpaces();
            // Newlines are never split across writes, which could potentially cause
            // indentation logic to mess it up.
            getBuilder().append(newline);
            // The next appended character will get indentation and a
            // leading prefix string.
            needsIndentation = true;
        }

        private void writeLine(String line) {
            write(line);

            if (!disableNewline) {
                writeNewline();
            }
        }

        private void trimSpaces() {
            if (!trimTrailingSpaces) {
                return;
            }

            StringBuilder buffer = getBuilder();
            int toRemove = 0;
            for (int i = buffer.length() - 1; i > 0; i--) {
                if (buffer.charAt(i) == ' ') {
                    toRemove++;
                } else {
                    break;
                }
            }

            if (toRemove > 0) {
                buffer.delete(buffer.length() - toRemove, buffer.length());
            }
        }

        private void indent(int levels, String indentText) {
            // Set to Integer.MIN_VALUE to indent back to root.
            if (levels == Integer.MIN_VALUE) {
                indentation = 0;
            } else if (levels + indentation < 0) {
                throw new IllegalStateException(String.format("Cannot dedent CodeWriter %d levels", levels));
            } else {
                indentation += levels;
            }

            if (indentText != null) {
                this.indentText = indentText;
            }

            leadingIndentString = StringUtils.repeat(this.indentText, indentation);
        }

        private void makeSectionInline(StringBuilder builder) {
            this.isInline = true;
            this.builder = builder;
        }

        private void makeInterceptableCodeSection(CodeSection section) {
            currentState.sectionValue = section;
            // If a section value is specified, then capture this state separately.
            // A separate string builder is given to the state, the indentation
            // level is reset back to the root, and the newline prefix is removed.
            // Indentation and prefixes are added automatically if/when the
            // captured text is written into the parent state.
            currentState.builder = null;
            currentState.newlinePrefix = "";
            dedent(-1);
        }

        private String getSectionName() {
            if (isRoot) {
                return "ROOT";
            } else if (sectionValue == null) {
                return "UNNAMED";
            } else {
                return sectionValue.sectionName();
            }
        }
    }

    String removeTrailingNewline(String value) {
        if (value.endsWith(currentState.newline)) {
            value = value.substring(0, value.length() - currentState.newline.length());
        }
        return value;
    }

    /**
     * An anonymous section that can never be directly intercepted.
     *
     * <p>This type of section is key to avoiding infinite recursion when an overly
     * greedy CodeInterceptor attempts to intercept all CodeSection instances with
     * no filtering by name.
     */
    private static final class AnonymousCodeSection implements CodeSection {
        private final String sectionName;

        private AnonymousCodeSection(String sectionName) {
            this.sectionName = Objects.requireNonNull(sectionName);
        }

        @Override
        public String sectionName() {
            return sectionName;
        }
    }
}
