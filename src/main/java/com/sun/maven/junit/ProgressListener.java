package com.sun.maven.junit;

import junit.framework.TestListener;

/**
 * Remoting-safe progress listener for JUnit test executions.
 *
 * @author Kohsuke Kawaguchi
 * @see TestListener
 */
public interface ProgressListener {
    void onStart(String testName);
    void onEnd(String testName);
    void onError(String testName, Throwable cause);
}
