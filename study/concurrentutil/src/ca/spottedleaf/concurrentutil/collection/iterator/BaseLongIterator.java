package ca.spottedleaf.concurrentutil.collection.iterator;

import java.util.Objects;
import java.util.PrimitiveIterator.OfLong;
import java.util.function.LongConsumer;

public abstract class BaseLongIterator extends BaseObjectIterator<Long> implements OfLong {
   public BaseLongIterator() {
      super();
   }

   @Override
   public final Long next() {
      return this.nextLong();
   }

   @Override
   public abstract long nextLong();

   @Override
   public final void forEachRemaining(final LongConsumer action) {
      Objects.requireNonNull(action, "Action may not be null");

      while (this.hasNext()) {
         action.accept(this.nextLong());
      }
   }
}
