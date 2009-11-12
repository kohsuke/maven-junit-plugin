package com.sun.maven.junit;

import junit.framework.Test;
import junit.framework.TestListener;
import junit.framework.TestResult;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Run tests with the given listeners.
 * @author Kohsuke Kawaguchi
 */
public class TestWithListners implements Test {
    private final Test delegate;

    private final List<TestListener> listeners;

    public TestWithListners(Test delegate, List<TestListener> listeners) {
        this.delegate = delegate;
        this.listeners = listeners;
    }

    public TestWithListners(Test delegate, TestListener... listeners) {
        this(delegate, Arrays.asList(listeners));
    }

    public int countTestCases() {
        return delegate.countTestCases();
    }

    public void run(TestResult result) {
        for (TestListener listener : listeners)
            result.addListener(listener);
        try {
            delegate.run(result);
        } finally {
            // close in the reverse order
            for (int i=listeners.size()-1; i>=0; i--) {
                TestListener l = listeners.get(i);
                if (l instanceof Closeable)
                    try {
                        ((Closeable)l).close();
                    } catch (IOException e) {
                        // ignore
                    }
            }
        }
    }
}
