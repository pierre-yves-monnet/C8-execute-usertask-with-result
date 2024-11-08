package io.camunda.executewithresult.executor;

import java.util.Map;

/**
 * Object returned by the method
 */
public class ExecuteWithResult {
  public Map<String, Object> processVariables;
  public boolean taskNotFound = false;
  public boolean timeOut = false;
  public long executionTime;
  public String processInstance;
  // Return back the taskId where the processinstance is
  public String taskId;
}
