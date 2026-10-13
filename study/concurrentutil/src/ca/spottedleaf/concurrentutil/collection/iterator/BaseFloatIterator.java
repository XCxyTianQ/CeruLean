package ca.spottedleaf.concurrentutil.collection.iterator;

public abstract class BaseFloatIterator extends BaseObjectIterator<Float> {
   public BaseFloatIterator() {
      super();
   }

   public final Float next() {
      return this.nextFloat();
   }

   public abstract float nextFloat();
}
