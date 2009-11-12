package com.sun.maven.junit;

import com.sun.istack.test.ForkOutputStream;
import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestListener;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitTest;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitVersionHelper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * {@link TestListener} that bridges to {@link JUnitResultFormatter}.
 *
 * @author Kohsuke Kawaguchi
 */
public class AntXmlFormatter implements TestListener, Closeable {
    private JUnitResultFormatter antf;
    private JUnitTest antTest; // Ant wants this
    private final Class<? extends JUnitResultFormatter> formatter;
    private long startTime;

    private final File dir;

    /**
     * Stdout, stderr that were replaced. Kept so that we can restore them in {@link #close()}.
     */
    private final PrintStream out,err;

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int id = 0;

    public AntXmlFormatter(Class<? extends JUnitResultFormatter> formatter, File dir) {
        this.formatter = formatter;
        this.dir = dir;

        out = System.out;
        err = System.err;
        System.setOut(new PrintStream(new ForkOutputStream(outBuf,out)));
        System.setErr(new PrintStream(new ForkOutputStream(errBuf,err)));
    }

    public void close() {
        // restore
        System.setOut(out);
        System.setErr(err);
    }

    public void addError(Test test, Throwable t) {
        antf.addError(test,t);
        antTest.setCounts(1,0,1);
    }

    public void addFailure(Test test, AssertionFailedError t) {
        antf.addFailure(test,t);
        antTest.setCounts(1,1,0);
    }

    public void startTest(Test test) {
        assert antf==null;
        try {
            antf = formatter.newInstance();
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }

        String testName = getTestName(test);

        antTest = new JUnitTest(testName);
        antTest.setCounts(1,0,0);
        try {
            antf.setOutput(new FileOutputStream(new File(dir,getResultFileName(testName))));
        } catch (FileNotFoundException e) {
            throw new Error(e);
        }
        antf.startTestSuite(antTest);
        antf.startTest(test);
        outBuf.reset();
        errBuf.reset();
        startTime = System.currentTimeMillis();
    }

    private String getTestName(Test test) {
        String testName = JUnitVersionHelper.getTestCaseName(test);
        if(testName==null)      testName="unknown";

        if(testName.indexOf('.')==-1)
            testName = test.getClass().getName()+"."+testName;

        return testName;
    }

    private String getResultFileName(String testName) {
        // try to use a test name.
        StringBuilder sb = new StringBuilder(testName);
        if(sb.length()>70)
            sb.delete(0,sb.length()-70);   // remove any portion longer than 70 to avoid file names that are too long

        for(int i=0; i<sb.length(); i++ ) {
            char ch = sb.charAt(i);
            if(!inRange(ch,'0','9') && !inRange(ch,'a','z') && !inRange(ch,'A','Z') && ch!='.')
                sb.setCharAt(i,'_');
        }
        sb.append('.');
        sb.append(id++);
        sb.append(".xml");
        return sb.toString();
    }

    private static boolean inRange(char ch, char start, char end) {
        return start<=ch && ch<=end;
    }

    public void endTest(Test test) {
        antf.endTest(test);

        antf.setSystemOutput(outBuf.toString());
        antf.setSystemError(errBuf.toString());
        antTest.setRunTime(System.currentTimeMillis()-startTime);
        antf.endTestSuite(antTest);

        antf = null;
        antTest = null;
    }

}
