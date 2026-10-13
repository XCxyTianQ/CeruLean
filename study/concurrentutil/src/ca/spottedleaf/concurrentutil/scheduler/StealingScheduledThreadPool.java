package ca.spottedleaf.concurrentutil.scheduler;

import ca.spottedleaf.common.util.TimeUtil;
import ca.spottedleaf.concurrentutil.list.COWArrayList;
import ca.spottedleaf.concurrentutil.numa.OSNuma;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.concurrentutil.util.LazyRunnable;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class StealingScheduledThreadPool extends Scheduler {
   public static final long FLAG_SCHEDULE_EVENLY = 1L;
   public static final long FLAG_SCHEDULE_NEAR = 2L;
   public static final long DEFAULT_FLAGS = 1L;
   private final ThreadFactory threadFactory;
   private final OSNuma numa;
   private final COWArrayList<StealingScheduledThreadPool.TickThreadRunner> coreThreads = new COWArrayList<>(StealingScheduledThreadPool.TickThreadRunner.class);
   private final COWArrayList<StealingScheduledThreadPool.TickThreadRunner> aliveThreads = new COWArrayList<>(
      StealingScheduledThreadPool.TickThreadRunner.class
   );
   private final COWArrayList<StealingScheduledThreadPool.NodeThreads> nodes = new COWArrayList<>(StealingScheduledThreadPool.NodeThreads.class);
   private final ReferenceOpenHashSet<SchedulableTick> allTasks = new ReferenceOpenHashSet();
   private boolean shutdown;
   private long stealThresholdNS;
   private long taskTimeSliceNS;
   private long flags;

   public StealingScheduledThreadPool(final ThreadFactory threadFactory, final OSNuma numa) {
      super();
      this.threadFactory = threadFactory;
      this.numa = numa;
      if (threadFactory == null) {
         throw new NullPointerException("Null thread factory");
      }

      this.setFlags(1L);
   }

   public OSNuma getNuma() {
      return this.numa;
   }

   private static StealingScheduledThreadPool.ScheduledState getState(final SchedulableTick tick) {
      return (StealingScheduledThreadPool.ScheduledState)tick.state;
   }

   private static Thread[] getThreads(final COWArrayList<StealingScheduledThreadPool.TickThreadRunner> runners) {
      StealingScheduledThreadPool.TickThreadRunner[] array = runners.getArray();
      Thread[] ret = new Thread[array.length];

      for (int i = 0; i < array.length; i++) {
         ret[i] = array[i].thread;
      }

      return ret;
   }

   @Override
   public Thread[] getAliveThreads() {
      return getThreads(this.aliveThreads);
   }

   @Override
   public Thread[] getCoreThreads() {
      return getThreads(this.coreThreads);
   }

   public synchronized boolean isShutdown() {
      return this.shutdown;
   }

   public synchronized boolean setThreadAllocation(final Int2IntMap threadsPerNode, final long stealThresholdNS, final long taskTimeSliceNS) {
      if (this.shutdown) {
         return false;
      }

      this.stealThresholdNS = stealThresholdNS;
      this.taskTimeSliceNS = taskTimeSliceNS;
      StealingScheduledThreadPool.NodeThreads[] nodes = new StealingScheduledThreadPool.NodeThreads[threadsPerNode.size()];
      List<StealingScheduledThreadPool.TickThreadRunner> newRunners = new ArrayList<>();
      int nodesIndex = 0;
      ObjectIterator oldRunners = threadsPerNode.int2IntEntrySet().iterator();

      while (oldRunners.hasNext()) {
         it.unimi.dsi.fastutil.ints.Int2IntMap.Entry entry = (it.unimi.dsi.fastutil.ints.Int2IntMap.Entry)oldRunners.next();
         int node = entry.getIntKey();
         int threads = entry.getIntValue();
         StealingScheduledThreadPool.TickThreadRunner[] runners = new StealingScheduledThreadPool.TickThreadRunner[threads];
         StealingScheduledThreadPool.NodeThreads nodeThreads = new StealingScheduledThreadPool.NodeThreads(runners, node);

         for (int i = 0; i < threads; i++) {
            LazyRunnable run = new LazyRunnable();
            run.setRunnable(runners[i] = new StealingScheduledThreadPool.TickThreadRunner(this.threadFactory.newThread(run), this, nodeThreads, nodes));
            newRunners.add(runners[i]);
            this.aliveThreads.add(runners[i]);
         }

         nodes[nodesIndex++] = nodeThreads;
      }

      LongOpenHashSet oldRunnersx = new LongOpenHashSet(this.coreThreads.getArray().length);

      for (StealingScheduledThreadPool.TickThreadRunner oldRunner : this.coreThreads.getArray()) {
         oldRunnersx.add(oldRunner.id);
         oldRunner.halt();
      }

      this.coreThreads.set(newRunners.toArray(new StealingScheduledThreadPool.TickThreadRunner[0]));
      this.nodes.set(nodes);
      SchedulableTick[] allTasks;
      synchronized (this.allTasks) {
         allTasks = (SchedulableTick[])this.allTasks.toArray(new SchedulableTick[0]);
      }

      for (SchedulableTick tick : allTasks) {
         StealingScheduledThreadPool.ScheduledState state = getState(tick);
         StealingScheduledThreadPool.TickThreadRunner runner = state.getOwnedBy();
         if (runner == null || oldRunnersx.contains(runner.id)) {
            StealingScheduledThreadPool.TickThreadRunner newRunner = this.selectRunner(runner, this.nodes.getArray());
            if (newRunner == runner) {
               if (runner != null) {
                  throw new IllegalStateException();
               }
            } else {
               state.transferScheduling(runner, newRunner);
            }
         }
      }

      if (this.numa.isAvailable()) {
         long[] prevAffinity = this.numa.getCurrentThreadAffinity();

         try {
            for (StealingScheduledThreadPool.NodeThreads node : this.nodes.getArray()) {
               this.numa.setCurrentNumaAffinity(new int[]{node.nodeNumber});

               for (StealingScheduledThreadPool.TickThreadRunner runner : node.threads) {
                  runner.thread.start();
               }
            }
         } finally {
            this.numa.setCurrentThreadAffinity(prevAffinity);
         }
      } else {
         for (StealingScheduledThreadPool.TickThreadRunner runner : this.coreThreads.getArray()) {
            runner.thread.start();
         }
      }

      return true;
   }

   public synchronized boolean setFlags(final long flags) {
      if (this.shutdown) {
         return false;
      }

      this.flags = flags;
      return true;
   }

   private void die(final StealingScheduledThreadPool.TickThreadRunner runner) {
      synchronized (this) {
         this.aliveThreads.remove(runner);
      }
   }

   @Override
   public void halt() {
      synchronized (this) {
         this.shutdown = true;
      }

      for (StealingScheduledThreadPool.TickThreadRunner runner : this.coreThreads.getArray()) {
         runner.halt();
      }
   }

   @Override
   public boolean join(final long msToWait) {
      try {
         return this.join(msToWait, false);
      } catch (InterruptedException ex) {
         throw new IllegalStateException(ex);
      }
   }

   @Override
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
         for (StealingScheduledThreadPool.TickThreadRunner runner : this.aliveThreads.getArray()) {
            Thread thread = runner.thread;

            while (thread.isAlive()) {
               try {
                  if (msToWait > 0L) {
                     long current = System.nanoTime();
                     if (current - deadline >= 0L) {
                        return false;
                     }

                     thread.join(Duration.ofNanos(deadline - current));
                  } else {
                     thread.join();
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

   private StealingScheduledThreadPool.TickThreadRunner selectRunner(
      final StealingScheduledThreadPool.TickThreadRunner previousRunner, final StealingScheduledThreadPool.NodeThreads[] nodes
   ) {
      int currentNode = previousRunner == null ? this.numa.getCurrentNumaNode() : previousRunner.node.nodeNumber;
      if ((this.flags & 2L) != 0L) {
         StealingScheduledThreadPool.TickThreadRunner selected = null;
         int selectedDistance = Integer.MAX_VALUE;
         int selectedSize = Integer.MAX_VALUE;

         for (StealingScheduledThreadPool.NodeThreads node : nodes) {
            int distance = this.numa.getNumaDistance(currentNode, node.nodeNumber);
            if (distance <= selectedDistance) {
               for (StealingScheduledThreadPool.TickThreadRunner runner : node.threads) {
                  int size = runner.tickQueue.size();
                  if (distance < selectedDistance || size < selectedSize) {
                     selected = runner;
                     selectedDistance = distance;
                     selectedSize = size;
                  }
               }
            }
         }

         return selected;
      } else {
         StealingScheduledThreadPool.TickThreadRunner selected = null;
         int selectedSize = Integer.MAX_VALUE;
         int selectedDistance = Integer.MAX_VALUE;

         for (StealingScheduledThreadPool.NodeThreads node : nodes) {
            int distance = this.numa.getNumaDistance(currentNode, node.nodeNumber);

            for (StealingScheduledThreadPool.TickThreadRunner runner : node.threads) {
               int size = runner.tickQueue.size();
               if (size < selectedSize || size == selectedSize && distance < selectedDistance) {
                  selected = runner;
                  selectedSize = size;
                  selectedDistance = distance;
               }
            }
         }

         return selected;
      }
   }

   @Override
   public void schedule(final SchedulableTick tick) {
      StealingScheduledThreadPool.NodeThreads[] nodes = this.nodes.getArray();
      StealingScheduledThreadPool.ScheduledState state = new StealingScheduledThreadPool.ScheduledState(tick);
      if (!tick.setState(state)) {
         throw new IllegalStateException("Task is already scheduled");
      }

      StealingScheduledThreadPool.TickThreadRunner initialRunner = this.selectRunner(null, nodes);
      if (!state.initScheduling(this, initialRunner)) {
         throw new IllegalStateException("Task is already scheduled");
      }

      if (nodes != (nodes = this.nodes.getArray())) {
         state.transferScheduling(initialRunner, this.selectRunner(null, nodes));
      }

      if (tick.hasTasks()) {
         this.notifyTasks(tick);
      }
   }

   @Override
   public void notifyTasks(final SchedulableTick tick) {
      if (tick.state instanceof StealingScheduledThreadPool.ScheduledState state) {
         state.scheduleTasks();
      }
   }

   @Override
   public boolean cancel(final SchedulableTick tick) {
      return tick.state instanceof StealingScheduledThreadPool.ScheduledState state ? state.tryCancel() : false;
   }

   private void tickAdded(final StealingScheduledThreadPool.ScheduledState tick) {
      synchronized (this.allTasks) {
         this.allTasks.add(tick.tick);
      }
   }

   private void tickCancelled(final StealingScheduledThreadPool.ScheduledState tick) {
      synchronized (this.allTasks) {
         this.allTasks.remove(tick.tick);
      }
   }

   private static final class NodeThreads {
      private final StealingScheduledThreadPool.TickThreadRunner[] threads;
      private final int nodeNumber;

      private NodeThreads(final StealingScheduledThreadPool.TickThreadRunner[] threads, final int nodeNumber) {
         super();
         this.threads = threads;
         this.nodeNumber = nodeNumber;
      }
   }

   private static final class ScheduledState {
      private final SchedulableTick tick;
      private StealingScheduledThreadPool scheduledTo;
      private StealingScheduledThreadPool.TickThreadRunner ownedBy;
      private StealingScheduledThreadPool.TickScheduleHolder tickScheduleHolder;
      private StealingScheduledThreadPool.TaskScheduleHolder taskScheduleHolder;
      private static final int STATE_UNSCHEDULED = 1;
      private static final int STATE_IDLE = 2;
      private static final int STATE_HAS_TASKS = 4;
      private static final int STATE_RUNNING = 8;
      private static final int STATE_RUNNING_CANCELLED = 16;
      private static final int STATE_CANCELLED = 32;
      private volatile int state = 1;

      private ScheduledState(final SchedulableTick tick) {
         super();
         this.tick = tick;
      }

      private StealingScheduledThreadPool.TickThreadRunner getOwnedBy() {
         synchronized (this) {
            return this.ownedBy;
         }
      }

      private boolean initScheduling(final StealingScheduledThreadPool threadPool, final StealingScheduledThreadPool.TickThreadRunner runner) {
         synchronized (this) {
            if (this.state != 1) {
               return false;
            }

            long scheduledStart = this.tick.scheduledStart;
            if (scheduledStart == Long.MIN_VALUE) {
               throw new IllegalStateException("Start must be set when scheduling");
            }

            threadPool.tickAdded(this);
            this.scheduledTo = threadPool;
            if (runner != null) {
               this.ownedBy = runner;
               this.tickScheduleHolder = new StealingScheduledThreadPool.TickScheduleHolder(this, scheduledStart, false);
               runner.tickQueue.put(this.tickScheduleHolder, Boolean.TRUE);
               if (runner.findFirstTick() == this.tickScheduleHolder) {
                  runner.interruptIfIdle();
               }
            }

            this.state = 2;
            return true;
         }
      }

      private boolean transferScheduling(final StealingScheduledThreadPool.TickThreadRunner from, final StealingScheduledThreadPool.TickThreadRunner to) {
         synchronized (this) {
            if (this.ownedBy != from) {
               return false;
            }

            switch (this.state) {
               case 1:
                  throw new IllegalStateException();
               case 2:
                  this.ownedBy = to;
                  this.reschedule(false);
                  if (this.taskScheduleHolder != null) {
                     throw new IllegalStateException();
                  }

                  return true;
               case 4:
                  this.ownedBy = to;
                  this.reschedule(false);
                  if (to == null) {
                     this.taskScheduleHolder = null;
                  } else if (this.taskScheduleHolder != null) {
                     this.taskScheduleHolder = new StealingScheduledThreadPool.TaskScheduleHolder(this, this.taskScheduleHolder.lastDrainedTasks);
                     to.taskQueue.put(this.taskScheduleHolder, Boolean.TRUE);
                     to.interruptIfIdle();
                  }

                  return true;
               case 8:
                  this.ownedBy = to;
                  this.tickScheduleHolder = null;
                  this.taskScheduleHolder = null;
                  return true;
               case 16:
               case 32:
                  return false;
               default:
                  throw new IllegalStateException("Unknown state: " + this.state);
            }
         }
      }

      private boolean trySteal(final StealingScheduledThreadPool.TickThreadRunner from, final StealingScheduledThreadPool.TickThreadRunner to) {
         synchronized (this) {
            if (this.ownedBy != from) {
               return false;
            }

            switch (this.state) {
               case 1:
                  throw new IllegalStateException();
               case 2:
                  this.ownedBy = to;
                  this.reschedule(true);
                  if (this.taskScheduleHolder != null) {
                     throw new IllegalStateException();
                  }

                  return true;
               case 4:
                  this.ownedBy = to;
                  this.reschedule(true);
                  if (to == null) {
                     this.taskScheduleHolder = null;
                  } else if (this.taskScheduleHolder != null) {
                     this.taskScheduleHolder = new StealingScheduledThreadPool.TaskScheduleHolder(this, this.taskScheduleHolder.lastDrainedTasks);
                     to.taskQueue.put(this.taskScheduleHolder, Boolean.TRUE);
                     to.interruptIfIdle();
                  }

                  return true;
               case 8:
               case 16:
               case 32:
                  return false;
               default:
                  throw new IllegalStateException("Unknown state: " + this.state);
            }
         }
      }

      private void scheduleTasks() {
         int currState = this.state;
         if (currState == 2) {
            long now = System.nanoTime();
            synchronized (this) {
               if (this.state == 2) {
                  this.taskScheduleHolder = new StealingScheduledThreadPool.TaskScheduleHolder(this, now);
                  if (this.ownedBy != null) {
                     this.ownedBy.taskQueue.put(this.taskScheduleHolder, Boolean.TRUE);
                     if (Thread.currentThread() != this.ownedBy.thread) {
                        this.ownedBy.interruptIfIdle();
                     }
                  }

                  this.state = 4;
               }
            }
         }
      }

      private void reschedule(final boolean stolen) {
         long scheduledStart = this.tick.scheduledStart;
         if (scheduledStart == Long.MIN_VALUE) {
            throw new IllegalStateException("Start must be set when scheduling");
         }

         if (this.ownedBy != null) {
            this.tickScheduleHolder = new StealingScheduledThreadPool.TickScheduleHolder(this, scheduledStart, stolen);
            this.ownedBy.tickQueue.put(this.tickScheduleHolder, Boolean.TRUE);
            if (Thread.currentThread() != this.ownedBy.thread && this.ownedBy.findFirstTick() == this.tickScheduleHolder) {
               this.ownedBy.tryInterrupt();
            }
         }
      }

      private boolean trySetRunning(final StealingScheduledThreadPool.TickThreadRunner expectedOwner) {
         synchronized (this) {
            if (this.ownedBy != expectedOwner) {
               return false;
            }

            switch (this.state) {
               case 1:
                  throw new IllegalStateException("Cannot be unscheduled here");
               case 2:
               case 4:
                  this.state = 8;
                  return true;
               case 8:
               case 16:
               case 32:
                  return false;
               default:
                  throw new IllegalStateException("Unknown state: " + this.state);
            }
         }
      }

      private boolean tryCancel() {
         synchronized (this) {
            switch (this.state) {
               case 1:
                  return false;
               case 2:
               case 4:
                  this.doCancel();
                  return true;
               case 8:
                  this.state = 16;
                  return true;
               case 16:
               case 32:
                  return false;
               default:
                  throw new IllegalStateException("Unknown state: " + this.state);
            }
         }
      }

      private void finishTaskExecution(final boolean cancel) {
         synchronized (this) {
            if (this.state != 8 && this.state != 16) {
               throw new IllegalStateException("Must be running here");
            }

            if (!cancel && this.state != 16) {
               this.taskScheduleHolder = null;
               if (this.tickScheduleHolder == null) {
                  this.reschedule(false);
               }

               this.state = 2;
            } else {
               this.doCancel();
            }
         }
      }

      private void doCancel() {
         if (this.tickScheduleHolder != null) {
            if (this.ownedBy != null) {
               this.ownedBy.tickQueue.remove(this.tickScheduleHolder);
            }

            this.tickScheduleHolder = null;
         }

         if (this.taskScheduleHolder != null) {
            if (this.ownedBy != null) {
               this.ownedBy.taskQueue.remove(this.taskScheduleHolder);
            }

            this.taskScheduleHolder = null;
         }

         this.state = 32;
         this.scheduledTo.tickCancelled(this);
      }

      private void finishTickExecution(final boolean cancel) {
         synchronized (this) {
            if (this.state != 8 && this.state != 16) {
               throw new IllegalStateException("Must be running here");
            }

            if (!cancel && this.state != 16) {
               if (this.taskScheduleHolder != null) {
                  if (this.ownedBy != null) {
                     this.ownedBy.taskQueue.remove(this.taskScheduleHolder);
                  }

                  this.taskScheduleHolder = null;
               }

               this.tickScheduleHolder = null;
               this.reschedule(false);
               this.state = 2;
            } else {
               this.doCancel();
            }
         }
      }
   }

   private static final class TaskScheduleHolder {
      private static final AtomicLong ID_GENERATOR = new AtomicLong();
      private final long id = ID_GENERATOR.getAndIncrement();
      private static final Comparator<StealingScheduledThreadPool.TaskScheduleHolder> COMPARATOR = (h1, h2) -> {
         int timeCompare = TimeUtil.compareTimes(h2.lastDrainedTasks, h1.lastDrainedTasks);
         return timeCompare != 0 ? timeCompare : Long.compare(h1.id, h2.id);
      };
      private final StealingScheduledThreadPool.ScheduledState task;
      private final long lastDrainedTasks;

      private TaskScheduleHolder(final StealingScheduledThreadPool.ScheduledState task, final long lastDrainedTasks) {
         super();
         this.task = task;
         this.lastDrainedTasks = lastDrainedTasks;
      }
   }

   private static final class TickScheduleHolder {
      private static final AtomicLong ID_GENERATOR = new AtomicLong();
      private final long id = ID_GENERATOR.getAndIncrement();
      private static final Comparator<StealingScheduledThreadPool.TickScheduleHolder> COMPARATOR = (h1, h2) -> {
         int timeCompare = TimeUtil.compareTimes(h1.atNS, h2.atNS);
         return timeCompare != 0 ? timeCompare : Long.compare(h1.id, h2.id);
      };
      private final StealingScheduledThreadPool.ScheduledState tick;
      private final long atNS;
      private final boolean stolen;

      private TickScheduleHolder(final StealingScheduledThreadPool.ScheduledState tick, final long atNS, final boolean stolen) {
         super();
         this.tick = tick;
         this.atNS = atNS;
         this.stolen = stolen;
      }
   }

   private static final class TickThreadRunner implements Runnable {
      private static final AtomicLong ID_GENERATOR = new AtomicLong();
      private final long id = ID_GENERATOR.getAndIncrement();
      private static final long MAX_IDLE_TIME = TimeUnit.MILLISECONDS.toNanos(10L);
      private final Thread thread;
      private final StealingScheduledThreadPool scheduler;
      private final StealingScheduledThreadPool.NodeThreads node;
      private final StealingScheduledThreadPool.NodeThreads[] nodes;
      private static final int STATE_NOT_STARTED = 1;
      private static final int STATE_IDLE = 2;
      private static final int STATE_INTERRUPTED = 4;
      private static final int STATE_EXECUTING_TASKS = 8;
      private static final int STATE_EXECUTING_TICK = 16;
      private static final int STATE_HALTED = 32;
      private volatile int state = 1;
      private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(StealingScheduledThreadPool.TickThreadRunner.class, "state", int.class);
      private final ConcurrentSkipListMap<StealingScheduledThreadPool.TickScheduleHolder, Boolean> tickQueue = new ConcurrentSkipListMap<>(
         StealingScheduledThreadPool.TickScheduleHolder.COMPARATOR
      );
      private final ConcurrentSkipListMap<StealingScheduledThreadPool.TaskScheduleHolder, Boolean> taskQueue = new ConcurrentSkipListMap<>(
         StealingScheduledThreadPool.TaskScheduleHolder.COMPARATOR
      );

      private int getStateVolatile() {
         return (int)STATE_HANDLE.getVolatile((StealingScheduledThreadPool.TickThreadRunner)this);
      }

      private void setStateVolatile(final int value) {
         STATE_HANDLE.setVolatile((StealingScheduledThreadPool.TickThreadRunner)this, (int)value);
      }

      private int exchangeStateVolatile(final int value) {
         return (int)STATE_HANDLE.getAndSet((StealingScheduledThreadPool.TickThreadRunner)this, (int)value);
      }

      private int compareAndExchangeStateVolatile(final int expect, final int update) {
         return (int)STATE_HANDLE.compareAndExchange((StealingScheduledThreadPool.TickThreadRunner)this, (int)expect, (int)update);
      }

      private TickThreadRunner(
         final Thread thread,
         final StealingScheduledThreadPool scheduler,
         final StealingScheduledThreadPool.NodeThreads node,
         final StealingScheduledThreadPool.NodeThreads[] nodes
      ) {
         super();
         this.thread = thread;
         this.scheduler = scheduler;
         this.node = node;
         this.nodes = nodes;
      }

      private void interruptIfIdle() {
         int state = this.getStateVolatile();
         if (state == 2 && state == this.compareAndExchangeStateVolatile(state, 4)) {
            LockSupport.unpark(this.thread);
         }
      }

      private void halt() {
         if (2 == this.exchangeStateVolatile(32)) {
            LockSupport.unpark(this.thread);
         }
      }

      private void tryInterrupt() {
         int state = this.getStateVolatile();
         int failures = 0;

         while (true) {
            for (int i = 0; i < failures; i++) {
               ConcurrentUtil.backoff();
            }

            failures++;
            switch (state) {
               case 1:
               case 4:
               case 16:
               case 32:
                  return;
               case 2:
                  if (state == (state = this.compareAndExchangeStateVolatile(state, 4))) {
                     LockSupport.unpark(this.thread);
                     return;
                  }
                  break;
               case 8:
                  if (state == (state = this.compareAndExchangeStateVolatile(state, 4))) {
                     return;
                  }
                  break;
               default:
                  throw new IllegalStateException("Unknown state: " + state);
            }
         }
      }

      private StealingScheduledThreadPool.TickScheduleHolder findFirstTick() {
         Entry<StealingScheduledThreadPool.TickScheduleHolder, Boolean> first = this.tickQueue.firstEntry();
         return first == null ? null : first.getKey();
      }

      private StealingScheduledThreadPool.TaskScheduleHolder findFirstTask() {
         Entry<StealingScheduledThreadPool.TaskScheduleHolder, Boolean> first = this.taskQueue.firstEntry();
         return first == null ? null : first.getKey();
      }

      private void tryStealTask() {
         StealingScheduledThreadPool.TickScheduleHolder ourDeadlineHolder = this.findFirstTick();
         long ourEarliest = ourDeadlineHolder == null ? Long.MIN_VALUE : ourDeadlineHolder.atNS;
         long now = System.nanoTime();
         long stealThreshold = this.scheduler.stealThresholdNS;
         StealingScheduledThreadPool.ScheduledState selected = null;
         long selectedBehindAdjusted = Long.MIN_VALUE;
         int selectedDistance = Integer.MAX_VALUE;
         StealingScheduledThreadPool.TickThreadRunner selectedRunner = null;

         for (StealingScheduledThreadPool.NodeThreads node : this.nodes) {
            int distance = this.scheduler.numa.getNumaDistance(this.node.nodeNumber, node.nodeNumber);

            for (StealingScheduledThreadPool.TickThreadRunner runner : node.threads) {
               if (runner != this) {
                  StealingScheduledThreadPool.TickScheduleHolder holder = runner.findFirstTick();
                  if (holder != null && !holder.stolen) {
                     long holderStart = holder.atNS;
                     if (ourEarliest == Long.MIN_VALUE || TimeUtil.compareTimes(holderStart, ourEarliest) < 0L) {
                        long behindBy = now - holderStart;
                        if (behindBy >= stealThreshold) {
                           long behindAdjusted = behindBy / distance;
                           if (behindAdjusted > selectedBehindAdjusted || behindAdjusted == selectedBehindAdjusted && distance < selectedDistance) {
                              selected = holder.tick;
                              selectedBehindAdjusted = behindAdjusted;
                              selectedDistance = distance;
                              selectedRunner = runner;
                           }
                        }
                     }
                  }
               }
            }
         }

         if (selected != null && selected.trySteal(selectedRunner, this)) {
            this.tryInterrupt();
         }
      }

      private void begin() {
         if (this.scheduler.numa.isAvailable()) {
            this.scheduler.numa.setCurrentNumaAffinity(new int[]{this.node.nodeNumber});
         }

         this.compareAndExchangeStateVolatile(1, 4);
      }

      private void doRun() {
         while (this.mainLoop()) {
         }
      }

      private boolean executeTasksUntil(final long deadline) {
         long timeSlice = this.scheduler.taskTimeSliceNS;

         while (true) {
            this.tryStealTask();
            StealingScheduledThreadPool.TaskScheduleHolder taskHolder = this.findFirstTask();
            if (taskHolder == null) {
               while (this.getStateVolatile() == 2) {
                  Thread.interrupted();
                  long sleep = deadline - System.nanoTime();
                  if (sleep <= 0L) {
                     return true;
                  }

                  LockSupport.parkNanos("Awaiting deadline", Math.min(sleep, MAX_IDLE_TIME));
               }

               return false;
            }

            if (deadline - System.nanoTime() <= 0L) {
               return true;
            }

            if (2 != this.compareAndExchangeStateVolatile(2, 8)) {
               return false;
            }

            if (null == this.taskQueue.remove(taskHolder)) {
               this.compareAndExchangeStateVolatile(8, 2);
            } else {
               StealingScheduledThreadPool.ScheduledState task = taskHolder.task;
               if (task.trySetRunning(this)) {
                  boolean cancel = false;

                  try {
                     long start = System.nanoTime();
                     long toEndAt = TimeUtil.getLeastTime(deadline, start + timeSlice);
                     BooleanSupplier canContinueTasks = () -> this.getStateVolatile() != 8 ? false : System.nanoTime() - toEndAt < 0L;
                     cancel = !task.tick.runTasks(canContinueTasks);
                  } finally {
                     task.finishTaskExecution(cancel);
                  }
               }

               if (task.tick.hasTasks()) {
                  this.scheduler.notifyTasks(task.tick);
               }

               this.compareAndExchangeStateVolatile(8, 2);
            }
         }
      }

      private void tick(final StealingScheduledThreadPool.TickScheduleHolder tickHolder) {
         if (this.executeTasksUntil(tickHolder.atNS)) {
            if (2 == this.compareAndExchangeStateVolatile(2, 16)) {
               if (null == this.tickQueue.remove(tickHolder)) {
                  this.compareAndExchangeStateVolatile(16, 2);
               } else {
                  StealingScheduledThreadPool.ScheduledState tick = tickHolder.tick;
                  if (tick.trySetRunning(this)) {
                     boolean cancel = false;

                     try {
                        cancel = !tick.tick.runTick();
                     } finally {
                        tick.finishTickExecution(cancel);
                     }
                  }

                  if (tick.tick.hasTasks()) {
                     this.scheduler.notifyTasks(tick.tick);
                  }

                  this.compareAndExchangeStateVolatile(16, 4);
               }
            }
         }
      }

      private boolean mainLoop() {
         while (this.getStateVolatile() == 2) {
            this.tryStealTask();
            Thread.interrupted();
            LockSupport.parkNanos("Idle parking", MAX_IDLE_TIME);
         }

         if (4 != this.compareAndExchangeStateVolatile(4, 2)) {
            int curr = this.getStateVolatile();
            if (32 != curr) {
               throw new IllegalStateException("Unknown state: " + curr);
            } else {
               return false;
            }
         } else {
            StealingScheduledThreadPool.TickScheduleHolder toTick = this.findFirstTick();
            if (toTick == null) {
               this.tryStealTask();
               return true;
            } else {
               this.tick(toTick);
               return true;
            }
         }
      }

      private void die() {
         this.scheduler.die(this);
      }

      @Override
      public void run() {
         try {
            this.begin();
            this.doRun();
         } finally {
            this.die();
         }
      }
   }
}
