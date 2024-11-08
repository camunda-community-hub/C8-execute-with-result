package io.camunda.executewithresult;

import io.camunda.executewithresult.scenario.ScenarioUserTask;

// import io.camunda.zeebe.spring.client.EnableZeebeClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan


@EnableScheduling

public class ExecuteApplication {
  Logger logger = LoggerFactory.getLogger(ExecuteApplication.class.getName());
  @Autowired
  ScenarioUserTask scenarioUserTask;

  public static void main(String[] args) {

    SpringApplication.run(ExecuteApplication.class, args);
    // thanks to Spring, the class AutomatorStartup.init() is active.
  }

  @PostConstruct
  public void init() {

    // first, check all internal runner
    scenarioUserTask.initialisation();

    // then let's the scheduler do the job
  }
}
