package ca.spottedleaf.concurrentutil.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

public final class ConcurrentUtil {
   public ConcurrentUtil() {
      super();
   }

   public static String genericToString(final Object object) {
      return object == null ? "null" : object.getClass().getName() + ":" + object.hashCode() + ":" + object.toString();
   }

   public static VarHandle getVarHandle(final Class<?> lookIn, final String fieldName, final Class<?> fieldType) {
      try {
         return MethodHandles.privateLookupIn(lookIn, MethodHandles.lookup()).findVarHandle(lookIn, fieldName, fieldType);
      } catch (Exception ex) {
         throw new RuntimeException(ex);
      }
   }

   public static VarHandle getStaticVarHandle(final Class<?> lookIn, final String fieldName, final Class<?> fieldType) {
      try {
         return MethodHandles.privateLookupIn(lookIn, MethodHandles.lookup()).findStaticVarHandle(lookIn, fieldName, fieldType);
      } catch (Exception ex) {
         throw new RuntimeException(ex);
      }
   }

   public static void backoff() {
      Thread.onSpinWait();
   }

   public static long exponentiallyBackoffSimple(final long counter) {
      for (long i = 0L; i < counter; i++) {
         backoff();
      }

      return counter + 1L;
   }

   public static long exponentiallyBackoffComplex(final long counter) {
      if (counter < 100L) {
         return exponentiallyBackoffSimple(counter);
      } else if (counter < 1200L) {
         Thread.yield();
         LockSupport.parkNanos(1000L);
         return counter + 1L;
      } else {
         Thread.yield();
         LockSupport.parkNanos(100000L * counter);
         return counter + 1L;
      }
   }

   public static long linearLongBackoff(long counter, final long scale, long max) {
      counter = Math.min(Long.MAX_VALUE, counter + 1L);
      max = Math.max(0L, max);
      if (scale <= 0L) {
         return counter;
      }

      long time = scale * counter;
      if (time > max || time / scale != counter) {
         time = max;
      }

      boolean interrupted = Thread.interrupted();
      if (time > 1000000L) {
         Thread.yield();
      }

      LockSupport.parkNanos(time);
      if (interrupted) {
         Thread.currentThread().interrupt();
      }

      return counter;
   }

   public static long linearLongBackoffDeadline(long counter, final long scale, long max, long deadline) {
      counter = Math.min(Long.MAX_VALUE, counter + 1L);
      max = Math.max(0L, max);
      if (scale <= 0L) {
         return counter;
      }

      long time = scale * counter;
      if (time / scale != counter) {
         counter--;
         time = max;
      } else if (time > max) {
         time = max;
      }

      long currTime = System.nanoTime();
      long diff = deadline - currTime;
      if (diff <= 0L) {
         return counter;
      }

      if (diff <= 1500000L) {
         time = 100000L;
      } else if (time > 1000000L) {
         Thread.yield();
      }

      boolean interrupted = Thread.interrupted();
      LockSupport.parkNanos(time);
      if (interrupted) {
         Thread.currentThread().interrupt();
      }

      return counter;
   }

   public static VarHandle getArrayHandle(final Class<?> type) {
      return MethodHandles.arrayElementVarHandle(type);
   }
}
