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
     * @param quiet
     */
    void setUp(List<URL> classpath, boolean quiet);

    /**
     * Runs a single test case and returns the result.
     *
     * @param fileName
     *      File name of the test case class, relative to the class directory root.
     */
    Result runTestCase(String fileName);
    
    /**
     * The clean up that pairs with {@link #setUp(List, boolean)}
     */
    void tearDown();
}
