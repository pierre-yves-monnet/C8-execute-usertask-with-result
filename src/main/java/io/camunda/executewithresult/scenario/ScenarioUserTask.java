package io.camunda.executewithresult.scenario;

import io.camunda.common.auth.Authentication;
import io.camunda.common.auth.Product;
import io.camunda.common.auth.SimpleAuthentication;
import io.camunda.common.auth.SimpleConfig;
import io.camunda.common.auth.SimpleCredential;
import io.camunda.executewithresult.ExecuteApplication;
import io.camunda.executewithresult.executor.TaskWithResult;
import io.camunda.executewithresult.worker.DelayWorker;
import io.camunda.executewithresult.worker.LogWorker;
import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.CamundaTaskListClientBuilder;
import io.camunda.tasklist.dto.Task;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
@ConfigurationProperties()

public class ScenarioUserTask {
  Logger logger = LoggerFactory.getLogger(ExecuteApplication.class.getName());

  private CamundaTaskListClient taskClient;

  @Value("${tasklist.url}")
  public String taskListUrl;
  @Value("${tasklist.username}")
  public String taskListUserName;
  @Value("${tasklist.userpassword:}")
  public String taskListUserPassword;

  @Value("${tasklist.clientId}")
  public String taskListClientId;
  @Value("${tasklist.clientSecret}")
  public String taskListClientSecret;
  @Value("${tasklist.taskListKeycloakUrl}")
  public String taskListKeycloakUrl;

  @Value("${usertaskwithresult.modeExecution:'simple'}")
  public String modeExecution;

  private int numberExecution = 0;

  @Autowired
  ZeebeClient zeebeClient;

  TaskWithResult taskWithResult;
  EngineCommand engineCommand;

  List<JobWorker> listWorkers = new ArrayList<>();

  /**
   * Initialize all environment
   */
  public void initialisation() {
    if (!connectionTaskList()) {
      return;
    }
    taskWithResult = new TaskWithResult(zeebeClient, taskClient);
    engineCommand = new EngineCommand(zeebeClient, taskClient);
    // create workers
    listWorkers.add(DelayWorker.registerWorker(zeebeClient));
    listWorkers.add(LogWorker.registerWorker(zeebeClient));

  }

  @Scheduled(fixedDelay = 10000)
  public void execute() {

    numberExecution++;
    try {
      if ("simple".equals(modeExecution)) {
        if (numberExecution > 1)
          return;
        executeSingleExecution();
      } else if ("multiple".equals(modeExecution)) {
        engineCommand.createProcessInstances("executeUserTaskWithResult", 100);
        executeSearchTask();
      }
    } catch (Exception e) {
      logger.error("Error execution [{}]", e);
    }

  }

  /**
   * ExecuteOne Simple execution
   */
  public void executeSingleExecution() throws Exception {
    try {
      engineCommand.createProcessInstances("executeUserTaskWithResult", 1);
      int loop = 0;
      while (loop < 10) {
        loop++;
        TaskList taskList = engineCommand.searchUserTask();
        if (taskList.getItems().isEmpty()) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          continue;
        }
        for (Task task : taskList.getItems()) {
          Map<String, Object> result = taskWithResult.executeTaskWithResult(task, 10000L);
        }
      }
    } catch (Exception e) {
      logger.error("Exception {}", e);
    }
  }

  // @ S c h  eduled(fixedDelay = 2000)
  public void executeSearchTask() {
    logger.info("------------------- Search for userTask to run");

    ExecutorService executor = Executors.newFixedThreadPool(20);

    TaskList tasksList = null;
    try {
      tasksList = engineCommand.searchUserTask();
      for (Task userTask : tasksList.getItems()) {
        Callable<Map<String, Object>> taskWithResultCallable = () -> taskWithResult.executeTaskWithResult(userTask,
            10000); // Use lambda to pass the parameter
        Future future = executor.submit(taskWithResultCallable);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Each task? Let's create a unique thread to manage it with Result

  }

  /**
   * connection To TaskList
   *
   * @return
   */
  private boolean connectionTaskList() {
    try {
      CamundaTaskListClientBuilder taskListBuilder = CamundaTaskListClient.builder();
      if (taskListClientId != null && !taskListClientId.isEmpty()) {

        taskListBuilder.taskListUrl(taskListUrl)
            .selfManagedAuthentication(taskListClientId, taskListClientSecret, taskListKeycloakUrl);
      } else {
        SimpleConfig simpleConf = new SimpleConfig();
        simpleConf.addProduct(Product.TASKLIST,
            new SimpleCredential(taskListUrl, taskListUserName, taskListUserPassword));
        Authentication auth = SimpleAuthentication.builder().withSimpleConfig(simpleConf).build();

        taskListBuilder.taskListUrl(taskListUrl).authentication(auth).cookieExpiration(Duration.ofSeconds(500));
      }
      logger.info("Connection to TaskList");
      taskClient = taskListBuilder.build();
      logger.info("Connection with success to TaskList");
      return true;
    } catch (Exception e) {
      logger.error("------------------ Connection error to taskList {}", e);
      return false;
    }
  }

}
