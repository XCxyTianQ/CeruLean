package ca.spottedleaf.concurrentutil.completable;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CallbackCompletable<T> {
   private static final Logger LOGGER = LoggerFactory.getLogger(CallbackCompletable.class);
   private final MultiThreadedQueue<BiConsumer<T, Throwable>> waiters = new MultiThreadedQueue<>();
   private T result;
   private Throwable throwable;
   private volatile boolean completed;

   public CallbackCompletable() {
      super();
   }

   public boolean isCompleted() {
      return this.completed;
   }

   public T getResult() {
      return this.result;
   }

   public Throwable getThrowable() {
      return this.throwable;
   }

   public Cancellable addAsynchronousWaiter(final BiConsumer<T, Throwable> consumer) {
      return this.waiters.add(consumer) ? new CallbackCompletable.CancellableImpl(consumer) : null;
   }

   private void completeAllWaiters(final T result, final Throwable throwable) {
      this.completed = true;

      BiConsumer<T, Throwable> waiter;
      while ((waiter = this.waiters.pollOrBlockAdds()) != null) {
         this.completeWaiter(waiter, result, throwable);
      }
   }

   private void completeWaiter(final BiConsumer<T, Throwable> consumer, final T result, final Throwable throwable) {
      try {
         consumer.accept(result, throwable);
      } catch (Throwable throwable2) {
         LOGGER.error("Failed to complete callback " + ConcurrentUtil.genericToString(consumer), throwable2);
      }
   }

   public Cancellable addWaiter(final BiConsumer<T, Throwable> consumer) {
      if (this.waiters.add(consumer)) {
         return new CallbackCompletable.CancellableImpl(consumer);
      }

      this.completeWaiter(consumer, this.result, this.throwable);
      return new CallbackCompletable.CancellableImpl(consumer);
   }

   public void complete(final T result) {
      this.result = result;
      this.completeAllWaiters(result, null);
   }

   public void completeWithThrowable(final Throwable throwable) {
      if (throwable == null) {
         throw new NullPointerException("Throwable cannot be null");
      }

      this.throwable = throwable;
      this.completeAllWaiters(null, throwable);
   }

   private final class CancellableImpl implements Cancellable {
      private final BiConsumer<T, Throwable> waiter;

      private CancellableImpl(final BiConsumer<T, Throwable> waiter) {
         super();
         this.waiter = waiter;
      }

      @Override
      public boolean cancel() {
         return CallbackCompletable.this.waiters.remove(this.waiter);
      }
   }
}
