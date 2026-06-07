package org.mockserver.model;

import org.junit.Test;
import org.mockserver.version.Version;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockserver.model.PortBinding.portBinding;

public class PortBindingTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // when
        PortBinding portBinding = new PortBinding();

        // then
        assertThat(portBinding.getPorts(), empty());
    }

    @Test
    public void shouldReturnValuesFromStaticBuilder() {
        // when
        PortBinding portBinding = portBinding(1, 2, 3);

        // then
        assertThat(portBinding.getPorts(), contains(1, 2, 3));
    }

    @Test
    public void shouldReturnValuesSetInSetter() {
        // when
        PortBinding portBinding = new PortBinding().setPorts(Arrays.asList(1, 2, 3));

        // then
        assertThat(portBinding.getPorts(), contains(1, 2, 3));
    }

    @Test
    public void shouldExposeBuildTimeGitHash() {
        // the git hash is captured at build time from the Version class
        assertThat(new PortBinding().getGitHash(), equalTo(Version.getGitHash()));
    }
}