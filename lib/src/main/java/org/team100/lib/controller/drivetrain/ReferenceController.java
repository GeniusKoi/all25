package org.team100.lib.controller.drivetrain;

import org.team100.lib.dashboard.Glassy;
import org.team100.lib.experiments.Experiment;
import org.team100.lib.experiments.Experiments;
import org.team100.lib.motion.drivetrain.DriveSubsystemInterface;
import org.team100.lib.motion.drivetrain.SwerveModel;
import org.team100.lib.motion.drivetrain.kinodynamics.FieldRelativeVelocity;
import org.team100.lib.reference.SwerveReference;
import org.team100.lib.util.Util;

/**
 * Drives based on a reference time series.
 * 
 * If the current reference velocity is zero, this waits a bit for the wheels to
 * align to the next reference direction, eliminating the little wiggle that
 * happens with uncoordinated steer/drive commands.
 * 
 * The lifespan of this object is intended to be a single "playback" of a
 * trajectory, so create it in Command.initialize().
 */
public class ReferenceController implements Glassy {
    private static final boolean DEBUG = false;
    private final DriveSubsystemInterface m_drive;
    private final SwerveController m_controller;
    private final SwerveReference m_reference;
    private final boolean m_verbatim;

    private boolean m_aligned;

    /**
     * Initializes the reference with the current measurement, so you should call
     * this at runtime, not in advance.
     */
    public ReferenceController(
            DriveSubsystemInterface drive,
            SwerveController controller,
            SwerveReference reference,
            boolean verbatim) {
        m_drive = drive;
        m_controller = controller;
        m_reference = reference;
        m_verbatim = verbatim;

        // initialize
        m_controller.reset();
        if (m_drive.getState().velocity().norm() > 0) {
            // keep moving if we're already moving
            m_aligned = true;
        } else {
            m_aligned = false;
        }
        // initialize here so that the "done" state knows about the clock
        m_reference.initialize(m_drive.getState());
        m_drive.resetLimiter();
    }

    public void execute() {
        if (!Experiments.instance.enabled(Experiment.SteerAtRest)) {
            // If the experiment is off, override the aligned flag.
            // TODO: decide whether to keep the "steer at rest" idea. for now, it's off.
            m_aligned = true;
        }
        SwerveModel measurement = m_drive.getState();
        if (!m_aligned) {
            // Haven't started the trajectory yet, so use the references from zero.
            m_reference.initialize(measurement);
        }
        FieldRelativeVelocity fieldRelativeTarget = m_controller.calculate(
                measurement, m_reference.current(), m_reference.next());
        if (!m_aligned && m_drive.aligned(fieldRelativeTarget)) {
            // Not aligned before, but aligned now.
            m_aligned = true;
        }
        if (DEBUG) {
            Util.printf("ReferenceController.execute() measurement %s current %s next %s output %s\n",
                    measurement,
                    m_reference.current(),
                    m_reference.next(),
                    fieldRelativeTarget);
        }
        if (!m_aligned) {
            // Still not aligned, so keep steering.
            m_drive.steerAtRest(fieldRelativeTarget);
        } else {
            // Aligned, so drive normally.
            if (m_verbatim)
                m_drive.driveInFieldCoordsVerbatim(fieldRelativeTarget);
            else
                m_drive.driveInFieldCoords(fieldRelativeTarget);
        }
    }

    /** Trajectory is complete and controller error is within tolerance. */
    public boolean isFinished() {
        return m_reference.done() && m_controller.atReference();
        
    }

    public boolean atReference(){
        return m_controller.atReference();
    }

    /**
     * If you want just the trajectory completion, don't care about the controller
     * error.
     */
    public boolean isDone() {
        return m_reference.done();
    }

    // for testing
    public boolean is_aligned() {
        return m_aligned;
    }
}
