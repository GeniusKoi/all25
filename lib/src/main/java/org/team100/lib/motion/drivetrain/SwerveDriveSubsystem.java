package org.team100.lib.motion.drivetrain;

import org.team100.lib.config.DriverSkill;
import org.team100.lib.dashboard.Glassy;
import org.team100.lib.geometry.GeometryUtil;
import org.team100.lib.localization.SwerveDrivePoseEstimator100;
import org.team100.lib.localization.VisionData;
import org.team100.lib.logging.Level;
import org.team100.lib.logging.LoggerFactory;
import org.team100.lib.logging.LoggerFactory.DoubleArrayLogger;
import org.team100.lib.logging.LoggerFactory.DoubleLogger;
import org.team100.lib.logging.LoggerFactory.EnumLogger;
import org.team100.lib.logging.LoggerFactory.FieldRelativeVelocityLogger;
import org.team100.lib.logging.LoggerFactory.SwerveModelLogger;
import org.team100.lib.motion.drivetrain.kinodynamics.FieldRelativeVelocity;
import org.team100.lib.motion.drivetrain.kinodynamics.SwerveKinodynamics;
import org.team100.lib.motion.drivetrain.kinodynamics.SwerveModuleStates;
import org.team100.lib.motion.drivetrain.kinodynamics.limiter.SwerveLimiter;
import org.team100.lib.sensors.Gyro;
import org.team100.lib.swerve.SwerveSetpoint;
import org.team100.lib.util.Memo;
import org.team100.lib.util.Takt;
import org.team100.lib.util.Util;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj2.command.SubsystemBase;

/**
 * There are four mutually exclusive drive methods.
 * We depend on CommandScheduler to enforce the mutex.
 */
public class SwerveDriveSubsystem extends SubsystemBase implements Glassy, DriveSubsystemInterface {
    private static final boolean DEBUG = false;
    private final Gyro m_gyro;
    private final SwerveDrivePoseEstimator100 m_poseEstimator;
    private final SwerveLocal m_swerveLocal;
    private final VisionData m_cameras;
    private final SwerveLimiter m_limiter;

    // CACHES
    private final Memo.CotemporalCache<SwerveModel> m_stateSupplier;

    // LOGGERS
    private final SwerveModelLogger m_log_state;
    private final DoubleLogger m_log_turning;
    private final DoubleArrayLogger m_log_pose_array;
    private final DoubleArrayLogger m_log_field_robot;
    private final DoubleLogger m_log_yaw_rate;
    private final EnumLogger m_log_skill;
    private final FieldRelativeVelocityLogger m_log_input;

    public SwerveDriveSubsystem(
            LoggerFactory fieldLogger,
            LoggerFactory parent,
            Gyro gyro,
            SwerveDrivePoseEstimator100 poseEstimator,
            SwerveLocal swerveLocal,
            VisionData cameras,
            SwerveLimiter limiter) {
        LoggerFactory child = parent.child(this);
        m_gyro = gyro;
        m_poseEstimator = poseEstimator;
        m_swerveLocal = swerveLocal;
        m_cameras = cameras;
        m_limiter = limiter;
        m_stateSupplier = Memo.of(this::update);
        stop();
        m_log_state = child.swerveModelLogger(Level.COMP, "state");
        m_log_turning = child.doubleLogger(Level.TRACE, "Tur Deg");
        m_log_pose_array = child.doubleArrayLogger(Level.COMP, "pose array");
        m_log_field_robot = fieldLogger.doubleArrayLogger(Level.COMP, "robot");
        m_log_yaw_rate = child.doubleLogger(Level.TRACE, "heading rate rad_s");
        m_log_skill = child.enumLogger(Level.TRACE, "skill level");
        m_log_input = child.fieldRelativeVelocityLogger(Level.TRACE, "drive input");
    }

    ////////////////
    //
    // ACTUATORS
    //

    /**
     * Scales the supplied twist by the "speed" driver control modifier.
     * 
     * Feasibility is enforced by the SwerveLimiter, and, at the moment, the setpoint generator.
     * 
     * TODO: remove setpoint generator.
     */
    @Override
    public void driveInFieldCoords(final FieldRelativeVelocity input) {
        // scale for driver skill; default is half speed.
        final DriverSkill.Level driverSkillLevel = DriverSkill.level();
        final FieldRelativeVelocity scaled = GeometryUtil.scale(input, driverSkillLevel.scale());

        // NEW!  Apply field-relative limits here.
        final FieldRelativeVelocity target = m_limiter.apply(getState(), scaled);

        final Rotation2d theta = getPose().getRotation();
        final ChassisSpeeds targetChassisSpeeds = SwerveKinodynamics.toInstantaneousChassisSpeeds(target, theta);

        m_log_input.log(() -> input);
        m_log_skill.log(() -> driverSkillLevel);
        // here heading and course are exactly opposite, as they should be.
        if (DEBUG)
            Util.printf(
                    "driveInFieldCoords() target heading %.8f target course %.8f speeds x %.6f y %.6f theta %.6f\n",
                    theta.getRadians(),
                    GeometryUtil.getCourse(targetChassisSpeeds).orElse(new Rotation2d()).getRadians(),
                    targetChassisSpeeds.vxMetersPerSecond,
                    targetChassisSpeeds.vyMetersPerSecond,
                    targetChassisSpeeds.omegaRadiansPerSecond);

        m_swerveLocal.setChassisSpeeds(targetChassisSpeeds);
    }

    /** Skip all scaling, setpoint generator, etc. */
    public void driveInFieldCoordsVerbatim(FieldRelativeVelocity vIn) {
        final ChassisSpeeds targetChassisSpeeds = SwerveKinodynamics.toInstantaneousChassisSpeeds(
                vIn, getPose().getRotation());

        final Rotation2d theta = getPose().getRotation();
        // if we're going +x then heading and course should be opposite.
        if (DEBUG)
            Util.printf(
                    "driveInFieldCoordsVerbatim() target heading %.8f target course %.8f speeds x %.6f y %.6f theta %.6f\n",
                    theta.getRadians(),
                    GeometryUtil.getCourse(targetChassisSpeeds).get().getRadians(),
                    targetChassisSpeeds.vxMetersPerSecond,
                    targetChassisSpeeds.vyMetersPerSecond,
                    targetChassisSpeeds.omegaRadiansPerSecond);

        m_swerveLocal.setChassisSpeedsNormally(targetChassisSpeeds);
    }

    /**
     * True if wheel steering is aligned to the desired motion.
     */
    public boolean aligned(FieldRelativeVelocity v) {
        ChassisSpeeds targetChassisSpeeds = SwerveKinodynamics.toInstantaneousChassisSpeeds(
                v, getPose().getRotation());
        return m_swerveLocal.aligned(targetChassisSpeeds);
    }

    /**
     * steer the wheels to match the target but don't drive them. This is for the
     * beginning of trajectories, like the "square" project or any other case where
     * the new direction happens not to be aligned with the wheels.
     * 
     * @return true if aligned
     */
    @Override
    public void steerAtRest(FieldRelativeVelocity v) {
        // Util.printf("steer at rest v %s\n", v);
        ChassisSpeeds targetChassisSpeeds = SwerveKinodynamics.toInstantaneousChassisSpeeds(
                v, getPose().getRotation());
        m_swerveLocal.steerAtRest(targetChassisSpeeds);
    }

    /**
     * Scales the supplied ChassisSpeed by the driver speed modifier.
     * 
     * Feasibility is enforced by the setpoint generator, if enabled.
     * 
     * @param speeds in robot coordinates
     */
    public void setChassisSpeeds(final ChassisSpeeds speeds) {
        // scale for driver skill; default is half speed.
        DriverSkill.Level driverSkillLevel = DriverSkill.level();
        m_swerveLocal.setChassisSpeeds(speeds.times(driverSkillLevel.scale()));
        m_log_skill.log(() -> driverSkillLevel);
    }

    public void setChassisSpeedsNormally(ChassisSpeeds speeds) {
        m_swerveLocal.setChassisSpeedsNormally(speeds);
    }

    /**
     * Does not desaturate or optimize.
     * 
     * This "raw" mode is just for testing.
     */
    public void setRawModuleStates(SwerveModuleStates states) {
        m_swerveLocal.setRawModuleStates(states);
    }

    /** Make an X, stopped. */
    public void defense() {
        m_swerveLocal.defense();
    }

    /** Wheels ahead, stopped, for testing. */
    public void steer0() {
        m_swerveLocal.steer0();
    }

    /** Wheels at 90 degrees, stopped, for testing. */
    public void steer90() {
        m_swerveLocal.steer90();
    }

    @Override
    public void stop() {
        m_swerveLocal.stop();
    }

    public void resetPose(Pose2d robotPose) {
        Util.warn("Make sure resetting the swerve module collection doesn't break anything");
        m_swerveLocal.reset();
        m_poseEstimator.reset(
                m_gyro,
                m_swerveLocal.positions(),
                robotPose,
                Takt.get());
        m_stateSupplier.reset();
    }

    public void resetSetpoint(SwerveSetpoint setpoint) {
        m_swerveLocal.resetSetpoint(setpoint);
    }

    ///////////////////////////////////////////////////////////////
    //
    // Observers
    //

    /**
     * Cached.
     * 
     * SwerveState representing the drivetrain's field-relative pose, velocity, and
     * acceleration.
     */
    @Override
    public SwerveModel getState() {
        return m_stateSupplier.get();
    }

    public SwerveLocalObserver getSwerveLocal() {
        return m_swerveLocal;
    }

    ///////////////////////////////////////////////////////////////

    /**
     * Periodic() should not do actuation. Let commands do that.
     */
    @Override
    public void periodic() {
        // m_poseEstimator.periodic();
        m_stateSupplier.reset();
        m_log_state.log(this::getState);
        m_log_turning.log(() -> getPose().getRotation().getDegrees());
        m_log_pose_array.log(
                () -> new double[] {
                        getPose().getX(),
                        getPose().getY(),
                        getPose().getRotation().getRadians()
                });

        // Update the Field2d widget
        // the name "field" is used by Field2d.
        // the name "robot" can be anything.
        m_log_field_robot.log(() -> new double[] {
                getPose().getX(),
                getPose().getY(),
                getPose().getRotation().getDegrees()
        });
        m_log_yaw_rate.log(m_gyro::getYawRateNWU);
        m_swerveLocal.periodic();
    }

    public void close() {
        m_swerveLocal.close();
    }

    /////////////////////////////////////////////////////////////////

    /** used by the supplier */
    private SwerveModel update() {
        double now = Takt.get();
        m_poseEstimator.put(
                now,
                m_gyro,
                m_swerveLocal.positions());
        m_cameras.update();
        SwerveModel swerveModel = m_poseEstimator.get(now);
        if (DEBUG)
            Util.printf("update() estimated pose: %s\n", swerveModel);
        return swerveModel;
    }

    /** Return cached pose. */
    public Pose2d getPose() {
        return m_stateSupplier.get().pose();
    }

    /** Return cached velocity. */
    public FieldRelativeVelocity getVelocity() {
        return m_stateSupplier.get().velocity();
    }

    /** Return cached speeds. */
    public ChassisSpeeds getChassisSpeeds() {
        return m_stateSupplier.get().chassisSpeeds();
    }

}
