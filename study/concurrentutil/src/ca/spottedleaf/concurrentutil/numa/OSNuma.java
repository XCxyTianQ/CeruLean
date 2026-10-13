package ca.spottedleaf.concurrentutil.numa;

import ca.spottedleaf.common.util.FlatBitsetUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;

public interface OSNuma {
   int NUMA_DISTANCE_CANNOT_DETERMINE = 255;

   static OSNuma getNativeInstance() {
      LinuxNuma linux = LinuxNuma.INSTANCE;
      return linux != null && linux.isAvailable() ? linux : OSNuma.NoOp.INSTANCE;
   }

   boolean isAvailable();

   int getNumaDistance(final int n1, final int n2);

   int getTotalNumaNodes();

   int[][] getNodeDistances();

   int getNumaNode(final int coreId);

   int getTotalCores();

   int[] getCores(final int numaId);

   int getCurrentNumaNode();

   int getCurrentCore();

   long[] getCurrentThreadAffinity();

   void setCurrentThreadAffinity(final long[] to);

   default void setCurrentNumaAffinity(final long[] to) {
      this.setCurrentNumaAffinity(FlatBitsetUtil.bitsetToInts(to));
   }

   default void setCurrentNumaAffinity(final int[] numaNodes) {
      IntArrayList cores = new IntArrayList();

      for (int node : numaNodes) {
         cores.addAll(IntArrayList.wrap(this.getCores(node)));
      }

      this.setCurrentThreadAffinity(FlatBitsetUtil.intsToBitset(cores.toIntArray()));
   }

   final class NoOp extends OSNuma.PreCalculatedNuma {
      public static final OSNuma.NoOp INSTANCE = new OSNuma.NoOp();
      private final long[] currentThreadAffinity = FlatBitsetUtil.intsToBitset(this.getCores(0));

      private NoOp() {
         super(new int[Runtime.getRuntime().availableProcessors()], new int[][]{{10}});
      }

      @Override
      public boolean isAvailable() {
         return false;
      }

      @Override
      public int getCurrentCore() {
         return 0;
      }

      @Override
      public long[] getCurrentThreadAffinity() {
         return (long[])this.currentThreadAffinity.clone();
      }

      @Override
      public void setCurrentThreadAffinity(final long[] to) {
      }
   }

   abstract class PreCalculatedNuma implements OSNuma {
      private final int[] coreToNuma;
      private final int[][] costArray;
      private final int[][] numaToCore;

      public PreCalculatedNuma(final int[] coreToNuma, final int[][] costArray) {
         super();
         this.coreToNuma = coreToNuma;
         this.costArray = costArray;
         IntArrayList[] numaToCore = new IntArrayList[this.costArray.length];

         for (int i = 0; i < numaToCore.length; i++) {
            numaToCore[i] = new IntArrayList();
         }

         for (int core = 0; core < coreToNuma.length; core++) {
            numaToCore[coreToNuma[core]].add(core);
         }

         this.numaToCore = new int[this.costArray.length][];

         for (int i = 0; i < this.numaToCore.length; i++) {
            this.numaToCore[i] = numaToCore[i].toIntArray();
         }
      }

      @Override
      public int getNumaDistance(final int n1, final int n2) {
         if (n1 >= 0 && n1 < this.costArray.length) {
            int[] distances = this.costArray[n1];
            return n2 >= 0 && n2 < distances.length ? distances[n2] : 255;
         } else {
            return 255;
         }
      }

      @Override
      public int getTotalNumaNodes() {
         return this.costArray.length;
      }

      @Override
      public int[][] getNodeDistances() {
         int[][] ret = new int[this.costArray.length][];

         for (int i = 0; i < ret.length; i++) {
            ret[i] = (int[])this.costArray[i].clone();
         }

         return ret;
      }

      @Override
      public int getNumaNode(final int coreId) {
         return coreId >= 0 && coreId < this.coreToNuma.length ? this.coreToNuma[coreId] : -1;
      }

      @Override
      public int getTotalCores() {
         return this.coreToNuma.length;
      }

      @Override
      public int[] getCores(final int numaId) {
         return numaId >= 0 && numaId < this.numaToCore.length ? (int[])this.numaToCore[numaId].clone() : null;
      }

      @Override
      public int getCurrentNumaNode() {
         return this.coreToNuma[this.getCurrentCore()];
      }
   }
}
