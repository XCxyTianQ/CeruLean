package ca.spottedleaf.concurrentutil.collection.iterator;

public abstract class BaseBooleanIterator extends BaseObjectIterator<Boolean> {
   public BaseBooleanIterator() {
      super();
   }

   public final Boolean next() {
      return this.nextBoolean();
   }

   public abstract boolean nextBoolean();
}
