package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

public class WorldVoxilizedSectionMipper {
   private static int G(int x, int y, int z) {
      return y << 8 | z << 4 | x;
   }

   private static int H(int x, int y, int z) {
      return (y << 6 | z << 3 | x) + 4096;
   }

   private static int I(int x, int y, int z) {
      return (y << 4 | z << 2 | x) + 512 + 4096;
   }

   private static int J(int x, int y, int z) {
      return (y << 2 | z << 1 | x) + 64 + 512 + 4096;
   }

   private static void resolveSurfaceCarriers(long[] data, int base, int size) {
      int layer = size * size;
      for (int y = 0; y < size; y++) {
         for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
               int index = base + y * layer + z * size + x;
               long carrier = data[index];
               if (!Mapper.isSurfaceCarrier(carrier)) continue;
               if (y == 0) continue;
               int belowIndex = index - layer;
               long below = data[belowIndex];
               if (!Mapper.isAir(below)) {
                  data[belowIndex] = Mapper.applySurfaceCarrier(below, carrier);
                  data[index] = Mapper.clearSurfaceCarrier(carrier);
                  continue;
               }
               data[index] = Mapper.restoreSurfaceCarrier(carrier);
            }
         }
      }
   }

   public static void mipSection(VoxelizedSection section, Mapper mapper) {
      long[] data = section.section;
      int i = 0;
      int MSK = 3822;
      int iMSK1 = ~MSK + 1;
      int q = 0;

      while (true) {
         data[4096 + i++] = Mipper.mip(
            data[q | G(0, 0, 0)],
            data[q | G(1, 0, 0)],
            data[q | G(0, 0, 1)],
            data[q | G(1, 0, 1)],
            data[q | G(0, 1, 0)],
            data[q | G(1, 1, 0)],
            data[q | G(0, 1, 1)],
            data[q | G(1, 1, 1)],
            mapper
         );
         if (q == MSK) {
            resolveSurfaceCarriers(data, 4096, 8);
            i = 0;

            for (int y = 0; y < 8; y += 2) {
               for (int z = 0; z < 8; z += 2) {
                  for (int x = 0; x < 8; x += 2) {
                     data[4608 + i++] = Mipper.mip(
                        data[H(x, y, z)],
                        data[H(x + 1, y, z)],
                        data[H(x, y, z + 1)],
                        data[H(x + 1, y, z + 1)],
                        data[H(x, y + 1, z)],
                        data[H(x + 1, y + 1, z)],
                        data[H(x, y + 1, z + 1)],
                        data[H(x + 1, y + 1, z + 1)],
                        mapper
                     );
                  }
               }
            }

            resolveSurfaceCarriers(data, 4608, 4);

            i = 0;

            for (int y = 0; y < 4; y += 2) {
               for (int z = 0; z < 4; z += 2) {
                  for (int x = 0; x < 4; x += 2) {
                     data[4672 + i++] = Mipper.mip(
                        data[I(x, y, z)],
                        data[I(x + 1, y, z)],
                        data[I(x, y, z + 1)],
                        data[I(x + 1, y, z + 1)],
                        data[I(x, y + 1, z)],
                        data[I(x + 1, y + 1, z)],
                        data[I(x, y + 1, z + 1)],
                        data[I(x + 1, y + 1, z + 1)],
                        mapper
                     );
                  }
               }
            }

            resolveSurfaceCarriers(data, 4672, 2);

            data[4680] = Mipper.mip(
               data[J(0, 0, 0)],
               data[J(1, 0, 0)],
               data[J(0, 0, 1)],
               data[J(1, 0, 1)],
               data[J(0, 1, 0)],
               data[J(1, 1, 0)],
               data[J(0, 1, 1)],
               data[J(1, 1, 1)],
               mapper
            );
            return;
         }

         q = q + iMSK1 & MSK;
      }
   }
}
