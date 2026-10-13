package ca.spottedleaf.concurrentutil.collection;

import ca.spottedleaf.common.util.ThrowUtil;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

public class MultiThreadedQueue<E> implements Queue<E> {
   protected volatile MultiThreadedQueue.LinkedNode<E> head;
   protected volatile MultiThreadedQueue.LinkedNode<E> tail;
   protected static final VarHandle HEAD_HANDLE = ConcurrentUtil.getVarHandle(MultiThreadedQueue.class, "head", MultiThreadedQueue.LinkedNode.class);
   protected static final VarHandle TAIL_HANDLE = ConcurrentUtil.getVarHandle(MultiThreadedQueue.class, "tail", MultiThreadedQueue.LinkedNode.class);

   protected final void setHeadPlain(final MultiThreadedQueue.LinkedNode<E> newHead) {
      HEAD_HANDLE.set((MultiThreadedQueue)this, (MultiThreadedQueue.LinkedNode)newHead);
   }

   protected final void setHeadOpaque(final MultiThreadedQueue.LinkedNode<E> newHead) {
      HEAD_HANDLE.setOpaque((MultiThreadedQueue)this, (MultiThreadedQueue.LinkedNode)newHead);
   }

   protected final MultiThreadedQueue.LinkedNode<E> getHeadPlain() {
      return (MultiThreadedQueue.LinkedNode)HEAD_HANDLE.get((MultiThreadedQueue)this);
   }

   protected final MultiThreadedQueue.LinkedNode<E> getHeadOpaque() {
      return (MultiThreadedQueue.LinkedNode)HEAD_HANDLE.getOpaque((MultiThreadedQueue)this);
   }

   protected final MultiThreadedQueue.LinkedNode<E> getHeadAcquire() {
      return (MultiThreadedQueue.LinkedNode)HEAD_HANDLE.getAcquire((MultiThreadedQueue)this);
   }

   protected final void setTailPlain(final MultiThreadedQueue.LinkedNode<E> newTail) {
      TAIL_HANDLE.set((MultiThreadedQueue)this, (MultiThreadedQueue.LinkedNode)newTail);
   }

   protected final void setTailOpaque(final MultiThreadedQueue.LinkedNode<E> newTail) {
      TAIL_HANDLE.setOpaque((MultiThreadedQueue)this, (MultiThreadedQueue.LinkedNode)newTail);
   }

   protected final MultiThreadedQueue.LinkedNode<E> getTailPlain() {
      return (MultiThreadedQueue.LinkedNode)TAIL_HANDLE.get((MultiThreadedQueue)this);
   }

   protected final MultiThreadedQueue.LinkedNode<E> getTailOpaque() {
      return (MultiThreadedQueue.LinkedNode)TAIL_HANDLE.getOpaque((MultiThreadedQueue)this);
   }

   public MultiThreadedQueue() {
      super();
      MultiThreadedQueue.LinkedNode<E> value = new MultiThreadedQueue.LinkedNode<>(null, null);
      this.setHeadPlain(value);
      this.setTailPlain(value);
   }

   public MultiThreadedQueue(final Iterable<? extends E> collection) {
      super();
      Iterator<? extends E> elements = collection.iterator();
      if (!elements.hasNext()) {
         MultiThreadedQueue.LinkedNode<E> value = new MultiThreadedQueue.LinkedNode<>(null, null);
         this.setHeadPlain(value);
         this.setTailPlain(value);
      } else {
         MultiThreadedQueue.LinkedNode<E> head = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(elements.next(), "Null element"), null);
         MultiThreadedQueue.LinkedNode<E> tail = head;

         while (elements.hasNext()) {
            MultiThreadedQueue.LinkedNode<E> next = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(elements.next(), "Null element"), null);
            tail.setNextPlain(next);
            tail = next;
         }

         this.setHeadPlain(head);
         this.setTailPlain(tail);
      }
   }

   @Override
   public final E remove() throws NoSuchElementException {
      E ret = this.poll();
      if (ret == null) {
         throw new NoSuchElementException();
      } else {
         return ret;
      }
   }

   @Override
   public final boolean add(final E element) {
      return this.offer(element);
   }

   public final boolean forceAdd(final E element) {
      MultiThreadedQueue.LinkedNode<E> node = new MultiThreadedQueue.LinkedNode<>(element, null);
      return !this.forceAppendList(node, node);
   }

   @Override
   public final E element() throws NoSuchElementException {
      E ret = this.peek();
      if (ret == null) {
         throw new NoSuchElementException();
      } else {
         return ret;
      }
   }

   @Override
   public final boolean offer(final E element) {
      Objects.requireNonNull(element, "Null element");
      MultiThreadedQueue.LinkedNode<E> node = new MultiThreadedQueue.LinkedNode<>(element, null);
      return this.appendList(node, node);
   }

   @Override
   public final E peek() {
      MultiThreadedQueue.LinkedNode<E> head = this.getHeadOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = head;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            if (this.getHeadOpaque() == head && curr != head) {
               this.setHeadOpaque(curr);
            }

            return element;
         }

         if (next == null || curr == next) {
            return null;
         }

         curr = next;
      }
   }

   @Override
   public final E poll() {
      return this.removeHead();
   }

   public final E pollIf(final Predicate<E> predicate) {
      return this.removeHead(Objects.requireNonNull(predicate, "Null predicate"));
   }

   @Override
   public void clear() {
      while (this.poll() != null) {
      }
   }

   public final boolean preventAdds() {
      MultiThreadedQueue.LinkedNode<E> deadEnd = new MultiThreadedQueue.LinkedNode<>(null, null);
      deadEnd.setNextPlain(deadEnd);
      if (!this.appendList(deadEnd, deadEnd)) {
         return false;
      }

      this.setTailPlain(deadEnd);
      return true;
   }

   public final void allowAdds() {
      MultiThreadedQueue.LinkedNode<E> tail = this.getTailPlain();

      while (tail != (tail = tail.getNextPlain())) {
      }

      tail.setNextVolatile(null);
   }

   public final boolean tryAllowAdds() {
      MultiThreadedQueue.LinkedNode<E> tail = this.getTailPlain();
      int failures = 0;

      do {
         while (tail == (tail = tail.getNextAcquire())) {
            for (int i = 0; i < failures; i++) {
               ConcurrentUtil.backoff();
            }

            if (tail == (tail = tail.compareExchangeNextVolatile(tail, null))) {
               return true;
            }

            if (tail == null) {
               return false;
            }

            failures++;
         }
      } while (tail != null);

      return false;
   }

   public final boolean addOrAllowAdds(final E element) {
      Objects.requireNonNull(element, "Null element");
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> append = new MultiThreadedQueue.LinkedNode<>(element, null);
      MultiThreadedQueue.LinkedNode<E> currTail = this.getTailOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = currTail;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (next == null) {
            MultiThreadedQueue.LinkedNode<E> compared = curr.compareExchangeNextVolatile(null, append);
            if (compared == null) {
               if (this.getTailOpaque() == currTail) {
                  this.setTailOpaque(append);
               }

               return false;
            }

            failures++;
            curr = compared;
         } else if (next == curr) {
            MultiThreadedQueue.LinkedNode<E> compared = curr.compareExchangeNextVolatile(curr, null);
            if (compared == curr) {
               return true;
            }

            failures++;
            if (compared != null) {
               curr = compared;
            }
         } else if (curr == currTail) {
            curr = next;
         } else if (currTail == (currTail = this.getTailOpaque())) {
            curr = next;
         } else {
            curr = currTail;
         }
      }
   }

   public final boolean isAddBlocked() {
      MultiThreadedQueue.LinkedNode<E> tail = this.getTailOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = tail.getNextVolatile();
         if (next == null) {
            return false;
         }

         if (next == tail) {
            return true;
         }

         tail = next;
      }
   }

   public final E pollOrBlockAdds() {
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> head = this.getHeadOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = head;

      while (true) {
         E currentVal = curr.getElementVolatile();
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextOpaque();
         if (next == curr) {
            return null;
         }

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (currentVal != null) {
            if (curr.getAndSetElementVolatile(null) != null) {
               if (this.getHeadOpaque() == head) {
                  this.setHeadOpaque(next != null ? next : curr);
               }

               return currentVal;
            }

            failures++;
         } else if (next == null) {
            if (curr != head && this.getHeadOpaque() == head) {
               this.setHeadOpaque(curr);
            }

            MultiThreadedQueue.LinkedNode<E> compared = curr.compareExchangeNextVolatile(null, curr);
            if (compared == null) {
               return null;
            }

            curr = compared;
            failures++;
         } else if (head == curr) {
            curr = next;
         } else if (head == (head = this.getHeadOpaque())) {
            curr = next;
         } else {
            curr = head;
         }
      }
   }

   @Override
   public boolean remove(final Object object) {
      Objects.requireNonNull(object, "Null object to remove");
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null && (element == object || element.equals(object)) && curr.getAndSetElementVolatile(null) == element) {
            return true;
         }

         if (next == curr || next == null) {
            return false;
         }

         curr = next;
      }
   }

   @Override
   public boolean removeIf(final Predicate<? super E> filter) {
      Objects.requireNonNull(filter, "Null filter");
      boolean ret = false;
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret |= filter.test(element) && curr.getAndSetElementVolatile(null) == element;
         }

         if (next == null || next == curr) {
            return ret;
         }

         curr = next;
      }
   }

   @Override
   public boolean removeAll(final Collection<?> collection) {
      Objects.requireNonNull(collection, "Null collection");
      boolean ret = false;
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret |= collection.contains(element) && curr.getAndSetElementVolatile(null) == element;
         }

         if (next == null || next == curr) {
            return ret;
         }

         curr = next;
      }
   }

   @Override
   public boolean retainAll(final Collection<?> collection) {
      Objects.requireNonNull(collection, "Null collection");
      boolean ret = false;
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret |= !collection.contains(element) && curr.getAndSetElementVolatile(null) == element;
         }

         if (next == null || next == curr) {
            return ret;
         }

         curr = next;
      }
   }

   @Override
   public Object[] toArray() {
      List<E> ret = new ArrayList<>();
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret.add(element);
         }

         if (next == null || next == curr) {
            return ret.toArray();
         }

         curr = next;
      }
   }

   @Override
   public <T> T[] toArray(final T[] array) {
      List<T> ret = new ArrayList();
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret.add((T)element);
         }

         if (next == null || next == curr) {
            return (T[])ret.toArray(array);
         }

         curr = next;
      }
   }

   @Override
   public <T> T[] toArray(final IntFunction<T[]> generator) {
      Objects.requireNonNull(generator, "Null generator");
      List<T> ret = new ArrayList();
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            ret.add((T)element);
         }

         if (next == null || next == curr) {
            return ret.toArray(generator);
         }

         curr = next;
      }
   }

   @Override
   public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("MultiThreadedQueue: {elements: {");
      int deadEntries = 0;
      int totalEntries = 0;
      int aliveEntries = 0;
      boolean addLocked = false;
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element == null) {
            deadEntries++;
         } else {
            aliveEntries++;
         }

         if (totalEntries != 0) {
            builder.append(", ");
         }

         builder.append(totalEntries).append(": \"").append(element).append('"');
         if (next == null) {
            break;
         }

         if (curr == next) {
            addLocked = true;
            break;
         }

         curr = next;
         totalEntries++;
      }

      builder.append("}, total_entries: \"")
         .append(totalEntries)
         .append("\", alive_entries: \"")
         .append(aliveEntries)
         .append("\", dead_entries:")
         .append(deadEntries)
         .append("\", add_locked: \"")
         .append(addLocked)
         .append("\"}");
      return builder.toString();
   }

   @Override
   public boolean addAll(final Collection<? extends E> collection) {
      return this.addAll((Iterable<? extends E>)collection);
   }

   public boolean addAll(final Iterable<? extends E> iterable) {
      Objects.requireNonNull(iterable, "Null iterable");
      Iterator<? extends E> elements = iterable.iterator();
      if (!elements.hasNext()) {
         return false;
      }

      MultiThreadedQueue.LinkedNode<E> head = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(elements.next(), "Null element"), null);
      MultiThreadedQueue.LinkedNode<E> tail = head;

      while (elements.hasNext()) {
         MultiThreadedQueue.LinkedNode<E> next = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(elements.next(), "Null element"), null);
         tail.setNextPlain(next);
         tail = next;
      }

      return this.appendList(head, tail);
   }

   public boolean addAll(final E[] items) {
      return this.addAll(items, 0, items.length);
   }

   public boolean addAll(final E[] items, final int off, final int len) {
      Objects.checkFromIndexSize(off, len, items.length);
      if (len == 0) {
         return false;
      }

      MultiThreadedQueue.LinkedNode<E> head = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(items[off], "Null element"), null);
      MultiThreadedQueue.LinkedNode<E> tail = head;

      for (int i = 1; i < len; i++) {
         MultiThreadedQueue.LinkedNode<E> next = new MultiThreadedQueue.LinkedNode<>(Objects.requireNonNull(items[off + i], "Null element"), null);
         tail.setNextPlain(next);
         tail = next;
      }

      return this.appendList(head, tail);
   }

   @Override
   public boolean containsAll(final Collection<?> collection) {
      Objects.requireNonNull(collection, "Null collection");

      for (Object element : collection) {
         if (!this.contains(element)) {
            return false;
         }
      }

      return false;
   }

   @Override
   public Iterator<E> iterator() {
      return new MultiThreadedQueue.LinkedIterator<>(this.getHeadOpaque());
   }

   @Override
   public final int size() {
      int size = 0;
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            size++;
         }

         if (next == null || next == curr) {
            return size;
         }

         curr = next;
      }
   }

   @Override
   public final boolean isEmpty() {
      return this.peek() == null;
   }

   @Override
   public boolean contains(final Object object) {
      Objects.requireNonNull(object, "Null object");
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null && (element == object || element.equals(object))) {
            return true;
         }

         if (next == null || next == curr) {
            return false;
         }

         curr = next;
      }
   }

   public E find(final Predicate<E> predicate) {
      Objects.requireNonNull(predicate, "Null predicate");
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null && predicate.test(element)) {
            return element;
         }

         if (next == null || next == curr) {
            return null;
         }

         curr = next;
      }
   }

   @Override
   public void forEach(final Consumer<? super E> action) {
      Objects.requireNonNull(action, "Null action");
      MultiThreadedQueue.LinkedNode<E> curr = this.getHeadOpaque();

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E element = curr.getElementPlain();
         if (element != null) {
            action.accept(element);
         }

         if (next == null || next == curr) {
            return;
         }

         curr = next;
      }
   }

   protected final boolean forceAppendList(final MultiThreadedQueue.LinkedNode<E> head, final MultiThreadedQueue.LinkedNode<E> tail) {
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> currTail = this.getTailOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = currTail;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (next == null || next == curr) {
            MultiThreadedQueue.LinkedNode<E> compared = curr.compareExchangeNextVolatile(next, head);
            if (compared == next) {
               if (this.getTailOpaque() == currTail) {
                  this.setTailOpaque(tail);
               }

               return next != curr;
            }

            failures++;
            curr = compared;
         } else if (curr == currTail) {
            curr = next;
         } else if (currTail == (currTail = this.getTailOpaque())) {
            curr = next;
         } else {
            curr = currTail;
         }
      }
   }

   protected final boolean appendList(final MultiThreadedQueue.LinkedNode<E> head, final MultiThreadedQueue.LinkedNode<E> tail) {
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> currTail = this.getTailOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = currTail;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         if (next == curr) {
            return false;
         }

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (next == null) {
            MultiThreadedQueue.LinkedNode<E> compared = curr.compareExchangeNextVolatile(null, head);
            if (compared == null) {
               if (this.getTailOpaque() == currTail) {
                  this.setTailOpaque(tail);
               }

               return true;
            }

            failures++;
            curr = compared;
         } else if (curr == currTail) {
            curr = next;
         } else if (currTail == (currTail = this.getTailOpaque())) {
            curr = next;
         } else {
            curr = currTail;
         }
      }
   }

   protected final E removeHead(final Predicate<E> predicate) {
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> head = this.getHeadOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = head;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E currentVal = curr.getElementPlain();

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (currentVal != null) {
            if (!predicate.test(currentVal)) {
               if (curr != head && this.getHeadOpaque() == head) {
                  this.setHeadOpaque(curr);
               }

               return null;
            }

            if (curr.getAndSetElementVolatile(null) != null) {
               if (this.getHeadOpaque() == head) {
                  this.setHeadOpaque(next != null ? next : curr);
               }

               return currentVal;
            }

            MultiThreadedQueue.LinkedNode var10000 = curr;
            curr = next;
            if (var10000 == next || next == null) {
               return null;
            }

            failures++;
         } else {
            if (curr == next || next == null) {
               if (curr != head && this.getHeadOpaque() == head) {
                  this.setHeadOpaque(curr);
               }

               return null;
            }

            if (head == curr) {
               curr = next;
            } else if (head == (head = this.getHeadOpaque())) {
               curr = next;
            } else {
               curr = head;
            }
         }
      }
   }

   protected final E removeHead() {
      int failures = 0;
      MultiThreadedQueue.LinkedNode<E> head = this.getHeadOpaque();
      MultiThreadedQueue.LinkedNode<E> curr = head;

      while (true) {
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         E currentVal = curr.getElementPlain();

         for (int i = 0; i < failures; i++) {
            ConcurrentUtil.backoff();
         }

         if (currentVal != null) {
            if (curr.getAndSetElementVolatile(null) != null) {
               if (this.getHeadOpaque() == head) {
                  this.setHeadOpaque(next != null ? next : curr);
               }

               return currentVal;
            }

            MultiThreadedQueue.LinkedNode var10000 = curr;
            curr = next;
            if (var10000 == next || next == null) {
               return null;
            }

            failures++;
         } else {
            if (curr == next || next == null) {
               if (curr != head && this.getHeadOpaque() == head) {
                  this.setHeadOpaque(curr);
               }

               return null;
            }

            if (head == curr) {
               curr = next;
            } else if (head == (head = this.getHeadOpaque())) {
               curr = next;
            } else {
               curr = head;
            }
         }
      }
   }

   public int drain(final Consumer<E> consumer) {
      return this.drain(consumer, false, ThrowUtil::throwUnchecked);
   }

   public int drain(final Consumer<E> consumer, final boolean preventAdds) {
      return this.drain(consumer, preventAdds, ThrowUtil::throwUnchecked);
   }

   public int drain(final Consumer<E> consumer, final boolean preventAdds, final Consumer<Throwable> exceptionHandler) {
      Objects.requireNonNull(consumer, "Null consumer");
      Objects.requireNonNull(exceptionHandler, "Null exception handler");
      int total = 0;
      MultiThreadedQueue.LinkedNode<E> head = this.getHeadAcquire();
      MultiThreadedQueue.LinkedNode<E> curr = head;

      while (true) {
         E currentVal = curr.getElementPlain();
         MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
         if (next == curr) {
            break;
         }

         if (currentVal == null) {
            if (next == null) {
               if (!preventAdds || (next = curr.compareExchangeNextVolatile(null, curr)) == null) {
                  break;
               }

               curr = next;
            } else {
               curr = next;
            }
         } else {
            try {
               consumer.accept(currentVal);
            } catch (Exception ex) {
               this.setHeadOpaque(next != null ? next : curr);
               curr.setElementOpaque(null);
               exceptionHandler.accept(ex);
            }

            curr.setElementOpaque(null);
            total++;
            if (next == null) {
               if (!preventAdds || (next = curr.compareExchangeNextVolatile(null, curr)) == null) {
                  break;
               }

               curr = next;
            } else {
               curr = next;
            }
         }
      }

      if (curr != head) {
         this.setHeadOpaque(curr);
      }

      return total;
   }

   @Override
   public Spliterator<E> spliterator() {
      return Spliterators.spliterator(this, 4368);
   }

   protected static final class LinkedIterator<E> implements Iterator<E> {
      protected MultiThreadedQueue.LinkedNode<E> curr;
      protected MultiThreadedQueue.LinkedNode<E> next;
      protected E nextElement;

      protected LinkedIterator(final MultiThreadedQueue.LinkedNode<E> start) {
         super();
         MultiThreadedQueue.LinkedNode<E> curr = start;

         while (true) {
            MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
            E element = curr.getElementPlain();
            if (element != null) {
               this.nextElement = element;
               this.next = curr;
               break;
            }

            if (next == null || next == curr) {
               break;
            }

            curr = next;
         }
      }

      protected final void findNext() {
         MultiThreadedQueue.LinkedNode<E> curr = this.next;

         while (true) {
            MultiThreadedQueue.LinkedNode<E> next = curr.getNextVolatile();
            if (next == null || next == curr) {
               this.curr = this.next;
               this.next = null;
               this.nextElement = null;
               return;
            }

            E element = next.getElementPlain();
            if (element != null) {
               this.nextElement = element;
               this.curr = this.next;
               this.next = next;
               return;
            }

            curr = next;
         }
      }

      @Override
      public boolean hasNext() {
         return this.nextElement != null;
      }

      @Override
      public E next() {
         E element = this.nextElement;
         if (element == null) {
            throw new NoSuchElementException();
         }

         this.findNext();
         return element;
      }

      @Override
      public void remove() {
         if (this.curr == null) {
            throw new IllegalStateException();
         }

         this.curr.setElementVolatile(null);
         this.curr = null;
      }
   }

   protected static final class LinkedNode<E> {
      protected volatile Object element;
      protected volatile MultiThreadedQueue.LinkedNode<E> next;
      protected static final VarHandle ELEMENT_HANDLE = ConcurrentUtil.getVarHandle(MultiThreadedQueue.LinkedNode.class, "element", Object.class);
      protected static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         MultiThreadedQueue.LinkedNode.class, "next", MultiThreadedQueue.LinkedNode.class
      );

      protected LinkedNode(final Object element, final MultiThreadedQueue.LinkedNode<E> next) {
         super();
         ELEMENT_HANDLE.set((MultiThreadedQueue.LinkedNode)this, (Object)element);
         NEXT_HANDLE.set((MultiThreadedQueue.LinkedNode)this, (MultiThreadedQueue.LinkedNode)next);
      }

      protected final E getElementPlain() {
         return (E)(Object)ELEMENT_HANDLE.get((MultiThreadedQueue.LinkedNode)this);
      }

      protected final E getElementVolatile() {
         return (E)(Object)ELEMENT_HANDLE.getVolatile((MultiThreadedQueue.LinkedNode)this);
      }

      protected final void setElementPlain(final E update) {
         ELEMENT_HANDLE.set((MultiThreadedQueue.LinkedNode)this, (Object)update);
      }

      protected final void setElementOpaque(final E update) {
         ELEMENT_HANDLE.setOpaque((MultiThreadedQueue.LinkedNode)this, (Object)update);
      }

      protected final void setElementVolatile(final E update) {
         ELEMENT_HANDLE.setVolatile((MultiThreadedQueue.LinkedNode)this, (Object)update);
      }

      protected final E getAndSetElementVolatile(final E update) {
         return (E)(Object)ELEMENT_HANDLE.getAndSet((MultiThreadedQueue.LinkedNode)this, (Object)update);
      }

      protected final E compareExchangeElementVolatile(final E expect, final E update) {
         return (E)(Object)ELEMENT_HANDLE.compareAndExchange((MultiThreadedQueue.LinkedNode)this, (Object)expect, (Object)update);
      }

      protected final MultiThreadedQueue.LinkedNode<E> getNextPlain() {
         return (MultiThreadedQueue.LinkedNode)NEXT_HANDLE.get((MultiThreadedQueue.LinkedNode)this);
      }

      protected final MultiThreadedQueue.LinkedNode<E> getNextOpaque() {
         return (MultiThreadedQueue.LinkedNode)NEXT_HANDLE.getOpaque((MultiThreadedQueue.LinkedNode)this);
      }

      protected final MultiThreadedQueue.LinkedNode<E> getNextAcquire() {
         return (MultiThreadedQueue.LinkedNode)NEXT_HANDLE.getAcquire((MultiThreadedQueue.LinkedNode)this);
      }

      protected final MultiThreadedQueue.LinkedNode<E> getNextVolatile() {
         return (MultiThreadedQueue.LinkedNode)NEXT_HANDLE.getVolatile((MultiThreadedQueue.LinkedNode)this);
      }

      protected final void setNextPlain(final MultiThreadedQueue.LinkedNode<E> next) {
         NEXT_HANDLE.set((MultiThreadedQueue.LinkedNode)this, (MultiThreadedQueue.LinkedNode)next);
      }

      protected final void setNextVolatile(final MultiThreadedQueue.LinkedNode<E> next) {
         NEXT_HANDLE.setVolatile((MultiThreadedQueue.LinkedNode)this, (MultiThreadedQueue.LinkedNode)next);
      }

      protected final MultiThreadedQueue.LinkedNode<E> compareExchangeNextVolatile(
         final MultiThreadedQueue.LinkedNode<E> expect, final MultiThreadedQueue.LinkedNode<E> set
      ) {
         return (MultiThreadedQueue.LinkedNode)NEXT_HANDLE.compareAndExchange(
            (MultiThreadedQueue.LinkedNode)this, (MultiThreadedQueue.LinkedNode)expect, (MultiThreadedQueue.LinkedNode)set
         );
      }
   }
}
