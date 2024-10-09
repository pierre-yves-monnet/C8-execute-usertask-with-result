package io.camunda.executewithresult.worker;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class LogWorker implements JobHandler {
  public static final String PROCESS_VARIABLE_CALCULATION = "calculation";
  public static final String PROCESS_VARIABLE_SIGNATURE = "signature";
  public static final String PROCESS_VARIABLE_DELAYMS = "delayms";
  public static final String PROCESS_VARIABLE_MESSAGE = "message";
  Logger logger = LoggerFactory.getLogger(LogWorker.class.getName());



  public static io.camunda.zeebe.client.api.worker.JobWorker registerWorker(ZeebeClient zeebeClient) {
    return zeebeClient.newWorker()
        .jobType("log")
        .handler(new LogWorker())
        .open();

  }

  public void handle(JobClient jobClient, ActivatedJob job) throws Exception {

      Object message = job.getVariablesAsMap().get(PROCESS_VARIABLE_MESSAGE);
      logger.info("WorkerLog. message[{}]", message);
      jobClient.newCompleteCommand(job.getKey()).send();

  }

}
