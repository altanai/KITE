package org.webrtc.kite.tests;

import io.cosmosoftware.kite.entities.Stage;
import io.cosmosoftware.kite.exception.KiteTestException;
import io.cosmosoftware.kite.instrumentation.NetworkInstrumentation;
import io.cosmosoftware.kite.instrumentation.Scenario;
import io.cosmosoftware.kite.manager.RoomManager;
import io.cosmosoftware.kite.report.*;
import io.cosmosoftware.kite.steps.StepPhase;
import io.cosmosoftware.kite.steps.StepSynchronizer;
import io.cosmosoftware.kite.steps.TestStep;
import io.cosmosoftware.kite.util.ReportUtils;
import io.cosmosoftware.kite.util.TestUtils;
import io.cosmosoftware.kite.util.WebDriverUtils;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.webrtc.kite.config.client.Client;
import org.webrtc.kite.config.test.Tuple;
import org.webrtc.kite.exception.KiteGridException;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.beans.Transient;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.cosmosoftware.kite.util.ReportUtils.getStackTrace;
import static io.cosmosoftware.kite.util.ReportUtils.timestamp;
import static io.cosmosoftware.kite.util.TestUtils.processTestStep;
import static org.webrtc.kite.Utils.populateInfoFromNavigator;

public abstract class KiteBaseTest extends ArrayList<TestRunner> implements StepSynchronizer {

  protected final LinkedHashMap<StepPhase, AllureTestReport> reports = new LinkedHashMap<>();
  protected final ArrayList<Scenario> scenarioArrayList = new ArrayList<>();
  protected final Map<WebDriver, Map<String, Object>> sessionData = new HashMap<WebDriver, Map<String, Object>>();
  protected String configFilePath;
  protected String description;
  protected JsonObject getStatsConfig = null;
  protected boolean isLoadTest = false;
  protected String kiteServerGridId;
  protected KiteLogger logger = KiteLogger.getLogger(this.getClass().getName());
  protected int meetingDuration = 0; //in seconds
  protected boolean multiThread = true;
  protected String name = this.getClass().getSimpleName();
  protected NetworkInstrumentation networkInstrumentation = null;
  protected String parentSuite = "";
  protected JsonObject payload;
  protected int port;
  protected Object resultObject;
  protected Container suite;
  protected Tuple tuple;
  protected int tupleSize;
  protected String url;
  protected Reporter reporter;
  protected RoomManager roomManager;
  private boolean closeWebDrivers = true;
  private boolean consoleLogs = true;
  private boolean csvReport = false;
  private int expectedTestDuration = 60; //in minutes
  private boolean fastRampUp = false;
  private boolean finished = false;
  private boolean loopRooms = false;
  private int maxUsersPerRoom = 1;
  private List<StepPhase> phases = new ArrayList<>();
  private boolean takeScreenshotForEachTest = false; // false by default
  private int testTimeout = 60;
  protected int windowWidth = 0;
  protected int windowHeight = 0;
  private String testJar = null;

  public KiteBaseTest() {}

  /**
   * Creates the TestRunners and add them to the testRunners list.
   */
  protected void createTestRunners() throws KiteGridException {
    for (int index = 0; index < this.tuple.size(); index++) {
      Client client = this.tuple.get(index);
      TestRunner runner = new TestRunner(client, this.reports, this.logger, this.reporter, index);
      this.add(runner);
      addToSessionMap(client);
    }
    this.tupleSize = size();
  }

  /**
   * Execute json object.
   *
   * @return the json object
   */
  public Object execute() {
    return execute(phases.get(0));
  }

  
  /**
   * Execute json object.
   *
   * @param stepPhase the phase for this Step.
   *
   * @return the json object
   */
  public Object execute(StepPhase stepPhase) {
    try {
      this.reports.get(stepPhase).setStartTimestamp();
      logger.info("execute(" + stepPhase + ") for: " + this.generateTestCaseName());
      if (!stepPhase.equals(StepPhase.LOADREACHED)) {
        init(stepPhase);
      }
      setStepPhase(stepPhase);
      if (multiThread) {
        testInParallel(stepPhase);
      } else {
        testSequentially(stepPhase);
      }
      this.reports.get(stepPhase).setStopTimestamp();
    } catch (Exception e) {
      // this is for the initiation mostly
      reporter.processException(reports.get(stepPhase), e, false);
    } finally {
      logger.info("execute(" + stepPhase + ") completed.");
      if (stepPhase.isLastPhase() || stepPhase.equals(StepPhase.LOADREACHED)) {
        terminate(stepPhase);
      }
    }
    if (resultObject == null) {
      resultObject = reports.get(stepPhase).toJson();
    }
    return resultObject;
  }

  /**
   * Fast ramp up boolean.
   *
   * @return true for fastRampUp
   */
  public boolean fastRampUp() {
    return fastRampUp;
  }

  private void init(StepPhase stepPhase) throws KiteTestException {
    AllureStepReport initStep = new AllureStepReport("Creating webdrivers and preparing threads..");
    initStep.setPhase(stepPhase);
    this.reports.get(stepPhase).addStepReport(initStep);
    try {
      initStep.setStartTimestamp();
      if (this.payload != null) {
        payloadHandling();
        reporter.jsonAttachment(initStep, "Test payload", this.payload);
      } else {
        logger.warn("payload is null");
      }
      reporter.setLogger(logger);
      populateTestRunners();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> terminate(stepPhase)));
      getInfoFromNavigator();
      initStep.setStatus(Status.PASSED);
    } catch (KiteGridException e) {
      logger.error("Exception while populating web drivers, " +
          "closing already created webdrivers...\r\n" + getStackTrace(e));
      reporter.textAttachment(initStep, "KiteGridException", getStackTrace(e), "plain");
      initStep.setStatus(Status.FAILED);
      throw new KiteTestException("Exception while populating web drivers", Status.BROKEN);
    }
  }

  public void terminate(StepPhase stepPhase) {
    if (!this.finished) {
      this.finished = true;
      AllureStepReport terminateStep = new AllureStepReport("Cleaning up and finishing the test");
      terminateStep.setStartTimestamp();
      if (closeWebDrivers) {
        WebDriverUtils.closeDrivers(this.tuple.getWebDrivers());
      }

      terminateStep.setStopTimestamp();

      // try to put stop time for all phase
      for (StepPhase phase : reports.keySet()) {
        AllureStepReport report = reports.get(phase);
        if (stepPhase.isLastPhase()) {
          report.addStepReport(terminateStep);
        }
        if (!report.getStage().equals(Stage.FINISHED)) {
          report.setStopTimestamp();
        }
      }
    }
  }

  /**
   * /**
   * Executes the tests in parallel.
   *
   * @throws Exception if an Exception occurs during method execution.
   */
  protected void testInParallel(StepPhase stepPhase) throws Exception {
    logger.info("Starting the execution of the test runners in parallel " + stepPhase.getName());
    expectedTestDuration = Math.max(expectedTestDuration, size() * 2);
    ExecutorService executorService = Executors.newFixedThreadPool(size());
    List<Future<Object>> futureList =
        executorService.invokeAll(this, expectedTestDuration, TimeUnit.MINUTES);
    executorService.shutdown();
    for (Future<Object> future : futureList) {
      future.get();
    }
  }

  /**
   * Executes the tests sequentially.
   * Assuming that all the callables have the same number of steps
   * If not, overwrite this function with appropriate order.
   */
  protected void testSequentially(StepPhase stepPhase) {
    logger.info("Starting the execution of the test runners sequentially " + stepPhase.getName());
    for (int i = 0; i < get(0).size(); i++) {
      for (TestRunner runner : this) {
        TestStep step = runner.get(i);
        processTestStep(stepPhase, step, reports.get(stepPhase));
      }
    }
  }

  /**
   * Fill out reports.get(stepPhase).
   */
  private void fillOutReport(StepPhase stepPhase) {
    String phaseReportName = (stepPhase.equals(StepPhase.DEFAULT)
        ? "" : stepPhase.getShortName() + "_") + generateTestCaseName();
    AllureTestReport phaseReport = new AllureTestReport(phaseReportName);
    phaseReport.setReporter(this.reporter);
    phaseReport.setFullName(getClass().getName());
    phaseReport.addLabel("package", getClass().getPackage().toString());
    phaseReport.addLabel("testClass", getClass().toString());
    phaseReport.addLabel("testMethod", "execute");
    try {
      phaseReport.addLabel("host", InetAddress.getLocalHost().getHostName());
    } catch (UnknownHostException e) {
      phaseReport.addLabel("host", "N/A");
    }
    this.reports.put(stepPhase, phaseReport);
    logger.info("Finished filling out initial report for phase " + stepPhase.getName());
  }

  /**
   * Generate test case name string.
   *
   * @return the string
   */
  protected String generateTestCaseName() {
    StringBuilder name = new StringBuilder();
    for (int index = 0; index < tuple.size(); index++) {
      Client client = tuple.get(index);
      name.append(client.getPlatform().name(), 0, 3);
      if (!client.isApp()) {
        name.append("_").append(client.getBrowserName(), 0, 2);
        if (client.getVersion() != null) {
          name.append("_").append(client.getVersion());
        }
      } else {
        name.append("_").append(client.getDeviceName(), 0, 2);
      }

      if (index < tuple.size() - 1) {
        name.append("-");
      }
    }
    return name.toString() + " (" +timestamp() + ")";
  }

  /**
   * Gets config file path.
   *
   * @return the config file path
   */
  public String getConfigFilePath() {
    return this.configFilePath;
  }

  /**
   * Sets config file path.
   *
   * @param configFilePath the config file path
   */
  public void setConfigFilePath(String configFilePath) {
    this.configFilePath = configFilePath;
  }

  /**
   * Gets expected test duration.
   *
   * @return the expected test duration (in minutes)
   */
  public int getExpectedTestDuration() {
    return expectedTestDuration;
  }

  /**
   * Sets the expected test duration (in minutes)
   *
   * @param expectedTestDuration the expected test duration
   */
  public void setExpectedTestDuration(int expectedTestDuration) {
    this.expectedTestDuration = expectedTestDuration;
  }

  /**
   * Retrieves the navigator.userAgent from all of the config objects and passes it to the the respective
   * Config object for processing.
   */
  protected void getInfoFromNavigator() throws KiteGridException {
    for (int i = 0; i < tuple.size(); i++) {
      if (!this.tuple.get(i).isApp()) {
        populateInfoFromNavigator(this.tuple.get(i).getWebDriver(), this.tuple.get(i));
      }
    }
  }

  public String getKiteServerGridId() {
    return kiteServerGridId;
  }

  /**
   * Gets max users per room.
   *
   * @return the max users per room
   */
  public int getMaxUsersPerRoom() {
    return maxUsersPerRoom;
  }

  /**
   * Gets the Instrumentation object
   *
   * @return the network instrumentation object
   */
  public NetworkInstrumentation getNetworkInstrumentation() {
    return this.networkInstrumentation;
  }

  /**
   * Sets instrumentation.
   *
   * @param networkInstrumentation the instrumentation
   */
  public void setNetworkInstrumentation(NetworkInstrumentation networkInstrumentation) {
    this.networkInstrumentation = networkInstrumentation;
  }

  /**
   * Gets peerConnections
   *
   * @return the jsonArray of peerConnections
   */
  public JsonArray getPeerConnections() {
    return getStatsConfig != null && getStatsConfig.containsKey("peerConnections") ?
        getStatsConfig.getJsonArray("peerConnections") : null;
  }

  /**
   * Gets report.
   *
   * @return the report
   */
  public AllureTestReport getReport(StepPhase stepPhase) {
    return reports.get(stepPhase);
  }

  /**
   * Gets room manager.
   *
   * @return the roomManager
   */
  @Transient
  protected RoomManager getRoomManager() {
    return roomManager;
  }

  /**
   * Gets selected stats.
   *
   * @return the jsonArray of selected stats for getStats
   */
  public JsonArray getSelectedStats() {
    return getStatsConfig != null && getStatsConfig.containsKey("selectedStats") ?
        getStatsConfig.getJsonArray("selectedStats") : null;
  }

  /**
   * Gets stats.
   *
   * @return true to call and collect getStats, false otherwise, as set in the config file.
   */
  public boolean getStats() {
    return getStatsConfig != null && getStatsConfig.getBoolean("enabled", false);
  }

  /**
   * Gets stats collection interval.
   *
   * @return statsCollectionInterval Time interval between each getStats call (Default 1)
   */
  public int getStatsCollectionInterval() {
    return getStatsConfig != null ? getStatsConfig.getInt("statsCollectionInterval", 0) : 0;
  }

  /**
   * Gets stats collection time.
   *
   * @return statsCollectionTime Time in seconds to collect stats (Default 10)
   */
  public int getStatsCollectionTime() {
    return getStatsConfig != null ? getStatsConfig.getInt("statsCollectionTime", 0) : 0;
  }

  /**
   * Gets the consoleLogs boolean (true by default)
   *
   * @return consoleLogs
   */
  public boolean getConsoleLogs() {
    return consoleLogs;
  }
  public boolean isLoadTest() {
    return isLoadTest;
  }

  public void setLoadTest(boolean loadTest) {
    isLoadTest = loadTest;
  }

  /**
   * Restructuring the test according to options given in payload object from config file. This
   * function processes the parameters common to all load tests.
   */
  protected void payloadHandling() {
    if (payload != null) {
      logger.info("the payload is " + payload.toString());
      url = payload.getString("url", null);
      port = payload.getInt("port", 30000);
      testTimeout = payload.getInt("testTimeout", testTimeout);
      takeScreenshotForEachTest =
          payload.getBoolean("takeScreenshotForEachTest", false);
      getStatsConfig = payload.getJsonObject("getStats");
      multiThread = payload.getBoolean("multiThread", true);
      expectedTestDuration = payload.getInt("expectedTestDuration", expectedTestDuration);
      meetingDuration = this.payload.getInt("meetingDuration", meetingDuration);
      setExpectedTestDuration(Math.max(getExpectedTestDuration(), (meetingDuration + 300) / 60));
      maxUsersPerRoom = payload.getInt("usersPerRoom", maxUsersPerRoom);
      csvReport = payload.getBoolean("csvReport", csvReport);
      consoleLogs = payload.getBoolean("consoleLogs", consoleLogs);
      fastRampUp = payload.getBoolean("fastRampUp", fastRampUp);
      if (payload.containsKey("windowSize") && payload.getString("windowSize").contains("x")) {
        StringTokenizer st = new StringTokenizer(payload.getString("windowSize"), "x");
        windowWidth = Integer.parseInt(st.nextToken());
        windowHeight = Integer.parseInt(st.nextToken());
      }
      if (this.payload.containsKey("scenarios")) {
        JsonArray jsonArray2 = this.payload.getJsonArray("scenarios");
        for (int i = 0; i < jsonArray2.size(); ++ i) {
          try {
            this.scenarioArrayList.add(new Scenario(jsonArray2.getJsonObject(i), logger, networkInstrumentation));
          } catch (Exception e) {
            logger.error("Invalid scenario number : " + i + "\r\n" + ReportUtils.getStackTrace(e));
          }
        }
      }
    }
  }

  /**
   * Populate the testRunners.
   */
  protected void populateTestRunners() throws KiteGridException {
    createTestRunners();
    for (TestRunner runner : this) {
      populateTestSteps(runner);
    }
  }

  /**
   *
   */
   private void addToSessionMap(Client client) throws KiteGridException {
     Map<String, Object> map = new HashMap<>();
     map.put("end_point", client);
    if (!client.isApp()) {
      String node =
          TestUtils.getNode(
              client.getPaas().getUrl(),
              ((RemoteWebDriver) client.getWebDriver()).getSessionId().toString());
      if (node != null) {
        map.put("node_host", node);
      }
     }
     this.sessionData.put(client.getWebDriver(), map);
   }
  /**
   * Abstract method to be overridden by the client to add steps to the TestRunner.
   *
   * @param runner the TestRunner
   */
  protected abstract void populateTestSteps(TestRunner runner);

  public void setCloseWebDrivers(boolean closeWebDrivers) {
    this.closeWebDrivers = closeWebDrivers;
  }

  /**
   * Sets description.
   *
   * @param description the description
   */
  public void setDescription(String description) {
    this.description = description;
    for (StepPhase stepPhase : phases) {
      this.reports.get(stepPhase).setDescription(stepPhase.getName() + description);
    }
  }

  public void setKiteServerGridId(String kiteServerGridId) {
    this.kiteServerGridId = kiteServerGridId;
  }

  /**
   * Sets logger.
   *
   * @param logger the logger
   */
  public void setLogger(KiteLogger logger) {
    this.logger = logger;
  }

  /**
   * Sets name.
   *
   * @param name the name
   */
  public void setName(String name) {
    this.name = name;
    for (StepPhase stepPhase : phases) {
      this.reports.get(stepPhase).setName(stepPhase.getName() + name);
    }
  }

  /**
   * Sets parent suite.
   *
   * @param parentTestSuite the parent test suite
   */
  public void setParentSuite(String parentTestSuite) {
    this.parentSuite = parentTestSuite;
    for (StepPhase stepPhase : phases) {
      this.reports.get(stepPhase).addLabel("parentSuite", parentTestSuite);
    }
  }

  /**
   * Sets payload.
   *
   * @param payload the payload
   */
  public void setPayload(JsonValue payload) {
    this.payload = (JsonObject) payload;
  }

  /**
   * Sets the List of StepPhases.
   *
   * @param phases
   */
  public void setPhases(List<StepPhase> phases) {
    this.phases = phases;
    this.reports.clear();
    for (StepPhase stepPhase : phases) {
      fillOutReport(stepPhase);
    }
  }

  private void setStepPhase(StepPhase stepPhase) {
    for (TestRunner runner : this) {
      runner.setStepPhase(stepPhase);
    }
  }

  /**
   * Sets suite.
   *
   * @param suite the suite
   */
  public void setSuite(Container suite) {
    this.suite = suite;
    for (StepPhase stepPhase : phases) {
      this.reports.get(stepPhase).addLabel("suite", suite.getName());
    }
  }

  /**
   * Sets client list.
   *
   * @param clientList the client list
   */
  public void setTuple(List<Client> clientList) {
    Tuple tuple = new Tuple(clientList);
    setTuple(tuple);
  }

  /**
   * Sets client list.
   *
   * @param tuple the client list
   */
  public void setTuple(Tuple tuple) {
    this.tuple = tuple;
    this.tupleSize = this.tuple.size();
    for (StepPhase stepPhase : phases) {
      this.reports.get(stepPhase).setName(stepPhase.getName() + generateTestCaseName());
    }
  }
  
  /**
   * Gets selected stats.
   * /**
   * Check if the steps are completed for all runners.
   *
   * @param stepName class name of the step
   *
   * @return true if the step has been completed on all runners
   * @throws KiteTestException the kite test exception
   */
  @Override
  public boolean stepCompleted(String stepName) throws KiteTestException {
    for (TestRunner runner : this) {
      if (!runner.completed(stepName)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Take screenshot for each test boolean.
   *
   * @return true or false as set in config file
   */
  public boolean takeScreenshotForEachTest() {
    return takeScreenshotForEachTest;
  }

  /**
   * Sets reporter.
   *
   * @param reporter the reporter
   */
  public void setReporter(Reporter reporter) {
    this.reporter = reporter;
  }

  /**
   * Sets room manager.
   *
   * @param roomManager the room manager
   */
  public void setRoomManager(RoomManager roomManager) {
    this.roomManager = roomManager;
  }


  /**
   * Gets the jar file
   * 
   * @return the jar file
   */
  public String getTestJar() {
    return testJar;
  }

  /**
   * Sets the jar file
   * 
   * @param testJar the jar file
   */
  public void setTestJar(String testJar) {
    this.testJar = testJar;
  }



}
