package com.sun.maven.junit;

import junit.framework.TestResult;

import java.io.Serializable;

/**
 * Results of the test execution.
 *
 * @author Kohsuke Kawaguchi
 */
public class Result implements Serializable {
    public final int totalRun;
    public final int failed;

    public Result(int totalRun, int failed) {
        this.totalRun = totalRun;
        this.failed = failed;
    }

    public Result add(Result that) {
        return new Result(this.totalRun+that.totalRun, this.failed+that.failed);
    }

    public static Result add(Result... results) {
        int total=0;
        int failed=0;

        for (Result r : results) {
            total += r.totalRun;
            failed += r.failed;
        }

        return new Result(total,failed);
    }

    public static Result from(TestResult r) {
        return new Result(r.runCount(), r.errorCount()+r.failureCount());
    }

    public static final Result ZERO = new Result(0,0);

    private static final long serialVersionUID = 1L;
}
