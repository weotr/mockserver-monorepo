package org.mockserver.springtest;

import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MockServerTestExecutionListener extends AbstractTestExecutionListener {

    private static final ConcurrentHashMap<Class<?>, List<Field>> MOCK_SERVER_FIELDS = new ConcurrentHashMap<>();

    @Override
    public void prepareTestInstance(TestContext testContext) throws Exception {
        if (isMockServerTest(testContext)) {
            ClientAndServer clientAndServer = MockServerPropertyCustomizer.getOrCreateClientAndServer();
            injectMockServerClient(testContext.getTestInstance(), clientAndServer);
        }
    }

    static void injectMockServerClient(Object testInstance, MockServerClient clientAndServer) {
        for (Field field : getMockServerFields(testInstance.getClass())) {
            // A field discovered on an enclosing class (see findMockServerFields) belongs to the
            // outer instance, not to a @Nested inner test instance, so it must be set on the
            // enclosing instance that actually declares it - otherwise Field.set throws
            // IllegalArgumentException (issue #2371). Walk out via the synthetic enclosing-instance
            // reference until we reach an instance the field can be assigned to.
            Object target = resolveTarget(testInstance, field);
            if (target == null) {
                // the declaring instance is not reachable from this test instance; nothing to inject here
                continue;
            }
            boolean accessible = field.isAccessible();
            field.setAccessible(true);
            try {
                field.set(target, clientAndServer);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                    "Failed to inject MockServerClient into " + field.getDeclaringClass().getName() + "." + field.getName(), e);
            } finally {
                field.setAccessible(accessible);
            }
        }
    }

    private static Object resolveTarget(Object testInstance, Field field) {
        Class<?> declaringClass = field.getDeclaringClass();
        Object candidate = testInstance;
        while (candidate != null && !declaringClass.isInstance(candidate)) {
            candidate = enclosingInstance(candidate);
        }
        return candidate;
    }

    private static Object enclosingInstance(Object instance) {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (field.isSynthetic() && field.getName().startsWith("this$")) {
                boolean accessible = field.isAccessible();
                field.setAccessible(true);
                try {
                    return field.get(instance);
                } catch (IllegalAccessException e) {
                    return null;
                } finally {
                    field.setAccessible(accessible);
                }
            }
        }
        return null;
    }

    private static List<Field> getMockServerFields(Class<?> testClass) {
        return MOCK_SERVER_FIELDS.computeIfAbsent(testClass, MockServerTestExecutionListener::findMockServerFields);
    }

    private static List<Field> findMockServerFields(Class<?> classToCheck) {
        if (classToCheck == null || Object.class.equals(classToCheck)) {
            return new ArrayList<>();
        }
        List<Field> fields = findMockServerFields(classToCheck.getSuperclass());
        if (fields.isEmpty()) {
            fields = findMockServerFields(classToCheck.getEnclosingClass());
        }
        for (Field field : classToCheck.getDeclaredFields()) {
            if (MockServerClient.class.equals(field.getType())) {
                fields.add(field);
            }
        }
        return fields;
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (isMockServerTest(testContext)) {
            MockServerPropertyCustomizer.getOrCreateClientAndServer().reset();
        }
    }

    private static boolean isMockServerTest(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        while (testClass != null && !Object.class.equals(testClass)) {
            if (AnnotatedElementUtils.findMergedAnnotation(testClass, MockServerTest.class) != null) {
                return true;
            }
            testClass = testClass.getEnclosingClass();
        }
        return false;
    }

}
