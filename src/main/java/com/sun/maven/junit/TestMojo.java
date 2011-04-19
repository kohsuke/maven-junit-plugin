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
import junit.framework.TestResult;
import junit.framework.TestSuite;
import junit.textui.ResultPrinter;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;
import org.codehaus.plexus.util.StringUtils;
import org.kohsuke.junit.ParallelTestSuite;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Properties;
import java.util.Map.Entry;
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

    /**
     * Attach a debugger to the forked JVM.  If set to "true", the process will suspend and
     * wait for a debugger to attach on port 5005.  If set to some other string, that
     * string will be appended to the argLine, allowing you to configure arbitrary
     * debuggability options (without overwriting the other options specified in the argLine).
     *
     * @parameter expression="${maven.surefire.debug}"
     */
    protected String debugForkedProcess;

    /**
     * Specify this parameter to run individual tests by file name, overriding the <code>includes/excludes</code>
     * parameters.  Each pattern you specify here will be used to create an
     * include pattern formatted like <code>**&#47;${test}.java</code>, so you can just type "-Dtest=MyTest"
     * to run a single test called "foo/MyTest.java".  This parameter will override the TestNG suiteXmlFiles
     * parameter.
     * since 1.7 you can now use "-Dtest=MyTest#method" to execute only one method of the unit tests.
     * Note * is supported too "-Dtest=MyTest#method*" to execute only method starting with method
     *
     * @parameter expression="${test}"
     */
    protected String test = "*Test";

    /**
     * If true, the stdout/stderr from tests will not be copied to the console. (Note that they are
     * always in the test result XML file, regardless of the value of this option.)
     *
     * @parameter expression="${maven.junit.quiet}" default-value="true"
     */
    protected boolean quiet;

    /**
     * Arbitrary JVM options to set on the command line. Used when forking.
     *
     * @parameter expression="${argLine}"
     */
    protected String argLine;

    /**
     * List of System properties to pass to the JUnit tests.
     *
     * @parameter
     */
    protected Properties systemProperties = new Properties();

    /**
     * Set this to 'true' to skip running tests, but still compile them. Its use is NOT RECOMMENDED, but quite
     * convenient on occasion.
     *
     * @parameter expression="${skipTests}"
     */
    protected boolean skipTests;

    /**
     * Base directory where all reports are written to.
     *
     * @parameter expression="${project.build.directory}/surefire-reports"
     */
    private File reportsDirectory;

    /**
     * A list of &lt;exclude> elements specifying the tests (by pattern) that should be excluded in testing. When not
     * specified and when the <code>test</code> parameter is not specified, the default excludes will be
     * <code><br/>
     * &lt;excludes><br/>
     * &nbsp;&lt;exclude>**&#47;*$*&lt;/exclude><br/>
     * &lt;/excludes><br/>
     * </code>
     * (which excludes all inner classes).<br>
     * This parameter is ignored if the TestNG <code>suiteXmlFiles</code> parameter is specified.
     *
     * @parameter
     */
    private List<String> excludes;

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipTests) {
            getLog().info("Tests are skipped.");
            return;
        }

        normalizeParameters();

        if (fork)
            executeForked();
        else
            executeLocal();
    }

    /**
     * interpret special values and broken values
     */
    private void normalizeParameters() {
        if (concurrency<0)
            concurrency = -Runtime.getRuntime().availableProcessors()*concurrency;
        concurrency = Math.max(concurrency,1);

        if ( "true".equals( debugForkedProcess ) )
            debugForkedProcess = "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005";
    }

    public void executeLocal() throws MojoExecutionException {
        PrintStream progress = System.out; // before we start messing around with stdout/stderr, this is where we send the progres report.
        try {
            LocalTestCaseRunner runner = createTestCaseRunner();
            runner.setUp(makeClassPath(), quiet);

            try {
                Test all = buildTestSuite(runner, concurrency>1 ? new ParallelTestSuite(concurrency) : new TestSuite());

                long startTime = System.currentTimeMillis();
                TestResult r = runner.runTests(all, progress);
                printResult(r,System.currentTimeMillis()-startTime);
            } finally {
                runner.tearDown();
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
                    runner.setUp(makeClassPath(), quiet);
                }
            }

            class Task {
                String testCaseFile;
                Future<Result> future;

                Task(String testCaseFile, Future<Result> future) {
                    this.testCaseFile = testCaseFile;
                    this.future = future;
                }
            }

            // allocated channels
            final Set<Port> ports = Collections.synchronizedSet(new HashSet<Port>());
            final ThreadLocal<Port> port4thread = new ThreadLocal<Port>();
            final long startTime = System.currentTimeMillis();
            try {
                ExecutorService testRunners = Executors.newFixedThreadPool(concurrency);

                // schedule executions
                List<Task> jobs = new ArrayList<Task>();
                for (final String testClassFile : scanTestClasses().getIncludedFiles()) {
                    jobs.add(new Task(testClassFile,testRunners.submit(new Callable<Result>() {
                        public Result call() throws Exception {
                            Port p = port4thread.get();
                            if (p==null) {
                                port4thread.set(p=new Port());
                                ports.add(p);
                            }
                            int index = test.indexOf( '#' );
                            String methodName = null;
                            if (index>=0) {
                                methodName = test.substring( index + 1, test.length() );
                                return p.runner.runTestCase(testClassFile+"#"+methodName);
                            }                             
                            return p.runner.runTestCase(testClassFile);                            
                        }
                    })));
                }

                // tally the results
                Result r = Result.ZERO;
                String oldName = Thread.currentThread().getName();
                for (Task f : jobs) {
                    try {
                        Thread.currentThread().setName(oldName+" : waiting for "+f.testCaseFile);
                        r = r.add(f.future.get());
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                        throw new MojoExecutionException("Failed to run a test",e);
                    }
                }
                Thread.currentThread().setName(oldName);

                printResult(r.toTestResult(), System.currentTimeMillis()-startTime);

                if (!r.isSuccess() && !testFailureIgnore) {// fatal failure
                    String msg = String.format("Tests run: %d,  Failures: %d,  Errors: %d", r.totalRun, r.failures.size(), r.errors.size());
                    throw new MojoExecutionException(msg);
                }
            } finally {
                try {
                    for (Port p : ports) {
                        p.runner.tearDown();
                        p.channel.close();
                    }
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

    private void printResult(TestResult r, long totalTime) {
        ResultPrinter printer = new ResultPrinter(System.out);
        try {
            Method m = printer.getClass().getDeclaredMethod("print",TestResult.class, long.class);
            m.setAccessible(true);
            m.invoke(printer,r,totalTime);
        } catch (IllegalAccessException e) {
            throw new Error(e); // impossible
        } catch (InvocationTargetException e) {
            throw new Error(e); // I'm lazy
        } catch (NoSuchMethodException e) {
            throw new Error(e); // impossible
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
        int port = serverSocket.getLocalPort();

        List<String> args = new ArrayList<String>();
        args.add(new File(System.getProperty("java.home"),"bin/java").getAbsolutePath());
        // TODO: system properties
        ClasspathBuilder cb = new ClasspathBuilder();
        cb.addJarOf(Channel.class).addJarOf(TestCase.class);

        addTokenized(args, debugForkedProcess);
        addTokenized(args, argLine);
        for (Entry<Object, Object> e : systemProperties.entrySet())
            args.add("-D"+e.getKey()+'='+e.getValue());
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

    private void addTokenized(List<String> args, String line) {
        if (line == null)   return;

        StringTokenizer tokens = new StringTokenizer(line);
        while (tokens.hasMoreTokens())
            args.add(tokens.nextToken());
    }


    private File getReportDirectory() {
        reportsDirectory.mkdirs();
        return reportsDirectory;
    }

    private TestSuite buildTestSuite(LocalTestCaseRunner r, TestSuite testSuite) throws MojoExecutionException {
        int index = test.indexOf( '#' );
        String methodName = null;
        if (index>=0) {
            methodName = test.substring( index + 1, test.length() );
        }         
        for (String name : scanTestClasses().getIncludedFiles())
            testSuite.addTest(r.buildTestCase(name + (methodName == null ? "" : "#" + methodName)));
        return testSuite;
    }

    private DirectoryScanner scanTestClasses() {
        FileSet fs = new FileSet();
        fs.setDir(getTestOutputDirectory());
        // maybe user use Test#myMethod
        String classNamesPattern = test;
        if (test.contains( "#" )) {
            classNamesPattern = test.substring( 0, test.indexOf( '#' ));
        }
        fs.setIncludes("**/"+classNamesPattern.replace('.','/')+".class");
        if (excludes!=null) {
            for (String exclude : excludes)
                fs.setExcludes(exclude);
        }
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
