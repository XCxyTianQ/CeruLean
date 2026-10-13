package ca.spottedleaf.concurrentutil.util;

import java.lang.invoke.VarHandle;

public final class LazyRunnable implements Runnable {
   private volatile Runnable toRun;
   private static final VarHandle TO_RUN_HANDLE = ConcurrentUtil.getVarHandle(LazyRunnable.class, "toRun", Runnable.class);

   public LazyRunnable() {
      super();
   }

   public LazyRunnable(final Runnable run) {
      super();
      TO_RUN_HANDLE.setRelease((LazyRunnable)this, (Runnable)run);
   }

   public void setRunnable(final Runnable run) {
      Runnable prev = (Runnable)TO_RUN_HANDLE.compareAndExchange((LazyRunnable)this, (Runnable)((Runnable)null), (Runnable)run);
      if (prev != null) {
         throw new IllegalStateException("Runnable already set");
      }
   }

   @Override
   public void run() {
      (Runnable)TO_RUN_HANDLE.getVolatile((LazyRunnable)this).run();
   }
}
