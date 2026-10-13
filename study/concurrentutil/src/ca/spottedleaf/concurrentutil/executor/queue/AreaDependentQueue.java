package ca.spottedleaf.concurrentutil.executor.queue;

import ca.spottedleaf.common.util.IntPairUtil;
import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock;
import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class AreaDependentQueue {
   public static final long FLAG_ORDER_BY_STREAM = 1L;
   private final AtomicLong idGenerator = new AtomicLong();
   private final PrioritisedExecutor executor;
   private final ConcurrentChainedLong2ReferenceHashTable<AreaDependentQueue.Position> tasks = ConcurrentChainedLong2ReferenceHashTable.createWithCapacity(
      1024, 0.25F
   );
   private final ReentrantAreaLock lock;
   private final boolean orderByLowerStream;

   public AreaDependentQueue(final PrioritisedExecutor executor, final int lockShift) {
      this(executor, lockShift, 0L);
   }

   public AreaDependentQueue(final PrioritisedExecutor executor, final int lockShift, final long flags) {
      super();
      this.executor = executor;
      this.lock = new ReentrantAreaLock(lockShift);
      this.orderByLowerStream = (flags & 1L) != 0L;
   }

   public long generateNextSubOrder() {
      return this.executor.generateNextSubOrder();
   }

   public PrioritisedExecutor.PrioritisedTask createTask(final int x, final int y, final int radius, final Runnable run) {
      return this.createTask(x - radius, y - radius, x + radius, y + radius, run);
   }

   public PrioritisedExecutor.PrioritisedTask createTask(final int x, final int y, final int radius, final Runnable run, final Priority priority) {
      return this.createTask(x - radius, y - radius, x + radius, y + radius, run, priority);
   }

   public PrioritisedExecutor.PrioritisedTask createTask(
      final int x, final int y, final int radius, final Runnable run, final Priority priority, final long subOrder, final long stream
   ) {
      return this.createTask(x - radius, y - radius, x + radius, y + radius, run, priority, subOrder, stream);
   }

   public PrioritisedExecutor.PrioritisedTask createTask(final int minX, final int minY, final int maxX, final int maxY, final Runnable run) {
      return this.createTask(minX, minY, maxX, maxY, run, Priority.NORMAL);
   }

   public PrioritisedExecutor.PrioritisedTask createTask(
      final int minX, final int minY, final int maxX, final int maxY, final Runnable run, final Priority priority
   ) {
      return this.createTask(minX, minY, maxX, maxY, run, priority, this.generateNextSubOrder(), 0L);
   }

   public PrioritisedExecutor.PrioritisedTask createTask(
      final int minX, final int minY, final int maxX, final int maxY, final Runnable run, final Priority priority, final long subOrder, final long stream
   ) {
      if (minX <= maxX && minY <= maxY) {
         return new AreaDependentQueue.PositionedTask(this, this.idGenerator.getAndIncrement(), minX, minY, maxX, maxY, run, priority, subOrder, stream);
      } else {
         throw new IllegalArgumentException("min should be <= max");
      }
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(final int x, final int y, final int radius, final Runnable run) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(x, y, radius, run);
      ret.queue();
      return ret;
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(final int x, final int y, final int radius, final Runnable run, final Priority priority) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(x, y, radius, run, priority);
      ret.queue();
      return ret;
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(
      final int x, final int y, final int radius, final Runnable run, final Priority priority, final long subOrder, final long stream
   ) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(x, y, radius, run, priority, subOrder, stream);
      ret.queue();
      return ret;
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(final int minX, final int minY, final int maxX, final int maxY, final Runnable run) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(minX, minY, maxX, maxY, run);
      ret.queue();
      return ret;
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(
      final int minX, final int minY, final int maxX, final int maxY, final Runnable run, final Priority priority
   ) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(minX, minY, maxX, maxY, run, priority);
      ret.queue();
      return ret;
   }

   public PrioritisedExecutor.PrioritisedTask queueTask(
      final int minX, final int minY, final int maxX, final int maxY, final Runnable run, final Priority priority, final long subOrder, final long stream
   ) {
      PrioritisedExecutor.PrioritisedTask ret = this.createTask(minX, minY, maxX, maxY, run, priority, subOrder, stream);
      ret.queue();
      return ret;
   }

   boolean add(
      final AreaDependentQueue.PositionedTask positionedTask,
      final AreaDependentQueue.QueuedTask task,
      final List<PrioritisedExecutor.PrioritisedTask> toCancel
   ) {
      int minX = positionedTask.minX;
      int minY = positionedTask.minY;
      int maxX = positionedTask.maxX;
      int maxY = positionedTask.maxY;
      boolean first = true;

      for (int currY = minY; currY <= maxY; currY++) {
         for (int currX = minX; currX <= maxX; currX++) {
            long key = IntPairUtil.key(currX, currY);
            AreaDependentQueue.Position position = this.tasks.computeIfAbsent(key, keyInMap -> new AreaDependentQueue.Position());
            first &= position.addTask(task, toCancel);
         }
      }

      return first;
   }

   void remove(
      final AreaDependentQueue.PositionedTask positionedTask, final AreaDependentQueue.QueuedTask task, final List<PrioritisedExecutor.PrioritisedTask> toQueue
   ) {
      int minX = positionedTask.minX;
      int minY = positionedTask.minY;
      int maxX = positionedTask.maxX;
      int maxY = positionedTask.maxY;

      for (int currY = minY; currY <= maxY; currY++) {
         for (int currX = minX; currX <= maxX; currX++) {
            long key = IntPairUtil.key(currX, currY);
            AreaDependentQueue.Position position = this.tasks.get(key);
            if (!position.removeTask(task, toQueue)) {
               this.tasks.remove(key);
            }
         }
      }
   }

   void adjust(
      final AreaDependentQueue.PositionedTask positionedTask,
      final AreaDependentQueue.QueuedTask oldTask,
      final AreaDependentQueue.QueuedTask newTask,
      final List<PrioritisedExecutor.PrioritisedTask> toQueue,
      final List<PrioritisedExecutor.PrioritisedTask> toCancel
   ) {
      int minX = positionedTask.minX;
      int minY = positionedTask.minY;
      int maxX = positionedTask.maxX;
      int maxY = positionedTask.maxY;

      for (int currY = minY; currY <= maxY; currY++) {
         for (int currX = minX; currX <= maxX; currX++) {
            this.tasks.get(IntPairUtil.key(currX, currY)).adjustTask(oldTask, newTask, toQueue, toCancel);
         }
      }
   }

   private static final class Position {
      private final PriorityQueue<AreaDependentQueue.QueuedTask> queue = new PriorityQueue<>();
      private AreaDependentQueue.QueuedTask firstOrRunning;

      private Position() {
         super();
      }

      boolean addTask(final AreaDependentQueue.QueuedTask task, final List<PrioritisedExecutor.PrioritisedTask> toCancel) {
         this.queue.add(task);
         if (this.queue.peek() != task) {
            task.positionedTask.incWaitingPosRaw();
            return false;
         } else {
            AreaDependentQueue.QueuedTask currentFirst = this.firstOrRunning;
            if (currentFirst != null && !currentFirst.positionedTask.incWaitingPos(currentFirst, toCancel)) {
               task.positionedTask.incWaitingPosRaw();
               return false;
            } else {
               this.firstOrRunning = task;
               return true;
            }
         }
      }

      boolean removeTask(final AreaDependentQueue.QueuedTask task, final List<PrioritisedExecutor.PrioritisedTask> toQueue) {
         this.queue.remove(task);
         AreaDependentQueue.QueuedTask currentFirst = this.firstOrRunning;
         if (task != currentFirst) {
            return true;
         } else {
            AreaDependentQueue.QueuedTask nextFirst = this.queue.peek();
            if (nextFirst == null) {
               this.firstOrRunning = null;
               return false;
            } else {
               this.firstOrRunning = nextFirst;
               nextFirst.positionedTask.decWaitingPos(nextFirst, toQueue);
               return true;
            }
         }
      }

      void adjustTask(
         final AreaDependentQueue.QueuedTask oldTask,
         final AreaDependentQueue.QueuedTask newTask,
         final List<PrioritisedExecutor.PrioritisedTask> toQueue,
         final List<PrioritisedExecutor.PrioritisedTask> toCancel
      ) {
         this.queue.remove(oldTask);
         this.queue.add(newTask);
         AreaDependentQueue.QueuedTask currentFirst = this.firstOrRunning;
         AreaDependentQueue.QueuedTask nextFirst = this.queue.peek();
         if (currentFirst != nextFirst) {
            if (currentFirst == oldTask) {
               this.firstOrRunning = nextFirst;
               if (nextFirst != newTask) {
                  nextFirst.positionedTask.decWaitingPos(nextFirst, toQueue);
                  if (!oldTask.positionedTask.incWaitingPos(oldTask, null)) {
                     throw new IllegalStateException("Old task may not be in executing state");
                  }
               }
            } else if (currentFirst.positionedTask.incWaitingPos(currentFirst, toCancel)) {
               this.firstOrRunning = nextFirst;
               if (nextFirst == newTask) {
                  nextFirst.positionedTask.decWaitingPosRaw();
               } else {
                  nextFirst.positionedTask.decWaitingPos(newTask, toQueue);
               }
            }
         }
      }
   }

   private static final class PositionedTask implements PrioritisedExecutor.PrioritisedTask {
      private static final int STATE_NOT_QUEUED = 1;
      private static final int STATE_WAITING = 2;
      private static final int STATE_SCHEDULED = 4;
      private static final int STATE_EXECUTING = 8;
      private int waitingPosCount;
      private int state = 1;
      private AreaDependentQueue.QueuedTask scheduledTask;
      private final AreaDependentQueue areaDependentQueue;
      private final long taskId;
      private final int minX;
      private final int minY;
      private final int maxX;
      private final int maxY;
      private final int maxWaitingPos;
      private final Runnable run;

      private PositionedTask(
         final AreaDependentQueue areaDependentQueue,
         final long taskId,
         final int minX,
         final int minY,
         final int maxX,
         final int maxY,
         final Runnable run,
         final Priority priority,
         final long subOrder,
         final long stream
      ) {
         super();
         this.areaDependentQueue = areaDependentQueue;
         this.taskId = taskId;
         this.minX = minX;
         this.minY = minY;
         this.maxX = maxX;
         this.maxY = maxY;
         this.maxWaitingPos = (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1);
         this.run = run;
         this.scheduledTask = new AreaDependentQueue.QueuedTask(priority, subOrder, stream, this, areaDependentQueue.executor);
      }

      void incWaitingPosRaw() {
         if (++this.waitingPosCount > this.maxWaitingPos) {
            throw new IllegalStateException("Waiting pos too large");
         }
      }

      void decWaitingPosRaw() {
         if (--this.waitingPosCount < 0) {
            throw new IllegalStateException("Waiting pos too small");
         }
      }

      synchronized boolean incWaitingPos(final AreaDependentQueue.QueuedTask task, final List<PrioritisedExecutor.PrioritisedTask> toCancel) {
         int state = this.state;
         switch (state) {
            case 2:
               this.incWaitingPosRaw();
               return true;
            case 4:
               this.state = 2;
               this.incWaitingPosRaw();
               if (toCancel != null) {
                  toCancel.add(task.swapTask(this.areaDependentQueue.executor));
               }

               return true;
            case 8:
               return false;
            default:
               throw new IllegalStateException("Illegal or unknown state: " + state);
         }
      }

      synchronized void decWaitingPos(final AreaDependentQueue.QueuedTask task, final List<PrioritisedExecutor.PrioritisedTask> toQueue) {
         int state = this.state;
         if (state == 2) {
            int newWaiting = --this.waitingPosCount;
            if (newWaiting == 0) {
               this.state = 4;
               toQueue.add(task.prioritisedTask);
            } else {
               if (newWaiting < 0) {
                  throw new IllegalStateException("Waiting pos too small");
               }
            }
         } else {
            throw new IllegalStateException("Illegal or unknown state: " + state);
         }
      }

      synchronized boolean tryMarkExecuting(final AreaDependentQueue.QueuedTask scheduledTask) {
         if (this.scheduledTask == scheduledTask && this.state == 4) {
            this.state = 8;
            return true;
         } else {
            return false;
         }
      }

      void finishTask(final AreaDependentQueue.QueuedTask scheduledTask) {
         List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
         ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

         try {
            this.areaDependentQueue.remove(this, scheduledTask, toQueue);
         } finally {
            this.areaDependentQueue.lock.unlock(lock);
         }

         int i = 0;

         for (int len = toQueue.size(); i < len; i++) {
            toQueue.get(i).queue();
         }
      }

      private void adjustPriority(
         final Priority priority,
         final long subOrder,
         final long stream,
         final List<PrioritisedExecutor.PrioritisedTask> toQueue,
         final List<PrioritisedExecutor.PrioritisedTask> toCancel
      ) {
         AreaDependentQueue.QueuedTask oldTask = this.scheduledTask;
         AreaDependentQueue.QueuedTask newTask = this.scheduledTask = new AreaDependentQueue.QueuedTask(
            priority, subOrder, stream, this, this.areaDependentQueue.executor
         );
         toCancel.add(oldTask.prioritisedTask);
         this.areaDependentQueue.adjust(this, oldTask, newTask, toQueue, toCancel);
         if (this.waitingPosCount == 0) {
            this.state = 4;
            toQueue.add(newTask.prioritisedTask);
         }
      }

      @Override
      public PrioritisedExecutor getExecutor() {
         return this.areaDependentQueue.executor;
      }

      @Override
      public boolean queue() {
         if (this.state != 1) {
            synchronized (this) {
               return false;
            }
         } else {
            List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
            ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

            PrioritisedExecutor.PrioritisedTask toQueue;
            try {
               synchronized (this) {
                  if (this.state != 1) {
                     return false;
                  }

                  boolean queue = this.areaDependentQueue.add(this, this.scheduledTask, toCancel);
                  if (queue) {
                     this.state = 4;
                     toQueue = this.scheduledTask.prioritisedTask;
                  } else {
                     this.state = 2;
                     toQueue = null;
                  }
               }
            } finally {
               this.areaDependentQueue.lock.unlock(lock);
            }

            int i = 0;

            for (int var14 = toCancel.size(); i < var14; i++) {
               toCancel.get(i).cancel();
            }

            if (toQueue != null) {
               toQueue.queue();
            }

            return true;
         }
      }

      @Override
      public boolean isQueued() {
         synchronized (this) {
            return this.state != 1 && this.state != 8;
         }
      }

      @Override
      public boolean cancel() {
         if (this.state == 1 || this.state == 8) {
            synchronized (this) {
               if (this.state == 1) {
                  this.state = 8;
                  return true;
               }

               if (this.state == 8) {
                  return false;
               }
            }
         }

         List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
         ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

         PrioritisedExecutor.PrioritisedTask toCancel;
         try {
            synchronized (this) {
               int state = this.state;
               switch (this.state) {
                  case 2:
                     this.state = 8;
                     toCancel = null;
                     break;
                  case 4:
                     this.state = 8;
                     toCancel = this.scheduledTask.prioritisedTask;
                     break;
                  case 8:
                     return false;
                  default:
                     throw new IllegalStateException("Unknown state: " + state);
               }
            }

            this.areaDependentQueue.remove(this, this.scheduledTask, toQueue);
         } finally {
            this.areaDependentQueue.lock.unlock(lock);
         }

         if (toCancel != null) {
            toCancel.cancel();
         }

         int i = 0;

         for (int len = toQueue.size(); i < len; i++) {
            toQueue.get(i).queue();
         }

         return true;
      }

      @Override
      public boolean execute() {
         if (!this.cancel()) {
            return false;
         }

         this.run.run();
         return true;
      }

      @Override
      public Priority getPriority() {
         synchronized (this) {
            return this.state == 8 ? Priority.COMPLETING : this.scheduledTask.priority;
         }
      }

      @Override
      public boolean setPriority(final Priority priority) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.priority == priority) {
                        return false;
                     }

                     this.scheduledTask.priority = priority;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.priority == priority) {
                        return false;
                     }

                     this.adjustPriority(priority, this.scheduledTask.subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public boolean raisePriority(final Priority priority) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.priority.isHigherOrEqualPriority(priority)) {
                        return false;
                     }

                     this.scheduledTask.priority = priority;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.priority.isHigherOrEqualPriority(priority)) {
                        return false;
                     }

                     this.adjustPriority(priority, this.scheduledTask.subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public boolean lowerPriority(final Priority priority) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.priority.isLowerOrEqualPriority(priority)) {
                        return false;
                     }

                     this.scheduledTask.priority = priority;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.priority.isLowerOrEqualPriority(priority)) {
                        return false;
                     }

                     this.adjustPriority(priority, this.scheduledTask.subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public long getSubOrder() {
         synchronized (this) {
            return this.state == 8 ? 0L : this.scheduledTask.subOrder;
         }
      }

      @Override
      public boolean setSubOrder(final long subOrder) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.subOrder == subOrder) {
                        return false;
                     }

                     this.scheduledTask.subOrder = subOrder;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.subOrder == subOrder) {
                        return false;
                     }

                     this.adjustPriority(this.scheduledTask.priority, subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public boolean raiseSubOrder(final long subOrder) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.subOrder >= subOrder) {
                        return false;
                     }

                     this.scheduledTask.subOrder = subOrder;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.subOrder >= subOrder) {
                        return false;
                     }

                     this.adjustPriority(this.scheduledTask.priority, subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public boolean lowerSubOrder(final long subOrder) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.subOrder <= subOrder) {
                        return false;
                     }

                     this.scheduledTask.subOrder = subOrder;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.subOrder <= subOrder) {
                        return false;
                     }

                     this.adjustPriority(this.scheduledTask.priority, subOrder, this.scheduledTask.stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public long getStream() {
         synchronized (this) {
            return this.state == 8 ? 0L : this.scheduledTask.stream;
         }
      }

      @Override
      public boolean setStream(final long stream) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.stream == stream) {
                        return false;
                     }

                     this.scheduledTask.stream = stream;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.stream == stream) {
                        return false;
                     }

                     this.adjustPriority(this.scheduledTask.priority, this.scheduledTask.subOrder, stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public boolean setPrioritySubOrderStream(final Priority priority, final long subOrder, final long stream) {
         switch (this.state) {
            case 1:
               synchronized (this) {
                  if (this.state == 1) {
                     if (this.scheduledTask.priority == priority && this.scheduledTask.subOrder == subOrder && this.scheduledTask.stream == stream) {
                        return false;
                     }

                     this.scheduledTask.priority = priority;
                     this.scheduledTask.subOrder = subOrder;
                     this.scheduledTask.stream = stream;
                     return true;
                  }
               }
            default:
               List<PrioritisedExecutor.PrioritisedTask> toQueue = new ArrayList<>();
               List<PrioritisedExecutor.PrioritisedTask> toCancel = new ArrayList<>();
               ReentrantAreaLock.Node lock = this.areaDependentQueue.lock.lock(this.minX, this.minY, this.maxX, this.maxY);

               try {
                  synchronized (this) {
                     if (this.state == 8) {
                        return false;
                     }

                     if (this.scheduledTask.priority == priority && this.scheduledTask.subOrder == subOrder && this.scheduledTask.stream == stream) {
                        return false;
                     }

                     this.adjustPriority(priority, subOrder, stream, toQueue, toCancel);
                  }
               } finally {
                  this.areaDependentQueue.lock.unlock(lock);
               }

               int i = 0;

               for (int len = toCancel.size(); i < len; i++) {
                  toCancel.get(i).cancel();
               }

               i = 0;

               for (int len = toQueue.size(); i < len; i++) {
                  toQueue.get(i).queue();
               }

               return true;
            case 8:
               synchronized (this) {
                  return false;
               }
         }
      }

      @Override
      public PrioritisedExecutor.PriorityState getPriorityState() {
         synchronized (this) {
            return this.state == 8
               ? null
               : new PrioritisedExecutor.PriorityState(this.scheduledTask.priority, this.scheduledTask.subOrder, this.scheduledTask.stream);
         }
      }
   }

   private static final class QueuedTask implements Runnable, Comparable<AreaDependentQueue.QueuedTask> {
      private Priority priority;
      private long subOrder;
      private long stream;
      private final AreaDependentQueue.PositionedTask positionedTask;
      private PrioritisedExecutor.PrioritisedTask prioritisedTask;

      private QueuedTask(
         final Priority priority,
         final long subOrder,
         final long stream,
         final AreaDependentQueue.PositionedTask positionedTask,
         final PrioritisedExecutor executor
      ) {
         super();
         this.priority = priority;
         this.subOrder = subOrder;
         this.stream = stream;
         this.positionedTask = positionedTask;
         this.prioritisedTask = executor.createTask(this, priority, subOrder, stream);
      }

      PrioritisedExecutor.PrioritisedTask swapTask(final PrioritisedExecutor executor) {
         PrioritisedExecutor.PrioritisedTask ret = this.prioritisedTask;
         this.prioritisedTask = executor.createTask(this, this.priority, this.subOrder, this.stream);
         return ret;
      }

      @Override
      public void run() {
         AreaDependentQueue.PositionedTask task = this.positionedTask;
         if (task.tryMarkExecuting(this)) {
            try {
               task.run.run();
            } finally {
               task.finishTask(this);
            }
         }
      }

      public int compareTo(final AreaDependentQueue.QueuedTask other) {
         int priorityCompare = this.priority.priority - other.priority.priority;
         if (priorityCompare != 0) {
            return priorityCompare;
         }

         if (this.positionedTask.areaDependentQueue.orderByLowerStream) {
            int streamCompare = Long.compare(this.stream, other.stream);
            if (streamCompare != 0) {
               return streamCompare;
            }
         }

         int subOrderCompare = Long.compare(this.subOrder, other.subOrder);
         return subOrderCompare != 0 ? subOrderCompare : Long.signum(this.positionedTask.taskId - other.positionedTask.taskId);
      }
   }
}
