package org.mockserver.serialization.model;

import org.mockserver.model.ConditionalRequestDefinition;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;

/**
 * DTO for {@link ConditionalRequestDefinition} (if-then-else request matching).
 *
 * @author jamesdbloom
 */
public class ConditionalRequestDefinitionDTO extends RequestDefinitionDTO {

    private RequestDefinitionDTO ifMatches;
    private RequestDefinitionDTO thenMatches;
    private RequestDefinitionDTO elseMatches;

    public ConditionalRequestDefinitionDTO(ConditionalRequestDefinition conditionalRequestDefinition) {
        super(conditionalRequestDefinition != null ? conditionalRequestDefinition.getNot() : null);
        if (conditionalRequestDefinition != null) {
            ifMatches = toDTO(conditionalRequestDefinition.getIf());
            thenMatches = toDTO(conditionalRequestDefinition.getThen());
            elseMatches = toDTO(conditionalRequestDefinition.getElse());
        }
    }

    public ConditionalRequestDefinitionDTO() {
        super(false);
    }

    private static RequestDefinitionDTO toDTO(RequestDefinition requestDefinition) {
        // Branch types are deliberately limited to those the conditionalRequestDefinition JSON schema
        // accepts (httpRequest, openAPIDefinition and a nested conditionalRequestDefinition), so the
        // DTO mapping and the control-plane schema stay in lock step and an expectation built in Java
        // always round-trips over the JSON API. BinaryRequestDefinition/DnsRequestDefinition are not
        // supported as conditional branches.
        if (requestDefinition instanceof HttpRequest) {
            return new HttpRequestDTO((HttpRequest) requestDefinition);
        } else if (requestDefinition instanceof OpenAPIDefinition) {
            return new OpenAPIDefinitionDTO((OpenAPIDefinition) requestDefinition);
        } else if (requestDefinition instanceof ConditionalRequestDefinition) {
            return new ConditionalRequestDefinitionDTO((ConditionalRequestDefinition) requestDefinition);
        } else if (requestDefinition != null) {
            throw new IllegalArgumentException("Unsupported conditional request branch type: "
                + requestDefinition.getClass().getSimpleName()
                + " (only httpRequest, openAPIDefinition and a nested conditional are supported as if/then/else branches)");
        } else {
            return null;
        }
    }

    public ConditionalRequestDefinition buildObject() {
        return (ConditionalRequestDefinition) new ConditionalRequestDefinition()
            .withIf(ifMatches != null ? ifMatches.buildObject() : null)
            .withThen(thenMatches != null ? thenMatches.buildObject() : null)
            .withElse(elseMatches != null ? elseMatches.buildObject() : null)
            .withNot(getNot());
    }

    public RequestDefinitionDTO getIf() {
        return ifMatches;
    }

    public ConditionalRequestDefinitionDTO setIf(RequestDefinitionDTO ifMatches) {
        this.ifMatches = ifMatches;
        return this;
    }

    public RequestDefinitionDTO getThen() {
        return thenMatches;
    }

    public ConditionalRequestDefinitionDTO setThen(RequestDefinitionDTO thenMatches) {
        this.thenMatches = thenMatches;
        return this;
    }

    public RequestDefinitionDTO getElse() {
        return elseMatches;
    }

    public ConditionalRequestDefinitionDTO setElse(RequestDefinitionDTO elseMatches) {
        this.elseMatches = elseMatches;
        return this;
    }
}
