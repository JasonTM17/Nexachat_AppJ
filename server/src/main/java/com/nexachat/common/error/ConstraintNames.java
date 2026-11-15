package com.nexachat.common.error;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Extracts the violated constraint name from a Spring
 * {@code DataIntegrityViolationException} cause chain.
 *
 * <p>Constraint names are schema metadata and safe to log; the exception
 * message itself can embed the offending column values and must never be
 * logged.
 */
public final class ConstraintNames {

    private ConstraintNames() {
    }

    /** Returns the violated constraint name, or {@code null} when the chain has none. */
    public static String of(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return null;
    }
}
