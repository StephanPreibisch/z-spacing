package org.janelia.thickness.lut;

import net.imglib2.Dimensions;
import net.imglib2.ExtendedRandomAccessibleInterval;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import net.imglib2.view.composite.CompositeIntervalView;
import net.imglib2.view.composite.RealComposite;

public abstract class AbstractLUTGrid implements InvertibleRealTransform {
	
	protected final int numSourceDimensions; // number of input dimensions of the transform
	protected final int numTargetDimensions; // number of output dimensions of the transform
	protected final int lutMaxIndex; // max index of the look-up table
	protected final int nNonTransformedCoordinates; // number of grid dimensions (one less than lutArray.numDimensions())
	protected final Dimensions dimensions; // actual grid dimensions
	protected final ArrayImg< DoubleType, DoubleArray > lutArray; // look-up tables
	final protected RealRandomAccessible< RealComposite< DoubleType > > coefficients; // interpolated composite of lutArray
	final protected InterpolatorFactory< RealComposite< DoubleType >, RandomAccessible< RealComposite< DoubleType > > > interpolatorFactory = 
			new NLinearInterpolatorFactory< RealComposite< DoubleType > >(); // how to interpolate for coefficients
	protected RealRandomAccess<RealComposite<DoubleType>> access; // temporary variables 
	protected RealComposite< DoubleType > currentLut; // temporary variables 

	public AbstractLUTGrid(final int numSourceDimensions, final int numTargetDimensions,
			final ArrayImg<DoubleType, DoubleArray> lutArray) {
		super();
		this.numSourceDimensions = numSourceDimensions;
		this.numTargetDimensions = numTargetDimensions;
		this.lutArray = lutArray;
		
		final CompositeIntervalView<DoubleType, RealComposite<DoubleType>> collapsedSource = Views.collapseReal( lutArray );
		this.dimensions = new FinalInterval( collapsedSource );
		this.nNonTransformedCoordinates = this.dimensions.numDimensions();
		this.lutMaxIndex = (int) (this.lutArray.dimension( this.nNonTransformedCoordinates ) );
		final ExtendedRandomAccessibleInterval<RealComposite<DoubleType>, CompositeIntervalView<DoubleType, RealComposite<DoubleType>>> extendedCollapsedSource = Views.extendBorder( collapsedSource );
		this.coefficients = Views.interpolate( extendedCollapsedSource, this.interpolatorFactory );
		this.access = this.coefficients.realRandomAccess();
		this.currentLut = this.access.get();
		
		
	}

	@Override
	public int numSourceDimensions() {
		return this.numSourceDimensions;
	}

	@Override
	public int numTargetDimensions() {
		return this.numTargetDimensions;
	}
	
	protected double apply( final double lutCoordinate ) {
//		this.updateCoordinates( gridCoordinates );
		this.currentLut = this.access.get();
		
		final int zFloor = ( int ) lutCoordinate;
		final double floorVal = this.currentLut.get( zFloor ).get();
		final double nextVal  = this.currentLut.get( zFloor + 1 ).get();
		final double dz = lutCoordinate - zFloor;
		
//		System.out.println( String.format("lutCoordinate=%f, zFloor=%d, floorVal=%f, nextVal=%f, dz=%f, return=%f",
//				lutCoordinate,
//				zFloor,
//				floorVal,
//				nextVal,
//				dz,
//				( nextVal - floorVal ) * dz + floorVal));
		return ( nextVal - floorVal ) * dz + floorVal;
		
	}
	
	protected double applyChecked( final double lutCoordinate ) {
		if ( lutCoordinate < 0 )
			return -Double.MAX_VALUE;
		else if ( lutCoordinate > this.lutMaxIndex )
			return Double.MAX_VALUE;
		else
			return apply( lutCoordinate );
	}
	
	
	/**

	 * Implemented as bin-search.
	 * 
	 * @return
	 */
	protected int findFloorIndex( final double realLutCoordinate )
	{
//		this.updateCoordinates( gridCoordinates );
		this.currentLut = this.access.get();
		
		int min = 0;
		int max = this.lutMaxIndex;
		int i = max >> 1;
		do
		{
			if ( currentLut.get( i ).get() > realLutCoordinate )
				max = i;
			else
				min = i;
			i = ( ( max - min ) >> 1 ) + min;
		}
		while ( i != min );
		return i;
	}
	
	
	public double applyInverse( final double realLutCoordinate ) {
		final int i = this.findFloorIndex( realLutCoordinate );
		
//		this.updateCoordinates( gridCoordinates );
		this.currentLut = this.access.get();
		
		final double realZ1 = this.currentLut.get( i ).get();
		final double realZ2 = this.currentLut.get( i + 1 ).get();
		
		return( realLutCoordinate - realZ1 ) / (realZ2 - realZ1 ) + i;
	}
	
	
	public double applyInverseChecked( final double realLutCoordinate ) {
//		this.updateCoordinates( gridCoordinates );
		this.currentLut = this.access.get();
		if ( realLutCoordinate < this.currentLut.get( 0 ).get() )
			return -Double.MAX_VALUE;
		if ( realLutCoordinate > this.currentLut.get( this.lutMaxIndex ).get() )
			return Double.MAX_VALUE;
		else
			return this.applyInverse( realLutCoordinate );
	}
	
	
	public double minTransformedCoordinate( final double[] gridCoordinates )
	{
		this.updateCoordinates( gridCoordinates );
		return this.access.get().get( 0 ).get();
	}
	
	
	public double maxTransformedCoordinate( final double[] gridCoordinates )
	{
		this.updateCoordinates( gridCoordinates );
		return this.access.get().get( this.lutMaxIndex ).get();
	}
	
	protected void updateCoordinates( final double[] gridCoordinates ) {
		for ( int d = 0; d < this.nNonTransformedCoordinates; ++d ) {
			this.access.setPosition( gridCoordinates[d], d);
		}
	}
	
	protected void updateCoordinates( final float[] gridCoordinates ) {
		for ( int d = 0; d < this.nNonTransformedCoordinates; ++d ) {
			this.access.setPosition( gridCoordinates[d], d);
		}
	}
	
	protected void updateCoordinates(final RealLocalizable gridCoordinates ) {
		for ( int d = 0; d < this.nNonTransformedCoordinates; ++d ) {
			this.access.setPosition( gridCoordinates.getDoublePosition( d ), d);
		}
	}


	

}