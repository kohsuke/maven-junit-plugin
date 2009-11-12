package org.kohsuke.maven.junit;

import junit.framework.TestCase;

/**
 * @author Kohsuke Kawaguchi
 */
public class FailedTest extends TestCase {
    private final Exception failure;

    public FailedTest(Exception failure) {
        this.failure = failure;
    }

    @Override
    protected void runTest() throws Throwable {
        throw failure;
    }
}
