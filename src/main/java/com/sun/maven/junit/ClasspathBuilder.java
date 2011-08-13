package com.sun.maven.junit;

import hudson.remoting.Which;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Used to build up an argument in the classpath format.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.300
 */
public class ClasspathBuilder {
    private final List<String> args = new ArrayList<String>();

    /**
     * Adds a single directory or a jar file.
     */
    public ClasspathBuilder add(File f) {
        return add(f.getAbsolutePath());
    }

    public ClasspathBuilder addJarOf(Class<?> c) throws IOException {
        return add(Which.jarFile(c));
    }

    /**
     * Adds a single directory or a jar file.
     */
    public ClasspathBuilder add(String path) {
        args.add(path);
        return this;
    }

    /**
     * Returns the string representation of the classpath.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        boolean first=true;
        for (String s : args) {
            if(first)   first=false;
            else        buf.append(File.pathSeparatorChar);
            buf.append(s);
        }
        return buf.toString();
    }
}
