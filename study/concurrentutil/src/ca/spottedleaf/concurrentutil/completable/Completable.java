package ca.spottedleaf.concurrentutil.completable;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletableFuture.AsynchronousCompletionTask;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Completable<T> {
   private static final Logger LOGGER = LoggerFactory.getLogger(Completable.class);
   private static final Function<? super Throwable, ? extends Throwable> DEFAULT_EXCEPTION_HANDLER = thr -> {
      LOGGER.error("Unhandled exception during Completable operation", thr);
      return (Throwable)thr;
   };
   private static final Completable.Transform<?, ?> COMPLETED_STACK = new Completable.Transform<Object, Object>(null, null, null, null) {
      @Override
      public void run() {
      }
   };
   private volatile Completable.Transform<?, T> completeStack;
   private static final VarHandle COMPLETE_STACK_HANDLE = ConcurrentUtil.getVarHandle(Completable.class, "completeStack", Completable.Transform.class);
   private static final Object NULL_MASK = new Object();
   private volatile Object result;
   private static final VarHandle RESULT_HANDLE = ConcurrentUtil.getVarHandle(Completable.class, "result", Object.class);

   public static Executor getDefaultExecutor() {
      return ForkJoinPool.commonPool();
   }

   private Object getResultPlain() {
      return (Object)RESULT_HANDLE.get((Completable)this);
   }

   private Object getResultVolatile() {
      return (Object)RESULT_HANDLE.getVolatile((Completable)this);
   }

   private void pushStackOrRun(final Completable.Transform<?, T> push) {
      int failures = 0;

      for (Completable.Transform<?, T> curr = (Completable.Transform)COMPLETE_STACK_HANDLE.getVolatile((Completable)this); curr != COMPLETED_STACK; failures++) {
         push.next = curr;

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (curr
            == (
               curr = (Completable.Transform)COMPLETE_STACK_HANDLE.compareAndExchange(
                  (Completable)this, (Completable.Transform)curr, (Completable.Transform)push
               )
            )) {
            return;
         }

         push.next = null;
      }

      push.execute();
   }

   private void propagateStack() {
      for (Completable.Transform<?, T> topStack = (Completable.Transform)COMPLETE_STACK_HANDLE.getAndSet(
            (Completable)this, (Completable.Transform)COMPLETED_STACK
         );
         topStack != null;
         topStack = topStack.next
      ) {
         topStack.execute();
      }
   }

   private static Object maskNull(final Object res) {
      return res == null ? NULL_MASK : res;
   }

   private static Object unmaskNull(final Object res) {
      return res == NULL_MASK ? null : res;
   }

   private static Executor checkExecutor(final Executor executor) {
      return Objects.requireNonNull(executor, "Executor may not be null");
   }

   public Completable() {
      super();
   }

   private Completable(final Object complete) {
      super();
      COMPLETE_STACK_HANDLE.set((Completable)this, (Completable.Transform)COMPLETED_STACK);
      RESULT_HANDLE.setRelease((Completable)this, (Object)complete);
   }

   public static <T> Completable<T> completed(final T value) {
      return new Completable<>(maskNull(value));
   }

   public static <T> Completable<T> failed(final Throwable ex) {
      Objects.requireNonNull(ex, "Exception may not be null");
      return new Completable<>(new Completable.ExceptionResult(ex));
   }

   public static <T> Completable<T> supplied(final Supplier<T> supplier) {
      return supplied(supplier, DEFAULT_EXCEPTION_HANDLER);
   }

   public static <T> Completable<T> supplied(final Supplier<T> supplier, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      try {
         return completed(supplier.get());
      } catch (Throwable var6) {
         Throwable throwable = var6;

         Throwable complete;
         try {
            complete = exceptionHandler.apply(throwable);
         } catch (Throwable thr2) {
            var6.addSuppressed(thr2);
            complete = var6;
         }

         return failed(complete);
      }
   }

   public static <T> Completable<T> suppliedAsync(final Supplier<T> supplier, final Executor executor) {
      return suppliedAsync(supplier, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public static <T> Completable<T> suppliedAsync(
      final Supplier<T> supplier, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      final Completable<T> ret = new Completable<>();

      try {
         class AsyncSuppliedCompletable implements Runnable, AsynchronousCompletionTask {
            AsyncSuppliedCompletable() {
               super();
            }

            @Override
            public void run() {
               try {
                  ret.complete(supplier.get());
               } catch (Throwable throwable) {
                  Completable.failed(ret, throwable, exceptionHandler);
               }
            }
         }

         executor.execute(new AsyncSuppliedCompletable());
      } catch (Throwable throwable) {
         failed(ret, throwable, exceptionHandler);
      }

      return ret;
   }

   private boolean completeRaw(final Object value) {
      if ((Object)RESULT_HANDLE.getVolatile((Completable)this) == null && RESULT_HANDLE.compareAndSet((Completable)this, (Object)null, (Object)value)) {
         this.propagateStack();
         return true;
      } else {
         return false;
      }
   }

   public boolean complete(final T result) {
      return this.completeRaw(maskNull(result));
   }

   public boolean completeExceptionally(final Throwable exception) {
      Objects.requireNonNull(exception, "Exception may not be null");
      return this.completeRaw(new Completable.ExceptionResult(exception));
   }

   public boolean isDone() {
      return this.getResultVolatile() != null;
   }

   public boolean isNormallyComplete() {
      return this.getResultVolatile() != null && !(this.getResultVolatile() instanceof Completable.ExceptionResult);
   }

   public boolean isExceptionallyComplete() {
      return this.getResultVolatile() instanceof Completable.ExceptionResult;
   }

   public Throwable getException() {
      Object res = this.getResultVolatile();
      if (res == null) {
         return null;
      } else if (res instanceof Completable.ExceptionResult exRes) {
         return exRes.ex;
      } else {
         throw new IllegalStateException("Not completed exceptionally");
      }
   }

   public T getNow(final T dfl) throws CompletionException {
      Object res = this.getResultVolatile();
      if (res == null) {
         return dfl;
      } else if (res instanceof Completable.ExceptionResult exRes) {
         throw new CompletionException(exRes.ex);
      } else {
         return (T)unmaskNull(res);
      }
   }

   public T join() throws CompletionException {
      if (this.isDone()) {
         return this.getNow(null);
      }

      Completable.UnparkTransform<T> unparkTransform = new Completable.UnparkTransform<>(this, Thread.currentThread());
      this.pushStackOrRun(unparkTransform);
      boolean interuptted = false;

      while (!unparkTransform.isReleasable()) {
         try {
            ForkJoinPool.managedBlock(unparkTransform);
         } catch (InterruptedException ex) {
            interuptted = true;
         }
      }

      if (interuptted) {
         Thread.currentThread().interrupt();
      }

      return this.getNow(null);
   }

   public CompletableFuture<T> toFuture() {
      Object rawResult = this.getResultVolatile();
      if (rawResult != null) {
         return rawResult instanceof Completable.ExceptionResult exRes
            ? CompletableFuture.failedFuture(exRes.ex)
            : CompletableFuture.completedFuture((T)unmaskNull(rawResult));
      }

      final CompletableFuture<T> ret = new CompletableFuture<>();

      class ToFuture implements BiConsumer<T, Throwable> {
         ToFuture() {
            super();
         }

         public void accept(final T res, final Throwable ex) {
            if (ex != null) {
               ret.completeExceptionally(ex);
            } else {
               ret.complete(res);
            }
         }
      }

      this.whenComplete(new ToFuture());
      return ret;
   }

   public static <T> Completable<T> fromFuture(final CompletionStage<T> stage) {
      final Completable<T> ret = new Completable<>();

      class FromFuture implements BiConsumer<T, Throwable> {
         FromFuture() {
            super();
         }

         public void accept(final T res, final Throwable ex) {
            if (ex != null) {
               ret.completeExceptionally(ex);
            } else {
               ret.complete(res);
            }
         }
      }

      stage.whenComplete(new FromFuture());
      return ret;
   }

   public <U> Completable<U> thenApply(final Function<? super T, ? extends U> function) {
      return this.thenApply(function, DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> thenApply(final Function<? super T, ? extends U> function, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<U> ret = new Completable();
      this.pushStackOrRun(new Completable.ApplyTransform<>(null, this, ret, exceptionHandler, function));
      return ret;
   }

   public <U> Completable<U> thenApplyAsync(final Function<? super T, ? extends U> function) {
      return this.thenApplyAsync(function, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> thenApplyAsync(final Function<? super T, ? extends U> function, final Executor executor) {
      return this.thenApplyAsync(function, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> thenApplyAsync(
      final Function<? super T, ? extends U> function, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<U> ret = new Completable();
      this.pushStackOrRun(new Completable.ApplyTransform<>(checkExecutor(executor), this, ret, exceptionHandler, function));
      return ret;
   }

   public Completable<Void> thenAccept(final Consumer<? super T> consumer) {
      return this.thenAccept(consumer, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenAccept(final Consumer<? super T> consumer, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      Objects.requireNonNull(consumer, "Consumer may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<Void> ret = new Completable<>();
      this.pushStackOrRun(new Completable.AcceptTransform<>(null, this, ret, exceptionHandler, consumer));
      return ret;
   }

   public Completable<Void> thenAcceptAsync(final Consumer<? super T> consumer) {
      return this.thenAcceptAsync(consumer, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenAcceptAsync(final Consumer<? super T> consumer, final Executor executor) {
      return this.thenAcceptAsync(consumer, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenAcceptAsync(
      final Consumer<? super T> consumer, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(consumer, "Consumer may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<Void> ret = new Completable<>();
      this.pushStackOrRun(new Completable.AcceptTransform<>(checkExecutor(executor), this, ret, exceptionHandler, consumer));
      return ret;
   }

   public Completable<Void> thenRun(final Runnable run) {
      return this.thenRun(run, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenRun(final Runnable run, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      Objects.requireNonNull(run, "Run may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<Void> ret = new Completable<>();
      this.pushStackOrRun(new Completable.RunTransform<>(null, this, ret, exceptionHandler, run));
      return ret;
   }

   public Completable<Void> thenRunAsync(final Runnable run) {
      return this.thenRunAsync(run, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenRunAsync(final Runnable run, final Executor executor) {
      return this.thenRunAsync(run, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<Void> thenRunAsync(final Runnable run, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      Objects.requireNonNull(run, "Run may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<Void> ret = new Completable<>();
      this.pushStackOrRun(new Completable.RunTransform<>(checkExecutor(executor), this, ret, exceptionHandler, run));
      return ret;
   }

   public <U> Completable<U> handle(final BiFunction<? super T, ? super Throwable, ? extends U> function) {
      return this.handle(function, DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> handle(
      final BiFunction<? super T, ? super Throwable, ? extends U> function, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<U> ret = new Completable();
      this.pushStackOrRun(new Completable.HandleTransform<>(null, this, ret, exceptionHandler, function));
      return ret;
   }

   public <U> Completable<U> handleAsync(final BiFunction<? super T, ? super Throwable, ? extends U> function) {
      return this.handleAsync(function, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> handleAsync(final BiFunction<? super T, ? super Throwable, ? extends U> function, final Executor executor) {
      return this.handleAsync(function, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public <U> Completable<U> handleAsync(
      final BiFunction<? super T, ? super Throwable, ? extends U> function,
      final Executor executor,
      final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<U> ret = new Completable();
      this.pushStackOrRun(new Completable.HandleTransform<>(checkExecutor(executor), this, ret, exceptionHandler, function));
      return ret;
   }

   public Completable<T> whenComplete(final BiConsumer<? super T, ? super Throwable> consumer) {
      return this.whenComplete(consumer, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> whenComplete(
      final BiConsumer<? super T, ? super Throwable> consumer, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(consumer, "Consumer may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<T> ret = new Completable<>();
      this.pushStackOrRun(new Completable.WhenTransform<>(null, this, ret, exceptionHandler, consumer));
      return ret;
   }

   public Completable<T> whenCompleteAsync(final BiConsumer<? super T, ? super Throwable> consumer) {
      return this.whenCompleteAsync(consumer, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> whenCompleteAsync(final BiConsumer<? super T, ? super Throwable> consumer, final Executor executor) {
      return this.whenCompleteAsync(consumer, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> whenCompleteAsync(
      final BiConsumer<? super T, ? super Throwable> consumer, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(consumer, "Consumer may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<T> ret = new Completable<>();
      this.pushStackOrRun(new Completable.WhenTransform<>(checkExecutor(executor), this, ret, exceptionHandler, consumer));
      return ret;
   }

   public Completable<T> exceptionally(final Function<Throwable, ? extends T> function) {
      return this.exceptionally(function, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> exceptionally(final Function<Throwable, ? extends T> function, final Function<? super Throwable, ? extends Throwable> exceptionHandler) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<T> ret = new Completable<>();
      this.pushStackOrRun(new Completable.ExceptionallyTransform<>(null, this, ret, exceptionHandler, function));
      return ret;
   }

   public Completable<T> exceptionallyAsync(final Function<Throwable, ? extends T> function) {
      return this.exceptionallyAsync(function, getDefaultExecutor(), DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> exceptionallyAsync(final Function<Throwable, ? extends T> function, final Executor executor) {
      return this.exceptionallyAsync(function, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public Completable<T> exceptionallyAsync(
      final Function<Throwable, ? extends T> function, final Executor executor, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(function, "Function may not be null");
      Objects.requireNonNull(exceptionHandler, "Exception handler may not be null");
      Completable<T> ret = new Completable<>();
      this.pushStackOrRun(new Completable.ExceptionallyTransform<>(checkExecutor(executor), this, ret, exceptionHandler, function));
      return ret;
   }

   public <R, O> Completable<R> thenCombine(final Completable<O> other, final BiFunction<T, O, R> combineFunction) {
      return this.thenCombine(other, combineFunction, DEFAULT_EXCEPTION_HANDLER);
   }

   public <R, O> Completable<R> thenCombine(
      final Completable<O> other, final BiFunction<T, O, R> combineFunction, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(other, "Other may not be null");
      Objects.requireNonNull(combineFunction, "Combine function may not be null");
      Completable<R> ret = new Completable();
      this.whenComplete((thisVal, thr) -> {
         if (thr != null) {
            failed(ret, thr, exceptionHandler);
         } else {
            other.whenComplete((otherVal, thr2) -> {
               if (thr2 != null) {
                  failed(ret, thr2, exceptionHandler);
               } else {
                  try {
                     ret.complete(combineFunction.apply((T)thisVal, (O)otherVal));
                  } catch (Throwable thr3) {
                     failed(ret, thr3, exceptionHandler);
                  }
               }
            }, exceptionHandler);
         }
      }, exceptionHandler);
      return ret;
   }

   public <R, O> Completable<R> thenCombineAsync(final Completable<O> other, final BiFunction<T, O, R> combineFunction, final Executor executor) {
      return this.thenCombineAsync(other, combineFunction, executor, DEFAULT_EXCEPTION_HANDLER);
   }

   public <R, O> Completable<R> thenCombineAsync(
      final Completable<O> other,
      final BiFunction<T, O, R> combineFunction,
      final Executor executor,
      final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Objects.requireNonNull(other, "Other may not be null");
      Objects.requireNonNull(combineFunction, "Combine function may not be null");
      Completable<R> ret = new Completable();
      this.whenComplete((thisVal, thr) -> {
         if (thr != null) {
            failed(ret, thr, exceptionHandler);
         } else {
            other.whenCompleteAsync((otherVal, thr2) -> {
               if (thr2 != null) {
                  failed(ret, thr2, exceptionHandler);
               } else {
                  try {
                     ret.complete(combineFunction.apply((T)thisVal, (O)otherVal));
                  } catch (Throwable thr3) {
                     failed(ret, thr3, exceptionHandler);
                  }
               }
            }, executor, exceptionHandler);
         }
      }, exceptionHandler);
      return ret;
   }

   private static <T> void failed(
      final Completable<T> future, final Throwable throwable, final Function<? super Throwable, ? extends Throwable> exceptionHandler
   ) {
      Throwable complete;
      try {
         complete = exceptionHandler.apply(throwable);
      } catch (Throwable thr2) {
         throwable.addSuppressed(thr2);
         complete = throwable;
      }

      future.completeExceptionally(complete);
   }

   private static final class AcceptTransform<T> extends Completable.Transform<Void, T> {
      private final Consumer<? super T> consumer;

      public AcceptTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<Void> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final Consumer<? super T> consumer
      ) {
         super(executor, from, to, exceptionHandler);
         this.consumer = consumer;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.to.completeExceptionally(exRes.ex);
            } else {
               this.consumer.accept((T)Completable.unmaskNull(result));
               this.to.complete(null);
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }

   private static final class ApplyTransform<U, T> extends Completable.Transform<U, T> {
      private final Function<? super T, ? extends U> function;

      public ApplyTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<U> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final Function<? super T, ? extends U> function
      ) {
         super(executor, from, to, exceptionHandler);
         this.function = function;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.to.completeExceptionally(exRes.ex);
            } else {
               this.to.complete((U)this.function.apply((T)Completable.unmaskNull(result)));
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }

   private static final class ExceptionResult {
      public final Throwable ex;

      public ExceptionResult(final Throwable ex) {
         super();
         this.ex = ex;
      }
   }

   private static final class ExceptionallyTransform<T> extends Completable.Transform<T, T> {
      private final Function<Throwable, ? extends T> function;

      public ExceptionallyTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<T> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final Function<Throwable, ? extends T> function
      ) {
         super(executor, from, to, exceptionHandler);
         this.function = function;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.to.complete((T)this.function.apply(exRes.ex));
            } else {
               this.to.complete((T)Completable.unmaskNull(result));
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }

   private static final class HandleTransform<U, T> extends Completable.Transform<U, T> {
      private final BiFunction<? super T, ? super Throwable, ? extends U> function;

      public HandleTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<U> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final BiFunction<? super T, ? super Throwable, ? extends U> function
      ) {
         super(executor, from, to, exceptionHandler);
         this.function = function;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.to.complete((U)this.function.apply(null, exRes.ex));
            } else {
               this.to.complete((U)this.function.apply((T)Completable.unmaskNull(result), null));
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }

   private static final class RunTransform<T> extends Completable.Transform<Void, T> {
      private final Runnable run;

      public RunTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<Void> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final Runnable run
      ) {
         super(executor, from, to, exceptionHandler);
         this.run = run;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.to.completeExceptionally(exRes.ex);
            } else {
               this.run.run();
               this.to.complete(null);
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }

   private abstract static class Transform<U, T> implements Runnable, AsynchronousCompletionTask {
      private Completable.Transform<?, T> next;
      private final Executor executor;
      protected final Completable<T> from;
      protected final Completable<U> to;
      protected final Function<? super Throwable, ? extends Throwable> exceptionHandler;

      protected Transform(
         final Executor executor, final Completable<T> from, final Completable<U> to, final Function<? super Throwable, ? extends Throwable> exceptionHandler
      ) {
         super();
         this.executor = executor;
         this.from = from;
         this.to = to;
         this.exceptionHandler = exceptionHandler;
      }

      @Override
      public abstract void run();

      protected final void failed(final Throwable throwable) {
         Completable.failed(this.to, throwable, this.exceptionHandler);
      }

      public void execute() {
         if (this.executor == null) {
            this.run();
         } else {
            try {
               this.executor.execute(this);
            } catch (Throwable throwable) {
               this.failed(throwable);
            }
         }
      }
   }

   private static final class UnparkTransform<T> extends Completable.Transform<Void, T> implements ManagedBlocker {
      private volatile Thread thread;

      public UnparkTransform(final Completable<T> from, final Thread target) {
         super(null, from, null, null);
         this.thread = target;
      }

      @Override
      public void run() {
         Thread t = this.thread;
         this.thread = null;
         LockSupport.unpark(t);
      }

      @Override
      public boolean block() throws InterruptedException {
         while (!this.isReleasable()) {
            if (Thread.interrupted()) {
               throw new InterruptedException();
            }

            LockSupport.park(this);
         }

         return true;
      }

      @Override
      public boolean isReleasable() {
         return this.thread == null;
      }
   }

   private static final class WhenTransform<T> extends Completable.Transform<T, T> {
      private final BiConsumer<? super T, ? super Throwable> consumer;

      public WhenTransform(
         final Executor executor,
         final Completable<T> from,
         final Completable<T> to,
         final Function<? super Throwable, ? extends Throwable> exceptionHandler,
         final BiConsumer<? super T, ? super Throwable> consumer
      ) {
         super(executor, from, to, exceptionHandler);
         this.consumer = consumer;
      }

      @Override
      public void run() {
         Object result = this.from.getResultPlain();

         try {
            if (result instanceof Completable.ExceptionResult exRes) {
               this.consumer.accept(null, exRes.ex);
               this.to.completeExceptionally(exRes.ex);
            } else {
               T unmasked = (T)Completable.unmaskNull(result);
               this.consumer.accept(unmasked, null);
               this.to.complete(unmasked);
            }
         } catch (Throwable throwable) {
            this.failed(throwable);
         }
      }
   }
}
