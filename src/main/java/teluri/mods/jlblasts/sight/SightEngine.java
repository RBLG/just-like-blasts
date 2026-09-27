package teluri.mods.jlblasts.sight;

import org.joml.Math;
import org.joml.Vector3i;

import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.state.BlockState;

import static java.lang.Math.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * naive implementation of the Face Based GBV algorithm, which is an evolution of GBV that attribute a value per face, instead of per voxel.
 * <br>
 * this come with the precision of 18 neigbors 3d GBV but without the leaks that happen with anything further than 6neigbors. <br>
 * it drawback is having to interpolate for 3 values instead of 1
 * 
 * @author RBLG
 * @since v0.0.4
 */
public class SightEngine {
	private static final int[] SIGNS = new int[] { 1, -1 };
	// positive axis
	private static final Vector3i X = new Vector3i(1, 0, 0);
	private static final Vector3i Y = new Vector3i(0, 1, 0);
	private static final Vector3i Z = new Vector3i(0, 0, 1);

	public static final Quadrant[] QUADRANTS = new Quadrant[8];

	static {
		int index = 0;
		for (int s1 : SIGNS) {
			for (int s2 : SIGNS) {
				for (int s3 : SIGNS) { // 8 variations
					Vector3i v1 = new Vector3i(X).mul(s1);
					Vector3i v2 = new Vector3i(Y).mul(s2);
					Vector3i v3 = new Vector3i(Z).mul(s3);
					QUADRANTS[index] = new Quadrant(v1, v2, v3);
					index++;
				}
			}
		}
	}

	public static final float UNPOINTLIGHT_FIX = 0.5f; // bias the weights so the source doesnt act like a point light

	/**
	 * standard weighted interpolation <br>
	 * 
	 * @return (val1 * w1 + val2 * w2 + val3 * w3) / (w1 + w2 + w3)
	 */
	public static float interpolate(float val1, float w1, float val2, float w2, float val3, float w3) {
		return (val1 * w1 + val2 * w2 + val3 * w3) / (w1 + w2 + w3);
	}

	/**
	 * check if a quadrant should draw blocks when on an edge shared with another quadrant
	 */
	public static boolean isNotDuplicatedEdge(Quadrant quadr, int itr1, int itr2, int itr3) {
		return (itr1 != 0 || 0 <= quadr.axis1.x) && (itr2 != 0 || 0 <= quadr.axis2.y) && (itr3 != 0 || 0 <= quadr.axis3.z);
	}

	/**
	 * get the visibility value for the block based on the visibility values of the faces and their weights
	 */
	public static float facesToVolumeValue(float val1, float w1, float val2, float w2, float val3, float w3) {
		return interpolate(val1, w1, val2, w2, val3, w3);
	}

	/**
	 * paralel iteration over quadrants
	 */
	public static void forEachQuadrants(Consumer<Quadrant> step) {
		Stream.of(QUADRANTS)/*.parallel()*/.forEach(step);
	}

	/**
	 * run the FBGBV algorithm over a quadrant with no block updates in range
	 * 
	 * @param origin position of the source of the light
	 * @param range max range that is iterated over
	 * @param quadr quadrant currently processed
	 * @param aprov alpha provider
	 * @param scons sight consumer (output)
	 */
	public static void traceQuadrant(Vector3i origin, int range, Quadrant quadr, IResistanceProvider aprov, ISightConsumer scons, float rmax) {
		final Vector3i vit1 = new Vector3i();
		final Vector3i vit2 = new Vector3i();
		final Vector3i xyz = new Vector3i();
		MutableBlockPos xyzbp = new MutableBlockPos();
		BlockState[] blockstate = new BlockState[1];
		float max = rmax * 1.732050808f;
		range = max(1, range); // 1 is the minimal possible range
		int size = range + 2;

		// store the sight/visibility values of the last plane iterated over for incoming steps
		float[] vbuffer = new float[size * size * 3];

		// array of... its complicated. but it allow to skip a lot of the iteration
		int[] lRowVis = new int[size]; // init at 0

		xyzbp.set(origin.x, origin.y, origin.z);
		float startingValue = aprov.getResistance(xyzbp, blockstate);
		if (isNotDuplicatedEdge(quadr, 0, 0, 0)) {
			scons.consume(xyzbp, startingValue, blockstate[0]);
		}
		// initialize the source sight values to visible
		vbuffer[0 + 0 /*      */] = startingValue; // the source (1,0,0) neigbor's face 1
		vbuffer[0 + 1 + size * 3] = startingValue; // the source (0,1,0) neigbor's face 2
		vbuffer[0 + 2 + 3 /*  */] = startingValue; // the source (0,0,1) neigbor's face 3

		for (int itr1 = 0; itr1 < range; itr1++) {
			vit1.set(quadr.axis1).mul(itr1).add(origin); // first component of xyz
			for (int itr2 = 0; itr2 < range; itr2++) {
				vit2.set(quadr.axis2).mul(itr2).add(vit1); // second component of xyz
				int rowVis = (itr1 + itr2 - 1) >>> 31;// (itr1 == 0 && itr2 == 0) ? 1 : 0; // if both are zero, itr3 start at 1
				for (int itr3 = rowVis; itr3 < range; itr3++) {
					int index = ((itr2 * size) + itr3) * 3; // translate 2d coordinates to 1d for use as an array index

					// get this voxel exposed faces from the buffer
					float face1 = vbuffer[index + 0];
					float face2 = vbuffer[index + 1];
					float face3 = vbuffer[index + 2];

					if (max < face1 && max < face2 && max < face3) {
						vbuffer[index + 1 + size * 3] = max;
						vbuffer[index + 2 + 3 /*  */] = max;
						if (lRowVis[itr2 + 1] <= itr3 && lRowVis[itr2] <= itr3) { // <= because diagonals are fine
							break; // if depended on rows are out of sight from here, skip
						}
						continue; // if none of the faces had sight, skip this step
					}

					xyz.set(quadr.axis3).mul(itr3).add(vit2); // world position
					xyzbp.set(xyz.x, xyz.y, xyz.z);
					// each face alpha values (assume faces values are multiplied by the block alpha)
					float resistance = aprov.getResistance(xyzbp, blockstate);

					face1 += resistance;
					face2 += resistance;
					face3 += resistance;

					if (max < face1 && max < face2 && max < face3) {
						rowVis = itr3;
					} else if (isNotDuplicatedEdge(quadr, itr1, itr2, itr3)) {
						// output the sight unless its an edge without the priority and therefore skip to avoid duplicated edges output
						float voxelvisi = facesToVolumeValue(face1, itr1, face2, itr2, face3, itr3);

						if (voxelvisi <= max) {
							float manDist = Math.abs(xyz.x - origin.x) + Math.abs(xyz.y - origin.y) + Math.abs(xyz.z - origin.z);
							float dist = (float) xyz.distance(origin);
							voxelvisi *= dist / manDist;
							if (voxelvisi <= rmax) {
								scons.consume(xyzbp, voxelvisi, blockstate[0]);
							}
						}
					}
					face1 += 0.22500001f;
					face2 += 0.22500001f;
					face3 += 0.22500001f;

					// weights are similar to 18 neigbors 3d classic gbv, aka it1, it2 and it3 except for those 3
					float f4w1 = max(0, itr1 - max(itr2, itr3) + UNPOINTLIGHT_FIX); // weight 1 for face 4. reach 0 when either it2 or it3 reach it1
					float f5w2 = max(0, itr2 - max(itr1, itr3) + UNPOINTLIGHT_FIX); // weight 2 for face 5
					float f6w3 = max(0, itr3 - max(itr1, itr2) + UNPOINTLIGHT_FIX); // weight 3 for face 6

					applyFaces456(vbuffer, max, index, size, face1, face2, face3, itr1, itr2, itr3, f4w1, f5w2, f6w3);
				}
				lRowVis[itr2 + 1] = rowVis;
			}
		}

	}

	private static void applyFaces456(float[] vbuffer, float max, int index, int size, //
			float face1, float face2, float face3, //
			float itr1, float itr2, float itr3, //
			float f4w1, float f5w2, float f6w3 //
	) {
		float face4 = max, face5 = max, face6 = max;
		if (!(max < face1 && max < face2 && max < face3)) {
			face4 = interpolate(face1, f4w1, face2, itr2, face3, itr3);
			face5 = interpolate(face1, itr1, face2, f5w2, face3, itr3);
			face6 = interpolate(face1, itr1, face2, itr2, face3, f6w3);
		}

		// write the results to the non exposed faces of this voxel (which are also the exposed faces of later processed voxels)
		vbuffer[index + 0 /*      */] = face4;
		vbuffer[index + 1 + size * 3] = face5;
		vbuffer[index + 2 + 3 /*  */] = face6;
	}

}
