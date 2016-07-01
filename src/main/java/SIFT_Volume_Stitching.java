/*
 * To the extent possible under law, the Fiji developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.process.ImageProcessor;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.*;
import mpicbg.models.*;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import java.awt.*;
import ij.measure.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.awt.Color;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import stitching.ImageInformation;
import static stitching.CommonFunctions.LIN_BLEND;
import static stitching.CommonFunctions.methodListCollection;

import mpicbg.models.TranslationModel3D;
import mpicbg.models.AffineModel3D;

/**
* Align and stitch two stacks of images assumed to be contiguous using automatically extracted robust landmark
* correspondences.
* @author Chloe Murtin <chloe.murtinl@gmail.com> and Carole Frindel <carole.frindel@creatis.insa-lyon.fr>
* @version 0.0
*/
public class SIFT_Volume_Stitching implements PlugIn, KeyListener
{
	
	/**
	 * Main method for debugging.
	 *
	 * For debugging, it is convenient to have a method that starts ImageJ, loads an
	 * image and calls the plugin, e.g. after setting breakpoints.
	 *
	 * @param args unused
	 */
	public static void main(String[] args) {
		// set the plugins.dir property to make the plugin appear in the Plugins menu
		Class<?> clazz = SIFT_Volume_Stitching.class;
		String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
		String pluginsDir = url.substring("file:".length(), url.length() - clazz.getName().length() - ".class".length());
		System.setProperty("plugins.dir", pluginsDir);

		// start ImageJ
		new ImageJ();

		// open the Clown sample
		ImagePlus image1 = IJ.openImage("https://www.creatis.insa-lyon.fr/~frindel/BackStack115.tif");
		ImagePlus image2 = IJ.openImage("https://www.creatis.insa-lyon.fr/~frindel/FrontStack.tif");
		image1.show();
		image2.show();

		// run the plugin
		IJ.runPlugIn(clazz.getName(), "");
	}

    private List< Feature > fsf = new ArrayList< Feature >();
    private List< Feature > fsb = new ArrayList< Feature >();
	private ImagePlus impf; // Original Front Stack
	private ImagePlus impb; // Original Back Stack
    private ImagePlus impAlignedZYX; // Aligned Back Stack
	private ImagePlus frontTemplate;
	private ImagePlus backTemplate;
	private ImagePlus channel2front;
	private ImagePlus channel2back;
	private ImagePlus channel3front;
	private ImagePlus channel3back;
    private boolean modelFound;
	private AffineModel3D BestModel3D = new AffineModel3D();
	public boolean Reg3D = true;
	boolean template_bool = false;
	final static public String[] stitchingModelStrings = new String[]{ "Front - Back", "Back - Front", "Left - Right", "Right - Left", "Top - Bottom", "Bottom - Top" };
	public String stitchingMethod = "Front - Back";
	final static public String[] OVMethod = new String[]{ "Slice-by-Slice", "Block-by-Block" };
	public String myOVMethod = "Slice-by-Slice";
	
	/** Fusion method*/
	public String fusionMethod = methodListCollection[LIN_BLEND];
	public double alpha = 1.5;
    
	/** Class for SIFT parameters*/
    static private class Param
    {        
		public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();
                
        /**Closest/next closest neighbor distance ratio*/
        public float rod = 0.92f;
                
        /** Maximal allowed alignment error in px*/
        public float maxEpsilon = 25.0f;
                
        /** Inlier/candidates ratio*/
        public float minInlierRatio = 0.05f;
                
        public int modelIndex = 1;
		
    }
	
	static Param p = new Param();
    
	/**
	 * Main method of the plugin
	 * @param args unused
	 */
    final public void run( final String args )
    {
        fsf.clear();
        fsb.clear();
                
        if ( IJ.versionLessThan( "1.41n" ) ) return;
		
		/** Estimated Overlap size*/
		int ov = 100;
				
		/**Image Choice Dialog Box*/
		Font myfont = new Font("SansSerif", Font.BOLD, 12);
		final int[] ids = WindowManager.getIDList();
		if ( ids == null || ids.length < 2 )
		{
			IJ.showMessage( "You should have at least two images open." );
			return;
		}
		
		final String[] titles = new String[ ids.length ];
		final int[] sSizes = new int[ ids.length ];
        	for ( int i = 0; i < ids.length; ++i )
        	{
            		titles[ i ] = ( WindowManager.getImage( ids[ i ] ) ).getTitle();
					sSizes[ i ] = ( WindowManager.getImage( ids[ i ] ) ).getStackSize();
        	}
				
		final GenericDialog gd = new GenericDialog( "2D-SIFT in 3D-Space Volume Stitching" );
		gd.addMessage( "* Image Selection", myfont );
		final String current = WindowManager.getCurrentImage().getTitle();
		gd.addChoice( "Front_Image", titles, current );
		gd.addChoice( "Back_Image", titles, current.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );
		
		gd.addChoice( "Stitching Orientation", stitchingModelStrings, stitchingMethod );
		
		gd.addMessage( "* Overlap Detection Method", myfont );
		
		gd.addChoice( "Method Selection", OVMethod, OVMethod[ 0 ] );
		WindowManager.getCurrentImage().getStackSize(); 
		gd.addNumericField( "Split (Block-by-Block)", 5, 0, 4, "" );
		gd.addNumericField( "Overlap Size (Slice-by-Slice)", 100, 0, 4, "" );
		
		
		boolean c2 = false; boolean c3 = false;
		gd.addMessage( "* Additional Channels", myfont );
		gd.addCheckbox( "Channel 2", c2 );
		gd.addChoice( "Channel_2_Front_Image", titles, current );
		gd.addChoice( "Channel_2_Back_Image", titles, current.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );
		gd.addCheckbox( "Channel 3", c3 );
		gd.addChoice( "Channel_3_Front_Image", titles, current );
		gd.addChoice( "Channel_3_Back_Image", titles, current.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );
		
		int it = 2; int MIP = 50;
		gd.addMessage( "* 3D Registration Parameters", myfont );
		gd.addCheckbox( "3D Registration", Reg3D );
		gd.addNumericField( "Number_Of_Iterations", it, 0, 4, "" );
		gd.addNumericField( "MIP size", MIP, 0, 4, "Slices" );
		
	    p.sift.steps = 5; p.sift.initialSigma = 1.6f;
		gd.addMessage( "* SIFT Parameters", myfont );
		gd.addNumericField( "Initial_Gaussian_Blur :", p.sift.initialSigma, 1, 4, "pixels" ); 
		gd.addNumericField( "Steps_Per_Scales_Octave", p.sift.steps, 0, 4, "" );
		gd.addNumericField( "Minimum_Image_Size :", p.sift.minOctaveSize, 0, 4, "pixels" );
		gd.addNumericField( "Maximum_Image_Size :", p.sift.maxOctaveSize, 0, 4, "pixels" );
		
		gd.addMessage( "* Filtered Image Selection", myfont );
		gd.addCheckbox( "Compare Filtered Images", template_bool );
		gd.addChoice( "Front_Filtered_Image", titles, current );
		gd.addChoice( "Back_Filtered_Image", titles, current.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );

		gd.addMessage( "* Fusion Method", myfont );
		gd.addChoice( "Method", methodListCollection, fusionMethod );
		
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		long start_time = System.currentTimeMillis();
		
		impf = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
		impb = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );

		
		stitchingMethod = stitchingModelStrings[ gd.getNextChoiceIndex() ];
		
		myOVMethod = stitchingModelStrings[ gd.getNextChoiceIndex() ];
		int split = (int) gd.getNextNumber();
		ov = (int) gd.getNextNumber();
		
		int indb = impb.getStackSize();
		
		c2 = gd.getNextBoolean();
		channel2front = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
		channel2back = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
		c3 = gd.getNextBoolean();
		channel3front = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
		channel3back = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
		
		if ( stitchingMethod != "Front - Back"){
			impf = stackOrientation(impf, stitchingMethod); 
			impb = stackOrientation(impb, stitchingMethod);
			channel2front = stackOrientation(channel2front, stitchingMethod); 
			channel2back = stackOrientation(channel2back, stitchingMethod);
			channel3front = stackOrientation(channel3front, stitchingMethod); 
			channel3back = stackOrientation(channel3back, stitchingMethod);
		}
		
		Reg3D = gd.getNextBoolean();
		it = (int) gd.getNextNumber();
		MIP = (int) gd.getNextNumber();
		
		template_bool = gd.getNextBoolean();
		
		p.sift.initialSigma = (float) gd.getNextNumber();
	    p.sift.steps = (int) gd.getNextNumber();
		p.sift.minOctaveSize = (int) gd.getNextNumber();
	    p.sift.maxOctaveSize = (int) gd.getNextNumber();
		
		backTemplate = null; frontTemplate = null;
		if (template_bool)
		{
			
			/** If use of a template exchange between template and real channel **/
			
			frontTemplate = new ImagePlus( impf.getTitle(), impf.getStack() );
			backTemplate = new ImagePlus( impb.getTitle(), impb.getStack() );
			
			impf = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
			impb = WindowManager.getImage( ids[ gd.getNextChoiceIndex() ] );
	

			if ( stitchingMethod != "Front - Back"){
				impf = stackOrientation(impf, stitchingMethod); 
				impb = stackOrientation(impb, stitchingMethod);
			}
			
		}
		
		/** Copy */
		ImagePlus impf2 = new ImagePlus(impf.getTitle(),impf.getStack());
		impf2.setCalibration(impf.getCalibration());
		
		impAlignedZYX = new ImagePlus(impb.getTitle(),impb.getStack());
		impAlignedZYX.setCalibration(impb.getCalibration());
		
		if ( impb.getStackSize() < ov )
		{
			IJ.showMessage( "The expected overlap exceed the back stack size." );
			return;
		}
		
		fusionMethod = gd.getNextChoice(  );

	    /** SIFT parameters Setting */	
		p.modelIndex = 3; // 1: Rigid Model, 3 : Affine Model (include the scale)
	    p.sift.fdSize = 4; 
	    p.sift.fdBins = 8; // Number of tested directions
	    p.rod = 0.92f;

	    p.maxEpsilon = 25.0f;
	    p.minInlierRatio = 0.05f;

	    /** Divers Variables*/
		ImagePlus impAlignedZYX2;
		
		boolean showStep = false;
		
		
		
		/** -------------------------- STEP 1: PREPROCESSING -------------------------- */
		
		IJ.log( "* IMAGE STACK REGISTRATION *" );
		IJ.log( " " );
		
		IJ.log("Stitching Orientation " + stitchingMethod);
		IJ.log("MIP Size " + MIP);
		IJ.log( " " );
		
		/** SIFT Object */
		FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
		SIFT ijSIFT = new SIFT( sift );
		
		
		
		/** -------------------------- STEP 2: OVERLAP EXTRACTION -------------------------- */
		
		double[] data = new double[12];
		
		if ( Reg3D ){
		for (int l = 1; l<=it; ++l) // Iterations
		{
		
		ImageStack stackZf = impf.getStack();
		ImageStack stackZb = impAlignedZYX.getStack();
			
		fsf.clear();
        fsb.clear();
			
		int l2 = 3*l-2;
		IJ.log("STEP " + l2 + ": BEST Z ROTATION");
		
		/** Find Back Image Overlap */
		
		if (myOVMethod == "Slice-by-Slice"){indb = OverlapFinderSbS(stackZf, stackZb, ijSIFT, ov);}
		else if (myOVMethod == "Block-by-Block"){indb = recursiveOverlapFinder(stackZf, stackZb, ijSIFT, stackZb.getSize(), split);}
		
		IJ.log(" Overlap Size " + indb );
		
		ImageStack subStackZf = stackZf.crop(0, 0, stackZf.getSize() - indb, stackZf.getWidth(), stackZf.getHeight(), indb);
		ImageStack subStackZb = stackZb.crop(0, 0, 0, stackZb.getWidth(), stackZb.getHeight(), indb);
		fsf.clear();
		fsb.clear();
		
		AbstractAffineModel2D< ? > BestModelZ = CompareCrossSection(subStackZf, subStackZb, ijSIFT, MIP);
		IJ.log("STEP 2");
		/** Back Substack Affine Registration */ 
		if ( modelFound )
		{
			Model3D(null, null, BestModelZ);
			impAlignedZYX = Rotation3D(impb, BestModel3D);
		}
        else{
            IJ.log( "No model found for the data" );
        }

		/** Show intermediate steps */
		
		BestModel3D.toArray(data);
		IJ.log("1| "+ data[0] + "\t| " + data[3] + "\t| " + data[6] + "\t| " + data[9]);
		IJ.log("2| "+ data[1] + "\t| " + data[4] + "\t| " + data[7] + "\t| " + data[10]);
		IJ.log("3| "+ data[2] + "\t| " + data[5] + "\t| " + data[8] + "\t| " + data[11]);
		
		

        /** -------------------------- STEP 3: BEST X ROTATION -------------------------- */

		l2 = 3*l-1;
		IJ.log(" ");
		IJ.log("STEP " + l2 + ": BEST X ROTATION");
		
		/** Parameter setting */
		fsf.clear();
        fsb.clear();	
		
		/** Rotation of the front substack around y */
		impf = StackRotation(impf, 0, 90, 0);
		ImageStack stackYf = impf.getStack();
		
		/** Rotation of the back substack around y */
		impAlignedZYX = StackRotation(impAlignedZYX, 0, 90, 0);
		ImageStack stackYb = impAlignedZYX.getStack();
		
		/** Cropping the overlap */		
		ImageStack subStackYf = stackYf.crop(stackYf.getWidth()-indb,0,0,indb,stackYf.getHeight(),stackYf.getSize());
		ImageStack subStackYb = stackYb.crop(0, 0, 0, indb, stackYb.getHeight(), stackYb.getSize());
		
		/** Comparison */
		AbstractAffineModel2D< ? > BestModelX = CompareCrossSection(subStackYf, subStackYb, ijSIFT, MIP);
		
		/** Alignment */
		if ( modelFound )
		{
			Model3D(BestModelX, null, null);
			impAlignedZYX = Rotation3D(impb, BestModel3D);
			if (showStep)
			{
				impAlignedZYX2 = StackRotation(impAlignedZYX, 0, 90, 0);
				impAlignedZYX2.setTitle(String.valueOf( l2 ));
				impAlignedZYX2.show();
			}
		}
		else
		{
			IJ.log( "No model found for the data" );
			impAlignedZYX = StackRotation(impAlignedZYX, 0, -90, 0);
		}
		
		BestModel3D.toArray(data);
		IJ.log("1| "+ data[0] + "\t| " + data[3] + "\t| " + data[6] + "\t| " + data[9]);
		IJ.log("2| "+ data[1] + "\t| " + data[4] + "\t| " + data[7] + "\t| " + data[10]);
		IJ.log("3| "+ data[2] + "\t| " + data[5] + "\t| " + data[8] + "\t| " + data[11]);
		
		/** Show intermediate steps */
		//impAlignedZYX.setTitle(String.valueOf( l2 ));
		//impAlignedZYX.show();
		

		/** -------------------------- STEP 4: BEST Y ROTATION -------------------------- */
		
		IJ.log(" ");
		IJ.log("STEP " + 3*l + ": BEST Y ROTATION");
		
		/** Parameter setting */
		fsf.clear();
        fsb.clear();
		
		/** Rotation of the front substack around x */
		impf = StackRotation(impf2, -90, 0, 0);
		ImageStack stackXf = impf.getStack();
		
		/** Rotation of the back substack around x */
		impAlignedZYX = StackRotation(impAlignedZYX, -90, 0, 0);
		ImageStack stackXb = impAlignedZYX.getStack();
		
		/** Cropping the overlap */
		ImageStack subStackXf = stackXf.crop(0, stackXf.getHeight()-indb, 0, stackXf.getWidth(), indb, stackXf.getSize());
		ImageStack subStackXb = stackXb.crop(0, 0, 0, stackXb.getWidth(), indb, stackXb.getSize());
		
		/** Comparison */
		AbstractAffineModel2D < ? > BestModelY = CompareCrossSection(subStackXf, subStackXb, ijSIFT, MIP);
		
		if ( modelFound )
		{
			Model3D(null, BestModelY, null);
			impAlignedZYX = Rotation3D(impb, BestModel3D);
			if (showStep)
			{
				impAlignedZYX2 = StackRotation(impAlignedZYX, -90, 0, 0);
				impAlignedZYX2.setTitle(String.valueOf( 3*l ));
				impAlignedZYX2.show();
			}
		}
		else
		{
			IJ.log( "No model found for the data" );
			impAlignedZYX = StackRotation(impAlignedZYX, 90, 0, 0);

		}
		
		BestModel3D.toArray(data);
		IJ.log("1| "+ data[0] + "\t| " + data[3] + "\t| " + data[6] + "\t| " + data[9]);
		IJ.log("2| "+ data[1] + "\t| " + data[4] + "\t| " + data[7] + "\t| " + data[10]);
		IJ.log("3| "+ data[2] + "\t| " + data[5] + "\t| " + data[8] + "\t| " + data[11]);
		IJ.log(" ");
		

		/** -------------------------- REFORMAT IMAGE AS AT BEGINNING -------------------------- */

		impf = impf2.duplicate();


		}// Loop end
		} // 3D end
		
		/** -------------------------- LAST OVERLAP EXTRACTION -------------------------- */
		
		int l2 = 3*it + 1;
		IJ.log("STEP " + l2 + ": BEST Z ROTATION");
		
		fsf.clear();
        fsb.clear();
		
		ImageStack stackZf = impf.getStack();
		ImageStack stackZb = impAlignedZYX.getStack();
		
		/** Find Back Image Overlap */
		fsf.clear();
		fsb.clear();
		int start = 1; int end = stackZb.getSize();
		if ( Reg3D == true){
			start = indb - 250;
			if ( start < 10 ){ start = 1; }
			end = indb + 250;
			if ( end > stackZb.getSize() ){ end = stackZb.getSize(); }
		}

		indb = OverlapFinder(stackZf, stackZb, ijSIFT, start, end);
		
		/** Cropping the overlap */
		ImageStack subStackZf = stackZf.crop(0, 0, stackZf.getSize() - indb, stackZf.getWidth(), stackZf.getHeight(), indb);
		ImageStack subStackZb = stackZb.crop(0, 0, 0, stackZb.getWidth(), stackZb.getHeight(), indb);
		
		/** Comparison */
		fsf.clear();
		fsb.clear();
		if ( indb < MIP )
		{
			MIP = indb;
		}
		AbstractAffineModel2D< ? > BestModelZ = CompareCrossSection(subStackZf, subStackZb, ijSIFT, MIP);
		
		if ( modelFound )
		{
			Model3D(null, null, BestModelZ);
			impAlignedZYX = Rotation3D(impb, BestModel3D);
			if (template_bool)
			{
				impAlignedZYX = Rotation3D(backTemplate, BestModel3D);
				impf = new ImagePlus( frontTemplate.getTitle(),frontTemplate.getStack() );
			}
		}
		else
		{
			IJ.log( "No model found for the data" );
		}
		
		BestModel3D.toArray(data);
		IJ.log("1| "+ data[0] + "\t|" + data[3] + "\t|" + data[6] + "\t|" + data[9]);
		IJ.log("2| "+ data[1] + "\t|" + data[4] + "\t|" + data[7] + "\t|" + data[10]);
		IJ.log("3| "+ data[2] + "\t|" + data[5] + "\t|" + data[8] + "\t|" + data[11]);
		
		/** Show final back stack */
		//impAlignedZYX.setTitle("Final Back Stack"); 
		//impAlignedZYX.show();
		
		/** -------------------------- IMAGE FUSION -------------------------- */
		
		IJ.log(" ");
		IJ.log( "STEP: IMAGE FUSION" );
		
		String title = "Fused Image";
		if ( c2 || c3 )
		{
			title = title + " Channel1";
		}
		
		ImagePlus FinalImg = fuseImages(impf, impAlignedZYX, indb, fusionMethod, title); 
		if ( stitchingMethod != "Front - Back"){
				FinalImg = reverseStackOrientation(FinalImg, stitchingMethod);
			}
		FinalImg.show(); FinalImg.draw();
		
		if (c2)
		{
			ImagePlus impAligned2 = Rotation3D(channel2back, BestModel3D);
			ImagePlus FinalImg2 = fuseImages(channel2front, impAligned2, indb, fusionMethod, "Fused Image Channel2"); 
			if ( stitchingMethod != "Front - Back"){
				FinalImg2 = reverseStackOrientation(FinalImg2, stitchingMethod);
			}
			FinalImg2.show(); FinalImg2.draw();
		}
		
		if (c3)
		{
			ImagePlus impAligned3 = Rotation3D(channel3back, BestModel3D);
			ImagePlus FinalImg3 = fuseImages(channel3front, impAligned3, indb, fusionMethod, "Fused Image Channel3"); 
			if ( stitchingMethod != "Front - Back"){
				FinalImg3 = reverseStackOrientation(FinalImg3, stitchingMethod);
			}
			FinalImg3.show(); FinalImg3.draw();
		}
		
		fsf.clear();
        fsb.clear();
		
		IJ.log( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
	
		IJ.log( "* Done *" );
		IJ.log(" ");
		Toolkit.getDefaultToolkit().beep();
    }
	
		/** Functions */
		
		/**
		Find the overlap between front and back stacks
		@param front stack
		@param back stack
		@param sift object with parameters set as detailed in run method
		@param overlap beginning
		@param overlap end
		@return real overlap size
		*/
		public int OverlapFinder(ImageStack stackf, ImageStack stackb, SIFT ijSIFT, int start, int end)
		{
		
			ImageProcessor ipb;
			ImageProcessor ipf = stackf.getProcessor( stackf.getSize() );
            
			/** Features extraction of the last slice of the front substack */
			//long start_time = System.currentTimeMillis();
			ijSIFT.extractFeatures( ipf, fsf );// fsf stores the features
		
			float[] bestModelInliers = new float[end - start + 1]; //number of matches
			float[] sliceNumber = new float[end - start + 1];
			int ind = 1; //maximum indice
			float max = 0; //maximum value 
			
			/** Comparison with slices from the back stack */
			for ( int i = start; i <= end; ++i )
			{
				ipb = stackb.getProcessor( i );
				Vector< PointMatch > inliers = searchBestInliers(ipf, ipb, ijSIFT, false);	
			
				bestModelInliers[i - start] = (float) inliers.size(); 
				sliceNumber[i - start] = (float) i;
				
				/** Best model index computation */
				if ( inliers.size() >= max) 
				{
					ind = i;
					max = inliers.size();
				}
			}

			Plot myplot = new Plot("Correspondence","Slice Number","Correspondence",sliceNumber,bestModelInliers);
			myplot.show();
		
			/*final GenericDialog gd2 = new GenericDialog( "Warning" );
			gd2.addMessage( "If the correspondence graph is not a bell-shaped curve, the registration may not be accurate. In such case try to increase the overlap size." );
			gd2.addMessage( "Do you still want to perform the registration ?" );
			gd2.showDialog();
			if (gd2.wasCanceled()) System.exit(1); */ // System.exit(1) à la place de return;
		
			IJ.log( "(Info) Image Overlap Size : " + ind + " pixels");

			
			searchBestInliers(ipf, stackb.getProcessor(ind), ijSIFT, true);
			
        	return ind;
    		}
		
		
		/**
		Compute the best 2D model
		@param front substack
		@param back substack
		@param sift object with parameters set as detailed in run method
		@param MIP size
		@return computed model
		*/
		public AbstractAffineModel2D< ? > CompareCrossSection(ImageStack subStack1, ImageStack subStack2, SIFT ijSIFT, int MIP)
		{
			/** Copy of the substacks */
			ImageStack frontCOPY = subStack1.duplicate(); 
			ImageStack backCOPY = subStack2.duplicate();
			
			/** MIP construction */
			if ( MIP > 1 )
			{
				int step = MIP;
				
				if ( MIP > subStack1.getSize() )
				{
					step = subStack1.getSize();
				}
				subStack1 = createMIP(subStack1, step);
				subStack2 = createMIP(subStack2, step);
			}
			
			/** Parameters initialization */
			ImageProcessor ip2;
			ImageProcessor ip1;
			
			fsf.clear();
			fsb.clear(); 
		
			int taille = subStack2.getSize();
				
			/** Comparisons of cross-section simultaneously */
			for ( int i = 1; i <= taille; ++i )
			{	
				ip1 = subStack1.getProcessor( i );
				ijSIFT.extractFeatures( ip1, fsf );
			
				ip2 = subStack2.getProcessor( i );
				ijSIFT.extractFeatures( ip2, fsb);
			}
			
			System.out.print( "identifying correspondences using brute force ..." );
			Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches( fsb, fsf, 1.5f, null, Float.MAX_VALUE, p.rod );

			Vector< PointMatch > inliers = new Vector< PointMatch >();
               
			AbstractAffineModel2D< ? > BestModel;
			switch ( p.modelIndex )
			{
				case 0: BestModel = new TranslationModel2D();
						break;
				case 1: BestModel = new RigidModel2D();
						break;
				case 2: BestModel = new SimilarityModel2D();
						break;
				case 3: BestModel = new AffineModel2D();
						break;
				default: BestModel = new RigidModel2D();
			}
		
			try
			{
				modelFound = BestModel.filterRansac(candidates,inliers,1000,p.maxEpsilon,p.minInlierRatio );
			}
			catch ( Exception e )
			{
            	modelFound = false;
            	System.err.println( e.getMessage() );
        	}
			
			if ( modelFound )
			{
				double[][] data = new double[2][3];
				BestModel.toMatrix(data);
				IJ.log("Rotation : "+ Math.acos(data[0][0])*(180/Math.PI)+"°");
				IJ.log("Horizontal Translation : "+ data[0][2]+"pixels");
				IJ.log("Vertical Translation : "+ data[1][1]+"pixels");
				
				ip1 = createMIP(frontCOPY, frontCOPY.getSize()).getProcessor(1);
				ip2 = createMIP(backCOPY, backCOPY.getSize()).getProcessor(1);
				displayFeatures( ip1, ip2, candidates, inliers, modelFound );
				
				IJ.log( "(Info) Number of Matching Features : " + inliers.size() );
			}
			
			return BestModel;
		}
		
		/**
		Compute the partial MIP stack
		@param stack
		@param MIP size
		@return the real overlap size
		*/
		public ImageStack createMIP(ImageStack myStack, int step)
		{

			ImagePlus myIM = new ImagePlus( "myStack", myStack ); 
			ImageProcessor myIP = myIM.getProcessor();
				
			ImageStack myMIP = new ImageStack( myIM.getWidth(), myIM.getHeight() ); 

			int Zmax = myIM.getStackSize()/step;
			int extra = myIM.getStackSize() - Zmax*step;
			for (int j = 0; j < Zmax; j++)
			{
				ImageStack myTemp = new ImageStack( myIM.getWidth(), myIM.getHeight() );
				ImagePlus imTemp;
				for ( int i = 1; i <= step; i++ )
				{
					myIM.setSlice( i+step*j );
					myTemp.addSlice( myIP );
				}
				if ( (j == Zmax - 1 ) && ( extra != 0 ) )
				{
					for ( int i = 1; i <= extra; i++ )
					{
						myIM.setSlice( i+step*Zmax );
						myTemp.addSlice( myIP );
					}
				}
				imTemp = new ImagePlus( "myTemp", myTemp );
				IJ.run(imTemp, "Z Project...", "projection=[Max Intensity]");
				imTemp = WindowManager.getCurrentImage();
				imTemp.setSlice(1);
				myMIP.addSlice( imTemp.getProcessor( ) );
				imTemp.hide();	
			}
			return myMIP;
		}
	
    	public Vector< PointMatch > searchBestInliers(ImageProcessor ip1, ImageProcessor ip2, SIFT ijSIFT, boolean showInfoBoolean)
		{
			
			fsb.clear();
			
			ijSIFT.extractFeatures( ip2, fsb);
			System.out.print( "identifying correspondences using brute force ..." );
			Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches( fsb, fsf, 1.5f, null, Float.MAX_VALUE, p.rod );

			Vector< PointMatch > inliers = new Vector< PointMatch >();
                        
			AbstractAffineModel2D< ? > BestModel;
			switch ( p.modelIndex )
			{
				case 0: BestModel = new TranslationModel2D();
						break;
				case 1: BestModel = new RigidModel2D();
						break;
				case 2: BestModel = new SimilarityModel2D();
						break;
				case 3: BestModel = new AffineModel2D();
						break;
				default: BestModel = new RigidModel2D();
			}
		
			try
			{
				modelFound = BestModel.filterRansac(candidates,inliers,1000,p.maxEpsilon,p.minInlierRatio );
			}
			catch ( Exception e )
			{
            	modelFound = false;
            	System.err.println( e.getMessage() );
        	}
			
			if ( modelFound )
			{
				if ( showInfoBoolean )
				{
					double[][] data = new double[2][3];
					BestModel.toMatrix(data);
					displayFeatures( ip1, ip2, candidates, inliers, modelFound );
				}
            }
			
        	return inliers;
    	}

    	public AbstractAffineModel2D< ? > searchBestModel(ImageProcessor ipb, SIFT ijSIFT)
		{
			fsb.clear();
			ijSIFT.extractFeatures( ipb, fsb );
                        
			System.out.print( "identifying correspondences using brute force ..." );
			Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches( fsb, fsf, 1.5f, null, Float.MAX_VALUE, p.rod );

			Vector< PointMatch > inliers = new Vector< PointMatch >();
                        
			AbstractAffineModel2D< ? > BestModel;
			switch ( p.modelIndex )
			{
				case 0: BestModel = new TranslationModel2D();
						break;
				case 1: BestModel = new RigidModel2D();
						break;
				case 2: BestModel = new SimilarityModel2D();
						break;
				case 3: BestModel = new AffineModel2D();
						break;
				default: BestModel = new RigidModel2D();
			}
			
			try
			{
				modelFound = BestModel.filterRansac(candidates,inliers,1000,p.maxEpsilon,p.minInlierRatio );
			}
			catch ( Exception e )
			{
            	modelFound = false;
            	System.err.println( e.getMessage() );
        	}

        	return BestModel;
    	}

        public void keyPressed(KeyEvent e)
        {
            if (
                ( e.getKeyCode() == KeyEvent.VK_F1 ) &&
                ( e.getSource() instanceof TextField ) )
            {
            }
        }

        public void keyReleased(KeyEvent e) { }

        public void keyTyped(KeyEvent e) { }
		
	private ImagePlus fuseImages(ImagePlus imp1, ImagePlus imp2, int ov, String fusionMethod, String name)
			{              
			
            final int dim = 3;
               
            ImageInformation i1 = new ImageInformation(dim, 1, null); //
            i1.closeAtEnd = false;
            i1.imp = imp1;
            i1.size[0] = imp1.getWidth();
            i1.size[1] = imp1.getHeight();
            i1.size[2] = imp1.getStack().getSize();
            i1.position = new float[dim];
            i1.position[0] = i1.position[1] = i1.position[2] = 0;
            i1.imageName = imp1.getTitle();
            i1.invalid = false;
            i1.imageType = imp1.getType();
			
            ImageInformation i2 = new ImageInformation(dim, 2, null);
            i2.closeAtEnd = false;
            i2.imp = imp2;
            i2.size[0] = imp2.getWidth();
            i2.size[1] = imp2.getHeight();
            i2.size[2] = imp2.getStack().getSize();
            i2.position = new float[dim];
            i2.position[0] = 0;
            i2.position[1] = 0;
            i2.position[2] = imp1.getStack().getSize() - ov;
            i2.imageName = imp2.getTitle();
            i2.invalid = false;
			i2.imageType = imp2.getType();

            ArrayList<ImageInformation> imageInformationList = new ArrayList<ImageInformation>();
            imageInformationList.add(i1);
            imageInformationList.add(i2);
               
            float[] maximum = getAndApplyMinMax(imageInformationList, dim);
            ImagePlus image = Stitch_Image_Collection.fuseImages(imageInformationList, maximum, name, fusionMethod, "rgb", dim, alpha, true);
            return image;
        }
		
		float[] getAndApplyMinMax(final ArrayList<ImageInformation> imageInformationList, final int dim)
        {
                float min[] = new float[dim];
                float max[] = new float[dim];
                for (int i = 0; i < min.length; i++)
                {
                        min[i] = Float.MAX_VALUE;
                        max[i] = Float.MIN_VALUE;
                }
               
                for (ImageInformation iI : imageInformationList)
                        for (int i = 0; i < min.length; i++)
                        {
                                if (iI.position[i] < min[i])
                                        min[i] = iI.position[i];
                       
                                if (iI.position[i] + iI.size[i] > max[i])
                                        max[i] = iI.position[i] + iI.size[i];
                        }
               
                for (ImageInformation iI : imageInformationList)
                        for (int i = 0; i < min.length; i++)
                                iI.position[i] -= min[i];

                for (int i = 0; i < min.length; i++)
                {
                        max[i] -= min[i];
                        min[i] = 0;
                }

                return max;
        }
		
		/** Rotation 90°, 180° and 270° only of the image stack */ 
		public ImagePlus StackRotation ( ImagePlus imp, int Rx, int Ry, int Rz)
		{	
			/** Parameters */
			int Xm = imp.getWidth(); int Ym = imp.getHeight(); int Zm = imp.getStackSize();
			ImageStack impStack = imp.getStack();
			int x = Xm; int y = Ym; int z = Zm;
		
			int cosRx = (int) Math.round(Math.cos(Math.toRadians(Rx)));
			int sinRx = (int) Math.round(Math.sin(Math.toRadians(Rx)));
			
			int cosRy = (int) Math.round(Math.cos(Math.toRadians(Ry)));
			int sinRy = (int) Math.round(Math.sin(Math.toRadians(Ry)));
			
			int cosRz = (int) Math.round(Math.cos(Math.toRadians(Rz)));
			int sinRz = (int) Math.round(Math.sin(Math.toRadians(Rz)));
			
			/** New Stack Size */
			int newZm = ((x*sinRz + y*cosRz)*sinRx + z*cosRx)*cosRy - (x*cosRz - y*sinRz)*sinRy;
			int newXm = ((x*sinRz + y*cosRz)*sinRx + z*cosRx)*sinRy + (x*cosRz - y*sinRz)*cosRy;
			int newYm = (x*sinRz + y*cosRz)*cosRx - z*sinRx;
		
			/** Image Creation */
			ImagePlus impR = NewImage.createImage("Rotation", Math.abs(newXm), Math.abs(newYm), Math.abs(newZm), imp.getBitDepth(), 1);
			ImageStack imgR = impR.getStack();
			
			/** Calibration */
			Calibration cal = new Calibration();
			double Cx = imp.getCalibration().pixelWidth; double Cy = imp.getCalibration().pixelHeight; double Cz = imp.getCalibration().pixelDepth;
			
			double newCz = Math.abs(((Cx*sinRz + Cy*cosRz)*sinRx + Cz*cosRx)*cosRy - (Cx*cosRz - Cy*sinRz)*sinRy);
			double newCx = Math.abs(((Cx*sinRz + Cy*cosRz)*sinRx + Cz*cosRx)*sinRy + (Cx*cosRz - Cy*sinRz)*cosRy);
			double newCy = Math.abs((Cx*sinRz + Cy*cosRz)*cosRx - Cz*sinRx);
			
			cal.pixelWidth = newCx;
			cal.pixelHeight = newCy;
			cal.pixelDepth = newCz;
			
			cal.setUnit( imp.getCalibration().getUnit() );

			/** Rotation */
			int max = 0;
			int newX = 0; int newY = 0; int newZ = 0;
			for ( z = 0; z <= Zm; z++ )
			{
				for ( x = 0; x <= Xm; x++ )
				{
					for ( y = 0; y <= Ym; y++ )
					{
				
						newZ = ((x*sinRz + y*cosRz)*sinRx + z*cosRx)*cosRy - (x*cosRz - y*sinRz)*sinRy;
						if ( newZm < 0 ){ newZ = newZ - newZm - 1; }
						newX = ((x*sinRz + y*cosRz)*sinRx + z*cosRx)*sinRy + (x*cosRz - y*sinRz)*cosRy;
						if ( newXm < 0 ){ newX = newX - newXm - 1; }
						newY = (x*sinRz + y*cosRz)*cosRx - z*sinRx;
						if ( newYm < 0 ){ newY = newY - newYm - 1; }
				
						imgR.setVoxel(newX, newY, newZ, impStack.getVoxel(x, y, z) );
						
						if ( impStack.getVoxel(x, y, z) > max ){ max = (int) impStack.getVoxel(x, y, z); }
					}
				}
			}
	
			ImagePlus imp2 = new ImagePlus( "imp", imgR );
			imp2.setCalibration(cal);
			
			/** 12 bit Images */
			if ( 255 < max && max <= 4095 )
			{
				ImagePlus.setDefault16bitRange(12);
			}

			return imp2;
		}
		

		public ImagePlus Rotation3D(ImagePlus imp, AffineModel3D model)
		{
			Calibration c = imp.getCalibration();
			float zFactor = ( float )( c.pixelDepth / c.pixelWidth );
			AffineModel3D unScale = new AffineModel3D();
			unScale.set(
				1.0f, 0.0f, 0.0f, 0.0f,
				0.0f, 1.0f, 0.0f, 0.0f,
				0.0f, 0.0f, zFactor, 0.0f );

			TranslationModel3D centerShift = new TranslationModel3D();
			centerShift.set( -imp.getWidth() / 2, -imp.getHeight() / 2, -imp.getStack().getSize() / 2 * zFactor );
			TranslationModel3D centerUnShift = new TranslationModel3D();
			centerUnShift.set( imp.getWidth() / 2, imp.getHeight() / 2, imp.getStack().getSize() / 2 * zFactor );

			AffineModel3D transform = new AffineModel3D();
			transform.preConcatenate( model );

			/* bounding volume */
			int w = imp.getWidth();
			int h = imp.getHeight();
			int d = imp.getStackSize();

			/* render target stack */
			mpicbg.ij.stack.InverseTransformMapping<AffineModel3D> mapping = new mpicbg.ij.stack.InverseTransformMapping<AffineModel3D>( transform );

			ImageProcessor ip = imp.getStack().getProcessor( 1 ).createProcessor( imp.getWidth(), imp.getHeight() );
			ImageStack targetStack = new ImageStack( w, h );

			for ( int s = 0; s < d; ++s )
			{
				ip = ip.createProcessor( w, h );
				mapping.setSlice( s );
				try
				{
					mapping.mapInterpolated( imp.getStack(), ip );
				}
				catch ( Exception e )
				{
					e.printStackTrace();
				}
				targetStack.addSlice( "", ip );
			}

			/* set proper calibration (it's isotropic at the former x,y-scale now) */
			ImagePlus impTarget = new ImagePlus( "target", targetStack );

		return impTarget;
		}
		
		public AffineModel3D Model3D(AbstractAffineModel2D< ? > modelX, AbstractAffineModel2D< ? > modelY, AbstractAffineModel2D< ? > modelZ)
		{
			AffineModel3D modelTemp = new AffineModel3D();
			double[] data = new double[6];
			
			if (modelZ != null)
				{
					modelZ.toArray(data);
					modelTemp.set(
						data[0], data[2], 0.0f, data[4],
						data[1], data[3], 0.0f, data[5],
						0.0f, 0.0f, 1.0f, 0.0f );
					BestModel3D.concatenate(modelTemp);
				}
			
			if (modelX != null)
				{
					modelX.toArray(data);
					modelTemp.set(
						1.0f, 0.0f, 0.0f, 0.0f,
						0.0f, data[3], data[1], data[5],
						0.0f, data[2], data[0], data[4]);
						//0.0f, data[2], data[0], 0.0f);
					BestModel3D.concatenate(modelTemp);
				}
				
			if (modelY != null)
				{
					// WARNING Change the origin of the rotation
					TranslationModel3D Shift = new TranslationModel3D();
					TranslationModel3D UnShift = new TranslationModel3D();

				
					modelY.toArray(data);
					modelTemp.set(
						data[0], 0.0f, data[2], data[4],
						0.0f, 1.0f, 0.0f, 0.0f,
						data[1], 0.0f, data[3], data[5]);
						//data[1], 0.0f, data[3], 0.0f);
					
					BestModel3D.concatenate(UnShift);
					BestModel3D.concatenate(modelTemp);
					BestModel3D.concatenate(Shift);
				}
		
			return BestModel3D;
		
		}
		
		public void displayFeatures( ImageProcessor ip1, ImageProcessor ip2, Vector< PointMatch > candidates, Vector< PointMatch > inliers, boolean modelfound )
		{
			//Slices with features for visualization wrt boolean showInfoBoolean
			ImageProcessor ip1wf = null;
            ImageProcessor ip2wf = null;
			float vis_scale = 1;  
		
			ip1wf = ip1.convertToRGB().duplicate();
            ip2wf = ip2.convertToRGB().duplicate();
            ip1wf.setColor( Color.red );
            ip2wf.setColor( Color.red );
            ip1wf.setLineWidth( 2 );
            ip2wf.setLineWidth( 2 );
			
	        for ( PointMatch m : candidates )
		    {

				double[] m_p1 = m.getP1().getL();
		        double[] m_p2 = m.getP2().getL();
				//float[] m_p1 = m.getP1().getL();
		        //float[] m_p2 = m.getP2().getL();
     
		        ip1wf.drawDot( (int) Math.round( vis_scale * m_p2[ 0 ] ), (int) Math.round( vis_scale * m_p2[ 1 ] ) );
		        ip2wf.drawDot( (int) Math.round( vis_scale * m_p1[ 0 ] ), (int) Math.round( vis_scale * m_p1[ 1 ] ) );
		    }
			
			if ( modelfound )
			{
				ip1wf.setColor( Color.green );
				ip2wf.setColor( Color.green );
				ip1wf.setLineWidth( 2 );
				ip2wf.setLineWidth( 2 );
					
				for ( PointMatch m : inliers )
				{
					//float[] m_p1 = m.getP1().getL();
					//float[] m_p2 = m.getP2().getL();
					double[] m_p1 = m.getP1().getL();
					double[] m_p2 = m.getP2().getL();

					ip1wf.drawDot( (int) Math.round( vis_scale * m_p2[ 0 ] ), (int) Math.round( vis_scale * m_p2[ 1 ] ) );
					ip2wf.drawDot( (int) Math.round( vis_scale * m_p1[ 0 ] ), (int) Math.round( vis_scale * m_p1[ 1 ] ) );
				}

				ImageStack stackInfo = new ImageStack(Math.round( ip1.getWidth() ), Math.round( ip1.getHeight() ) );
				ImageProcessor tmp;
				tmp = ip1wf.createProcessor( stackInfo.getWidth(), stackInfo.getHeight() );
				tmp.insert( ip1wf, 0, 0 );
				stackInfo.addSlice( null, tmp ); 
				tmp = ip2wf.createProcessor( stackInfo.getWidth(), stackInfo.getHeight() );
				tmp.insert( ip2wf, 0, 0 );
				stackInfo.addSlice( null, tmp );
				ImagePlus impInfo = new ImagePlus( "Alignment info", stackInfo );
				impInfo.show();
			}
		}
		
		public void StackFeatures(ImageStack stackf, ImageStack stackb, SIFT ijSIFT, int ov)
		{
		
			ImageProcessor ipb;
			ImageProcessor ipf;
            
			/** Features extraction of the last slice of the front substack */
		
			float[] front = new float[ov]; //number of matches
			float[] back = new float[ov];
			float[] sliceNumber = new float[ov];
			
			/** Comparison with slices from the back stack */
			for ( int i = 1; i <= ov; ++i )
			{
				ipf = stackf.getProcessor( i );
				ijSIFT.extractFeatures( ipf, fsf );
				front[i-1] = (float) fsf.size();
				fsf.clear();
				ipb = stackb.getProcessor( i );
				ijSIFT.extractFeatures( ipb, fsb );
				back[i-1] = (float) fsb.size();
				sliceNumber[i-1] = (float) i;
				fsb.clear();
			}
			
			Plot myplot = new Plot("fsf", "Slice Number", "fsf", sliceNumber, front);
			Plot myplot2 = new Plot("fsb", "Slice Number", "fsb", sliceNumber, back);
			myplot.show(); myplot2.show();
			return;
		
		}
		
		public ImagePlus stackOrientation(ImagePlus imp, String orientation)
		{
			
			if ( orientation == "Back - Front"){
				imp.revert();
			}
			else if ( orientation == "Left - Right"){
				imp = StackRotation(imp, 0, -90, 0);
			}
			else if ( orientation == "Right - Left"){
				imp = StackRotation(imp, 0, 90, 0);
			}
			else if ( orientation == "Top - Bottom"){
				imp = StackRotation(imp, 90, 0, 0); 
			}
			else if ( orientation == "Bottom - Top"){
				imp = StackRotation(imp, -90, 0, 0); 
			}
		
			return imp;
		}
		
		public ImagePlus reverseStackOrientation(ImagePlus imp, String orientation)
		{
			
			if ( orientation == "Back - Front"){
				imp.revert();
			}
			else if ( orientation == "Left - Right"){
				imp = StackRotation(imp, 0, 90, 0);
			}
			else if ( orientation == "Right - Left"){
				imp = StackRotation(imp, 0, -90, 0);
			}
			else if ( orientation == "Top - Bottom"){
				imp = StackRotation(imp, -90, 0, 0); 
			}
			else if ( orientation == "Bottom - Top"){
				imp = StackRotation(imp, 90, 0, 0); 
			}
		
			return imp;
		}
		
		public int recursiveOverlapFinder(ImageStack stackf, ImageStack stackb, SIFT ijSIFT, int Cov, int split)
		{

			int sb = stackb.getSize();
			int sf = stackf.getSize();
			
			ImageStack subStackf = new ImageStack();
			ImageStack subStackb = new ImageStack();
			
			int myMIP = (int) sb/split;
			if ( myMIP > 1 )
			{
				subStackb = createMIP(stackb, myMIP);
				if ( sf >= (int) sb/split )
				{
					subStackf = makeSubstack(stackf, (int) sf-sb/split + 1, sf);
					subStackf = createMIP( subStackf, subStackf.getSize() );	
				}
				else{
					subStackf = createMIP( stackf, stackf.getSize() );	
				}
			}
			else if ( myMIP <= 1) {
				subStackb = stackb;
				subStackf = stackf; 
			}
			
			/** Parameters initialization */
			ImageProcessor ipf;
			ImageProcessor ipb;
			fsf.clear();
			fsb.clear();	

			/** Comparisons */
			int taille = subStackb.getSize();
			ipf = subStackf.getProcessor( subStackf.getSize() ); // Last slice of the front stack
			ijSIFT.extractFeatures( ipf, fsf );
			int ind = 1; int max = 0;
			
			for ( int i = 1; i <= taille; ++i ) // Find the best match
			{	
				ipb = subStackb.getProcessor( i );
				ijSIFT.extractFeatures( ipb, fsb );
				Vector< PointMatch > inliers = searchBestInliers(ipf, ipb, ijSIFT, false);
				if ( inliers.size() >= max) 
				{
					ind = i;
					max = inliers.size();
				}
			}
			
			if ( myMIP > 1 )
			{
				Cov = (int) (Cov - sb + ind*sb/split);
				stackb = makeSubstack( stackb, (int) ((ind-1)*(myMIP) + 1), (int) (ind*myMIP) );
				stackf = makeSubstack( stackf, (int) sf-myMIP + 1, sf );
			}
			else 
			{
				Cov = (int) (Cov - sb + ind);
				stackb = makeSubstack( stackb, (int) ((ind-1)*(sb/taille) + 1), (int) (ind*sb/taille) );
				stackf = makeSubstack( stackf, (int) sf-sb/taille, sf );
				
			}
			
			if ( sb <= split || split == 1)
			{
				IJ.log("Return OV: " + Cov);
				return Cov;
			}
			else
			{
				Cov = recursiveOverlapFinder(stackf, stackb, ijSIFT, Cov, split);
				return Cov;
			}
    	}
		
		public ImageStack makeSubstack(ImageStack stack, int z1, int z2)
		{
			ImageStack substack = new ImageStack( stack.getWidth(), stack.getHeight() );
			
			for (int i = z1; i<= z2; ++i )
			{
				substack.addSlice( stack.getProcessor( i ) );
			}
			
			return substack;
		}
		
		public int OverlapFinderSbS(ImageStack stackf, ImageStack stackb, SIFT ijSIFT, int ov)
		{
		
			ImageProcessor ipb;
			ImageProcessor ipf = stackf.getProcessor( stackf.getSize() );
            
			/** Features extraction of the last slice of the front substack */
			ijSIFT.extractFeatures( ipf, fsf );// fsf stores the features
		
			float[] bestModelInliers = new float[ov]; //number of matches
			float[] bestModelInliers2 = new float[ov];
			//percentage of matches respect to the number of matches in the last slice of the front stack
			float[] sliceNumber = new float[ov];//x in graph
			int ind = 1; 
			float max = 0; 
			
			/** Comparison with slices from the back stack */
			for ( int i = 1; i <= ov; ++i )
			{
				ipb = stackb.getProcessor( i );
				Vector< PointMatch > inliers = searchBestInliers(ipf, ipb, ijSIFT, false);	
			
				bestModelInliers[i-1] = (float) inliers.size(); 
				bestModelInliers2[i-1] = (float) inliers.size()*100/fsf.size();
				sliceNumber[i-1] = (float) i;
				
				/** Best model index computation */
				if ( inliers.size() >= max) 
				{
					ind = i;
					max = inliers.size();
				}
				
			}

			Plot myplot = new Plot("Correspondence","Slice Number","Correspondence",sliceNumber,bestModelInliers);
			myplot.show();
		
			IJ.log( "(Info) Image Overlap Size : " + ind + " pixels");
			
			searchBestInliers(ipf, stackb.getProcessor(ind), ijSIFT, true);
			
        	return ind;
    		}
		
}

