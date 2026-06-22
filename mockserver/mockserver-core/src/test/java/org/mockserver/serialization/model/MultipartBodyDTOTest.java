package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.Body;
import org.mockserver.model.MultipartBody;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.Parameter.param;

/**
 * @author jamesdbloom
 */
public class MultipartBodyDTOTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // when
        MultipartBodyDTO multipartBody = new MultipartBodyDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "value")),
            new Parameters().withEntries(param("file", "report.pdf")),
            new Parameters().withEntries(param("file", "application/pdf"))
        ));

        // then
        assertThat(multipartBody.getFields().getEntries(), containsInAnyOrder(new Parameter("file", "value")));
        assertThat(multipartBody.getFilenames().getEntries(), containsInAnyOrder(new Parameter("file", "report.pdf")));
        assertThat(multipartBody.getPartContentTypes().getEntries(), containsInAnyOrder(new Parameter("file", "application/pdf")));
        assertThat(multipartBody.getType(), is(Body.Type.MULTIPART));
    }

    @Test
    public void shouldBuildCorrectObject() {
        // when
        MultipartBody multipartBody = new MultipartBodyDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "value")),
            new Parameters().withEntries(param("file", "report.pdf")),
            new Parameters().withEntries(param("file", "application/pdf"))
        )).buildObject();

        // then
        assertThat(multipartBody.getFields().getEntries(), containsInAnyOrder(new Parameter("file", "value")));
        assertThat(multipartBody.getFilenames().getEntries(), containsInAnyOrder(new Parameter("file", "report.pdf")));
        assertThat(multipartBody.getPartContentTypes().getEntries(), containsInAnyOrder(new Parameter("file", "application/pdf")));
        assertThat(multipartBody.getType(), is(Body.Type.MULTIPART));
    }

    @Test
    public void shouldBuildCorrectObjectWithOptional() {
        // when
        MultipartBody multipartBody = new MultipartBodyDTO((MultipartBody) new MultipartBody(
            new Parameters().withEntries(param("file", "value"))
        ).withOptional(true)).buildObject();

        // then
        assertThat(multipartBody.getFields().getEntries(), containsInAnyOrder(new Parameter("file", "value")));
        assertThat(multipartBody.getType(), is(Body.Type.MULTIPART));
        assertThat(multipartBody.getOptional(), is(true));
    }

    @Test
    public void shouldRoundTripMultipartBodyThroughJson() throws IOException {
        // given
        MultipartBody multipartBody = new MultipartBody(
            new Parameters().withEntries(param("file", "fileContent")),
            new Parameters().withEntries(param("file", "report.pdf")),
            new Parameters().withEntries(param("file", "application/pdf"))
        );

        // when
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new MultipartBodyDTO(multipartBody));
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        // then
        assertThat(deserialized, is(new MultipartBodyDTO(multipartBody)));
        assertThat(deserialized.buildObject(), is(multipartBody));
    }

    @Test
    public void shouldParseRawMultipartJsonIntoMultipartBody() throws IOException {
        // given
        String json = ("{" + NEW_LINE +
            "  \"type\" : \"MULTIPART\"," + NEW_LINE +
            "  \"fields\" : {" + NEW_LINE +
            "    \"file\" : [ \"fileContent\" ]" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"filenames\" : {" + NEW_LINE +
            "    \"file\" : [ \"report.pdf\" ]" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"partContentTypes\" : {" + NEW_LINE +
            "    \"file\" : [ \"application/pdf\" ]" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // when
        BodyDTO bodyDTO = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        // then
        assertThat(bodyDTO, is(new MultipartBodyDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "fileContent")),
            new Parameters().withEntries(param("file", "report.pdf")),
            new Parameters().withEntries(param("file", "application/pdf"))
        ))));
    }

    @Test
    public void shouldParseMultipartJsonWithoutExplicitType() throws IOException {
        // given (type inferred from filenames/partContentTypes keys)
        String json = ("{" + NEW_LINE +
            "  \"fields\" : {" + NEW_LINE +
            "    \"file\" : [ \"fileContent\" ]" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"filenames\" : {" + NEW_LINE +
            "    \"file\" : [ \"report.pdf\" ]" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // when
        BodyDTO bodyDTO = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        // then
        assertThat(bodyDTO, is(new MultipartBodyDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "fileContent")),
            new Parameters().withEntries(param("file", "report.pdf")),
            null
        ))));
    }

    @Test
    public void shouldCreateDTOFromMultipartBodyModel() {
        // when
        BodyDTO bodyDTO = BodyDTO.createDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "value"))
        ));

        // then
        assertThat(bodyDTO, is(new MultipartBodyDTO(new MultipartBody(
            new Parameters().withEntries(param("file", "value"))
        ))));
    }
}
