package com.sun.maven.junit;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestListener;

import java.io.PrintStream;
import java.io.Serializable;
import java.io.Closeable;
import java.io.IOException;

import hudson.remoting.Channel;

/**
 * Prints a progress report in one letter per test.
 *
 * @author Kohsuke Kawaguchi
 */
public class ProgressReporter implements ProgressListener, Serializable, Closeable {
    private final PrintStream report;

    public ProgressReporter(PrintStream report) {
        this.report = report;
    }

    public synchronized void onStart(String testName) {
        print("Running "+testName);
    }

    public void onEnd(String testName) {
    }

    public synchronized void onError(String testName, Throwable cause) {
        print("");
        report.println("Test failure: "+testName);
        cause.printStackTrace(report);
    }

    public void close() {
        report.println();
    }

    private void print(String msg) {
        report.print(msg);

        // for non-ASCII chars, this is a better approximation of the line length than String.length()
        for (int i=msg.getBytes().length; i<72; i++) {
            report.print(' ');
        }
        report.print('\r');
    }

    /**
     * Send a proxy.
     */
    private Object writeReplace() {
        return Channel.current().export(ProgressListener.class,this);
    }

    private static final long serialVersionUID = 1L;
}
