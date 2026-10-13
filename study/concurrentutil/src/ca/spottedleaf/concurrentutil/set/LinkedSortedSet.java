package ca.spottedleaf.concurrentutil.set;

import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class LinkedSortedSet<E> implements Iterable<E> {
   public final Comparator<? super E> comparator;
   private LinkedSortedSet.Link<E> head;
   private LinkedSortedSet.Link<E> tail;

   public LinkedSortedSet() {
      this(Comparator.naturalOrder());
   }

   public LinkedSortedSet(final Comparator<? super E> comparator) {
      super();
      this.comparator = comparator;
   }

   public void clear() {
      this.head = this.tail = null;
   }

   public boolean isEmpty() {
      return this.head == null;
   }

   public E first() {
      LinkedSortedSet.Link<E> head = this.head;
      return head == null ? null : head.element;
   }

   public E last() {
      LinkedSortedSet.Link<E> tail = this.tail;
      return tail == null ? null : tail.element;
   }

   public boolean containsFirst(final E element) {
      Comparator<? super E> comparator = this.comparator;

      for (LinkedSortedSet.Link<E> curr = this.head; curr != null; curr = curr.next) {
         if (comparator.compare(element, curr.element) == 0) {
            return true;
         }
      }

      return false;
   }

   public boolean containsLast(final E element) {
      Comparator<? super E> comparator = this.comparator;

      for (LinkedSortedSet.Link<E> curr = this.tail; curr != null; curr = curr.prev) {
         if (comparator.compare(element, curr.element) == 0) {
            return true;
         }
      }

      return false;
   }

   private void removeNode(final LinkedSortedSet.Link<E> node) {
      LinkedSortedSet.Link<E> prev = node.prev;
      LinkedSortedSet.Link<E> next = node.next;
      node.element = null;
      node.prev = null;
      node.next = null;
      if (prev == null) {
         this.head = next;
      } else {
         prev.next = next;
      }

      if (next == null) {
         this.tail = prev;
      } else {
         next.prev = prev;
      }
   }

   public boolean remove(final LinkedSortedSet.Link<E> link) {
      if (link.element == null) {
         return false;
      }

      this.removeNode(link);
      return true;
   }

   public boolean removeFirst(final E element) {
      Comparator<? super E> comparator = this.comparator;

      for (LinkedSortedSet.Link<E> curr = this.head; curr != null; curr = curr.next) {
         if (comparator.compare(element, curr.element) == 0) {
            this.removeNode(curr);
            return true;
         }
      }

      return false;
   }

   public boolean removeLast(final E element) {
      Comparator<? super E> comparator = this.comparator;

      for (LinkedSortedSet.Link<E> curr = this.tail; curr != null; curr = curr.prev) {
         if (comparator.compare(element, curr.element) == 0) {
            this.removeNode(curr);
            return true;
         }
      }

      return false;
   }

   @Override
   public Iterator<E> iterator() {
      return new Iterator<E>() {
         private LinkedSortedSet.Link<E> next = LinkedSortedSet.this.head;

         @Override
         public boolean hasNext() {
            return this.next != null;
         }

         @Override
         public E next() {
            LinkedSortedSet.Link<E> next = this.next;
            if (next == null) {
               throw new NoSuchElementException();
            }

            this.next = next.next;
            return next.element;
         }
      };
   }

   public E pollFirst() {
      LinkedSortedSet.Link<E> head = this.head;
      if (head == null) {
         return null;
      }

      E ret = head.element;
      LinkedSortedSet.Link<E> next = head.next;
      this.head = next;
      if (next == null) {
         this.tail = null;
      } else {
         next.prev = null;
      }

      head.element = null;
      head.next = null;
      return ret;
   }

   public E pollLast() {
      LinkedSortedSet.Link<E> tail = this.tail;
      if (tail == null) {
         return null;
      }

      E ret = tail.element;
      LinkedSortedSet.Link<E> prev = tail.prev;
      this.tail = prev;
      if (prev == null) {
         this.head = null;
      } else {
         prev.next = null;
      }

      tail.element = null;
      tail.prev = null;
      return ret;
   }

   public LinkedSortedSet.Link<E> addLast(final E element) {
      Comparator<? super E> comparator = this.comparator;
      LinkedSortedSet.Link<E> curr = this.tail;
      if (curr != null) {
         int compare;
         while ((compare = comparator.compare(element, curr.element)) < 0) {
            LinkedSortedSet.Link<E> prev = curr;
            curr = curr.prev;
            if (curr == null) {
               return this.head = prev.prev = new LinkedSortedSet.Link<>(element, null, prev);
            }
         }

         if (compare != 0) {
            LinkedSortedSet.Link<E> next = curr.next;
            LinkedSortedSet.Link<E> insert = new LinkedSortedSet.Link<>(element, curr, next);
            curr.next = insert;
            if (next == null) {
               this.tail = insert;
            } else {
               next.prev = insert;
            }

            return insert;
         } else {
            return null;
         }
      } else {
         return this.head = this.tail = new LinkedSortedSet.Link<>(element);
      }
   }

   public LinkedSortedSet.Link<E> addFirst(final E element) {
      Comparator<? super E> comparator = this.comparator;
      LinkedSortedSet.Link<E> curr = this.head;
      if (curr != null) {
         int compare;
         while ((compare = comparator.compare(element, curr.element)) > 0) {
            LinkedSortedSet.Link<E> prev = curr;
            curr = curr.next;
            if (curr == null) {
               return this.tail = prev.next = new LinkedSortedSet.Link<>(element, prev, null);
            }
         }

         if (compare != 0) {
            LinkedSortedSet.Link<E> prev = curr.prev;
            LinkedSortedSet.Link<E> insert = new LinkedSortedSet.Link<>(element, prev, curr);
            curr.prev = insert;
            if (prev == null) {
               this.head = insert;
            } else {
               prev.next = insert;
            }

            return insert;
         } else {
            return null;
         }
      } else {
         return this.head = this.tail = new LinkedSortedSet.Link<>(element);
      }
   }

   public static final class Link<E> {
      private E element;
      private LinkedSortedSet.Link<E> prev;
      private LinkedSortedSet.Link<E> next;

      private Link(final E element) {
         super();
         this.element = element;
      }

      private Link(final E element, final LinkedSortedSet.Link<E> prev, final LinkedSortedSet.Link<E> next) {
         super();
         this.element = element;
         this.prev = prev;
         this.next = next;
      }
   }
}
