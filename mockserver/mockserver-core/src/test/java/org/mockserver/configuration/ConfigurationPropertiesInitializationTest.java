package org.mockserver.configuration;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Regression test for <a href="https://github.com/mock-server/mockserver-monorepo/issues/2338">#2338</a>.
 *
 * <p>{@code ConfigurationProperties} eagerly initialises a static field early in its {@code <clinit>}:
 * <pre>public static final Properties PROPERTIES = readPropertyFile();</pre>
 * {@code readPropertyFile()} logs every loaded property and, for each one, calls
 * {@code isSensitivePropertyName()} which iterates the {@code SENSITIVE_SUBSTRINGS} set. Because Java
 * initialises static fields in textual order, any field used during {@code readPropertyFile()} that is
 * declared <em>after</em> the {@code PROPERTIES} initialiser is still {@code null} when {@code <clinit>}
 * runs. Loading a <em>non-empty</em> property file then throws {@code ExceptionInInitializerError} /
 * {@code NoClassDefFoundError: Could not initialize class ConfigurationProperties}, and the server fails
 * to start (issue #2338 — {@code SENSITIVE_SUBSTRINGS} was declared ~3000 lines after {@code PROPERTIES}).
 *
 * <p>The fault only reproduces on a <em>fresh</em> class initialisation with a populated property file, so
 * this test loads {@code ConfigurationProperties} in an isolated child-first classloader (the class is
 * already initialised in the test JVM and cannot otherwise re-run its static initialiser). The property
 * file is supplied through that classloader's {@code getResourceAsStream} so the test mutates <strong>no
 * global state</strong> (no system properties, no shared {@code ConfigurationProperties}) and is safe under
 * parallel execution. It guards not just this specific field, but the general invariant: <strong>
 * {@code ConfigurationProperties} must initialise cleanly when a property file containing entries
 * (including sensitive ones) is present.</strong>
 */
public class ConfigurationPropertiesInitializationTest {

    private static final String PROPERTY_FILE_NAME = "mockserver.properties";
    // A normal property plus a "sensitive" one (password) so the redaction path in readPropertyFile() —
    // the code that NPEs in #2338 — is exercised during <clinit>. None of these are file-validated, so
    // the test can never crash another test even if isolation ever regressed.
    private static final byte[] PROPERTY_FILE_CONTENT = (
        "mockserver.serverPort=1080\n" +
        "mockserver.maxExpectations=42\n" +
        "mockserver.proxyAuthenticationPassword=super-secret\n"
    ).getBytes(StandardCharsets.UTF_8);

    @Test
    public void shouldInitialiseWhenPropertyFileContainsEntries() throws Exception {
        // Force a fresh <clinit> of ConfigurationProperties in an isolated classloader that also serves
        // the property file. Before the fix this throws ExceptionInInitializerError (NPE on the
        // not-yet-initialised SENSITIVE_SUBSTRINGS); after the fix it initialises and PROPERTIES is loaded.
        ClassLoader isolated = new FreshConfigurationPropertiesClassLoader(getClass().getClassLoader());
        Class<?> freshConfigurationProperties =
            Class.forName("org.mockserver.configuration.ConfigurationProperties", true, isolated);

        Properties loaded = (Properties) freshConfigurationProperties.getField("PROPERTIES").get(null);
        assertThat("ConfigurationProperties.PROPERTIES should be populated from the property file",
            loaded, is(notNullValue()));
        assertThat(loaded.getProperty("mockserver.serverPort"), is("1080"));
        assertThat(loaded.getProperty("mockserver.maxExpectations"), is("42"));
    }

    /**
     * Loads {@code org.mockserver.configuration.ConfigurationProperties} and its nested classes
     * child-first so {@code Class.forName(..., initialize = true, ...)} runs a brand-new {@code <clinit>},
     * and serves {@code mockserver.properties} so the fresh class reads a populated property file without
     * any global state being touched. Every other class/resource is delegated to the parent.
     */
    private static final class FreshConfigurationPropertiesClassLoader extends ClassLoader {

        FreshConfigurationPropertiesClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            // Serve the property file for whatever name ConfigurationProperties.propertyFile() resolves
            // to (the "mockserver.properties" default, or an env/system-property override), so the test
            // works regardless of the environment without ever setting a system property itself.
            if (name != null && name.equals(resolvedPropertyFileName())) {
                return new ByteArrayInputStream(PROPERTY_FILE_CONTENT);
            }
            return super.getResourceAsStream(name);
        }

        private static String resolvedPropertyFileName() {
            String systemProperty = System.getProperty("mockserver.propertyFile");
            if (systemProperty != null && !systemProperty.trim().isEmpty()) {
                return systemProperty;
            }
            String environmentVariable = System.getenv("MOCKSERVER_PROPERTY_FILE");
            if (environmentVariable != null && !environmentVariable.trim().isEmpty()) {
                return environmentVariable;
            }
            return PROPERTY_FILE_NAME;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("org.mockserver.configuration.ConfigurationProperties")
                || name.startsWith("org.mockserver.configuration.ConfigurationProperties$")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (InputStream classBytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (classBytes != null) {
                                byte[] bytes = classBytes.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            }
                        } catch (IOException exception) {
                            throw new ClassNotFoundException(name, exception);
                        }
                    }
                    if (loaded != null) {
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
