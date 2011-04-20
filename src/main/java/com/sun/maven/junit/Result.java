package com.sun.maven.junit;

import hudson.remoting.ProxyException;
import junit.framework.Test;
import junit.framework.TestFailure;
import junit.framework.TestResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Equivalent of {@link TestResult} but remoting safe.
 *
 * @author Kohsuke Kawaguchi
 */
public class Result implements Serializable {
    public final int totalRun;
    public final List<Failure> failures = new ArrayList<Failure>();
    public final List<Failure> errors = new ArrayList<Failure>();

    /**
     * Represents an error or a failure.
     *
     * <p>
     * This implements {@link Test} for masquarading as a test to {@link TestResult}.
     */
    public static class Failure implements Serializable, Test {
        public final String name;
        public final Throwable cause;

        public Failure(String name, Throwable cause) {
            this.name = name;
            this.cause = cause;
        }

        public Failure(TestFailure f) {
            this.name = f.failedTest().toString(); // this is what ResultPrinter goes after
            this.cause = new ProxyException(f.thrownException());
        }

        public int countTestCases() {
            return 1;
        }

        public void run(TestResult result) {
            // not supposed to be run
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return name;
        }

        private static final long serialVersionUID = 1L;
    }

    public Result(int totalRun) {
        this.totalRun = totalRun;
    }

    public Result add(Result that) {
        Result r = new Result(this.totalRun + that.totalRun);
        r.failures.addAll(this.failures);
        r.failures.addAll(that.failures);
        r.errors.addAll(this.errors);
        r.errors.addAll(that.errors);
        return r;
    }

    public static Result add(Result... results) {
        Result r = results[0];
        for (int i=1; i<results.length; i++)
            r = r.add(results[i]);
        return r;
    }

    public static Result from(TestResult tr) {
        Result r = new Result(tr.runCount());
        copyTo(tr.errors(), r.errors);
        copyTo(tr.failures(), r.failures);
        return r;
    }

    public static Result fromFailure(Failure f) {
        Result r = new Result(1);
        r.failures.add(f);
        return r;
    }

    public boolean isSuccess() {
        return failures.isEmpty() && errors.isEmpty();
    }
    
    /**
     * Restores the {@link TestResult}.
     */
    public TestResult toTestResult() {
        TestResult r = new TestResult();

        r.startTest(new Test() {// fake to set the count
            public int countTestCases() {
                return totalRun;
            }

            public void run(TestResult result) {
            }
        });
        for (Failure f : failures)
            r.addError(f,f.cause);
        for (Failure f : errors)
            r.addError(f,f.cause);
        return r;
    }

    private static void copyTo(Enumeration<TestFailure> e, List<Failure> dest) {
        while (e.hasMoreElements())
            dest.add(new Failure(e.nextElement()));
    }

    public static final Result ZERO = new Result(0);

    private static final long serialVersionUID = 1L;
}
