package com.sun.maven.junit;

import com.sun.istack.test.AntXmlFormatter;
import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;

import java.io.Closeable;
import java.io.File;

/**
 * {@link AntXmlFormatter} + {@link Closeable}.
 * 
 * @author Kohsuke Kawaguchi
 */
class AntXmlFormatter2 extends AntXmlFormatter implements Closeable {
    AntXmlFormatter2(Class<? extends JUnitResultFormatter> formatter, File dir) {
        super(formatter, dir);
    }
}
