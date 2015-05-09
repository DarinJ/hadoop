package org.apache.hadoop.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mesos.Executor;
import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.MesosExecutorDriver;
import org.apache.mesos.Protos.*;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskStatus;

import java.io.*;

import java.lang.reflect.Field;
import java.lang.ReflectiveOperationException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MesosExecutor implements Executor {
  public static final Log LOG = LogFactory.getLog(MesosExecutor.class);

  protected final ScheduledExecutorService timerScheduler =
      Executors.newScheduledThreadPool(1);
  private SlaveInfo slaveInfo;
  private TaskTracker taskTracker;

  public static void main(String[] args) {
    MesosExecutorDriver driver = new MesosExecutorDriver(new MesosExecutor());
    System.exit(driver.run() == Status.DRIVER_STOPPED ? 0 : 1);
  }

// --------------------- Interface Executor ---------------------

  @Override
  public void registered(ExecutorDriver driver, ExecutorInfo executorInfo,
                         FrameworkInfo frameworkInfo, SlaveInfo slaveInfo) {
    LOG.info("Executor registered with the slave");
    this.slaveInfo = slaveInfo;
  }

  @Override
  public void reregistered(ExecutorDriver driver, SlaveInfo slaveInfo) {
    LOG.info("Executor reregistered with the slave");
  }

  @Override
  public void disconnected(ExecutorDriver driver) {
    LOG.info("Executor disconnected from the slave");
  }

  @Override
  public void launchTask(final ExecutorDriver driver, final TaskInfo task) {
    LOG.info("Launching task : " + task.getTaskId().getValue());

    // Get configuration from task data (prepared by the JobTracker).
    JobConf conf = configure(task);

    // NOTE: We need to manually set the context class loader here because,
    // the TaskTracker is unable to find LoginModule class otherwise.
    Thread.currentThread().setContextClassLoader(
        TaskTracker.class.getClassLoader());

    try {
      taskTracker = new TaskTracker(conf);
    } catch (IOException | InterruptedException e) {
      LOG.fatal("Failed to start TaskTracker", e);
      System.exit(1);
    }

    // Spin up a TaskTracker in a new thread.
    new Thread("TaskTracker Run Thread") {
      @Override
      public void run() {
        try {
          taskTracker.run();

          // Send a TASK_FINISHED status update.
          // We do this here because we want to send it in a separate thread
          // than was used to call killTask().
          driver.sendStatusUpdate(TaskStatus.newBuilder()
              .setTaskId(task.getTaskId())
              .setState(TaskState.TASK_FINISHED)
              .build());

          // Give some time for the update to reach the slave.
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            LOG.error("Failed to sleep TaskTracker thread", e);
          }

          // Stop the executor.
          driver.stop();
        } catch (Throwable t) {
          LOG.fatal("Caught exception, committing suicide.", t);
          driver.stop();
          System.exit(1);
        }
      }
    }.start();

    driver.sendStatusUpdate(TaskStatus.newBuilder()
        .setTaskId(task.getTaskId())
        .setState(TaskState.TASK_RUNNING).build());
  }

  @Override
  public void killTask(final ExecutorDriver driver, final TaskID taskId) {
    LOG.info("Killing task : " + taskId.getValue());
    if (taskTracker != null) {
      LOG.info("Revoking task tracker map/reduce slots");

      // terminate the task launchers
      try {
        killLauncher(taskTracker, "mapLauncher");
        killLauncher(taskTracker, "reduceLauncher");
      } catch (ReflectiveOperationException e) {
        LOG.fatal("Failed updating map slots due to error with reflection", e);
      }

      // Configure the new slot counts on the task tracker
      taskTracker.setMaxMapSlots(0);
      taskTracker.setMaxReduceSlots(0);

      // commit suicide when no jobs are running
      scheduleSuicideTimer();

      // Send the TASK_FINISHED status
      new Thread("TaskFinishedUpdate") {
        @Override
        public void run() {
          driver.sendStatusUpdate(TaskStatus.newBuilder()
              .setTaskId(taskId)
              .setState(TaskState.TASK_FINISHED)
              .build());
        }
      }.start();
    }
  }

  @Override
  public void frameworkMessage(ExecutorDriver d, byte[] msg) {
    LOG.info("Executor received framework message of length: " + msg.length
        + " bytes");
  }

  @Override
  public void shutdown(ExecutorDriver d) {
    LOG.info("Executor asked to shutdown");
  }

  @Override
  public void error(ExecutorDriver d, String message) {
    LOG.error("MesosExecutor.error: " + message);
  }

  private JobConf configure(final TaskInfo task) {
    JobConf conf = new JobConf(false);
    try {
      byte[] bytes = task.getData().toByteArray();
      conf.readFields(new DataInputStream(new ByteArrayInputStream(bytes)));
    } catch (IOException e) {
      LOG.warn("Failed to deserialize configuration.", e);
      System.exit(1);
    }

    // Output the configuration as XML for easy debugging.
    try {
      StringWriter writer = new StringWriter();
      conf.writeXml(writer);
      writer.flush();
      String xml = writer.getBuffer().toString();
      LOG.info("XML Configuration received:\n" +
          org.apache.mesos.hadoop.Utils.formatXml(xml));
    } catch (Exception e) {
      LOG.warn("Failed to output configuration as XML.", e);
    }

    // Get hostname from Mesos to make sure we match what it reports
    // to the JobTracker.
    conf.set("slave.host.name", slaveInfo.getHostname());

    // Set the mapred.local directory inside the executor sandbox, so that
    // different TaskTrackers on the same host do not step on each other.
    conf.set("mapred.local.dir", System.getenv("MESOS_DIRECTORY") + "/mapred");

    return conf;
  }

  /**
   * This is a hack to over
   * @param tracker
   * @param name
   * @throws NoSuchFieldException
   * @throws IllegalAccessException
   */
  private void killLauncher(TaskTracker tracker, String name) throws NoSuchFieldException, IllegalAccessException {
    Field f = tracker.getClass().getDeclaredField(name);
    f.setAccessible(true);

    // Kill the current map task launcher
    TaskTracker.TaskLauncher launcher = ((TaskTracker.TaskLauncher) f.get(tracker));
    launcher.notifySlots();
    launcher.interrupt();
  }

  protected void scheduleSuicideTimer() {
    timerScheduler.schedule(new Runnable() {
      @Override
      public void run() {
        if (taskTracker == null) {
          return;
        }

        LOG.info("Checking to see if TaskTracker is idle");

        // If the task tracker is idle, all tasks have finished and task output
        // has been cleaned up.
        if (taskTracker.isIdle()) {
          LOG.warn("TaskTracker is idle, terminating");

          try {
            taskTracker.shutdown();
          } catch (IOException | InterruptedException e) {
            LOG.error("Failed to shutdown TaskTracker", e);
          }
        } else {
          try {
            Field field = taskTracker.getClass().getDeclaredField("tasksToCleanup");
            field.setAccessible(true);
            BlockingQueue<TaskTrackerAction> tasksToCleanup = ((BlockingQueue<TaskTrackerAction>) field.get(taskTracker));
            LOG.info("TaskTracker has " + taskTracker.tasks.size() +
                " running tasks and " + tasksToCleanup +
                " tasks to clean up.");
          } catch (ReflectiveOperationException e) {
            LOG.fatal("Failed to get task counts from TaskTracker", e);
          }

          scheduleSuicideTimer();
        }
      }
    }, 1000, TimeUnit.MILLISECONDS);
  }
}
