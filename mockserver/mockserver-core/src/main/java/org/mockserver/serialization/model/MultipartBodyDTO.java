package org.mockserver.serialization.model;

import org.mockserver.model.MultipartBody;
import org.mockserver.model.Parameters;

/**
 * @author jamesdbloom
 */
public class MultipartBodyDTO extends BodyDTO {

    private final Parameters fields;
    private final Parameters filenames;
    private final Parameters partContentTypes;

    public MultipartBodyDTO(MultipartBody multipartBody) {
        this(multipartBody, null);
    }

    public MultipartBodyDTO(MultipartBody multipartBody, Boolean not) {
        super(multipartBody.getType(), not);
        fields = multipartBody.getFields();
        filenames = multipartBody.getFilenames();
        partContentTypes = multipartBody.getPartContentTypes();
        withOptional(multipartBody.getOptional());
    }

    public Parameters getFields() {
        return fields;
    }

    public Parameters getFilenames() {
        return filenames;
    }

    public Parameters getPartContentTypes() {
        return partContentTypes;
    }

    public MultipartBody buildObject() {
        return (MultipartBody) new MultipartBody(fields, filenames, partContentTypes).withOptional(getOptional());
    }

}
