package com.atlassian.performance.tools.virtualusers

import com.atlassian.performance.tools.concurrency.api.AbruptExecutorService
import com.atlassian.performance.tools.concurrency.api.finishBy
import com.atlassian.performance.tools.io.api.ensureDirectory
import com.atlassian.performance.tools.jiraactions.api.SeededRandom
import com.atlassian.performance.tools.jiraactions.api.WebJira
import com.atlassian.performance.tools.jiraactions.api.measure.ActionMeter
import com.atlassian.performance.tools.jiraactions.api.measure.output.AppendableActionMetricOutput
import com.atlassian.performance.tools.jiraactions.api.memories.User
import com.atlassian.performance.tools.jiraactions.api.memories.UserMemory
import com.atlassian.performance.tools.jiraactions.api.memories.adaptive.AdaptiveUserMemory
import com.atlassian.performance.tools.jiraactions.api.scenario.Scenario
import com.atlassian.performance.tools.virtualusers.api.VirtualUserOptions
import com.atlassian.performance.tools.virtualusers.api.browsers.Browser
import com.atlassian.performance.tools.virtualusers.api.diagnostics.*
import com.atlassian.performance.tools.virtualusers.api.users.UserGenerator
import com.atlassian.performance.tools.virtualusers.measure.JiraNodeCounter
import com.atlassian.performance.tools.virtualusers.measure.WebJiraNode
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.logging.log4j.CloseableThreadContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.openqa.selenium.WebDriver
import org.openqa.selenium.remote.RemoteWebDriver
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant.now
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [load test](https://en.wikipedia.org/wiki/Load_testing).
 */
internal class LoadTest(
    private val options: VirtualUserOptions
) {
    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val behavior = options.behavior
    private val target = options.target
    private val workspace = Paths.get("test-results")
    private val nodeCounter = JiraNodeCounter()
    private val random = SeededRandom(behavior.seed)
    private val diagnosisPatience = DiagnosisPatience(Duration.ofSeconds(5))
    private val diagnosisLimit = DiagnosisLimit(behavior.diagnosticsLimit)
    private val scenario = behavior.scenario.getConstructor().newInstance() as Scenario
    private val browser = behavior.browser.getConstructor().newInstance() as Browser
    private val userGenerator = options.behavior.userGenerator.getConstructor().newInstance() as UserGenerator

    private val systemUsers = listOf(
        User(
            name = target.userName,
            password = target.password
        )
    )
    private val load = behavior.load

    fun run() {
        val users = generateUsers()
        logger.info("Holding for ${load.hold}.")
        Thread.sleep(load.hold.toMillis())
        workspace.toFile().ensureDirectory()
        setUpJira()
        applyLoad(users)
        val nodesDump = workspace.resolve("nodes.csv")
        nodesDump.toFile().bufferedWriter().use {
            nodeCounter.dump(it)
        }
        logger.debug("Dumped node's counts to $nodesDump")
    }

    private fun generateUsers(): List<User> {
        val deadline = now() + behavior.maxOverhead
        return AbruptExecutorService(
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "user-generation-${runnable.hashCode()}")
            }
        ).use { pool ->
            (1..load.virtualUsers)
                .map { pool.submit(Callable { userGenerator.generateUser(options) }) }
                .map { it.finishBy(deadline, logger) }
        }
    }

    private fun setUpJira() {
        if (behavior.skipSetup) {
            logger.debug("Skipped the setup")
            return
        }
        CloseableThreadContext.push("setup").use {
            browser.start().use { closeableDriver ->
                val (driver, diagnostics) = closeableDriver.getDriver().toDiagnosableDriver()
                val meter = ActionMeter(virtualUser = UUID.randomUUID())
                val jira = WebJira(
                    driver = driver,
                    base = target.webApplication,
                    adminPassword = target.password
                )
                val userMemory = AdaptiveUserMemory(random)
                userMemory.remember(systemUsers)
                val virtualUser = createVirtualUser(jira, meter, userMemory, diagnostics)
                virtualUser.setUpJira()
            }
        }
    }

    private fun applyLoad(
        users: List<User>
    ) {
        val userCount = users.size
        val finish = load.ramp + load.flat
        val loadPool = ThreadPoolExecutor(
            userCount,
            userCount,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactoryBuilder().setNameFormat("virtual-user-%d").setDaemon(true).build()
        )
        logger.info("Segmenting load across $userCount VUs")
        val segments = users.mapIndexed { index, user -> segmentLoad(user, index + 1) }
        logger.info("Load segmented")
        segments.forEach { loadPool.submit { applyLoad(it) } }
        Thread.sleep(finish.toMillis())
        close(segments)
    }

    private fun segmentLoad(
        user: User,
        index: Int
    ): LoadSegment {
        val uuid = UUID.randomUUID()
        return LoadSegment(
            driver = browser.start(),
            output = workspace
                .resolve(uuid.toString())
                .toFile()
                .ensureDirectory()
                .resolve("action-metrics.jpt")
                .bufferedWriter(),
            done = AtomicBoolean(false),
            id = uuid,
            index = index,
            user = user
        )
    }

    private fun applyLoad(
        segment: LoadSegment
    ) {
        CloseableThreadContext.push("applying load #${segment.id}").use {
            val rampUpWait = load.rampInterval.multipliedBy(segment.index.toLong())
            logger.info("Waiting for $rampUpWait")
            Thread.sleep(rampUpWait.toMillis())
            val (driver, diagnostics) = segment.driver.getDriver().toDiagnosableDriver()
            val jira = WebJira(
                driver = driver,
                base = target.webApplication,
                adminPassword = target.password
            )
            val userMemory = AdaptiveUserMemory(random)
            userMemory.remember(
                listOf(segment.user)
            )
            val meter = ActionMeter(
                virtualUser = segment.id,
                output = AppendableActionMetricOutput(segment.output)
            )
            val virtualUser = createVirtualUser(jira, meter, userMemory, diagnostics)
            virtualUser.applyLoad(segment.done)
        }
    }

    private fun createVirtualUser(
        jira: WebJira,
        meter: ActionMeter,
        userMemory: UserMemory,
        diagnostics: Diagnostics
    ): ExploratoryVirtualUser {
        val scenarioAdapter = ScenarioAdapter(scenario)
        val maxOverallLoad = load.maxOverallLoad
        return ExploratoryVirtualUser(
            node = WebJiraNode(jira),
            nodeCounter = nodeCounter,
            actions = scenarioAdapter.getActions(
                jira = jira,
                seededRandom = SeededRandom(random.random.nextLong()),
                meter = meter
            ),
            setUpAction = scenarioAdapter.getSetupAction(
                jira = jira,
                meter = meter
            ),
            logInAction = scenarioAdapter.getLogInAction(
                jira = jira,
                meter = meter,
                userMemory = userMemory
            ),
            maxLoad = maxOverallLoad / load.virtualUsers,
            diagnostics = diagnostics
        )
    }

    private fun close(
        segments: List<LoadSegment>
    ) {
        logger.info("Closing segments")
        val closePool = Executors.newCachedThreadPool { Thread(it, "close-segment") }
        segments
            .map { closePool.submit { it.close() } }
            .forEach { it.get() }
        logger.info("Segments closed")
        closePool.shutdown()
    }

    private fun RemoteWebDriver.toDiagnosableDriver(): DiagnosableDriver {
        return DiagnosableDriver(
            this,
            LimitedDiagnostics(
                ImpatientDiagnostics(
                    WebDriverDiagnostics(this),
                    diagnosisPatience
                ),
                diagnosisLimit
            )
        )
    }

    internal data class DiagnosableDriver(
        val driver: WebDriver,
        val diagnostics: Diagnostics
    )
}
