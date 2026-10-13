package ca.spottedleaf.concurrentutil.scheduler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public abstract class SchedulableTick {
   private static final AtomicLong ID_GENERATOR = new AtomicLong();
   public final long id = ID_GENERATOR.getAndIncrement();
   long scheduledStart = Long.MIN_VALUE;
   Object state;

   public SchedulableTick() {
      super();
   }

   boolean setState(final Object state) {
      synchronized (this) {
         if (this.state != null) {
            return false;
         }

         this.state = state;
         return true;
      }
   }

   protected final long getScheduledStart() {
      return this.scheduledStart;
   }

   protected final void setScheduledStart(final long value) {
      this.scheduledStart = value;
   }

   public abstract boolean runTick();

   public abstract boolean hasTasks();

   public abstract boolean runTasks(final BooleanSupplier canContinue);

   @Override
   public String toString() {
      return "SchedulableTick:{class=" + this.getClass().getName() + ",state=" + this.state + ",}";
   }
}
