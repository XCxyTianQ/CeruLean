package ca.spottedleaf.concurrentutil.numa;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.IntByReference;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxNuma extends OSNuma.PreCalculatedNuma {
   private static final Logger LOGGER = LoggerFactory.getLogger(LinuxNuma.class);
   private static final ThreadLocal<IntByReference> CURRENT_CORE_POINTER = ThreadLocal.withInitial(IntByReference::new);
   private static final boolean LIBRARIES_AVAILABLE;
   public static final LinuxNuma INSTANCE;

   private LinuxNuma(final int[] coreToNuma, final int[][] costArray) {
      super(coreToNuma, costArray);
   }

   @Override
   public boolean isAvailable() {
      return true;
   }

   private static int getCurrentCore0() {
      IntByReference cpu = CURRENT_CORE_POINTER.get();
      int res = LinuxNuma.LibC.getcpu(cpu, (IntByReference)null);
      if (res == 0) {
         return cpu.getValue();
      } else {
         throw new IllegalStateException("getcpu failed: " + res);
      }
   }

   @Override
   public int getCurrentCore() {
      return getCurrentCore0();
   }

   private static long[] getCurrentThreadAffinity0() {
      int cpus = LinuxNuma.LibNuma.numa_num_possible_cpus();
      if (cpus < 0) {
         throw new IllegalStateException();
      } else {
         LinuxNuma.CpuSet cpuSet = new LinuxNuma.CpuSet(cpus);
         int res = LinuxNuma.LibC.sched_getaffinity(0, (int)cpuSet.sizeof(), cpuSet.getPointer());
         if (res != 0) {
            throw new IllegalStateException();
         } else {
            return cpuSet.toLongs();
         }
      }
   }

   @Override
   public long[] getCurrentThreadAffinity() {
      return getCurrentThreadAffinity0();
   }

   private static void setCurrentThreadAffinity0(final long[] to) {
      LinuxNuma.CpuSet cpuSet = new LinuxNuma.CpuSet(to);
      LinuxNuma.LibC.sched_setaffinity(0, (int)cpuSet.sizeof(), cpuSet.getPointer());
   }

   @Override
   public void setCurrentThreadAffinity(final long[] to) {
      setCurrentThreadAffinity0(to);
   }

   static {
      boolean librariesOk = (boolean)0;

      try {
         LinuxNuma.LibNuma.init();
         LinuxNuma.LibC.init();
         librariesOk = (boolean)1;
      } catch (Throwable var11) {
      }

      if (!librariesOk) {
         LIBRARIES_AVAILABLE = false;
         LOGGER.debug("Unable to link NUMA libraries for Linux NUMA");
      } else {
         int numaAvail = LinuxNuma.LibNuma.numa_available();
         LIBRARIES_AVAILABLE = numaAvail >= 0;
         LOGGER.debug("libnuma numa_available: " + numaAvail);
      }

      if (!LIBRARIES_AVAILABLE) {
         INSTANCE = null;
      } else {
         librariesOk = (boolean)(LinuxNuma.LibNuma.numa_max_node() + 1);
         Pointer cpuMask = LinuxNuma.LibNuma.numa_allocate_cpumask();

         try {
            if (cpuMask == null) {
               INSTANCE = null;
               LOGGER.debug("Failed to create Linux NUMA CPU mapping: Failed to allocate cpu mask for libnuma");
            } else {
               int totalCpus = LinuxNuma.LibNuma.numa_num_possible_cpus();
               int[] coreToNuma = new int[totalCpus];
               Arrays.fill(coreToNuma, -1);

               label182:
               for (int node = 0; node < librariesOk; node++) {
                  LinuxNuma.LibNuma.numa_bitmask_clearall(cpuMask);
                  int res = LinuxNuma.LibNuma.numa_node_to_cpus(node, cpuMask);
                  if (res != 0) {
                     LOGGER.debug("Failed to create Linux NUMA CPU mapping: Failed libnuma numa_node_to_cpus(" + node + ", ...): " + res);
                     coreToNuma = null;
                     break;
                  }

                  for (int cpu = 0; cpu < totalCpus; cpu++) {
                     int bit = LinuxNuma.LibNuma.numa_bitmask_isbitset(cpuMask, cpu);
                     if (bit != 0) {
                        if (coreToNuma[cpu] != -1) {
                           LOGGER.debug(
                              "Failed to create Linux NUMA CPU mapping: Duplicate node mapping for core " + cpu + ": " + coreToNuma[cpu] + " and " + node
                           );
                           coreToNuma = null;
                           break label182;
                        }

                        coreToNuma[cpu] = node;
                     }
                  }
               }

               if (coreToNuma != null) {
                  for (int cpu = 0; cpu < coreToNuma.length; cpu++) {
                     int node = coreToNuma[cpu];
                     if (node == -1) {
                        LOGGER.debug("Failed to create Linux NUMA CPU mapping: No node mapping for core " + cpu);
                        coreToNuma = null;
                        break;
                     }
                  }
               }

               int[][] costArray = new int[librariesOk][librariesOk];

               for (int i = 0; i < librariesOk; i++) {
                  for (int j = 0; j < librariesOk; j++) {
                     int dist = LinuxNuma.LibNuma.numa_distance(i, j);
                     costArray[i][j] = dist < 10 ? 255 : dist;
                  }
               }

               INSTANCE = coreToNuma == null ? null : new LinuxNuma(coreToNuma, costArray);
            }
         } finally {
            if (cpuMask != null) {
               LinuxNuma.LibNuma.numa_bitmask_free(cpuMask);
            }
         }
      }
   }

   private static final class CpuSet extends ByReference {
      private static int roundLongSizeBits(final int bits) {
         int bytes = (bits + 7) / 8;
         return roundLongSizeBytes(bytes);
      }

      private static int roundLongSizeBytes(final int bytes) {
         int longs = (bytes + (Native.LONG_SIZE - 1)) / Native.LONG_SIZE;
         return longs * Native.LONG_SIZE;
      }

      public CpuSet(final int bits) {
         super(roundLongSizeBits(bits));
      }

      public CpuSet(final long[] bitset) {
         super(roundLongSizeBytes(bitset.length * 8));

         for (int i = 0; i < bitset.length; i++) {
            this.getPointer().setLong((long)i << 3, bitset[i]);
         }
      }

      public long[] toLongs() {
         long[] ret = new long[(int)((this.sizeof() + 7L) / 8L)];

         for (int i = 0; i < ret.length; i++) {
            ret[i] = this.getPointer().getLong((long)i << 3);
         }

         return ret;
      }

      public long sizeof() {
         return ((Memory)this.getPointer()).size();
      }
   }

   private static final class LibC {
      private LibC() {
         super();
      }

      public static void init() {
      }

      public static native int getcpu(final IntByReference cpu, final IntByReference node);

      public static native int sched_setaffinity(final int pid, final int cpusetsize, final Pointer cpuset);

      public static native int sched_getaffinity(final int pid, final int cpusetsize, final Pointer cpuset);

      static {
         Native.register(Platform.C_LIBRARY_NAME);
      }
   }

   private static final class LibNuma {
      private LibNuma() {
         super();
      }

      public static void init() {
      }

      public static native int numa_available();

      public static native int numa_max_node();

      public static native int numa_distance(final int node1, final int node2);

      public static native Pointer numa_allocate_cpumask();

      public static native Pointer numa_bitmask_clearall(final Pointer bitmask);

      public static native int numa_bitmask_isbitset(final Pointer bitmask, final int bit);

      public static native int numa_node_to_cpus(final int node, final Pointer bitmask);

      public static native void numa_bitmask_free(final Pointer bitmask);

      public static native int numa_num_possible_cpus();

      static {
         Native.register("numa");
      }
   }
}
