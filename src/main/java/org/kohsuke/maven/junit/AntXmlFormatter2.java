package org.kohsuke.maven.junit;

import com.sun.istack.test.AntXmlFormatter;

import java.io.Closeable;
import java.io.File;

import org.apache.tools.ant.taskdefs.optional.junit.JUnitResultFormatter;

/**
 * {@link AntXmlFormatter} + {@link Closeable}.
 * 
 * @author Kohsuke Kawaguchi
 */
public class AntXmlFormatter2 extends AntXmlFormatter implements Closeable {
    public AntXmlFormatter2(Class<? extends JUnitResultFormatter> formatter, File dir) {
        super(formatter, dir);
    }
}
