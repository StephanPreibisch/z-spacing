package org.janelia.correlations.storage;

import java.util.HashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import org.janelia.utility.sampler.DenseXYSampler;
import org.janelia.utility.tuple.SerializableConstantPair;




/**
 * @author Philipp Hanslovsky <hanslovskyp@janelia.hhmi.org>
 *
 * A {@link CorrelatonsObject} calculates and stores the parameters of a fit to
 * correllation data. Use {@link CorrelationsObject.Options} to specify the fitter
 * as well as the sample ranges (by stride and fitIntervalLength) of the fit.
 * {@link CorrelationsObject.Meta} stores meta information, such as the position
 * of the current image in z-direction.
 *
 */
public class CorrelationsObject extends AbstractCorrelationsObject implements CorrelationsObjectInterface {



	private static final long serialVersionUID = -7390337451098486971L;
	private final HashMap<Long, RandomAccessibleInterval<FloatType> > correlationsMap;
	private final HashMap<Long, RandomAccessibleInterval<FloatType> > fitMap;



	/**
	 * @return the correlationsMap
	 */
	public HashMap<Long, RandomAccessibleInterval<FloatType>> getCorrelationsMap() {
		return correlationsMap;
	}


	/**
	 * @return the metaMap
	 */
	@Override
	public TreeMap<Long, Meta> getMetaMap() {
		return metaMap;
	}


	/**
	 * @return the fitMap
	 */
	public HashMap<Long, RandomAccessibleInterval<FloatType>> getFitMap() {
		return fitMap;
	}


	/**
	 * @return the zMin
	 */
	@Override
	public long getzMin() {
		return zMin;
	}


	/**
	 * @return the zMax
	 */
	@Override
	public long getzMax() {
		return zMax;
	}


	public CorrelationsObject(
			final HashMap<Long, RandomAccessibleInterval<FloatType>> correlationsMap,
			final TreeMap<Long, Meta> metaMap) {
		super( metaMap );
		this.correlationsMap = correlationsMap;
		this.fitMap = new HashMap<Long, RandomAccessibleInterval<FloatType>>();

	}


	public CorrelationsObject() {
		this(new HashMap<Long, RandomAccessibleInterval<FloatType>>(),
				new TreeMap<Long, Meta>());
	}


	public void addCorrelationImage(final long index,
			final RandomAccessibleInterval<FloatType> correlations,
			final Meta meta)
	{
		this.correlationsMap.put(index, correlations);
		this.addToMeta( index, meta );
	}


	@Override
	public ArrayImg<DoubleType, DoubleArray> toMatrix (
			final long x,
			final long y) {
        final ArrayImg<DoubleType, DoubleArray> matrix = ArrayImgs.doubles( metaMap.size(), metaMap.size() );
        toMatrix( x, y, matrix );
        return matrix;
	}


	@Override
	public void toMatrix(
			final long x,
			final long y,
			final RandomAccessibleInterval<DoubleType> matrix) {
		for ( final DoubleType m : Views.flatIterable( matrix ) ) {
            m.set( Double.NaN );
		}



	    for ( long zRef = zMin; zRef < zMax; ++zRef ) {
	   	     final RandomAccessibleInterval<FloatType> correlationsAt = this.correlationsMap.get( zRef );
	            final long relativeZ = zRef - zMin;
	            final IntervalView<DoubleType> row = Views.hyperSlice( matrix, 1, relativeZ);

	            final RandomAccess<FloatType> correlationsAccess = correlationsAt.randomAccess();
	            final RandomAccess<DoubleType> rowAccess         = row.randomAccess();

	            correlationsAccess.setPosition( x, 0 );
	            correlationsAccess.setPosition( y, 1 );
	            correlationsAccess.setPosition( 0, 2 );

	            final Meta meta = this.metaMap.get( zRef );

	            rowAccess.setPosition( Math.max( meta.zCoordinateMin - zMin, 0 ), 0 );

	            for ( long zComp = meta.zCoordinateMin; zComp < meta.zCoordinateMax; ++zComp ) {
	                    if ( zComp < zMin || zComp >= zMax ) {
	                            correlationsAccess.fwd( 2 );
	                            continue;
	                    }
	                    rowAccess.get().set( correlationsAccess.get().getRealDouble() );
	                    rowAccess.fwd( 0 );
	                    correlationsAccess.fwd( 2 );

	            }
	    }

	}


	@Override
	public Set<SerializableConstantPair<Long, Long>> getXYCoordinates() {
		final Long firstKey = this.metaMap.firstKey();
		final RandomAccessibleInterval<FloatType> firstEl = this.correlationsMap.get( firstKey );
		final DenseXYSampler sampler = new DenseXYSampler( firstEl.dimension( 0 ), firstEl.dimension( 1 ) );
		final TreeSet<SerializableConstantPair<Long, Long>> result = new TreeSet< SerializableConstantPair<Long, Long> >();
		for ( final SerializableConstantPair<Long, Long> s : sampler ) {
			result.add( s );
		}
		return result;
	}


	@Override
	public long getxMin() {
		return 0;
	}


	@Override
	public long getyMin() {
		return 0;
	}


	@Override
	public long getxMax() {
		if ( this.correlationsMap.size() > 0 )
			return this.correlationsMap.values().iterator().next().dimension( 0 );
		else
			return 0;
	}


	@Override
	public long getyMax() {
		if ( this.correlationsMap.size() > 0 )
			return this.correlationsMap.values().iterator().next().dimension( 1 );
		else
			return 0;
	}


}