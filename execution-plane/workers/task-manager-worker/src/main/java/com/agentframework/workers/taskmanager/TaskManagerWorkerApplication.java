package com.agentframework.workers.taskmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for the Task Manager Worker.
 *
 * <p>This worker fetches issue snapshots from the external issue tracker
 * (via tracker-mcp) and stores them on PlanItems so downstream workers
 * receive rich, up-to-date task context without having to query the tracker
 * themselves.
 */
@SpringBootApplication
public class TaskManagerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskManagerWorkerApplication.class, args);
    }
}
