package org.mockserver.exception;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import javax.net.ssl.SSLException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.handleThrowable;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.swallowThrowable;

public class ExceptionHandlingTest {

    @Test
    public void shouldSwallowException() {
        String originalLogLevel = ConfigurationProperties.logLevel().name();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            ExceptionHandling.mockServerLogger = mock(MockServerLogger.class);

            // when
            swallowThrowable(() -> {
                throw new RuntimeException();
            });

            // then
            verify(ExceptionHandling.mockServerLogger).logEvent(any(LogEntry.class));
        } finally {
            ConfigurationProperties.logLevel(originalLogLevel);
        }
    }

    @Test
    public void shouldOnlyLogExceptions() {
        // given
        ExceptionHandling.mockServerLogger = mock(MockServerLogger.class);

        // when
        swallowThrowable(() -> System.out.println("ignore me"));

        // then
        verify(ExceptionHandling.mockServerLogger, never()).logEvent(any(LogEntry.class));
    }

    @Test
    public void shouldIdentifySslExceptionCauseAsSslOrDecoderFault() {
        // a wrapping throwable whose cause is an SSLException is exactly what
        // connectionClosedException returns false for (~line 123-124)
        Throwable wrapped = new RuntimeException("relay failure", new SSLException("handshake failed"));

        // routes to the WARN branch (helper true) and NOT the ERROR branch (connectionClosed false)
        assertThat(isSslOrDecoderFault(wrapped), is(true));
        assertThat(connectionClosedException(wrapped), is(false));
    }

    @Test
    public void shouldIdentifyDecoderExceptionAsSslOrDecoderFault() {
        Throwable throwable = new DecoderException("could not decode");

        assertThat(isSslOrDecoderFault(throwable), is(true));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldIdentifyNotSslRecordExceptionAsSslOrDecoderFault() {
        Throwable throwable = new NotSslRecordException("not an SSL/TLS record");

        assertThat(isSslOrDecoderFault(throwable), is(true));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldNotIdentifyBenignConnectionCloseAsSslOrDecoderFault() {
        // benign close: not an SSL/decoder fault and not an unexpected exception
        Throwable throwable = new RuntimeException("Connection reset by peer");

        assertThat(isSslOrDecoderFault(throwable), is(false));
        assertThat(connectionClosedException(throwable), is(false));
    }

    @Test
    public void shouldNotIdentifyUnexpectedExceptionAsSslOrDecoderFault() {
        // unexpected exception: stays on the ERROR branch (connectionClosed true), not WARN
        Throwable throwable = new IllegalStateException("something unexpected went wrong");

        assertThat(isSslOrDecoderFault(throwable), is(false));
        assertThat(connectionClosedException(throwable), is(true));
    }

}