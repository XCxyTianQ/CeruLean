package ca.spottedleaf.concurrentutil.executor.queue;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.lang.invoke.VarHandle;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class PrioritisedTaskQueue implements PrioritisedExecutor {
   public static final long FLAG_ORDER_BY_STREAM = 1L;
   private final AtomicLong taskIdGenerator = new AtomicLong();
   private final AtomicLong scheduledTasks = new AtomicLong();
   private final AtomicLong executedTasks = new AtomicLong();
   private final AtomicLong subOrderGenerator;
   private final AtomicBoolean shutdown = new AtomicBoolean();
   private final ConcurrentSkipListMap<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> tasks;
   private final Consumer<PrioritisedExecutor.PrioritisedTask> queueHook;

   public PrioritisedTaskQueue() {
      this(new AtomicLong());
   }

   public PrioritisedTaskQueue(final AtomicLong subOrderGenerator) {
      this(subOrderGenerator, 0L);
   }

   public PrioritisedTaskQueue(final AtomicLong subOrderGenerator, final long flags) {
      this(subOrderGenerator, flags, null);
   }

   public PrioritisedTaskQueue(final AtomicLong subOrderGenerator, final long flags, final Consumer<PrioritisedExecutor.PrioritisedTask> queueHook) {
      super();
      this.subOrderGenerator = subOrderGenerator;
      this.tasks = new ConcurrentSkipListMap<>(
         (flags & 1L) != 0L ? PrioritisedTaskQueue.PrioritisedQueuedTask.COMPARATOR_STREAM : PrioritisedTaskQueue.PrioritisedQueuedTask.COMPARATOR
      );
      this.queueHook = queueHook;
   }

   @Override
   public long getTotalTasksScheduled() {
      return this.scheduledTasks.get();
   }

   @Override
   public long getTotalTasksExecuted() {
      return this.executedTasks.get();
   }

   @Override
   public long generateNextSubOrder() {
      return this.subOrderGenerator.getAndIncrement();
   }

   @Override
   public boolean shutdown() {
      return this.shutdown.compareAndSet(false, true);
   }

   @Override
   public boolean isShutdown() {
      return this.shutdown.get();
   }

   public PrioritisedExecutor.PrioritisedTask peekFirst() {
      Entry<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> firstEntry = this.tasks.firstEntry();
      return firstEntry == null ? null : firstEntry.getKey().task;
   }

   public Priority getHighestPriority() {
      Entry<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> firstEntry = this.tasks.firstEntry();
      return firstEntry == null ? null : Priority.getPriority(firstEntry.getKey().priority);
   }

   public boolean hasNoScheduledTasks() {
      long executedTasks = this.executedTasks.get();
      long scheduledTasks = this.scheduledTasks.get();
      return executedTasks == scheduledTasks;
   }

   public PrioritisedExecutor.PriorityState getHighestPriorityState() {
      Entry<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> firstEntry = this.tasks.firstEntry();
      if (firstEntry == null) {
         return null;
      }

      PrioritisedTaskQueue.PrioritisedQueuedTask.Holder holder = firstEntry.getKey();
      return new PrioritisedExecutor.PriorityState(Priority.getPriority(holder.priority), holder.subOrder, holder.stream);
   }

   public Runnable pollTask() {
      while (true) {
         Entry<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> firstEntry = this.tasks.pollFirstEntry();
         if (firstEntry != null) {
            PrioritisedTaskQueue.PrioritisedQueuedTask.Holder task = firstEntry.getKey();
            task.markRemoved();
            if (!task.task.cancel()) {
               continue;
            }

            return task.task.execute;
         }

         return null;
      }
   }

   @Override
   public boolean executeTask() {
      while (true) {
         Entry<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder, Boolean> firstEntry = this.tasks.pollFirstEntry();
         if (firstEntry != null) {
            PrioritisedTaskQueue.PrioritisedQueuedTask.Holder task = firstEntry.getKey();
            task.markRemoved();
            if (!task.task.execute()) {
               continue;
            }

            return true;
         }

         return false;
      }
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask createTask(final Runnable task) {
      return this.createTask(task, Priority.NORMAL);
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask createTask(final Runnable task, final Priority priority) {
      return this.createTask(task, priority, this.generateNextSubOrder(), 0L);
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask createTask(final Runnable task, final Priority priority, final long subOrder, final long stream) {
      if (!Priority.isValidPriority(priority)) {
         throw new IllegalArgumentException("Invalid priority " + priority);
      } else {
         return new PrioritisedTaskQueue.PrioritisedQueuedTask(task, priority, subOrder, stream, this.taskIdGenerator.getAndIncrement());
      }
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(task);
      ret.queue();
      return ret;
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task, final Priority priority) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(task, priority);
      ret.queue();
      return ret;
   }

   @Override
   public PrioritisedExecutor.PrioritisedTask queueTask(final Runnable task, final Priority priority, final long subOrder, final long stream) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(task, priority, subOrder, stream);
      ret.queue();
      return ret;
   }

   private final class PrioritisedQueuedTask implements PrioritisedExecutor.PrioritisedTask {
      public static final Comparator<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder> COMPARATOR = (t1, t2) -> {
         int priorityCompare = t1.priority - t2.priority;
         if (priorityCompare != 0) {
            return priorityCompare;
         }

         int subOrderCompare = Long.compare(t1.subOrder, t2.subOrder);
         return subOrderCompare != 0 ? subOrderCompare : Long.signum(t1.id - t2.id);
      };
      public static final Comparator<PrioritisedTaskQueue.PrioritisedQueuedTask.Holder> COMPARATOR_STREAM = (t1, t2) -> {
         int priorityCompare = t1.priority - t2.priority;
         if (priorityCompare != 0) {
            return priorityCompare;
         }

         int streamCompare = Long.compare(t1.stream, t2.stream);
         if (streamCompare != 0) {
            return streamCompare;
         }

         int subOrderCompare = Long.compare(t1.subOrder, t2.subOrder);
         return subOrderCompare != 0 ? subOrderCompare : Long.signum(t1.id - t2.id);
      };
      private final long id;
      private final Runnable execute;
      private Priority priority;
      private long subOrder;
      private long stream;
      private PrioritisedTaskQueue.PrioritisedQueuedTask.Holder holder;

      public PrioritisedQueuedTask(final Runnable execute, final Priority priority, final long subOrder, final long stream, final long id) {
         super();
         this.execute = execute;
         this.priority = priority;
         this.subOrder = subOrder;
         this.stream = stream;
         this.id = id;
      }

      @Override
      public PrioritisedExecutor getExecutor() {
         return PrioritisedTaskQueue.this;
      }

      @Override
      public boolean queue() {
         synchronized (this) {
            if (this.holder != null || this.priority == Priority.COMPLETING) {
               return false;
            }

            if (PrioritisedTaskQueue.this.isShutdown()) {
               throw new IllegalStateException("Queue is shutdown");
            }

            PrioritisedTaskQueue.PrioritisedQueuedTask.Holder holder = new PrioritisedTaskQueue.PrioritisedQueuedTask.Holder(
               this, this.priority.priority, this.subOrder, this.stream, this.id
            );
            this.holder = holder;
            PrioritisedTaskQueue.this.scheduledTasks.getAndIncrement();
            PrioritisedTaskQueue.this.tasks.put(holder, Boolean.TRUE);
         }

         if (PrioritisedTaskQueue.this.isShutdown() && this.cancel()) {
            throw new IllegalStateException("Queue is shutdown");
         }

         Consumer<PrioritisedExecutor.PrioritisedTask> queueHook = PrioritisedTaskQueue.this.queueHook;
         if (queueHook != null) {
            queueHook.accept(this);
         }

         return true;
      }

      @Override
      public boolean isQueued() {
         synchronized (this) {
            return this.holder != null && this.priority != Priority.COMPLETING;
         }
      }

      @Override
      public boolean cancel() {
         synchronized (this) {
            if (this.priority == Priority.COMPLETING) {
               return false;
            }

            this.priority = Priority.COMPLETING;
            if (this.holder != null) {
               if (this.holder.markRemoved()) {
                  PrioritisedTaskQueue.this.tasks.remove(this.holder);
               }

               PrioritisedTaskQueue.this.executedTasks.getAndIncrement();
            }

            return true;
         }
      }

      @Override
      public boolean execute() {
         boolean increaseExecuted;
         synchronized (this) {
            if (this.priority == Priority.COMPLETING) {
               return false;
            }

            this.priority = Priority.COMPLETING;
            if ((increaseExecuted = this.holder != null) && this.holder.markRemoved()) {
               PrioritisedTaskQueue.this.tasks.remove(this.holder);
            }
         }

         try {
            this.execute.run();
            return true;
         } finally {
            if (increaseExecuted) {
               PrioritisedTaskQueue.this.executedTasks.getAndIncrement();
            }
         }
      }

      @Override
      public Priority getPriority() {
         synchronized (this) {
            return this.priority;
         }
      }

      private void updateHolder(final int priority, final long subOrder, final long stream, final long id) {
         PrioritisedTaskQueue.PrioritisedQueuedTask.Holder oldHolder = this.holder;
         if (oldHolder != null) {
            PrioritisedTaskQueue.PrioritisedQueuedTask.Holder newHolder = new PrioritisedTaskQueue.PrioritisedQueuedTask.Holder(
               this, priority, subOrder, stream, id
            );
            this.holder = newHolder;
            PrioritisedTaskQueue.this.tasks.put(newHolder, Boolean.TRUE);
            if (oldHolder.markRemoved()) {
               PrioritisedTaskQueue.this.tasks.remove(oldHolder);
            }
         }
      }

      @Override
      public boolean setPriority(final Priority priority) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && this.priority != priority) {
               this.priority = priority;
               this.updateHolder(priority.priority, this.subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public boolean raisePriority(final Priority priority) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && !this.priority.isHigherOrEqualPriority(priority)) {
               this.priority = priority;
               this.updateHolder(priority.priority, this.subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public boolean lowerPriority(Priority priority) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && !this.priority.isLowerOrEqualPriority(priority)) {
               this.priority = priority;
               this.updateHolder(priority.priority, this.subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public long getSubOrder() {
         synchronized (this) {
            return this.priority == Priority.COMPLETING ? 0L : this.subOrder;
         }
      }

      @Override
      public boolean setSubOrder(final long subOrder) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && this.subOrder != subOrder) {
               this.subOrder = subOrder;
               this.updateHolder(this.priority.priority, subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public boolean raiseSubOrder(long subOrder) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && this.subOrder < subOrder) {
               this.subOrder = subOrder;
               this.updateHolder(this.priority.priority, subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public boolean lowerSubOrder(final long subOrder) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && this.subOrder > subOrder) {
               this.subOrder = subOrder;
               this.updateHolder(this.priority.priority, subOrder, this.stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public long getStream() {
         synchronized (this) {
            return this.priority == Priority.COMPLETING ? 0L : this.stream;
         }
      }

      @Override
      public boolean setStream(final long stream) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && this.stream != stream) {
               this.stream = stream;
               this.updateHolder(this.priority.priority, this.subOrder, stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public boolean setPrioritySubOrderStream(final Priority priority, final long subOrder, final long stream) {
         synchronized (this) {
            if (this.priority != Priority.COMPLETING && (this.priority != priority || this.subOrder != subOrder || this.stream != stream)) {
               this.priority = priority;
               this.subOrder = subOrder;
               this.stream = stream;
               this.updateHolder(priority.priority, subOrder, stream, this.id);
               return true;
            } else {
               return false;
            }
         }
      }

      @Override
      public PrioritisedExecutor.PriorityState getPriorityState() {
         synchronized (this) {
            return this.priority == Priority.COMPLETING ? null : new PrioritisedExecutor.PriorityState(this.priority, this.subOrder, this.stream);
         }
      }

      private static final class Holder {
         private final PrioritisedTaskQueue.PrioritisedQueuedTask task;
         private final int priority;
         private final long subOrder;
         private final long stream;
         private final long id;
         private volatile boolean removed;
         private static final VarHandle REMOVED_HANDLE = ConcurrentUtil.getVarHandle(
            PrioritisedTaskQueue.PrioritisedQueuedTask.Holder.class, "removed", boolean.class
         );

         private Holder(final PrioritisedTaskQueue.PrioritisedQueuedTask task, final int priority, final long subOrder, final long stream, final long id) {
            super();
            this.task = task;
            this.priority = priority;
            this.subOrder = subOrder;
            this.stream = stream;
            this.id = id;
         }

         public boolean markRemoved() {
            return !(boolean)REMOVED_HANDLE.compareAndExchange((PrioritisedTaskQueue.PrioritisedQueuedTask.Holder)this, (boolean)false, (boolean)true);
         }
      }
   }
}
