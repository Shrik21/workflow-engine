package com.orchpilot.workflow.expression;

import com.orchpilot.workflow.exception.ExpressionEvaluationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safe SpEL evaluator.
 *
 * <p>Two things make this safe enough to run author-supplied text:
 *
 * <ol>
 *   <li>{@link SimpleEvaluationContext} with property accessors only. Unlike
 *       {@code StandardEvaluationContext} it refuses type references ({@code T(java.lang.Runtime)}),
 *       constructor calls ({@code new java.io.File(...)}), bean references ({@code @beanName}) and
 *       assignment. Without this, a decision expression would be arbitrary code execution.</li>
 *   <li>A static screen that rejects those constructs at publish time with a readable message,
 *       instead of letting them parse and fail obscurely during a 3 a.m. execution. The screen runs
 *       against a copy with string literals removed, so {@code status == 'new order'} is not
 *       mistaken for a constructor call.</li>
 * </ol>
 *
 * <p>Parsed expressions are cached. The cache is bounded and cleared wholesale when full: workflow
 * definitions are finite and republished rarely, so an LRU would add contention for no benefit.
 *
 * <p>Thread-safe.
 */
@Component
public class SpelExpressionEvaluator implements ExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SpelExpressionEvaluator.class);

    /** Constructs rejected outright, checked against literal-stripped text. */
    private static final String[] FORBIDDEN_FRAGMENTS = {"T(", "new ", "@", "#", ".class", "getClass"};

    private static final int MAX_CACHE_ENTRIES = 2_000;
    private static final int MAX_EXPRESSION_LENGTH = 4_000;

    /** Stateless and thread-safe, so one instance serves every evaluation. */
    private static final MapPropertyAccessor MAP_ACCESSOR = new MapPropertyAccessor();

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public boolean evaluateBoolean(String expression, Map<String, Object> root) {
        Object value = evaluate(expression, root);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new ExpressionEvaluationException(expression,
                "expected a boolean result but got " + value.getClass().getSimpleName());
    }

    @Override
    public Object evaluate(String expression, Map<String, Object> root) {
        Expression parsed = parse(expression);
        EvaluationContext context = newContext(root);
        try {
            return parsed.getValue(context);
        } catch (RuntimeException ex) {
            throw new ExpressionEvaluationException(expression, rootMessage(ex), ex);
        }
    }

    @Override
    public void validate(String expression) {
        parse(expression);
    }

    private Expression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionEvaluationException(String.valueOf(expression), "expression is blank");
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new ExpressionEvaluationException("<" + expression.length() + " chars>",
                    "expression exceeds " + MAX_EXPRESSION_LENGTH + " characters");
        }
        Expression cached = cache.get(expression);
        if (cached != null) {
            return cached;
        }
        screen(expression);
        Expression parsed;
        try {
            parsed = parser.parseExpression(expression);
        } catch (RuntimeException ex) {
            throw new ExpressionEvaluationException(expression, rootMessage(ex), ex);
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            log.info("Expression cache reached {} entries; clearing", MAX_CACHE_ENTRIES);
            cache.clear();
        }
        cache.put(expression, parsed);
        return parsed;
    }

    /**
     * Rejects constructs the restricted evaluation context would refuse anyway, so the failure is
     * reported at publish time with an explanation rather than at execution time as a SpEL internal
     * error.
     */
    private void screen(String expression) {
        String withoutLiterals = stripStringLiterals(expression);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (withoutLiterals.contains(fragment)) {
                throw new ExpressionEvaluationException(expression,
                        "'" + fragment.trim() + "' is not permitted. Workflow expressions may only read "
                                + "variables and use operators, for example: amount > 10000 or "
                                + "status == 'APPROVED'");
            }
        }
    }

    private static String stripStringLiterals(String expression) {
        StringBuilder out = new StringBuilder(expression.length());
        boolean inLiteral = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                // '' inside a literal is an escaped quote and does not end it
                if (inLiteral && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inLiteral = !inLiteral;
                continue;
            }
            if (!inLiteral) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static EvaluationContext newContext(Map<String, Object> root) {
        return SimpleEvaluationContext
                .forPropertyAccessors(MAP_ACCESSOR)
                .withRootObject(root == null ? Map.of() : root)
                .build();
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }
}
