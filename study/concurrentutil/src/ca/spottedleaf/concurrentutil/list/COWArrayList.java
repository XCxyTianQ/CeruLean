package ca.spottedleaf.concurrentutil.list;

import java.lang.reflect.Array;
import java.util.Arrays;

public final class COWArrayList<E> {
   private volatile E[] array;

   public COWArrayList(final Class<E> clazz) {
      super();
      this.array = (E[])((Object[])Array.newInstance(clazz, 0));
   }

   public E[] getArray() {
      return this.array;
   }

   public void clear() {
      synchronized (this) {
         this.array = Arrays.copyOf(this.array, 0);
      }
   }

   public void set(final E[] array) {
      if (array.getClass() != this.array.getClass()) {
         throw new IllegalStateException();
      }

      synchronized (this) {
         this.array = array;
      }
   }

   public boolean contains(final E test) {
      for (E elem : this.array) {
         if (elem == test) {
            return true;
         }
      }

      return false;
   }

   public E getFirst() {
      E[] array = this.array;
      return array.length == 0 ? null : array[0];
   }

   public E getLast() {
      E[] array = this.array;
      return array.length == 0 ? null : array[array.length - 1];
   }

   public void add(final E element) {
      synchronized (this) {
         E[] array = this.array;
         E[] copy = Arrays.copyOf(array, array.length + 1);
         copy[array.length] = element;
         this.array = copy;
      }
   }

   public boolean remove(final E element) {
      synchronized (this) {
         E[] array = this.array;
         int index = -1;
         int i = 0;

         for (int len = array.length; i < len; i++) {
            if (array[i] == element) {
               index = i;
               break;
            }
         }

         if (index == -1) {
            return false;
         }

         E[] copy = (E[])((Object[])Array.newInstance(array.getClass().getComponentType(), array.length - 1));
         System.arraycopy(array, 0, copy, 0, index);
         System.arraycopy(array, index + 1, copy, index, array.length - 1 - index);
         this.array = copy;
         return true;
      }
   }

   public boolean removeLast(final E element) {
      synchronized (this) {
         E[] array = this.array;
         int index = -1;

         for (int i = array.length - 1; i >= 0; i--) {
            if (array[i] == element) {
               index = i;
               break;
            }
         }

         if (index == -1) {
            return false;
         }

         E[] copy = (E[])((Object[])Array.newInstance(array.getClass().getComponentType(), array.length - 1));
         System.arraycopy(array, 0, copy, 0, index);
         System.arraycopy(array, index + 1, copy, index, array.length - 1 - index);
         this.array = copy;
         return true;
      }
   }
}
