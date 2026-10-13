package ca.spottedleaf.concurrentutil.util;

public enum Priority {
   COMPLETING(-1),
   BLOCKING,
   HIGHEST,
   HIGHER,
   HIGH,
   NORMAL,
   LOW,
   LOWER,
   LOWEST,
   IDLE;

   static final Priority[] PRIORITIES = values();
   public static final int TOTAL_PRIORITIES = PRIORITIES.length;
   public static final int TOTAL_SCHEDULABLE_PRIORITIES = TOTAL_PRIORITIES - 1;
   private static int priorityCounter;
   public final int priority;

   public static boolean isValidPriority(final Priority priority) {
      return priority != null && priority != COMPLETING;
   }

   public static Priority max(final Priority p1, final Priority p2) {
      return p1.isHigherOrEqualPriority(p2) ? p1 : p2;
   }

   public static Priority min(final Priority p1, final Priority p2) {
      return p1.isLowerOrEqualPriority(p2) ? p1 : p2;
   }

   public boolean isHigherOrEqualPriority(final Priority than) {
      return this.priority <= than.priority;
   }

   public boolean isHigherPriority(final Priority than) {
      return this.priority < than.priority;
   }

   public boolean isLowerOrEqualPriority(final Priority than) {
      return this.priority >= than.priority;
   }

   public boolean isLowerPriority(final Priority than) {
      return this.priority > than.priority;
   }

   public boolean isHigherOrEqualPriority(final int than) {
      return this.priority <= than;
   }

   public boolean isHigherPriority(final int than) {
      return this.priority < than;
   }

   public boolean isLowerOrEqualPriority(final int than) {
      return this.priority >= than;
   }

   public boolean isLowerPriority(final int than) {
      return this.priority > than;
   }

   public static boolean isHigherOrEqualPriority(final int priority, final int than) {
      return priority <= than;
   }

   public static boolean isHigherPriority(final int priority, final int than) {
      return priority < than;
   }

   public static boolean isLowerOrEqualPriority(final int priority, final int than) {
      return priority >= than;
   }

   public static boolean isLowerPriority(final int priority, final int than) {
      return priority > than;
   }

   public static Priority getPriority(final int priority) {
      return PRIORITIES[priority + 1];
   }

   private static int nextCounter() {
      return priorityCounter++;
   }

   Priority() {
      this(nextCounter());
   }

   Priority(final int priority) {
      this.priority = priority;
   }
}
