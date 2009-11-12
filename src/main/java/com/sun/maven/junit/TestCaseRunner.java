package com.sun.maven.junit;

import java.net.URL;
import java.util.List;

/**
 * Remoting interface to run tests.
 *
 * @author Kohsuke Kawaguchi
 */
public interface TestCaseRunner {
    /**
     * Prepares for the test execution.
     *
     * @param classpath
     *      Classpath for running tests.
     */
    void setUp(List<URL> classpath);

    /**
     * Runs a single test case and returns the result.
     *
     * @param fileName
     *      File name of the test case class, relative to the class directory root.
     */
    Result runTestCase(String fileName);

    /**
     * Redirects the stdout/stderr to /dev/null.
     *
     * This method doesn't actually belong here but it's convenient to do this.
     */
    void redirectToDevNull();
}
