package org.janelia.thickness.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.plugin.FolderOpener;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.FloatStatistics;
import ij.process.ImageConverter;
import ini.trakem2.utils.Utils;
import mpicbg.ij.util.Filter;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.TranslationModel1D;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.RealRandomAccessibleRealInterval;
import net.imglib2.converter.RealDoubleConverter;
import net.imglib2.converter.read.ConvertedRandomAccessibleInterval;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayRandomAccess;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.transform.Transform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.TransformView;
import net.imglib2.view.Views;

import org.janelia.thickness.inference.InferFromMatrix;
import org.janelia.thickness.inference.Options;
import org.janelia.thickness.inference.visitor.LazyVisitor;
import org.janelia.thickness.lut.LUTRealTransform;
import org.janelia.thickness.lut.PermutationTransform;
import org.janelia.thickness.lut.SingleDimensionLUTRealTransform;
import org.janelia.thickness.lut.SingleDimensionPermutationTransform;
import org.janelia.thickness.mediator.OpinionMediatorWeightedAverage;
import org.janelia.utility.arrays.ArraySortedIndices;

public class ZPositionCorrection implements PlugIn {

	@Override
	public void run(String arg0) {
		
		Options options = Options.generateDefaultOptions();
		
		final GenericDialogPlus dialog = new GenericDialogPlus( "Correct layer z-positions" );
		dialog.addMessage( "Data source settings : " );
		dialog.addFileField( "Input path (use current image if empty)", "" );
		dialog.addChoice( "Type of input data : ", new String[] { "Matrix", "Image Stack" }, "Image Stack" );
		dialog.addMessage( "Inference settings : " );
		dialog.addMessage( "Section neighbor range :" );
		dialog.addNumericField( "test_maximally :", options.comparisonRange, 0, 6, "layers" );
		dialog.addMessage( "Optimizer :" );
		dialog.addNumericField( "outer_iterations :", options.nIterations, 0, 6, "" );
		dialog.addNumericField( "outer_regularization :", options.shiftProportion, 2, 6, "" );
		dialog.addNumericField( "inner_iterations :", options.multiplierEstimationIterations, 0, 6, "" );
		dialog.addNumericField( "inner_regularization :", options.multiplierGenerationRegularizerWeight, 2, 6, "" );
		dialog.addCheckbox( " allow_reordering", options.withReorder );


		dialog.showDialog();
		
		if ( dialog.wasCanceled() )
			return;
		
		String inputPath      = dialog.getNextString();
		boolean inputIsMatrix = dialog.getNextChoiceIndex() == 0;
		ImagePlus input       = inputPath.equals( "" ) ? IJ.getImage() : FolderOpener.open( inputPath );
		
		options.comparisonRange                       = (int) dialog.getNextNumber();
		options.nIterations                           = (int) dialog.getNextNumber();
		options.shiftProportion                       = dialog.getNextNumber();
		options.multiplierEstimationIterations        = (int) dialog.getNextNumber();
		options.multiplierGenerationRegularizerWeight = dialog.getNextNumber();
		options.withReorder                           = dialog.getNextBoolean();
		options.minimumSectionThickness               = 0.0;
		
		FloatProcessor matrixFp = inputIsMatrix ? 
				normalize( input ).getProcessor().convertToFloatProcessor() : 
					calculateSimilarityMatrix( input, options.comparisonRange );
		
		RandomAccessibleInterval<DoubleType> matrix = wrapDouble( new ImagePlus( "", matrixFp ) );
		
		if ( matrix == null )
			return;
		
		if ( ! inputIsMatrix ) ImageJFunctions.show( matrix );
		
		double[] startingCoordinates = new double[ (int) matrix.dimension(0) ];
		for (int i = 0; i < startingCoordinates.length; i++)
			startingCoordinates[i] = i;
		
		InferFromMatrix<TranslationModel1D> inf = new InferFromMatrix< TranslationModel1D >( new TranslationModel1D(), new OpinionMediatorWeightedAverage() );
		
		boolean estimatedSuccessfully = false;
		double[] transform = null;
		try {
			transform = inf.estimateZCoordinates( matrix, startingCoordinates, new LazyVisitor(), options );
			estimatedSuccessfully = true;
		} catch (NotEnoughDataPointsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllDefinedDataPointsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		IJ.log( "After estimate " + estimatedSuccessfully );
		IJ.log( options.toString() );
		System.out.println( options.toString() );
		
		
		
		if ( estimatedSuccessfully ) {
			
			IJ.log( Arrays.toString( transform ) );
			boolean renderTransformedStack = false;
			
			double[] sortedTransform = transform.clone();
			int[] forward            = new int[ sortedTransform.length ];
			int[] backward           = new int[ sortedTransform.length ];
			
			if ( options.withReorder )
				ArraySortedIndices.sort( sortedTransform, forward, backward );
			else {
				for ( int i = 0; i < forward.length; ++i ) {
					forward[i]  = i;
					backward[i] = i;
				}
			}
			
			int[] permutationArray = backward; // use backward?
			
			PermutationTransform permutation = new PermutationTransform( permutationArray, 2, 2 );
			LUTRealTransform lut = new LUTRealTransform( sortedTransform, 2, 2 );
			RealRandomAccessible<DoubleType> transformedMatrix = generateTransformed( matrix, permutation, lut, new DoubleType( Double.NaN ) );
			
			ImageJFunctions.show( Views.interval( Views.raster( transformedMatrix ), matrix ), "Warped matrix" );
			
			final GenericDialogPlus renderDialog = new GenericDialogPlus( "Rendering." );
			renderDialog.addCheckbox( "Render stack?", renderTransformedStack );
			if ( inputIsMatrix ) renderDialog.addFileField( "Input path (use current image if empty)", "" );
			renderDialog.showDialog();
			
			if ( renderDialog.wasCanceled() )
				return;
			
			renderTransformedStack = renderDialog.getNextBoolean();
			
			if ( renderTransformedStack ) {
				ImagePlus stackImp = inputIsMatrix ? getFileFromOption( renderDialog.getNextString() ) : input;
				stackImp.show();
				new ImageConverter( stackImp ).convertToGray32();
				RandomAccessibleInterval< FloatType > stack = ImageJFunctions.wrapFloat( stackImp ); 
				
				SingleDimensionPermutationTransform permutation1D = new SingleDimensionPermutationTransform( permutationArray, 3, 3, 2 );
				SingleDimensionLUTRealTransform lut1D             = new SingleDimensionLUTRealTransform( sortedTransform, 3, 3, 2 );
				
				RealRandomAccessible<FloatType> transformedStack = generateTransformed( stack, permutation1D, lut1D, new FloatType( Float.NaN ) );
				
				IJ.log( "c " + transformedStack );
				IJ.log( "d " + stack );
				IJ.log( "e " + stackImp );
				FinalInterval interval = new FinalInterval( stack.dimension( 0 ), stack.dimension( 1 ), stack.dimension( 2 ) );
				ImageJFunctions.show( Views.interval( Views.raster( transformedStack ), interval ), "Warped image stack." );
				IJ.log( "Done." );
			}
			
		}

	}
	
	public static RandomAccessibleInterval< DoubleType > wrapDouble( ImagePlus input ) {
		return new ConvertedRandomAccessibleInterval< FloatType, DoubleType >( ImageJFunctions.wrapFloat( input ), new RealDoubleConverter< FloatType >(), new DoubleType() );
	}
	
	public static ImagePlus normalize( ImagePlus input ) {
		FloatProcessor fp = input.getProcessor().convertToFloatProcessor();
		FloatStatistics stat = new FloatStatistics( fp );
		float[] array = (float[])fp.getPixels();
		for ( int i = 0; i < array.length; ++i )
			array[i] /= stat.max;
		return input;
	}
	
	public static RandomAccessibleInterval< DoubleType > normalizeAndWrap( ImagePlus input ) {
		return wrapDouble( normalize( input ) );
	}
	
	
	public static FloatProcessor calculateSimilarityMatrix( ImagePlus input, int range ) {
		GenericDialog dialog = new GenericDialog( "Choose similiarity calculation method" );
		dialog.addChoice("Similarity_method :", new String[]{ "NCC (aligned)", "SIFT consensus (unaligned)" }, "NCC (aligned)" );
		dialog.showDialog();
		
		if ( dialog.wasCanceled() )
			return null;
		
		int method = dialog.getNextChoiceIndex();
		FloatProcessor matrix = createEmptyMatrix( input.getStack().getSize() );
		
		boolean similarityCalculationWasSuccessful = false;
		switch ( method ) {
		case 1:
			similarityCalculationWasSuccessful = invokeSIFT( input, range, matrix );
		default:
			similarityCalculationWasSuccessful = invokeNCC( input, range, matrix );
		}
		if ( similarityCalculationWasSuccessful )
			return matrix;
		else
			return null;
	}
	
	
	public static void main(String[] args) {
		new ZPositionCorrection().run( "" );
	}
	
	
	public static boolean invokeSIFT( ImagePlus input, int range, FloatProcessor matrix ) {
		// TODO IMPLEMENT
		return false;
	}
	
	
	public static boolean invokeNCC( ImagePlus input, final int range, final FloatProcessor matrix ) {
		new ImageConverter( input ).convertToGray32();
		ImageStack stackSource = input.getStack();
		
		GenericDialog dialog = new GenericDialog( "NCC options" );
		dialog.addNumericField( "Scale xy before similarity calculation", 1.0, 4 );
		dialog.showDialog();
		if ( dialog.wasCanceled() )
			return false;
		
		double xyScale = dialog.getNextNumber();
		
		final ImageStack stack = xyScale == 1.0 ? stackSource : downsampleStack( stackSource, xyScale );
		final int height = input.getHeight();
		final int nThreads = Runtime.getRuntime().availableProcessors();
		ArrayList<Callable<Void>> callables = new ArrayList< Callable< Void > >();
		for ( int i = 0; i < height; ++i ) {
			final int finalI = i;  
			callables.add( new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					for ( int k = finalI + 1; k - finalI <= range && k < height; ++k ) {
						float val = new RealSumFloatNCC( (float[])stack.getProcessor( finalI + 1 ).getPixels(), (float[])stack.getProcessor( k + 1 ).getPixels() ).call().floatValue();
						matrix.setf( finalI, k, (float)val );
						matrix.setf( k, finalI, (float)val );
					}
					return null;
				}
			});
		}
		ExecutorService es = Executors.newFixedThreadPool( nThreads );
		try {
			es.invokeAll( callables );
		} catch (InterruptedException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	
	public static ImageStack downsampleStack( ImageStack stackSource, double xyScale ) {
		ImageStack stack = new ImageStack((int)Math.round( stackSource.getWidth()*xyScale), (int)Math.round( stackSource.getHeight()*xyScale));
		for ( int z = 1; z <= stackSource.getSize(); ++z ) {
			stack.addSlice(Filter.createDownsampled(
	                stackSource.getProcessor(z),
	    	        xyScale,
	    	        0.5f,
	                0.5f));
		}
		return stack;
	}
	
	
	public static FloatProcessor createEmptyMatrix( int height ) {
		FloatProcessor matrix = new FloatProcessor( height, height );
		matrix.add( Double.NaN );
		for ( int i = 0; i < height; ++i )
			matrix.setf( i, i, 1.0f );
		return matrix;
	}

	
	public static ImagePlus getFileFromOption( String path ) {
		return path.equals( "" ) ? IJ.getImage() : 
			( new File( path ).isDirectory() ? FolderOpener.open( path ) : new ImagePlus( path ) );
	}
	
	
	public static < T extends RealType< T > > RealRandomAccessible< T > generateTransformed( 
			RandomAccessibleInterval< T > input,
			Transform permutation,
			InvertibleRealTransform lut,
			T dummy
			) {
		dummy.setReal( Double.NaN );
		TransformView< T > extendedAndPermuted = new TransformView<T>( Views.extendValue( input, dummy ), permutation );
		IJ.log( "a " + extendedAndPermuted );
		RealRandomAccessible< T > interpolated = Views.interpolate( extendedAndPermuted, new NLinearInterpolatorFactory< T >() );
		IJ.log( "b " + interpolated );
		return RealViews.transformReal( interpolated, lut );
	}

}
