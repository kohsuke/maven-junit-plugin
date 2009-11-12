package com.sun.maven.junit;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import junit.framework.Test;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * {@link TestCaseRunner} that runs tests on the current JVM.
 *
 * <p>
 * This object can be also sent to a remote channel to execute tests over there.
 * 
 * @author Kohsuke Kawaguchi
 */
public class LocalTestCaseRunner implements TestCaseRunner, Serializable {
    private final File reportDirectory;

    /**
     * ClassLoader for running tests. Set up later.
     */
    private transient ClassLoader cl;

    private transient PrintStream progress;

    public LocalTestCaseRunner(File reportDirectory) {
        this.reportDirectory = reportDirectory;
    }

    public void setUp(List<URL> classpath) {
        // bootstrap class path + junit
        cl = new URLClassLoader(classpath.toArray(new URL[classpath.size()]),new JUnitSharingClassLoader(null,getClass().getClassLoader()));
        progress = System.out;
    }

    public Result runTestCase(String fileName, ProgressListener listener) {
        return Result.from(runTests(buildTestCase(fileName),listener));
    }

    public void redirectToDevNull() {
        System.setOut(new PrintStream(new NullOutputStream()));
        System.setErr(new PrintStream(new NullOutputStream()));
    }

    public Test buildTestCase(String fileName) {
        String className = toClassName(fileName);
        try {
            Class c = cl.loadClass(className);
            if (!isTest(c))
                return EMPTY;

            try {
                // look for the static suite method. I thought JUnit already does this but I guess it doesn't.
                Method m = c.getDeclaredMethod("suite");
                if (Modifier.isStatic(m.getModifiers())) {
                    try {
                        return (Test)m.invoke(null);
                    } catch (IllegalAccessException e) {
                        return new FailedTest(e);
                    } catch (InvocationTargetException e) {
                        return new FailedTest(e);
                    }
                }
            } catch (NoSuchMethodException e) {
                // fall through
            }
            return new TestSuite(c);
        } catch (ClassNotFoundException e) {
            return new FailedTest(e);
        }
    }

    /**
     * Run tests and send the progress report to the given {@link PrintStream}.
     */
    public TestResult runTests(Test all, ProgressListener listener) {
        TestResult tr = new TestResult();
        AntXmlFormatter formatter = new AntXmlFormatter(XMLJUnitResultFormatter.class, reportDirectory);
        tr.addListener(formatter);
        tr.addListener(new TestListenerAdapter(listener));

        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(cl);
        try {
            all.run(tr);
        } finally {
            t.setContextClassLoader(old);
            formatter.close();
        }

        return tr;
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

    private static final long serialVersionUID = 1L;
}
