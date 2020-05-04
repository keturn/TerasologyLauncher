/*
 * Copyright 2020 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.launcher.game;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jersey.repackaged.com.google.common.base.Throwables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.spf4j.log.Level;
import org.spf4j.test.log.TestLoggers;
import org.spf4j.test.matchers.LogMatchers;
import org.terasology.launcher.SlowTest;
import org.testfx.framework.junit5.ApplicationExtension;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@ExtendWith(ApplicationExtension.class)
public class TestRunGameTask {

    static final int EXIT_CODE_OK = 0;
    static final int EXIT_CODE_ERROR = 1;

    static Callable<Process> completesSuccessfully = runProcess("true");
    static Callable<Process> completesWithError = runProcess("false");
    static Callable<Process> exceptionThrowingStart = () -> {
        throw new OurIOException("GRUMPY \uD83D\uDC7F");
    };
    static Callable<Process> noSuchCommand = runProcess(() -> {
        // If you have a program with this name on your path while running these tests,
        // you have incredible luck.
        return "nope" + new Random()
                .ints(16, 0, 255).mapToObj(
                        i -> Integer.toString(i, Character.MAX_RADIX)
                )
                .collect(Collectors.joining());
    });

    private ExecutorService executor;
    private RunGameTask gameTask;

    @SuppressWarnings("SameParameterValue")
    static ExecutorService singleThreadExecutor(String nameFormat) {
        var builder = new ThreadFactoryBuilder().setNameFormat(nameFormat);
        return Executors.newSingleThreadExecutor(builder.build());
    }

    @BeforeEach
    void setUp() {
        // Would it be plausible to do a @BeforeAll thing that provides a thread pool we don't have
        // to tear down for each test? What kind of assertions would we have to make between tests
        // to ensure it's in a clean state?
        executor = singleThreadExecutor("gameTask-%s");
        gameTask = new RunGameTask(null);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        assertThat(executor.shutdownNow(), empty());
        executor.awaitTermination(100, TimeUnit.MILLISECONDS);
        assertTrue(executor.isTerminated());
        assertTrue(gameTask.isDone());
    }

    @SlowTest
    @DisabledOnOs(OS.WINDOWS)
    public void testGameExitSuccessful() throws InterruptedException, ExecutionException {
        gameTask.starter = completesSuccessfully;

        // we can use TestLogger expectations without Slf4jTestRunner, we just can't
        // depend on their annotations. I think.
        var hasExitMessage = TestLoggers.sys().expect(
                RunGameTask.class.getName(), Level.DEBUG,
                allOf(
                    LogMatchers.hasFormatWithPattern("Game closed with the exit value.*"),
                    LogMatchers.hasArguments(EXIT_CODE_OK)
                )
        );

        executor.submit(gameTask);
        assertEquals(EXIT_CODE_OK, gameTask.get());

        hasExitMessage.assertObservation(100, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testGameExitError() throws InterruptedException {
        gameTask.starter = completesWithError;

        var hasExitMessage = TestLoggers.sys().expect(
                RunGameTask.class.getName(), Level.DEBUG,
                allOf(
                    LogMatchers.hasFormatWithPattern("Game closed with the exit value.*"),
                    LogMatchers.hasArguments(EXIT_CODE_ERROR)
                )
        );

        executor.submit(gameTask);
        try {
            assertEquals(EXIT_CODE_ERROR, gameTask.get());
        } catch (ExecutionException e) {
            // see the stack trace from in-thread
            // can we have a test Extension for this please?
            Throwables.propagate(e.getCause());
        }

        hasExitMessage.assertObservation(100, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testBadStarter() {
        gameTask.starter = exceptionThrowingStart;

        executor.submit(gameTask);
        var thrown = assertThrows(ExecutionException.class, gameTask::get);
        assertThat(thrown.getCause(), instanceOf(OurIOException.class));
    }

    @Test
    public void testExeNotFound() {
        gameTask.starter = noSuchCommand;

        executor.submit(gameTask);
        var thrown = assertThrows(ExecutionException.class, gameTask::get);
        var cause = thrown.getCause();
        assertThat(cause, allOf(
                instanceOf(IOException.class),
                hasToString(containsStringIgnoringCase("no such file"))
       ));
    }

    @Disabled("use to experiment with how processes work")
    @Test
    public void testProcessKiller() throws InterruptedException, ExecutionException {
        gameTask.starter = new SlowTicker(4);

        var result = executor.submit(gameTask);
//        Thread.sleep(2500);
        result.get();
    }

    private static Callable<Process> runProcess(String... command) {
        return new ProcessBuilder(command)::start;
    }

    private static Callable<Process> runProcess(Supplier<String> command) {
        return runProcess(command.get());
    }

    private static class SlowTicker implements Callable<Process> {
        private final int seconds;

        SlowTicker(int seconds) {
            this.seconds = seconds;
        }

        @Override
        public Process call() throws IOException {
            var pb = new ProcessBuilder(
                    "/bin/bash", "-c",
                    String.format("for i in $( seq %s ) ; do echo $i ; sleep 1 ; done", seconds)
            );
            var proc = pb.start();
            LoggerFactory.getLogger(SlowTicker.class).info(" ⏲ Ticker PID {}", proc.pid());
            return proc;
        }
    }

    static class OurIOException extends IOException {
        OurIOException(final String grumpy) {
            super(grumpy);
        }
    }
}
