package com.sun.maven.junit;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestListener;

import java.io.PrintStream;

/**
 * Prints a progress report in one letter per test.
 *
 * @author Kohsuke Kawaguchi
 */
public class ProgressReporter implements TestListener {
    private final PrintStream report;
    private int width;

    public ProgressReporter(PrintStream report) {
        this.report = report;
    }

    public void addError(Test test, Throwable t) {
        letter('E');
    }

    public void addFailure(Test test, AssertionFailedError t) {
        letter('F');
    }

    public void endTest(Test test) {
    }

    public void startTest(Test test) {
        letter('.');
    }

    private synchronized void letter(char ch) {
        report.print(ch);
        if (width++ > 72) {
            report.println();
            width= 0;
        }
    }
}
