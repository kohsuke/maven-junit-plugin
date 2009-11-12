package com.sun.maven.junit;

import junit.framework.TestListener;
import junit.framework.Test;
import junit.framework.AssertionFailedError;
import hudson.remoting.ProxyException;

/**
 * Use {@link ProgressListener} as a {@link TestListener}.
 *
 * @author Kohsuke Kawaguchi
 */
class TestListenerAdapter implements TestListener {
    private ProgressListener progressListener;

    TestListenerAdapter(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public void addError(Test test, Throwable t) {
        progressListener.onError(test.toString(), new ProxyException(t));
    }

    public void addFailure(Test test, AssertionFailedError t) {
        progressListener.onError(test.toString(), new ProxyException(t));
    }

    public void endTest(Test test) {
        progressListener.onEnd(test.toString());
    }

    public void startTest(Test test) {
        progressListener.onStart(test.toString());
    }
}
