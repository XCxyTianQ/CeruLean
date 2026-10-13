package ca.spottedleaf.concurrentutil.collection.iterator;

public abstract class BaseShortIterator extends BaseObjectIterator<Short> {
   public BaseShortIterator() {
      super();
   }

   public final Short next() {
      return this.nextShort();
   }

   public abstract short nextShort();
}
