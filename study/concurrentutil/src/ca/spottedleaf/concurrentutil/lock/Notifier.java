package ca.spottedleaf.concurrentutil.lock;

import java.util.concurrent.locks.AbstractQueuedLongSynchronizer;

public final class Notifier {
   private final Notifier.Lock lock;

   public Notifier(final boolean blocked) {
      super();
      this.lock = new Notifier.Lock(blocked);
   }

   public boolean notifyThreads() {
      return this.lock.indicateReady();
   }

   public boolean notifyThreadsAndBlock() {
      return this.lock.indicateReadyAndBlock();
   }

   public boolean block() {
      return this.lock.block();
   }

   public void waitUntilReady() {
      this.lock.waitUntilReady();
   }

   private static final class Lock extends AbstractQueuedLongSynchronizer {
      private static final long STATE_READY = 0L;
      private static final long STATE_NOT_READY = 1L;
      private static final long ACQUIRE_FAIL = -1L;
      private static final long ACQUIRE_SUCCESS = 1L;

      public Lock(final boolean blocked) {
         super();
         this.setState(blocked ? 1L : 0L);
      }

      @Override
      protected long tryAcquireShared(final long arg) {
         if ((arg & 1L) == 0L) {
            return 1L;
         } else {
            return (this.getState() ^ arg) == 0L ? -1L : 1L;
         }
      }

      @Override
      protected boolean tryRelease(final long arg) {
         long add = arg + 1L;
         long state = this.getState();
         return (state & 1L) == 1L && this.compareAndSetState(state, state + add);
      }

      public boolean indicateReady() {
         return this.release(0L);
      }

      public boolean indicateReadyAndBlock() {
         return this.release(1L);
      }

      public boolean block() {
         long state = this.getState();
         return (state & 1L) == 0L && this.compareAndSetState(state, state + 1L);
      }

      public void waitUntilReady() {
         this.acquireShared(this.getState());
      }
   }
}
