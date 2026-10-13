package ca.spottedleaf.concurrentutil.executor.thread;

import ca.spottedleaf.common.util.TimeUtil;
import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.executor.QueueExecutorRunnable;
import ca.spottedleaf.concurrentutil.executor.queue.PrioritisedTaskQueue;
import ca.spottedleaf.concurrentutil.list.COWArrayList;
import ca.spottedleaf.concurrentutil.util.LazyRunnable;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BalancedPrioritisedThreadPool {
   public static final long DEFAULT_GROUP_TIME_SLICE = 15000000L;
   public static final long FLAG_ORDER_BY_STREAM = 1L;
   public static final long DEFAULT_FLAGS = 0L;
   private static final Logger LOGGER = LoggerFactory.getLogger(BalancedPrioritisedThreadPool.class);
   private final COWArrayList<BalancedPrioritisedThreadPool.OrderedStreamGroup> groups = new COWArrayList<>(
      BalancedPrioritisedThreadPool.OrderedStreamGroup.class
   );
   private final COWArrayList<BalancedPrioritisedThreadPool.WorkerThread> threads = new COWArrayList<>(BalancedPrioritisedThreadPool.WorkerThread.class);
   private final COWArrayList<BalancedPrioritisedThreadPool.WorkerThread> aliveThreads = new COWArrayList<>(BalancedPrioritisedThreadPool.WorkerThread.class);
   private final long groupTimeSliceNS;
   private final ThreadFactory threadFactory;
   private boolean shutdown;
   private final long flags;

   public BalancedPrioritisedThreadPool(final long groupTimeSliceNS, final ThreadFactory threadFactory) {
      this(groupTimeSliceNS, threadFactory, 0L);
   }

   public BalancedPrioritisedThreadPool(final long groupTimeSliceNS, final ThreadFactory threadFactory, final long flags) {
      super();
      this.flags = flags;
      this.groupTimeSliceNS = groupTimeSliceNS;
      this.threadFactory = threadFactory;
      if (threadFactory == null) {
         throw new NullPointerException("Thread factory may not be null");
      }
   }

   private void wakeupIdleThread() {
      for (BalancedPrioritisedThreadPool.WorkerThread thread : this.threads.getArray()) {
         if (thread.notifyTasks()) {
            return;
         }
      }
   }

   private static Thread[] getThreads(final COWArrayList<BalancedPrioritisedThreadPool.WorkerThread> workers) {
      BalancedPrioritisedThreadPool.WorkerThread[] array = workers.getArray();
      Thread[] ret = new Thread[array.length];

      for (int i = 0; i < array.length; i++) {
         ret[i] = array[i].thread;
      }

      return ret;
   }

   public Thread[] getAliveThreads() {
      return getThreads(this.aliveThreads);
   }

   public Thread[] getCoreThreads() {
      return getThreads(this.threads);
   }

   public void halt(final boolean shutdownQueues) {
      synchronized (this) {
         this.shutdown = true;
      }

      if (shutdownQueues) {
         for (BalancedPrioritisedThreadPool.OrderedStreamGroup group : this.groups.getArray()) {
            for (BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue : group.queues.getArray()) {
               queue.shutdown();
            }
         }
      }

      for (BalancedPrioritisedThreadPool.WorkerThread thread : this.threads.getArray()) {
         thread.halt(false);
      }
   }

   public boolean join(final long msToWait) {
      try {
         return this.join(msToWait, false);
      } catch (InterruptedException ex) {
         throw new IllegalStateException(ex);
      }
   }

   public boolean joinInterruptable(final long msToWait) throws InterruptedException {
      return this.join(msToWait, true);
   }

   private boolean join(final long msToWait, final boolean interruptable) throws InterruptedException {
      synchronized (this) {
         if (!this.shutdown) {
            throw new IllegalStateException("Attempting to join on non-shutdown pool");
         }
      }

      long nsToWait = TimeUnit.MILLISECONDS.toNanos(msToWait);
      long start = System.nanoTime();
      long deadline = start + nsToWait;
      boolean interrupted = false;

      try {
         for (BalancedPrioritisedThreadPool.WorkerThread thread : this.aliveThreads.getArray()) {
            while (thread.thread.isAlive()) {
               try {
                  if (msToWait > 0L) {
                     long current = System.nanoTime();
                     if (current - deadline >= 0L) {
                        return false;
                     }

                     thread.thread.join(Duration.ofNanos(deadline - current));
                  } else {
                     thread.thread.join();
                  }
               } catch (InterruptedException ex) {
                  if (interruptable) {
                     throw ex;
                  }

                  interrupted = true;
               }
            }
         }

         return true;
      } finally {
         if (interrupted) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public void shutdown(final boolean wait) {
      synchronized (this) {
         this.shutdown = true;
      }

      for (BalancedPrioritisedThreadPool.OrderedStreamGroup group : this.groups.getArray()) {
         for (BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue : group.queues.getArray()) {
            queue.shutdown();
         }
      }

      for (BalancedPrioritisedThreadPool.WorkerThread thread : this.threads.getArray()) {
         thread.close(false, false);
      }

      if (wait) {
         this.join(0L);
      }
   }

   private void die(final BalancedPrioritisedThreadPool.WorkerThread thread) {
      this.aliveThreads.remove(thread);
   }

   public void adjustThreadCount(final int threads) {
      synchronized (this) {
         if (this.shutdown) {
            return;
         }

         BalancedPrioritisedThreadPool.WorkerThread[] currentThreads = this.threads.getArray();
         if (threads == currentThreads.length) {
            return;
         }

         if (threads < currentThreads.length) {
            int i = 0;

            for (int difference = currentThreads.length - threads; i < difference; i++) {
               BalancedPrioritisedThreadPool.WorkerThread remove = currentThreads[currentThreads.length - i - 1];
               remove.halt(false);
               this.threads.remove(remove);
            }
         } else {
            int i = 0;

            for (int difference = threads - currentThreads.length; i < difference; i++) {
               LazyRunnable run = new LazyRunnable();
               BalancedPrioritisedThreadPool.WorkerThread thread = new BalancedPrioritisedThreadPool.WorkerThread(this.threadFactory.newThread(run));
               run.setRunnable(thread);
               this.aliveThreads.add(thread);
               this.threads.add(thread);
               thread.thread.start();
            }
         }
      }

      for (BalancedPrioritisedThreadPool.WorkerThread thread : this.threads.getArray()) {
         thread.notifyTasks();
      }
   }

   public BalancedPrioritisedThreadPool.OrderedStreamGroup createOrderedStreamGroup() {
      return this.createOrderedStreamGroup(new AtomicLong());
   }

   public BalancedPrioritisedThreadPool.OrderedStreamGroup createOrderedStreamGroup(final AtomicLong subOrderGenerate) {
      synchronized (this) {
         if (this.shutdown) {
            throw new IllegalStateException("Queue is shutdown");
         }

         BalancedPrioritisedThreadPool.OrderedStreamGroup ret = new BalancedPrioritisedThreadPool.OrderedStreamGroup(subOrderGenerate, (this.flags & 1L) != 0L);
         this.groups.add(ret);
         return ret;
      }
   }

   private static int compareGroup(final BalancedPrioritisedThreadPool.OrderedStreamGroup g1, final BalancedPrioritisedThreadPool.OrderedStreamGroup g2) {
      int parallelismCompare = g1.currentParallelism - g2.currentParallelism;
      return parallelismCompare != 0 ? parallelismCompare : TimeUtil.compareTimes(g1.lastRetrieved, g2.lastRetrieved);
   }

   private List<BalancedPrioritisedThreadPool.OrderedStreamGroup> findEligibleGroups() {
      List<BalancedPrioritisedThreadPool.OrderedStreamGroup> nonEmpty = new ArrayList<>();

      for (BalancedPrioritisedThreadPool.OrderedStreamGroup group : this.groups.getArray()) {
         if (group.hasAnyTasks()) {
            nonEmpty.add(group);
         }
      }

      return nonEmpty;
   }

   private BalancedPrioritisedThreadPool.OrderedStreamGroup obtainGroup0(final List<BalancedPrioritisedThreadPool.OrderedStreamGroup> groups, final long time) {
      BalancedPrioritisedThreadPool.OrderedStreamGroup ret = null;

      for (BalancedPrioritisedThreadPool.OrderedStreamGroup group : groups) {
         if (ret == null || compareGroup(group, ret) < 0) {
            ret = group;
         }
      }

      if (ret != null) {
         ret.lastRetrieved = time;
         ret.currentParallelism++;
         return ret;
      } else {
         return ret;
      }
   }

   private BalancedPrioritisedThreadPool.OrderedStreamGroup obtainGroup(final long time) {
      List<BalancedPrioritisedThreadPool.OrderedStreamGroup> groups = this.findEligibleGroups();
      synchronized (this) {
         return this.obtainGroup0(groups, time);
      }
   }

   private void returnGroup(final BalancedPrioritisedThreadPool.OrderedStreamGroup group) {
      synchronized (this) {
         group.currentParallelism--;
      }
   }

   public final class OrderedStreamGroup {
      private final AtomicLong subOrderGenerator;
      private final boolean streamCompare;
      private final COWArrayList<BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue> queues = new COWArrayList<>(
         BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue.class
      );
      private int currentParallelism;
      private long lastRetrieved = System.nanoTime();

      public OrderedStreamGroup(final AtomicLong subOrderGenerator, final boolean streamCompare) {
         super();
         this.subOrderGenerator = subOrderGenerator;
         this.streamCompare = streamCompare;
      }

      public boolean hasAnyTasks() {
         for (BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue : this.queues.getArray()) {
            if (queue.wrapped.peekFirst() != null) {
               return true;
            }
         }

         return false;
      }

      public boolean executeTask() {
         PrioritisedExecutor.PrioritisedTask task;
         do {
            task = this.peekTask();
            if (task == null) {
               return false;
            }
         } while (!task.execute());

         return true;
      }

      public PrioritisedExecutor.PrioritisedTask peekTask() {
         PrioritisedExecutor.PrioritisedTask highestTask = null;
         PrioritisedExecutor.PriorityState highestPriority = null;

         for (BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue wrapper : this.queues.getArray()) {
            PrioritisedTaskQueue queue = wrapper.wrapped;
            PrioritisedExecutor.PriorityState state = null;

            PrioritisedExecutor.PrioritisedTask first;
            while ((first = queue.peekFirst()) != null && (state = first.getPriorityState()) == null) {
            }

            if (first != null) {
               if (highestPriority == null || (this.streamCompare ? state.compareToUsingStream(highestPriority) : state.compareTo(highestPriority)) < 0) {
                  highestTask = first;
                  highestPriority = state;
               }
            } else if (queue.isShutdown() && queue.hasNoScheduledTasks()) {
               this.queues.remove(wrapper);
            }
         }

         return highestTask;
      }

      public BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue createExecutor() {
         synchronized (BalancedPrioritisedThreadPool.this) {
            if (BalancedPrioritisedThreadPool.this.shutdown) {
               throw new IllegalStateException("Queue is shutdown");
            }

            BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue ret = new BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue(
               this.subOrderGenerator, this.streamCompare
            );
            this.queues.add(ret);
            return ret;
         }
      }

      public final class Queue implements PrioritisedExecutor {
         private final PrioritisedTaskQueue wrapped;
         private volatile boolean halt;
         private final AtomicLong executors = new AtomicLong();

         public Queue(final AtomicLong subOrderGenerator, final boolean streamCompare) {
            super();
            this.wrapped = new PrioritisedTaskQueue(subOrderGenerator, streamCompare ? 1L : 0L);
         }

         public void halt() {
            this.halt = true;
            OrderedStreamGroup.this.queues.remove(this);
         }

         public boolean isActive() {
            if (this.halt) {
               return this.executors.get() > 0L;
            } else {
               return !this.isShutdown() ? true : !this.wrapped.hasNoScheduledTasks();
            }
         }

         @Override
         public long getTotalTasksScheduled() {
            return this.wrapped.getTotalTasksScheduled();
         }

         @Override
         public long getTotalTasksExecuted() {
            return this.wrapped.getTotalTasksExecuted();
         }

         @Override
         public long generateNextSubOrder() {
            return this.wrapped.generateNextSubOrder();
         }

         @Override
         public boolean shutdown() {
            return this.wrapped.shutdown();
         }

         @Override
         public boolean isShutdown() {
            return this.wrapped.isShutdown();
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
            return new BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue.Task(this.wrapped.createTask(() -> {
               this.executors.getAndIncrement();

               try {
                  task.run();
               } finally {
                  this.executors.getAndDecrement();
               }
            }, priority, subOrder, stream));
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

         @Override
         public boolean executeTask() {
            return this.wrapped.executeTask();
         }

         private final class Task implements PrioritisedExecutor.PrioritisedTask {
            private final PrioritisedExecutor.PrioritisedTask wrap;

            public Task(final PrioritisedExecutor.PrioritisedTask wrap) {
               super();
               this.wrap = wrap;
            }

            @Override
            public PrioritisedExecutor getExecutor() {
               return Queue.this;
            }

            @Override
            public boolean queue() {
               if (this.wrap.queue()) {
                  BalancedPrioritisedThreadPool.this.wakeupIdleThread();
                  return true;
               } else {
                  return false;
               }
            }

            @Override
            public boolean isQueued() {
               return this.wrap.isQueued();
            }

            @Override
            public boolean cancel() {
               return this.wrap.cancel();
            }

            @Override
            public boolean execute() {
               return this.wrap.execute();
            }

            @Override
            public Priority getPriority() {
               return this.wrap.getPriority();
            }

            @Override
            public boolean setPriority(final Priority priority) {
               return this.wrap.setPriority(priority);
            }

            @Override
            public boolean raisePriority(final Priority priority) {
               return this.wrap.raisePriority(priority);
            }

            @Override
            public boolean lowerPriority(final Priority priority) {
               return this.wrap.lowerPriority(priority);
            }

            @Override
            public long getSubOrder() {
               return this.wrap.getSubOrder();
            }

            @Override
            public boolean setSubOrder(final long subOrder) {
               return this.wrap.setSubOrder(subOrder);
            }

            @Override
            public boolean raiseSubOrder(final long subOrder) {
               return this.wrap.raiseSubOrder(subOrder);
            }

            @Override
            public boolean lowerSubOrder(final long subOrder) {
               return this.wrap.lowerSubOrder(subOrder);
            }

            @Override
            public long getStream() {
               return this.wrap.getStream();
            }

            @Override
            public boolean setStream(final long stream) {
               return this.wrap.setStream(stream);
            }

            @Override
            public boolean setPrioritySubOrderStream(final Priority priority, final long subOrder, final long stream) {
               return this.wrap.setPrioritySubOrderStream(priority, subOrder, stream);
            }

            @Override
            public PrioritisedExecutor.PriorityState getPriorityState() {
               return this.wrap.getPriorityState();
            }
         }
      }
   }

   private final class WorkerThread extends QueueExecutorRunnable {
      public WorkerThread(final Thread thread) {
         super(thread, null);
      }

      @Override
      protected void die() {
         BalancedPrioritisedThreadPool.this.die(this);
      }

      @Override
      protected boolean pollTasks() {
         boolean ret = false;

         while (!this.halted) {
            BalancedPrioritisedThreadPool.OrderedStreamGroup group = BalancedPrioritisedThreadPool.this.obtainGroup(System.nanoTime());
            if (group == null) {
               break;
            }

            long deadline = System.nanoTime() + BalancedPrioritisedThreadPool.this.groupTimeSliceNS;

            do {
               try {
                  if (this.halted || !group.executeTask()) {
                     break;
                  }

                  ret = true;
               } catch (Throwable throwable) {
                  BalancedPrioritisedThreadPool.LOGGER.error("Exception thrown from thread '" + this.thread.getName(), throwable);
               }
            } while (System.nanoTime() - deadline <= 0L);

            BalancedPrioritisedThreadPool.this.returnGroup(group);
         }

         return ret;
      }
   }
}
