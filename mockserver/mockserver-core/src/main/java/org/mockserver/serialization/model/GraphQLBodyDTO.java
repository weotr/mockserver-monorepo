package org.mockserver.serialization.model;

import org.mockserver.model.Body;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.List;

public class GraphQLBodyDTO extends BodyDTO {

    private final String query;
    private final String operationName;
    private final String variablesSchema;
    private final SelectionSetMatchType selectionSetMatchType;
    private final List<String> fields;

    public GraphQLBodyDTO(GraphQLBody graphQLBody) {
        this(graphQLBody, null);
    }

    public GraphQLBodyDTO(GraphQLBody graphQLBody, Boolean not) {
        super(Body.Type.GRAPHQL, not);
        this.query = graphQLBody.getQuery();
        this.operationName = graphQLBody.getOperationName();
        this.variablesSchema = graphQLBody.getVariablesSchema();
        this.selectionSetMatchType = graphQLBody.getSelectionSetMatchType();
        this.fields = graphQLBody.getFields();
        withOptional(graphQLBody.getOptional());
    }

    public String getQuery() {
        return query;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getVariablesSchema() {
        return variablesSchema;
    }

    public SelectionSetMatchType getSelectionSetMatchType() {
        return selectionSetMatchType;
    }

    public List<String> getFields() {
        return fields;
    }

    public GraphQLBody buildObject() {
        GraphQLBody body = new GraphQLBody(getQuery(), getOperationName(), getVariablesSchema());
        if (selectionSetMatchType != null) {
            body.withSelectionSetMatchType(selectionSetMatchType);
        }
        if (fields != null) {
            body.withFields(fields);
        }
        body.withOptional(getOptional());
        return body;
    }
}
