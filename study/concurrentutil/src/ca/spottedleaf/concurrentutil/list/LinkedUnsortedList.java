package ca.spottedleaf.concurrentutil.list;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class LinkedUnsortedList<E> implements Iterable<E> {
   private LinkedUnsortedList.Link<E> head;
   private LinkedUnsortedList.Link<E> tail;

   public LinkedUnsortedList() {
      super();
   }

   public void clear() {
      this.head = this.tail = null;
   }

   public boolean isEmpty() {
      return this.head == null;
   }

   public E first() {
      LinkedUnsortedList.Link<E> head = this.head;
      return head == null ? null : head.element;
   }

   public E last() {
      LinkedUnsortedList.Link<E> tail = this.tail;
      return tail == null ? null : tail.element;
   }

   public boolean containsFirst(final E element) {
      for (LinkedUnsortedList.Link<E> curr = this.head; curr != null; curr = curr.next) {
         if (Objects.equals(element, curr.element)) {
            return true;
         }
      }

      return false;
   }

   public boolean containsLast(final E element) {
      for (LinkedUnsortedList.Link<E> curr = this.tail; curr != null; curr = curr.prev) {
         if (Objects.equals(element, curr.element)) {
            return true;
         }
      }

      return false;
   }

   private void removeNode(final LinkedUnsortedList.Link<E> node) {
      LinkedUnsortedList.Link<E> prev = node.prev;
      LinkedUnsortedList.Link<E> next = node.next;
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

   public boolean remove(final LinkedUnsortedList.Link<E> link) {
      if (link.element == null) {
         return false;
      }

      this.removeNode(link);
      return true;
   }

   public boolean removeFirst(final E element) {
      for (LinkedUnsortedList.Link<E> curr = this.head; curr != null; curr = curr.next) {
         if (Objects.equals(element, curr.element)) {
            this.removeNode(curr);
            return true;
         }
      }

      return false;
   }

   public boolean removeLast(final E element) {
      for (LinkedUnsortedList.Link<E> curr = this.tail; curr != null; curr = curr.prev) {
         if (Objects.equals(element, curr.element)) {
            this.removeNode(curr);
            return true;
         }
      }

      return false;
   }

   @Override
   public Iterator<E> iterator() {
      return new Iterator<E>() {
         private LinkedUnsortedList.Link<E> next = LinkedUnsortedList.this.head;

         @Override
         public boolean hasNext() {
            return this.next != null;
         }

         @Override
         public E next() {
            LinkedUnsortedList.Link<E> next = this.next;
            if (next == null) {
               throw new NoSuchElementException();
            }

            this.next = next.next;
            return next.element;
         }
      };
   }

   public E pollFirst() {
      LinkedUnsortedList.Link<E> head = this.head;
      if (head == null) {
         return null;
      }

      E ret = head.element;
      LinkedUnsortedList.Link<E> next = head.next;
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
      LinkedUnsortedList.Link<E> tail = this.tail;
      if (tail == null) {
         return null;
      }

      E ret = tail.element;
      LinkedUnsortedList.Link<E> prev = tail.prev;
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

   public LinkedUnsortedList.Link<E> addLast(final E element) {
      LinkedUnsortedList.Link<E> curr = this.tail;
      return curr != null ? (this.tail = new LinkedUnsortedList.Link<>(element, curr, null)) : (this.head = this.tail = new LinkedUnsortedList.Link<>(element));
   }

   public LinkedUnsortedList.Link<E> addFirst(final E element) {
      LinkedUnsortedList.Link<E> curr = this.head;
      return curr != null ? (this.head = new LinkedUnsortedList.Link<>(element, null, curr)) : (this.head = this.tail = new LinkedUnsortedList.Link<>(element));
   }

   public static final class Link<E> {
      private E element;
      private LinkedUnsortedList.Link<E> prev;
      private LinkedUnsortedList.Link<E> next;

      private Link(final E element) {
         super();
         this.element = element;
      }

      private Link(final E element, final LinkedUnsortedList.Link<E> prev, final LinkedUnsortedList.Link<E> next) {
         super();
         this.element = element;
         this.prev = prev;
         this.next = next;
      }
   }
}
