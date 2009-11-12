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

import hudson.remoting.Channel;
import hudson.remoting.Launcher;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.kohsuke.junit.ParallelTestSuite;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /**
     * Set this to true to ignore a failure during testing. Its use is NOT RECOMMENDED, but quite convenient on
     * occasion.
     *
     * @parameter expression="${maven.test.failure.ignore}"
     */
    protected boolean testFailureIgnore;

    /**
     * Option to specify the forking behavior. True to fork, false to run in process.
     *
     * @parameter expression="${maven.junit.fork}"
     */
    protected boolean fork = false;

    /**
     * Number of concurrent executions. Specify -N means "N times #ofProcessors"
     *
     * @parameter expression="${maven.junit.concurrency}"
     */
    protected int concurrency = 1;

    public void execute() throws MojoExecutionException, MojoFailureException {
        // interpret special values and broken values
        if (concurrency<0)
            concurrency = -Runtime.getRuntime().availableProcessors()*concurrency;
        concurrency = Math.max(concurrency,1);
        
        if (fork)
            executeForked();
        else
            executeLocal();
    }

    public void executeLocal() throws MojoExecutionException {
        try {
            LocalTestCaseRunner runner = createTestCaseRunner();
            runner.setUp(makeClassPath());

            Test all = buildTestSuite(runner, concurrency>1 ? new ParallelTestSuite(concurrency) : new TestSuite());

            // redirect output from the tests since they are captured in XML already
            PrintStream out = System.out;
            PrintStream err = System.err;
            System.setOut(new PrintStream(new NullOutputStream()));
            System.setErr(new PrintStream(new NullOutputStream()));
            try {
                runner.runTests(all,out);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Failed to execute JUnit tests",e);
        }
    }

    protected LocalTestCaseRunner createTestCaseRunner() {
        return new LocalTestCaseRunner(getReportDirectory());
    }

    /**
     * Executes tests in several child JVMs concurrently.
     */
    public void executeForked() throws MojoExecutionException, MojoFailureException {
        try {
            final ExecutorService remoteOps = Executors.newCachedThreadPool();

            /**
             * Per thread workspace.
             */
            class Port {
                Channel channel;
                TestCaseRunner runner;

                Port() throws IOException, InterruptedException {
                    channel = fork(System.out,remoteOps);
                    runner = createTestCaseRunner().copyTo(channel);
                    runner.setUp(makeClassPath());
                    runner.redirectToDevNull();
                }
            }
            // allocated channels
            final Set<Port> ports = Collections.synchronizedSet(new HashSet<Port>());
            final ThreadLocal<Port> port4thread = new ThreadLocal<Port>();

            try {
                ExecutorService testRunners = Executors.newFixedThreadPool(concurrency);

                // schedule executions
                List<Future<Result>> jobs = new ArrayList<Future<Result>>();
                for (final String testClassFile : scanTestClasses().getIncludedFiles()) {
                    jobs.add(testRunners.submit(new Callable<Result>() {
                        public Result call() throws Exception {
                            Port p = port4thread.get();
                            if (p==null) {
                                port4thread.set(p=new Port());
                                ports.add(p);
                            }
                            return p.runner.runTestCase(testClassFile);
                        }
                    }));
                }

                // tally the results
                Result r = Result.ZERO;
                for (Future<Result> f : jobs) {
                    try {
                        r = r.add(f.get());
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                        throw new MojoExecutionException("Failed to run a test",e);
                    }
                }

                String msg = String.format("Tests run: %d,  Errors: %d", r.totalRun, r.failed);

                if(testFailureIgnore || r.failed==0)
                    getLog().info(msg);
                else
                    throw new MojoFailureException(msg);
            } finally {
                try {
                    for (Port p : ports)
                        p.channel.close();
                    remoteOps.shutdownNow();
                } catch (IOException e) {
                    // if this fails, we want the root cause to be displayed, not the close failure
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Failed to execute JUnit tests",e);
        }
    }

    /**
     * Forks a new JVM and pumps its output to the given {@link OutputStream}
     *
     * @param output
     *      The stream will be closed upon the completion of the process.
     * @param executors
     *      Executes the remote requests.
     */
    public Channel fork(OutputStream output, ExecutorService executors) throws IOException {
        // let the child process come connect to this port
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("localhost",0));
        serverSocket.setSoTimeout(10*1000);
        int port = serverSocket.getLocalPort();

        List<String> args = new ArrayList<String>();
        args.add(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath());
        // TODO: system properties
        ClasspathBuilder cb = new ClasspathBuilder();
        cb.addJarOf(Channel.class).addJarOf(TestCase.class);

        args.add("-cp");
        args.add(cb.toString());
        args.add(Launcher.class.getName());
        args.add("-connectTo");
        args.add("localhost:"+ port);

        // fork
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        final Process proc = pb.start();
        proc.getOutputStream().close();

        // pump stream
        final CopyThread t1 = new CopyThread("stream copy thread",proc.getInputStream(),output);
        t1.start();

        // connect to the child process
        Socket s = serverSocket.accept();
        serverSocket.close();

        return new Channel("Channel to child process port:"+port, executors,
                new BufferedInputStream(new SocketInputStream(s)), new BufferedOutputStream(new SocketOutputStream(s))) {
            /**
             * Kill the process when the channel is severed.
             */
            @Override
            protected synchronized void terminate(IOException e) {
                super.terminate(e);
                proc.destroy();
                t1.interrupt();
            }

            @Override
            public synchronized void close() throws IOException {
                super.close();
                // wait for the process to complete
                try {
                    proc.waitFor();
                    t1.join();
                } catch (InterruptedException e) {
                    // process the interrupt later
                    Thread.currentThread().interrupt();
                }
            }
        };
    }


    private File getReportDirectory() {
        File dir = new File(project.getBasedir(), "target/surefire-reports");
        dir.mkdirs();
        return dir;
    }

    private TestSuite buildTestSuite(LocalTestCaseRunner r, TestSuite testSuite) throws MojoExecutionException {
        for (String name : scanTestClasses().getIncludedFiles())
            testSuite.addTest(r.buildTestCase(name));
        return testSuite;
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
    private List<URL> makeClassPath() throws MalformedURLException {
        List<URL> urls = new ArrayList<URL>(classpathElements.size());
        for (String e : classpathElements)
            urls.add(new File(e).toURL());
        return urls;
    }

}
