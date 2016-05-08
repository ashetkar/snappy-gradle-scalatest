package com.github.maiflai

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.process.internal.JavaExecAction;
import org.gradle.testfixtures.ProjectBuilder
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Test

import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.hasItem
import static org.hamcrest.CoreMatchers.not
import static org.hamcrest.core.CombinableMatcher.both
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertThat

class ScalaTestActionTest {

    private static Project testProject() {
        Project project = ProjectBuilder.builder().build()
        project.plugins.apply(ScalaTestPlugin)
        project
    }

    private static org.gradle.api.tasks.testing.Test testTask() {
        testProject().tasks.test as org.gradle.api.tasks.testing.Test
    }

    private static List<String> commandLine(org.gradle.api.tasks.testing.Test task) {
        JavaExecAction action = ScalaTestAction.makeAction(task)
        action.getCommandLine()
    }

    private static Map<String, Object> environment(org.gradle.api.tasks.testing.Test task) {
        JavaExecAction action = ScalaTestAction.makeAction(task)
        action.getEnvironment()
    }

    @Test
    public void environmentVariableIsCopied() {
        Task test = testTask()
        test.environment.put('a', 'b')
        assertThat(environment(test).get('a') as String, equalTo('b'))
    }

    @Test
    public void colorOutputIsDisabled() {
        Task test = testTask()
        test.getProject().getGradle().startParameter.setColorOutput(false)
        assertThat(commandLine(test), hasItem("-oDW".toString()))
    }

    @Test
    public void colorOutputIsEnabled() {
        Task test = testTask()
        test.getProject().getGradle().startParameter.setColorOutput(true)
        assertThat(commandLine(test), hasItem("-oD".toString()))
    }

    @Test
    public void maxHeapSizeIsAdded() throws Exception {
        Task test = testTask()
        String size = '123m'
        test.maxHeapSize = size
        assertThat(commandLine(test), hasItem("-Xmx$size".toString()))
    }

    @Test
    public void minHeapSizeIsAdded() throws Exception {
        Task test = testTask()
        String size = '123m'
        test.minHeapSize = size
        assertThat(commandLine(test), hasItem("-Xms$size".toString()))
    }

    @Test
    public void jvmArgIsAdded() throws Exception {
        String permSize = '-XX:MaxPermSize=256m'
        Task test = testTask().jvmArgs(permSize)
        assertThat(commandLine(test), hasItem(permSize))
    }

    @Test
    public void sysPropIsAdded() throws Exception {
        Task test = testTask()
        test.systemProperties.put('bob', 'rita')
        assertThat(commandLine(test), hasItem('-Dbob=rita'))
    }

    @Test
    public void parallelDefaultsToProcessorCount() throws Exception {
        Task test = testTask()
        int processors = Runtime.runtime.availableProcessors()
        assertThat(commandLine(test), hasItem("-PS$processors".toString()))
    }

    @Test
    public void parallelSupportsConfiguration() throws Exception {
        Task test = testTask()
        int forks = Runtime.runtime.availableProcessors() + 1
        test.maxParallelForks = forks
        assertThat(commandLine(test), hasItem("-PS$forks".toString()))
    }

    @Test
    public void noTagsAreSpecifiedByDefault() throws Exception {
        Task test = testTask()
        assertThat(commandLine(test), both(not(hasItem('-n'))).and(not(hasItem('-l'))))
    }

    private static Matcher<List<String>> hasOption(String option, String required) {
        return new TypeSafeMatcher<List<String>>() {
            @Override
            protected boolean matchesSafely(List<String> strings) {
                def optionLocations = strings.findIndexValues { it == option }
                def optionValues = optionLocations.grep { locationOfOption ->
                    def optionValue = strings.get((locationOfOption + 1) as Integer)
                    required.equals(optionValue)
                }
                return optionValues.size() == 1
            }

            @Override
            void describeTo(Description description) {
                description.appendText("a list containing $option followed by $required")
            }
        }
    }

    @Test
    public void includesAreAddedAsTags() throws Exception {
        Task test = testTask()
        test.tags.include('bob', 'rita')
        def args = commandLine(test)
        assertThat(args, both(hasOption('-n', 'bob')).and(hasOption('-n', 'rita')))
    }

    @Test
    public void excludesAreAddedAsTags() throws Exception {
        Task test = testTask()
        test.tags.exclude('jane', 'sue')
        def args = commandLine(test)
        assertThat(args, both(hasOption('-l', 'jane')).and(hasOption('-l', 'sue')))
    }

    @Test
    public void filtersAreTranslatedToZ() throws Exception {
        Task test = testTask()
        test.filter.setIncludePatterns('popped', 'weasel')
        def args = commandLine(test)
        assertThat(args, both(hasOption('-z', 'popped')).and(hasOption('-z', 'weasel')))
    }

    private static void checkSuiteTranslation(String message, Closure<Task> task, List<String> suites) {
        Task test = testTask()
        test.configure(task)
        def args = commandLine(test)
        suites.each {
            assertThat(message, args, hasOption('-s', it))
        }
    }

    @Test
    public void suiteIsTranslatedToS() throws Exception {
        checkSuiteTranslation('simple suite', { it.suite 'hello.World' }, ['hello.World'])
        checkSuiteTranslation('multiple calls', { it.suite 'a'; it.suite 'b' }, ['a', 'b'])
    }

    @Test
    public void suitesAreTranslatedToS() throws Exception {
        checkSuiteTranslation('list of suites', { it.suites 'a', 'b' }, ['a', 'b'])
    }

    @Test
    public void distinctSuitesAreRun() throws Exception {
        Task test = testTask()
        test.suites 'a', 'a'
        def args = commandLine(test)
        def callsToS = args.findAll { it.equals('-s') }
        assertThat(callsToS.size(), equalTo(1))
    }

    @Test
    public void configString() throws Exception {
        Task test = testTask()
        test.config 'a', 'b'
        def args = commandLine(test)
        assertThat(args, hasItem('-Da=b'))
    }

    @Test
    public void configNumber() throws Exception {
        Task test = testTask()
        test.config 'a', 1
        def args = commandLine(test)
        assertThat(args, hasItem('-Da=1'))
    }

    @Test
    public void configMap() throws Exception {
        Task test = testTask()
        test.configMap([a:'b', c:1])
        def args = commandLine(test)
        assertThat(args, both(hasItem('-Da=b')).and(hasItem("-Dc=1")))
    }

    @Test
    public void workingDir() throws Exception {
        Task test = testTask()
        def workDir = '/tmp'
        test.workingDir = workDir
        JavaExecAction action = ScalaTestAction.makeAction(test)
        assertEquals(workDir, action.workingDir.path)
    }

    @Test
    public void testResult() throws Exception {
        Task test = testTask()
        def resultFile = '/tmp/result.txt'
        test.testResult resultFile
        def args = commandLine(test)
        assertThat(args, hasOption('-f', resultFile))
    }

    @Test
    public void testOutput() throws Exception {
        Task test = testTask()
        def outputFile = 'testOutput.txt'
        test.testOutput outputFile
        JavaExecAction action = ScalaTestAction.makeAction(test)
        def outStream = action.standardOutput as FileOutputStream
        try {
            outStream.newPrintWriter().withCloseable {
                it.println('testing')
            }
            // check the contents
            new FileInputStream(outputFile).newReader().withCloseable {
                assertEquals('testing', it.readLine())
            }
        } finally {
            new File(outputFile).delete()
        }
    }

    @Test
    public void testError() throws Exception {
        Task test = testTask()
        def errorFile = 'testError.txt'
        test.testError errorFile
        JavaExecAction action = ScalaTestAction.makeAction(test)
        def outStream = action.errorOutput as FileOutputStream
        try {
            outStream.newPrintWriter().withCloseable {
                it.println('testing')
            }
            // check the contents
            new FileInputStream(errorFile).newReader().withCloseable {
                assertEquals('testing', it.readLine())
            }
        } finally {
            new File(errorFile).delete()
        }
    }
}
