package org.mockserver.codec;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a {@code multipart/form-data} request body into field-level
 * {@link Parameters} maps that can be matched the same way form parameters are.
 * <p>
 * Returns three multi-valued maps keyed by part field name:
 * <ul>
 *     <li>{@link DecodedMultipart#getFields() fields} — part values (text value or file bytes as a UTF-8 string)</li>
 *     <li>{@link DecodedMultipart#getFilenames() filenames} — filenames of file parts</li>
 *     <li>{@link DecodedMultipart#getPartContentTypes() partContentTypes} — part-level content types</li>
 * </ul>
 * Parsing uses Netty's {@link HttpPostMultipartRequestDecoder}; no additional
 * dependency is introduced.
 */
public class MultipartFormDataDecoder {

    /**
     * Decodes the supplied body bytes using the boundary from the supplied
     * {@code Content-Type} header value.
     *
     * @param contentTypeHeader the full request Content-Type header (must contain a boundary)
     * @param body              the raw request body bytes
     * @return the decoded multipart parts, or {@code null} if the body is not multipart/form-data or cannot be parsed
     */
    public DecodedMultipart decode(String contentTypeHeader, byte[] body) {
        if (StringUtils.isBlank(contentTypeHeader) || !contentTypeHeader.toLowerCase().contains("multipart/form-data") || body == null) {
            return null;
        }
        HttpPostMultipartRequestDecoder decoder = null;
        FullHttpRequest nettyRequest = null;
        try {
            nettyRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/",
                Unpooled.wrappedBuffer(body)
            );
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, contentTypeHeader);
            nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);

            decoder = new HttpPostMultipartRequestDecoder(new DefaultHttpDataFactory(false), nettyRequest, StandardCharsets.UTF_8);
            if (!decoder.isMultipart()) {
                return null;
            }

            Map<String, List<String>> fields = new LinkedHashMap<>();
            Map<String, List<String>> filenames = new LinkedHashMap<>();
            Map<String, List<String>> partContentTypes = new LinkedHashMap<>();

            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                if (data instanceof FileUpload) {
                    FileUpload fileUpload = (FileUpload) data;
                    Charset partCharset = fileUpload.getCharset() != null ? fileUpload.getCharset() : StandardCharsets.UTF_8;
                    add(fields, fileUpload.getName(), new String(fileUpload.get(), partCharset));
                    if (fileUpload.getFilename() != null) {
                        add(filenames, fileUpload.getName(), fileUpload.getFilename());
                    }
                    if (fileUpload.getContentType() != null) {
                        add(partContentTypes, fileUpload.getName(), fileUpload.getContentType());
                    }
                } else if (data instanceof Attribute) {
                    Attribute attribute = (Attribute) data;
                    add(fields, attribute.getName(), attribute.getValue());
                }
            }

            return new DecodedMultipart(toParameters(fields), toParameters(filenames), toParameters(partContentTypes));
        } catch (Throwable throwable) {
            return null;
        } finally {
            if (decoder != null) {
                try {
                    decoder.destroy();
                } catch (Throwable ignore) {
                    // best effort cleanup
                }
            }
            if (nettyRequest != null) {
                nettyRequest.release();
            }
        }
    }

    private static void add(Map<String, List<String>> map, String key, String value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static Parameters toParameters(Map<String, List<String>> map) {
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            parameters.add(new Parameter(entry.getKey(), entry.getValue()));
        }
        return new Parameters(parameters);
    }

    /**
     * Immutable holder for the three field-level views of a decoded multipart body.
     */
    public static class DecodedMultipart {
        private final Parameters fields;
        private final Parameters filenames;
        private final Parameters partContentTypes;

        DecodedMultipart(Parameters fields, Parameters filenames, Parameters partContentTypes) {
            this.fields = fields;
            this.filenames = filenames;
            this.partContentTypes = partContentTypes;
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
    }
}
