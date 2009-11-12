package com.sun.maven.junit;

import junit.framework.Test;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter;

import java.io.File;
import java.io.PrintStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import hudson.remoting.Channel;
import hudson.remoting.Callable;

/**
 * {@link TestCaseRunner} that runs tests on the current JVM.
 *
 * @author Kohsuke Kawaguchi
 */
public class LocalTestCaseRunner implements TestCaseRunner {
    private final File reportDirectory;

    /**
     * ClassLoader for running tests. Set up later.
     */
    private transient ClassLoader cl;

    public LocalTestCaseRunner(File reportDirectory) {
        this.reportDirectory = reportDirectory;
    }

    public void setUp(List<URL> classpath) {
        cl = new URLClassLoader(classpath.toArray(new URL[classpath.size()]),new JUnitSharingClassLoader(ClassLoader.getSystemClassLoader(),getClass().getClassLoader()));
    }

    public Result runTestCase(String fileName) {
        return Result.from(runTests(buildTestCase(fileName),System.out));
    }

    public Test buildTestCase(String fileName) {
        String className = toClassName(fileName);
        try {
            Class c = cl.loadClass(className);
            if (!isTest(c))
                return EMPTY;
            return new TestSuite(c);
        } catch (ClassNotFoundException e) {
            return new FailedTest(e);
        }
    }

    /**
     * Run tests and send the progress report to the given {@link PrintStream}.
     */
    public TestResult runTests(Test all, PrintStream report) {
        all = new TestWithListners(all,
            new AntXmlFormatter2(XMLJUnitResultFormatter.class, reportDirectory)
        );
        return new TestRunner(report).doRun(all);
    }

    protected boolean isTest(Class c) {
        return !Modifier.isAbstract(c.getModifiers());
    }

    /**
     * Converts a file name of a class file to a class name.
     */
    protected String toClassName(String name) {
        name = name.substring(0,name.length()-".class".length());
        return name.replace('/','.').replace('\\','.');
    }

    /**
     * Creates a clone on the given channel and returns a proxy to it.
     */
    public TestCaseRunner copyTo(Channel channel) throws IOException, InterruptedException {
        return channel.call(new Callable<TestCaseRunner, IOException>() {
            public TestCaseRunner call() throws IOException {
                return Channel.current().export(TestCaseRunner.class,LocalTestCaseRunner.this);
            }
        });
    }

    /**
     * No tests.
     */
    private static final Test EMPTY = new TestSuite();
}
