package com.parsv2r.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The JUnit entry point CI runs.
 *
 * <p>It deliberately holds no assertions of its own. Every check lives in {@link CoreChecks},
 * which has no test-framework dependency and can therefore be compiled and run directly on a
 * desktop JVM while the code is being written. One copy of the checks, two ways to run them.
 */
public class CoreChecksTest {

    @Test
    public void everyCoreCheckPasses() {
        int failures = CoreChecks.runAll();
        assertEquals("core checks failed:\n" + CoreChecks.report(), 0, failures);
    }

    @Test
    public void theSuiteIsNotEmpty() {
        CoreChecks.runAll();
        // A suite that silently stops running is worse than no suite, so the count is asserted.
        if (CoreChecks.passedCount() < 100) {
            throw new AssertionError("only " + CoreChecks.passedCount() + " checks ran");
        }
    }
}
