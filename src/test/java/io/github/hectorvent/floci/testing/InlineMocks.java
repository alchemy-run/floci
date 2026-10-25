package io.github.hectorvent.floci.testing;

import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * Mocks and spies for the types the suite's default subclass mock maker cannot extend: final classes,
 * records and enums. Each one redefines its class in the running JVM, the cost the default avoids, so a
 * record is better built for real than mocked.
 */
public final class InlineMocks {

    private InlineMocks() {
    }

    public static <T> T inlineMock(Class<T> type) {
        return Mockito.mock(type, Mockito.withSettings().mockMaker(MockMakers.INLINE));
    }

    /** {@link #inlineMock(Class)} with a default answer, e.g. {@code RETURNS_SELF} for a builder. */
    public static <T> T inlineMock(Class<T> type, Answer<?> defaultAnswer) {
        return Mockito.mock(type, Mockito.withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(defaultAnswer));
    }

    /** {@link Mockito#spy(Object)} for an instance of a final class. */
    @SuppressWarnings("unchecked")
    public static <T> T inlineSpy(T instance) {
        return (T) Mockito.mock(instance.getClass(), Mockito.withSettings()
                .spiedInstance(instance)
                .defaultAnswer(Mockito.CALLS_REAL_METHODS)
                .mockMaker(MockMakers.INLINE));
    }
}
