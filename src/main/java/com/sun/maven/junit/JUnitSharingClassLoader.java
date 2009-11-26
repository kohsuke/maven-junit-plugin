package com.sun.maven.junit;

/**
 * Loads JUnit from another class loader, but otherwise delegate everything to the parent.
 *
 * @author Kohsuke Kawaguchi
 */
final class JUnitSharingClassLoader extends ClassLoader {
    private final ClassLoader junitLoader;
    public JUnitSharingClassLoader(ClassLoader parent, ClassLoader junitLoader) {
        super(parent);
        this.junitLoader = junitLoader;
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("junit.") || name.startsWith("org.junit"))
            return junitLoader.loadClass(name);
        return super.loadClass(name, resolve);
    }
}
