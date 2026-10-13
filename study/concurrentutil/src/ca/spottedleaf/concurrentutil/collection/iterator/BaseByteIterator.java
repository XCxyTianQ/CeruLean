package ca.spottedleaf.concurrentutil.collection.iterator;

public abstract class BaseByteIterator extends BaseObjectIterator<Byte> {
   public BaseByteIterator() {
      super();
   }

   public final Byte next() {
      return this.nextByte();
   }

   public abstract byte nextByte();
}
