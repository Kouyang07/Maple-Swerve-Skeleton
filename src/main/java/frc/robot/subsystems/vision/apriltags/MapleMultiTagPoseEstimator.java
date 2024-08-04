package frc.robot.subsystems.vision.apriltags;

import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import frc.robot.utils.Config.PhotonCameraProperties;
import frc.robot.utils.MapleMaths.Statistics;
import org.littletonrobotics.junction.Logger;
import org.photonvision.targeting.PhotonPipelineResult;
import org.photonvision.targeting.PhotonTrackedTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static frc.robot.Constants.VisionConfigs.*;
import static frc.robot.Constants.LogConfigs.APRIL_TAGS_VISION_PATH;

public class MapleMultiTagPoseEstimator {
    public static final class RobotPoseEstimationResult {
        public final Pose2d pointEstimation;
        public final Matrix<N3, N1> estimationStandardError;

        public RobotPoseEstimationResult(Pose2d pointEstimation, double translationXStandardDeviationMeters, double translationYStandardDeviationMeters, double rotationalStandardDeviationRadians) {
            this.pointEstimation = pointEstimation;
            this.estimationStandardError = VecBuilder.fill(translationXStandardDeviationMeters, translationYStandardDeviationMeters, rotationalStandardDeviationRadians);
        }
    }

    private final AprilTagFieldLayout fieldLayout;
    private final VisionResultsFilter filter;
    private final List<PhotonCameraProperties> camerasProperties;
    public MapleMultiTagPoseEstimator(AprilTagFieldLayout aprilTagFieldLayout, VisionResultsFilter filter, List<PhotonCameraProperties> camerasProperties) {
        this.fieldLayout = aprilTagFieldLayout;
        this.filter = filter;
        this.camerasProperties = camerasProperties;
    }

    /**
     * using the filtering mechanism, find out the best guess of the robot pose and the standard error
     * @param pipelineResults pipeline results
     * @return (optionally) the best guess of the robot pose and the standard error, if there are valid targets
     * */
    public Optional<RobotPoseEstimationResult> estimateRobotPose(PhotonPipelineResult[] pipelineResults, Pose2d currentOdometryPose) {
        if (pipelineResults.length != camerasProperties.size())
            throw new IllegalStateException("pipe line results length" + pipelineResults.length
                    + " does not match cameras properties length: " + camerasProperties.size());
        final List<Integer> observedAprilTagsIDs = new ArrayList<>();
        final List<Pose3d> robotPose3dObservations = new ArrayList<>(),
                observedAprilTagsPoses = new ArrayList<>(),
                observedVisionTargetPoseInFieldLayout = new ArrayList<>();
        for (int i = 0; i < pipelineResults.length; i++)
            for (PhotonTrackedTarget target:pipelineResults[i].getTargets()) {
                final Transform3d robotToCamera = camerasProperties.get(i).robotToCamera;
                calculateRobotPose3dFromSingleObservation(
                        target,
                        robotToCamera
                ).ifPresent(robotPose3dObservation -> {
                    robotPose3dObservations.add(robotPose3dObservation);
                    observedAprilTagsIDs.add(target.getFiducialId());
                    observedAprilTagsPoses.add(calculateObservedAprilTagTargetPose(target, robotToCamera, currentOdometryPose));
                    observedVisionTargetPoseInFieldLayout.add(fieldLayout.getTagPose(target.getFiducialId()).orElse(new Pose3d()));
                });
            }

        final List<Pose2d> filteredResults = robotPose3dObservations.stream()
                .filter(filter)
                .map(Pose3d::toPose2d)
                .toList();

        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/RobotPose3dsResults", robotPose3dObservations.toArray(Pose3d[]::new));
        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/CurrentFilterImplementation", filter.getFilterImplementationName());
        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/RobotPose2dObservationsFiltered", filteredResults.toArray(Pose2d[]::new));
        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/VisibleFieldTargets", observedVisionTargetPoseInFieldLayout.toArray(Pose3d[]::new));
        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/AprilTagsObservedPositions/AprilTag ID", observedAprilTagsIDs.stream().mapToInt(i -> i).toArray());
        Logger.recordOutput(APRIL_TAGS_VISION_PATH + "Filtering/AprilTagsObservedPositions/", observedAprilTagsPoses.toArray(Pose3d[]::new));

        return getEstimationResultFromValidObservations(filteredResults);
    }

    private Pose3d calculateObservedAprilTagTargetPose(PhotonTrackedTarget target, Transform3d robotToCamera, Pose2d currentOdometryPose) {
        return new Pose3d(currentOdometryPose).transformBy(robotToCamera).transformBy(target.getBestCameraToTarget());
    }
    private Optional<Pose3d> calculateRobotPose3dFromSingleObservation(PhotonTrackedTarget target, Transform3d robotToCamera) {
        if (target == null) return Optional.empty();
        final Optional<Pose3d> targetPosition = fieldLayout.getTagPose(target.getFiducialId());
        return targetPosition.map(tagAbsolutePoseOnField ->
                tagAbsolutePoseOnField
                        .transformBy(target.getBestCameraToTarget().inverse())
                        .transformBy(robotToCamera.inverse())
        );
    }

    private static Optional<RobotPoseEstimationResult> getEstimationResultFromValidObservations(List<Pose2d> validRobotPoseEstimations) {
        final int n = validRobotPoseEstimations.size();
        if (n == 0)
            return Optional.empty();
        if (n == 1)
            return Optional.of(new RobotPoseEstimationResult(
                    validRobotPoseEstimations.get(0),
                    TRANSLATIONAL_STANDARD_ERROR_METERS_FOR_SINGLE_OBSERVATION,
                    TRANSLATIONAL_STANDARD_ERROR_METERS_FOR_SINGLE_OBSERVATION,
                    ROTATIONAL_STANDARD_ERROR_RADIANS_FOR_SINGLE_OBSERVATION
            ));

        final double[]
                robotPoseEstimationsXMeters = validRobotPoseEstimations.stream().mapToDouble(Pose2d::getX).toArray(),
                robotPoseEstimationsYMeters = validRobotPoseEstimations.stream().mapToDouble(Pose2d::getY).toArray(),
                robotPoseEstimatorThetaRadians = validRobotPoseEstimations.stream().mapToDouble(pose2d -> pose2d.getRotation().getRadians()).toArray();

        final Translation2d translationPointEstimate = new Translation2d(
                Statistics.getMean(robotPoseEstimationsXMeters),
                Statistics.getMean(robotPoseEstimationsYMeters)
        );
        final Rotation2d rotationPointEstimate = Rotation2d.fromRadians(
                Statistics.getMean(robotPoseEstimatorThetaRadians)
        );

        final double estimationStandardDeviationX = Statistics.getStandardDeviation(robotPoseEstimationsXMeters),
                estimationStandardDeviationY = Statistics.getStandardDeviation(robotPoseEstimationsYMeters),
                estimationStandardDeviationTheta = Statistics.getStandardDeviation(robotPoseEstimatorThetaRadians);

        return Optional.of(new RobotPoseEstimationResult(
                new Pose2d(translationPointEstimate, rotationPointEstimate),
                estimationStandardDeviationX,
                estimationStandardDeviationY,
                estimationStandardDeviationTheta
        ));
    }
}
