package org.mockserver.model;

import java.util.Objects;

/**
 * A conditional (if-then-else) request matcher.
 * <p>
 * Unlike the default AND-only matching (where every field of a single
 * {@link HttpRequest} must match), a conditional matcher evaluates one of two
 * branches depending on whether a guard request definition matches:
 * </p>
 * <pre>
 *   if (ifMatches.matches(request)) {
 *       require thenMatches.matches(request)
 *   } else {
 *       require elseMatches.matches(request)   // when elseMatches is absent the
 *                                              // expectation matches whenever the
 *                                              // guard is false
 *   }
 * </pre>
 * <p>
 * Each branch reuses the existing {@link RequestDefinition} matching machinery,
 * so any request definition (an {@link HttpRequest}, an
 * {@link OpenAPIDefinition}, even a nested {@link ConditionalRequestDefinition})
 * may be used as the {@code if}, {@code then} or {@code else} branch.
 * </p>
 * <p>
 * This construct is entirely additive and opt-in: existing AND-only
 * expectations are unchanged.
 * </p>
 *
 * @author jamesdbloom
 */
public class ConditionalRequestDefinition extends RequestDefinition {

    private int hashCode;
    private RequestDefinition ifMatches;
    private RequestDefinition thenMatches;
    private RequestDefinition elseMatches;

    public static ConditionalRequestDefinition requestIf(RequestDefinition ifMatches) {
        return new ConditionalRequestDefinition().withIf(ifMatches);
    }

    public static ConditionalRequestDefinition requestIf(RequestDefinition ifMatches, RequestDefinition thenMatches) {
        return new ConditionalRequestDefinition().withIf(ifMatches).withThen(thenMatches);
    }

    public static ConditionalRequestDefinition requestIf(RequestDefinition ifMatches, RequestDefinition thenMatches, RequestDefinition elseMatches) {
        return new ConditionalRequestDefinition().withIf(ifMatches).withThen(thenMatches).withElse(elseMatches);
    }

    public RequestDefinition getIf() {
        return ifMatches;
    }

    public ConditionalRequestDefinition withIf(RequestDefinition ifMatches) {
        this.ifMatches = ifMatches;
        this.hashCode = 0;
        return this;
    }

    public RequestDefinition getThen() {
        return thenMatches;
    }

    public ConditionalRequestDefinition withThen(RequestDefinition thenMatches) {
        this.thenMatches = thenMatches;
        this.hashCode = 0;
        return this;
    }

    public RequestDefinition getElse() {
        return elseMatches;
    }

    public ConditionalRequestDefinition withElse(RequestDefinition elseMatches) {
        this.elseMatches = elseMatches;
        this.hashCode = 0;
        return this;
    }

    @Override
    public ConditionalRequestDefinition shallowClone() {
        return (ConditionalRequestDefinition) new ConditionalRequestDefinition()
            .withIf(ifMatches)
            .withThen(thenMatches)
            .withElse(elseMatches)
            .withNot(getNot());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ConditionalRequestDefinition that = (ConditionalRequestDefinition) o;
        return Objects.equals(ifMatches, that.ifMatches) &&
            Objects.equals(thenMatches, that.thenMatches) &&
            Objects.equals(elseMatches, that.elseMatches);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), ifMatches, thenMatches, elseMatches);
        }
        return hashCode;
    }
}
