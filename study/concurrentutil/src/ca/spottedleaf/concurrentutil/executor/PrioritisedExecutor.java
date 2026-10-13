package ca.spottedleaf.concurrentutil.executor;

import ca.spottedleaf.concurrentutil.util.Priority;

public interface PrioritisedExecutor {
   long getTotalTasksScheduled();

   long getTotalTasksExecuted();

   long generateNextSubOrder();

   boolean executeTask() throws IllegalStateException;

   boolean shutdown();

   boolean isShutdown();

   PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task);

   PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task, final Priority priority);

   PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task, final Priority priority, final long subOrder, final long stream);

   PrioritisedExecutor.PrioritisedTask createTask(final Runnable task);

   PrioritisedExecutor.PrioritisedTask createTask(final Runnable task, final Priority priority);

   PrioritisedExecutor.PrioritisedTask createTask(final Runnable task, final Priority priority, final long subOrder, final long stream);

   interface PrioritisedTask extends Cancellable {
      PrioritisedExecutor getExecutor();

      boolean queue();

      boolean isQueued();

      @Override
      boolean cancel();

      boolean execute();

      Priority getPriority();

      boolean setPriority(final Priority priority);

      boolean raisePriority(final Priority priority);

      boolean lowerPriority(final Priority priority);

      long getSubOrder();

      boolean setSubOrder(final long subOrder);

      boolean raiseSubOrder(final long subOrder);

      boolean lowerSubOrder(final long subOrder);

      long getStream();

      boolean setStream(final long stream);

      boolean setPrioritySubOrderStream(final Priority priority, final long subOrder, final long stream);

      PrioritisedExecutor.PriorityState getPriorityState();
   }

   record PriorityState(Priority priority, long subOrder, long stream) implements Comparable<PrioritisedExecutor.PriorityState> {
      public PriorityState {
         super();
      }

      public int compareTo(final PrioritisedExecutor.PriorityState other) {
         int priorityCompare = this.priority.priority - other.priority.priority;
         return priorityCompare != 0 ? priorityCompare : Long.compare(this.subOrder, other.subOrder);
      }

      public int compareToUsingStream(final PrioritisedExecutor.PriorityState other) {
         int streamCompare = Long.compare(this.stream, other.stream);
         if (streamCompare != 0) {
            return streamCompare;
         }

         int priorityCompare = this.priority.priority - other.priority.priority;
         return priorityCompare != 0 ? priorityCompare : Long.compare(this.subOrder, other.subOrder);
      }
   }
}
