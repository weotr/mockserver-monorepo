package org.mockserver.junit.jupiter;

import org.junit.jupiter.api.extension.*;
import org.junit.platform.commons.support.AnnotationSupport;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MockServerExtension implements ParameterResolver, BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(MockServerExtension.class);
    private static final String CLIENT_KEY = "clientAndServer";
    private static final String OWNER_KEY = "ownerExtension";
    protected static ClientAndServer perTestSuiteClientAndServer;
    protected ClientAndServer customClientAndServer;
    protected ClientAndServer clientAndServer;
    protected boolean perTestSuite;
    protected boolean resetBeforeEach;

    public MockServerExtension() {

    }

    public MockServerExtension(ClientAndServer clientAndServer) {
        this.customClientAndServer = clientAndServer;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return MockServerClient.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        if (clientAndServer == null) {
            ensureStarted(extensionContext);
        }
        return clientAndServer;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        ensureStarted(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // beforeAll has already run ensureStarted, so clientAndServer and the
        // resetBeforeEach flag are deterministically set by the time we get here.
        if (resetBeforeEach && clientAndServer != null && clientAndServer.isRunning()) {
            clientAndServer.reset();
        }
    }

    private void ensureStarted(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        ClientAndServer existing = store.get(CLIENT_KEY, ClientAndServer.class);
        if (existing != null) {
            clientAndServer = existing;
            return;
        }
        List<Integer> ports = new ArrayList<>();
        Optional<MockServerSettings> mockServerSettingsOptional = retrieveAnnotationFromTestClass(context);
        if (mockServerSettingsOptional.isPresent()) {
            MockServerSettings mockServerSettings = mockServerSettingsOptional.get();
            perTestSuite = mockServerSettings.perTestSuite();
            resetBeforeEach = mockServerSettings.resetBeforeEach();
            for (int port : mockServerSettings.ports()) {
                ports.add(port);
            }
        }
        clientAndServer = instantiateClient(ports);
        store.put(CLIENT_KEY, clientAndServer);
        store.put(OWNER_KEY, this);
    }

    ClientAndServer instantiateClient(List<Integer> ports) {
        synchronized (MockServerExtension.class) {
            if (perTestSuite) {
                if (perTestSuiteClientAndServer == null) {
                    perTestSuiteClientAndServer = ClientAndServer.startClientAndServer(ports);
                    Runtime.getRuntime().addShutdownHook(new Scheduler.SchedulerThreadFactory("MockServer Test Extension ShutdownHook").newThread(() -> perTestSuiteClientAndServer.stop()));
                }
                return perTestSuiteClientAndServer;
            } else if (customClientAndServer != null) {
                return customClientAndServer;
            } else {
                return ClientAndServer.startClientAndServer(ports);
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
        if (!perTestSuite && this == store.get(OWNER_KEY) && clientAndServer != null && clientAndServer.isRunning()) {
            clientAndServer.stop();
            store.remove(CLIENT_KEY);
            store.remove(OWNER_KEY);
        }
    }

    private Optional<MockServerSettings> retrieveAnnotationFromTestClass(final ExtensionContext context) {
        ExtensionContext currentContext = context;
        Optional<MockServerSettings> annotation;

        do {
            annotation = AnnotationSupport.findAnnotation(currentContext.getElement(), MockServerSettings.class);
            if (!currentContext.getParent().isPresent()) {
                break;
            }
            currentContext = currentContext.getParent().get();
        } while (!annotation.isPresent() && currentContext != context.getRoot());

        return annotation;
    }
}
