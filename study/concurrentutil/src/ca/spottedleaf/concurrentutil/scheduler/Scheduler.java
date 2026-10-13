package ca.spottedleaf.concurrentutil.scheduler;

public abstract class Scheduler {
   public Scheduler() {
      super();
   }

   public abstract Thread[] getAliveThreads();

   public abstract Thread[] getCoreThreads();

   public abstract void halt();

   public abstract boolean join(final long msToWait);

   public abstract boolean joinInterruptable(final long msToWait) throws InterruptedException;

   public abstract void schedule(final SchedulableTick tick);

   public abstract void notifyTasks(final SchedulableTick tick);

   public abstract boolean cancel(final SchedulableTick tick);
}
