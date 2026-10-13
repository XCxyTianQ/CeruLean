package ca.spottedleaf.concurrentutil.collection.iterator;

import java.util.Objects;
import java.util.PrimitiveIterator.OfDouble;
import java.util.function.DoubleConsumer;

public abstract class BaseDoubleIterator extends BaseObjectIterator<Double> implements OfDouble {
   public BaseDoubleIterator() {
      super();
   }

   @Override
   public final Double next() {
      return this.nextDouble();
   }

   @Override
   public abstract double nextDouble();

   @Override
   public final void forEachRemaining(final DoubleConsumer action) {
      Objects.requireNonNull(action, "Action may not be null");

      while (this.hasNext()) {
         action.accept(this.nextDouble());
      }
   }
}
