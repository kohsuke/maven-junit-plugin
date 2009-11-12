package com.sun.maven.junit;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import junit.framework.Test;
import junit.framework.TestSuite;
import junit.framework.TestCase;
import junit.textui.TestRunner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter;
import org.apache.tools.ant.types.FileSet;
import org.apache.commons.io.output.NullOutputStream;

import java.io.File;
import java.io.PrintStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import hudson.remoting.Which;
import hudson.remoting.Channel;
import hudson.remoting.Launcher;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;

/**
 * Runs tests
 *
 * @goal test
 * @requiresDependencyResolution test
 * @goal test
 * @phase test
 */
public class TestMojo extends AbstractMojo
{
    /**
     * @parameter expression="${project}"
     * @required
     */
    protected MavenProject project;

    /**
     * The classpath elements of the project being tested.
     *
     * @parameter expression="${project.testClasspathElements}"
     * @required
     * @readonly
     */
    protected List<String> classpathElements;

    public void execute() throws MojoExecutionException {
        Test all = buildTestSuite();

        // redirect output from the tests since they are captured in XML already
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(new PrintStream(new NullOutputStream()));
        System.setErr(new PrintStream(new NullOutputStream()));
        try {
            runTests(all,out);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    public Channel forkAndRun(OutputStream stdout, OutputStream stderr) throws IOException {
        // let the child process come connect to this port
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost",0));
        serverSocket.setSoTimeout(10*1000);

        List<String> args = new ArrayList<String>();
        args.add(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath());
        // TODO: system properties
        ClasspathBuilder cb = new ClasspathBuilder();
        cb.addJarOf(Channel.class).addJarOf(TestCase.class);

        args.add("-cp");
        args.add(cb.toString());
        args.add(Launcher.class.getName());
        args.add("-connectTo");
        args.add("localhost:"+serverSocket.getLocalPort());

        // fork
        ProcessBuilder pb = new ProcessBuilder(args);
        final Process proc = pb.start();

        Socket s = serverSocket.accept();
        serverSocket.close();

        return new Channel("Channel to child process", executors,
                new BufferedInputStream(new SocketInputStream(s)), new BufferedOutputStream(new SocketOutputStream(s))) {
            /**
             * Kill the process when the channel is severed.
             */
            @Override
            protected synchronized void terminate(IOException e) {
                super.terminate(e);
                proc.destroy();
            }

            @Override
            public synchronized void close() throws IOException {
                super.close();
                // wait for the process to complete
                try {
                    proc.waitFor();
                } catch (InterruptedException e) {
                    // process the interrupt later
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /**
     * Run tests and send the progress report to the given {@link PrintStream}.
     */
    private void runTests(Test all, PrintStream report) {
        all = new TestWithListners(all,
            new AntXmlFormatter2(XMLJUnitResultFormatter.class, getReportDirectory())
        );
        new TestRunner(report).doRun(all);
    }

    private File getReportDirectory() {
        File dir = new File(project.getBasedir(), "target/surefire-reports");
        dir.mkdirs();
        return dir;
    }

    private TestSuite buildTestSuite() throws MojoExecutionException {
        TestSuite ts = new TestSuite();

        ClassLoader cl = makeClassLoader();

        for (String name : scanTestClasses().getIncludedFiles()) {
            String className = toClassName(name);
            try {
                Class c = cl.loadClass(className);
                if (!isTest(c))
                    continue;
                ts.addTest(new TestSuite(c));
            } catch (ClassNotFoundException e) {
                ts.addTest(new FailedTest(e));
            }
        }
        return ts;
    }

    private DirectoryScanner scanTestClasses() {
        FileSet fs = new FileSet();
        fs.setDir(getTestOutputDirectory());
        fs.setIncludes("**/*Test.class");
        return fs.getDirectoryScanner(new Project());
    }

    private File getTestOutputDirectory() {
        return new File(project.getBuild().getTestOutputDirectory());
    }

    /**
     * Creates a classloader for loading tests.
     *
     * <p>
     * We need to be able to see the same JUnit classes between this code and the test code,
     * but everything else should be isolated.
     */
    private ClassLoader makeClassLoader() throws MojoExecutionException {
        try {
            URL[] urls = new URL[classpathElements.size()];
            for (int i=0; i<urls.length; i++)
                urls[i] = new File(classpathElements.get(i)).toURL();
            return new URLClassLoader(urls,new JUnitSharingClassLoader(ClassLoader.getSystemClassLoader(),getClass().getClassLoader()));
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Failed to create a test classloader",e);
        }
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

    private final ExecutorService executors = Executors.newCachedThreadPool();
}
