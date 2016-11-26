/*
 * Copyright (c) 2011-2016, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pramid.ml.cv.boofcv.stereo;

import boofcv.abst.feature.disparity.StereoDisparity;
import boofcv.alg.distort.ImageDistort;
import boofcv.alg.distort.LensDistortionOps;
import boofcv.alg.filter.derivative.LaplacianEdge;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.alg.geo.RectifyImageOps;
import boofcv.alg.geo.rectify.RectifyCalibrated;
import boofcv.core.image.border.BorderType;
import boofcv.factory.feature.disparity.DisparityAlgorithms;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.factory.geo.ConfigEssential;
import boofcv.factory.geo.ConfigRansac;
import boofcv.factory.geo.FactoryMultiViewRobust;
import boofcv.gui.d3.PointCloudTiltPanel;
import boofcv.gui.feature.AssociationPanel;
import boofcv.gui.image.ShowImages;
import boofcv.gui.image.VisualizeImageData;
import boofcv.gui.stereo.RectifiedPairPanel;
import boofcv.io.UtilIO;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.distort.DoNothingTransform_F64;
import boofcv.struct.distort.PointTransform_F64;
import boofcv.struct.geo.AssociatedPair;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayS16;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageGray;
import georegression.struct.se.Se3_F64;
import org.ddogleg.fitting.modelset.ModelMatcher;
import org.ejml.data.DenseMatrix64F;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Example demonstrating how to use to images taken from a single calibrated
 * camera to create a stereo disparity image, from which a dense 3D point cloud
 * of the scene can be computed. For this technique to work the camera's motion
 * needs to be approximately tangential to the direction the camera is pointing.
 * The code below assumes that the first image is to the left of the second
 * image.
 *
 * @author Peter Abeles
 */
public class ExampleStereoTwoViewsOneCamera {

	// Disparity calculation parameters
	private static final int minDisparity = 15;
	private static final int maxDisparity = 100;

	public static void main(String args[]) {
		// specify location of images and calibration
		String calibDir = UtilIO
				.pathExample("calibration/mono/Sony_DSC-HX5V_Chess/");
		String imageDir = UtilIO.pathExample("stereo/");

		// Camera parameters
		IntrinsicParameters intrinsic = UtilIO.loadXML(calibDir,
				"intrinsic.xml");

		// Input images from the camera moving left to right
		BufferedImage origLeft = UtilImageIO.loadImage(imageDir,
				"mono_wall_01.jpg");
		BufferedImage origRight = UtilImageIO.loadImage(imageDir,
				"mono_wall_02.jpg");

		// Input images with lens distortion
		GrayU8 distortedLeft = ConvertBufferedImage.convertFrom(origLeft,
				(GrayU8) null);
		GrayU8 distortedRight = ConvertBufferedImage.convertFrom(origRight,
				(GrayU8) null);

		// matched features between the two images
		List<AssociatedPair> matchedFeatures = ExampleFundamentalMatrix
				.computeMatches(origLeft, origRight);

		// convert from pixel coordinates into normalized image coordinates
		List<AssociatedPair> matchedCalibrated = convertToNormalizedCoordinates(
				matchedFeatures, intrinsic);

		// Robustly estimate camera motion
		List<AssociatedPair> inliers = new ArrayList<AssociatedPair>();
		Se3_F64 leftToRight = estimateCameraMotion(intrinsic,
				matchedCalibrated, inliers);

		drawInliers(origLeft, origRight, intrinsic, inliers);

		// Rectify and remove lens distortion for stereo processing
		DenseMatrix64F rectifiedK = new DenseMatrix64F(3, 3);
		GrayU8 rectifiedLeft = distortedLeft.createSameShape();
		GrayU8 rectifiedRight = distortedRight.createSameShape();

		rectifyImages(distortedLeft, distortedRight, leftToRight, intrinsic,
				rectifiedLeft, rectifiedRight, rectifiedK);

		// compute disparity
		StereoDisparity<GrayS16, GrayF32> disparityAlg = FactoryStereoDisparity
				.regionSubpixelWta(DisparityAlgorithms.RECT_FIVE, minDisparity,
						maxDisparity, 5, 5, 20, 1, 0.1, GrayS16.class);

		// Apply the Laplacian across the image to add extra resistance to
		// changes in lighting or camera gain
		GrayS16 derivLeft = new GrayS16(rectifiedLeft.width,
				rectifiedLeft.height);
		GrayS16 derivRight = new GrayS16(rectifiedLeft.width,
				rectifiedLeft.height);
		LaplacianEdge.process(rectifiedLeft, derivLeft);
		LaplacianEdge.process(rectifiedRight, derivRight);

		// process and return the results
		disparityAlg.process(derivLeft, derivRight);
		GrayF32 disparity = disparityAlg.getDisparity();

		// show results
		BufferedImage visualized = VisualizeImageData.disparity(disparity,
				null, minDisparity, maxDisparity, 0);

		BufferedImage outLeft = ConvertBufferedImage.convertTo(rectifiedLeft,
				null);
		BufferedImage outRight = ConvertBufferedImage.convertTo(rectifiedRight,
				null);

		ShowImages.showWindow(new RectifiedPairPanel(true, outLeft, outRight),
				"Rectification");
		ShowImages.showWindow(visualized, "Disparity");

		showPointCloud(disparity, outLeft, leftToRight, rectifiedK,
				minDisparity, maxDisparity);

		System.out.println("Total found " + matchedCalibrated.size());
		System.out.println("Total Inliers " + inliers.size());
	}

	/**
	 * Estimates the camera motion robustly using RANSAC and a set of associated
	 * points.
	 *
	 * @param intrinsic
	 *            Intrinsic camera parameters
	 * @param matchedNorm
	 *            set of matched point features in normalized image coordinates
	 * @param inliers
	 *            OUTPUT: Set of inlier features from RANSAC
	 * @return Found camera motion. Note translation has an arbitrary scale
	 */
	public static Se3_F64 estimateCameraMotion(IntrinsicParameters intrinsic,
			List<AssociatedPair> matchedNorm, List<AssociatedPair> inliers) {
		ModelMatcher<Se3_F64, AssociatedPair> epipolarMotion = FactoryMultiViewRobust
				.essentialRansac(new ConfigEssential(intrinsic),
						new ConfigRansac(200, 0.5));

		if (!epipolarMotion.process(matchedNorm))
			throw new RuntimeException("Motion estimation failed");

		// save inlier set for debugging purposes
		inliers.addAll(epipolarMotion.getMatchSet());

		return epipolarMotion.getModelParameters();
	}

	/**
	 * Convert a set of associated point features from pixel coordinates into
	 * normalized image coordinates.
	 */
	public static List<AssociatedPair> convertToNormalizedCoordinates(
			List<AssociatedPair> matchedFeatures, IntrinsicParameters intrinsic) {

		PointTransform_F64 p_to_n = LensDistortionOps.transformPoint(intrinsic)
				.undistort_F64(true, false);

		List<AssociatedPair> calibratedFeatures = new ArrayList<AssociatedPair>();

		for (AssociatedPair p : matchedFeatures) {
			AssociatedPair c = new AssociatedPair();

			p_to_n.compute(p.p1.x, p.p1.y, c.p1);
			p_to_n.compute(p.p2.x, p.p2.y, c.p2);

			calibratedFeatures.add(c);
		}

		return calibratedFeatures;
	}

	/**
	 * Remove lens distortion and rectify stereo images
	 *
	 * @param distortedLeft
	 *            Input distorted image from left camera.
	 * @param distortedRight
	 *            Input distorted image from right camera.
	 * @param leftToRight
	 *            Camera motion from left to right
	 * @param intrinsic
	 *            Intrinsic camera parameters
	 * @param rectifiedLeft
	 *            Output rectified image for left camera.
	 * @param rectifiedRight
	 *            Output rectified image for right camera.
	 * @param rectifiedK
	 *            Output camera calibration matrix for rectified camera
	 */
	public static void rectifyImages(GrayU8 distortedLeft,
			GrayU8 distortedRight, Se3_F64 leftToRight,
			IntrinsicParameters intrinsic, GrayU8 rectifiedLeft,
			GrayU8 rectifiedRight, DenseMatrix64F rectifiedK) {
		RectifyCalibrated rectifyAlg = RectifyImageOps.createCalibrated();

		// original camera calibration matrices
		DenseMatrix64F K = PerspectiveOps.calibrationMatrix(intrinsic, null);

		rectifyAlg.process(K, new Se3_F64(), K, leftToRight);

		// rectification matrix for each image
		DenseMatrix64F rect1 = rectifyAlg.getRect1();
		DenseMatrix64F rect2 = rectifyAlg.getRect2();

		// New calibration matrix,
		rectifiedK.set(rectifyAlg.getCalibrationMatrix());

		// Adjust the rectification to make the view area more useful
		RectifyImageOps.allInsideLeft(intrinsic, rect1, rect2, rectifiedK);

		// undistorted and rectify images
		ImageDistort<GrayU8, GrayU8> distortLeft = RectifyImageOps
				.rectifyImage(intrinsic, rect1, BorderType.SKIP,
						distortedLeft.getImageType());
		ImageDistort<GrayU8, GrayU8> distortRight = RectifyImageOps
				.rectifyImage(intrinsic, rect2, BorderType.SKIP,
						distortedRight.getImageType());

		distortLeft.apply(distortedLeft, rectifiedLeft);
		distortRight.apply(distortedRight, rectifiedRight);
	}

	/**
	 * Draw inliers for debugging purposes. Need to convert from normalized to
	 * pixel coordinates.
	 */
	public static void drawInliers(BufferedImage left, BufferedImage right,
			IntrinsicParameters intrinsic, List<AssociatedPair> normalized) {
		PointTransform_F64 n_to_p = LensDistortionOps.transformPoint(intrinsic)
				.distort_F64(false, true);

		List<AssociatedPair> pixels = new ArrayList<AssociatedPair>();

		for (AssociatedPair n : normalized) {
			AssociatedPair p = new AssociatedPair();

			n_to_p.compute(n.p1.x, n.p1.y, p.p1);
			n_to_p.compute(n.p2.x, n.p2.y, p.p2);

			pixels.add(p);
		}

		// display the results
		AssociationPanel panel = new AssociationPanel(20);
		panel.setAssociation(pixels);
		panel.setImages(left, right);

		ShowImages.showWindow(panel, "Inlier Features");
	}

	/**
	 * Show results as a point cloud
	 */
	public static void showPointCloud(ImageGray disparity, BufferedImage left,
			Se3_F64 motion, DenseMatrix64F rectifiedK, int minDisparity,
			int maxDisparity) {
		PointCloudTiltPanel gui = new PointCloudTiltPanel();

		double baseline = motion.getT().norm();

		gui.configure(baseline, rectifiedK, new DoNothingTransform_F64(),
				minDisparity, maxDisparity);
		gui.process(disparity, left);
		gui.setPreferredSize(new Dimension(left.getWidth(), left.getHeight()));

		ShowImages.showWindow(gui, "Point Cloud");
	}
}
