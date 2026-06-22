package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.file.FileReader;

import java.util.Objects;

import static org.mockserver.model.MediaType.DEFAULT_TEXT_HTTP_CHARACTER_SET;

public class FileBody extends BodyWithContentType<String> {
    private int hashCode;
    private final String filePath;
    private final HttpTemplate.TemplateType templateType;

    public FileBody(String filePath) {
        this(filePath, null);
    }

    public FileBody(String filePath, MediaType contentType) {
        this(filePath, contentType, null);
    }

    public FileBody(String filePath, MediaType contentType, HttpTemplate.TemplateType templateType) {
        super(Type.FILE, contentType);
        this.filePath = filePath;
        this.templateType = templateType;
    }

    public static FileBody file(String filePath) {
        return new FileBody(filePath);
    }

    public static FileBody file(String filePath, MediaType contentType) {
        return new FileBody(filePath, contentType);
    }

    public static FileBody file(String filePath, MediaType contentType, HttpTemplate.TemplateType templateType) {
        return new FileBody(filePath, contentType, templateType);
    }

    public static FileBody file(String filePath, HttpTemplate.TemplateType templateType) {
        return new FileBody(filePath, null, templateType);
    }

    public String getFilePath() {
        return filePath;
    }

    /**
     * When set, the file contents are processed by the named template engine against the request
     * before being returned as the response body. When {@code null} the file is returned verbatim.
     */
    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public String getValue() {
        return filePath;
    }

    @Override
    @JsonIgnore
    public byte[] getRawBytes() {
        try {
            String content = FileReader.readFileFromClassPathOrPath(filePath);
            return content.getBytes(determineCharacterSet(contentType, DEFAULT_TEXT_HTTP_CHARACTER_SET));
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    @Override
    public String toString() {
        try {
            return FileReader.readFileFromClassPathOrPath(filePath);
        } catch (Throwable t) {
            return "";
        }
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
        FileBody fileBody = (FileBody) o;
        return Objects.equals(filePath, fileBody.filePath) &&
            templateType == fileBody.templateType;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), filePath, templateType);
        }
        return hashCode;
    }
}
