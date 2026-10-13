package ca.spottedleaf.concurrentutil.scheduler;

import ca.spottedleaf.common.util.TimeUtil;
import ca.spottedleaf.concurrentutil.set.LinkedSortedSet;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.concurrentutil.util.LazyRunnable;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class EDFSchedulerThreadPool extends Scheduler {
   private static final Comparator<EDFSchedulerThreadPool.ScheduledState> TICK_COMPARATOR_BY_TIME = (s1, s2) -> {
      SchedulableTick t1 = s1.tick;
      SchedulableTick t2 = s2.tick;
      int timeCompare = TimeUtil.compareTimes(t1.scheduledStart, t2.scheduledStart);
      return timeCompare != 0 ? timeCompare : Long.signum(t1.id - t2.id);
   };
   private final EDFSchedulerThreadPool.TickThreadRunner[] runners;
   private final Thread[] threads;
   private final LinkedSortedSet<EDFSchedulerThreadPool.ScheduledState> awaiting = new LinkedSortedSet<>(TICK_COMPARATOR_BY_TIME);
   private final PriorityQueue<EDFSchedulerThreadPool.ScheduledState> queued = new PriorityQueue<>(TICK_COMPARATOR_BY_TIME);
   private final BitSet idleThreads;
   private final Object scheduleLock = new Object();
   private volatile boolean halted;

   public EDFSchedulerThreadPool(final int threads, final ThreadFactory threadFactory) {
      super();
      BitSet idleThreads = new BitSet(threads);

      for (int i = 0; i < threads; i++) {
         idleThreads.set(i);
      }

      this.idleThreads = idleThreads;
      EDFSchedulerThreadPool.TickThreadRunner[] runners = new EDFSchedulerThreadPool.TickThreadRunner[threads];
      Thread[] t = new Thread[threads];

      for (int i = 0; i < threads; i++) {
         LazyRunnable run = new LazyRunnable();
         Thread thread = t[i] = threadFactory.newThread(run);
         run.setRunnable(runners[i] = new EDFSchedulerThreadPool.TickThreadRunner(thread, i, this));
      }

      this.threads = t;
      this.runners = runners;
   }

   public void start() {
      for (Thread thread : this.threads) {
         thread.start();
      }
   }

   @Override
   public void halt() {
      this.halted = true;

      for (Thread thread : this.threads) {
         LockSupport.unpark(thread);
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
      long nsToWait = TimeUnit.MILLISECONDS.toNanos(msToWait);
      long start = System.nanoTime();
      long deadline = start + nsToWait;
      boolean interrupted = false;

      try {
         for (Thread thread : this.threads) {
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

   public Thread[] getThreads() {
      return (Thread[])this.threads.clone();
   }

   @Override
   public Thread[] getCoreThreads() {
      return this.getThreads();
   }

   @Override
   public Thread[] getAliveThreads() {
      List<Thread> ret = new ArrayList<>(this.threads.length);

      for (Thread thread : this.threads) {
         if (thread.isAlive()) {
            ret.add(thread);
         }
      }

      return ret.toArray(new Thread[0]);
   }

   private void insertFresh(final EDFSchedulerThreadPool.ScheduledState task) {
      EDFSchedulerThreadPool.TickThreadRunner[] runners = this.runners;
      int firstIdleThread = this.idleThreads.nextSetBit(0);
      if (firstIdleThread != -1) {
         this.idleThreads.clear(firstIdleThread);
         EDFSchedulerThreadPool.TickThreadRunner runner = runners[firstIdleThread];
         task.awaitingLink = this.awaiting.addLast(task);
         runner.acceptTask(task);
      } else {
         EDFSchedulerThreadPool.ScheduledState last = this.awaiting.last();
         if (last != null && TICK_COMPARATOR_BY_TIME.compare(task, last) < 0) {
            this.awaiting.pollLast();
            last.awaitingLink = null;
            task.awaitingLink = this.awaiting.addLast(task);
            this.queued.add(last);
            EDFSchedulerThreadPool.TickThreadRunner runner = last.ownedBy;
            runner.replaceTask(task);
         } else {
            this.queued.add(task);
         }
      }
   }

   private void takeTask(final EDFSchedulerThreadPool.TickThreadRunner runner, final EDFSchedulerThreadPool.ScheduledState tick) {
      if (!this.awaiting.remove(tick.awaitingLink)) {
         throw new IllegalStateException("Task is not in awaiting");
      }

      tick.awaitingLink = null;
   }

   private EDFSchedulerThreadPool.ScheduledState returnTask(
      final EDFSchedulerThreadPool.TickThreadRunner runner, final EDFSchedulerThreadPool.ScheduledState reschedule
   ) {
      if (reschedule != null) {
         this.queued.add(reschedule);
      }

      EDFSchedulerThreadPool.ScheduledState ret = this.queued.poll();
      if (ret == null) {
         this.idleThreads.set(runner.id);
      } else {
         ret.awaitingLink = this.awaiting.addLast(ret);
      }

      return ret;
   }

   @Override
   public void schedule(final SchedulableTick task) {
      synchronized (this.scheduleLock) {
         EDFSchedulerThreadPool.ScheduledState state = new EDFSchedulerThreadPool.ScheduledState(task);
         if (!task.setState(state)) {
            throw new IllegalStateException("Task " + task + " is already scheduled or cancelled");
         }

         if (!state.tryMarkScheduled()) {
            throw new IllegalStateException();
         }

         state.schedulerOwnedBy = this;
         this.insertFresh(state);
      }
   }

   public boolean updateTickStartToMax(final EDFSchedulerThreadPool.ScheduledState task, final long newStart) {
      synchronized (this.scheduleLock) {
         if (TimeUtil.compareTimes(newStart, task.tick.getScheduledStart()) <= 0) {
            return false;
         }

         if (this.queued.remove(task)) {
            task.tick.setScheduledStart(newStart);
            this.queued.add(task);
            return true;
         }

         if (task.awaitingLink != null) {
            this.awaiting.remove(task.awaitingLink);
            task.awaitingLink = null;
            task.tick.setScheduledStart(newStart);
            this.queued.add(task);
            EDFSchedulerThreadPool.TickThreadRunner runner = task.ownedBy;
            EDFSchedulerThreadPool.ScheduledState replace = this.queued.poll();
            if (replace != task) {
               runner.replaceTask(replace);
            }

            return true;
         } else {
            return false;
         }
      }
   }

   @Override
   public boolean cancel(final SchedulableTick task) {
      if (task.state instanceof EDFSchedulerThreadPool.ScheduledState state) {
         if (state.schedulerOwnedBy != this) {
            return false;
         }

         synchronized (this.scheduleLock) {
            if (this.queued.remove(state)) {
               return true;
            }

            if (state.awaitingLink != null) {
               this.awaiting.remove(state.awaitingLink);
               state.awaitingLink = null;
               EDFSchedulerThreadPool.TickThreadRunner runner = state.ownedBy;
               EDFSchedulerThreadPool.ScheduledState replace = this.queued.poll();
               if (replace == null) {
                  this.idleThreads.set(runner.id);
                  runner.forceIdle();
               } else {
                  runner.replaceTask(replace);
               }

               return true;
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   @Override
   public void notifyTasks(final SchedulableTick task) {
   }

   private static final class ScheduledState {
      private final SchedulableTick tick;
      private static final int SCHEDULE_STATE_NOT_SCHEDULED = 0;
      private static final int SCHEDULE_STATE_SCHEDULED = 1;
      private static final int SCHEDULE_STATE_CANCELLED = 2;
      private final AtomicInteger scheduled = new AtomicInteger();
      private EDFSchedulerThreadPool schedulerOwnedBy;
      private EDFSchedulerThreadPool.TickThreadRunner ownedBy;
      private LinkedSortedSet.Link<EDFSchedulerThreadPool.ScheduledState> awaitingLink;

      private ScheduledState(final SchedulableTick tick) {
         super();
         this.tick = tick;
      }

      private boolean tryMarkScheduled() {
         return this.scheduled.compareAndSet(0, 1);
      }

      private boolean tryMarkCancelled() {
         return this.scheduled.compareAndSet(1, 2);
      }

      private boolean isScheduled() {
         return this.scheduled.get() == 1;
      }
   }

   private static final class TickThreadRunner implements Runnable {
      private static final int STATE_IDLE = 0;
      private static final int STATE_AWAITING_TICK = 1;
      private static final int STATE_EXECUTING_TICK = 2;
      private final Thread thread;
      public final int id;
      public final EDFSchedulerThreadPool scheduler;
      private volatile EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state = new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(
         null, 0
      );
      private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(
         EDFSchedulerThreadPool.TickThreadRunner.class, "state", EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState.class
      );

      private void setStatePlain(final EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state) {
         STATE_HANDLE.set((EDFSchedulerThreadPool.TickThreadRunner)this, (EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState)state);
      }

      private void setStateOpaque(final EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state) {
         STATE_HANDLE.setOpaque((EDFSchedulerThreadPool.TickThreadRunner)this, (EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState)state);
      }

      private void setStateVolatile(final EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state) {
         STATE_HANDLE.setVolatile((EDFSchedulerThreadPool.TickThreadRunner)this, (EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState)state);
      }

      public TickThreadRunner(final Thread thread, final int id, final EDFSchedulerThreadPool scheduler) {
         super();
         this.thread = thread;
         this.id = id;
         this.scheduler = scheduler;
      }

      private Thread getRunnerThread() {
         return this.thread;
      }

      private void acceptTask(final EDFSchedulerThreadPool.ScheduledState task) {
         if (task.ownedBy != null) {
            throw new IllegalStateException("Already owned by another runner");
         }

         task.ownedBy = this;
         EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state = this.state;
         if (state.state != 0) {
            throw new IllegalStateException("Cannot accept task in state " + state);
         }

         this.setStateVolatile(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(task, 1));
         LockSupport.unpark(this.getRunnerThread());
      }

      private void replaceTask(final EDFSchedulerThreadPool.ScheduledState task) {
         EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state = this.state;
         if (state.state != 1) {
            throw new IllegalStateException("Cannot replace task in state " + state);
         }

         if (task.ownedBy != null) {
            throw new IllegalStateException("Already owned by another runner");
         }

         task.ownedBy = this;
         state.stateTarget.ownedBy = null;
         this.setStateVolatile(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(task, 1));
         LockSupport.unpark(this.getRunnerThread());
      }

      private void forceIdle() {
         EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state = this.state;
         if (state.state != 1) {
            throw new IllegalStateException("Cannot replace task in state " + state);
         }

         state.stateTarget.ownedBy = null;
         this.setStateOpaque(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(null, 0));
      }

      private boolean takeTask(final EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState state, final EDFSchedulerThreadPool.ScheduledState task) {
         synchronized (this.scheduler.scheduleLock) {
            if (this.state != state) {
               return false;
            }

            this.setStatePlain(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(task, 2));
            this.scheduler.takeTask(this, task);
            return true;
         }
      }

      private void returnTask(final EDFSchedulerThreadPool.ScheduledState task, final boolean reschedule) {
         synchronized (this.scheduler.scheduleLock) {
            task.ownedBy = null;
            EDFSchedulerThreadPool.ScheduledState newWait = this.scheduler.returnTask(this, reschedule && task.isScheduled() ? task : null);
            if (newWait == null) {
               this.setStatePlain(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(null, 0));
            } else {
               if (newWait.ownedBy != null) {
                  throw new IllegalStateException("Already owned by another runner");
               }

               newWait.ownedBy = this;
               this.setStatePlain(new EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState(newWait, 1));
            }
         }
      }

      @Override
      public void run() {
         while (true) {
            EDFSchedulerThreadPool.TickThreadRunner.TickThreadRunnerState startState = this.state;
            int startStateType = startState.state;
            EDFSchedulerThreadPool.ScheduledState startStateTask = startState.stateTarget;
            if (this.scheduler.halted) {
               return;
            }

            switch (startStateType) {
               case 0:
                  while (this.state.state == 0) {
                     Thread.interrupted();
                     LockSupport.park();
                     if (this.scheduler.halted) {
                        return;
                     }
                  }
                  break;
               case 1:
                  long deadline = startStateTask.tick.getScheduledStart();

                  while (this.state == startState) {
                     Thread.interrupted();
                     long diff = deadline - System.nanoTime();
                     if (diff <= 0L) {
                        if (this.takeTask(startState, startStateTask)) {
                           boolean reschedule = startStateTask.tick.runTick();
                           this.returnTask(startStateTask, reschedule);
                        }
                        break;
                     }

                     LockSupport.parkNanos(startState, diff);
                     if (this.scheduler.halted) {
                        return;
                     }
                  }
                  break;
               case 2:
                  throw new IllegalStateException("Tick execution must be set by runner thread, not by any other thread");
               default:
                  throw new IllegalStateException("Unknown state: " + startState);
            }
         }
      }

      private record TickThreadRunnerState(EDFSchedulerThreadPool.ScheduledState stateTarget, int state) {
         private TickThreadRunnerState {
            super();
         }
      }
   }
}
