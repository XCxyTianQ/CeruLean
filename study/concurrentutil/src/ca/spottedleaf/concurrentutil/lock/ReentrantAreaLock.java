package ca.spottedleaf.concurrentutil.lock;

import ca.spottedleaf.common.util.IntPairUtil;
import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import java.util.Objects;

public final class ReentrantAreaLock {
   public final int coordinateShift;
   private final ConcurrentChainedLong2ReferenceHashTable<ReentrantAreaLock.Node> nodes = ConcurrentChainedLong2ReferenceHashTable.createWithCapacity(128, 0.2F);

   public ReentrantAreaLock(final int coordinateShift) {
      super();
      this.coordinateShift = coordinateShift;
   }

   public boolean isHeldByCurrentThread(final int x, final int y) {
      Thread currThread = Thread.currentThread();
      int shift = this.coordinateShift;
      int sectionX = x >> shift;
      int sectionY = y >> shift;
      long coordinate = IntPairUtil.key(sectionX, sectionY);
      ReentrantAreaLock.Node node = this.nodes.get(coordinate);
      return node != null && node.thread == currThread;
   }

   public boolean isHeldByCurrentThread(final int centerX, final int centerY, final int radius) {
      return this.isHeldByCurrentThread(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
   }

   public boolean isHeldByCurrentThread(final int fromX, final int fromY, final int toX, final int toY) {
      if (fromX <= toX && fromY <= toY) {
         Thread currThread = Thread.currentThread();
         int shift = this.coordinateShift;
         int fromSectionX = fromX >> shift;
         int fromSectionY = fromY >> shift;
         int toSectionX = toX >> shift;
         int toSectionY = toY >> shift;

         for (int currY = fromSectionY; currY <= toSectionY; currY++) {
            for (int currX = fromSectionX; currX <= toSectionX; currX++) {
               long coordinate = IntPairUtil.key(currX, currY);
               ReentrantAreaLock.Node node = this.nodes.get(coordinate);
               if (node == null || node.thread != currThread) {
                  return false;
               }
            }
         }

         return true;
      } else {
         throw new IllegalArgumentException();
      }
   }

   public ReentrantAreaLock.Node tryLock(final int x, final int y) {
      return this.tryLock(x, y, x, y);
   }

   public ReentrantAreaLock.Node tryLock(final int centerX, final int centerY, final int radius) {
      return this.tryLock(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
   }

   public ReentrantAreaLock.Node tryLock(final int fromX, final int fromY, final int toX, final int toY) {
      if (fromX <= toX && fromY <= toY) {
         Thread currThread = Thread.currentThread();
         int shift = this.coordinateShift;
         int fromSectionX = fromX >> shift;
         int fromSectionY = fromY >> shift;
         int toSectionX = toX >> shift;
         int toSectionY = toY >> shift;
         long[] areaAffected = new long[(toSectionX - fromSectionX + 1) * (toSectionY - fromSectionY + 1)];
         int areaAffectedLen = 0;
         ReentrantAreaLock.Node ret = new ReentrantAreaLock.Node(this, areaAffected, currThread);
         boolean failed = false;

         for (int currY = fromSectionY; currY <= toSectionY; currY++) {
            for (int currX = fromSectionX; currX <= toSectionX; currX++) {
               long coordinate = IntPairUtil.key(currX, currY);
               ReentrantAreaLock.Node prev = this.nodes.putIfAbsent(coordinate, ret);
               if (prev == null) {
                  areaAffected[areaAffectedLen++] = coordinate;
               } else if (prev.thread != currThread) {
                  failed = true;
                  break;
               }
            }
         }

         if (!failed) {
            return ret;
         }

         if (areaAffectedLen != 0) {
            for (int i = 0; i < areaAffectedLen; i++) {
               long key = areaAffected[i];
               if (this.nodes.remove(key) != ret) {
                  throw new IllegalStateException();
               }
            }

            int var20 = false;
            ret.notifier.notifyThreads();
         }

         return null;
      } else {
         throw new IllegalArgumentException();
      }
   }

   public ReentrantAreaLock.Node lock(final int x, final int y) {
      Thread currThread = Thread.currentThread();
      int shift = this.coordinateShift;
      int sectionX = x >> shift;
      int sectionY = y >> shift;
      long coordinate = IntPairUtil.key(sectionX, sectionY);
      long[] areaAffected = new long[]{coordinate};
      ReentrantAreaLock.Node ret = new ReentrantAreaLock.Node(this, areaAffected, currThread);

      while (true) {
         ReentrantAreaLock.Node prev = this.nodes.putIfAbsent(coordinate, ret);
         if (prev == null) {
            ret.areaAffectedLen = 1;
            return ret;
         }

         if (prev.thread == currThread) {
            return ret;
         }

         ReentrantAreaLock.Node park = prev;
         park.notifier.waitUntilReady();
      }
   }

   public ReentrantAreaLock.Node lock(final int centerX, final int centerY, final int radius) {
      return this.lock(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
   }

   public ReentrantAreaLock.Node lock(final int fromX, final int fromY, final int toX, final int toY) {
      if (fromX <= toX && fromY <= toY) {
         Thread currThread = Thread.currentThread();
         int shift = this.coordinateShift;
         int fromSectionX = fromX >> shift;
         int fromSectionY = fromY >> shift;
         int toSectionX = toX >> shift;
         int toSectionY = toY >> shift;
         if ((fromSectionX ^ toSectionX | fromSectionY ^ toSectionY) == 0) {
            return this.lock(fromX, fromY);
         }

         long[] areaAffected = new long[(toSectionX - fromSectionX + 1) * (toSectionY - fromSectionY + 1)];
         int areaAffectedLen = 0;
         ReentrantAreaLock.Node ret = new ReentrantAreaLock.Node(this, areaAffected, currThread);

         ReentrantAreaLock.Node park;
         boolean alreadyOwned;
         boolean allOwned;
         do {
            park = null;
            boolean addedToArea = false;
            alreadyOwned = false;
            allOwned = true;

            for (int currY = fromSectionY; currY <= toSectionY; currY++) {
               for (int currX = fromSectionX; currX <= toSectionX; currX++) {
                  long coordinate = IntPairUtil.key(currX, currY);
                  ReentrantAreaLock.Node prev = this.nodes.putIfAbsent(coordinate, ret);
                  if (prev == null) {
                     addedToArea = true;
                     allOwned = false;
                     areaAffected[areaAffectedLen++] = coordinate;
                  } else if (prev.thread != currThread) {
                     park = prev;
                     alreadyOwned = true;
                     break;
                  }
               }
            }

            if (park != null && addedToArea || park == null && alreadyOwned && !allOwned) {
               for (int i = 0; i < areaAffectedLen; i++) {
                  long key = areaAffected[i];
                  if (this.nodes.remove(key) != ret) {
                     throw new IllegalStateException();
                  }
               }

               areaAffectedLen = 0;
               ret.notifier.notifyThreadsAndBlock();
            }
         } while (park != null);

         if (alreadyOwned && !allOwned) {
            ret.notifier.notifyThreads();
            throw new IllegalStateException("Improper lock usage: Should never acquire intersecting areas");
         } else {
            ret.areaAffectedLen = areaAffectedLen;
            return ret;
         }
      } else {
         throw new IllegalArgumentException();
      }
   }

   public void unlock(final ReentrantAreaLock.Node node) {
      if (node.lock != this) {
         throw new IllegalStateException("Unlock target lock mismatch");
      }

      long[] areaAffected = node.areaAffected;
      int areaAffectedLen = node.areaAffectedLen;
      if (areaAffectedLen != 0) {
         Objects.checkFromToIndex(0, areaAffectedLen, areaAffected.length);

         for (int i = 0; i < areaAffectedLen; i++) {
            long coordinate = areaAffected[i];
            if (this.nodes.remove(coordinate, node) != node) {
               throw new IllegalStateException();
            }
         }

         node.notifier.notifyThreads();
      }
   }

   public static final class Node {
      private final ReentrantAreaLock lock;
      private final Notifier notifier = new Notifier(true);
      private final long[] areaAffected;
      private int areaAffectedLen;
      private final Thread thread;

      private Node(final ReentrantAreaLock lock, final long[] areaAffected, final Thread thread) {
         super();
         this.lock = lock;
         this.areaAffected = areaAffected;
         this.thread = thread;
      }

      @Override
      public String toString() {
         return "Node{areaAffected=" + IntPairUtil.toString(this.areaAffected, 0, this.areaAffectedLen) + ", thread=" + this.thread + "}";
      }
   }
}
