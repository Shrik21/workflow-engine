package com.orchpilot.workflow.expression;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

/**
 * Read-only SpEL property accessor for maps, so {@code customer.name} works as well as
 * {@code customer['name']}.
 *
 * <p>Workflow variables are maps, and authors write {@code amount > 10000}, not
 * {@code root['amount'] > 10000}. Without an accessor like this, every expression in every workflow would
 * need bracket syntax.
 *
 * <p>Written here rather than using Spring's {@code MapAccessor}, which is deprecated for removal, and
 * because writing it means the engine controls two decisions explicitly:
 *
 * <ul>
 *   <li><b>Writes are refused.</b> An expression evaluates a condition; it must not be able to mutate the
 *       variables it is reading. {@code SimpleEvaluationContext} already blocks assignment, so this is
 *       defence in depth.</li>
 *   <li><b>A missing key is an error, not null.</b> {@code canRead} returns false when the key is absent, so
 *       a misspelled variable produces a diagnosable failure rather than silently evaluating to false. The
 *       decision executor catches it, logs the expression, and treats the branch as unmatched, which means a
 *       typo shows up in the execution log instead of quietly routing every case down the default path.</li>
 * </ul>
 */
public class MapPropertyAccessor implements PropertyAccessor {

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[]{Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map && ((Map<?, ?>) target).containsKey(name);
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        if (!(target instanceof Map)) {
            throw new AccessException("Target is not a map");
        }
        Map<?, ?> map = (Map<?, ?>) target;
        if (!map.containsKey(name)) {
            throw new AccessException("No variable named '" + name + "'");
        }
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue)
            throws AccessException {
        throw new AccessException("Workflow expressions are read-only and cannot assign to '" + name + "'");
    }
}
